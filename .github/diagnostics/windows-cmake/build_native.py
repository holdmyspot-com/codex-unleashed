#!/usr/bin/env python3
"""Build the candidate native voice prefix with upstream build systems."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shlex
import subprocess
import sys

# Match the package builder: import this script's sibling under PYTHONSAFEPATH,
# without adding the caller's working directory to the module search path.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from prepare_sources import MANIFEST, load_sources, prepare_sources
from windows_build_inputs import build_environment

WARNING_DIAGNOSTIC = re.compile(
    r"""^\s*(?:CMake (?:Deprecation )?Warning\b|(?:[^"'\n]*:\s*)?warning(?:\s+[A-Z]+\d+)?:\s)""",
    re.IGNORECASE,
)

TARGET_SYSTEMS = {
    "apple-darwin": "Darwin",
    "unknown-linux-gnu": "Linux",
    "pc-windows-msvc": "Windows",
}


def validate_target(target, system, machine, libc, deployment_target):
    architecture, separator, suffix = target.partition("-")
    host_architecture = {"arm64": "aarch64", "amd64": "x86_64"}.get(
        machine.lower(), machine.lower()
    )
    if (
        not separator
        or architecture not in ("aarch64", "x86_64")
        or suffix not in TARGET_SYSTEMS
    ):
        raise ValueError(f"Unsupported native voice target: {target}")
    if TARGET_SYSTEMS[suffix] != system or architecture != host_architecture:
        raise ValueError("Use a native build host matching the requested target")
    if system == "Linux" and libc != "glibc":
        raise ValueError("Native Linux voice builds require glibc")
    if system == "Darwin" and not re.fullmatch(
        r"[0-9]+\.[0-9]+(?:\.[0-9]+)?", deployment_target or ""
    ):
        raise ValueError(
            "Declare the macOS deployment target; do not inherit the host default"
        )


class NativeBuild:
    def __init__(self, args, inherited_environment):
        validate_target(
            args.target,
            platform.system(),
            platform.machine(),
            platform.libc_ver()[0],
            args.deployment_target,
        )
        self.args = args
        self.output = args.output.absolute()
        self.prefix = self.output / "prefix"
        self.tools = self.output / "tools"
        self.windows = args.target.endswith("windows-msvc")
        # Compiler drivers such as clang++ select link behavior from argv[0].
        self.toolchain = {
            name: getattr(args, name).absolute()
            for name in ("cc", "cxx", "cmake", "make", "pkg_config", "shell")
        }
        self.toolchain.update(
            (name, path.absolute())
            for name in ("ar", "ranlib", "nm", "readelf")
            if (path := getattr(args, name, None)) is not None
        )
        self.quote = subprocess.list2cmdline if self.windows else shlex.join
        self.flags = {
            variable: list(getattr(args, argument, []))
            for variable, argument in (
                ("CFLAGS", "c_flag"),
                ("CXXFLAGS", "cxx_flag"),
                ("LDFLAGS", "link_flag"),
            )
        }
        if self.windows and (
            any(self.flags.values())
            or any(name in self.toolchain for name in ("ar", "ranlib", "nm", "readelf"))
        ):
            raise ValueError("Explicit toolchain overrides require a Unix build host")
        self.bootstrap_make = (args.bootstrap_make or args.make).absolute()
        for tool in (*self.toolchain.values(), self.bootstrap_make):
            if not tool.is_file():
                raise ValueError(f"Missing build tool: {tool}")
        windows_inputs = getattr(args, "windows_build_inputs", None)
        input_record = None
        if windows_inputs is not None:
            if not self.windows:
                raise ValueError("Windows build inputs require a Windows MSVC target")
            explicit, input_record = build_environment(
                windows_inputs,
                args.target,
                {
                    **self.toolchain,
                    "bootstrap_make": self.bootstrap_make,
                    "python": Path(sys.executable),
                },
            )
            inherited_environment = {**inherited_environment, **explicit}
        if self.windows and not all(
            inherited_environment.get(key) for key in ("INCLUDE", "LIB")
        ):
            raise ValueError(
                "Initialize the standard Visual Studio build environment first"
            )
        self.environment = {
            key: value
            for key, value in inherited_environment.items()
            if key
            in (
                "HOME",
                "TMPDIR",
                "TMP",
                "TEMP",
                "SYSTEMROOT",
                "SystemRoot",
                "WINDIR",
                "COMSPEC",
                "INCLUDE",
                "LIB",
                "LIBPATH",
                "HTTPS_PROXY",
                "HTTP_PROXY",
                "NO_PROXY",
            )
            or (self.windows and key == "USERPROFILE")
        }
        if windows_inputs is not None:
            self.environment.pop("LIBPATH", None)
        paths = [
            str(self.tools / "bin"),
            *(
                []
                if windows_inputs is not None
                else [str(p.parent) for p in self.toolchain.values()]
            ),
        ]
        paths += (
            inherited_environment.get("PATH", "").split(os.pathsep)
            if self.windows
            else ["/usr/bin", "/bin", "/usr/sbin", "/sbin"]
        )
        self.environment.update(
            {
                "PATH": os.pathsep.join(dict.fromkeys(paths)),
                "LANG": "C",
                "LC_ALL": "C",
                "PYTHONDONTWRITEBYTECODE": "1",
                "CC": str(self.toolchain["cc"]),
                "CXX": str(self.toolchain["cxx"]),
                "PKG_CONFIG": str(self.toolchain["pkg_config"]),
                "PKG_CONFIG_PATH": "",
                "PKG_CONFIG_LIBDIR": os.pathsep.join(
                    str(self.prefix / p) for p in ("lib/pkgconfig", "share/pkgconfig")
                ),
                "CMAKE_PREFIX_PATH": str(self.prefix),
                "NINJA": str(
                    self.tools / "bin" / ("ninja.exe" if self.windows else "ninja")
                ),
            }
        )
        self.cmake_platform = []
        self.environment.update(
            (name, self.quote(flags)) for name, flags in self.flags.items() if flags
        )
        self.environment.update(
            (name.upper(), self.quote([str(self.toolchain[name])]))
            for name in ("ar", "ranlib", "nm", "readelf")
            if name in self.toolchain
        )
        if args.target.endswith("apple-darwin"):
            self.environment["MACOSX_DEPLOYMENT_TARGET"] = args.deployment_target
            self.cmake_platform = [
                f"-DCMAKE_OSX_DEPLOYMENT_TARGET={args.deployment_target}",
                "-DCMAKE_INSTALL_NAME_DIR=@rpath",
                "-DCMAKE_INSTALL_RPATH=@loader_path",
            ]
        elif not self.windows:
            self.cmake_platform = [
                "-DCMAKE_BUILD_RPATH_USE_ORIGIN=ON",
                "-DCMAKE_INSTALL_RPATH=$ORIGIN",
            ]
        self.record = {
            "target": args.target,
            "deployment_target": args.deployment_target,
            "flags": self.flags,
            "steps": [],
        }
        if input_record is not None:
            self.record["windows_build_inputs"] = input_record

    def run(self, name, command, cwd=None, environment=None):
        command = [str(part) for part in command]
        step = {"name": name, "command": command}
        self.record["steps"].append(step)
        print(f"Building {name}", flush=True)
        with (self.output / f"{name}.log").open("w", encoding="utf-8") as log:
            result = subprocess.run(
                command,
                cwd=cwd or self.output,
                env=environment or self.environment,
                stdout=log,
                stderr=subprocess.STDOUT,
                check=False,
            )
        step["exit_code"] = result.returncode
        warning_diagnostics = []
        if result.returncode == 0:
            in_warning = False
            lines = (self.output / f"{name}.log").read_text(
                encoding="utf-8", errors="replace"
            )
            for line in lines.splitlines():
                if WARNING_DIAGNOSTIC.match(line):
                    warning_diagnostics.append(line)
                    in_warning = True
                elif in_warning and (not line or line[0].isspace()):
                    warning_diagnostics[-1] += "\n" + line
                else:
                    in_warning = False
            warning_diagnostics = [warning.rstrip() for warning in warning_diagnostics]
            if warning_diagnostics:
                step["warning_diagnostics"] = warning_diagnostics

        (self.output / "build-state.json").write_text(
            json.dumps(self.record, indent=2) + "\n", encoding="utf-8"
        )
        result.check_returncode()
        if warning_diagnostics:
            raise RuntimeError(
                f"Native build step {name} completed with warning diagnostics:\n"
                + "\n".join(warning_diagnostics)
            )

    def posix_path(self, path):
        if not self.windows:
            return path.as_posix()
        return subprocess.check_output(
            [self.toolchain["shell"].with_name("cygpath.exe"), "-u", str(path)],
            env=self.environment,
            text=True,
        ).strip()

    def cmake(self, name, options, *, bootstrap=False):
        directory = self.output / "build" / name
        prefix = self.tools if bootstrap else self.prefix
        generator = (
            ("NMake Makefiles" if self.windows else "Unix Makefiles")
            if bootstrap
            else "Ninja"
        )
        make = self.bootstrap_make if bootstrap else self.environment["NINJA"]
        settings = {
            "CMAKE_MAKE_PROGRAM": str(make),
            "CMAKE_BUILD_TYPE": "Release",
            "CMAKE_INSTALL_PREFIX": str(prefix),
            "CMAKE_INSTALL_LIBDIR": "lib",
            "CMAKE_C_COMPILER": str(self.toolchain["cc"]),
            "CMAKE_CXX_COMPILER": str(self.toolchain["cxx"]),
            "CMAKE_PREFIX_PATH": str(self.prefix),
            "CMAKE_FIND_USE_PACKAGE_REGISTRY": "OFF",
            "CMAKE_FIND_USE_SYSTEM_PACKAGE_REGISTRY": "OFF",
            "CMAKE_FIND_USE_CMAKE_ENVIRONMENT_PATH": "OFF",
            "FETCHCONTENT_FULLY_DISCONNECTED": "ON",
        }
        settings.update(
            (f"CMAKE_{tool.upper()}", str(self.toolchain[tool]))
            for tool in ("ar", "ranlib")
            if tool in self.toolchain
        )
        settings.update(option[2:].split("=", 1) for option in self.cmake_platform)
        cache = self.output / f"{name}-initial-cache.cmake"
        entries = []
        for key, value in settings.items():
            delimiter = ""
            while f"]{delimiter}]" in value:
                delimiter += "="
            entries.append(
                f'set({key} [{delimiter}[\n{value}]{delimiter}] CACHE STRING "")\n'
            )
        cache.write_text("".join(entries), encoding="utf-8")
        self.record.setdefault("cmake_initial_caches", {})[name] = settings
        self.run(
            name + "-configure",
            [
                self.toolchain["cmake"],
                "-S",
                self.sources[name],
                "-B",
                directory,
                "-G",
                generator,
                "-C",
                cache,
                *options,
            ],
        )
        build_command = [self.toolchain["cmake"], "--build", directory]
        if generator in ("Ninja", "Unix Makefiles"):
            build_command.extend(["--parallel", self.args.jobs])
        self.run(name + "-build", build_command)
        self.run(name + "-install", [self.toolchain["cmake"], "--install", directory])

    def meson(self, name, options):
        directory = self.output / "build" / name
        meson = [sys.executable, self.sources["meson"] / "meson.py"]
        include = f"{'/I' if self.windows else '-I'}{self.prefix / 'include'}"
        link = (
            [f"/LIBPATH:{self.prefix / 'lib'}"]
            if self.windows
            else [f"-L{self.prefix / 'lib'}", f"-Wl,-rpath,{self.prefix / 'lib'}"]
        )
        if self.args.target.endswith("unknown-linux-gnu"):
            link[-1] = "-Wl,-rpath,$ORIGIN:$ORIGIN/.."

        meson_flags = {name: list(flags) for name, flags in self.flags.items()}
        resource_prefix = "-resource-dir="
        link_resources = [
            flag for flag in self.flags["LDFLAGS"] if flag.startswith(resource_prefix)
        ]
        if link_resources:
            # Meson appends compilation flags to its link command. Preserve the
            # declared linker resource tree, which includes headers and runtimes.
            for variable in ("CFLAGS", "CXXFLAGS"):
                meson_flags[variable] = [
                    link_resources[-1] if flag.startswith(resource_prefix) else flag
                    for flag in meson_flags[variable]
                ]

        self.environment.update(
            {
                name: self.quote([*flags, *(link if name == "LDFLAGS" else [include])])
                for name, flags in meson_flags.items()
            }
        )
        if self.args.target.endswith("apple-darwin"):
            self.environment["OBJC"] = str(self.toolchain["cc"])
            self.environment["OBJCFLAGS"] = self.environment["CFLAGS"]
        self.run(
            name + "-configure",
            [
                *meson,
                "setup",
                directory,
                self.sources[name],
                f"--prefix={self.prefix}",
                "--libdir=lib",
                "--buildtype=release",
                "--wrap-mode=nofallback",
                "-Dauto_features=disabled",
                "-Ddefault_library=shared",
                "-Dpkgconfig.relocatable=true",
                *options,
            ],
        )
        self.run(
            name + "-build", [*meson, "compile", "-C", directory, "-j", self.args.jobs]
        )
        self.run(
            name + "-install", [*meson, "install", "-C", directory, "--no-rebuild"]
        )

    def build(self):
        self.output.mkdir()
        manifest = MANIFEST.read_bytes()
        prepare_sources(self.args.archives, self.output / "sources", manifest)
        self.sources = {
            s.name: self.output / "sources" / s.root for s in load_sources(manifest)
        }
        self.record["manifest_sha256"] = hashlib.sha256(manifest).hexdigest()
        self.record["tools"] = {
            name: str(path) for name, path in self.toolchain.items()
        }
        self.record["python"] = sys.version
        self.run("cmake-version", [self.toolchain["cmake"], "--version"])
        self.run("pkg-config-version", [self.toolchain["pkg_config"], "--version"])
        self.cmake(
            "ninja",
            ["-DBUILD_TESTING=OFF", "-DNINJA_REGENERATE_LEXERS=OFF"],
            bootstrap=True,
        )
        self.cmake(
            "zlib",
            [
                "-DZLIB_BUILD_TESTING=OFF",
                "-DZLIB_BUILD_SHARED=ON",
                "-DZLIB_BUILD_STATIC=OFF",
            ],
        )
        self.cmake(
            "pcre2",
            [
                "-DBUILD_SHARED_LIBS=ON",
                "-DBUILD_STATIC_LIBS=OFF",
                "-DPCRE2_BUILD_TESTS=OFF",
                "-DPCRE2_BUILD_PCRE2GREP=OFF",
                "-DPCRE2_SUPPORT_LIBZ=OFF",
                "-DPCRE2_SUPPORT_LIBBZ2=OFF",
                "-DPCRE2_SUPPORT_LIBREADLINE=OFF",
                "-DPCRE2_SUPPORT_LIBEDIT=OFF",
            ],
        )
        ffi_build = self.output / "build/libffi"
        ffi_build.mkdir()
        environment = self.environment.copy()
        configure_options = []
        if self.windows:
            wrapper = shlex.quote(self.posix_path(self.sources["libffi"] / "msvcc.sh"))
            architecture = (
                "-m64" if self.args.target.startswith("x86_64-") else "-marm64"
            )
            # Match upstream's MSVC recipe, including native ARM64 outputs
            # from x64-emulated Cygwin tools. Never infer the target from uname.
            host = self.args.target.partition("-")[0] + "-w64-mingw32"
            configure_options = [f"--build={host}", f"--host={host}"]
            automake = subprocess.check_output(
                [
                    self.toolchain["shell"],
                    "--noprofile",
                    "--norc",
                    "-c",
                    "automake-1.18 --print-libdir",
                ],
                env=environment,
                text=True,
            ).strip()
            environment.update(
                {
                    "CC": f"{wrapper} {architecture}",
                    "CXX": f"{wrapper} {architecture}",
                    "AR": f"{shlex.quote(automake + '/ar-lib')} lib",
                    "RANLIB": ":",
                    "LD": "link",
                    "NM": "dumpbin -symbols",
                    "STRIP": ":",
                    "LDFLAGS": "-no-undefined",
                    # Libffi clears MAKEOVERRIDES. Use its recursion hook to
                    # name the import library expected by libtool's installer.
                    "AM_MAKEFLAGS": shlex.quote(
                        "LTLDFLAGS=-no-undefined -Wc,-link,/IMPLIB:.libs/libffi.lib"
                    ),
                    "CPP": "cl -nologo -EP",
                    "CXXCPP": "cl -nologo -EP",
                    "CPPFLAGS": "-DFFI_BUILDING_DLL",
                    "CONFIG_SHELL": self.posix_path(self.toolchain["shell"]),
                }
            )
        else:
            ffi_flags = {name: list(flags) for name, flags in self.flags.items()}
            if self.args.target.endswith("apple-darwin"):
                # WORKAROUND: use libtool's documented control to omit the
                # obsolete Darwin module flag for libffi's C shared library.
                # https://github.com/holdmyspot-com/codex-unleashed/issues/61
                # Remove when bundled libtool skips its obsolete module probe.
                environment["LT_MULTI_MODULE"] = "1"

                # Libtool's partial links need -r, which ld64.lld does not support.
                ffi_flags["LDFLAGS"].append("-fuse-ld=/usr/bin/ld")
                # LTCC also compiles feature probes, where linker flags are invalid.
                # Its partial-link command omits LDFLAGS, so select the linker only
                # when this driver is linking.
                driver = ffi_build / "ltcc.sh"
                if shlex.quote(str(driver)) != str(driver):
                    raise ValueError("libffi driver path cannot require shell quoting")
                compiler = shlex.quote(str(self.toolchain["cc"]))
                driver.write_text(
                    "#!/bin/sh\n"
                    'for argument in "$@"; do\n'
                    '  case "$argument" in\n'
                    f'    -c|-E|-S|-fsyntax-only|-M|-MM) exec {compiler} "$@" ;;\n'
                    "  esac\n"
                    "done\n"
                    f'exec {compiler} -fuse-ld=/usr/bin/ld "$@"\n'
                )
                driver.chmod(0o755)
                environment["LTCC"] = str(driver)
            for name in ("ar", "ranlib"):
                if name in self.toolchain:
                    path = str(self.toolchain[name])
                    if shlex.quote(path) != path:
                        raise ValueError(
                            f"libffi {name} path cannot require shell quoting"
                        )
            # Response files preserve compiler arguments through Autoconf's word
            # splitting and Make/libtool's shell expansion without losing flags
            # added by configure (such as -fexceptions).
            for name, flags in ffi_flags.items():
                if any(
                    any(character.isspace() for character in flag) for flag in flags
                ):
                    raise ValueError("libffi flags cannot contain whitespace")
                if flags:
                    response = ffi_build / f"{name.lower()}.rsp"
                    if shlex.quote(str(response)) != str(response):
                        raise ValueError(
                            "libffi response-file path cannot require shell quoting"
                        )
                    response.write_text(
                        "\n".join(
                            '"' + flag.replace("\\", "\\\\").replace('"', '\\"') + '"'
                            for flag in flags
                        )
                        + "\n"
                    )
                    environment[name] = f"@{response}"
            if self.args.target.endswith("unknown-linux-gnu"):
                # Libtool otherwise drops Clang's declared CRT/runtime options.
                environment["AM_LTLDFLAGS"] = shlex.join(
                    argument
                    for flag in self.flags["LDFLAGS"]
                    for argument in ("-Xcompiler", flag)
                )
        self.run(
            "libffi-configure",
            [
                self.toolchain["shell"],
                self.posix_path(self.sources["libffi"] / "configure"),
                f"--prefix={self.posix_path(self.prefix)}",
                "--enable-shared",
                "--disable-static",
                "--disable-docs",
                "--disable-multi-os-directory",
                *configure_options,
            ],
            cwd=ffi_build,
            environment=environment,
        )
        self.run(
            "libffi-build",
            [self.toolchain["make"], f"-j{self.args.jobs}"],
            cwd=ffi_build,
            environment=environment,
        )
        self.run(
            "libffi-install",
            [self.toolchain["make"], "install"],
            cwd=ffi_build,
            environment=environment,
        )
        if self.windows:
            self.run(
                "libffi-pkg-config",
                [self.toolchain["pkg_config"], "--cflags", "--libs", "libffi"],
            )
            if "/cygdrive/" in (self.output / "libffi-pkg-config.log").read_text():
                raise ValueError("pkg-config did not relocate libffi to native paths")
        self.cmake(
            "opus",
            [
                "-DOPUS_BUILD_SHARED_LIBRARY=ON",
                "-DOPUS_BUILD_TESTING=OFF",
                "-DOPUS_BUILD_PROGRAMS=OFF",
                # Windows ARM64 guarantees NEON; upstream misses its ARM64 spelling.
                *(
                    ["-DOPUS_PRESUME_NEON=ON"]
                    if self.args.target == "aarch64-pc-windows-msvc"
                    else []
                ),
            ],
        )
        self.meson("proxy-libintl", [])
        self.meson(
            "glib",
            [
                "-Dtests=false",
                "-Dinstalled_tests=false",
                "-Dnls=disabled",
                "-Ddocumentation=false",
                "-Dintrospection=disabled",
                "-Dlibmount=disabled",
                "-Dselinux=disabled",
                "-Dxattr=false",
            ],
        )
        self.meson(
            "gstreamer",
            [
                "-Dregistry=false",
                "-Doption-parsing=false",
                "-Dtracer_hooks=false",
                "-Dgst_parse=false",
                "-Dtools=disabled",
                "-Dptp-helper=disabled",
            ],
        )
        self.meson(
            "gst-plugins-base",
            [
                "-Dapp=enabled",
                "-Daudioconvert=enabled",
                "-Daudioresample=enabled",
                "-Dopus=enabled",
                "-Dgl=disabled",
            ],
        )
        self.meson("gst-plugins-good", ["-Drtp=enabled", "-Drtpmanager=enabled"])
        (self.output / "built.json").write_text(
            json.dumps(self.record, indent=2) + "\n", encoding="utf-8"
        )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in (
        "archives",
        "output",
        "cc",
        "cxx",
        "cmake",
        "make",
        "pkg-config",
        "shell",
    ):
        parser.add_argument(f"--{name}", type=Path, required=True)
    parser.add_argument(
        "--bootstrap-make",
        type=Path,
        help="NMake on Windows; defaults to --make elsewhere",
    )
    parser.add_argument(
        "--windows-build-inputs",
        type=Path,
        help="Explicit Windows tool and SDK selection; no unrelated PATH fallback",
    )
    for name in ("ar", "ranlib", "nm", "readelf"):
        parser.add_argument(f"--{name}", type=Path)
    for name in ("c-flag", "cxx-flag", "link-flag"):
        parser.add_argument(f"--{name}", action="append", default=[])
    parser.add_argument("--target", required=True)
    parser.add_argument(
        "--deployment-target",
        help="Required on macOS; use the existing supported release minimum",
    )
    parser.add_argument("--jobs", type=int, default=8)
    args = parser.parse_args()
    if sys.version_info < (3, 12) or args.jobs < 1:
        parser.error("Python 3.12+ and a positive --jobs value are required")
    NativeBuild(args, os.environ).build()


if __name__ == "__main__":
    main()
