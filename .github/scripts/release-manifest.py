#!/usr/bin/env python3
"""Record packaged inputs, assemble a manifest, authenticate it, or verify release assets and reconstructed source.

receipt requires a clean patched source checkout, its exact patch-repository checkout, collected assets and a
codex-version-TARGET.txt file containing packaged CLI stdout. assemble requires all six receipts and payloads.
verify requires that same patch checkout and reconstructed upstream source. Each command exits 1 on invalid input,
I/O, Git or attestation failure, and prints only completion or a diagnostic; it never executes packaged binaries.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import subprocess
import sys
import tarfile
import tempfile

# Consumer policy is fixed here; manifest contents cannot redirect the attestation trust root.
REPOSITORY = "holdmyspot-com/codex-unleashed"
SIGNER_WORKFLOW = REPOSITORY + "/.github/workflows/upstream-auto-release.yml"
UPSTREAM_REPOSITORY = "openai/codex"
# These are the release workflow's complete supported native package targets.
TARGETS = (
    "aarch64-apple-darwin", "aarch64-pc-windows-msvc", "aarch64-unknown-linux-musl",
    "x86_64-apple-darwin", "x86_64-pc-windows-msvc", "x86_64-unknown-linux-musl"
)
MANIFEST_NAME = "release-manifest.json"
CHECKSUM_NAME = "SHA256SUMS"
# Configuration manifests are global documents; this bound limits untrusted JSON memory use.
MAX_DOCUMENT_BYTES = 4 * 1024 * 1024
MAX_ASSETS = 128
SHA256 = re.compile(r"[0-9a-f]{64}")
GIT_SHA = re.compile(r"[0-9a-f]{40}")
SAFE_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+-]*")
VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\+([1-9][0-9]*)")
# Exact process-variable names the shared release builder permits in native operation diagnostics.
NATIVE_ENVIRONMENT_EXACT_KEYS = frozenset({
    "PATH", "CARGO_HOME", "CARGO_TARGET_DIR", "RUSTUP_TOOLCHAIN", "RUSTC", "RUSTC_WRAPPER",
    "RUSTC_WORKSPACE_WRAPPER", "RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS", "CARGO_BUILD_JOBS",
    "CARGO_INCREMENTAL", "STABLE_GIT_COMMIT", "CODEX_UNLEASHED_BUILD_NUMBER", "CODEX_BWRAP_SHA256",
    "MACOSX_DEPLOYMENT_TARGET", "DEVELOPER_DIR", "SDKROOT", "LIBSQLITE3_FLAGS", "SOURCE_DATE_EPOCH", "INCLUDE", "LIB",
    "LIBPATH", "VCINSTALLDIR", "VCToolsInstallDir", "WindowsSdkDir", "WindowsSDKVersion",
    "CC", "CXX", "AR", "CFLAGS", "CXXFLAGS", "LDFLAGS", "AWS_LC_SYS_NO_JITTER_ENTROPY"
})
# Prefix families the shared release builder permits in native operation diagnostics, with a required suffix.
NATIVE_ENVIRONMENT_PREFIXES = (
    "CARGO_PROFILE_RELEASE_", "CARGO_TARGET_", "RUSTY_V8_"
)
# Target-qualified C tool keys are handled by the environment family predicate below.
NATIVE_TARGET_ENVIRONMENT_PREFIXES = tuple(
    f"{name}_" for name in ("CC", "CXX", "AR", "CFLAGS", "CXXFLAGS", "LDFLAGS")
)


def require(condition, message):
    """Raise ValueError with an actionable input diagnostic when condition is false."""
    if not condition:
        raise ValueError(message)


def run(command, cwd=None):
    """Return a successful process's stdout; report its operation and status without leaking captured credentials."""
    result = subprocess.run(command, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    require(result.returncode == 0, f"{command[0]} {command[1]} failed (exit {result.returncode}); inspect the supplied checkout or verifier")
    return result.stdout.decode("utf-8").strip()


def git(repository, *arguments):
    """Return Git's output for a supplied repository, rejecting failed Git operations."""
    return run(["git", "-C", str(repository), *arguments])


def digest(path):
    """Return a file's SHA256 using bounded reads, rejecting symbolic links and non-files."""
    require(path.is_file() and not path.is_symlink(), f"Expected a regular file: {path.name}")
    value = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def unique_object(pairs):
    """Build a JSON object while rejecting repeated field names rather than silently replacing them."""
    result = {}
    for key, value in pairs:
        require(key not in result, f"Duplicate JSON field: {key}")
        result[key] = value
    return result


def read_json(path):
    """Read a bounded JSON document, rejecting duplicate keys and non-finite numbers."""
    require(path.stat().st_size <= MAX_DOCUMENT_BYTES, f"JSON document exceeds {MAX_DOCUMENT_BYTES} bytes: {path.name}")
    with path.open(encoding="utf-8") as source:
        return json.load(source, object_pairs_hook=unique_object, parse_constant=reject_constant)


def reject_constant(value):
    """Reject non-JSON numeric spellings accepted by Python's optional decoder extensions."""
    raise ValueError(f"Invalid JSON constant: {value}")


def write_json(path, value):
    """Write deterministic UTF-8 JSON to the explicit output path after validation completes."""
    require(not path.is_symlink(), f"Output must not be a symbolic link: {path.name}")
    path.write_text(json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n", encoding="utf-8")


def fields(value, expected, subject):
    """Require an object with exactly the schema's named fields; reject missing or unknown members."""
    require(isinstance(value, dict) and set(value) == set(expected), f"Invalid {subject} fields; expected {', '.join(expected)}")


def basename(name):
    """Return a safe asset basename, rejecting directories, traversal and ambiguous checksum names."""
    require(isinstance(name, str) and SAFE_NAME.fullmatch(name) is not None, f"Unsafe asset name: {name!r}")
    return name


def patch_path(name):
    """Return a relative slash-separated path below patches, rejecting traversal and platform separators."""
    require(isinstance(name, str), "Patch path must be text")
    parts = PurePosixPath(name).parts
    safe = len(parts) >= 2 and parts[0] == "patches" and "\\" not in name
    require(safe and all(SAFE_NAME.fullmatch(part) for part in parts) and "/".join(parts) == name,
            f"Unsafe patch path: {name!r}")
    require(name.endswith(".patch"), f"Patch path must end in .patch: {name}")
    return name


def hash_field(value, pattern, subject):
    """Validate an exact lowercase Git or SHA256 identity without coercing its representation."""
    require(isinstance(value, str) and pattern.fullmatch(value) is not None, f"Invalid {subject}")


def package_name(target):
    """Return the published CLI package name for a supported target."""
    return f"codex-package-{target}.tar.gz"


def symbols_name(target):
    """Return the published debug-symbol archive name for a supported target."""
    return f"codex-symbols-{target}.tar.gz"


def proof_name(target):
    """Return the text asset containing native packaged CLI --version stdout for a target."""
    return f"codex-version-{target}.txt"


def receipt_name(target):
    """Return the published package receipt name for a supported target."""
    return f"package-receipt-{target}.json"


def artifact(path, kind, target=None):
    """Return name, semantic kind, target, byte size and SHA256 of one regular published asset."""
    return {"name": basename(path.name), "kind": kind, "target": target,
            "size": path.stat().st_size, "sha256": digest(path)}


def payload_kind(name, target):
    """Classify known release assets; reject unexpected files instead of publishing unaudited payloads."""
    if name == package_name(target):
        return "cli_archive"
    if name == symbols_name(target):
        return "symbols_archive"
    if name == proof_name(target):
        return "version_proof"
    if name in ("install.sh", "install.ps1"):
        require(target == "x86_64-unknown-linux-musl", "Installers must be supplied by the x86_64 Linux receipt")
        return "installer"
    if name == f"codex-app-server-package-{target}.tar.gz":
        return "app_server_archive"
    raise ValueError(f"Unsupported release payload: {name}")


def validate_artifact(value):
    """Validate the complete persisted artifact record and reject malformed names, sizes or target values."""
    fields(value, ("name", "kind", "target", "size", "sha256"), "artifact")
    basename(value["name"])
    require(type(value["size"]) is int and value["size"] >= 0, "Artifact size must be a nonnegative integer")
    hash_field(value["sha256"], SHA256, "artifact SHA256")
    require(value["target"] in (*TARGETS, None), "Unsupported artifact target")
    if value["kind"] == "package_receipt":
        require(value["target"] in TARGETS and value["name"] == receipt_name(value["target"]), "Invalid package receipt asset")
    else:
        require(value["target"] in TARGETS, "Payload target is required")
        require(payload_kind(value["name"], value["target"]) == value["kind"], "Payload name and kind disagree")


def archive_binaries(path):
    """Hash regular CLI/app-server executable members inside an archive without extraction or native execution."""
    result = []
    with tarfile.open(path, "r|gz") as archive:
        for member in archive:
            name = PurePosixPath(member.name)
            require(not name.is_absolute() and ".." not in name.parts and "\\" not in member.name,
                    f"Unsafe archive member in {path.name}")
            executable = name.name in ("codex", "codex.exe", "codex-app-server", "codex-app-server.exe")
            if executable:
                require(member.isfile(), f"Executable archive member must be a regular file: {member.name}")
                value = hashlib.sha256()
                with archive.extractfile(member) as source:
                    for chunk in iter(lambda: source.read(1024 * 1024), b""):
                        value.update(chunk)
                result.append({"archive": path.name, "path": name.as_posix(), "size": member.size,
                               "sha256": value.hexdigest()})
    require(len({entry["path"] for entry in result}) == len(result), "Duplicate executable archive member")
    return result


def patch_inventory(patch_repo, fork_commit):
    """Return ordered raw patch identities from the existing release queue parser and pinned Git blobs."""
    require(git(patch_repo, "rev-parse", "HEAD") == fork_commit, "Patch repository HEAD differs from fork_commit")
    parser = Path(__file__).with_name("upstream-auto-release.py")
    require((patch_repo / "patches/README.md").stat().st_size <= MAX_DOCUMENT_BYTES, "Patch README is too large")
    names = run([sys.executable, "-I", str(parser), "patches", "--readme", str(patch_repo / "patches/README.md")]).split()
    require(len(names) <= 512 and len(set(names)) == len(names), "Invalid or duplicate patch queue entries")
    result = []
    for name in names:
        patch_path(name)
        path = patch_repo.joinpath(*name.split("/"))
        value = digest(path)
        require(git(patch_repo, "hash-object", str(path.resolve())) == git(patch_repo, "rev-parse", f"{fork_commit}:{name}"),
                f"Patch differs from fork commit: {name}")
        result.append({"path": name, "sha256": value})
    readme = patch_repo / "patches/README.md"
    committed_readme = git(patch_repo, "show", f"{fork_commit}:patches/README.md")
    require(readme.read_text(encoding="utf-8").strip() == committed_readme, "Patch README differs from fork commit")
    return result


def source_identity(source):
    """Return clean source HEAD/tree and Cargo.lock identity, rejecting modified tracked or untracked inputs."""
    require(git(source, "status", "--porcelain", "--untracked-files=normal") == "", "Source checkout must be clean before recording or verification")
    return {"commit": git(source, "rev-parse", "HEAD"), "tree": git(source, "rev-parse", "HEAD^{tree}"),
            "cargo_lock_sha256": digest(source / "codex-rs/Cargo.lock")}


def validate_identity(value):
    """Validate fixed policy and all shared release/source fields in a receipt or manifest."""
    require(type(value["format_version"]) is int and value["format_version"] == 1, "Unsupported manifest format_version")
    require(value["repository"] == REPOSITORY, "Release repository violates consumer policy")
    require(value["signer_workflow"] == SIGNER_WORKFLOW, "Signer workflow violates consumer policy")
    release = value["release"]
    fields(release, ("tag", "version", "build_number"), "release")
    require(isinstance(release["version"], str), "Release version must be text")
    match = VERSION.fullmatch(release["version"])
    require(match is not None, "Release version must be numeric X.Y.Z+BUILD")
    require(type(release["build_number"]) is int and release["build_number"] == int(match[4]), "Build number differs from release version")
    require(release["tag"] == f"rust-v{release['version']}", "Release tag differs from release version")
    upstream = value["upstream"]
    fields(upstream, ("repository", "tag", "commit"), "upstream")
    require(upstream["repository"] == UPSTREAM_REPOSITORY, "Unexpected upstream repository")
    require(upstream["tag"] == f"rust-v{release['version'].split('+')[0]}", "Upstream tag differs from release version")
    hash_field(upstream["commit"], GIT_SHA, "upstream commit")
    hash_field(value["fork_commit"], GIT_SHA, "fork commit")
    source = value["source"]
    fields(source, ("commit", "tree", "cargo_lock_sha256"), "source")
    hash_field(source["commit"], GIT_SHA, "source commit")
    hash_field(source["tree"], GIT_SHA, "source tree")
    hash_field(source["cargo_lock_sha256"], SHA256, "Cargo.lock SHA256")
    require(isinstance(value["patches"], list) and 0 < len(value["patches"]) <= 512, "Invalid patch inventory")
    seen = set()
    for patch in value["patches"]:
        fields(patch, ("path", "sha256"), "patch")
        patch_path(patch["path"])
        require(patch["path"] not in seen, "Duplicate patch path")
        seen.add(patch["path"])
        hash_field(patch["sha256"], SHA256, "patch SHA256")


def is_approved_native_environment_key(key):
    """Return whether a key belongs to the shared builder's approved diagnostic environment families."""
    if isinstance(key, str):
        if key in NATIVE_ENVIRONMENT_EXACT_KEYS:
            return True
        for prefix in (*NATIVE_ENVIRONMENT_PREFIXES, *NATIVE_TARGET_ENVIRONMENT_PREFIXES):
            has_family_prefix = key.startswith(prefix)
            has_family_suffix = len(key) > len(prefix)
            if has_family_prefix and has_family_suffix:
                return True
    return False


def require_bounded_native_string(value, subject):
    """Require one exact native observation string to fit the per-string byte bound."""
    require(isinstance(value, str), f"{subject} must be text")
    require(len(value.encode("utf-8")) <= 64 * 1024, f"{subject} exceeds 64 KiB")


def require_nullable_native_string(value, subject):
    """Require one nullable native observation string to fit the per-string byte bound when present."""
    if value is not None:
        require_bounded_native_string(value, subject)


def validate_native_build(value, target):
    """Validate the bounded diagnostic observations for one receipt target and return the original object."""
    fields(value, ("format_version", "target", "runner", "operations", "tools", "resources"), "native_build")
    require(type(value["format_version"]) is int and value["format_version"] == 1,
            "Unsupported native_build format_version")
    require(value["target"] == target, "native_build target differs from receipt target")
    require_bounded_native_string(value["target"], "native_build target")

    runner = value["runner"]
    runner_fields = ("os", "arch", "image_os", "image_version")
    fields(runner, runner_fields, "native_build runner")
    for name in runner_fields:
        require_nullable_native_string(runner[name], f"native_build runner {name}")

    expected_operations = ("build_binaries", "archive_packages")
    operations = value["operations"]
    require(isinstance(operations, list) and len(operations) == len(expected_operations),
            "native_build requires build_binaries then archive_packages")
    command_count = 0
    environment_count = 0
    for operation, expected_name in zip(operations, expected_operations):
        fields(operation, ("operation", "commands", "environment"), "native_build operation")
        require(operation["operation"] == expected_name, "native_build operation order is invalid")
        commands = operation["commands"]
        require(isinstance(commands, list) and 0 < len(commands) <= 256,
                "native_build operation requires 1 to 256 commands")
        command_count += len(commands)
        require(command_count <= 256, "native_build requires at most 256 commands")
        for command in commands:
            require(isinstance(command, list) and 0 < len(command) <= 256,
                    "native_build command requires 1 to 256 arguments")
            for argument in command:
                require_bounded_native_string(argument, "native_build argument")
                require("\x00" not in argument, "native_build argument contains NUL")
        environment = operation["environment"]
        require(isinstance(environment, dict) and len(environment) <= 256,
                "native_build environment requires at most 256 entries")
        environment_count += len(environment)
        require(environment_count <= 256, "native_build requires at most 256 environment entries")
        for name, observed in environment.items():
            require(is_approved_native_environment_key(name), f"Unapproved native_build environment key: {name!r}")
            require_bounded_native_string(observed, f"native_build environment value for {name}")
            require("\x00" not in observed, f"native_build environment value for {name} contains NUL")

    tools = value["tools"]
    required_tool_names = ("rustc", "cargo", "git", "zig", "cc", "cxx", "linker", "strip", "objcopy",
                           "dsymutil", "python", "zstd")
    require(isinstance(tools, list) and len(tools) == len(required_tool_names),
            "native_build requires one entry for each diagnostic tool")
    seen_tools = set()
    for tool in tools:
        fields(tool, ("name", "status", "path", "version"), "native_build tool")
        name = tool["name"]
        require(isinstance(name, str) and name in required_tool_names, "Unsupported native_build tool")
        require(name not in seen_tools, f"Duplicate native_build tool: {name}")
        seen_tools.add(name)
        require(tool["status"] in ("observed", "unavailable", "not_applicable"),
                f"Invalid native_build tool status for {name}")
        require_nullable_native_string(tool["path"], f"native_build tool path for {name}")
        require_nullable_native_string(tool["version"], f"native_build tool version for {name}")
    require(seen_tools == set(required_tool_names), "native_build is missing a diagnostic tool")

    resources = value["resources"]
    require(isinstance(resources, list), "native_build resources must be an array")
    resource_identities = []
    for resource in resources:
        fields(resource, ("role", "path", "size", "sha256"), "native_build resource")
        role = resource["role"]
        require(role in ("packaged_resource", "configured_v8_archive"), "Unsupported native_build resource role")
        path_value = resource["path"]
        require_bounded_native_string(path_value, "native_build resource path")
        path = PurePosixPath(path_value)
        unsafe_path = (path_value in ("", ".") or path.is_absolute() or ".." in path.parts or
                       "\\" in path_value or path.as_posix() != path_value)
        require(not unsafe_path, f"Unsafe native_build resource path: {path_value!r}")
        if role == "configured_v8_archive":
            require(path.name == path_value, "configured_v8_archive path must be a basename")
        require(type(resource["size"]) is int and resource["size"] >= 0,
                "native_build resource size must be a nonnegative integer")
        hash_field(resource["sha256"], SHA256, "native_build resource SHA256")
        resource_identities.append((role, path_value))
    require(len(set(resource_identities)) == len(resource_identities), "Duplicate native_build resource identity")
    require(resource_identities == sorted(resource_identities), "native_build resources must be sorted by role and path")
    return value


def shared_identity(value):
    """Return release inputs that every target receipt and the manifest must agree on exactly."""
    return {key: value[key] for key in ("format_version", "repository", "signer_workflow", "release", "upstream", "fork_commit", "patches", "source")}


def validate_source(value, patch_repo, source, temp_root):
    """Check supplied source identity and independently replay pinned raw patches to prove its tree and ancestry."""
    require(patch_inventory(patch_repo, value["fork_commit"]) == value["patches"], "Patch inventory differs from pinned fork")
    require(source_identity(source) == value["source"], "Source HEAD, tree or Cargo.lock differs from manifest")
    upstream = value["upstream"]["commit"]
    require(git(source, "rev-parse", value["upstream"]["tag"] + "^{commit}") == upstream, "Upstream tag differs from pinned commit")
    git(source, "merge-base", "--is-ancestor", upstream, "HEAD")
    require(int(git(source, "rev-list", "--count", upstream + "..HEAD")) == len(value["patches"]), "Source ancestry differs from ordered patch count")
    with tempfile.TemporaryDirectory(prefix="release-source-", dir=temp_root) as temporary:
        reconstructed = Path(temporary) / "source"
        run(["git", "clone", "--shared", "--no-checkout", str(source.resolve()), str(reconstructed)])
        git(reconstructed, "checkout", "--detach", upstream)
        for patch in value["patches"]:
            git(reconstructed, "-c", "core.longpaths=true", "apply", "--index", str((patch_repo / patch["path"]).resolve()))
        require(git(reconstructed, "write-tree") == value["source"]["tree"], "Source tree is not pinned upstream plus the ordered raw patches")


def validate_receipt(value):
    """Validate a target receipt, its actual observed version, bounded inventory and required build observations."""
    fields(value, ("format_version", "repository", "signer_workflow", "release", "upstream", "fork_commit", "patches", "source",
                   "target", "run", "observed_cli_version", "inputs", "artifacts", "binaries"), "receipt")
    validate_identity(value)
    target = value["target"]
    require(target in TARGETS, "Unsupported receipt target")
    require(value["observed_cli_version"] == "codex-cli " + value["release"]["version"], "Packaged CLI version differs from expected numeric release version")
    fields(value["run"], ("id", "attempt"), "run")
    require(isinstance(value["run"]["id"], str) and value["run"]["id"].isdigit(), "Run id must be numeric text")
    require(type(value["run"]["attempt"]) is int and value["run"]["attempt"] > 0, "Run attempt must be positive")
    fields(value["inputs"], ("receipt_operation", "python", "host", "git", "native_build"), "observed inputs")
    require(value["inputs"]["receipt_operation"] == "receipt", "Invalid receipt operation")
    require(all(isinstance(value["inputs"][key], str) for key in ("python", "host", "git")), "Invalid observed tool inputs")
    validate_native_build(value["inputs"]["native_build"], target)
    require(isinstance(value["artifacts"], list) and len(value["artifacts"]) <= MAX_ASSETS, "Invalid receipt artifact inventory")
    names = []
    for entry in value["artifacts"]:
        validate_artifact(entry)
        require(entry["target"] == target and entry["kind"] != "package_receipt", "Receipt payload target differs from receipt")
        names.append(entry["name"])
    require(len(set(names)) == len(names), "Duplicate receipt asset")
    require({package_name(target), symbols_name(target), proof_name(target)} <= set(names), "Receipt is missing CLI archive, symbols or version proof")
    require(isinstance(value["binaries"], list) and 0 < len(value["binaries"]) <= 16, "Receipt requires archive binary hashes")
    binary_names = set()
    for binary in value["binaries"]:
        fields(binary, ("archive", "path", "size", "sha256"), "binary")
        require(binary["archive"] in names, "Binary archive is absent from receipt assets")
        require(isinstance(binary["path"], str), "Binary path must be text")
        path = PurePosixPath(binary["path"])
        require(not path.is_absolute() and ".." not in path.parts and "\\" not in binary["path"], "Unsafe binary member path")
        require(type(binary["size"]) is int and binary["size"] >= 0, "Invalid binary size")
        hash_field(binary["sha256"], SHA256, "binary SHA256")
        identity = (binary["archive"], binary["path"])
        require(identity not in binary_names, "Duplicate binary archive member")
        binary_names.add(identity)
    require(any(entry["archive"] == package_name(target) and PurePosixPath(entry["path"]).name in ("codex", "codex.exe")
                for entry in value["binaries"]), "CLI package contains no recorded codex executable")
    for artifact in value["artifacts"]:
        if artifact["kind"] == "app_server_archive":
            require(any(entry["archive"] == artifact["name"] and
                        PurePosixPath(entry["path"]).name in ("codex-app-server", "codex-app-server.exe")
                        for entry in value["binaries"]),
                    "App-server archive contains no recorded codex-app-server executable")


def observed_version(path):
    """Read native CLI stdout from a bounded regular proof file without executing its archive."""
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 4096, "Version proof must be a regular file of at most 4096 bytes")
    return path.read_text(encoding="utf-8").rstrip("\r\n")


def create_receipt(args, temp_root):
    """Record source, native build observations and packaged asset identities for one target receipt."""
    native_build = read_json(args.native_inputs)
    validate_native_build(native_build, args.target)
    value = {"format_version": 1, "repository": REPOSITORY, "signer_workflow": SIGNER_WORKFLOW,
             "release": {"tag": "rust-v" + args.version, "version": args.version, "build_number": args.build_number},
             "upstream": {"repository": UPSTREAM_REPOSITORY, "tag": args.upstream_tag, "commit": args.upstream_commit},
             "fork_commit": args.fork_commit, "patches": patch_inventory(args.patch_repo, args.fork_commit),
             "source": source_identity(args.source), "target": args.target,
             "run": {"id": args.run_id, "attempt": args.run_attempt},
             "observed_cli_version": observed_version(args.assets / proof_name(args.target)),
             "inputs": {"receipt_operation": "receipt", "python": platform.python_version(), "host": platform.platform(),
                        "git": run(["git", "--version"]), "native_build": native_build}, "artifacts": [], "binaries": []}
    for path in sorted(args.assets.iterdir()):
        if path.resolve() == args.output.resolve():
            continue
        kind = payload_kind(path.name, args.target)
        value["artifacts"].append(artifact(path, kind, args.target))
        if kind in ("cli_archive", "app_server_archive"):
            value["binaries"].extend(archive_binaries(path))
    validate_receipt(value)
    validate_source(value, args.patch_repo, args.source, temp_root)
    write_json(args.output, value)
    print(f"Recorded package receipt for {args.target}")


def verify_file(root, entry):
    """Reject a missing, symlinked, resized or changed inventoried asset."""
    path = root / entry["name"]
    require(path.is_file() and not path.is_symlink(), f"Missing regular asset: {entry['name']}")
    require(path.stat().st_size == entry["size"] and digest(path) == entry["sha256"], f"Asset digest or size mismatch: {entry['name']}")


def assemble(args):
    """Assemble exactly six agreeing receipts into a manifest and checksum inventory, rejecting extra payloads."""
    receipts = []
    for path in sorted(args.receipts.iterdir()):
        if path.name.startswith("package-receipt-") and path.name.endswith(".json"):
            value = read_json(path)
            validate_receipt(value)
            require(path.name == receipt_name(value["target"]), "Receipt filename differs from target")
            receipts.append((path, value))
    require(len(receipts) == len(TARGETS) and {value["target"] for _, value in receipts} == set(TARGETS), "Exactly six unique target receipts are required")
    identity = shared_identity(receipts[0][1])
    artifacts = []
    for path, value in receipts:
        require(shared_identity(value) == identity, "Receipts disagree on release, fork, source or ordered patches")
        artifacts.extend(value["artifacts"])
        record = artifact(path, "package_receipt", value["target"])
        verify_file(args.assets, record)
        artifacts.append(record)
    names = [entry["name"] for entry in artifacts]
    require(len(set(names)) == len(names), "Duplicate published asset name")
    require({"install.sh", "install.ps1"} <= set(names), "Both installers are required")
    value = {**identity, "receipts": [receipt_name(target) for target in TARGETS],
             "artifacts": sorted(artifacts, key=lambda entry: entry["name"])}
    require(args.output.name == MANIFEST_NAME and args.output.parent.resolve() == args.assets.resolve(), f"Manifest output must be assets/{MANIFEST_NAME}")
    validate_manifest(value)
    for entry in artifacts:
        verify_file(args.assets, entry)
    actual = {path.name for path in args.assets.iterdir()}
    require(actual - {MANIFEST_NAME, CHECKSUM_NAME} == set(names), "Release assets contain missing or uninventoried payloads")
    require(not (args.assets / CHECKSUM_NAME).is_symlink(), "Checksum output must not be a symbolic link")
    write_json(args.output, value)
    with (args.assets / CHECKSUM_NAME).open("w", encoding="utf-8", newline="\n") as output:
        for name in sorted([*names, MANIFEST_NAME]):
            output.write(f"{digest(args.assets / name)}  {name}\n")
    print("Assembled six-target release manifest and SHA256SUMS")


def validate_manifest(value):
    """Reject policy changes, incomplete targets or duplicate/unsupported release inventory entries."""
    fields(value, ("format_version", "repository", "signer_workflow", "release", "upstream", "fork_commit", "patches", "source", "receipts", "artifacts"), "manifest")
    validate_identity(value)
    require(value["receipts"] == [receipt_name(target) for target in TARGETS], "Manifest must name exactly six unique target receipts")
    require(isinstance(value["artifacts"], list) and len(value["artifacts"]) <= MAX_ASSETS, "Invalid manifest asset inventory")
    names = set()
    for entry in value["artifacts"]:
        validate_artifact(entry)
        require(entry["name"] not in names, "Duplicate manifest asset name")
        names.add(entry["name"])
    expected = {"install.sh", "install.ps1"}
    for target in TARGETS:
        expected.update((receipt_name(target), package_name(target), symbols_name(target), proof_name(target)))
    require(expected <= names, "Manifest is missing a required target asset or installer")


def attestation_command(path):
    """Return the fixed trusted repository and signer-workflow command for one asset."""
    return ["gh", "attestation", "verify", str(path), "--repo", REPOSITORY, "--signer-workflow", SIGNER_WORKFLOW]


def authenticate_manifest(manifest_path, *, run_command):
    """Validate a manifest and verify its attestation with the fixed consumer trust policy."""
    path = Path(manifest_path)
    value = read_json(path)
    validate_manifest(value)
    run_command(attestation_command(path))
    return value


def verify(args, temp_root):
    """Verify all release bytes, receipt agreement and reconstructed source; optionally require hosted attestations."""
    root = args.release_dir
    value = read_json(root / MANIFEST_NAME)
    validate_manifest(value)
    names = {entry["name"] for entry in value["artifacts"]}
    require({path.name for path in root.iterdir()} == names | {MANIFEST_NAME, CHECKSUM_NAME}, "Release directory contains missing or uninventoried assets")
    for entry in value["artifacts"]:
        verify_file(root, entry)
    expected = names | {MANIFEST_NAME}
    checksums = {}
    with (root / CHECKSUM_NAME).open(encoding="utf-8") as source:
        for line in iter(lambda: source.readline(257), ""):
            require(len(line) <= 256, "Checksum record is too long")
            require(len(checksums) <= MAX_ASSETS, "Too many checksum records")
            parts = line.rstrip("\n").split("  ")
            require(len(parts) == 2, "Malformed SHA256SUMS record")
            sha, name = parts
            basename(name)
            hash_field(sha, SHA256, "checksum SHA256")
            require(name not in checksums, "Duplicate checksum asset")
            checksums[name] = sha
    require(set(checksums) == expected, "SHA256SUMS inventory differs from release manifest")
    for name, sha in checksums.items():
        require(digest(root / name) == sha, f"SHA256SUMS mismatch: {name}")
    receipt_artifacts = []
    for name in value["receipts"]:
        receipt = read_json(root / name)
        validate_receipt(receipt)
        require(shared_identity(receipt) == shared_identity(value), "Receipt identity differs from release manifest")
        require(receipt_name(receipt["target"]) == name, "Receipt target differs from filename")
        require(observed_version(root / proof_name(receipt["target"])) == receipt["observed_cli_version"], "Version proof differs from receipt")
        binaries = []
        for entry in receipt["artifacts"]:
            if entry["kind"] in ("cli_archive", "app_server_archive"):
                binaries.extend(archive_binaries(root / entry["name"]))
        require(binaries == receipt["binaries"], "Archive binary hashes differ from receipt")
        receipt_artifacts.extend(receipt["artifacts"])
    payloads = [entry for entry in value["artifacts"] if entry["kind"] != "package_receipt"]
    require(sorted(receipt_artifacts, key=lambda entry: entry["name"]) == payloads, "Manifest payloads differ from receipts")
    validate_source(value, args.patch_repo, args.upstream_source, temp_root)
    if args.verify_attestations:
        for name in sorted(expected | {CHECKSUM_NAME}):
            run(attestation_command(root / name))
        print("Verified checksums, reconstructed source and GitHub attestations")
    else:
        print("Verified checksums and reconstructed source only; GitHub attestations were not checked")


def authenticate(args):
    """Authenticate one validated manifest before a caller trusts its recorded source identities."""
    authenticate_manifest(args.manifest, run_command=run)
    print("Authenticated release manifest")


def main(arguments, temp_root):
    """Parse one supported operation and return 0 on completion or 1 with a safe diagnostic on failure."""
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    receipt = commands.add_parser("receipt", help="record one native package receipt")
    for option in ("source", "patch-repo", "assets", "output"):
        receipt.add_argument("--" + option, type=Path, required=True)
    receipt.add_argument("--target", choices=TARGETS, required=True)
    for option in ("version", "upstream-tag", "upstream-commit", "fork-commit", "run-id"):
        receipt.add_argument("--" + option, required=True)
    for option in ("build-number", "run-attempt"):
        receipt.add_argument("--" + option, type=int, required=True)
    receipt.add_argument("--native-inputs", type=Path, required=True)
    assembly = commands.add_parser("assemble", help="assemble the six receipts and checksums")
    for option in ("receipts", "assets", "output"):
        assembly.add_argument("--" + option, type=Path, required=True)
    verification = commands.add_parser("verify", help="verify release bytes and source, optionally attestations")
    for option in ("release-dir", "patch-repo", "upstream-source"):
        verification.add_argument("--" + option, type=Path, required=True)
    verification.add_argument("--verify-attestations", action="store_true")
    authentication = commands.add_parser("authenticate", help="validate and authenticate one release manifest")
    authentication.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args(arguments)
    try:
        if args.command == "receipt":
            create_receipt(args, temp_root)
        elif args.command == "assemble":
            assemble(args)
        elif args.command == "authenticate":
            authenticate(args)
        else:
            verify(args, temp_root)
    except (ValueError, OSError, subprocess.CalledProcessError, tarfile.TarError, UnicodeError) as error:
        print(f"Release verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:], os.environ.get("TMPDIR")))
