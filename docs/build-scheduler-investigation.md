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

No scheduler implementation is selected or accepted by this document. Native
swap-control, allocation-blocking, FIFO continuation and build-tool integration
checks remain unrun. Existing two-job Linux limits remain until replacement
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
