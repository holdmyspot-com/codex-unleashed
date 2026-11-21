#!/usr/bin/env python3
"""Record verified native archive identity and inventories without downloading package assets."""
import json
import os
from pathlib import Path
import runpy
import tempfile

target = "aarch64-apple-darwin"
root = Path(os.environ["RUNNER_TEMP"])
compare = runpy.run_path(str(Path(__file__).with_name("compare-package-cache-pilot.py")))
run = os.environ["GITHUB_RUN_ID"]
phase, tag, restore = (os.environ[name] for name in ("CACHE_PHASE", "CACHE_TAG", "CACHE_RESTORE"))
with tempfile.TemporaryDirectory(prefix="intel-cache-archive-evidence-") as scratch:
    receipt, files, symbols = compare["inspect_assets"](root / "assets", run, Path(scratch) / "member", target)
contents = (root / "package-cargo-cache-key.txt").read_text().splitlines()
if contents[0] != f"tag={tag}" or receipt["fork_commit"] != os.environ["GITHUB_SHA"]:
    raise ValueError("Package or cache evidence differs from the selected head or key")
report = {
    "phase": phase, "key": tag, "restore": restore, "receipt": receipt, "archives": files, "symbols": symbols,
    "key_components": {name: digest for digest, name in (line.split() for line in contents[1:])},
}
(root / "arm64-package-cache-evidence.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
