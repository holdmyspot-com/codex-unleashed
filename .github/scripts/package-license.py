"""Stage supplied legal documents and verify their bytes in canonical package archives."""

from pathlib import Path, PurePosixPath
import argparse
import sys
import tarfile

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
    "codex-rs/vendor/bubblewrap/COPYING", "codex-rs/vendor/bubblewrap/LICENSE",
    "third_party/voice/NOTICE.md", "third_party/wezterm/LICENSE"
)
# Keep bundled agreement-relative links intact inside the existing package resources boundary.
LEGAL_DIRECTORY = PurePosixPath("codex-resources/legal")
# Legal text is bounded; binaries are always consumed through tarfile's streaming reader.
MAX_DOCUMENT_BYTES = 2 * 1024 * 1024
MAX_LEGAL_FILES = 64
MAX_ARCHIVE_MEMBERS = 10000


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


def main(argv=None) -> int:
    """Run stage or check; return zero on success and report invalid input as exit one.

    Both modes require downstream metadata and original upstream source directories.
    Stage requires an assembled package directory; check requires a gzip/plain tar
    path, or '-' for a plain tar stream supplied by a zstd decompressor.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("stage", "check"))
    parser.add_argument("--metadata-root", type=Path, required=True)
    parser.add_argument("--upstream-root", type=Path, required=True)
    parser.add_argument("--package-dir", type=Path)
    parser.add_argument("--archive")
    args = parser.parse_args(argv)
    try:
        documents = supplied_documents(args.metadata_root, args.upstream_root)
        if args.mode == "stage":
            if args.package_dir is None or args.archive is not None:
                raise ValueError("Stage requires --package-dir and excludes --archive")
            stage_documents(args.package_dir, documents)
        else:
            if args.archive is None or args.package_dir is not None:
                raise ValueError("Check requires --archive and excludes --package-dir")
            if args.archive == "-":
                check_archive(sys.stdin.buffer, documents)
            else:
                with open(args.archive, "rb") as source:
                    check_archive(source, documents)
    except (OSError, ValueError, tarfile.TarError) as error:
        print(f"Package legal content rejected: {error}", file=sys.stderr)
        return 1
    print(f"Package legal content {args.mode} passed ({len(documents)} documents)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
