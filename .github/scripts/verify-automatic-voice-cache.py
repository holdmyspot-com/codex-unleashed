#!/usr/bin/env python3
"""Record native proof that the production cache chose the expected source."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import runpy

root = Path(os.environ["RUNNER_TEMP"])
phase = os.environ["PROBE"]
result = Path(os.environ["RESULT_DIR"])
incoming = root / "voice-cache-incoming/approved.json"
restored = os.environ["RESTORED"] == "true"
assert restored == (phase == "warm")
assert incoming.is_file() == (phase != "cold")
origin_path = result / "evidence/voice-cache-origin.json"
assert origin_path.is_file() == restored
origin = json.loads(origin_path.read_text()) if restored else None

helper = runpy.run_path(str(Path(__file__).with_name("macos-sealed-voice-cache.py")))
inputs = json.loads((root / "voice-cache-inputs.json").read_text())
assert inputs["target"] == os.environ["TARGET"]
assert inputs["fork_commit"] == os.environ["GITHUB_SHA"]
if restored:
    assert origin["inputs"] == inputs
    assert origin["producer"]["run_id"] != os.environ["GITHUB_RUN_ID"]
    assert origin["consumer"]["run_id"] == os.environ["GITHUB_RUN_ID"]
else:
    assert (result / "evidence/build.stdout.log").is_file()
proof = root / "voice-auto-proof"
proof.mkdir()
arguments = argparse.Namespace(target=inputs["target"], version=inputs["version"])
helper["package_voice_fixture"](arguments, inputs, result, proof / "native-fixture")
files = helper["validate_producer_result"](result, inputs)
report = {
    "phase": phase, "restored": restored, "producer": origin["producer"] if origin else helper["producer"](),
    "consumer": helper["producer"](), "origin": origin,
    "files_sha256": hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest(),
    "files": len(files), "verification": "both native fixture tests passed",
}
if phase != "cold":
    report["approved"] = json.loads(incoming.read_text())
(proof / "receipt.json").write_text(json.dumps(report, indent=2) + "\n")
