#!/usr/bin/env python3
"""Compare Linux x64 package cache runs from downloaded assets and complete package job logs."""

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
import zlib


TARGET = "x86_64-unknown-linux-musl"
CLI = f"codex-package-{TARGET}.tar.gz"
APP = f"codex-app-server-package-{TARGET}.tar.gz"
SYMBOLS = f"codex-symbols-{TARGET}.tar.gz"
PROOF = f"codex-version-{TARGET}.txt"
RECEIPT = f"package-receipt-{TARGET}.json"
ASSETS = {CLI, APP, SYMBOLS, PROOF, "install.sh", "install.ps1"}
SYMBOL_BINARIES = {"codex", "codex-app-server", "codex-code-mode-host", "codex-exec-server"}
RECEIPT_BINARIES = {"codex", "codex-app-server"}
CACHE_TAG = rf"cargo-v3-{TARGET}-pkg-[0-9a-f]{{64}}"
FINAL_CACHE_TAG = rf"cargo-v3-{TARGET}-pkg-final-v1-[0-9a-f]{{64}}"
TIMESTAMP = re.compile(r"\b\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z\b")
ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")


def require(condition, message):
    """Raise a concise error when an evidence invariant fails."""
    if not condition:
        raise ValueError(message)


def readelf(path, options):
    """Inspect a temporary ELF without executing it."""
    return subprocess.run(
        ["readelf", *options, "--", path],
        env={**os.environ, "LC_ALL": "C"},
        check=True, capture_output=True, encoding="utf-8", errors="replace",
    ).stdout


def elf_identity(path, name, debug):
    """Read x86-64 ELF and validate the GNU debuglink record or the debug companion."""
    output = readelf(path, ["--file-header", "--notes", "--wide"])
    build_id = re.search(r"Build ID:\s*([0-9a-f]+)", output)
    require(re.search(r"Class:\s*ELF64", output) and "little endian" in output
            and re.search(r"Machine:\s*Advanced Micro Devices X86-64", output),
            f"Expected x86_64 ELF file for {name}")
    if debug:
        sections = readelf(path, ["--section-headers", "--wide"])
        require(re.search(r"\s\.z?debug_info\s", sections), f"Missing debug info for {name}")
        crc = 0
        with path.open("rb") as symbol:
            for block in iter(lambda: symbol.read(1024 * 1024), b""):
                crc = zlib.crc32(block, crc)
    else:
        dump = readelf(path, ["--hex-dump=.gnu_debuglink"])
        words = re.findall(r"^\s*0x[0-9a-f]+ +([0-9a-f]{8}(?: [0-9a-f]{8}){0,3})", dump, re.MULTILINE)
        section = bytes.fromhex(" ".join(words))
        filename = name.encode("ascii") + b".debug\0"
        padding = bytes((-len(filename)) % 4)
        require(section[:-4] == filename + padding, f"Missing GNU debug link to {name}.debug")
        crc = int.from_bytes(section[-4:], "little")
    return {"build_id": build_id.group(1) if build_id else None, "gnu_debuglink_crc32": f"{crc:08x}"}


def archive_evidence(path, temp):
    """Inventory archive content and inspect selected ELF members in an owned file."""
    inventory = {}
    identities = {}
    debug = path.name == SYMBOLS
    with tarfile.open(path, "r|gz") as archive:
        for member in archive:
            entry = PurePosixPath(member.name)
            require(not entry.is_absolute() and ".." not in entry.parts,
                    f"Unexpected member path in {path.name}: {member.name}")
            name = str(entry)
            require(name not in inventory, f"Duplicate member in {path.name}: {name}")
            identity_name = entry.name.removesuffix(".debug") if debug else entry.name
            selected = identity_name in SYMBOL_BINARIES and (not debug or entry.name.endswith(".debug"))
            digest = None
            if member.isfile():
                with archive.extractfile(member) as content:
                    if selected:
                        with temp.open("wb") as output:
                            shutil.copyfileobj(content, output)
                        with temp.open("rb") as materialized:
                            digest = hashlib.file_digest(materialized, "sha256").hexdigest()
                        identities[name] = elf_identity(temp, identity_name, debug)
                        temp.unlink()
                    else:
                        digest = hashlib.file_digest(content, "sha256").hexdigest()
            elif selected:
                raise ValueError(f"Expected regular ELF member in {path.name}: {name}")
            inventory[name] = (member.type.hex(), member.mode, member.size, member.linkname, digest)
    return inventory, identities


def inspect_assets(root, expected_run, temp):
    """Validate artifact hashes and receipt binary records, and pair shipped ELFs with symbols."""
    path = root / RECEIPT
    require(path.stat().st_size <= 4 * 1024 * 1024, "Package receipt exceeds its size limit")
    receipt = json.loads(path.read_text())
    require(receipt["format_version"] == 1 and receipt["target"] == TARGET, "Unexpected package receipt format or target")
    require(receipt["run"] == {"id": expected_run, "attempt": 1}, f"Expected fresh receipt for run {expected_run}")
    artifacts = receipt["artifacts"]
    require(len(artifacts) == len(ASSETS) and {item["name"] for item in artifacts} == ASSETS,
            "Unexpected receipt artifact set")
    require({entry.name for entry in root.iterdir()} == ASSETS | {RECEIPT}, "Unexpected downloaded asset set")
    for artifact in artifacts:
        file = root / artifact["name"]
        require(file.is_file() and not file.is_symlink() and artifact["target"] == TARGET
                and artifact["size"] == file.stat().st_size, f"Invalid receipt artifact {file.name}")
        with file.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        require(digest == artifact["sha256"], f"Receipt artifact SHA-256 mismatch for {file.name}")
    require((root / PROOF).read_text().strip() == receipt["observed_cli_version"], "Receipt CLI version proof differs")

    payloads = {}
    binaries = []
    build_ids = {}
    for name in (CLI, APP, SYMBOLS):
        inventory, identities = archive_evidence(root / name, temp)
        payloads[name] = inventory
        build_ids[name] = identities
        if name != SYMBOLS:
            for member in identities:
                if PurePosixPath(member).name in RECEIPT_BINARIES:
                    record = inventory[member]
                    binaries.append({"archive": name, "path": member, "size": record[2], "sha256": record[4]})
    sort_key = lambda item: (item["archive"], item["path"])
    require(sorted(binaries, key=sort_key) == sorted(receipt["binaries"], key=sort_key),
            "Receipt executable records do not match archive contents")

    symbols = {PurePosixPath(member).stem: identity for member, identity in build_ids[SYMBOLS].items()}
    require(len(build_ids[SYMBOLS]) == len(SYMBOL_BINARIES) and symbols.keys() == SYMBOL_BINARIES,
            "Unexpected ELF symbol archive set")
    for name, required in (
        (CLI, {"codex", "codex-code-mode-host", "codex-exec-server"}),
        (APP, {"codex-app-server", "codex-code-mode-host", "codex-exec-server"}),
    ):
        identities = {PurePosixPath(member).name: identity for member, identity in build_ids[name].items()}
        require(required <= identities.keys(), f"Missing packaged ELF in {name}")
        for binary, identity in identities.items():
            require(identity == symbols[binary], f"ELF debug-link identity for {binary} differs from its archived symbols")
    return receipt, payloads, symbols


def inspect_log(path, *, final_output=False):
    """Read the complete GitHub package job log and summarize actual cache and Cargo messages."""
    started = finished = None
    if final_output:
        operation_pattern = rf"Final output cache operation=(pull|push) reference=(?P<repository>\S+):(?P<tag>{FINAL_CACHE_TAG})"
        result_pattern = r"final_output_cache_result=(hit|miss|saved) transport_seconds=(\d+)(?: digest=(sha256:[0-9a-f]{64}))?"
    else:
        operation_pattern = rf"Cache operation=(pull|push) state=verified reference=(?P<repository>\S+):(?P<tag>{CACHE_TAG})"
        result_pattern = r"cache_result=(hit|miss|saved)(?: digest=sha256:[0-9a-f]{64})?"
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
            operation = re.fullmatch(operation_pattern, line)
            if operation:
                entry = {"operation": operation[1], "tag": operation["tag"]}
                if final_output:
                    entry["repository"] = operation["repository"]
                operations.append(entry)
            result = re.fullmatch(result_pattern, line)
            if result and operations:
                if final_output:
                    require("result" not in operations[-1], f"Duplicate final output cache result in {path}")
                    operations[-1]["transfer_seconds"] = int(result[2])
                    if result[3]:
                        operations[-1]["digest"] = result[3]
                operations[-1]["result"] = result[1]
            if final_output and line.startswith("final_output_cache_producer_run_id="):
                producer = re.fullmatch(
                    r"final_output_cache_producer_run_id=([1-9][0-9]*) producer_run_attempt=([1-9][0-9]*)", line
                )
                require(producer and operations and operations[-1]["operation"] == "pull"
                        and "producer_run_id" not in operations[-1], f"Invalid final output cache producer in {path}")
                operations[-1]["producer_run_id"] = producer[1]
                operations[-1]["producer_run_attempt"] = int(producer[2])
            if not final_output:
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


def compare(cold_root, warm_root, cold_run, warm_run, cold_log, warm_log, *, final_output=False):
    """Return cache benefit and artifact evidence after checking the exact cold/warm comparison."""
    require(cold_run != warm_run, "Cold and warm evidence must come from distinct runs")
    cold = inspect_log(cold_log, final_output=final_output)
    warm = inspect_log(warm_log, final_output=final_output)
    for label, evidence, expected in (
        ("Cold", cold, [("pull", "miss"), ("push", "saved")]),
        ("Warm", warm, [("pull", "hit")]),
    ):
        actual = [(entry["operation"], entry.get("result")) for entry in evidence["cache"]]
        require(actual == expected, f"{label} log cache operations were {actual}, expected {expected}")
        if final_output:
            build_count = 2 if label == "Cold" else 0
            require(evidence["cargo_release_builds"] == build_count,
                    f"{label} log did not show exactly {build_count} Cargo release builds")
        else:
            require(evidence["cargo_release_builds"] >= 2, f"{label} log lacks the two Cargo release builds")
    tag = cold["cache"][0]["tag"]
    require(cold["cache"][1]["tag"] == warm["cache"][0]["tag"] == tag, "Cache keys differ between runs")

    if final_output:
        require(warm["cargo_compiling_messages"] == 0, "Warm log contains Cargo compilation")
        cold_save, warm_pull = cold["cache"][1], warm["cache"][0]
        require(cold["cache"][0]["repository"] == cold_save["repository"] == warm_pull["repository"],
                "Final output cache repositories differ between runs")
        require(cold_save.get("digest") and warm_pull.get("digest"), "Missing final output OCI artifact digest")
        require(cold_save["digest"] == warm_pull["digest"], "Saved and restored OCI artifact digests differ")
        require(warm_pull.get("producer_run_id") == cold_run and warm_pull.get("producer_run_attempt") == 1,
                "Final output cache producer differs from the measured cold run attempt")

    with tempfile.TemporaryDirectory(prefix="package-cache-evidence-") as scratch:
        temp = Path(scratch) / "elf"
        cold_receipt, cold_payloads, cold_symbols = inspect_assets(cold_root, cold_run, temp)
        warm_receipt, warm_payloads, warm_symbols = inspect_assets(warm_root, warm_run, temp)
    for field in ("repository", "signer_workflow", "fork_commit", "release", "upstream", "source", "patches",
                  "observed_cli_version"):
        require(cold_receipt[field] == warm_receipt[field], f"Package receipt identity differs at {field}")

    changed = {
        archive: sorted(member for member in cold_payloads[archive].keys() | warm_payloads[archive].keys()
                        if cold_payloads[archive].get(member) != warm_payloads[archive].get(member))
        for archive in (CLI, APP, SYMBOLS)
    }
    cold_hashes = {item["name"]: item["sha256"] for item in cold_receipt["artifacts"]}
    warm_hashes = {item["name"]: item["sha256"] for item in warm_receipt["artifacts"]}
    for name in ("install.sh", "install.ps1", PROOF):
        if cold_hashes[name] != warm_hashes[name]:
            changed[name] = [name]

    savings = round(cold["elapsed_seconds"] - warm["elapsed_seconds"], 3)
    recompilations_avoided = cold["cargo_compiling_messages"] - warm["cargo_compiling_messages"]
    return {
        "accepted": savings > 0 and recompilations_avoided > 0 and not any(changed.values()),
        "target": TARGET,
        "cache_tag": tag,
        "cold": {"run_id": cold_run, **cold},
        "warm": {"run_id": warm_run, **warm},
        "elapsed_seconds_saved": savings,
        "cargo_compiling_messages_avoided": recompilations_avoided,
        "changed_payload_members": changed,
        "changed_archive_sha256": sorted(name for name in (CLI, APP, SYMBOLS) if cold_hashes[name] != warm_hashes[name]),
        "elf_symbols": {"cold": cold_symbols, "warm": warm_symbols},
    }


def main(argv=None, *, final_output=False):
    """Read downloaded run evidence and return failure if it does not pass the comparison."""
    description = "Compare exact Linux x64 final output cache pilot evidence." if final_output else __doc__
    parser = argparse.ArgumentParser(description=description)
    for mode in ("cold", "warm"):
        parser.add_argument(f"--{mode}-assets", type=Path, required=True,
                            help=f"extracted assets-{TARGET} artifact from the {mode} run")
        parser.add_argument(f"--{mode}-log", type=Path, required=True,
                            help=f"complete timestamped Package job log from the {mode} run")
        parser.add_argument(f"--{mode}-run", required=True, help=f"GitHub run ID of the {mode} log and artifact")
    arguments = parser.parse_args(argv)
    try:
        report = compare(
            arguments.cold_assets, arguments.warm_assets, arguments.cold_run, arguments.warm_run,
            arguments.cold_log, arguments.warm_log, final_output=final_output,
        )
    except (ValueError, OSError, KeyError, TypeError, EOFError, tarfile.TarError, subprocess.CalledProcessError) as failure:
        print(f"Package cache evidence failed: {failure}", file=sys.stderr)
        return 1
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report["accepted"] else 1


if __name__ == "__main__":
    sys.exit(main())
