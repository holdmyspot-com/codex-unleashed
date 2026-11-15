"""Archive writers for canonical Codex package directories."""

import argparse
import gzip
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile
from collections.abc import Callable
from pathlib import Path

from .targets import REPO_ROOT


ZSTD_DOTSLASH = REPO_ROOT / ".github" / "workflows" / "zstd"

# The fixed Unix epoch keeps gzip and tar headers independent of build time.
ARCHIVE_EPOCH = 0


def write_archive(package_dir: Path, archive_path: Path, *, force: bool) -> None:
    if is_relative_to(archive_path, package_dir):
        raise RuntimeError(
            f"Archive output must be outside the package directory: {archive_path}"
        )

    archive_path.parent.mkdir(parents=True, exist_ok=True)
    if archive_path.exists():
        if not force:
            raise RuntimeError(f"Archive output already exists: {archive_path}")
        archive_path.unlink()

    archive_format = archive_format_for_path(archive_path)
    if archive_format == "tar.gz":
        write_tar_archive(package_dir, archive_path, mode="w:gz")
    elif archive_format == "tar.zst":
        write_tar_zst_archive(package_dir, archive_path)
    elif archive_format == "zip":
        write_zip_archive(package_dir, archive_path)
    else:
        raise AssertionError(f"unexpected archive format: {archive_format}")


def is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def archive_format_for_path(path: Path) -> str:
    suffixes = path.suffixes
    if suffixes[-2:] == [".tar", ".gz"] or path.suffix == ".tgz":
        return "tar.gz"
    if suffixes[-2:] == [".tar", ".zst"]:
        return "tar.zst"
    if path.suffix == ".zip":
        return "zip"
    raise RuntimeError(
        f"Unsupported archive suffix for {path}. Use .tar.gz, .tgz, .tar.zst, or .zip."
    )


def write_tar_archive(package_dir: Path, archive_path: Path, *, mode: str) -> None:
    archive_format = tarfile.PAX_FORMAT
    if mode == "w:gz":
        with archive_path.open("wb") as output_file:
            with gzip.GzipFile(
                fileobj=output_file,
                filename="",
                mode="wb",
                mtime=ARCHIVE_EPOCH,
                compresslevel=6,
            ) as compressed_file:
                with tarfile.open(
                    fileobj=compressed_file,
                    mode="w|",
                    format=archive_format,
                ) as archive:
                    add_normalized_archive_entries(archive, package_dir)
    else:
        with tarfile.open(archive_path, mode, format=archive_format) as archive:
            add_normalized_archive_entries(archive, package_dir)


def add_normalized_archive_entries(archive: tarfile.TarFile, package_dir: Path) -> None:
    for path in package_entries(package_dir):
        archive.add(
            path,
            arcname=path.relative_to(package_dir).as_posix(),
            recursive=False,
            filter=normalize_archive_member,
        )


def normalize_archive_member(tar_info: tarfile.TarInfo) -> tarfile.TarInfo:
    is_supported_archive_entry = (
        tar_info.isreg() or tar_info.isdir() or tar_info.issym()
    )
    if is_supported_archive_entry:
        is_windows_executable = tar_info.name.lower().endswith(".exe")
        has_execute_bit = bool(tar_info.mode & 0o111)
        is_executable = has_execute_bit or is_windows_executable
        if tar_info.isdir():
            tar_info.mode = 0o755
        elif tar_info.issym():
            tar_info.mode = 0o777
        else:
            tar_info.mode = 0o755 if is_executable else 0o644

        tar_info.uid = tar_info.gid = 0
        tar_info.uname = tar_info.gname = ""
        tar_info.mtime = ARCHIVE_EPOCH
        tar_info.pax_headers = {}
        return tar_info

    raise RuntimeError(
        f"Unsupported archive entry type for {tar_info.name}; only regular files, "
        "directories, and symlinks are supported."
    )


def write_tar_zst_archive(package_dir: Path, archive_path: Path) -> None:
    zstd_command = resolve_zstd_command()

    with tempfile.TemporaryDirectory(prefix="codex-package-archive-") as temp_dir_str:
        tar_path = Path(temp_dir_str) / "package.tar"
        write_tar_archive(package_dir, tar_path, mode="w")
        subprocess.check_call(
            [*zstd_command, "-T0", "-19", "-f", str(tar_path), "-o", str(archive_path)]
        )


def resolve_zstd_command(
    *,
    dotslash_manifest: Path = ZSTD_DOTSLASH,
    which: Callable[[str], str | None] = shutil.which,
) -> list[str]:
    zstd = which("zstd")
    if zstd is not None:
        return [zstd]

    dotslash = which("dotslash")
    if dotslash is not None and dotslash_manifest.is_file():
        return [dotslash, str(dotslash_manifest)]

    raise RuntimeError(
        "zstd is required to write .tar.zst archives. Install zstd, or install "
        f"DotSlash so the repository wrapper can run: {dotslash_manifest}"
    )


def write_zip_archive(package_dir: Path, archive_path: Path) -> None:
    with zipfile.ZipFile(
        archive_path, "w", compression=zipfile.ZIP_DEFLATED
    ) as archive:
        for path in package_entries(package_dir):
            relative_path = path.relative_to(package_dir)
            if path.is_dir():
                archive.write(path, f"{relative_path}/")
            else:
                archive.write(path, relative_path)


def package_entries(package_dir: Path) -> list[Path]:
    return sorted(
        package_dir.rglob("*"),
        key=lambda path: path.relative_to(package_dir).as_posix(),
    )


def main() -> int:
    """Write an archive or print the resolved zstd argv for a module caller.

    Successful writes are silent; resolve-zstd prints one JSON array and LF.
    Invalid arguments exit 2. Archive or resolver failures report to stderr
    and return 1; successful operations return 0. --force replaces an existing
    archive using the writer's normal replacement behavior.
    """
    parser = argparse.ArgumentParser(
        description="Write canonical package archives using the package writer."
    )
    commands = parser.add_subparsers(dest="command", required=True)
    write = commands.add_parser("write", help="Write an archive from a package tree.")
    write.add_argument("--package-dir", type=Path, required=True)
    write.add_argument("--archive-output", type=Path, required=True)
    write.add_argument("--force", action="store_true")
    commands.add_parser("resolve-zstd", help="Print the resolved zstd argument vector.")
    args = parser.parse_args()

    try:
        if args.command == "write":
            write_archive_silently(
                args.package_dir, args.archive_output, force=args.force
            )
        else:
            print(json.dumps(resolve_zstd_command()))
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Package archive operation failed: {error}", file=sys.stderr)
        return 1
    return 0


def write_archive_silently(
    package_dir: Path, archive_path: Path, *, force: bool
) -> None:
    """Keep compressor progress off the module command's successful output."""
    sys.stdout.flush()
    sys.stderr.flush()
    saved_stdout = os.dup(sys.stdout.fileno())
    saved_stderr = os.dup(sys.stderr.fileno())
    null_output = os.open(os.devnull, os.O_WRONLY)
    try:
        os.dup2(null_output, sys.stdout.fileno())
        os.dup2(null_output, sys.stderr.fileno())
        write_archive(package_dir, archive_path, force=force)
    finally:
        os.dup2(saved_stdout, sys.stdout.fileno())
        os.dup2(saved_stderr, sys.stderr.fileno())
        os.close(saved_stdout)
        os.close(saved_stderr)
        os.close(null_output)


if __name__ == "__main__":
    sys.exit(main())
