#!/usr/bin/env python3
"""Exercise the final output comparison gates with timestamped CI log fixtures."""

from datetime import datetime, timedelta, timezone
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest.mock import patch


EVIDENCE = runpy.run_path(str(Path(__file__).with_name("compare-package-cache-pilot.py")))
COMPARE = EVIDENCE["compare"]
TAG = "cargo-v3-x86_64-unknown-linux-musl-pkg-final-v1-" + "a" * 64
DEPENDENCY_TAG = "cargo-v3-x86_64-unknown-linux-musl-pkg-" + "b" * 64
DIGEST = "sha256:" + "c" * 64
REPOSITORY = "ghcr.io/example/package-cache"
COLD = [
    f"Final output cache operation=pull reference={REPOSITORY}:{TAG}",
    "final_output_cache_result=miss transport_seconds=1",
    f"Cache operation=pull state=verified reference={REPOSITORY}:{DEPENDENCY_TAG}",
    "cache_result=hit",
    "Compiling codex-cli v0.1.0",
    "Finished `release` profile [optimized] target(s) in 10s",
    "Finished `release` profile [optimized] target(s) in 1s",
    f"Final output cache operation=push reference={REPOSITORY}:{TAG}",
    f"final_output_cache_result=saved transport_seconds=3 digest={DIGEST}",
]
WARM = [
    f"Final output cache operation=pull reference={REPOSITORY}:{TAG}",
    "final_output_cache_producer_run_id=101 producer_run_attempt=1",
    f"final_output_cache_result=hit transport_seconds=2 digest={DIGEST}",
]


class FinalOutputEvidenceTest(unittest.TestCase):
    def compare(self, warm=WARM, cold=COLD, payload_change=False, warm_interval=1):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            cold_log = self.write_log(root / "cold.log", cold, interval=5)
            warm_log = self.write_log(root / "warm.log", warm, interval=warm_interval)
            archives = [EVIDENCE[name] for name in ("CLI", "APP", "SYMBOLS")]
            receipt = dict.fromkeys(
                ("repository", "signer_workflow", "fork_commit", "release", "upstream", "source", "patches",
                 "observed_cli_version"), "same"
            )
            receipt["artifacts"] = [{"name": name, "sha256": "a" * 64} for name in EVIDENCE["ASSETS"]]

            def inspect_assets(asset_root, expected_run, scratch_elf):
                self.assertEqual(asset_root.name, {"101": "cold", "102": "warm"}[expected_run])
                self.assertIsInstance(scratch_elf, Path)
                members = dict.fromkeys(archives, {"binary": "unchanged"})
                if payload_change and expected_run == "102":
                    members[archives[2]] = {"binary.debug": "changed"}
                return receipt, members, {}

            with patch.dict(COMPARE.__globals__, {"inspect_assets": inspect_assets}):
                return COMPARE(root / "cold", root / "warm", "101", "102", cold_log, warm_log, final_output=True)

    @staticmethod
    def write_log(path, messages, interval):
        start = datetime(2026, 11, 21, 12, tzinfo=timezone.utc)
        path.write_text("".join(
            f"{(start + timedelta(minutes=index * interval)).isoformat().replace("+00:00", "Z")} {message}\n"
            for index, message in enumerate(["Start job", *messages, "Finish job"])
        ))
        return path

    def test_accepts_measured_hit_ignoring_dependency_telemetry(self):
        report = self.compare()
        self.assertTrue(report["accepted"])
        self.assertEqual(report["cache_tag"], TAG)
        self.assertEqual(report["cold"]["cache"][1]["digest"], DIGEST)

    def test_rejects_wrong_tag_digest_and_cargo_activity(self):
        for messages, reason in (
            ([WARM[0].replace(TAG, TAG[:-1] + "b"), *WARM[1:]], "Cache keys differ"),
            ([*WARM[:2], WARM[2].replace(DIGEST, DIGEST[:-1] + "d")], "OCI artifact digests differ"),
            ([*WARM[:2], "final_output_cache_result=hit transport_seconds=2"], "Missing final output OCI"),
            ([WARM[0], WARM[2]], "producer differs"),
            ([WARM[0], WARM[1].replace("101", "100"), WARM[2]], "producer differs"),
            ([WARM[0], WARM[1].replace("attempt=1", "attempt=2"), WARM[2]], "producer differs"),
            ([WARM[0], WARM[1].replace("attempt=1", "attempt=0"), WARM[2]], "Invalid final output cache producer"),
            ([*WARM, "Finished `release` profile"], "exactly 0 Cargo release builds"),
            ([*WARM, "Compiling codex-cli v0.1.0"], "contains Cargo compilation"),
            ([*WARM, "final_output_cache_result=miss transport_seconds=1"], "Duplicate final output cache result"),
        ):
            with self.subTest(reason=reason), self.assertRaisesRegex(ValueError, reason):
                self.compare(warm=messages)

    def test_changed_symbols_or_slower_warm_run_reject_acceptance(self):
        report = self.compare(payload_change=True)
        self.assertFalse(report["accepted"])
        self.assertIn("binary.debug", report["changed_payload_members"][EVIDENCE["SYMBOLS"]])
        self.assertFalse(self.compare(warm_interval=20)["accepted"])


if __name__ == "__main__":
    unittest.main()
