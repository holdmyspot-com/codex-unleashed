//! Creates executable test fixtures without exposing writable descriptors to sibling spawns.
//! On Linux, writes and copies finish in a separate process before the fixture can be launched.

use std::fs;
use std::io;
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
