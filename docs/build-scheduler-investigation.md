# Build scheduler investigation

## Required behavior

Step 3 of [the restart plan](../plan.md) requires one memory-responsive scheduling
policy for Cargo and Bazel on Linux, macOS and Windows, including both supported
architectures of each OS. The owner specifies:

1. Disable machine swap/pagefiles where verified native control is available;
   otherwise use the authorized swap-monitoring and ordered retry fallback.
2. Use the build tool's default job count as the starting concurrency, subject
   to estimated-memory admission in both swap modes. Refuse admission when the
   expected demand exceeds the remaining memory budget, including headroom and
   expected growth of running jobs. Measure unknown work conservatively first.
3. Block a job's unsatisfied memory allocation and queue that job for continuation.
4. When a job completes, resume paused jobs from oldest to newest before admitting
   new work. Apply the memory admission check to resumed and retried jobs too.
5. Preserve completed build outputs and accurate failure reporting.

The owner replaces deliberate oversubscription with estimated-memory admission
even when swap can be disabled. Estimates are best-effort; observed demand must
inform later admission, and an underestimate still requires safe recovery.
The owner selects investigation of exact allocation blocking as that fallback
before choosing an implementation. Proactive pressure-based suspension is not
an approved substitute.
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
build-tool integration acceptance remain open. Existing two-job Linux limits
remain until replacement acceptance; the active baseline build is not changed by
this investigation.

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
| macOS pressure notifications / process suspension | Apple's documentation describes pressure notification and memory recovery, not an established caller-controlled allocation-wait contract. | The native address-space-limit fixture below now demonstrates denied mapping and successful retry on both architectures. It does not establish compiler interception, physical-pressure recovery or suspension behavior. |

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

### Comparison with other maintained schedulers

The following candidates are compared against the complete required behavior,
including native Windows and preserving the oldest job during recovery. These
are selection findings, not accepted Cargo/Bazel integrations.

| Candidate | Useful existing behavior | Conflict with the required behavior |
| --- | --- | --- |
| [mr boxington](https://mr-boxington.jdx.dev/scheduling) | Learned memory admission and Linux compiler-tree suspension, with oldest-first continuation. | Pressure-based suspension is Linux-only; Windows lacks live pressure admission control. It does not block failed allocations. |
| [qex](https://github.com/stephenc/qex) | Shared admission control for commands. | Native Windows is unsupported; WSL2 cannot replace our native Windows builds. |
| [GNU Parallel](https://www.gnu.org/software/parallel/man.html#memfree) | Memory thresholds can delay starts, suspend work, or terminate and retry the youngest job. | Threshold-based suspension does not establish waiting inside an unsatisfied allocation. |
| [BuildXL](https://github.com/microsoft/BuildXL/blob/main/Documentation/Wiki/Advanced-Features/Performance-Tuning.md) | Historical memory estimates and pressure-driven cancellation/retry. | Its documented suspension mode is Windows-only; working-set eviction deliberately writes pages to the pagefile. Neither is our allocation-blocking policy. |
| [rush v0.11.0](https://github.com/shenwei356/rush/releases/tag/v0.11.0) | Cross-platform command admission and youngest-job termination/retry. | Its memory monitor can select the only remaining job for termination. Fixed free-memory thresholds also omit per-job estimates, swap-growth detection and allocation blocking. |

The rush Linux x86-64 release archive is acquired and its SHA-256 matches the
GitHub release asset digest:
`7a22bbc7122acd5bd23e2dfde49f84bb856944a1860057bc89921b3dce45d056`.
Invoking its public command with `-j 2 --memfree 1M` on two tiny commands exits
zero and emits both results (`job-2`, `job-1`). This establishes acquisition and
ordinary admission only; it does not exercise memory pressure or native Windows.
The evaluation directory and executable are removed afterward.

Inspection of its pinned
[`monitorMemory`](https://github.com/shenwei356/rush/blob/v0.11.0/process/start_gate.go#L162)
shows that, below half the free-memory threshold, it traverses the active list
from newest to oldest and calls `stopForMemory` without exempting a sole remaining
job. It waits while an earlier termination is underway, but can select another
job on a subsequent measurement after cleanup. Thus the source permits repeated
termination down to the last job when low available memory persists; this is a
source finding, not an observed machine-pressure failure. The pinned source file
has SHA-256 `684022d1fe8bc01b0ef8eb98e8940f9c4d00ef2c535a647f1d4c794e3da73736`.

None of these candidates is selected as the complete scheduler. Their partial
capabilities do not close the allocation-blocking or cross-platform recovery
gates. The existing Frida/compiler experiment below remains evidence for one
Linux allocation path; native macOS/Windows blocking and recovery are still
required before selecting that integration.

The investigation must next establish a native blocking boundary for each OS and
whether swap/pagefiles can actually be disabled on the selected free hosted
runners. A candidate must demonstrate an unsatisfied allocation waiting while
independent work completes, followed by successful continuation of that same
allocation. Then establish FIFO resumption, memory-budgeted startup and admission,
and the approved targeted-retry escape from retained-memory deadlock.
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

### Controlled allocation denial in the real compiler

A subsequent Linux x86-64 probe uses the installed Rust 1.95.0 compiler and
Frida 17.24.3, installed only inside an owned temporary directory. It compiles a
standalone library containing 5,000 public functions, generated by joining
`pub fn function_{index}() -> u64 { {index} }` for indices 0 through 4,999.
The source SHA-256 is
`5e0c982958660fedbb6e1922f34e4438c9ea2049c620e5ac718a936f10ef1a99`.
The compiler invocation uses `--crate-type=lib --emit=metadata`, an absolute
fixture source path and an owned metadata output. No Codex source is involved.

The controller spawns the compiler suspended and installs a replacement on its
exported `malloc`; Frida resolves the owning module as `rustc`. On requests of
at least 1 MiB, the replacement waits for the controller to set only that
process's soft `RLIMIT_AS` to its current virtual size plus 4 KiB. The hard limit
is unchanged. It then calls the original allocator. A null result waits inside
the same callback for restoration of the original limit before retrying the
same requested allocation. The experiment changes no host memory/swap policy.

The final probe records this sequence:

1. A 1,310,720-byte request succeeds under the limited address space. Its
   existing allocator capacity is not directly inspected; success does not
   establish that a new mapping was needed.
2. A 2,097,152-byte request returns null with the soft address-space limit at
   582,705,152 bytes. The callback reports `allocation-blocked` and waits.
3. An independent `/bin/true` process completes. The controller restores the
   compiler's original limit and permits the waiting callback to retry.
4. That retry succeeds. The compiler calls `exit(0)` and terminates without a
   Frida crash report. The exit hook observes the requested status; it is not
   a parent `waitpid` receipt for the instrumented process.
5. The compiler creates 741,339 bytes of metadata. An uninstrumented compile
   returns status zero and produces identical bytes with the same source,
   compiler, arguments and working directory. Their SHA-256 is
   `bc43750b1175ae61776be1d0d5191f69b3d34ce5bd49c98a59aff1069f0cc9e7`.

The first byte-comparison attempt fails because the reference compile inherits
the checkout working directory while the instrumented process uses the owned
fixture directory. Isolating those inputs shows that changing only the working
directory changes the metadata, while different output filenames in the same
directory preserve identical bytes. Correcting the reference's working
directory and removing prior instrumented output before execution makes all
probe assertions pass. No compiler or test source is repaired to obtain that
result. The final controller returns zero; its recorded output SHA-256 is
`69a12c8e08b1fd75fdfe30f3212aefc72a81697d19fb55c154d6a8330cbcc65d`.

This advances the evidence from a synthetic allocator fixture to one denied
allocation made during real compiler work. The independent process does not
itself release the artificial address-space allowance; the controller explicitly
restores it. The experiment therefore does not prove physical-pressure recovery,
coverage of other allocation entry points or threads, FIFO resumption, linker
and build-script coverage, or native macOS/Windows behavior. Those remain open
before scheduler integration can be accepted. The controller restores the limit
and terminates its owned compiler on its cleanup path; the outer owner removes
the package, source, outputs and controller after recording the result.

### Native Windows commit-limit and retry diagnostic

The dedicated [Windows allocation workflow](../.github/workflows/scheduler-windows-allocation.yml)
selects only the two Windows entries from the release matrix. Its
[probe](../.github/scripts/probe-windows-allocation.py) uses Python's standard
library to call native Windows job-object and `VirtualAlloc` APIs; it adds no
package dependency and does not compile Codex or restore caches.

The controller assigns one owned worker to a kill-on-close job, measures its
peak committed memory, and sets that worker's commit allowance to the measured
peak plus 32 MiB. The worker requests a 256 MiB committed reservation. Acceptance
requires that request to return null, the same worker to remain alive while an
independent process finishes, and the same request to succeed after the
controller raises the allowance by 256 MiB. The worker writes and reads both
ends of the allocation, frees it and exits zero. JSON events bind each stage to
the worker PID; missing events fail after 30 seconds. Cleanup terminates and
waits for an unfinished worker and closes its native handles.

This diagnoses whether the hosted Windows job limit can provide a controlled,
reversible allocation denial. Waiting/retry belongs to the fixture function;
it is not a blocked kernel call or an injected compiler hook. Even a passing
result leaves compiler interception, other allocation APIs, physical-pressure
recovery, FIFO continuation and Cargo/Bazel integration open. Machine swap and
pagefile settings are unchanged. Local Python syntax validation passes; the
installed actionlint rejects the already documented `queue: max` concurrency
field. GitHub accepts and executes this workflow with that field.

Run [`40561766827`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40561766827)
at `3cdc72995e81865072da30ba9d54053008bc4392` passes both native probes. The jobs
take 23 seconds (x86-64) and 27 seconds (ARM64), including setup and evidence
upload. Each worker's first 268,435,456-byte request returns null with Windows
error 1455. After the peer completes and the controller increases only the
worker's commit allowance, the same PID reports successful allocation, observed
endpoint bytes 42/43, deallocation and zero exit. This paired result establishes
controlled commit-limit recovery, not machine-wide pagefile exhaustion.

| Native target | Worker PID | Initial / raised commit limit, bytes | Artifact ID | Raw log SHA-256 |
| --- | --- | --- | --- | --- |
| Windows x86-64 | `4764` | `45105152` / `313540608` | `12761136166` | `64a5e277b18f01d8b5f5ee792ad5ad13e2b9ef28637bf033d9493ef0fa8851fd` |
| Windows ARM64 | `6032` | `45469696` / `313905152` | `12761611088` | `4e1535b91424c1de0ede3efee8c31bf8014e1fdb7e57c6d6e8336e6b29063cc0` |

Both logs identify native Python 3.12.10 with the expected AMD64/ARM64 machine
and runner architecture. Artifact log bytes are read and hashed directly from
their downloaded ZIPs in memory; the one-day artifacts leave no local download
directory. No passing build or cache check is repeated for this diagnostic.

### Native Windows interception diagnostic

The [interception workflow](../.github/workflows/scheduler-windows-interception.yml)
tests the next boundary on both native Windows architectures. Its
[controller and one-call worker](../.github/scripts/probe-windows-interception.py)
reuse the accepted Windows probe's API bindings and job-limit structures. The
worker invokes `VirtualAlloc` once and has no caller-side retry. The
[Frida hook](../.github/scripts/windows-allocation-interceptor.js) must receive
the native failure, wait inside that call for controller permission, and return
the successful retry's pointer to the original caller. Other allocation
requests retain their native return value and last-error state.

The hook is armed for the worker's specific thread and 256 MiB request after
injection and commit-limit setup. Acceptance binds the hooked export address to
the worker's actual function pointer, the denied/retried events to its PID and
thread, and the returned pointer to the hook's successful native result. An
independent process must finish before the controller raises the limit and
permits retry. Endpoint read/write, deallocation and zero worker exit are also
required. The controller reaps unfinished work and detaches its session; the
workflow removes its owned package/temp directory after retaining one-day logs.

Frida 17.24.3 is acquired with hash-locked Windows x86-64/ARM64 wheels on native
Python 3.12. The declared `typing_extensions` dependency applies only below
Python 3.11, so this diagnostic requires no additional package. This is candidate
evaluation, not selection as the production scheduler. Native execution results
follow below. Python and JavaScript syntax checks pass locally; actionlint
reports its known unsupported concurrency `queue` field. A passing run would
establish interception of one native fixture allocation, leaving actual compiler
paths, other allocation APIs, FIFO recovery and physical-pressure behavior open.

Initial run
[`40564230178`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40564230178)
at `cd530c235908df3c141e254822d6bda2103ac7b3` fails on both architectures.
Both logs establish an installed hook at the caller's actual function address,
native error 1455, completion of independent work, successful retry on the same
thread, the identical pointer reaching the caller, endpoint bytes 42/43 and
deallocation. Both workers then time out at the unchanged 30-second exit check.
The probe remains failed: reaching deallocation does not establish clean exit.
Owned-worker termination/reaping and workflow package-directory cleanup run.

| Native target | Worker PID / thread ID | Artifact ID | Raw log SHA-256 |
| --- | --- | --- | --- |
| Windows x86-64 | `5664` / `9152` | `12762715819` | `744bfe69049f17dcbf091f35ee812cd3cb02510efb9bfac5cdf430aee6f8e613` |
| Windows ARM64 | `9988` / `9992` | `12762780822` | `0d5b48ff3e647e9f133516bde592a4cae21a47fc90e51c406031faeb01a041e8` |

Follow-up run
[`40564921832`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40564921832)
at `39c4df1ea1c361ab8c61575d8463d947c23e87bc` preserves the exit check and
allocation inputs. The x86-64 worker again recovers, returns the same pointer,
checks endpoint bytes and frees the allocation. After exit times out, the
thread-snapshot RPC also times out, so hook removal is never reached. The
ARM64 job is cancelled after its five-minute job limit; its final retained
event sets the commit limit, with no subsequent denied-allocation event.
The record does not establish whether its arming RPC returned. Both jobs retain
logs and remove their owned diagnostic directories. Neither accepts clean exit.

| Native target | Artifact ID | Raw log SHA-256 |
| --- | --- | --- |
| Windows x86-64 | `12760999035` | `c0e49faee0a3dcfca42513d785ccfb6fecec42a6d377414b0e58f41e47814c6b` |
| Windows ARM64 | `12762254327` | `ba23803b0eddd23b77faac93b5843fe294562aef76fb895908086352cbbd1001` |

The next comparison uses the same worker and native package with two controls:
`--mode attach` installs no allocation hook; `--mode forward` installs the
existing hook without arming its denied-allocation branch. Both allow the
256 MiB allocation from the start and require the unchanged normal exit check.
These controls distinguish attachment and basic hook forwarding from blocking
and retry. The diagnostic workflow runs both controls
even if the first fails; manual dispatch can still select the original retry
experiment. That experiment now bounds arming RPC completion at 30 seconds and
records its acknowledgment before starting allocation. No Codex compilation or
already-passing build/cache check is repeated.

Control run
[`40566071488`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40566071488)
at `754ddc52b57a2797c0189c3f59a91ae2de1ade47` establishes the same distinction
on both architectures: attachment alone passes allocation, endpoint checks,
deallocation and normal exit; the unarmed JavaScript forwarding hook passes
allocation/deallocation but times out on exit. Both jobs fail as required and
reap their workers. The shutdown failure therefore does not require allocation
denial, controller arming or blocked-call retry. The internal wait responsible
for the hang remains unlocated; the failed thread-snapshot RPC provides no
stack evidence.

| Native target | Artifact ID | Raw log SHA-256 |
| --- | --- | --- |
| Windows x86-64 | `12762807700` | `4d71ede4cd1322ef1884736c2496c7c10a014c78bf10f1c1603c3a0f1fa02ff7` |
| Windows ARM64 | `12763510945` | `3086e6b6281950dd3718d3ddfa8018cd48153fa5fd743133d150bb7bb9a2799b` |

The next control, `--mode native-forward`, replaces only the forwarding callback
with a [CModule](https://frida.re/docs/javascript-api/#cmodule) implementation.
It forwards the same native request without entering JavaScript on allocation,
while keeping attachment, worker, memory allowance and normal-exit assertions.
The native module remains alive until the owned session ends. The
[native forwarder](../.github/scripts/windows-native-allocation-forwarder.js)
uses Frida's replacement context to call the original allocator. A passing
forwarding control alone would not accept blocked allocation recovery.

Native-forwarding run
[`40566605639`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40566605639)
at `f0019973714cdf7e79ece6329abccc6e44fd25a7` compiles and installs the native
callback on both architectures. Both workers allocate, check endpoints and free
memory, then time out at exit. This rules out JavaScript execution inside the
callback as a necessary condition for the shutdown hang. It does not identify
the internal wait. The native callback therefore remains a failed candidate.

| Native target | Artifact ID | Raw log SHA-256 |
| --- | --- | --- |
| Windows x86-64 | `12763466571` | `b168b577e085e1e7019d29f6ffdf9fbacc0d00eb08ea9ead6459003f1e1d1e40` |
| Windows ARM64 | `12763266936` | `3248640ec38b2d53cd293dbb228d0e060e7e71a73f7bf29e18f257711fc65add` |

The next control, `--mode native-fast`, uses Frida's documented
[`replaceFast`](https://frida.re/docs/javascript-api/#interceptor) mechanism:
it routes calls directly to a native forwarding callback, which calls the
returned original trampoline. This avoids the per-call replacement machinery
shared by the two failed hook variants. The
[direct forwarder](../.github/scripts/windows-native-allocation-fast-forwarder.js)
stores the original trampoline before pending hook changes are flushed, while
the worker is still waiting for allocation permission. The same allocation,
endpoint, deallocation and normal-exit checks still apply.

Direct-forwarding run
[`40567280406`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40567280406)
at `539590f907b37641376787b526a1265479c0d938` passes every forwarding assertion
on x86-64, including normal worker exit. On ARM64, Frida rejects hook installation
with `unable to intercept function`; the worker has not started allocation.
This establishes native direct-forwarding behavior on x86-64 only. It neither
accepts ARM64 interception nor blocked-allocation recovery on either target.

| Native target | Artifact ID | Raw log SHA-256 |
| --- | --- | --- |
| Windows x86-64 | `12763312346` | `e3236fc4e50d3c6238fec2089028b4816e083a8d7252958c03fd034db59fe747` |
| Windows ARM64 | `12763342404` | `0530f27bfa3d2599018cd653a323a7c64c182c28c1a75649ffbbfefceb6dbc43` |

Frida's pinned
[ARM64 backend](https://github.com/frida/frida-gum/blob/17.24.3/gum/backend-arm64/guminterceptor-arm64.c#L708)
rejects fast replacement when a full redirect is not relocatable, whereas
ordinary replacement can use a nearby deflector for a smaller entry. The
[JavaScript binding](https://github.com/frida/frida-gum/blob/17.24.3/bindings/gumjs/gumquickinterceptor.c#L1112)
maps the resulting wrong-signature code to the observed message. This is one
source-supported explanation, not proof of the hosted target's instructions.
The next native probe records the owning module, export offset and first eight
instructions before hook installation. It runs ARM64 only, preserving the
accepted x86-64 result. Manual dispatch can select either Windows target or
both and retains the prior controls and retry experiment.

Instruction-capture run
[`40567830325`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40567830325)
at `2853fa969c45bf0a074a628b6fa43d836d9163f9` stops inside the logger with
`invalid instruction`, before it emits its buffered instruction records or
attempts hook installation. Artifact `12763542685` has raw log SHA-256
`7037d8f6743012db98db3cb75ea94298d93191cf61057b37152f6ad53a657831`.
This is a diagnostic defect: the logger assumed eight consecutive decodable
instructions. It does not establish whether decoding failed at the entry or
after a valid instruction prefix. The corrected capture retains 32 raw entry
bytes, the decoded prefix and the exact failing address/message; it stops at
an unconditional branch or return rather than decoding past that boundary.
An undecodable instruction is recorded without bypassing the unchanged
hook-installation check.

Corrected capture run
[`40568126374`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40568126374)
at `90cadbeefe620f9a978206bb969404650ad1cd46` emits its target record and then
fails at the unchanged hook-installation check. Artifact `12763876722` has raw
log SHA-256 `9784fa94c880c6c4046753c36ac7de5a8b213cbf8b0332574aebe80eb8fb83ec`.
The caller export belongs to `KERNEL32.DLL` at offset `0x714f0`; its entry
instruction is a relative branch to offset `0x6d4f8` in that module. The raw
32-byte capture is
`02f0ff171f2003d51f2003d551050800f8efff171f2003d51f2003d591050800`.
Decoding stops at that branch with no decode error, accepting the logger repair.

This capture does not yet prove insufficient patch space at the actual
instrumented address: Frida's
[redirect resolver](https://github.com/frida/frida-gum/blob/17.24.3/gum/backend-arm64/guminterceptor-arm64.c#L1292)
follows relative branches before preparing instrumentation. The next required
check is to inspect that resolved destination and the `KernelBase.VirtualAlloc`
export, then compare the relevant entry with the pinned relocator's constraints.
Do not force unchecked relocation or claim an upstream defect from the caller
thunk's size alone. Normal-replacement shutdown remains failed on both
architectures; direct native forwarding is accepted only on x86-64, and native
blocked-allocation recovery remains open.

The resolved-entry capture in run
[`40569737275`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40569737275)
at `147bd865c0` follows the relative entry branch without a decode error or
truncation. Artifact `12764672476` has raw log SHA-256
`976ee8b9b1d7b4fcdac541e92880f38ce99c6d8e8773292fbd25a757d2a8749f`.
The destination at Kernel32 offset `0x6d4f8` consists of `adrp x16`,
`ldr x16, [x16, #0x140]`, and `br x16`: a 12-byte indirect thunk.
The pinned [relocator](https://github.com/frida/frida-gum/blob/17.24.3/gum/arch-arm64/gumarm64relocator.c#L234)
stops at the indirect branch; the
[fast-replacement backend](https://github.com/frida/frida-gum/blob/17.24.3/gum/backend-arm64/guminterceptor-arm64.c#L658)
requires a 16-byte redirect and rejects this shorter relocatable prefix.
This establishes an entry-selection limitation, rather than evidence that
the caller's relative branch alone cannot be followed.

The same capture shows a different `KernelBase.VirtualAlloc` export at module
offset `0x10f850`, starting with `pacibsp`, `stp`, `mov`, `adrp`, and `ldr`.
The next native forwarding control targets that implementation export on
ARM64, keeping the caller's Kernel32 invocation unchanged. The worker reports
both export addresses independently through ctypes; the controller checks the
installed address against the selected export. A native counter must observe
exactly one matching 256 MiB request, followed by the existing endpoint,
freeing and normal-exit assertions. This detects an installed hook that the
caller never reaches. x86-64 retains its previously accepted entry selection;
the added counter there remains unrun. Local JavaScript, Python and embedded-C
syntax checks pass; native behavior for this revised control remains unrun.
No relocation safety check is disabled, and neither source-wide interception
nor blocked-allocation recovery is accepted by a forwarding-only control.

The implementation-entry control passes on native Windows ARM64 in run
[`40570652974`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40570652974)
at `90972a544d`, with its native job completing in 38 seconds. Artifact
`12763778938` has raw log SHA-256
`1a5e27c8a386bb4cd8251c2df61c67b624ed532147bca3713f2f04c7678be5c2`.
The worker independently reports KernelBase address `0x7ffe28b8f850`; the
installed hook address agrees. The unchanged Kernel32 caller successfully
allocates 268,435,456 bytes, observes endpoint values 42 and 43, and produces
`native_forwarding_observed` with `matching_calls: 1`. It frees the allocation
and exits normally, producing `interception_probe_passed` with Frida 17.24.3.
Evidence upload and owned-directory cleanup also succeed.

This accepts direct native forwarding on ARM64 at the implementation export,
alongside the earlier x86-64 forwarding result at its original export. The
normal-replacement failures retain their failed status. The next unmet native
interception gate is a failed allocation that waits and retries inside the
hook, without caller-side retry code, and still permits normal worker exit.
No Codex compilation, previously accepted release check or x86-64 control is
repeated for this entry-selection correction.

### Native macOS address-space-limit diagnostic

Apple's current
[`RLIMIT_AS` implementation](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/kern/kern_resource.c)
passes a requested soft limit to `vm_map_set_size_limit` and rejects a limit
below existing usage. That source contract motivates a native probe; it does
not establish which behavior the hosted macOS 15 kernels expose.

The [macOS allocation workflow](../.github/workflows/scheduler-macos-allocation.yml)
selects only the Intel and Apple Silicon release runners. Its standard-library
[probe](../.github/scripts/probe-macos-allocation.py) measures an owned worker's
virtual size through native `ps`, then sets that worker's soft `RLIMIT_AS` to
the measured size plus 32 MiB, retaining the original hard limit. It requests a
256 MiB anonymous/private read-write `mmap`. Acceptance requires `ENOMEM`, an
independent process completing while the worker waits, and the same worker's
mapping succeeding after its original limit is restored. It reads/writes both
endpoints, closes the mapping and exits zero. Missing events fail after 30
seconds; an unfinished worker is terminated and reaped on cleanup.

Python syntax and the two-target selection are checked locally; actionlint
reports only its known unsupported `queue: max` field. GitHub accepts and runs
the workflow. This diagnostic changes no global swap setting and runs no Codex
build. Waiting/retry remains fixture code, not compiler interception, page-fault
blocking, physical-pressure recovery or FIFO scheduling.

Run [`40562742928`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40562742928)
at `0f7a5ea4617289e0e456e893c43ebeac61a0c203` passes both native probes.
The Intel job takes 17 seconds and the Apple Silicon job eight seconds, including
setup and artifact publication. Both use native Python 3.12.10 on Darwin 24.6.0.
Each 268,435,456-byte mapping fails with `ENOMEM` (12). After the independent
peer completes, the same worker restores its original soft limit of
9,223,372,036,854,775,807 bytes, maps the same size, reads back endpoint bytes
42/43, closes the mapping and exits zero. The hard limit is unchanged.

| Native target | Worker PID | Initial virtual size / temporary soft limit, bytes | Artifact ID | Raw log SHA-256 |
| --- | --- | --- | --- | --- |
| macOS Intel | `2920` | `34922926080` / `34956480512` | `12760119328` | `818cedadf649f4b3a09ef271d6ff0c5e6c01825a254ae2d9956e7239a64c1d5c` |
| macOS Apple Silicon | `1276` | `420688166912` / `420721721344` | `12760184330` | `9dd070801cda89789be0c33b0f99d3e432beaec59b964f26a4f2693a9e76abe6` |

These are virtual-address-space quantities, not physical RAM consumption. The
large initial virtual sizes must not be used as physical-memory admission
estimates. Logs are downloaded and hashed in memory from their one-day
artifacts. The result accepts reversible address-space denial for these owned
fixtures; it leaves the wider scheduler requirements open.

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
return alone is insufficient.

Run [`40552159334`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40552159334)
at `2116b7333ced6396716688d23b77a3747bec3ca5` passes Intel macOS and retains
a failed status for ARM64. On Intel, the effective state transitions from 1 to
0 and back to 1; both native requests return zero. On ARM64, both requests also
return zero, but the effective state stays 1 throughout. Its diagnostic is
`Kernel request returned success but swap remains enabled.` The native result
therefore matches the architecture distinction in the inspected kernel source.

The Intel job takes 16 seconds and ARM64 takes 13 seconds. Their image identities
remain `20260824.0482.1` and `20260907.0337.1`, respectively. Artifact upload and
owned-directory cleanup pass for both. The downloaded artifacts contain only
the two text files per target; the native binary is absent. The download's owned
directory is removed after inspection.

| Target | Artifact ID | Mach-control-file SHA-256 |
| --- | --- | --- |
| macOS x86-64 | `12755467124` | `89ad5b7aeb2309e2c2f7592288bd2ce96de19b46db7976c9ffa9507141a2a816` |
| macOS ARM64 | `12755422252` | `65db8fe055078c89e17d79210092aaf20ea32dfeff69b1a55335b169c3e85d6e` |

Intel native swap control is accepted at the observed state-transition boundary.
Neither result proves behavior during compiler memory pressure. ARM64 requires
the authorized monitoring/retry fallback for this control path. Windows remains
eligible for the same fallback while active-pagefile removal is unestablished.
Allocation blocking, ordered retries and Cargo/Bazel integration remain open.

## Bazel estimated-memory admission probe

Bazel 9.2.0 already owns local action admission through
[`ResourceManager`](https://github.com/bazelbuild/bazel/blob/9.2.0/src/main/java/com/google/devtools/build/lib/actions/ResourceManager.java).
Its [`local_resources` option](https://github.com/bazelbuild/bazel/blob/9.2.0/src/main/java/com/google/devtools/build/lib/exec/ExecutionOptions.java)
sets a resource budget; each action supplies a memory estimate. This facility
reserves estimates rather than disabling swap or sampling current RAM pressure.
The Bazel server and unrelated processes can consume additional memory, and
underestimated actions can exceed their reservations. It therefore cannot alone
prove swap avoidance, exact allocation blocking or ordered recovery.

A local Linux x86-64 fixture exercises the pinned 9.2.0 binary, verified against
SHA-256 `7668a95db1250f12c40407251e4e203b4ec8bf39bc495d2f485b2d8c99048694`.
The installed 9.3.1 binary is not used. The isolated module contains two custom
actions declaring 600 MB each, and one declaring 1,200 MB. The smaller actions
write start/end timestamps around a 0.2-second wait; they do not allocate the
declared memory. Their execution intervals test the admission boundary, not
physical-memory consumption.

Both invocations use `--jobs=2`, `--local_resources=cpu=2`,
`--local_resources=memory=1000`, `--experimental_action_resource_set` and
`--noallow_one_action_on_resource_unavailable`, with an owned output root and
all user rc files ignored:

| Invocation | Observed result | Accepted claim |
| --- | --- | --- |
| Build the two 600-MB actions together | Exit 0; output timestamps show nonoverlapping execution intervals. | The declared memory estimates serialize those actions despite two available job/CPU slots. |
| Build the 1,200-MB action | Exit 1 with the specific insufficient-memory-resource diagnostic; its output is absent. | With oversize bypass disabled, an action exceeding the entire budget is refused before execution. |

The initial fixture attempt fails during Starlark analysis because its timestamp
format contains an unescaped percent sign. No action executes in that attempt;
it provides no scheduling evidence. Correcting that fixture alone produces the
results above. Each attempt removes its own module, output root and downloaded
binary afterward. No Codex compilation, source modification or cache restore is
performed.

Reuse this native admission mechanism where it fits the final Bazel integration,
including explicit refusal of oversized work. The remaining integration must
supply defensible action estimates and a budget with server/OS headroom, observe
changing pressure, and preserve the approved recovery order. The fixed budget
probe does not accept those remaining behaviors or establish native behavior on
macOS/Windows. Cargo's documented job count and compiler-wrapper hook likewise
do not themselves provide memory admission.

The maintained diagnostic is
[test-bazel-memory-admission.py](../.github/scripts/test-bazel-memory-admission.py).
It takes `--bazel` pointing to the pinned executable and `--temporary-directory`
pointing to an existing owned parent. It creates and removes its isolated module
and output root, retains outputs between its two invocations, and uses a Python
worker with monotonic timestamps instead of a shell-specific date command. Its
local Linux x86-64 execution passes both assertions: estimated memory serializes
the two smaller actions, and the oversized action is rejected before producing
its output. The latter is an expected negative test rather than a repaired
failure. This validates the maintained diagnostic at its native Bazel execution
boundary; it does not repeat Codex compilation or accept unrun native platforms.

The focused
[native admission workflow](../.github/workflows/scheduler-bazel-memory-admission.yml)
extends that unchanged fixture to the five unaccepted release targets: Linux
ARM64, both macOS architectures and both Windows architectures. It downloads
the corresponding standalone Bazel 9.2.0 binary from the
[official release](https://github.com/bazelbuild/bazel/releases/tag/9.2.0),
checks its pinned SHA-256 and version, and rejects a Python/runner architecture
mismatch before running the fixture. It retains native identity, fixture hash,
acquisition identity, stdout/stderr, elapsed nanoseconds and terminal exit status
for one day, then removes its owned diagnostic directory. There is no upstream
checkout, compiler installation, Codex compilation, cache restoration or swap
change. Its push registration is limited to its own path on `main.new`.

Local YAML parsing and all five embedded Python syntax checks pass. Executing
the actual matrix-selection step selects exactly the five intended targets and
their existing free runner labels. The installed actionlint reports only the
previously established unsupported `queue` concurrency key; the requested queue
policy is retained, with GitHub ingestion as its acceptance boundary. Its
five-minute job timeout does not promise a terminal fixture receipt
or child cleanup after a forced runner termination; such a run remains
failed/incomplete rather than accepted.

### Native estimated-admission results

Run [`40571493167`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40571493167)
at `c1cc66ed00` passes all five native jobs, including acquisition, the unchanged
fixture, evidence upload and owned-directory cleanup. Each receipt exits zero;
each stdout reports both `estimated_memory_admission_serialized=True` and
`oversized_action_refused_before_execution=True`. All observed executable hashes
agree with the pinned release hashes and every version check returns Bazel 9.2.0.
Native jobs take 17–44 seconds; the table measures only the fixture command.

| Native target | Fixture elapsed (ns) | Artifact ID | `receipt.json` SHA-256 |
| --- | ---: | --- | --- |
| Linux ARM64 | `8443783109` | `12763974490` | `862c5aef83205526da8e0e2aa081a77fa5000cdc343b675700d4d025f8f6ca08` |
| macOS ARM64 | `8170791458` | `12763784672` | `c979f1bcf3cf4c7a93d37db1a451610e60b1202b238754c6e5cd4024736dbcf3` |
| macOS x86-64 | `26899962403` | `12765018752` | `07daeefc56e1904561eeec379892450e05074c3e2889c6aa7f74ac0365a1b838` |
| Windows ARM64 | `13875000000` | `12764144430` | `e846f97ed54ca870167454a489498620c05a1f74ada04c9055eca8ff732f1ba5` |
| Windows x86-64 | `10156000000` | `12764749427` | `8c99ae3361b95d812e4c854f5a6cea98e98ecd6f286a164882eb4ca79c3872b8` |

The POSIX fixture bytes have SHA-256
`eeb309f487cd0e26378a16e734ca113e064083e331f472340e2a2b1048dd2be3`;
the Windows checkout bytes have SHA-256
`22e8ae8bb06ab994e010081f35e4cd1ae54e19bbaa938d356530252060f13a5e`.
Replacing LF with CRLF in the local fixture exactly reproduces the Windows
digest. The same conversion explains the stdout digests:
`a0ab7914c883fa8fc35dafbf125b3a7fdc5237073ebea1fec507ed56b5c3dc73`
on POSIX and
`3253df6fcb257b1794e7852dfda6e7f43fea39df200aaf3008a1731250868b9f`
on Windows. Parsed output agrees on both assertions; no source repair or repeated
native run is needed for this byte-level difference.

Together with the accepted local Linux x86-64 result, these receipts accept
declared-estimate admission and oversized-action refusal on all six platforms.
They do not accept compiler action estimates, dynamically changing physical
pressure, allocation blocking, ordered recovery or Cargo/Bazel integration.
The full scheduler checkpoint remains open.

### Memory-load scheduling availability

The current Bazel development source also documents
`--experimental_memory_load_scheduling`. This must not be confused with the
estimated-memory admission exercised above. Direct inspection of the pinned
9.2.0 `ExecutionOptions.java` confirms that this option is absent there.

Upstream commit
[`0bae75d6382cbaf7303ba213b72d4e69db027216`](https://github.com/bazelbuild/bazel/commit/0bae75d6382cbaf7303ba213b72d4e69db027216),
dated `2026-08-24T14:13:49Z`, adds the option and its implementation. Its
`ResourceManager.isMemoryAvailable` consults measured memory usage plus memory
estimates for newly started actions; when measurement is unavailable, it falls
back to estimated reservations. This is source inspection, not a native runtime
acceptance result. It does not establish swap prevention, termination/retry
ordering, or behavior across our release platforms.

The development option is therefore unavailable to the pinned baseline without
changing Bazel. No toolchain update or backport is applied for this investigation.
The measured-pressure and recovery requirements remain open for the existing
toolchain; finding a similarly named option in current documentation does not
complete them.

Checking the latest stable release separately confirms that Bazel 9.3.1 also
lacks this option in its `ExecutionOptions.java`; upgrading from 9.2.0 to that
stable release would not provide memory-load admission. The recent stable
maintenance releases 8.8.2 and 7.7.2 likewise lack the option. The development
implementation must not be described as an available stable feature.

### Rust action memory estimates

The pinned upstream `MODULE.bazel` selects `rules_rs` 0.0.96. Its
[`rules_rust` extension](https://github.com/hermeticbuild/rules_rs/blob/v0.0.96/rs/rules_rust.bzl)
resolves `hermeticbuild/rules_rust` at
`f19661ba71f77c993ce1eb082053692610fe4313`. The published `rules_rs` archive's
Rust binary/library/test macros delegate to those `rules_rust` rules.

At that revision,
[`rustc.bzl`](https://github.com/hermeticbuild/rules_rust/blob/f19661ba71f77c993ce1eb082053692610fe4313/rust/private/rustc.bzl)
passes `get_rustc_resource_set(rust_toolchain)` for compilation and metadata
actions. Its
[`resource callback`](https://github.com/hermeticbuild/rules_rust/blob/f19661ba71f77c993ce1eb082053692610fe4313/rust/private/rustc_resource_set.bzl)
returns either no callback or a dictionary declaring CPU demand only. It supplies
no compiler-specific memory demand. Bazel 9.2.0's
[`actions.run` contract](https://bazel.build/versions/9.2.0/rules/lib/builtins/actions.html#run)
defaults memory to 250 MB.

This source trace identifies a concrete estimate gap in the Rustc action path;
it does not measure the effective resources of a running Codex action or prove
the cause of a particular failed build. Recorded compiler RSS measurements in
`plan.md` reach multiple GiB, so the default cannot be accepted as a conservative
estimate for every compiler job. Final integration must establish effective
action resources and measured demand before relying on Bazel's admission budget.
No upstream rule or dependency source is modified by this investigation.

## Cross-platform memory telemetry candidate

`psutil` 7.2.2 is under evaluation for physical-memory availability, swap
occupancy and process RSS sampling. The
[published release files](https://pypi.org/project/psutil/7.2.2/#files) include
ABI-compatible binary wheels for all six release OS/architecture combinations.
This establishes distribution availability, not native behavior on every target.

A fresh, owned local directory receives only the pinned binary package through
`pip install --only-binary=:all: --no-deps --target ... psutil==7.2.2`.
The imported module path is verified inside that directory. The Linux x86-64
probe returns these decision-relevant values:

| Selected operation/member | Observed value (bytes) |
| --- | --- |
| `virtual_memory().available` | `36828274688` |
| `virtual_memory().total` | `44136525824` |
| `swap_memory().used` | `2610085888` |
| `swap_memory().sout` | `20230164480` |
| `Process(current_pid).memory_info().rss` | `13574144` |

The probe removes its package and temporary directory on exit. It performs no
allocation-pressure experiment, process termination or Codex compilation.

The [stable API documentation](https://psutil.io/7.2/#psutil.swap_memory)
specifies that Windows swap-in and swap-out counters always return zero.
The [pinned Windows implementation](https://github.com/giampaolo/psutil/blob/release-7.2.2/psutil/_pswindows.py)
also calculates swap-used bytes from a usage percentage and total capacity.
Consequently, zero paging counters cannot be interpreted as absence of Windows
paging activity, and its occupancy value must not be claimed as an exact native
pagefile byte count. Windows needs an additional verified native signal before
this candidate can satisfy the monitoring contract. Native availability results
follow below; measurement during pressure and the final caller-facing integration
remain unrun. No dependency or scheduler implementation is selected by this probe.

### Native telemetry availability check

The
[Scheduler memory telemetry workflow](../.github/workflows/scheduler-memory-telemetry.yml)
uses the existing six-target release matrix. It installs only the pinned binary
candidate, with the six permitted wheel hashes in
[memory-telemetry-requirements.txt](../.github/scripts/memory-telemetry-requirements.txt),
inside an owned directory that its cleanup step removes. Two samples record
available physical memory, swap occupancy, swap-output counters and the sampling
process's RSS. Windows additionally records native `Win32_PageFileUsage`
properties, whose `CurrentUsage` unit is megabytes according to
[Microsoft's contract](https://learn.microsoft.com/en-us/windows/win32/cimwin32prov/win32-pagefileusage).
An empty pagefile inventory is retained as an empty array rather than fabricated
zero-valued active-pagefile records.

This workflow answers whether each native runner can acquire the package and
produce the required observations. It neither induces memory pressure nor
accepts a recovery policy. Its trigger and acceptance boundary differ from
the swap-control workflow, so it is a separate read-only diagnostic. Both Linux
architectures require native observations despite sharing an OS; a package-file
inventory alone cannot provide them. Existing Codex build checks need no rerun
for this diagnostic.

Local verification installs the hash-locked Linux wheel and executes the exact
sampling shell step extracted from the workflow. Both samples pass their
plausibility checks and emit JSON. An initial local invocation cannot find
`python`; supplying that command through an owned symlink to the installed
`python3` allows the unchanged step to run. Hosted runs instead obtain that
command through their explicit setup-python step. The owned local directory
is removed after each attempt.

The installed local actionlint reports only its unsupported `queue` concurrency
key. That finding is retained; the requested queue policy is not removed to
appease the linter. GitHub workflow ingestion and native execution remain the
acceptance boundary for that setting. Native acquisition, sampling and cleanup
require the hosted results below.

The initial filename dispatch after publishing `41bd0fc421` returns HTTP 404;
GitHub's workflow inventory lists the existing prerequisite workflow but not the
new telemetry workflow. A push trigger limited to `main.new` and the diagnostic's
workflow/requirements paths starts its first native run without changing old
`main`. Later manual dispatch remains available after registration. Documentation
updates alone do not trigger another matrix run.

### Native telemetry results

Run [`40556669684`](https://github.com/holdmyspot-com/codex-unleashed/actions/runs/40556669684)
at `39cde88ad7677756a6ebc3c2f80ddb51d6d813cb` passes all six sampling jobs.
Each imports version 7.2.2 from its owned package directory, produces two valid
samples, uploads its evidence and successfully removes the owned directory.
Job durations range from 8 to 34 seconds. GitHub also accepts the workflow's
requested queue setting; the local linter's rejection is not a hosted failure.

| Native target | Physical RAM (bytes) | First available RAM (bytes) | Artifact ID | `psutil.json` SHA-256 |
| --- | --- | --- | --- | --- |
| Linux x86-64 | `16770748416` | `15353716736` | `12757607508` | `7e00e78380c727632ce8c781b882dd13d20fa1a1237d52512bc4923e8b226a4f` |
| Linux ARM64 | `16715927552` | `15454117888` | `12757107293` | `8d04aad65b647b15737a03e9f2214e11628a65020e301158c4746631b23e652d` |
| macOS x86-64 | `15032385536` | `10811633664` | `12757297551` | `7dcac7fbc605a4d527f7386c8459d1549dcef685da700860bc43c7f2973ba4fe` |
| macOS ARM64 | `7516192768` | `3398008832` | `12757596896` | `4753ced218375e021060399b7f8a691c7e6373578f751df6a87fd2b4e01c4e10` |
| Windows x86-64 | `17174360064` | `14564114432` | `12756963171` | `717cbef2e6be2ffb88581cc41a29698e102435575976264717c1b44d8b022cfa` |
| Windows ARM64 | `17169387520` | `13620195328` | `12756389759` | `e077e947b67c60621496b46cf84de110f9e377b7050add48d470b4cb2eb96412` |

All samples report zero swap occupancy. macOS ARM64 nevertheless reports
`14942208` cumulative swap-output bytes in both samples. This illustrates why a
nonzero lifetime counter is not evidence of current paging activity: recovery
decisions need changes between samples plus current memory pressure. Other
targets report zero swap-output counters; Windows always-zero counters retain
their documented limitation.

Both native Windows inventories report one active pagefile with 2,944 MB
allocated and zero current/peak usage: `D:\pagefile.sys` on x86-64 and
`C:\pagefile.sys` on ARM64. Their JSON SHA-256 values are respectively
`67ea41a7277949021e7100951c770bdc78a204cecca1e1b205cce7e35e739815`
and `339835ae78666460054e510bc2dd7c074f4bca60729d4b58d29d7fb5e9ce7948`.
These observations accept native acquisition and idle telemetry availability,
including the Windows pagefile inventory. They do not yet prove that the signal
changes correctly under controlled paging pressure or supports safe recovery.

The registration observer initially filters `gh run list --commit` using the
short hash `39cde88ad7`; it reaches its five-minute observation deadline despite
the run having already completed. Looking up the workflow without that filter
locates the successful run. Repeating the query with the full commit hash returns
the same completed run, establishing the correct observation input. This is an
observer error, not a lost or failed native run; no matrix job is restarted.
Artifact ZIPs are inspected in memory, without extracting files into the checkout.
