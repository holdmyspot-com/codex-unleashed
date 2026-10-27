# Build scheduler investigation

## Required behavior

Step 3 of [the restart plan](../plan.md) requires one memory-responsive scheduling
policy for Cargo and Bazel on Linux, macOS and Windows, including both supported
architectures of each OS. The owner specifies:

1. Disable machine swap/pagefiles.
2. Start with the build tool's default job count.
3. Block a job's unsatisfied memory allocation and queue that job for continuation.
4. When a job completes, resume paused jobs from oldest to newest before admitting
   new work. With no paused jobs, increase admission enough to expect blocking.
5. Preserve completed build outputs and accurate failure reporting.

The owner selects investigation of exact allocation blocking before choosing an
implementation. Proactive pressure-based suspension is not an approved substitute.
The owner separately permits terminating and retrying a selected unfinished job
when retained memory prevents progress. This fallback does not establish that an
allocation can be blocked or that a killed process can resume.

When machine swap cannot be disabled, the owner permits monitoring swap usage
and terminating the newest unfinished build jobs when usage increases. Aborted
jobs retain their original queue positions and become eligible for retry when
a running job completes. Preserve completed outputs; terminate the selected
job's children as well, and wait for their exits before reassessing memory.

Swap occupancy alone cannot establish whether another termination is needed:
the newest job may occupy RAM while older jobs or unrelated processes own the
swapped pages. Killing that job can relieve pressure without reducing observed
swap occupancy. A loop that requires occupancy to decrease can consequently
kill every build job. The fallback must verify relief using native memory
pressure, available memory and paging activity alongside swap occupancy, and
retain the oldest running job while stopping new admissions. If that job alone
cannot progress within available memory, report insufficient capacity rather
than repeatedly killing and retrying it. Retry eligibility does not bypass the
memory check or the original FIFO order. Native behavior and Cargo/Bazel retry
integration remain unverified.

No scheduler implementation is selected or accepted by this document. Linux
swap-control checks pass below; allocation-blocking, FIFO continuation and
build-tool integration acceptance remain open. Existing two-job Linux limits remain until replacement
acceptance; the active baseline build is not changed by this investigation.

## Existing project code

Frozen old main is `545479059ac86394049b6efd3bf4297b1ca18d17`. Its
`BazelCommands.java` applies fixed hosted-macOS limits, `run-bazel-ci.sh` applies
fixed local limits separately from remote capacity, and `LocalRustCommand.java`
owns cache directories. No dynamic-memory scheduler was identified in that
snapshot or its reachable Java history. Searches of Java changes for memory,
concurrency, parallelism, jobserver, processor count and job-limit terms found
fixed limits and unrelated memory references. This conclusion covers that branch's
reachable history; it does not establish absence in other histories or external
projects.

## Operating-system evidence

| Candidate | Established behavior | Applicability and remaining evidence |
| --- | --- | --- |
| Linux cgroup v1 `memory.oom_control` | With its OOM killer disabled, accountable memory requests can sleep on the cgroup OOM waitqueue. Increasing the limit or reducing usage lets them continue. | A plausible exact-blocking facility. Requires an available, writable v1 memory controller and a controller outside the blocked workload. Native allocation-block/continue and ordering tests are unrun. |
| Linux cgroup v2 `memory.high` / `memory.max` | `memory.high` throttles/reclaims rather than imposing an absolute no-allocation boundary. `memory.max` can invoke the OOM killer when reclaim cannot satisfy the limit. | These documented behaviors do not establish the requested block-and-resume contract. This executor mounts cgroup2 read-only; `memory.high`, `memory.max` and `memory.swap.max` are not writable, and v1 `memory.oom_control` is absent. This is local availability evidence, not hosted-runner evidence. |
| Windows job-object memory limits | A commit request exceeding a hard process/job memory limit fails. Notification limits can notify while allowing allocation to continue. | Neither behavior alone blocks the failed request for later continuation. Native allocation interception remains to be evaluated. |
| macOS pressure notifications / process suspension | Apple's documentation describes pressure notification and memory recovery, not an established caller-controlled allocation-wait contract. | Exact allocation blocking, native swap control and compatibility with installed compilers remain unknown. No native macOS probe has run. |

Authoritative contracts: [Linux v1 OOM control](https://www.kernel.org/doc/html/v5.5/admin-guide/cgroup-v1/memory.html#oom-control),
[Linux v2 memory controls](https://www.kernel.org/doc/html/latest/admin-guide/cgroup-v2.html),
[Windows job memory limits](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-jobobject_basic_limit_information),
[Windows heap allocation failure](https://learn.microsoft.com/en-us/windows/win32/api/heapapi/nf-heapapi-heapalloc),
and [Apple memory-pressure notifications](https://github.com/apple-oss-distributions/xnu/blob/main/doc/vm/memorystatus_notify.md).

A small owned Linux subprocess allocates 32 MiB, then receives `SIGSTOP` and
`SIGCONT`. `/proc/<pid>/status` reports `VmRSS: 43024 kB` both before suspension
and after its stopped-state receipt. The subprocess is resumed, terminated and
reaped afterward. This establishes that ordinary suspension need not release its
resident memory; it does not exercise allocation exhaustion or prove the scheduler.

## Allocation interception and reuse candidates

Intercepting one allocator API cannot yet be treated as coverage of all build
memory. Rust 1.95.0's [compiler entry point](https://github.com/rust-lang/rust/blob/1.95.0/compiler/rustc/src/main.rs)
documents its distribution jemalloc override across Rust and LLVM. Any proposed
interceptor must demonstrate coverage against the actual installed compiler,
linker and required build-script children, including mappings/page faults and
failure paths, rather than only a synthetic `malloc` call. Windows
[`HeapAlloc`](https://learn.microsoft.com/en-us/windows/win32/api/heapapi/nf-heapapi-heapalloc)
can return `NULL` without raising an exception; a debugger's exception notification
alone therefore does not cover every heap-allocation failure.

The existing [mr boxington scheduler](https://mr-boxington.jdx.dev/configuration)
is a reuse candidate for comparison, not a selected dependency: its documented
experimental suspension is Linux-only, uses writable cgroup v2 delegation and
reacts to pressure. That contract does not satisfy the owner's requested exact
allocation blocking on all three operating systems.

The investigation must next establish a native blocking boundary for each OS and
whether swap/pagefiles can actually be disabled on the selected free hosted
runners. A candidate must demonstrate an unsatisfied allocation waiting while
independent work completes, followed by successful continuation of that same
allocation. Then establish FIFO resumption, default-concurrency startup, admission
growth and the approved targeted-retry escape from retained-memory deadlock.
Only after those premises hold can Cargo and Bazel integration be accepted. Keep
unresolved paths unknown rather than substituting pressure prediction or claiming
cross-platform support from a Linux-only result.

## Local allocation-interception probe

Frida 17.24.3 is evaluated in an isolated temporary directory, without adding a
project dependency. Its published Python wheels cover Linux, macOS and Windows
on x86-64 and ARM64. Wheel availability establishes distribution coverage only;
the native probe below runs on Linux x86-64 alone. The evaluated Linux wheel has
SHA-256 `2ba706b7385d56111af5c6cced55361168d3d93babdb300d2ea2b7f76d58a289`.

An owned C subprocess reaches `main`, reports readiness and waits for input before
calling `malloc(268435456)`. The controller attaches to its resolved `malloc`
import and uses Frida's documented `Interceptor.replace` with a `NativeCallback`.
The replacement calls the original allocator through `NativeFunction`. A null
result reports `ALLOCATION_BLOCKED`, waits and retries inside that same call
instead of returning null to the fixture.

After installing the hook, the controller restricts only that subprocess's
`RLIMIT_AS` to its current virtual size plus 32 MiB. This deliberately makes the
256 MiB request fail. The observed sequence is:

1. The fixture reports readiness and the controller sets the address-space limit
   to 529,600,512 bytes.
2. The hook reports `ALLOCATION_BLOCKED`; the fixture has not reported completion.
3. An independent process exits successfully. The controller restores the
   subprocess's previous address-space limit.
4. The same allocator call succeeds; the fixture writes the first and last bytes,
   reports `ALLOCATION_COMPLETED`, frees its allocation and terminates. The hook
   also reports `ALLOCATION_RESUMED`.

This establishes waiting and successful retry of a deliberately failed allocator
call. The address-space limit models allocation denial; independent process
completion does not itself free the allowance. Physical RAM exhaustion, complete
page commitment, FIFO admission, retry deadlocks and build-tool integration are
not exercised. Polling the original allocator is diagnostic behavior, not a
selected scheduling policy. All fixture processes are owned and terminated on
the controller's cleanup path.

Separate inspection of the installed Rust 1.95.0 executable locates its own
exported `malloc`, `calloc`, `realloc` and `free`, in addition to libc's separate
exports. A Frida-attached `rustc --version` exits normally after symbol inspection.
This confirms attachment and symbol availability, not interception of its
compilation allocations. Looking up a single export immediately after spawn first
fails, while enumerating the module's exports succeeds in the subsequent probe;
the initialization timing remains unisolated. Native CModule/`replaceFast`
attempts also let the fixture receive null without the blocking marker; their
cause remains unknown. The passing result above uses the ordinary callback
replacement, not those attempts.

A subsequent tiny Rust compilation (`--crate-type=lib --emit=metadata`) observes
calls to all five compiler-exported allocator entries: `malloc`, `calloc`,
`realloc`, `aligned_alloc` and `posix_memalign`. Its 2,412-byte metadata output is
produced without compiler diagnostics. This probe attaches entry observers; it
does not replace these allocators or force a compiler allocation failure. A
malloc-only replacement has not established coverage of the other observed
compiler entry points. Linker and build-script child coverage remains unrun.

The next decisive checks must cover compiler allocation failure and physical
memory commitment. This executor has `vm.overcommit_memory=1` (always overcommit);
the [kernel's overcommit contract](https://www.kernel.org/doc/html/latest/mm/overcommit-accounting.html)
distinguishes that policy from allocation-time commit rejection in mode 2 and
identifies implicit stack growth as another consideration. An allocator hook
cannot be assumed to intercept a later page fault merely because allocation
returns an address. Neither the machine's overcommit policy nor swap settings
are changed by these probes. No production scheduler or cross-platform guarantee
follows from this fixture.

Candidate interface: [Frida JavaScript API](https://frida.re/docs/javascript-api/)
(`Interceptor.replace`, `NativeCallback`, `NativeFunction` and `Thread.sleep`).

## Swap-control prerequisites

The `inspect` operation of the
[native prerequisite diagnostic](../.github/workflows/scheduler-prerequisites.yml)
uses the existing release matrix and records image identity, active swap/pagefiles
and relevant control availability without changing memory settings or compiling
Codex. Each diagnostic job has a five-minute limit and keeps its evidence for one
day. Its result is an inventory, not acceptance of a swap-disable operation or
allocation-blocking mechanism. Local actionlint 1.7.12 rejects the documented
`queue` key, as already established for the baseline workflow; a disposable input
omitting only that key passes the remaining lint checks.

Its separate manual `verify-swap-control` operation tests Linux `swapoff --all`
and the macOS `vm.swap_enabled` runtime setter on disposable hosted VMs, without
compilation. The macOS probe restores the previous setting if its setter accepts
the change. A failed control operation fails its job and still uploads evidence;
it does not become a successful no-swap result. Windows is excluded from this
operation because a supported active-pagefile removal operation has not been
selected; its read-only inventory remains available. The workflow is manual-only
after the initial inventory, so edits do not repeat completed inspections.

Native swap-control acceptance remains incomplete. In particular, changing a
persistent setting does not prove that the current build has no active swap:

- Microsoft's [Win32_PageFileSetting contract](https://learn.microsoft.com/en-us/windows/win32/cimwin32prov/win32-pagefilesetting)
  describes pagefile settings used at startup, distinct from the current state
  exposed by `Win32_PageFileUsage`. Deleting settings alone is insufficient
  evidence that the current runner's active pagefiles have been disabled.
- Apple's published XNU snapshot
  [`f6217f891ac0bb64f3d375211650a4c1ff8ca1ea`](https://github.com/apple-oss-distributions/xnu/blob/f6217f891ac0bb64f3d375211650a4c1ff8ca1ea/bsd/kern/kern_sysctl.c)
  declares `vm.swap_enabled` and `vm.compressor_mode` with `CTLFLAG_RD`. Those
  sysctl entries are not runtime setters in that source. This does not establish
  that every other swap-control mechanism is unavailable, or identify the kernel
  running on a selected hosted image.

Acceptance needs a supported control operation and a runtime observation of its
effect on each selected native runner. Do not count a successful configuration
edit, zero current swap usage, or an unused pagefile as disabling swap.

### Hosted read-only inventory

Run [`40548714734`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40548714734)
at `94ff9c78954410adf99ba7d566dc91e0b16536cf` passes all six native inspections.
Each inspection job takes 4–8 seconds; the workflow performs no compilation,
cache restoration or memory-setting change. Artifact upload and owned-directory
cleanup pass on every target.

| Native target | Image version | Observed runtime swap/pagefiles |
| --- | --- | --- |
| Linux x86-64 | `ubuntu24`, `20261026.354.1` | `/swapfile`, 3,221,221,376 bytes, unused; overcommit mode 0. |
| Linux ARM64 | `ubuntu24-arm64`, `20261026.166.1` | `/swapfile`, 3,221,221,376 bytes, unused; overcommit mode 0. |
| macOS x86-64 | `macos15`, `20260824.0482.1` | `vm.swap_enabled=1`, `vm.compressor_mode=4`; current swap total and usage are zero. |
| macOS ARM64 | `macos15`, `20260907.0337.1` | `vm.swap_enabled=1`, `vm.compressor_mode=4`; current swap total and usage are zero. |
| Windows x86-64 | `win25-vs2026`, `20261021.278.1` | Active `D:\pagefile.sys`, 2,944 MiB allocated, unused. |
| Windows ARM64 | `win11-vs2026-arm64`, `20261022.194.1` | Active `C:\pagefile.sys`, 2,944 MiB allocated, unused; startup settings instead name `D:\pagefile.sys`. |

Both Linux hosts mount writable cgroup v2 and list the memory controller. The
probe's unprivileged checks of root-level `memory.oom_control`, `memory.high`,
`memory.max` and `memory.swap.max` find no readable/writable entries; the process
belongs to `/system.slice/hosted-compute-agent.service`. This does not test control
files in that service or creation of a delegated child cgroup. `sudo -n true`
succeeds on both Linux and both macOS hosts. The Windows token reports
`SeDebugPrivilege` enabled and `SeCreatePagefilePrivilege` disabled; enabling that
privilege or controlling active pagefiles is not attempted.

The Windows ARM64 observation directly demonstrates why startup settings cannot
stand in for runtime state: its configured drive differs from its active drive.
The macOS observations likewise show that zero allocated swap does not imply
`vm.swap_enabled=0`. This inventory alone does not accept a swap-disable operation
or an allocation-failure mechanism; the subsequent control results follow below.

The following one-day artifacts contain `identity.txt` and the platform's raw
memory inventory. The SHA-256 values are computed from the downloaded memory
inventory files, not inferred from the job's success or the artifact API digest.

| Target | Artifact ID | Memory-inventory SHA-256 |
| --- | --- | --- |
| Linux x86-64 | `12753831449` | `063b1c4c60d330ced5c1b0d7f422ef4bd89bd644fa1b96ecdaaeb662dea85ed2` |
| Linux ARM64 | `12754206930` | `2b2c7fb48a3a09a42d38d8f638cb0a80eb13826ff52a8a57fa75dc66b036e274` |
| macOS x86-64 | `12752834835` | `b8b0aa01ea6b8ea947ed1ddd7b04338e2a4329e51d38407384731635bcfc450f` |
| macOS ARM64 | `12754386627` | `d4d98ffec72f1a2cd39b8cbeff9b08cc8c9bdcaf063a80ee6ae5f911742bf651` |
| Windows x86-64 | `12752799803` | `0ff900fd21babd196be5084455bf95ed61f0bb5f1880199cd7a7b1f008a1b60e` |
| Windows ARM64 | `12753069375` | `a73b8ee6e1b3a02dfb923f46320d6604564eb7c38c5e709c4942b4afdced5995` |

The download operation removes its own temporary directory after reading and
hashing these files. This durable summary preserves the established observations
after the short-lived artifacts expire; a later native probe must record its own
image identity rather than silently treating these images as unchanged.

### Native swap-control results

Run [`40550597505`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40550597505)
at `39d446bf6f0caa857079fb3105784cede7f658a3` exercises only the four Linux/macOS
swap-control jobs. Both Linux jobs pass: `sudo -n swapoff --all` removes the
3,221,221,376-byte active swapfile, the subsequent active-swap listing is empty,
and the probe reports `verified_swap_disabled=true`. The x86-64 image is
`20261023.334.3`, differing from the preceding inventory; ARM64 retains
`20261026.166.1`. These results accept the Linux control operation on those images,
not a subsequent compiler build without swap.

Both macOS jobs fail at the control operation. Their downloaded diagnostic says
`sysctl: oid 'vm.swap_enabled' is read only`, followed by the explicit refusal to
claim swap disabled. Their images match the earlier inventory. Upload and cleanup
still pass. This rejects that particular runtime setter; it does not establish
that every other native mechanism is unavailable. Windows active-pagefile
control remains unrun, and the full scheduler contract remains unaccepted.

Downloaded control-file SHA-256 values and one-day artifact identities:

| Target | Artifact ID | Control-file SHA-256 |
| --- | --- | --- |
| Linux x86-64 | `12755066694` | `0c5086594ec838136debcc2e65c1ab20fc678ea0d98af7d9b5f95b36cc2c6821` |
| Linux ARM64 | `12754857358` | `a9b3c43089736b9bdea933b161adf790af30437d6936d9a2bcb44854f6af6d43` |
| macOS x86-64 | `12754558107` | `1c5f835d519ffa50f3b3630de9dbc593f3643c6669d80b44dfcc9dea4b8a7483` |
| macOS ARM64 | `12753539219` | `1c5f835d519ffa50f3b3630de9dbc593f3643c6669d80b44dfcc9dea4b8a7483` |

The download operation removes its own temporary directory after collecting the
results. No Codex source, build settings or active baseline job is changed.

### Remaining macOS control probe

The manual `verify-macos-mach-control` operation compiles only the small native
probe on both macOS architectures. It calls `macx_triggers` with
`SWAP_COMPACT_DISABLE`, reads the effective `vm.swap_enabled` state, requests
`SWAP_COMPACT_ENABLE` and verifies restoration before reporting acceptance.
The binary is removed even when the probe fails. No Codex compilation runs.

Apple's published [XNU swap-control implementation](https://github.com/apple-oss-distributions/xnu/blob/xnu-11417.140.69/bsd/vm/dp_backing_file.c)
clears `vm_swap_enabled` in the disable path except on macOS ARM64. Its ARM64
path can return success without clearing that state. This source is the closest
inspected public tag to the hosted kernel, rather than an exact match for its
build suffix; native observation must decide acceptance. A successful system-call
return alone is insufficient. This operation remains unrun.
