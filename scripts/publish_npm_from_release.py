#!/usr/bin/env python3
"""Build and publish local npm packages from a GitHub Codex release."""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
from pathlib import Path


TARGETS = {
    "x86_64-unknown-linux-musl": "linux-x64",
    "aarch64-unknown-linux-musl": "linux-arm64",
    "x86_64-apple-darwin": "darwin-x64",
    "aarch64-apple-darwin": "darwin-arm64",
    "x86_64-pc-windows-msvc": "win32-x64",
    "aarch64-pc-windows-msvc": "win32-arm64",
}

LEGAL_FILES = (
    "LICENSE.md",
    "docs/LICENSE.html",
    "docs/terms.html",
    "docs/privacy.html",
)

PACKAGE_VARIANTS = (
    ("public", "codex-unleashed", "public"),
    ("ea", "codex-unleashed-ea", "restricted"),
)

LAUNCHER = r'''#!/usr/bin/env node
import { existsSync } from "node:fs";
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const require = createRequire(import.meta.url);
const target = {
  linux: { x64: "x86_64-unknown-linux-musl", arm64: "aarch64-unknown-linux-musl" },
  darwin: { x64: "x86_64-apple-darwin", arm64: "aarch64-apple-darwin" },
  win32: { x64: "x86_64-pc-windows-msvc", arm64: "aarch64-pc-windows-msvc" },
}[process.platform]?.[process.arch];
if (!target) throw new Error(`Unsupported platform: ${process.platform}/${process.arch}`);
const platformName = {
  "x86_64-unknown-linux-musl": "linux-x64",
  "aarch64-unknown-linux-musl": "linux-arm64",
  "x86_64-apple-darwin": "darwin-x64",
  "aarch64-apple-darwin": "darwin-arm64",
  "x86_64-pc-windows-msvc": "win32-x64",
  "aarch64-pc-windows-msvc": "win32-arm64",
}[target];
const platformPackage = `__PLATFORM_PACKAGE_PREFIX__${platformName}`;
let platformRoot;
try {
  platformRoot = path.dirname(require.resolve(`${platformPackage}/package.json`));
} catch {
  platformRoot = root;
}
const executable = path.join(platformRoot, "vendor", target, "bin", process.platform === "win32" ? "codex.exe" : "codex");
if (!existsSync(executable)) throw new Error(`Missing Codex binary for ${target}`);
const child = spawn(executable, process.argv.slice(2), { stdio: "inherit" });
child.on("exit", (code, signal) => {
  if (signal) process.kill(process.pid, signal);
  else process.exit(code ?? 1);
});
'''


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", default="holdmyspot-com/codex-unleashed")
    parser.add_argument("--tag", help="GitHub release tag, for example rust-v0.153.4+25")
    parser.add_argument("--version", help="npm version; defaults to the version in --tag")
    parser.add_argument("--registry", default="http://127.0.0.1:4873")
    parser.add_argument("--scope", default="@holdmyspot")
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--archive-dir", type=Path, help="Use downloaded archives from this directory")
    parser.add_argument("--publish", action="store_true", help="Run npm publish after packing")
    parser.add_argument("--npmrc", type=Path, help="Pass this npm config file to npm")
    return parser.parse_args()


def version_from_tag(tag: str) -> str:
    if tag.startswith("rust-v"):
        return tag.removeprefix("rust-v")
    if tag.startswith("v"):
        return tag.removeprefix("v")
    raise ValueError(f"Cannot derive npm version from tag {tag!r}")


def npm_version(version: str) -> str:
    """Convert a GitHub SemVer build suffix to one npm preserves."""
    match = re.fullmatch(r"(\d+\.\d+\.\d+)(?:\+([0-9]+))?", version)
    if not match:
        raise ValueError(
            f"Expected a stable vendor version such as 0.153.4+25; got {version!r}"
        )
    base, build = match.groups()
    return f"{base}-unleashed.{build}" if build else base


def download_archives(repository: str, tag: str, destination: Path) -> None:
    api_url = f"https://api.github.com/repos/{repository}/releases/tags/{tag}"
    request = urllib.request.Request(api_url, headers={"Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(request) as response:
        release = json.load(response)
    assets = {asset["name"]: asset["browser_download_url"] for asset in release["assets"]}
    for target in TARGETS:
        name = f"codex-package-{target}.tar.gz"
        url = assets.get(name)
        if not url:
            raise RuntimeError(f"Release {repository}@{tag} has no {name}")
        print(f"Downloading {name}")
        urllib.request.urlretrieve(url, destination / name)


def safe_extract(archive: Path, destination: Path) -> None:
    with tarfile.open(archive, "r:gz") as tar:
        root = destination.resolve()
        for member in tar.getmembers():
            target = (destination / member.name).resolve()
            if target != root and root not in target.parents:
                raise RuntimeError(f"Unsafe archive member: {member.name}")
        tar.extractall(destination)


def npm(args: argparse.Namespace, package_dir: Path, *extra: str) -> None:
    command = ["npm", *extra]
    if args.npmrc:
        command += ["--userconfig", str(args.npmrc)]
    command += ["--registry", args.registry]
    subprocess.run(command, cwd=package_dir, check=True)


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def copy_legal_files(package_dir: Path, source_root: Path) -> None:
    for relative_name in LEGAL_FILES:
        source = source_root / relative_name
        destination = package_dir / relative_name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
    third_party_source = source_root / "licenses"
    if not third_party_source.is_dir():
        raise RuntimeError(f"Missing generated third-party licenses: {third_party_source}")
    shutil.copytree(
        third_party_source,
        package_dir / "licenses",
        dirs_exist_ok=True,
    )


def main() -> int:
    args = parse_args()
    if not args.tag and not args.version:
        raise SystemExit("provide --tag or --version")
    release_version = args.version or version_from_tag(args.tag)
    version = npm_version(release_version)
    if not args.scope.startswith("@"):
        raise SystemExit("--scope must include the @ prefix")
    output = args.output_dir or Path(tempfile.mkdtemp(prefix="codex-npm-"))
    output.mkdir(parents=True, exist_ok=True)
    archives = args.archive_dir or output / "archives"
    archives.mkdir(parents=True, exist_ok=True)

    if args.archive_dir is None:
        if not args.tag:
            raise SystemExit("--tag is required when --archive-dir is not used")
        download_archives(args.repository, args.tag, archives)

    packages = output / "packages"
    if packages.exists():
        shutil.rmtree(packages)
    packages.mkdir()

    package_dirs = []
    with tempfile.TemporaryDirectory(prefix="codex-npm-extract-") as temp:
        temp_root = Path(temp)
        extracted_archives = {}
        for target in TARGETS:
            archive = archives / f"codex-package-{target}.tar.gz"
            if not archive.is_file():
                raise SystemExit(f"missing archive: {archive}")
            extracted = temp_root / target
            extracted.mkdir()
            safe_extract(archive, extracted)
            extracted_archives[target] = extracted

        for variant, package_base, access in PACKAGE_VARIANTS:
            platform_packages = []
            for target, platform_name in TARGETS.items():
                platform_package_name = f"{args.scope}/{package_base}-{platform_name}"
                platform_dir = packages / variant / platform_name
                vendor_dir = platform_dir / "vendor" / target
                vendor_dir.parent.mkdir(parents=True)
                shutil.copytree(extracted_archives[target], vendor_dir, dirs_exist_ok=True)
                copy_legal_files(platform_dir, extracted_archives[target])
                write_json(platform_dir / "package.json", {
                    "name": platform_package_name,
                    "version": version,
                    "description": f"Codex Unleashed native binaries for {platform_name}.",
                    "license": "See LICENSE.md",
                    "os": [platform_name.split("-")[0]],
                    "cpu": [platform_name.split("-")[-1]],
                    "files": ["vendor", "licenses", *LEGAL_FILES],
                })
                package_dirs.append((platform_dir, access))
                platform_packages.append((platform_name, platform_package_name))

            package_name = f"{args.scope}/{package_base}"
            main_dir = packages / variant / "main"
            (main_dir / "bin").mkdir(parents=True)
            launcher = LAUNCHER.replace(
                "__PLATFORM_PACKAGE_PREFIX__", f"{args.scope}/{package_base}-"
            )
            (main_dir / "bin" / "codex.js").write_text(launcher, encoding="utf-8")
            (main_dir / "bin" / "codex.js").chmod(0o755)
            copy_legal_files(main_dir, next(iter(extracted_archives.values())))
            optional = {name: version for _, name in platform_packages}
            write_json(main_dir / "package.json", {
                "name": package_name,
                "version": version,
                "description": "Codex CLI distributed by Codex Unleashed.",
                "license": "See LICENSE.md",
                "type": "module",
                "bin": {"codex": "bin/codex.js"},
                "files": ["bin", "licenses", *LEGAL_FILES],
                "optionalDependencies": optional,
                "publishConfig": {"registry": args.registry},
            })
            (main_dir / "README.md").write_text(
                f"# {package_name}\n\nCodex Unleashed build {version}.\n\nLicense: [Codex Unleashed product license](LICENSE.md); upstream and third-party materials retain their licenses under [licenses/](licenses/), including [Apache License 2.0](licenses/openai-codex/LICENSE-APACHE-2.0).\n",
                encoding="utf-8",
            )
            package_dirs.append((main_dir, access))

    for package_dir, _ in package_dirs:
        npm(args, package_dir, "pack", "--pack-destination", str(output))
    if args.publish:
        for package_dir, access in package_dirs:
            npm(args, package_dir, "publish", "--access", access, "--tag", "latest")
    print(f"Created {len(package_dirs)} npm packages in {output}")
    if not args.publish:
        print("Packages were not published; pass --publish to publish to the configured registry.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except urllib.error.HTTPError as error:
        raise SystemExit(f"GitHub release download failed: HTTP {error.code}")
