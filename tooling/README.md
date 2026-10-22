# Build tooling

The tooling uses JDK 27, Maven, TestNG, and named Java modules. From the
repository root, run `tooling/mvnw verify` to build the bundled runtime and run
tests, Checkstyle, and PMD.

Building the runtime also requires a host C++17 compiler for the native
compiler scheduler. Linux and macOS use `c++`. Windows uses `cl.exe` from a
Visual Studio developer environment and links the Windows socket library.
The setup-tooling action configures the host MSVC environment before a cache
miss builds the runtime. Runtime caches are specific to the host OS and
architecture; a cached Windows runtime is not a Linux or macOS runtime.

The `schedule-compilers -- <command> [arguments ...]` command runs a Cargo
build under the memory scheduler. The `bazel` command starts the same policy
for local compiler actions when building, testing, running, or collecting
coverage. Remote execution retains the remote worker's resource controls.
