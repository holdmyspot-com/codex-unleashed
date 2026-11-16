use std::cell::Cell;
use std::cell::RefCell;
use std::collections::VecDeque;
use std::io;
use std::time::Duration;

use crate::CaptureFailure;
use crate::CaptureResult;
use crate::CaptureStreamFailure;
use crate::capture::CaptureOperation;
use crate::capture::CaptureReadError;
use crate::capture::CaptureStream;
use crate::capture::collect_output;
use crate::capture::drain_pipe;

fn read_error(operation: CaptureOperation, code: i32) -> CaptureReadError {
    CaptureReadError {
        operation,
        error: io::Error::from_raw_os_error(code),
    }
}

#[test]
#[should_panic(expected = "a capture failure must contain at least one stream failure")]
fn capture_output_failure_constructor_rejects_empty_failures() {
    CaptureFailure::new(
        CaptureResult {
            exit_code: 0,
            stdout: Vec::new(),
            stderr: Vec::new(),
            timed_out: false,
        },
        Vec::<CaptureStreamFailure>::new(),
    );
}

#[test]
fn capture_output_drain_preserves_prefix_after_peek_error() {
    let available = RefCell::new(VecDeque::from([Ok(2), Err(io::Error::other("peek"))]));
    let reads = Cell::new(0);
    let output = drain_pipe(
        || false,
        || available.borrow_mut().pop_front().expect("peek outcome"),
        |buffer, _| {
            buffer[..2].copy_from_slice(b"ok");
            reads.set(reads.get() + 1);
            Ok(2)
        },
        |_| panic!("unfinished pipe should not sleep"),
    );

    assert_eq!(output, b"ok");
    assert_eq!(reads.get(), 1);
}

#[test]
fn capture_output_drain_stops_on_zero_byte_read() {
    let available = Cell::new(1);
    let reads = Cell::new(0);
    let finished = Cell::new(false);
    let output = drain_pipe(
        || {
            let was_finished = finished.get();
            finished.set(true);
            was_finished
        },
        || Ok(available.replace(0)),
        |_, _| {
            reads.set(reads.get() + 1);
            Ok(0)
        },
        |_| panic!("available bytes should not sleep"),
    );

    assert!(output.is_empty());
    assert_eq!(reads.get(), 1);
}

#[test]
fn capture_output_zero_byte_read_then_more_bytes() {
    let available = RefCell::new(VecDeque::from([1, 1, 0]));
    let finished = Cell::new(false);
    let reads = Cell::new(0);
    let mut output = Vec::new();
    let result = crate::capture::drain_pipe_into(
        &mut output,
        || finished.get(),
        || Ok(available.borrow_mut().pop_front().expect("peek outcome")),
        |buffer, _| {
            if reads.replace(reads.get() + 1) == 0 {
                finished.set(true);
                Ok(0)
            } else {
                buffer[0] = b'x';
                Ok(1)
            }
        },
        |_| panic!("available bytes should not sleep"),
        |_| false,
    );

    assert!(result.is_ok());
    assert_eq!(output, b"x");
}

#[test]
fn capture_output_drain_limits_completed_pipe_to_initial_snapshot() {
    let available = RefCell::new(VecDeque::from([4, 8, 8]));
    let finished = Cell::new(true);
    let read_count = Cell::new(0);
    let read_offset = Cell::new(0);
    let output = drain_pipe(
        || finished.get(),
        || Ok(available.borrow_mut().pop_front().expect("peek outcome")),
        |buffer, requested| {
            read_count.set(read_count.get() + 1);
            let count = requested.min(2) as usize;
            let offset = read_offset.get();
            buffer[..count].copy_from_slice(&b"abcdef"[offset..offset + count]);
            read_offset.set(offset + count);
            Ok(count)
        },
        |_| panic!("finished pipe should not sleep"),
    );

    assert_eq!(output, b"abcd");
    assert_eq!(read_count.get(), 2);
}

#[test]
fn capture_output_completed_empty_pipe_is_eof() {
    let finished = Cell::new(true);
    let output = drain_pipe(
        || finished.get(),
        || Ok(0),
        |_, _| panic!("empty completed pipe should not read"),
        |_| panic!("empty completed pipe should not sleep"),
    );

    assert!(output.is_empty());
}

#[test]
fn capture_output_drain_waits_for_data_before_completion() {
    let available = RefCell::new(VecDeque::from([0, 3, 0]));
    let finished = Cell::new(false);
    let sleeps = Cell::new(0);
    let output = drain_pipe(
        || {
            if sleeps.get() > 0 {
                finished.set(true);
            }
            finished.get()
        },
        || Ok(available.borrow_mut().pop_front().expect("peek outcome")),
        |buffer, _| {
            buffer[..3].copy_from_slice(b"hey");
            Ok(3)
        },
        |duration: Duration| {
            assert_eq!(duration, Duration::from_millis(5));
            sleeps.set(sleeps.get() + 1);
        },
    );

    assert_eq!(output, b"hey");
    assert_eq!(sleeps.get(), 1);
}

#[test]
fn capture_output_stdout_peek_failure_retains_both_prefixes() {
    let failure = match collect_output(
        |stdout| {
            stdout.extend_from_slice(b"out-prefix");
            Err(read_error(CaptureOperation::Peek, 5))
        },
        |stderr| {
            stderr.extend_from_slice(b"err-prefix");
            Ok(())
        },
        || (7, false),
    ) {
        Err(failure) => failure,
        Ok(_) => panic!("stdout peek failure is preserved"),
    };

    assert_eq!(failure.output.exit_code, 7);
    assert!(!failure.output.timed_out);
    assert_eq!(failure.output.stdout, b"out-prefix");
    assert_eq!(failure.output.stderr, b"err-prefix");
    assert_eq!(failure.failures()[0].stream, CaptureStream::Stdout);
    assert_eq!(failure.failures()[0].operation, CaptureOperation::Peek);
}

#[test]
fn capture_output_stderr_peek_failure_retains_both_prefixes() {
    let failure = match collect_output(
        |stdout| {
            stdout.extend_from_slice(b"out");
            Ok(())
        },
        |stderr| {
            stderr.extend_from_slice(b"err");
            Err(read_error(CaptureOperation::Peek, 6))
        },
        || (0, false),
    ) {
        Err(failure) => failure,
        Ok(_) => panic!("stderr peek failure is preserved"),
    };

    assert_eq!(failure.output.stdout, b"out");
    assert_eq!(failure.output.stderr, b"err");
    assert_eq!(failure.failures()[0].stream, CaptureStream::Stderr);
    assert_eq!(failure.failures()[0].operation, CaptureOperation::Peek);
}

#[test]
fn capture_output_stdout_read_failure_retains_both_prefixes() {
    let failure = match collect_output(
        |stdout| {
            stdout.extend_from_slice(b"out");
            Err(read_error(CaptureOperation::Read, 5))
        },
        |stderr| {
            stderr.extend_from_slice(b"err");
            Ok(())
        },
        || (0, false),
    ) {
        Err(failure) => failure,
        Ok(_) => panic!("stdout read failure is preserved"),
    };

    assert_eq!(failure.output.stdout, b"out");
    assert_eq!(failure.output.stderr, b"err");
    assert_eq!(failure.failures()[0].stream, CaptureStream::Stdout);
    assert_eq!(failure.failures()[0].operation, CaptureOperation::Read);
}

#[test]
fn capture_output_stderr_read_failure_retains_both_prefixes() {
    let failure = match collect_output(
        |stdout| {
            stdout.extend_from_slice(b"out");
            Ok(())
        },
        |stderr| {
            stderr.extend_from_slice(b"err");
            Err(read_error(CaptureOperation::Read, 6))
        },
        || (0, false),
    ) {
        Err(failure) => failure,
        Ok(_) => panic!("stderr read failure is preserved"),
    };

    assert_eq!(failure.output.stdout, b"out");
    assert_eq!(failure.output.stderr, b"err");
    assert_eq!(failure.failures()[0].stream, CaptureStream::Stderr);
    assert_eq!(failure.failures()[0].operation, CaptureOperation::Read);
}

#[test]
fn capture_output_panic_after_prefix_retains_both_streams() {
    let failure = match collect_output(
        |stdout| {
            stdout.extend_from_slice(b"before panic");
            panic!("private panic details");
        },
        |stderr| {
            stderr.extend_from_slice(b"still joined");
            Ok(())
        },
        || (9, false),
    ) {
        Err(failure) => failure,
        Ok(_) => panic!("reader panic is preserved"),
    };

    assert_eq!(failure.output.stdout, b"before panic");
    assert_eq!(failure.output.stderr, b"still joined");
    assert_eq!(failure.failures()[0].stream, CaptureStream::Stdout);
    assert_eq!(failure.failures()[0].operation, CaptureOperation::Panicked);
    let diagnostic = format!("{failure}");
    assert!(!diagnostic.contains("private panic details"));
    let debug = format!("{failure:?}");
    assert!(!debug.contains("before panic"));
    assert!(!debug.contains("still joined"));
    assert!(!debug.contains("private panic details"));
}

#[test]
fn capture_output_both_reader_failures_are_collected() {
    let failure = match collect_output(
        |stdout| {
            stdout.extend_from_slice(b"out");
            Err(read_error(CaptureOperation::Peek, 5))
        },
        |stderr| {
            stderr.extend_from_slice(b"err");
            Err(read_error(CaptureOperation::Read, 6))
        },
        || (0, true),
    ) {
        Err(failure) => failure,
        Ok(_) => panic!("both reader failures are preserved"),
    };

    assert_eq!(failure.output.stdout, b"out");
    assert_eq!(failure.output.stderr, b"err");
    assert!(failure.output.timed_out);
    assert_eq!(failure.failures().len(), 2);
    assert!(failure.failures().iter().any(|failure| {
        failure.stream == CaptureStream::Stdout && failure.operation == CaptureOperation::Peek
    }));
    assert!(failure.failures().iter().any(|failure| {
        failure.stream == CaptureStream::Stderr && failure.operation == CaptureOperation::Read
    }));
    assert!(format!("{failure}").contains("process timed out (reported exit code 0)"));
}

#[test]
fn capture_output_success_preserves_exit_metadata_and_streams() {
    let output = collect_output(
        |stdout| {
            stdout.extend_from_slice(b"out");
            Ok(())
        },
        |stderr| {
            stderr.extend_from_slice(b"err");
            Ok(())
        },
        || (23, true),
    )
    .expect("successful readers remain successful");

    assert_eq!(output.exit_code, 23);
    assert!(output.timed_out);
    assert_eq!(output.stdout, b"out");
    assert_eq!(output.stderr, b"err");
}
