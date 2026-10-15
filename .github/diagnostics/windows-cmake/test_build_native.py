"""Check native build preconditions and subprocess failure propagation."""

from contextlib import redirect_stderr
import io
import json
import os
from pathlib import Path
import platform
import shlex
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import bazel_native
from build_native import NativeBuild, validate_target


class NativeBuildTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        machine = platform.machine().lower()
        architecture = {"arm64": "aarch64", "amd64": "x86_64"}.get(machine, machine)
        suffix = {
            "Darwin": "apple-darwin",
            "Linux": "unknown-linux-gnu",
            "Windows": "pc-windows-msvc",
        }[platform.system()]
        self.args = SimpleNamespace(
            target=f"{architecture}-{suffix}",
            deployment_target="11.0",
            output=self.root / "build output",
            archives=self.root / "archives",
            cc=Path(sys.executable),
            cxx=Path(sys.executable),
            cmake=Path(sys.executable),
            make=Path(sys.executable),
            pkg_config=Path(sys.executable),
            shell=Path(sys.executable),
            bootstrap_make=None,
            jobs=2,
        )
        self.environment = {
            **os.environ,
            "INCLUDE": "fixture include",
            "LIB": "fixture lib",
        }

    def test_cli_entrypoint_imports_its_sibling_with_safe_path_enabled(self):
        result = subprocess.run(
            [
                sys.executable,
                "-P",
                str(Path(__file__).with_name("build_native.py")),
                "--help",
            ],
            cwd=self.root,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("--archives", result.stdout)

    def test_rejects_cross_host_and_musl_builds(self):
        for target, system, machine, libc in [
            ("x86_64-pc-windows-msvc", "Darwin", "x86_64", ""),
            ("aarch64-apple-darwin", "Darwin", "x86_64", ""),
            ("x86_64-unknown-linux-gnu", "Linux", "x86_64", "musl"),
            ("x86_64-unknown-linux-musl", "Linux", "x86_64", "musl"),
        ]:
            with self.subTest(target=target, system=system):
                with self.assertRaises(ValueError):
                    validate_target(target, system, machine, libc, "11.0")

    def test_requires_explicit_macos_deployment_target(self):
        with self.assertRaisesRegex(ValueError, "Declare the macOS deployment target"):
            validate_target("aarch64-apple-darwin", "Darwin", "arm64", "", None)

    def test_missing_tool_does_not_create_output(self):
        self.args.cc = self.root / "missing compiler"
        with self.assertRaisesRegex(ValueError, "Missing build tool"):
            NativeBuild(self.args, self.environment)
        self.assertFalse(self.args.output.exists())

    @unittest.skipIf(os.name == "nt", "Exercises Unix symbol-tool commands")
    def test_child_process_uses_declared_symbol_tools_with_literal_paths(self):
        for name in ("nm", "readelf"):
            tool = self.root / f"declared '{name} tool"
            tool.write_text(f"#!/bin/sh\nprintf '%s\\n' '{name} selected'\n")
            tool.chmod(0o755)
            setattr(self.args, name, tool)
        build = NativeBuild(self.args, self.environment)
        result = subprocess.run(
            [
                sys.executable,
                "-c",
                "import os, shlex, subprocess\n"
                "for name in ('NM', 'READELF'):\n"
                "    subprocess.run(shlex.split(os.environ[name]), check=True)\n",
            ],
            env=build.environment,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            result.stdout,
            "nm selected\nreadelf selected\n",
        )

    @unittest.skipIf(os.name == "nt", "Exercises the Unix clang++ driver symlink")
    def test_bootstrap_links_cpp_runtime_through_compiler_symlink(self):
        tools = {name: shutil.which(name) for name in ("clang", "cmake", "make")}
        if not all(tools.values()):
            self.skipTest("Requires clang, CMake and make")
        compiler = self.root / "clang++"
        compiler.symlink_to(Path(tools["clang"]).resolve())
        self.args.cc = Path(tools["clang"])
        self.args.cxx = compiler
        self.args.cmake = Path(tools["cmake"])
        self.args.make = Path(tools["make"])
        headers = self.root / "declared headers"
        headers.mkdir()
        (headers / "build_flag.h").write_text(
            '#define BUILD_FLAG "linked with flags"\n'
        )
        self.args.cxx_flag = [f"-I{headers}"]
        for name in ("ar", "ranlib"):
            tool = shutil.which(name)
            if tool is None:
                self.skipTest(f"Requires {name}")
            wrapper = self.root / f"declared {name}"
            wrapper.write_text(
                "#!/bin/sh\n"
                f"touch {shlex.quote(str(self.root / (name + '.used')))}\n"
                f'exec {shlex.quote(tool)} "$@"\n'
            )
            wrapper.chmod(0o755)
            setattr(self.args, name, wrapper)
        source = self.root / "cpp-source"
        source.mkdir()
        (source / "CMakeLists.txt").write_text(
            "cmake_minimum_required(VERSION 3.15)\n"
            "project(cpp_driver LANGUAGES CXX)\n"
            "add_library(message STATIC message.cpp)\n"
            "add_executable(cpp_driver main.cpp)\n"
            "target_link_libraries(cpp_driver PRIVATE message)\n"
            "install(TARGETS cpp_driver DESTINATION bin)\n"
        )
        (source / "main.cpp").write_text(
            "#include <iostream>\nconst char* message();\nint main() { std::cout << message(); }\n"
        )
        (source / "message.cpp").write_text(
            '#include "build_flag.h"\nconst char* message() { return BUILD_FLAG; }\n'
        )
        build = NativeBuild(self.args, self.environment)
        build.output.mkdir()
        build.sources = {"cpp-driver": source}
        build.cmake("cpp-driver", [], bootstrap=True)
        result = subprocess.run(
            [build.tools / "bin" / "cpp_driver"],
            check=True,
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.stdout, "linked with flags")
        self.assertTrue(
            all((self.root / (name + ".used")).is_file() for name in ("ar", "ranlib"))
        )

    @unittest.skipIf(os.name == "nt", "Exercises Autoconf's Unix tool expansion")
    def test_libffi_rejects_quoted_archive_tool_paths(self):
        for name in ("ar", "ranlib"):
            for index, path in enumerate(("declared tool", "declared'tool")):
                with self.subTest(name=name, path=path):
                    (self.root / path).touch()
                    setattr(self.args, name, self.root / path)
                    self.args.output = self.root / f"{name}-{index}"
                    build = NativeBuild(self.args, self.environment)
                    with (
                        patch(
                            "build_native.prepare_sources",
                            side_effect=lambda *args: (build.output / "build").mkdir(),
                        ),
                        patch.object(build, "cmake"),
                        patch.object(build, "meson"),
                        patch.object(build, "run"),
                        self.assertRaisesRegex(ValueError, "libffi .* path"),
                    ):
                        build.build()
                    setattr(self.args, name, None)

    @unittest.skipIf(os.name == "nt", "Exercises Darwin's Unix linker selection")
    def test_libffi_darwin_linker_selection_preserves_compile_only_probes(self):
        compiler = shutil.which("clang")
        if compiler is None:
            self.skipTest("Requires Clang")
        self.args.target = "aarch64-apple-darwin"
        self.args.output = self.root / "libffi"
        self.args.cc = Path(compiler)
        self.args.c_flag = ["-Werror"]
        self.args.link_flag = [
            "-fuse-ld=lld",
            "-Werror",
            "-Wl,-rpath,declared-relative-path",
        ]
        with patch("build_native.validate_target"):
            build = NativeBuild(self.args, self.environment)
        with (
            patch(
                "build_native.prepare_sources",
                side_effect=lambda *args: (build.output / "build").mkdir(),
            ),
            patch.object(build, "cmake"),
            patch.object(build, "meson"),
            patch.object(build, "run") as run,
        ):
            build.build()
        environment = next(
            call.kwargs["environment"]
            for call in run.call_args_list
            if call.args[0] == "libffi-configure"
        )
        (build.output / "probe.c").write_text("int main(void) { return 0; }\n")
        for command in (
            "$CC $CFLAGS -c probe.c -o probe.o",
            "$LTCC $CFLAGS -c probe.c -o libtool-probe.o",
            "$CC $LDFLAGS probe.o -o probe",
        ):
            result = subprocess.run(
                ["/bin/sh", "-c", command],
                cwd=build.output,
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
        trace = subprocess.run(
            ["/bin/sh", "-c", "$CC $LDFLAGS -### probe.o -o probe"],
            cwd=build.output,
            env=environment,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(trace.returncode, 0, trace.stderr)
        self.assertIn('"/usr/bin/ld"', trace.stderr)
        self.assertIn("declared-relative-path", trace.stderr)
        reload_trace = subprocess.run(
            [
                "/bin/sh",
                "-c",
                "$LTCC $CFLAGS -nostdlib -Wl,-r -### probe.o -o reload.o",
            ],
            cwd=build.output,
            env=environment,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(reload_trace.returncode, 0, reload_trace.stderr)
        self.assertIn('"/usr/bin/ld"', reload_trace.stderr)
        result = subprocess.run([build.output / "probe"], check=False)
        self.assertEqual(result.returncode, 0)

    @unittest.skipIf(os.name == "nt", "Exercises Darwin's libtool configuration")
    def test_libffi_darwin_recipe_avoids_obsolete_module_probe(self):
        compiler = shutil.which("clang")
        if compiler is None:
            self.skipTest("Requires Clang")
        compiler_flags = ["-nostdlib"]
        if sys.platform != "darwin":
            if shutil.which("ld64.lld") is None:
                self.skipTest("Requires ld64.lld for Mach-O cross-linking")
            compiler_flags.extend(["--target=aarch64-apple-macos14", "-fuse-ld=lld"])
        self.args.target = "aarch64-apple-darwin"
        self.args.output = self.root / "libffi-module"
        self.args.cc = Path(compiler)
        self.environment["LT_MULTI_MODULE"] = ""
        with patch("build_native.validate_target"):
            build = NativeBuild(self.args, self.environment)
        with (
            patch(
                "build_native.prepare_sources",
                side_effect=lambda *args: (build.output / "build").mkdir(),
            ),
            patch.object(build, "cmake"),
            patch.object(build, "meson"),
            patch.object(build, "run") as run,
        ):
            build.build()
        environment = next(
            call.kwargs["environment"]
            for call in run.call_args_list
            if call.args[0] == "libffi-configure"
        )
        fixture = Path(__file__).with_name("testdata") / "libffi-single-module.sh"
        probe_environment = {
            **environment,
            "LTCC": shlex.join([compiler, *compiler_flags]),
            "LTCFLAGS": "-Werror",
            "LDFLAGS": "",
            "GREP": shutil.which("grep"),
        }
        build.run(
            "module-probe",
            ["/bin/sh", str(fixture)],
            environment=probe_environment,
        )
        self.assertEqual(
            (build.output / "module-probe.log").read_text(), "capability=no\n"
        )
        source = build.output / "shared.c"
        source.write_text("int ffi_probe(void) { return 1; }\n")
        library = build.output / "libffi-probe.dylib"
        build.run(
            "plain-shared-link",
            [
                compiler,
                *compiler_flags,
                "-Werror",
                "-dynamiclib",
                "-Wl,-fatal_warnings",
                str(source),
                "-o",
                str(library),
            ],
        )
        self.assertEqual(library.read_bytes()[:4], bytes.fromhex("cffaedfe"))

    @unittest.skipIf(
        os.name == "nt", "Windows configure arguments have a separate recipe test"
    )
    def test_libffi_native_prefix_uses_its_declared_library_directory(self):
        build = NativeBuild(self.args, self.environment)
        with (
            patch(
                "build_native.prepare_sources",
                side_effect=lambda *args: (build.output / "build").mkdir(),
            ),
            patch.object(build, "cmake"),
            patch.object(build, "meson"),
            patch.object(build, "run") as run,
        ):
            build.build()
        command = next(
            call.args[1]
            for call in run.call_args_list
            if call.args[0] == "libffi-configure"
        )
        self.assertIn("--disable-multi-os-directory", command)

    @unittest.skipIf(os.name == "nt", "Exercises Autoconf's Unix flag expansion")
    def test_libffi_compiler_receives_literal_defines_without_shell_quotes(self):
        compiler = shutil.which("cc")
        if compiler is None:
            self.skipTest("Requires a C compiler")
        self.args.cc = Path(compiler)
        make = shutil.which("make")
        if make is None:
            self.skipTest("Requires Make")
        for index, (flag, expected) in enumerate(
            [
                ('-DBUILD_FLAG="redacted"', "redacted\n"),
                (r'-DBUILD_FLAG="C:\\voice"', "C:\\voice\n"),
                ('-DBUILD_FLAG="two words"', None),
            ]
        ):
            with self.subTest(flag=flag):
                self.args.c_flag = [flag]
                self.args.output = self.root / str(index)
                build = NativeBuild(self.args, self.environment)
                with (
                    patch(
                        "build_native.prepare_sources",
                        side_effect=lambda *args: (build.output / "build").mkdir(),
                    ),
                    patch.object(build, "cmake"),
                    patch.object(build, "meson"),
                    patch.object(build, "run") as run,
                ):
                    if " " in flag:
                        with self.assertRaisesRegex(ValueError, "libffi flags"):
                            build.build()
                        continue
                    build.build()
                environment = next(
                    call.kwargs["environment"]
                    for call in run.call_args_list
                    if call.args[0] == "libffi-configure"
                )
                (build.output / "probe.c").write_text(
                    "#include <stdio.h>\nint main(void) { puts(BUILD_FLAG); }\n"
                )
                subprocess.run(
                    ["/bin/sh", "-c", "$CC $CFLAGS probe.c -o probe"],
                    cwd=build.output,
                    env=environment,
                    check=True,
                )
                result = subprocess.run(
                    [build.output / "probe"], check=True, capture_output=True, text=True
                )
                self.assertEqual(result.stdout, expected)
                (build.output / "Makefile").write_text(
                    f"CC={compiler}\nCFLAGS={environment['CFLAGS']} -fexceptions\n"
                    "MAKEOVERRIDES=\nall:\n\t$(MAKE) nested\n"
                    "nested:\n\t$(CC) $(CFLAGS) probe.c -o probe\n\t./probe\n"
                )
                result = subprocess.run(
                    [make, "--silent"],
                    cwd=build.output,
                    check=True,
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(result.stdout, expected)

    @unittest.skipIf(os.name == "nt", "Unix install paths; Windows layout is unchanged")
    def test_cmake_installs_relative_library_paths(self):
        for argument, tool in (
            ("cc", "cc"),
            ("cxx", "c++"),
            ("cmake", "cmake"),
            ("make", "make"),
        ):
            setattr(self.args, argument, Path(shutil.which(tool)))
        source = self.root / "library-source"
        source.mkdir()
        self.args.c_flag = ["-DBUILD_FLAG=42"]
        self.args.link_flag = ["-Wl,-rpath,declared-relative-path"]
        (source / "CMakeLists.txt").write_text(
            "cmake_minimum_required(VERSION 3.15)\nproject(relative_paths LANGUAGES C)\n"
            "add_library(fixture SHARED fixture.c)\ninstall(TARGETS fixture DESTINATION lib)\n"
        )
        (source / "fixture.c").write_text("int fixture(void) { return BUILD_FLAG; }\n")
        build = NativeBuild(self.args, self.environment)
        build.output.mkdir()
        build.sources = {"fixture": source}
        build.cmake("fixture", [], bootstrap=True)
        if sys.platform == "darwin":
            from macos_runtime import inspect

            metadata = inspect(build.tools / "lib/libfixture.dylib", self.args.target)
            self.assertEqual(
                (metadata.identity, metadata.rpaths),
                ("@rpath/libfixture.dylib", ("declared-relative-path", "@loader_path")),
            )
        else:
            from linux_runtime import inspect

            metadata = inspect(build.tools / "lib/libfixture.so", self.args.target)
            self.assertEqual(
                (metadata.identity, metadata.rpaths),
                ("libfixture.so", ("29=declared-relative-path:$ORIGIN",)),
            )

    def test_cmake_initial_cache_preserves_literal_settings(self):
        cmake = shutil.which("cmake")
        if cmake is None:
            self.skipTest("Requires CMake")
        build = NativeBuild(self.args, self.environment)
        build.output.mkdir()
        build.sources = {"fixture": self.root}
        literal = '\nC:\\tools;"${literal}"]] ]=] trailing'
        build.cmake_platform = [f"-DCODEX_LITERAL={literal}"]
        with patch.object(build, "run") as run:
            build.cmake("fixture", ["-DPROJECT_OPTION=ON"], bootstrap=True)
        command = run.call_args_list[0].args[1]
        cache = Path(command[command.index("-C") + 1])
        self.assertIn("-DPROJECT_OPTION=ON", command)
        probe = self.root / "probe.cmake"
        output = self.root / "literal.txt"
        probe.write_text('file(WRITE "${OUTPUT}" "${CODEX_LITERAL}")\n')
        subprocess.run(
            [cmake, "-C", cache, f"-DOUTPUT={output}", "-P", probe], check=True
        )
        self.assertEqual(output.read_text(), literal)
        self.assertEqual(
            build.record["cmake_initial_caches"]["fixture"]["CODEX_LITERAL"], literal
        )

    @unittest.skipUnless(os.name == "nt", "Requires native Windows install-script generation")
    def test_cmake_windows_install_preserves_prefix_path(self):
        for argument, tool in (("cmake", "cmake"), ("make", "ninja")):
            selected = shutil.which(tool)
            if selected is None:
                self.skipTest(f"Requires {tool}")
            setattr(self.args, argument, Path(selected))
        source = self.root / "windows-install-source"
        source.mkdir()
        (source / "marker.txt").write_text("installed fixture")
        (source / "CMakeLists.txt").write_text(
            "cmake_minimum_required(VERSION 3.15)\n"
            "project(windows_install LANGUAGES NONE)\n"
            "install(FILES marker.txt DESTINATION share)\n"
        )
        build = NativeBuild(self.args, self.environment)
        build.output.mkdir()
        build.sources = {"fixture": source}
        build.environment["NINJA"] = str(self.args.make)
        try:
            build.cmake("fixture", [])
        finally:
            for log in sorted(build.output.glob("*.log")):
                print(log.read_text(), flush=True)
        self.assertEqual(
            (build.prefix / "share/marker.txt").read_text(), "installed fixture"
        )

    def test_cmake_parallel_builds_match_generator_support(self):
        self.environment["USERPROFILE"] = str(self.root / "user profile")
        for system, suffix in (
            ("Windows", "pc-windows-msvc"),
            ("Linux", "unknown-linux-gnu"),
        ):
            for bootstrap in (True, False):
                with self.subTest(system=system, bootstrap=bootstrap):
                    self.args.target = f"x86_64-{suffix}"
                    self.args.output = self.root / f"{system}-{bootstrap}"
                    with (
                        patch("build_native.platform.system", return_value=system),
                        patch("build_native.platform.machine", return_value="x86_64"),
                    ):
                        build = NativeBuild(self.args, self.environment)
                    build.output.mkdir()
                    build.sources = {"fixture": self.root}
                    build.environment["NINJA"] = str(Path(sys.executable))
                    with patch.object(build, "run") as run:
                        build.cmake("fixture", [], bootstrap=bootstrap)
                    command = run.call_args_list[1].args[1]
                    if system == "Windows" and bootstrap:
                        self.assertEqual(
                            command,
                            [
                                build.toolchain["cmake"],
                                "--build",
                                build.output / "build/fixture",
                            ],
                        )
                    else:
                        self.assertEqual(command[-2:], ["--parallel", self.args.jobs])

    def test_cmake_unused_project_option_remains_fatal(self):
        cmake = shutil.which("cmake")
        if cmake is None:
            self.skipTest("Requires CMake")
        self.args.cmake = Path(cmake)
        source = self.root / "unused-option"
        source.mkdir()
        (source / "CMakeLists.txt").write_text(
            "cmake_minimum_required(VERSION 3.15)\nproject(unused LANGUAGES NONE)\n"
        )
        build = NativeBuild(self.args, self.environment)
        build.output.mkdir()
        build.sources = {"fixture": source}
        with self.assertRaisesRegex(RuntimeError, "UNUSED_PROJECT_OPTION"):
            build.cmake("fixture", ["-DUNUSED_PROJECT_OPTION=ON"], bootstrap=True)

    def test_build_refuses_existing_output_before_reading_sources(self):
        self.args.output.mkdir()
        (self.args.output / "keep").write_text("untouched")
        with self.assertRaises(FileExistsError):
            NativeBuild(self.args, self.environment).build()
        self.assertEqual(
            {p.name: p.read_text() for p in self.args.output.iterdir()},
            {"keep": "untouched"},
        )

    def test_successful_step_rejects_native_warning_diagnostics(self):
        diagnostics = (
            "warning: compiler diagnostic",
            "WARNING: Meson configuration diagnostic",
            "configure: WARNING: feature configuration diagnostic",
            "CMake Warning at CMakeLists.txt:3 (message):\n  Configured warning detail",
            "CMake Deprecation Warning at CMakeLists.txt:1 (cmake_minimum_required):",
            "source.c:12:3: warning: compiler diagnostic",
            "ld.lld: warning: linker diagnostic",
            "source.c(12): warning C4996: compiler diagnostic",
            "LINK : warning LNK4098: linker diagnostic",
        )
        for index, diagnostic in enumerate(diagnostics):
            with self.subTest(diagnostic=diagnostic):
                self.args.output = self.root / f"warning-{index}"
                build = NativeBuild(self.args, self.environment)
                build.output.mkdir()
                command = [
                    sys.executable,
                    "-c",
                    "import sys; print(sys.argv[1], file=sys.stderr)",
                    diagnostic,
                ]
                with self.assertRaisesRegex(RuntimeError, "warning diagnostics"):
                    build.run("warning", command)
                state = json.loads((build.output / "build-state.json").read_text())
                self.assertEqual(state["steps"][-1]["exit_code"], 0)
                self.assertEqual(
                    state["steps"][-1]["warning_diagnostics"], [diagnostic]
                )
                self.assertEqual(
                    (build.output / "warning.log").read_text().strip(), diagnostic
                )
                self.assertFalse((build.output / "built.json").exists())

    def test_successful_step_accepts_source_text_containing_warning_literals(self):
        build = NativeBuild(self.args, self.environment)
        build.output.mkdir()
        output = (
            "source contains warning: example\n"
            'printf("note: warning: example");\n'
            "clean build completed"
        )
        build.run(
            "clean",
            [sys.executable, "-c", "import sys; print(sys.argv[1])", output],
        )
        state = json.loads((build.output / "build-state.json").read_text())
        self.assertEqual(state["steps"][-1]["exit_code"], 0)
        self.assertNotIn("warning_diagnostics", state["steps"][-1])

    def test_failed_step_preserves_its_status_when_it_also_emits_warnings(self):
        build = NativeBuild(self.args, self.environment)
        build.output.mkdir()
        with self.assertRaises(subprocess.CalledProcessError) as error:
            build.run(
                "failed-warning",
                [
                    sys.executable,
                    "-c",
                    "print('warning: failure'); raise SystemExit(23)",
                ],
            )
        self.assertEqual(error.exception.returncode, 23)

    def test_failure_retains_log_and_state_without_completion_marker(self):
        build = NativeBuild(self.args, self.environment)
        build.output.mkdir()
        command = [
            sys.executable,
            "-c",
            "print('synthetic failure'); raise SystemExit(23)",
        ]
        with self.assertRaises(subprocess.CalledProcessError) as error:
            build.run("failure", command)
        self.assertEqual(error.exception.returncode, 23)
        self.assertEqual(
            (build.output / "failure.log").read_text().strip(), "synthetic failure"
        )
        self.assertEqual(
            json.loads((build.output / "build-state.json").read_text()),
            {
                "target": self.args.target,
                "deployment_target": "11.0",
                "flags": {"CFLAGS": [], "CXXFLAGS": [], "LDFLAGS": []},
                "steps": [{"name": "failure", "command": command, "exit_code": 23}],
            },
        )
        self.assertFalse((build.output / "built.json").exists())

    def test_bazel_failure_preserves_configure_context_and_original_error(self):
        for index, (name, status, detail, context) in enumerate(
            (
                ("libffi-configure", 23, "configure failed", "libffi/config.log"),
                ("libffi-build", 0, "warning: no debug symbols", "libffi/config.log"),
                ("libffi-install", 23, "install failed", "libffi/config.log"),
                ("other-build", 23, "other failed", "libffi/config.log"),
                ("libffi-configure", 23, None, "libffi/config.log"),
                (
                    "proxy-libintl-configure",
                    23,
                    "compiler failed",
                    "proxy-libintl/meson-logs/meson-log.txt",
                ),
                ("glib-build", 23, "compile failed", "glib/meson-logs/meson-log.txt"),
                (
                    "gstreamer-install",
                    0,
                    "warning: Meson install diagnostic",
                    "gstreamer/meson-logs/meson-log.txt",
                ),
                (
                    "proxy-libintl-configure",
                    23,
                    None,
                    "proxy-libintl/meson-logs/meson-log.txt",
                ),
            )
        ):
            with self.subTest(name=name, status=status, detail=detail):
                self.args.output = self.root / f"failure-context-{index}"
                build = NativeBuild(self.args, self.environment)
                config = self.root / "bazel-config.json"
                config.write_text(
                    json.dumps(
                        {
                            "archives": [],
                            "prefix": str(self.root / "prefix.tar"),
                            "receipt": str(self.root / "built.json"),
                            "ld": sys.executable,
                            **{
                                tool: str(path)
                                for tool, path in build.toolchain.items()
                            },
                            **{
                                tool: sys.executable
                                for tool in ("ar", "ranlib", "nm", "readelf")
                            },
                        }
                    )
                )

                def fail():
                    build.output.mkdir()
                    if detail is not None:
                        log = build.output / "build" / context
                        log.parent.mkdir(parents=True)
                        log.write_text("native configure probe detail\n")
                    build.run(
                        name,
                        [
                            sys.executable,
                            "-c",
                            "import sys; print(sys.argv[1]); raise SystemExit(int(sys.argv[2]))",
                            detail or "configure failed before log creation",
                            str(status),
                        ],
                    )

                stderr = io.StringIO()
                exception = subprocess.CalledProcessError if status else RuntimeError
                with (
                    patch("bazel_native.sys.argv", ["bazel_native.py", str(config)]),
                    patch("bazel_native.NativeBuild", return_value=build),
                    patch.object(build, "build", side_effect=fail),
                    redirect_stderr(stderr),
                    self.assertRaises(exception) as error,
                ):
                    bazel_native.main()
                if status:
                    self.assertEqual(error.exception.returncode, status)
                self.assertIn(detail or "configure failed", stderr.getvalue())
                self.assertEqual(
                    "native configure probe detail" in stderr.getvalue(),
                    Path(context).parts[0] == name.rsplit("-", 1)[0]
                    and detail is not None,
                )
                self.assertFalse((self.root / "prefix.tar").exists())
                self.assertFalse((self.root / "built.json").exists())

    def test_ambient_native_discovery_variables_are_not_inherited(self):
        inherited = {
            **self.environment,
            "PKG_CONFIG_PATH": "/ambient",
            "CMAKE_PREFIX_PATH": "/ambient",
            "CPATH": "/ambient",
            "CFLAGS": "-I/ambient",
            "LDFLAGS": "-L/ambient",
        }
        environment = NativeBuild(self.args, inherited).environment
        self.assertFalse(any("/ambient" in value for value in environment.values()))
        self.assertEqual(environment["PKG_CONFIG_PATH"], "")

    def test_meson_receives_private_link_inputs_with_spaces(self):
        if platform.system() == "Darwin":
            self.args.cc = Path(shutil.which("cc"))
        self.args.c_flag = [] if os.name == "nt" else ["-DDECLARED_C=1"]
        self.args.cxx_flag = [] if os.name == "nt" else ["-DDECLARED_CXX=1"]
        self.args.link_flag = (
            [] if os.name == "nt" else ["-Ldeclared input with spaces"]
        )
        build = NativeBuild(self.args, self.environment)
        self.assertEqual(
            build.record["flags"],
            {
                "CFLAGS": self.args.c_flag,
                "CXXFLAGS": self.args.cxx_flag,
                "LDFLAGS": self.args.link_flag,
            },
        )
        build.sources = {"meson": self.root / "meson", "glib": self.root / "glib"}
        with patch.object(build, "run"):
            build.meson("glib", [])
        if platform.system() == "Darwin":
            subprocess.run(
                [
                    build.environment["OBJC"],
                    *shlex.split(build.environment["OBJCFLAGS"]),
                    "-x",
                    "objective-c",
                    "-fsyntax-only",
                    "-",
                ],
                input="#if DECLARED_C != 1\n#error missing declared flags\n#endif\n",
                text=True,
                check=True,
            )
        quote = subprocess.list2cmdline if build.windows else shlex.join
        include = f"{'/I' if build.windows else '-I'}{build.prefix / 'include'}"
        self.assertEqual(
            {name: build.environment[name] for name in ("CFLAGS", "CXXFLAGS")},
            {
                "CFLAGS": quote([*self.args.c_flag, include]),
                "CXXFLAGS": quote([*self.args.cxx_flag, include]),
            },
        )
        if build.windows:
            self.assertEqual(
                build.environment["LDFLAGS"],
                quote([*self.args.link_flag, f"/LIBPATH:{build.prefix / 'lib'}"]),
            )
        else:
            self.assertEqual(
                shlex.split(build.environment["LDFLAGS"]),
                [
                    *self.args.link_flag,
                    f"-L{build.prefix / 'lib'}",
                    (
                        "-Wl,-rpath,$ORIGIN:$ORIGIN/.."
                        if build.args.target.endswith("unknown-linux-gnu")
                        else f"-Wl,-rpath,{build.prefix / 'lib'}"
                    ),
                ],
            )

    def test_windows_paths_use_cygpath_and_propagate_failure(self):
        build = NativeBuild(self.args, self.environment)
        build.windows = True
        path = Path("D:/build output/source")
        with patch(
            "build_native.subprocess.check_output",
            return_value="/cygdrive/d/build output/source\n",
        ) as convert:
            self.assertEqual(build.posix_path(path), "/cygdrive/d/build output/source")
        self.assertEqual(
            convert.call_args.args[0],
            [build.toolchain["shell"].with_name("cygpath.exe"), "-u", str(path)],
        )
        with patch(
            "build_native.subprocess.check_output",
            side_effect=subprocess.CalledProcessError(1, "cygpath"),
        ):
            with self.assertRaises(subprocess.CalledProcessError):
                build.posix_path(path)

    def test_windows_rejects_unix_overrides_before_creating_output(self):
        self.args.target = "x86_64-pc-windows-msvc"
        for name, value in (
            ("c_flag", [r"/IC:\SDK\include"]),
            ("ranlib", Path(sys.executable)),
        ):
            with patch("build_native.validate_target"):
                setattr(self.args, name, value)
                with self.assertRaisesRegex(ValueError, "Unix build host"):
                    NativeBuild(self.args, self.environment)
                delattr(self.args, name)
            self.assertFalse(self.args.output.exists())

    def test_windows_recipes_use_explicit_targets_and_posix_paths(self):
        self.environment["USERPROFILE"] = str(self.root / "user profile")
        for architecture, flag in (("x86_64", "-m64"), ("aarch64", "-marm64")):
            with self.subTest(architecture=architecture):
                self.args.target = f"{architecture}-pc-windows-msvc"
                self.args.output = self.root / architecture
                with (
                    patch("build_native.platform.system", return_value="Windows"),
                    patch("build_native.platform.machine", return_value=architecture),
                ):
                    build = NativeBuild(self.args, self.environment)
                self.assertEqual(
                    build.environment["USERPROFILE"], self.environment["USERPROFILE"]
                )
                calls = {}

                def record(name, command, **kwargs):
                    calls[name] = (command, kwargs)
                    (build.output / f"{name}.log").write_text(
                        "-ID:/private/include -LD:/private/lib -lffi\n"
                    )

                with (
                    patch(
                        "build_native.prepare_sources",
                        side_effect=lambda *args: (build.output / "build").mkdir(),
                    ),
                    patch.object(build, "cmake") as cmake,
                    patch.object(build, "meson"),
                    patch.object(build, "run", side_effect=record),
                    patch.object(
                        build,
                        "posix_path",
                        side_effect=lambda path: "/cygdrive/d/" + path.name,
                    ),
                    patch(
                        "build_native.subprocess.check_output",
                        return_value="/usr/share/automake-1.18\n",
                    ),
                ):
                    build.build()
                opus_options = next(
                    call.args[1]
                    for call in cmake.call_args_list
                    if call.args[0] == "opus"
                )
                self.assertEqual(
                    "-DOPUS_PRESUME_NEON=ON" in opus_options, architecture == "aarch64"
                )
                command, kwargs = calls["libffi-configure"]
                host = f"{architecture}-w64-mingw32"
                linker_flags = "-no-undefined -Wc,-link,/IMPLIB:.libs/libffi.lib"
                self.assertEqual(
                    command,
                    [
                        build.toolchain["shell"],
                        "/cygdrive/d/configure",
                        "--prefix=/cygdrive/d/prefix",
                        "--enable-shared",
                        "--disable-static",
                        "--disable-docs",
                        "--disable-multi-os-directory",
                        f"--build={host}",
                        f"--host={host}",
                    ],
                )
                expected = {
                    **build.environment,
                    "CC": f"/cygdrive/d/msvcc.sh {flag}",
                    "CXX": f"/cygdrive/d/msvcc.sh {flag}",
                    "AR": "/usr/share/automake-1.18/ar-lib lib",
                    "RANLIB": ":",
                    "LD": "link",
                    "NM": "dumpbin -symbols",
                    "STRIP": ":",
                    "LDFLAGS": "-no-undefined",
                    "AM_MAKEFLAGS": shlex.quote(f"LTLDFLAGS={linker_flags}"),
                    "CPP": "cl -nologo -EP",
                    "CXXCPP": "cl -nologo -EP",
                    "CPPFLAGS": "-DFFI_BUILDING_DLL",
                    "CONFIG_SHELL": "/cygdrive/d/" + build.toolchain["shell"].name,
                }
                self.assertEqual(
                    kwargs,
                    {"cwd": build.output / "build/libffi", "environment": expected},
                )
                self.assertEqual(calls["libffi-install"][1]["environment"], expected)
                if platform.system() == "Windows":
                    cygwin = os.environ.get("VOICE_CYGWIN_ROOT")
                    make = str(Path(cygwin) / "bin/make.exe") if cygwin else None
                else:
                    make = shutil.which("make")
                if make is None:
                    self.skipTest("GNU make is required for the recursive build check")
                # Mirror libffi's MAKEOVERRIDES reset and recursive hook, without
                # running a compiler or requiring the native source archives.
                directory = build.output / "build/libffi"
                (directory / "Makefile").write_text(
                    "MAKEOVERRIDES =\nLTLDFLAGS = default\n"
                    "all:\n\t@$(MAKE) --no-print-directory $(AM_MAKEFLAGS) nested\n"
                    "nested:\n\t@$(MAKE) --no-print-directory $(AM_MAKEFLAGS) observe\n"
                    "observe:\n\t@printf '%s\\n' \"$(LTLDFLAGS)\"\n"
                )
                result = subprocess.run(
                    [make, "--no-print-directory"],
                    cwd=directory,
                    env={
                        **os.environ,
                        "AM_MAKEFLAGS": kwargs["environment"]["AM_MAKEFLAGS"],
                        "PATH": os.pathsep.join(
                            [str(Path(make).parent), os.environ.get("PATH", "")]
                        ),
                    },
                    capture_output=True,
                    text=True,
                    check=True,
                )
                self.assertEqual(
                    result.stdout.strip(),
                    linker_flags,
                )

    def test_explicit_windows_inputs_replace_ambient_sdk_and_search_paths(self):
        self.args.target = "x86_64-pc-windows-msvc"
        self.args.windows_build_inputs = self.root / "inputs.json"
        selected = {
            "PATH": str(self.root / "selected tools"),
            "INCLUDE": str(self.root / "selected include"),
            "LIB": str(self.root / "selected lib"),
        }
        with (
            patch("build_native.validate_target"),
            patch(
                "build_native.build_environment",
                return_value=(selected, {"target": self.args.target}),
            ),
        ):
            build = NativeBuild(
                self.args,
                {"PATH": "/unrelated", "LIBPATH": "/unrelated"},
            )
        self.assertFalse(
            any("/unrelated" in value for value in build.environment.values())
        )
        self.assertEqual(
            {name: build.environment[name] for name in ("INCLUDE", "LIB")},
            {name: selected[name] for name in ("INCLUDE", "LIB")},
        )
        self.assertEqual(
            build.record["windows_build_inputs"], {"target": self.args.target}
        )
        self.assertFalse(build.output.exists())
