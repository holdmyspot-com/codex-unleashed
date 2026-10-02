#!/usr/bin/env python3

from pathlib import Path
import json
import os
import re
import subprocess
import tempfile
import textwrap
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_PATH = REPOSITORY_ROOT / ".github" / "workflows" / "build-release.yml"


class ReleaseWorkflowTest(unittest.TestCase):
    def test_publication_condition_rejects_unsuccessful_verification(self) -> None:
        # Evaluate the configured predicate with modeled Actions job results.
        # This checks the decision locally; Actions scheduling remains a hosted check.
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        job = workflow.split("\n  publish:\n", 1)[1]
        condition = job.split("${{", 1)[1].split("}}", 1)[0]
        for reused in (False, True):
            for result in ("success", "failure", "cancelled", "skipped"):
                with self.subTest(reused=reused, verification=result):
                    context = {
                        "prepare": {"outputs": {"should_release": "true",
                                               "artifact_run_id": "previous" if reused else "current"}},
                        "verify": {"result": result},
                        "build-unix": {"result": "success"},
                        "build-windows-package": {"result": "success"},
                    }
                    script = (
                        "const needs = " + json.dumps(context) + ";\n"
                        "const github = {run_id: 'current'};\n"
                        "const inputs = {verification_level: 'full', focus_target: '', focus_bundle: ''};\n"
                        "const always = () => true; const cancelled = () => false;\n"
                        "console.log(Boolean(" + condition + "));\n"
                    )
                    output = subprocess.run(["node", "-e", script], capture_output=True,
                                            text=True, check=True)
                    self.assertEqual(output.stdout.strip(),
                                     "true" if result == "success" else "false")

    def test_verification_and_packaging_have_read_only_tokens(self) -> None:
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        for job_name in ("verify", "build-windows-package"):
            with self.subTest(job=job_name):
                job = re.split(r"\n  \S", workflow.split(f"\n  {job_name}:\n", 1)[1], maxsplit=1)[0]
                self.assertTrue("    permissions:\n      contents: read\n" in job,
                                f"{job_name} must not inherit release write permissions")

    def test_cache_writers_supply_package_api_authentication(self) -> None:
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        steps = re.split(r"\n      - name: ", workflow)
        writers = [step for step in steps if step.startswith((
            "Populate GHCR Cargo target overflow cache", "Prune old GHCR Cargo cache tags"))]
        self.assertEqual(len(writers), 4)
        for step in writers:
            with self.subTest(step=step.splitlines()[0]):
                self.assertIn("GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}", step)

    def test_native_cache_consumers_normalize_source_timestamps(self) -> None:
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        for name in ("build-unix", "build-windows-binaries"):
            with self.subTest(job=name):
                job = re.split(r"\n  \S", workflow.split(f"\n  {name}:\n", 1)[1], maxsplit=1)[0]
                self.assertIn('python3 .github/scripts/normalize-source-timestamps.py upstream "$SOURCE_DATE_EPOCH"', job)
                self.assertIn("CARGO_CACHE_SOURCE_ID: ${{ needs.prepare.outputs.upstream_sha }}:${{ needs.prepare.outputs.patch_hash }}", job)
                self.assertLess(job.index("Normalize patched source timestamps"),
                                job.index("Restore GHCR Cargo target cache"))

    def test_windows_cache_audit_records_library_reuse_before_main_build(self) -> None:
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        job = workflow.split("\n  build-windows-binaries:\n", 1)[1].split("\n  build-windows-package:\n", 1)[0]
        self.assertIn("Build Windows sandbox helper binaries", job)
        self.assertIn("cargo build --target", job)
        self.assertIn("--message-format=json-render-diagnostics", job)
        self.assertEqual(job.count("for binary in ${{ matrix.helper_binaries }}"), 1)
        self.assertEqual(job.count("scripts/run-cancellable-command.sh"), 2)
        self.assertEqual(job.count("LIBSQLITE3_FLAGS=SQLITE_DISABLE_INTRINSIC"), 1)
        environment_step = job.split("Configure Windows Cargo build environment", 1)[1].split("\n      - name:", 1)[0]
        self.assertIn("matrix.target == 'x86_64-pc-windows-msvc'", environment_step)
        self.assertLess(job.index("Configure Windows Cargo build environment"),
                        job.index("Build Windows sandbox helper binaries"))
        self.assertIn("${{ matrix.helper_binaries }}", job)
        self.assertIn("scripts/run-cancellable-command.sh", job)
        self.assertIn("cargo-cache-reuse-${{ matrix.target }}-${{ matrix.bundle }}.jsonl", job)
        self.assertLess(job.index("Rebuild release binaries from cached dependencies"),
                        job.index("Build Windows sandbox helper binaries"))
        self.assertLess(job.index("Upload Windows library cache reuse report"),
                        job.index("Cargo build (Windows binaries)"))

    def test_library_cache_audit_command_records_actual_cargo_reuse(self) -> None:
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        step = workflow.split("      - name: Build Windows sandbox helper binaries\n", 1)[1]
        step = step.split("\n      - name:", 1)[0]
        command = textwrap.dedent(step.split("        run: |\n", 1)[1])
        target = subprocess.check_output(["rustc", "-vV"], text=True).split("host: ")[1].splitlines()[0]
        command = command.replace("${{ matrix.target }}", target).replace("${{ matrix.bundle }}", "primary")
        command = command.replace("${{ matrix.helper_binaries }}", "codex-windows-sandbox")
        with tempfile.TemporaryDirectory(prefix="codex-cache-audit-test-") as directory:
            root = Path(directory)
            (root / "src").mkdir()
            (root / "reports").mkdir()
            (root / "Cargo.toml").write_text(
                '[package]\nname="codex-windows-sandbox"\nversion="0.1.0"\nedition="2021"\n',
                encoding="utf-8",
            )
            (root / "src/lib.rs").write_text("pub fn value() -> u8 { 7 }\n", encoding="utf-8")
            (root / "src/main.rs").write_text(
                'fn main() { let unused_warning = 1; println!("{}", codex_windows_sandbox::value()); }\n', encoding="utf-8"
            )
            environment = os.environ.copy()
            environment.update(CARGO_TARGET_DIR=str(root / "target"), RUNNER_TEMP=str(root / "reports"),
                               CARGO_CACHE_SOURCE_ID="unchanged-inputs", GITHUB_WORKSPACE=str(REPOSITORY_ROOT))
            cleanup = ["python3", str(REPOSITORY_ROOT / ".github/scripts/clean-cached-release-binaries.py"),
                       str(root), target, "codex-windows-sandbox"]
            subprocess.run(cleanup, env=environment, check=True, capture_output=True)
            subprocess.run(["cargo", "build", "--offline", "--release", "--target", target],
                           cwd=root, env=environment, check=True, capture_output=True)
            subprocess.run(["python3", str(REPOSITORY_ROOT / ".github/scripts/clean-cached-release-binaries.py"),
                            "--record-source-inputs", str(root), target],
                           env=environment, check=True, capture_output=True)
            subprocess.run(cleanup, env=environment, check=True, capture_output=True)
            result = subprocess.run(["bash", "-c", command], cwd=root, env=environment,
                                    check=True, capture_output=True, text=True)
            self.assertIn("unused_warning", result.stderr)
            report = root / "reports" / f"cargo-cache-reuse-{target}-primary.jsonl"
            messages = [json.loads(line) for line in report.read_text(encoding="utf-8").splitlines()]
            libraries = [message for message in messages if message.get("reason") == "compiler-artifact"
                         and message["target"]["name"] == "codex_windows_sandbox"]
            self.assertEqual(len(libraries), 1)
            self.assertTrue(libraries[0]["fresh"])
            self.assertEqual(libraries[0]["target"]["kind"], ["lib"])
            binaries = [message for message in messages if message.get("reason") == "compiler-artifact"
                        and message["target"]["kind"] == ["bin"]]
            self.assertEqual(len(binaries), 1)
            self.assertFalse(binaries[0]["fresh"])
            self.assertEqual(messages[-1], {"reason": "build-finished", "success": True})

    def test_codespell_referenced_files_exist(self) -> None:
        self.assertTrue((REPOSITORY_ROOT / ".codespellignore").is_file())
        self.assertTrue((REPOSITORY_ROOT / ".github/codespell-matcher.json").is_file())

    def test_codespell_accepts_the_tui_library_name(self) -> None:
        ignored_words = (REPOSITORY_ROOT / ".codespellignore").read_text().splitlines()
        self.assertIn("ratatui", ignored_words)

    def test_release_entry_points_use_one_workflow(self) -> None:
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        self.assertFalse((WORKFLOW_PATH.parent / "rust-release.yml").exists())
        self.assertIn('  push:\n    tags:\n      - "rust-v*.*.*"', workflow)
        self.assertIn("  workflow_dispatch:", workflow)
        self.assertIn("  workflow_call:", workflow)
        detector = (WORKFLOW_PATH.parent / "upstream-release-check.yml").read_text()
        self.assertIn("gh workflow run build-release.yml", detector)

    def test_release_permissions_cover_required_operations(self) -> None:
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        self.assertTrue("\npermissions:\n" in workflow, "workflow must declare token permissions")
        permissions = workflow.split("\npermissions:\n", 1)[1].split("\n\n", 1)[0]
        for permission in ("actions: read", "contents: write", "packages: write",
                           "id-token: write", "attestations: write"):
            self.assertIn(permission, permissions)

    def test_pushed_tag_selects_its_upstream_version(self) -> None:
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        start = workflow.index('          if [[ -n "${INPUT_UPSTREAM_TAG:-}" ]]')
        end = workflow.index('\n          if [[ -z "${upstream_tag}"', start)
        script = workflow[start:end]
        for input_tag, ref_type, ref_name, expected in (
            ("", "tag", "rust-v0.159.1+23", "rust-v0.159.1"),
            ("rust-v0.159.2", "tag", "rust-v0.159.1+23", "rust-v0.159.2"),
            ("", "branch", "main", "rust-v0.159.2"),
        ):
            with self.subTest(input_tag=input_tag, ref_type=ref_type):
                result = subprocess.run(
                    ["bash", "-eu", "-c", '''
gh() { printf 'false\\n'; }
check_upstream_release() { printf '{"tag_name":"rust-v0.159.2","prerelease":false,"draft":false}\\n'; }
''' + script.replace("./scripts/check-upstream-release.sh", "check_upstream_release")
                     + '\nprintf "%s" "$upstream_tag"'],
                    env={"INPUT_UPSTREAM_TAG": input_tag, "GITHUB_REF_TYPE": ref_type,
                         "GITHUB_REF_NAME": ref_name, "PATH": "/usr/bin:/bin"},
                    capture_output=True, text=True, check=True,
                )
                self.assertEqual(result.stdout, expected)

    def test_tag_push_keeps_npm_publication_enabled(self) -> None:
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        self.assertEqual(workflow.count("if: ${{ github.event_name == 'push' || inputs.publish_npm }}"), 2)

    def test_normal_build_does_not_export_empty_rustflags(self) -> None:
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")

        self.assertNotIn(
            "RUSTFLAGS: ${{ (inputs.reproducibility_mode || 'off') == "
            "'deterministic' && '-Ccodegen-units=1 -Zno-parallel-backend' || '' }}",
            workflow,
        )
        self.assertNotIn(
            "RUSTC_BOOTSTRAP: ${{ (inputs.reproducibility_mode || 'off') == "
            "'deterministic' && '1' || '' }}",
            workflow,
        )
        self.assertEqual(
            workflow.count("- name: Configure deterministic compiler mode"),
            2,
        )


if __name__ == "__main__":
    unittest.main()
