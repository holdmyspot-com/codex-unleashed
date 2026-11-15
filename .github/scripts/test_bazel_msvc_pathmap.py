#!/usr/bin/env python3

"""End-to-end contract tests for local Windows MSVC source-path mapping."""

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
WRAPPER = REPOSITORY_ROOT / ".github/scripts/run-bazel-ci.sh"
FAKE_BAZEL_SOURCE = r"""#!/usr/bin/env python3
import json
import os
import sys

arguments = sys.argv[1:]
command = next((argument for argument in arguments if argument in {"info", "build", "test"}), None)
record = {
    "argv": arguments,
    "command": command,
    "CFLAGS": os.environ.get("CFLAGS"),
    "CXXFLAGS": os.environ.get("CXXFLAGS"),
}
with open(os.environ["FAKE_BAZEL_LOG"], "a", encoding="utf-8") as stream:
    stream.write(json.dumps(record) + "\n")

if command == "info":
    if arguments[-1:] != ["execution_root"]:
        print("fake Bazel only serves the execution_root info key", file=sys.stderr)
        raise SystemExit(91)
    status = int(os.environ.get("FAKE_BAZEL_INFO_STATUS", "0"))
    if status:
        print("controlled execution_root query failure", file=sys.stderr)
        raise SystemExit(status)
    root = os.environ.get("FAKE_BAZEL_EXECUTION_ROOT", "D:/fixture/execroot")
    if os.environ.get("FAKE_BAZEL_MULTILINE") == "1":
        root = "D:/first/execroot\nD:/second/execroot"
    sys.stdout.write(root + "\r\n")
    raise SystemExit(0)

if command in {"build", "test"}:
    raise SystemExit(int(os.environ.get("FAKE_BAZEL_BUILD_STATUS", "0")))

print("fake Bazel received an unsupported command", file=sys.stderr)
raise SystemExit(92)
"""


class BazelMsvcPathmapTest(unittest.TestCase):
    def run_wrapper(
        self,
        *,
        runner_os: str = "Windows",
        selectors: tuple[str, ...] = ("--windows-msvc-host-platform",),
        keyed: bool = False,
        root: str = "D:/fixture/execroot",
        info_status: int = 0,
        build_status: int = 0,
        command: str = "test",
        bazel_args: tuple[str, ...] = (),
        build_targets: tuple[str, ...] = ("//fixture:target",),
        multiline: bool = False,
    ) -> tuple[subprocess.CompletedProcess[str], list[dict[str, object]]]:
        with tempfile.TemporaryDirectory(prefix="msvc pathmap fixture ") as temp_dir:
            temp_path = Path(temp_dir)
            fake_bazel = temp_path / "fake bazel.py"
            fake_bazel.write_text(FAKE_BAZEL_SOURCE, encoding="utf-8")
            fake_bazel.chmod(0o755)
            log_path = temp_path / "bazel argv.jsonl"
            environment = {
                "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
                "HOME": temp_dir,
                "TMPDIR": temp_dir,
                "TMP": temp_dir,
                "TEMP": temp_dir,
                "RUNNER_OS": runner_os,
                "GITHUB_ACTIONS": "true",
                "GITHUB_REPOSITORY": "openai/codex",
                "GITHUB_EVENT_NAME": "push",
                "CODEX_BAZEL_BIN": str(fake_bazel),
                "CODEX_BAZEL_WINDOWS_PATH": r"C:\bazel\bin",
                "BAZEL_OUTPUT_USER_ROOT": str(temp_path / "bazel output root"),
                "FAKE_BAZEL_LOG": str(log_path),
                "FAKE_BAZEL_EXECUTION_ROOT": root,
                "FAKE_BAZEL_INFO_STATUS": str(info_status),
                "FAKE_BAZEL_BUILD_STATUS": str(build_status),
                "FAKE_BAZEL_MULTILINE": "1" if multiline else "0",
                "CFLAGS": "/WX -DKEEP_CFLAGS",
                "CXXFLAGS": "/WX -DKEEP_CXXFLAGS",
            }
            if keyed:
                # Deliberately synthetic: never forward a real workflow secret.
                environment["BUILDBUDDY_API_KEY"] = "fixture-placeholder-key"

            invocation = [
                "bash",
                str(WRAPPER),
                *selectors,
                "--",
                command,
                *bazel_args,
                "--",
                *build_targets,
            ]
            completed = subprocess.run(
                invocation,
                check=False,
                capture_output=True,
                text=True,
                env=environment,
                cwd=REPOSITORY_ROOT,
            )
            records = (
                [
                    json.loads(line)
                    for line in log_path.read_text(encoding="utf-8").splitlines()
                ]
                if log_path.exists()
                else []
            )
            return completed, records

    @staticmethod
    def by_command(
        records: list[dict[str, object]], command: str
    ) -> list[dict[str, object]]:
        return [record for record in records if record["command"] == command]

    def assert_mapping(
        self,
        records: list[dict[str, object]],
        *,
        expected_mapping: str,
        keyed: bool,
        expected_host_platform: str | None = "//:local_windows_msvc",
    ) -> None:
        info_records = self.by_command(records, "info")
        build_records = self.by_command(records, "test") + self.by_command(
            records, "build"
        )
        self.assertEqual(len(info_records), 1, records)
        self.assertEqual(len(build_records), 1, records)
        info = info_records[0]["argv"]
        build = build_records[0]["argv"]
        self.assertIsInstance(info, list)
        self.assertIsInstance(build, list)
        info_args = (
            info  # A structured fake-Bazel argv record, not reconstructed shell text.
        )
        build_args = build

        self.assertEqual(info_args[-1], "execution_root")
        self.assertFalse(
            any(
                "/pathmap:" in arg or "/experimental:deterministic" in arg
                for arg in info_args
            )
        )
        self.assertNotIn("//fixture:target", info_args)
        self.assertFalse(
            any(
                arg.startswith(("--test_env=", "--jobs=", "--remote_download_toplevel"))
                for arg in info_args
            )
        )
        self.assertEqual(
            info_args.count("--noexperimental_remote_repo_contents_cache"), 1
        )
        self.assertEqual(
            build_args.count("--noexperimental_remote_repo_contents_cache"), 1
        )
        info_command_index = info_args.index("info")
        build_command_index = (
            build_args.index("test")
            if "test" in build_args
            else build_args.index("build")
        )
        self.assertEqual(
            info_args[:info_command_index], build_args[:build_command_index]
        )
        self.assertEqual(
            [arg for arg in info_args if arg.startswith("--output_user_root=")],
            [arg for arg in build_args if arg.startswith("--output_user_root=")],
        )
        self.assertEqual(
            sum(arg.startswith("--output_user_root=") for arg in info_args), 1
        )
        if keyed:
            self.assertEqual(info_args.count("--config=ci-windows"), 1)
            self.assertEqual(build_args.count("--config=ci-windows"), 1)
            self.assertTrue(
                any(
                    arg.startswith(
                        "--remote_header=x-buildbuddy-api-key=fixture-placeholder-key"
                    )
                    for arg in info_args
                )
            )
        else:
            self.assertNotIn("--config=ci-windows", info_args)
            self.assertNotIn("--config=ci-windows", build_args)

        target_options = [
            arg for arg in build_args if arg.startswith("--per_file_copt=")
        ]
        host_options = [
            arg for arg in build_args if arg.startswith("--host_per_file_copt=")
        ]
        self.assertEqual(
            target_options,
            [f"--per_file_copt=.*@/experimental:deterministic,{expected_mapping}"],
        )
        self.assertEqual(
            host_options,
            [f"--host_per_file_copt=.*@/experimental:deterministic,{expected_mapping}"],
        )
        self.assertGreater(
            build_args.index(target_options[0]),
            build_args.index("--host_platform=" + expected_host_platform)
            if expected_host_platform
            else -1,
        )
        self.assertGreater(
            build_args.index(host_options[0]), build_args.index(target_options[0])
        )
        for argument in build_args:
            if argument.startswith(
                (
                    "--copt=",
                    "--host_copt=",
                    "--action_env=CFLAGS=",
                    "--action_env=CXXFLAGS=",
                    "--host_action_env=CFLAGS=",
                    "--host_action_env=CXXFLAGS=",
                )
            ):
                self.assertNotIn("/pathmap:", argument)
                self.assertNotIn("/experimental:deterministic", argument)
        self.assertNotIn("/pathmap:", build_args)
        self.assertIn("--copt=/WX", build_args)
        self.assertIn("--features=treat_warnings_as_errors", build_args)
        self.assertEqual(build_args[-1], "//fixture:target")
        self.assertEqual(build_records[0]["CFLAGS"], "/WX -DKEEP_CFLAGS")
        self.assertEqual(build_records[0]["CXXFLAGS"], "/WX -DKEEP_CXXFLAGS")

    def test_native_windows_maps_supported_execution_roots_for_keyed_and_local_runs(
        self,
    ) -> None:
        cases = (
            (r"D:\o\execroot\_main", "/pathmap:D:/o/execroot/_main=."),
            ("D:/build root/execroot/_main", "/pathmap:D:/build root/execroot/_main=."),
            (
                "D:/build,root/execroot/_main",
                r"/pathmap:D:/build\,root/execroot/_main=.",
            ),
            ("D:/build@root/execroot/_main", "/pathmap:D:/build@root/execroot/_main=."),
        )
        for root, mapping in cases:
            for keyed in (False, True):
                with self.subTest(root=root, keyed=keyed):
                    completed, records = self.run_wrapper(
                        root=root,
                        keyed=keyed,
                        bazel_args=(
                            "--platforms=//:windows_x86_64_msvc",
                            "--keep_going",
                            "--copt=/WX",
                            "--features=treat_warnings_as_errors",
                        ),
                    )
                    self.assertEqual(
                        completed.returncode, 0, completed.stderr + completed.stdout
                    )
                    self.assert_mapping(records, expected_mapping=mapping, keyed=keyed)
                    build_args = self.by_command(records, "test")[0]["argv"]
                    self.assertIn("--platforms=//:windows_x86_64_msvc", build_args)
                    self.assertIn("--keep_going", build_args)

    def test_explicit_host_platform_is_preserved_while_mapping_is_added(self) -> None:
        completed, records = self.run_wrapper(
            keyed=True,
            root="D:/custom/root",
            bazel_args=(
                "--host_platform=//:custom_host",
                "--platforms=//:windows_arm64_msvc",
            ),
        )
        self.assertEqual(completed.returncode, 0, completed.stderr + completed.stdout)
        args = self.by_command(records, "test")[0]["argv"]
        self.assertIn("--host_platform=//:custom_host", args)
        self.assertNotIn("--host_platform=//:local_windows_msvc", args)
        self.assertEqual(
            args.count(
                "--per_file_copt=.*@/experimental:deterministic,/pathmap:D:/custom/root=."
            ),
            1,
        )

    def test_unkeyed_windows_cross_compile_uses_native_fallback_mapping(self) -> None:
        completed, records = self.run_wrapper(
            selectors=("--windows-cross-compile",),
            keyed=False,
            root="D:/fallback/root",
            bazel_args=("--copt=/WX", "--features=treat_warnings_as_errors"),
        )
        self.assertEqual(completed.returncode, 0, completed.stderr + completed.stdout)
        self.assert_mapping(
            records,
            expected_mapping="/pathmap:D:/fallback/root=.",
            keyed=False,
        )
        args = self.by_command(records, "test")[0]["argv"]
        self.assertIn("--jobs=8", args)
        self.assertIn("--host_platform=//:local_windows_msvc", args)

    def test_native_and_cross_platform_controls_do_not_query_or_map(self) -> None:
        cases = (
            ("Windows", (), False),
            ("Windows", ("--windows-cross-compile",), True),
            ("macOS", ("--windows-msvc-host-platform",), False),
            ("Linux", ("--windows-msvc-host-platform",), False),
            ("Linux", ("--windows-cross-compile",), False),
        )
        for runner_os, selectors, keyed in cases:
            with self.subTest(runner_os=runner_os, selectors=selectors, keyed=keyed):
                completed, records = self.run_wrapper(
                    runner_os=runner_os,
                    selectors=selectors,
                    keyed=keyed,
                )
                self.assertEqual(
                    completed.returncode, 0, completed.stderr + completed.stdout
                )
                self.assertEqual(self.by_command(records, "info"), [])
                build_records = self.by_command(records, "test")
                self.assertEqual(len(build_records), 1, records)
                args = build_records[0]["argv"]
                self.assertFalse(
                    any(
                        "_per_file_copt=" in arg
                        or "/pathmap:" in arg
                        or "/experimental:deterministic" in arg
                        for arg in args
                    )
                )
                if (
                    runner_os == "Windows"
                    and selectors == ("--windows-cross-compile",)
                    and keyed
                ):
                    self.assertIn("--host_platform=//:rbe", args)
                    self.assertIn("--shell_executable=/bin/bash", args)

    def test_invalid_execution_roots_fail_before_build_or_test(self) -> None:
        invalid_roots = (
            "",
            "D:/first/execroot\nD:/second/execroot",
            "relative/execroot",
            "/mnt/d/execroot",
            "C:drive-relative",
        )
        for root in invalid_roots:
            with self.subTest(root=root):
                completed, records = self.run_wrapper(root=root)
                self.assertNotEqual(completed.returncode, 0, completed.stdout)
                self.assertEqual(len(self.by_command(records, "info")), 1, records)
                self.assertEqual(self.by_command(records, "test"), [])
                self.assertEqual(self.by_command(records, "build"), [])
                self.assertRegex(completed.stderr, r"execution_root|execution root")

    def test_multiline_flag_and_failed_info_query_stop_before_build(self) -> None:
        multiline, multiline_records = self.run_wrapper(multiline=True)
        self.assertNotEqual(multiline.returncode, 0, multiline.stdout)
        self.assertEqual(len(self.by_command(multiline_records, "info")), 1)
        self.assertEqual(self.by_command(multiline_records, "test"), [])

        failed, failed_records = self.run_wrapper(info_status=37)
        self.assertEqual(failed.returncode, 37, failed.stderr + failed.stdout)
        self.assertEqual(len(self.by_command(failed_records, "info")), 1)
        self.assertEqual(self.by_command(failed_records, "test"), [])

    def test_build_failure_status_is_propagated_after_info_and_mapping(self) -> None:
        completed, records = self.run_wrapper(build_status=23, command="build")
        self.assertEqual(completed.returncode, 23, completed.stderr + completed.stdout)
        self.assertEqual(len(self.by_command(records, "info")), 1)
        self.assertEqual(len(self.by_command(records, "build")), 1)
        build_args = self.by_command(records, "build")[0]["argv"]
        self.assertEqual(
            build_args.count(
                "--per_file_copt=.*@/experimental:deterministic,/pathmap:D:/fixture/execroot=."
            ),
            1,
        )
        self.assertEqual(
            build_args.count(
                "--host_per_file_copt=.*@/experimental:deterministic,/pathmap:D:/fixture/execroot=."
            ),
            1,
        )


if __name__ == "__main__":
    if os.name != "posix":
        raise unittest.SkipTest("fake executable shims require a POSIX host")
    unittest.main()
