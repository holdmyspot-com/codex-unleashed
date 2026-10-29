"""Reclaim inactive Xcode versions on a disposable GitHub-hosted macOS runner."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


def inactive_xcodes(applications: Path, selected_developer: Path) -> list[Path]:
    """Find sorted, non-symlink Xcode_*.app directories excluding selected Xcode.

    applications names the containing directory. selected_developer must
    resolve to an existing Xcode*.app/Contents/Developer directory directly
    inside it; otherwise raises ValueError, or a filesystem resolution error.
    Returns an empty list when no candidate qualifies; changes no files.
    """
    selected = selected_developer.resolve(strict=True)
    selected_app = selected.parent.parent
    selected_is_xcode = (
        selected.is_dir()
        and selected.parts[-2:] == ("Contents", "Developer")
        and selected_app.parent == applications.resolve(strict=True)
        and selected_app.name.startswith("Xcode")
        and selected_app.suffix == ".app"
    )
    if not selected_is_xcode:
        raise ValueError(
            f"Selected developer directory is not an Xcode application: {selected}"
        )

    candidates = []
    for app in sorted(applications.glob("Xcode_*.app")):
        is_inactive_directory = (
            not app.is_symlink()
            and app.is_dir()
            and app.resolve(strict=True) != selected_app
        )
        if is_inactive_directory:
            candidates.append(app)
    return candidates


def native_tools() -> dict[str, str]:
    """Read the selected developer directory, SDK path and clang version.

    Returns a string mapping; native command failures propagate."""
    return {
        "developer": subprocess.check_output(["xcode-select", "-p"], text=True).strip(),
        "sdk": subprocess.check_output(["xcrun", "--show-sdk-path"], text=True).strip(),
        "compiler": subprocess.check_output(["xcrun", "clang", "--version"], text=True),
    }


def available_filesystem_bytes(path: Path) -> int:
    """Return filesystem bytes available to an unprivileged process at path.

    Uses available blocks, excluding reserved free blocks; filesystem errors propagate."""
    filesystem = os.statvfs(path)
    return filesystem.f_bavail * filesystem.f_frsize


def main() -> None:
    """Remove inactive versioned Xcode directories on a disposable hosted macOS runner.

    Requires --evidence-directory, native macOS, GITHUB_ACTIONS=true and
    RUNNER_ENVIRONMENT=github-hosted. Writes a removal plan before sudo deletion,
    then checks that selected tool identities are unchanged and a native C
    compilation runs. Writes the result only after those checks pass. Platform,
    selection, deletion, tool or smoke-check failures propagate; earlier successful
    deletions are not rolled back."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-directory", type=Path, required=True)
    args = parser.parse_args()
    hosted_macos = (
        sys.platform == "darwin"
        and os.environ.get("GITHUB_ACTIONS") == "true"
        and os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted"
    )
    if not hosted_macos:
        raise RuntimeError(
            "Xcode reclamation requires a disposable GitHub-hosted macOS runner"
        )

    applications = Path("/Applications")
    before_tools = native_tools()
    candidates = inactive_xcodes(applications, Path(before_tools["developer"]))
    evidence = args.evidence_directory
    evidence.mkdir(parents=True, exist_ok=True)
    before = {
        "applications_base": "/Applications",
        "planned_removals": [app.name for app in candidates],
        "free_bytes": available_filesystem_bytes(applications),
        "selected_tools": before_tools,
    }
    (evidence / "xcode-cleanup-plan.json").write_text(
        json.dumps(before, indent=2) + "\n"
    )

    for app in candidates:
        subprocess.run(["sudo", "rm", "-rf", "--", str(app)], check=True)
        if app.exists():
            raise RuntimeError(
                f"Inactive Xcode application remains after removal: {app}"
            )
    after_tools = native_tools()
    if after_tools != before_tools:
        raise RuntimeError(
            f"Selected native tools changed: before={before_tools!r}; after={after_tools!r}"
        )

    with tempfile.TemporaryDirectory(
        prefix="native-compiler-", dir=evidence
    ) as scratch:
        executable = Path(scratch) / "smoke"
        subprocess.run(
            ["xcrun", "clang", "-x", "c", "-", "-o", str(executable)],
            input='#include <stdio.h>\nint main(void) { puts("baseline native tool smoke"); return 0; }\n',
            text=True,
            check=True,
        )
        output = subprocess.check_output([str(executable)], text=True)
        if output != "baseline native tool smoke\n":
            raise RuntimeError(
                f"Native compiler smoke produced unexpected output: {output!r}"
            )

    after = {
        "free_bytes": available_filesystem_bytes(applications),
        "selected_tools": after_tools,
        "compiler_smoke": "passed",
        "removed_applications": before["planned_removals"],
    }
    (evidence / "xcode-cleanup-result.json").write_text(
        json.dumps(after, indent=2) + "\n"
    )
    print(
        f"Removed {len(candidates)} inactive Xcode versions; recovered {after['free_bytes'] - before['free_bytes']} bytes"
    )


if __name__ == "__main__":
    main()
