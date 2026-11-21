#!/usr/bin/env python3
"""Compare package cache runs from downloaded assets and complete package job logs."""

import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import uuid
import zlib


TARGET = "x86_64-unknown-linux-musl"
SYMBOL_BINARIES = {"codex", "codex-app-server", "codex-code-mode-host", "codex-exec-server"}
NATIVE_ARCHITECTURES = {
    "aarch64-apple-darwin": "arm64",
    "x86_64-apple-darwin": "x86_64",
    "aarch64-pc-windows-msvc": "IMAGE_FILE_MACHINE_ARM64",
    "x86_64-pc-windows-msvc": "IMAGE_FILE_MACHINE_AMD64",
}
WINDOWS_BINARIES = {"codex-command-runner", "codex-windows-sandbox-setup"}
DWARF_DIRECTORY = ("Contents", "Resources", "DWARF")
GUID = r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"
RECEIPT_BINARIES = {"codex", "codex-app-server"}
TIMESTAMP = re.compile(r"\b\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z\b")
ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")


def require(condition, message):
    """Raise a concise error when an evidence invariant fails."""
    if not condition:
        raise ValueError(message)


def inspect_file(command, path):
    """Run a format inspector against an owned temporary file."""
    return subprocess.run(
        [*command, path],
        env={**os.environ, "LC_ALL": "C"},
        check=True, capture_output=True, encoding="utf-8", errors="replace",
    ).stdout


def elf_identity(path, name, debug):
    """Read x86-64 ELF and validate the GNU debuglink record or the debug companion."""
    output = inspect_file(["readelf", "--file-header", "--notes", "--wide", "--"], path)
    build_id = re.search(r"Build ID:\s*([0-9a-f]+)", output)
    require(re.search(r"Class:\s*ELF64", output) and "little endian" in output
            and re.search(r"Machine:\s*Advanced Micro Devices X86-64", output),
            f"Expected x86_64 ELF file for {name}")
    if debug:
        sections = inspect_file(["readelf", "--section-headers", "--wide", "--"], path)
        require(re.search(r"\s\.z?debug_info\s", sections), f"Missing debug info for {name}")
        crc = 0
        with path.open("rb") as symbol:
            for block in iter(lambda: symbol.read(1024 * 1024), b""):
                crc = zlib.crc32(block, crc)
    else:
        dump = inspect_file(["readelf", "--hex-dump=.gnu_debuglink", "--"], path)
        words = re.findall(r"^\s*0x[0-9a-f]+ +([0-9a-f]{8}(?: [0-9a-f]{8}){0,3})", dump, re.MULTILINE)
        section = bytes.fromhex(" ".join(words))
        filename = name.encode("ascii") + b".debug\0"
        padding = bytes((-len(filename)) % 4)
        require(section[:-4] == filename + padding, f"Missing GNU debug link to {name}.debug")
        crc = int.from_bytes(section[-4:], "little")
    return {"build_id": build_id.group(1) if build_id else None, "gnu_debuglink_crc32": f"{crc:08x}"}


def macho_identity(path, name, architecture):
    """Require exactly one UUID, for the native slice of a Mach-O or a dSYM file."""
    output = inspect_file(["xcrun", "dwarfdump", "--uuid"], path)
    records = re.findall(rf"^UUID: ({GUID}) \(([^)]+)\)", output, re.MULTILINE)
    require(len(records) == 1 and records[0][1] == architecture,
            f"Expected one Mach-O UUID for {name} ({architecture})")
    identity = uuid.UUID(records[0][0])
    require(identity.int != 0, f"Empty Mach-O UUID for {name}")
    return {"uuid": str(identity)}


def unique_field(output, key, pattern, name):
    """Require a single value in an inspector's field-oriented output."""
    values = re.findall(rf"^\s*{key}:[ \t]+({pattern})[ \t]*$", output, re.MULTILINE)
    require(len(values) == 1, f"Expected one {key} field for {name}")
    return values[0]


def windows_identity(path, name, debug, architecture):
    """Read an RSDS CodeView or a PDB stream identity for the supplied pair."""
    if debug:
        output = inspect_file(["llvm-pdbutil", "dump", "-summary"], path)
        require(unique_field(output, "Has Debug Info", "true|false", name) == "true",
                f"Missing PDB debug information for {name}")
        guid = uuid.UUID(unique_field(output, "GUID", rf"\{{{GUID}\}}", name))
        age = unique_field(output, "Age", r"[0-9]+", name)
    else:
        output = inspect_file(["llvm-readobj", "--file-headers", "--coff-debug-directory"], path)
        machine = unique_field(output, "Machine", r"IMAGE_FILE_MACHINE_\w+ \(0x[0-9A-Fa-f]+\)", name)
        require(machine.split()[0] == architecture and "IMAGE_FILE_EXECUTABLE_IMAGE" in output,
                f"Expected PE executable for {name} ({architecture})")
        signature = unique_field(output, "PDBSignature", r"0x[0-9A-Fa-f]+", name)
        require(int(signature, 16) == 0x53445352, f"Expected RSDS CodeView record for {name}")
        # LLVM releases print either a canonical GUID or its 16 on-disk bytes.
        guid_text = unique_field(output, "PDBGUID", rf"\{{{GUID}\}}|\([0-9a-fA-F ]+\)", name)
        if guid_text.startswith("("):
            guid = uuid.UUID(bytes_le=bytes.fromhex(guid_text[1:-1]))
        else:
            guid = uuid.UUID(guid_text)
        age = unique_field(output, "PDBAge", r"[0-9]+", name)
    require(guid.int != 0 and int(age) > 0, f"Empty Windows debug identity for {name}")
    return {"guid": str(guid), "age": int(age)}


def package_identity(path, name, debug, target):
    """Inspect the platform-specific symbol identity for a package member."""
    if target.endswith("-apple-darwin"):
        return macho_identity(path, name, NATIVE_ARCHITECTURES[target])
    if target.endswith("-windows-msvc"):
        return windows_identity(path, name, debug, NATIVE_ARCHITECTURES[target])
    return elf_identity(path, name, debug)


def member_binary(entry, debug, target):
    """Map archive members to the names used by the shared package builder."""
    if target.endswith("-apple-darwin") and debug:
        if len(entry.parts) >= 5 and entry.parts[-4:-1] == DWARF_DIRECTORY:
            bundle = entry.parts[-5]
            if bundle.endswith(".dSYM"):
                return bundle.removesuffix(".dSYM")
        return None
    suffix = ".pdb" if debug else ".exe"
    if not target.endswith("-windows-msvc"):
        suffix = ".debug" if debug else ""
    return entry.name.removesuffix(suffix) if entry.name.endswith(suffix) else None


def archive_evidence(path, temp, debug, target):
    """Inventory archive content and inspect selected executable and debug members in an owned file."""
    inventory = {}
    identities = {}
    names = SYMBOL_BINARIES | WINDOWS_BINARIES if target.endswith("-windows-msvc") else SYMBOL_BINARIES
    with tarfile.open(path, "r|gz") as archive:
        for member in archive:
            entry = PurePosixPath(member.name)
            require(not entry.is_absolute() and ".." not in entry.parts,
                    f"Unexpected member path in {path.name}: {member.name}")
            name = str(entry)
            require(name not in inventory, f"Duplicate member in {path.name}: {name}")
            identity_name = member_binary(entry, debug, target)
            selected = identity_name in names
            digest = None
            if member.isfile():
                with archive.extractfile(member) as content:
                    if selected:
                        with temp.open("wb") as output:
                            shutil.copyfileobj(content, output)
                        with temp.open("rb") as materialized:
                            digest = hashlib.file_digest(materialized, "sha256").hexdigest()
                        identities[name] = package_identity(temp, identity_name, debug, target)
                        temp.unlink()
                    else:
                        digest = hashlib.file_digest(content, "sha256").hexdigest()
            elif selected:
                raise ValueError(f"Expected regular binary or symbol member in {path.name}: {name}")
            inventory[name] = (member.type.hex(), member.mode, member.size, member.linkname, digest)
    return inventory, identities


def inspect_assets(root, expected_run, temp, target):
    """Validate artifact hashes and receipt binary records, and pair shipped executables with symbols."""
    cli = f"codex-package-{target}.tar.gz"
    app = f"codex-app-server-package-{target}.tar.gz"
    symbols_archive = f"codex-symbols-{target}.tar.gz"
    proof = f"codex-version-{target}.txt"
    receipt_name = f"package-receipt-{target}.json"
    assets = {cli, app, symbols_archive, proof}
    if target == TARGET:
        assets.update({"install.sh", "install.ps1"})
    path = root / receipt_name
    require(path.stat().st_size <= 4 * 1024 * 1024, "Package receipt exceeds its size limit")
    receipt = json.loads(path.read_text())
    require(receipt["format_version"] == 1 and receipt["target"] == target, "Unexpected package receipt format or target")
    require(receipt["run"] == {"id": expected_run, "attempt": 1}, f"Expected fresh receipt for run {expected_run}")
    artifacts = receipt["artifacts"]
    require(len(artifacts) == len(assets) and {item["name"] for item in artifacts} == assets,
            "Unexpected receipt artifact set")
    require({entry.name for entry in root.iterdir()} == assets | {receipt_name}, "Unexpected downloaded asset set")
    for artifact in artifacts:
        file = root / artifact["name"]
        require(file.is_file() and not file.is_symlink() and artifact["target"] == target
                and artifact["size"] == file.stat().st_size, f"Invalid receipt artifact {file.name}")
        with file.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        require(digest == artifact["sha256"], f"Receipt artifact SHA-256 mismatch for {file.name}")
    require((root / proof).read_text().strip() == receipt["observed_cli_version"], "Receipt CLI version proof differs")

    payloads = {}
    binaries = []
    archive_identities = {}
    for name in (cli, app, symbols_archive):
        inventory, identities = archive_evidence(root / name, temp, name == symbols_archive, target)
        payloads[name] = inventory
        archive_identities[name] = identities
        if name != symbols_archive:
            for member in identities:
                if member_binary(PurePosixPath(member), False, target) in RECEIPT_BINARIES:
                    record = inventory[member]
                    binaries.append({"archive": name, "path": member, "size": record[2], "sha256": record[4]})
    sort_key = lambda item: (item["archive"], item["path"])
    require(sorted(binaries, key=sort_key) == sorted(receipt["binaries"], key=sort_key),
            "Receipt executable records do not match archive contents")

    def identities_by_binary(archive, debug):
        entries = archive_identities[archive]
        identities = {member_binary(PurePosixPath(member), debug, target): identity
                      for member, identity in entries.items()}
        require(len(identities) == len(entries), f"Duplicate binary or symbol identities in {archive}")
        return identities

    symbols = identities_by_binary(symbols_archive, True)
    helpers = SYMBOL_BINARIES - RECEIPT_BINARIES
    if target.endswith("-windows-msvc"):
        helpers |= WINDOWS_BINARIES
    require(symbols.keys() == RECEIPT_BINARIES | helpers, "Unexpected symbol archive set")
    for name, entrypoint in ((cli, "codex"), (app, "codex-app-server")):
        identities = identities_by_binary(name, False)
        require(({entrypoint} | helpers) <= identities.keys(), f"Missing packaged executable in {name}")
        for binary, identity in identities.items():
            require(identity == symbols[binary], f"Debug identity for {binary} differs from its archived symbols")
    return receipt, payloads, symbols


def inspect_log(path, target):
    """Read the complete GitHub package job log and summarize actual cache and Cargo messages."""
    cache_tag = rf"cargo-v3-{target}-pkg-[0-9a-f]{{64}}"
    started = finished = None
    operations = []
    compilations = cargo_builds = 0
    with path.open(encoding="utf-8", errors="replace") as lines:
        for raw_line in lines:
            match = TIMESTAMP.search(raw_line)
            if not match:
                continue
            finished = datetime.fromisoformat(match[0])
            if started is None:
                started = finished
            line = ANSI.sub("", raw_line[match.end():]).strip()
            operation = re.fullmatch(
                rf"Cache operation=(pull|push) state=verified reference=\S+:(?P<tag>{cache_tag})", line
            )
            if operation:
                operations.append({"operation": operation[1], "tag": operation["tag"]})
            result = re.fullmatch(r"cache_result=(hit|miss|saved)(?: digest=sha256:[0-9a-f]{64})?", line)
            if result and operations:
                operations[-1]["result"] = result[1]
            transfer = re.fullmatch(r"cache_transfer_seconds=(\d+)", line)
            if transfer and operations:
                operations[-1]["transfer_seconds"] = int(transfer[1])
            compilations += bool(re.match(r"Compiling\s+\S+\s+v\d", line))
            cargo_builds += bool(re.match(r"Finished `release` profile\b", line))
    require(started is not None and finished > started, f"Missing usable job log timestamps in {path}")
    return {
        "elapsed_seconds": round((finished - started).total_seconds(), 3),
        "cache": operations,
        "cargo_compiling_messages": compilations,
        "cargo_release_builds": cargo_builds,
    }


def compare(cold_root, warm_root, cold_run, warm_run, cold_log, warm_log, target=TARGET):
    """Return cache benefit and artifact evidence after checking the exact cold/warm comparison."""
    require(cold_run != warm_run, "Cold and warm evidence must come from distinct runs")
    cold = inspect_log(cold_log, target)
    warm = inspect_log(warm_log, target)
    release_builds = 2 if target == TARGET else 1
    for label, evidence, expected in (
        ("Cold", cold, [("pull", "miss"), ("push", "saved")]),
        ("Warm", warm, [("pull", "hit")]),
    ):
        actual = [(entry["operation"], entry.get("result")) for entry in evidence["cache"]]
        require(actual == expected, f"{label} log cache operations were {actual}, expected {expected}")
        require(evidence["cargo_release_builds"] >= release_builds,
                f"{label} log lacks the expected {release_builds} Cargo release build(s)")
    tag = cold["cache"][0]["tag"]
    require(cold["cache"][1]["tag"] == warm["cache"][0]["tag"] == tag, "Cache keys differ between runs")

    with tempfile.TemporaryDirectory(prefix="package-cache-evidence-") as scratch:
        temp = Path(scratch) / "member"
        cold_receipt, cold_payloads, cold_symbols = inspect_assets(cold_root, cold_run, temp, target)
        warm_receipt, warm_payloads, warm_symbols = inspect_assets(warm_root, warm_run, temp, target)
    for field in ("repository", "signer_workflow", "fork_commit", "release", "upstream", "source", "patches",
                  "observed_cli_version"):
        require(cold_receipt[field] == warm_receipt[field], f"Package receipt identity differs at {field}")

    changed = {
        archive: sorted(member for member in cold_payloads[archive].keys() | warm_payloads[archive].keys()
                        if cold_payloads[archive].get(member) != warm_payloads[archive].get(member))
        for archive in cold_payloads
    }
    cold_hashes = {item["name"]: item["sha256"] for item in cold_receipt["artifacts"]}
    warm_hashes = {item["name"]: item["sha256"] for item in warm_receipt["artifacts"]}
    for name in sorted(cold_hashes.keys() - cold_payloads.keys()):
        if cold_hashes[name] != warm_hashes[name]:
            changed[name] = [name]

    savings = round(cold["elapsed_seconds"] - warm["elapsed_seconds"], 3)
    recompilations_avoided = cold["cargo_compiling_messages"] - warm["cargo_compiling_messages"]
    return {
        "accepted": savings > 0 and recompilations_avoided > 0 and not any(changed.values()),
        "target": target,
        "cache_tag": tag,
        "cold": {"run_id": cold_run, **cold},
        "warm": {"run_id": warm_run, **warm},
        "elapsed_seconds_saved": savings,
        "cargo_compiling_messages_avoided": recompilations_avoided,
        "changed_payload_members": changed,
        "changed_archive_sha256": sorted(name for name in cold_payloads if cold_hashes[name] != warm_hashes[name]),
        ("elf_symbols" if target == TARGET else "native_symbols"): {"cold": cold_symbols, "warm": warm_symbols},
    }


def main(argv=None):
    """Read downloaded run evidence and return failure if it does not pass the comparison."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=(TARGET, *NATIVE_ARCHITECTURES), default=TARGET)
    for mode in ("cold", "warm"):
        parser.add_argument(f"--{mode}-assets", type=Path, required=True,
                            help=f"extracted assets artifact from the {mode} run")
        parser.add_argument(f"--{mode}-log", type=Path, required=True,
                            help=f"complete timestamped Package job log from the {mode} run")
        parser.add_argument(f"--{mode}-run", required=True, help=f"GitHub run ID of the {mode} log and artifact")
    arguments = parser.parse_args(argv)
    try:
        report = compare(
            arguments.cold_assets, arguments.warm_assets, arguments.cold_run, arguments.warm_run,
            arguments.cold_log, arguments.warm_log, arguments.target,
        )
    except (ValueError, OSError, KeyError, TypeError, EOFError, tarfile.TarError, subprocess.CalledProcessError) as failure:
        print(f"Package cache evidence failed: {failure}", file=sys.stderr)
        return 1
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report["accepted"] else 1


if __name__ == "__main__":
    sys.exit(main())
