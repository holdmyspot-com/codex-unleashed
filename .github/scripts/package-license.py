"""Stage supplied legal documents and verify their bytes in canonical package archives."""

from pathlib import Path, PurePosixPath
import argparse
from contextlib import AbstractContextManager, contextmanager
import os
import sys
import subprocess
import tarfile
import tempfile
from typing import BinaryIO, Callable

# These repository documents include the entire agreement and its referenced local copies.
DOWNSTREAM_DOCUMENTS = ("LICENSE.md", "docs/LICENSE.html", "docs/terms.html", "docs/privacy.html",
                        "licenses/openai-codex/LICENSE-APACHE-2.0")
# Supplied notices at the pinned upstream package source revision; keep their source paths.
UPSTREAM_NOTICES = (
    "LICENSE", "NOTICE",
    "codex-rs/skills/src/assets/samples/imagegen/LICENSE.txt",
    "codex-rs/skills/src/assets/samples/openai-docs/LICENSE.txt",
    "codex-rs/skills/src/assets/samples/skill-creator/license.txt",
    "codex-rs/skills/src/assets/samples/skill-installer/LICENSE.txt",
    "codex-rs/vendor/bubblewrap/COPYING",
    "third_party/voice/NOTICE.md", "third_party/wezterm/LICENSE"
)
# Keep bundled agreement-relative links intact inside the existing package resources boundary.
LEGAL_DIRECTORY = PurePosixPath("codex-resources/legal")
# Legal text is bounded; binaries are always consumed through tarfile's streaming reader.
MAX_DOCUMENT_BYTES = 2 * 1024 * 1024
MAX_LEGAL_FILES = 64
MAX_ARCHIVE_MEMBERS = 10000
PACKAGE_VARIANTS = ("codex", "codex-app-server")
ARCHIVE_SUFFIXES = (".tar.gz", ".tar.zst")


def supplied_documents(metadata_root: Path, upstream_root: Path) -> dict[str, bytes]:
    """Read required legal sources, rejecting absent, empty, linked or oversized documents.

    Return package-relative paths and complete bytes. Metadata supplies downstream legal
    documents and licenses; upstream supplies the pinned list of original legal notices.
    """
    sources = [(path, metadata_root / path) for path in DOWNSTREAM_DOCUMENTS]
    sources.extend((f"licenses/upstream/{path}", upstream_root / path) for path in UPSTREAM_NOTICES)
    for path in (metadata_root / "licenses").rglob("*"):
        if path.is_file():
            sources.append((path.relative_to(metadata_root).as_posix(), path))
            if len(sources) > MAX_LEGAL_FILES:
                raise ValueError(f"Legal sources exceed {MAX_LEGAL_FILES} documents")
    documents = {}
    for relative, source in sources:
        if source.is_symlink() or not source.is_file():
            raise ValueError(f"Missing regular legal source: {source}")
        size = source.stat().st_size
        if size == 0 or size > MAX_DOCUMENT_BYTES:
            raise ValueError(f"Legal source must contain 1..{MAX_DOCUMENT_BYTES} bytes: {source}")
        name = (LEGAL_DIRECTORY / relative).as_posix()
        content = source.read_bytes()
        if name in documents and documents[name] != content:
            raise ValueError(f"Conflicting legal sources for: {name}")
        documents[name] = content
    return documents


def stage_documents(package_dir: Path, documents: dict[str, bytes]) -> None:
    """Add supplied legal documents to an existing canonical package directory.

    Preserve every existing package file; reject linked destinations and differing
    existing legal bytes before writing. Equal legal bytes permit an idempotent retry.
    """
    if not (package_dir / "codex-package.json").is_file():
        raise ValueError(f"Missing canonical package metadata: {package_dir / 'codex-package.json'}")
    for relative, content in documents.items():
        destination = package_dir / relative
        for part in [destination, *destination.parents]:
            if part == package_dir.parent:
                break
            if part.is_symlink():
                raise ValueError(f"Linked legal destination: {part}")
        if destination.exists():
            if destination.stat().st_size != len(content) or destination.read_bytes() != content:
                raise ValueError(f"Existing legal document differs: {destination}")
    for relative, content in documents.items():
        destination = package_dir / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(content)


def check_archive(stream, documents: dict[str, bytes]) -> None:
    """Verify supplied legal bytes in a gzip or uncompressed tar stream without extraction.

    Reject missing/mismatched documents, duplicate or noncanonical member paths, links
    and special files, and archives with over 10,000 members. Other regular package
    files and directories are retained and accepted, including existing notices.
    """
    seen = set()
    remaining = set(documents)
    with tarfile.open(fileobj=stream, mode="r|*") as archive:
        for member in archive:
            name = member.name.rstrip("/")
            if len(seen) >= MAX_ARCHIVE_MEMBERS:
                raise ValueError(f"Archive exceeds {MAX_ARCHIVE_MEMBERS} members")
            parts = name.split("/")
            if name == "" or any(part in ("", ".", "..") for part in parts) or "\\" in name or ":" in name:
                raise ValueError(f"Unsafe archive member path: {member.name!r}")
            if name in seen:
                raise ValueError(f"Duplicate archive member: {name}")
            seen.add(name)
            if not (member.isfile() or member.isdir()):
                raise ValueError(f"Archive member is not a regular file or directory: {name}")
            if name in documents:
                expected = documents[name]
                if not member.isfile() or member.size != len(expected):
                    raise ValueError(f"Legal document differs: {name}")
                with archive.extractfile(member) as source:
                    if source.read(MAX_DOCUMENT_BYTES + 1) != expected:
                        raise ValueError(f"Legal document differs: {name}")
                remaining.remove(name)
    if remaining:
        raise ValueError(f"Missing legal documents: {', '.join(sorted(remaining))}")


def finalize_packages(
    metadata_root: Path,
    upstream_root: Path,
    package_root: Path,
    archive_root: Path,
    target: str,
    *,
    write_archive: Callable[..., None],
    decompress_zstd: Callable[[Path], AbstractContextManager[BinaryIO]],
) -> int:
    """Stage legal documents in both canonical packages and write and verify four archives.

    Package directories and archive paths are derived from the target. The supplied
    writer owns archive bytes; the supplied zstd stream context manager provides the
    decompressed tar stream. A write or legal-byte validation failure stops later outputs.
    """
    documents = supplied_documents(metadata_root, upstream_root)
    package_dirs = {
        variant: package_root / f"{variant}-package-{target}"
        for variant in PACKAGE_VARIANTS
    }
    for package_dir in package_dirs.values():
        stage_documents(package_dir, documents)

    for variant, package_dir in package_dirs.items():
        for suffix in ARCHIVE_SUFFIXES:
            archive_path = archive_root / f"{variant}-package-{target}{suffix}"
            write_archive(package_dir, archive_path, force=True)
            if suffix == ".tar.gz":
                with archive_path.open("rb") as source:
                    check_archive(source, documents)
            else:
                with decompress_zstd(archive_path) as source:
                    check_archive(source, documents)
    return len(documents)


@contextmanager
def _decompress_zstd_archive(archive_path: Path, zstd_command: list[str]):
    """Yield a completed zstd decoder's tar output and always close its temporary stream."""
    with tempfile.TemporaryFile(mode="w+b") as tar_stream:
        subprocess.run(
            [*zstd_command, "-dc", str(archive_path)],
            stdout=tar_stream,
            check=True,
        )
        tar_stream.seek(0)
        yield tar_stream


def _load_upstream_archive_api(upstream_root: Path):
    """Import archive writing and zstd resolution from the supplied upstream checkout."""
    scripts_root = upstream_root / "scripts"
    if not scripts_root.is_dir():
        raise ValueError(f"Missing upstream scripts directory: {scripts_root}")
    os.environ["CODEX_REPO_ROOT"] = str(upstream_root.resolve())
    sys.path.insert(0, str(scripts_root))
    from codex_package.archive import resolve_zstd_command, write_archive

    return write_archive, resolve_zstd_command


def main(argv=None) -> int:
    """Run stage, check, or finalize; return zero on success and nonzero on failure.

    Every mode requires downstream metadata and original upstream source directories.
    Finalize also requires staging and archive roots plus a target triple. It imports
    the authoritative archive writer and zstd resolver from the supplied upstream root.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("stage", "check", "finalize"))
    parser.add_argument("--metadata-root", type=Path, required=True)
    parser.add_argument("--upstream-root", type=Path, required=True)
    parser.add_argument("--package-dir", type=Path)
    parser.add_argument("--archive")
    parser.add_argument("--package-root", type=Path)
    parser.add_argument("--archive-root", type=Path)
    parser.add_argument("--target")
    args = parser.parse_args(argv)
    if args.mode == "stage":
        if args.package_dir is None or args.archive is not None or args.package_root is not None or args.archive_root is not None or args.target is not None:
            parser.error("Stage requires --package-dir and excludes archive and finalization options")
    elif args.mode == "check":
        if args.archive is None or args.package_dir is not None or args.package_root is not None or args.archive_root is not None or args.target is not None:
            parser.error("Check requires --archive and excludes package and finalization options")
    elif args.package_dir is not None or args.archive is not None or args.package_root is None or args.archive_root is None or args.target is None:
        parser.error("Finalize requires --package-root, --archive-root, and --target; it excludes --package-dir and --archive")
    try:
        if args.mode == "stage":
            documents = supplied_documents(args.metadata_root, args.upstream_root)
            stage_documents(args.package_dir, documents)
            document_count = len(documents)
        elif args.mode == "check":
            documents = supplied_documents(args.metadata_root, args.upstream_root)
            if args.archive == "-":
                check_archive(sys.stdin.buffer, documents)
            else:
                with open(args.archive, "rb") as source:
                    check_archive(source, documents)
            document_count = len(documents)
        else:
            write_archive, resolve_zstd_command = _load_upstream_archive_api(args.upstream_root)
            zstd_command = resolve_zstd_command()
            decompress_zstd = lambda archive_path: _decompress_zstd_archive(archive_path, zstd_command)
            document_count = finalize_packages(
                args.metadata_root,
                args.upstream_root,
                args.package_root,
                args.archive_root,
                args.target,
                write_archive=write_archive,
                decompress_zstd=decompress_zstd,
            )
    except (OSError, ValueError, ImportError, subprocess.CalledProcessError, tarfile.TarError, RuntimeError) as error:
        prefix = "Package legal finalization failed" if args.mode == "finalize" else "Package legal content rejected"
        print(f"{prefix}: {error}", file=sys.stderr)
        return 1
    print(f"Package legal content {args.mode} passed ({document_count} documents)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
