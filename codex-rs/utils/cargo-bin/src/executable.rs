//! Creates executable test fixtures without exposing writable descriptors to sibling spawns.
//! On Linux, writes and copies finish in a separate process before the fixture can be launched.

use std::fs;
use std::io::{self, BufRead, BufReader};
use std::path::Path;
#[cfg(target_os = "linux")]
use std::process::Command;

/// Writes a small Unix script fixture and makes it executable with mode 0o755.
#[cfg(unix)]
pub fn write_executable(path: &Path, script: &str) -> io::Result<()> {
    use std::os::unix::fs::PermissionsExt;

    #[cfg(target_os = "linux")]
    {
        // A sibling test can fork while a parent-owned file is open for writing.
        // CLOEXEC does not release that inherited descriptor until the sibling execs.
        let output = Command::new("/bin/sh")
            .arg("-c")
            .arg("printf '%s' \"$1\" > \"$2\"")
            .arg("write-executable-fixture")
            .arg(script)
            .arg(path)
            .output()?;
        if !output.status.success() {
            return Err(io::Error::other(format!(
                "failed to write executable fixture {}: {}",
                path.display(),
                String::from_utf8_lossy(&output.stderr),
            )));
        }
    }
    #[cfg(not(target_os = "linux"))]
    fs::write(path, script)?;
    fs::set_permissions(path, fs::Permissions::from_mode(/*mode*/ 0o755))
}

/// Returns whether copying an executable fixture failed because a running process holds the destination open.
///
/// A sandbox helper can outlive the test that started it. Windows then rejects a copy over its executable with
/// access denied or with a sharing violation.
pub fn is_executable_in_use(error: &io::Error) -> bool {
    // ERROR_SHARING_VIOLATION
    const SHARING_VIOLATION: i32 = 32;
    error.kind() == io::ErrorKind::PermissionDenied
        || (cfg!(windows) && error.raw_os_error() == Some(SHARING_VIOLATION))
}

/// Copies an executable fixture, preserving the source permissions like [`fs::copy`].
pub fn copy_executable(source: &Path, destination: &Path) -> io::Result<()> {
    #[cfg(target_os = "linux")]
    {
        let output = Command::new("/bin/cp")
            .arg("--")
            .arg(source)
            .arg(destination)
            .output()?;
        if !output.status.success() {
            return Err(io::Error::other(format!(
                "failed to copy executable fixture {} to {}: {}",
                source.display(),
                destination.display(),
                String::from_utf8_lossy(&output.stderr),
            )));
        }
        // Match fs::copy even when the test process has a restrictive umask.
        fs::set_permissions(destination, fs::metadata(source)?.permissions())?;
    }
    #[cfg(not(target_os = "linux"))]
    fs::copy(source, destination)?;
    Ok(())
}

/// Stages an executable fixture, reusing it only when its complete contents match the source.
pub fn stage_executable(source: &Path, destination: &Path) -> io::Result<()> {
    stage_executable_with_copy(source, destination, copy_executable)
}

fn stage_executable_with_copy(
    source: &Path,
    destination: &Path,
    copy: impl FnOnce(&Path, &Path) -> io::Result<()>,
) -> io::Result<()> {
    if executable_files_match(source, destination)? {
        return Ok(());
    }

    if let Err(error) = copy(source, destination) {
        if is_executable_in_use(&error)
            && executable_files_match(source, destination).unwrap_or(false)
        {
            return Ok(());
        }
        return Err(error);
    }
    Ok(())
}

fn executable_files_match(source: &Path, destination: &Path) -> io::Result<bool> {
    let source = std::fs::File::open(source)?;
    let destination_metadata = match fs::metadata(destination) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(false),
        Err(error) => return Err(error),
    };
    if !destination_metadata.is_file() {
        return Ok(false);
    }
    let destination = match std::fs::File::open(destination) {
        Ok(file) => file,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(false),
        Err(error) => return Err(error),
    };

    let mut source = BufReader::new(source);
    let mut destination = BufReader::new(destination);
    loop {
        let source_bytes = source.fill_buf()?;
        let destination_bytes = destination.fill_buf()?;
        if source_bytes.is_empty() || destination_bytes.is_empty() {
            return Ok(source_bytes.is_empty() && destination_bytes.is_empty());
        }
        let compared = source_bytes.len().min(destination_bytes.len());
        if source_bytes[..compared] != destination_bytes[..compared] {
            return Ok(false);
        }
        source.consume(compared);
        destination.consume(compared);
    }
}

#[cfg(test)]
mod tests {
    use super::{stage_executable, stage_executable_with_copy};
    use std::io;
    use std::path::{Path, PathBuf};
    use std::sync::atomic::{AtomicU64, Ordering};

    static NEXT_TEMP_DIR: AtomicU64 = AtomicU64::new(0);

    struct TempDir(PathBuf);

    impl TempDir {
        fn new() -> io::Result<Self> {
            let path = std::env::temp_dir().join(format!(
                "codex-helper-identity-{}-{}",
                std::process::id(),
                NEXT_TEMP_DIR.fetch_add(1, Ordering::Relaxed)
            ));
            std::fs::create_dir(&path)?;
            Ok(Self(path))
        }

        fn path(&self) -> &Path {
            &self.0
        }
    }

    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    #[cfg(windows)]
    #[test]
    fn reuses_helper_while_windows_denies_replacement() -> io::Result<()> {
        use std::os::windows::fs::OpenOptionsExt;

        let dir = TempDir::new()?;
        let source = dir.path().join("source.exe");
        let destination = dir.path().join("helper.exe");
        std::fs::write(&source, b"expected helper")?;
        std::fs::write(&destination, b"expected helper")?;
        let locked = std::fs::OpenOptions::new()
            .read(true)
            .share_mode(/* FILE_SHARE_READ */ 1)
            .open(&destination)?;
        assert_eq!(
            std::fs::copy(&source, &destination)
                .unwrap_err()
                .raw_os_error(),
            Some(32)
        );
        stage_executable(&source, &destination)?;
        assert_eq!(std::fs::read(&destination)?, b"expected helper");
        drop(locked);
        Ok(())
    }

    #[test]
    fn copies_missing_helper() -> io::Result<()> {
        let dir = TempDir::new()?;
        let source = dir.path().join("source.exe");
        let destination = dir.path().join("helper.exe");
        std::fs::write(&source, b"expected helper")?;
        stage_executable_with_copy(&source, &destination, |source, destination| {
            std::fs::copy(source, destination).map(|_| ())
        })?;
        assert_eq!(std::fs::read(destination)?, b"expected helper");
        Ok(())
    }

    #[test]
    fn reuses_identical_helper_without_opening_it_for_write() -> io::Result<()> {
        let dir = TempDir::new()?;
        let source = dir.path().join("source.exe");
        let destination = dir.path().join("helper.exe");
        std::fs::write(&source, b"expected helper")?;
        std::fs::write(&destination, b"expected helper")?;
        stage_executable_with_copy(&source, &destination, |_, _| {
            panic!("an identical helper must not be reopened for writing")
        })?;
        Ok(())
    }

    #[test]
    fn reuses_identical_empty_helper_without_opening_it_for_write() -> io::Result<()> {
        let dir = TempDir::new()?;
        let source = dir.path().join("source.exe");
        let destination = dir.path().join("helper.exe");
        std::fs::write(&source, b"")?;
        std::fs::write(&destination, b"")?;
        stage_executable_with_copy(&source, &destination, |_, _| {
            panic!("an identical empty helper must not be reopened for writing")
        })?;
        Ok(())
    }

    #[test]
    fn permission_denied_rejects_stale_helper() -> io::Result<()> {
        let dir = TempDir::new()?;
        let source = dir.path().join("source.exe");
        let destination = dir.path().join("helper.exe");
        std::fs::write(&source, b"expected helper")?;
        std::fs::write(&destination, b"different helper")?;
        let result = stage_executable_with_copy(&source, &destination, |_, _| {
            Err(io::Error::from(io::ErrorKind::PermissionDenied))
        });
        assert_eq!(result.unwrap_err().kind(), io::ErrorKind::PermissionDenied);
        assert_eq!(std::fs::read(destination)?, b"different helper");
        Ok(())
    }

    #[cfg(windows)]
    #[test]
    fn sharing_violation_accepts_identical_concurrent_publication() -> io::Result<()> {
        let dir = TempDir::new()?;
        let source = dir.path().join("source.exe");
        let destination = dir.path().join("helper.exe");
        std::fs::write(&source, b"expected helper")?;
        stage_executable_with_copy(&source, &destination, |source, destination| {
            // Model the recorded Windows OS32 result after another stager finishes.
            std::fs::copy(source, destination)?;
            Err(io::Error::from_raw_os_error(32))
        })?;
        assert_eq!(std::fs::read(destination)?, b"expected helper");
        Ok(())
    }

    #[cfg(windows)]
    #[test]
    fn sharing_violation_rejects_different_helper() -> io::Result<()> {
        let dir = TempDir::new()?;
        let source = dir.path().join("source.exe");
        let destination = dir.path().join("helper.exe");
        std::fs::write(&source, b"expected helper")?;
        std::fs::write(&destination, b"different helper")?;
        let result = stage_executable_with_copy(&source, &destination, |_, _| {
            Err(io::Error::from_raw_os_error(32))
        });
        assert_eq!(result.unwrap_err().raw_os_error(), Some(32));
        assert_eq!(std::fs::read(destination)?, b"different helper");
        Ok(())
    }

    #[cfg(windows)]
    #[test]
    fn sharing_violation_rejects_different_length_helper() -> io::Result<()> {
        let dir = TempDir::new()?;
        let source = dir.path().join("source.exe");
        let destination = dir.path().join("helper.exe");
        std::fs::write(&source, b"expected helper")?;
        std::fs::write(&destination, b"expected")?;
        let result = stage_executable_with_copy(&source, &destination, |_, _| {
            Err(io::Error::from_raw_os_error(32))
        });
        assert_eq!(result.unwrap_err().raw_os_error(), Some(32));
        assert_eq!(std::fs::read(destination)?, b"expected");
        Ok(())
    }

    #[cfg(windows)]
    #[test]
    fn sharing_violation_rejects_missing_helper() -> io::Result<()> {
        let dir = TempDir::new()?;
        let source = dir.path().join("source.exe");
        let destination = dir.path().join("helper.exe");
        std::fs::write(&source, b"expected helper")?;
        let result = stage_executable_with_copy(&source, &destination, |_, _| {
            Err(io::Error::from_raw_os_error(32))
        });
        assert_eq!(result.unwrap_err().raw_os_error(), Some(32));
        assert!(!destination.exists());
        Ok(())
    }

    #[cfg(windows)]
    #[test]
    fn sharing_violation_checks_bytes_beyond_first_buffer() -> io::Result<()> {
        let dir = TempDir::new()?;
        let source = dir.path().join("source.exe");
        let destination = dir.path().join("helper.exe");
        let expected = vec![0u8; 32769];
        let mut different = expected.clone();
        different[32768] = 1;
        std::fs::write(&source, &expected)?;
        std::fs::write(&destination, &different)?;
        let result = stage_executable_with_copy(&source, &destination, |_, _| {
            Err(io::Error::from_raw_os_error(32))
        });
        assert_eq!(result.unwrap_err().raw_os_error(), Some(32));
        Ok(())
    }

    #[test]
    fn permission_denied_rejects_directory_destination() -> io::Result<()> {
        let dir = TempDir::new()?;
        let source = dir.path().join("source.exe");
        let destination = dir.path().join("helper.exe");
        std::fs::write(&source, b"expected helper")?;
        std::fs::create_dir(&destination)?;
        assert!(
            stage_executable_with_copy(&source, &destination, |_, _| {
                Err(io::Error::from(io::ErrorKind::PermissionDenied))
            })
            .is_err()
        );
        Ok(())
    }

    #[test]
    fn unrelated_copy_failure_is_preserved() -> io::Result<()> {
        let dir = TempDir::new()?;
        let source = dir.path().join("source.exe");
        let destination = dir.path().join("helper.exe");
        std::fs::write(&source, b"expected helper")?;
        let result = stage_executable_with_copy(&source, &destination, |source, destination| {
            std::fs::copy(source, destination)?;
            Err(io::Error::other("copy failed after publication"))
        });
        assert_eq!(
            result.unwrap_err().to_string(),
            "copy failed after publication"
        );
        Ok(())
    }
}
