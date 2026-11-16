import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("archive-release-symbols-and-strip-binaries.sh")
REPO_ROOT = SCRIPT.parents[2]
TEMP_ROOT = Path(os.environ["TMPDIR"])
EXACT_SYMBOL = "codex_code_mode_host::main"


class CodeModeSymbolValidationTest(unittest.TestCase):
    """Exercise the real packaging shell entrypoint with local tool stand-ins.

    Local nm, objcopy, and strip stand-ins record the shell's requests; fake nm
    supplies controlled output/status. Assertions cover only the shell's tool
    selection, validation response, ordering, and exit propagation. They do not
    establish native symbol-table correctness or real-tool behavior.
    """

    def setUp(self):
        self.case_root = Path(tempfile.mkdtemp(dir=TEMP_ROOT))
        self.addCleanup(shutil.rmtree, self.case_root)
        self.tools = self.case_root / "tools"
        self.tools.mkdir()
        self.events = self.case_root / "events.log"
        self.release = self.case_root / "release"
        self.release.mkdir()
        self.archive = self.case_root / "archive"
        self.archive.mkdir()
        self.environment = os.environ.copy()
        self.environment["PATH"] = f"{self.tools}{os.pathsep}{self.environment['PATH']}"
        self.environment["EVENT_LOG"] = str(self.events)
        self.environment["RUNNER_TEMP"] = str(self.case_root / "runner-temp")
        self.install_tools()

    def install_tools(self):
        tool_source = """#!/usr/bin/env python3
import os
import sys
from pathlib import Path
name = Path(sys.argv[0]).name
with open(os.environ['EVENT_LOG'], 'a', encoding='utf-8') as events:
    events.write(name + '|' + '|'.join(sys.argv[1:]) + '\\n')
if name in ('nm', 'fake-nm'):
    expected = ['-C']
    if 'linux' in os.environ.get('SYMBOL_TARGET', ''):
        expected.append('--defined-only')
    if os.environ.get('REQUIRE_NM_FLAGS', '1') == '1' and sys.argv[1:] != expected + [os.environ['BINARY_PATH']]:
        sys.exit(90)
    sys.stdout.write(os.environ.get('NM_OUTPUT', ''))
    sys.exit(int(os.environ.get('NM_EXIT', '0')))
sys.exit(0)
"""
        for name in ("nm", "fake-nm", "objcopy", "strip", "tar"):
            path = self.tools / name
            path.write_text(tool_source, encoding="utf-8")
            path.chmod(0o755)

    def read_events(self):
        if not self.events.exists():
            return []
        return [
            line.split("|", 1)[0]
            for line in self.events.read_text(encoding="utf-8").splitlines()
        ]

    def add_binary(self, name="codex-code-mode-host"):
        binary = self.release / name
        binary.write_bytes(b"controlled executable fixture")
        self.environment["BINARY_PATH"] = str(binary)
        return binary

    def run_packager(self, target, binary_name="codex-code-mode-host"):
        return subprocess.run(
            [
                "bash",
                str(SCRIPT),
                "--target",
                target,
                "--artifact-name",
                "symbols-test",
                "--release-dir",
                str(self.release),
                "--archive-dir",
                str(self.archive),
                "--binaries",
                binary_name,
            ],
            cwd=REPO_ROOT,
            env=self.environment,
            capture_output=True,
            text=True,
            check=False,
        )

    def set_linux_symbol_output(self, symbol_type="T", symbol=EXACT_SYMBOL):
        self.environment["SYMBOL_TARGET"] = "x86_64-unknown-linux-gnu"
        self.environment["NM"] = str(self.tools / "fake-nm")
        self.environment["NM_OUTPUT"] = f"0000000000001000 {symbol_type} {symbol}\n"

    def assert_validation_failed_before_packaging(self, result):
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.read_events(), ["fake-nm"])

    def test_linux_accepts_one_defined_exact_entrypoint_with_lowercase_or_uppercase_type(
        self,
    ):
        for symbol_type in ("t", "T"):
            with self.subTest(symbol_type=symbol_type):
                self.events.unlink(missing_ok=True)
                self.add_binary()
                self.set_linux_symbol_output(symbol_type)
                self.environment["BINARY_PATH"] = str(
                    self.release / "codex-code-mode-host"
                )

                result = self.run_packager("x86_64-unknown-linux-gnu")

                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual(
                    self.read_events(), ["fake-nm", "objcopy", "strip", "objcopy"]
                )

    def test_linux_rejects_undefined_generic_suffixed_duplicate_and_absent_entrypoint(
        self,
    ):
        invalid_outputs = {
            "undefined": f"                 U {EXACT_SYMBOL}\n",
            "generic mention": "0000000000001000 T codex_code_mode_host::main::h123456\n",
            "suffixed name": "0000000000001000 T wrapper_codex_code_mode_host::main\n",
            "embedded type-like text": f"0000000000001000 T unrelated T {EXACT_SYMBOL}\n",
            "duplicate exact match": f"0000000000001000 T {EXACT_SYMBOL}\n0000000000002000 t {EXACT_SYMBOL}\n",
            "absent": "0000000000001000 T some_other_symbol\n",
        }
        self.add_binary()
        for label, output in invalid_outputs.items():
            with self.subTest(label=label):
                self.events.unlink(missing_ok=True)
                self.set_linux_symbol_output()
                self.environment["NM_OUTPUT"] = output

                result = self.run_packager("x86_64-unknown-linux-gnu")

                self.assert_validation_failed_before_packaging(result)

    def test_linux_nm_failure_is_preserved_without_fallback(self):
        self.add_binary()
        self.set_linux_symbol_output()
        self.environment["NM_EXIT"] = "23"

        result = self.run_packager("x86_64-unknown-linux-gnu")

        self.assertEqual(result.returncode, 23, result.stdout + result.stderr)
        self.assertEqual(self.read_events(), ["fake-nm"])

    def test_linux_uses_nm_from_path_when_nm_is_not_configured(self):
        self.add_binary()
        self.environment.pop("NM", None)
        self.environment["SYMBOL_TARGET"] = "x86_64-unknown-linux-gnu"
        self.environment["NM_OUTPUT"] = f"0000000000001000 T {EXACT_SYMBOL}\n"

        result = self.run_packager("x86_64-unknown-linux-gnu")

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.read_events(), ["nm", "objcopy", "strip", "objcopy"])

    def test_macos_accepts_plain_and_cargo_hashed_single_dwarf_members(self):
        for member_name in (
            "codex-code-mode-host",
            "codex_code_mode_host-0123456789abcdef",
        ):
            with self.subTest(member_name=member_name):
                self.events.unlink(missing_ok=True)
                self.add_binary()
                dsym = (
                    self.release
                    / "codex-code-mode-host.dSYM"
                    / "Contents"
                    / "Resources"
                    / "DWARF"
                )
                dsym_bundle = dsym.parent.parent.parent
                if dsym_bundle.exists():
                    shutil.rmtree(dsym_bundle)
                dsym.mkdir(parents=True)
                (dsym / member_name).write_bytes(b"debug symbols")
                self.environment["SYMBOL_TARGET"] = "aarch64-apple-darwin"
                self.environment["NM"] = str(self.tools / "fake-nm")
                self.environment["NM_OUTPUT"] = f"0000000000001000 T {EXACT_SYMBOL}\n"

                result = self.run_packager("aarch64-apple-darwin")

                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual(self.read_events(), ["fake-nm", "strip"])

    def test_macos_rejects_zero_multiple_empty_and_nonregular_dwarf_members(self):
        fixtures = {
            "zero": [],
            "multiple": [("one", "file"), ("two", "file")],
            "empty": [("empty", "empty")],
            "nonregular directory": [("nested", "directory")],
            "nonregular symlink": [("linked", "symlink")],
        }
        self.add_binary()
        for label, members in fixtures.items():
            with self.subTest(label=label):
                self.events.unlink(missing_ok=True)
                dwarf = (
                    self.release
                    / "codex-code-mode-host.dSYM"
                    / "Contents"
                    / "Resources"
                    / "DWARF"
                )
                shutil.rmtree(dwarf.parent.parent.parent, ignore_errors=True)
                dwarf.mkdir(parents=True)
                for name, kind in members:
                    member = dwarf / name
                    if kind == "directory":
                        member.mkdir()
                    elif kind == "symlink":
                        target = self.case_root / "dwarf-target"
                        target.write_bytes(b"symbols")
                        member.symlink_to(target)
                    else:
                        member.write_bytes(b"" if kind == "empty" else b"symbols")
                self.environment["SYMBOL_TARGET"] = "aarch64-apple-darwin"
                self.environment["NM"] = str(self.tools / "fake-nm")
                self.environment["NM_OUTPUT"] = f"0000000000001000 T {EXACT_SYMBOL}\n"

                result = self.run_packager("aarch64-apple-darwin")

                self.assert_validation_failed_before_packaging(result)
                staged_dsym = (
                    self.case_root
                    / "runner-temp"
                    / "codex-symbols-symbols-test"
                    / "codex-symbols-symbols-test"
                    / "codex-code-mode-host.dSYM"
                )
                self.assertFalse(staged_dsym.exists())
                shutil.rmtree(self.release / "codex-code-mode-host.dSYM")

    def test_other_linux_binaries_skip_code_mode_validation(self):
        self.add_binary("codex")
        self.environment.pop("NM", None)
        self.environment.pop("SYMBOL_TARGET", None)

        result = self.run_packager("x86_64-unknown-linux-gnu", "codex")

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.read_events(), ["objcopy", "strip", "objcopy"])

    def test_windows_pdb_packaging_does_not_use_unix_symbol_validation(self):
        self.environment.pop("NM", None)
        self.environment.pop("SYMBOL_TARGET", None)
        (self.release / "codex-code-mode-host.pdb").write_bytes(b"windows symbols")

        result = self.run_packager("x86_64-pc-windows-msvc")

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.read_events(), [])
        self.assertEqual(
            (
                self.case_root
                / "runner-temp"
                / "codex-symbols-symbols-test"
                / "codex-symbols-symbols-test"
                / "codex-code-mode-host.pdb"
            ).read_bytes(),
            b"windows symbols",
        )


if __name__ == "__main__":
    unittest.main()
