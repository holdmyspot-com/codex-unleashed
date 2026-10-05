#!/usr/bin/env python3
import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate a machine-readable release manifest for codex-unleashed."
    )
    parser.add_argument("--release-dir", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--patch-repo", required=True)
    parser.add_argument("--patch-repository", default="holdmyspot-com/codex-unleashed")
    parser.add_argument("--upstream-repository", default="openai/codex")
    parser.add_argument("--upstream-tag", default="")
    parser.add_argument("--upstream-commit", required=True)
    parser.add_argument("--patched-tag", default="")
    parser.add_argument("--build-number", default="")
    parser.add_argument("--build-date", default="")
    parser.add_argument("--builder-type", default="github-actions")
    parser.add_argument("--workflow-path", default="")
    parser.add_argument("--workflow-ref", default="")
    parser.add_argument("--workflow-sha", default="")
    parser.add_argument("--workflow-run-id", default="")
    parser.add_argument("--workflow-run-attempt", default="")
    parser.add_argument("--supported-targets", default="")
    parser.add_argument("--consolidated-checksums", default="codex-package_SHA256SUMS")
    parser.add_argument("--attestation-bundle", default="release-provenance.intoto.jsonl")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    release_dir = Path(args.release_dir).resolve()
    output_path = Path(args.output).resolve()
    patch_repo = Path(args.patch_repo).resolve()

    build_date = args.build_date or datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")

    artifacts = []
    for path in sorted(release_dir.rglob("*")):
        if not path.is_file():
            continue
        if path.resolve() == output_path:
            continue
        rel = path.relative_to(release_dir).as_posix()
        artifacts.append(
            {
                "path": rel,
                "sha256": sha256_file(path),
                "size_bytes": path.stat().st_size,
            }
        )

    patches = []
    for patch in sorted((patch_repo / "patches").rglob("*.patch")):
        patches.append(
            {
                "path": patch.relative_to(patch_repo).as_posix(),
                "sha256": sha256_file(patch),
            }
        )

    manifest = {
        "schema_version": 2,
        "release": {
            "patch_repository": args.patch_repository,
            "patched_tag": args.patched_tag,
            "build_number": args.build_number,
            "build_date": build_date,
            "builder_type": args.builder_type,
            "supported_targets": [item.strip() for item in args.supported_targets.split(",") if item.strip()],
        },
        "upstream": {
            "repository": args.upstream_repository,
            "tag": args.upstream_tag,
            "commit": args.upstream_commit,
        },
        "workflow": {
            "path": args.workflow_path,
            "ref": args.workflow_ref,
            "sha": args.workflow_sha,
            "run_id": args.workflow_run_id,
            "run_attempt": args.workflow_run_attempt,
        },
        "patches": patches,
        "artifacts": artifacts,
        "verification": {
            "consolidated_checksums": args.consolidated_checksums,
            "attestation_bundle": args.attestation_bundle,
        },
    }

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="ascii") as handle:
        json.dump(manifest, handle, indent=2, sort_keys=True)
        handle.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
