use std::fmt;
use std::io;
#[cfg(any(target_os = "windows", test))]
use std::time::Duration;

use crate::CaptureResult;

#[cfg(any(target_os = "windows", test))]
const CAPTURE_BUFFER_SIZE: usize = 8192;
#[cfg(any(target_os = "windows", test))]
const CAPTURE_POLL_INTERVAL: Duration = Duration::from_millis(5);

/// Drains a capture pipe while preserving the legacy completion snapshot rule.
///
/// Errors intentionally stop the drain for now; the caller retains the prefix,
/// matching the legacy capture behavior while the Win32 interaction is made
/// independently testable.
#[cfg(test)]
pub(crate) fn drain_pipe(
    mut is_finished: impl FnMut() -> bool,
    mut peek_available: impl FnMut() -> io::Result<u32>,
    mut read: impl FnMut(&mut [u8], u32) -> io::Result<usize>,
    mut sleep: impl FnMut(Duration),
) -> Vec<u8> {
    let mut output = Vec::new();
    let mut buffer = [0_u8; CAPTURE_BUFFER_SIZE];
    let mut remaining = None;

    loop {
        let has_finished = is_finished();
        let available = match peek_available() {
            Ok(available) => available,
            Err(_) => break,
        };
        if has_finished && remaining.is_none() {
            remaining = Some(available);
        }
        let to_read = available.min(remaining.unwrap_or(available));
        if to_read == 0 {
            if has_finished {
                break;
            }
            sleep(CAPTURE_POLL_INTERVAL);
            continue;
        }
        let read_limit = to_read.min(buffer.len() as u32);
        let read_bytes = match read(&mut buffer, read_limit) {
            Ok(read_bytes) if read_bytes > 0 => read_bytes,
            _ => break,
        };
        output.extend_from_slice(&buffer[..read_bytes]);
        if let Some(remaining) = remaining.as_mut() {
            *remaining -= read_bytes as u32;
        }
    }

    output
}

#[cfg(any(target_os = "windows", test))]
pub(crate) fn drain_pipe_into(
    output: &mut Vec<u8>,
    mut is_finished: impl FnMut() -> bool,
    mut peek_available: impl FnMut() -> io::Result<u32>,
    mut read: impl FnMut(&mut [u8], u32) -> io::Result<usize>,
    mut sleep: impl FnMut(Duration),
    mut is_broken_pipe: impl FnMut(&io::Error) -> bool,
) -> Result<(), CaptureReadError> {
    let mut buffer = [0_u8; CAPTURE_BUFFER_SIZE];
    let mut remaining = None;

    loop {
        let has_finished = is_finished();
        let available = match peek_available() {
            Ok(available) => available,
            Err(error) if is_broken_pipe(&error) => return Ok(()),
            Err(error) => {
                return Err(CaptureReadError {
                    operation: CaptureOperation::Peek,
                    error,
                });
            }
        };
        if has_finished && remaining.is_none() {
            remaining = Some(available);
        }
        let to_read = available.min(remaining.unwrap_or(available));
        if to_read == 0 {
            if has_finished {
                break;
            }
            sleep(CAPTURE_POLL_INTERVAL);
            continue;
        }
        let read_limit = to_read.min(buffer.len() as u32);
        let read_bytes = match read(&mut buffer, read_limit) {
            Ok(read_bytes) if read_bytes > 0 => read_bytes,
            Ok(_) => continue,
            Err(error) if is_broken_pipe(&error) => return Ok(()),
            Err(error) => {
                return Err(CaptureReadError {
                    operation: CaptureOperation::Read,
                    error,
                });
            }
        };
        output.extend_from_slice(&buffer[..read_bytes]);
        if let Some(remaining) = remaining.as_mut() {
            *remaining -= read_bytes as u32;
        }
    }

    Ok(())
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CaptureStream {
    Stdout,
    Stderr,
}

impl fmt::Display for CaptureStream {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Stdout => "stdout",
            Self::Stderr => "stderr",
        })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CaptureOperation {
    Peek,
    Read,
    Panicked,
}

impl fmt::Display for CaptureOperation {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Peek => "peek",
            Self::Read => "read",
            Self::Panicked => "reader panicked",
        })
    }
}

pub struct CaptureStreamFailure {
    pub stream: CaptureStream,
    pub operation: CaptureOperation,
    pub error: Option<io::Error>,
}

impl fmt::Debug for CaptureStreamFailure {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let mut debug = f.debug_struct("CaptureStreamFailure");
        debug.field("stream", &self.stream);
        debug.field("operation", &self.operation);
        if let Some(code) = self.error.as_ref().and_then(io::Error::raw_os_error) {
            debug.field("win32_error_code", &code);
        }
        debug.finish()
    }
}

pub struct CaptureFailure {
    pub output: CaptureResult,
    failures: Vec<CaptureStreamFailure>,
}

impl CaptureFailure {
    /// Creates a capture failure for a process whose output readers did not all complete.
    ///
    /// # Panics
    ///
    /// Panics when `failures` is empty.
    pub fn new(output: CaptureResult, failures: Vec<CaptureStreamFailure>) -> Self {
        assert!(
            !failures.is_empty(),
            "a capture failure must contain at least one stream failure"
        );
        Self { output, failures }
    }

    pub fn failures(&self) -> &[CaptureStreamFailure] {
        &self.failures
    }
}

impl fmt::Debug for CaptureFailure {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("CaptureFailure")
            .field("exit_code", &self.output.exit_code)
            .field("timed_out", &self.output.timed_out)
            .field("failures", &self.failures)
            .finish()
    }
}

impl fmt::Display for CaptureFailure {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        for (index, failure) in self.failures.iter().enumerate() {
            if index > 0 {
                f.write_str("; ")?;
            }
            write!(f, "{} capture {} failed", failure.stream, failure.operation)?;
            if let Some(code) = failure.error.as_ref().and_then(io::Error::raw_os_error) {
                write!(f, " (Win32 error {code})")?;
            }
        }
        if self.output.timed_out {
            write!(
                f,
                "; process timed out (reported exit code {})",
                self.output.exit_code
            )
        } else {
            write!(f, "; child exit code {}", self.output.exit_code)
        }
    }
}

impl std::error::Error for CaptureFailure {}

#[cfg(any(target_os = "windows", test))]
pub(crate) struct CaptureReadError {
    pub operation: CaptureOperation,
    pub error: io::Error,
}

#[cfg(any(target_os = "windows", test))]
pub(crate) fn collect_output(
    read_stdout: impl FnOnce(&mut Vec<u8>) -> Result<(), CaptureReadError> + Send,
    read_stderr: impl FnOnce(&mut Vec<u8>) -> Result<(), CaptureReadError> + Send,
    run_process: impl FnOnce() -> (i32, bool),
) -> Result<CaptureResult, CaptureFailure> {
    let mut stdout = Vec::new();
    let mut stderr = Vec::new();
    let ((exit_code, timed_out), stdout_result, stderr_result) = std::thread::scope(|scope| {
        let stdout_reader = scope.spawn(|| read_stdout(&mut stdout));
        let stderr_reader = scope.spawn(|| read_stderr(&mut stderr));
        let process_result = run_process();
        let stdout_result = stdout_reader.join();
        let stderr_result = stderr_reader.join();
        (process_result, stdout_result, stderr_result)
    });

    let output = CaptureResult {
        exit_code,
        stdout,
        stderr,
        timed_out,
    };
    let mut failures = Vec::new();
    collect_failure(&mut failures, CaptureStream::Stdout, stdout_result);
    collect_failure(&mut failures, CaptureStream::Stderr, stderr_result);
    if failures.is_empty() {
        Ok(output)
    } else {
        Err(CaptureFailure::new(output, failures))
    }
}

#[cfg(any(target_os = "windows", test))]
fn collect_failure(
    failures: &mut Vec<CaptureStreamFailure>,
    stream: CaptureStream,
    result: std::thread::Result<Result<(), CaptureReadError>>,
) {
    let failure = match result {
        Ok(Ok(())) => return,
        Ok(Err(CaptureReadError { operation, error })) => CaptureStreamFailure {
            stream,
            operation,
            error: Some(error),
        },
        Err(_) => CaptureStreamFailure {
            stream,
            operation: CaptureOperation::Panicked,
            error: None,
        },
    };
    failures.push(failure);
}
