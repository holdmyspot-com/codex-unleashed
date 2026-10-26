#!/usr/bin/env python3
"""Verify cache reference identities from controlled compilation and wrapper inputs."""

import hashlib
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

module_specification = importlib.util.spec_from_file_location("cargo_cache_keys", Path(__file__).with_name("cargo-cache-keys.py"))
cache_keys = importlib.util.module_from_spec(module_specification)
module_specification.loader.exec_module(cache_keys)


class CargoCacheKeysTest(unittest.TestCase):
    """Cache identities preserve exact inputs and isolate compatible compilation state."""

    def test_exact_tag_retains_input_order_and_bytes(self):
        """Derivation hashes the same bytes as the original workflow."""
        values = [b"commit\r\n", b"wrapper\n", b"helper\n", b"compiler\n"]
        result = cache_keys.derive_tags("x86_64-unknown-linux-musl", *values)
        expected = hashlib.sha256(b"".join(values)).hexdigest()
        self.assertEqual(result["tag"], "cargo-v3-x86_64-unknown-linux-musl-" + expected)

    def test_invalid_target_is_rejected(self):
        """Derivation rejects names that cannot be placed in a cache tag."""
        for target in ["", "../target", "target:other", "target with spaces"]:
            with self.subTest(target=target), self.assertRaises(ValueError):
                cache_keys.derive_tags(target, b"source", b"wrapper", b"helper", b"compiler")

    def test_wrapper_repair_preserves_seed_but_changes_exact_tag(self):
        """Wrapper-only repairs can locate compiled outputs from an earlier run."""
        before = cache_keys.derive_tags("target", b"source", b"wrapper-old", b"helper", b"compiler")
        after = cache_keys.derive_tags("target", b"source", b"wrapper-fixed", b"helper", b"compiler")
        self.assertNotEqual(before["tag"], after["tag"])
        self.assertIsNotNone(before.get("seed_tag"))
        self.assertEqual(before.get("seed_tag"), after.get("seed_tag"))

    def test_compile_input_changes_partition_seed(self):
        """Source, helper, compiler/configuration and target changes isolate seeds."""
        original = cache_keys.derive_tags("target", b"source", b"wrapper", b"helper", b"compiler")
        self.assertIsNotNone(original.get("seed_tag"))
        for values in [
            ("other-target", b"source", b"wrapper", b"helper", b"compiler"),
            ("target", b"changed-source", b"wrapper", b"helper", b"compiler"),
            ("target", b"source", b"wrapper", b"changed-helper", b"compiler"),
            ("target", b"source", b"wrapper", b"helper", b"changed-compiler")
        ]:
            with self.subTest(values=values):
                changed = cache_keys.derive_tags(*values)
                self.assertNotEqual(original.get("seed_tag"), changed.get("seed_tag"))

    def test_cli_emits_both_tags_and_rejects_invalid_inputs(self):
        """The workflow-facing command emits usable outputs or exits 2 without them."""
        with tempfile.TemporaryDirectory(prefix="cargo-cache-keys-") as temporary:
            root = Path(temporary)
            inputs = []
            for name in ["upstream", "wrappers", "helper", "compiler"]:
                path = root / name
                path.write_text(name + "\n")
                inputs.append(str(path))
            command = [sys.executable, str(Path(__file__).with_name("cargo-cache-keys.py"))]
            success = subprocess.run(command + ["target"] + inputs, capture_output=True, text=True)
            self.assertEqual(success.returncode, 0, success.stderr)
            self.assertRegex(success.stdout, r"\Atag=cargo-v3-target-[0-9a-f]{64}\nseed_tag=cargo-seed-v1-target-[0-9a-f]{64}\n\Z")

            for target in ["../target", ""]:
                failure = subprocess.run(command + [target] + inputs, capture_output=True, text=True)
                self.assertEqual(failure.returncode, 2, failure.stderr)
                self.assertEqual(failure.stdout, "")

            (root / "compiler").unlink()
            missing = subprocess.run(command + ["target"] + inputs, capture_output=True, text=True)
            self.assertEqual(missing.returncode, 2, missing.stderr)
            self.assertEqual(missing.stdout, "")


if __name__ == "__main__":
    unittest.main()
