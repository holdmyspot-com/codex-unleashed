"""Choose scheduler controls from measured state; never operates on processes."""
from dataclasses import dataclass
from enum import Enum


class State(Enum):
    """Unfinished controller states; STOPPING remains live until tree reaping."""
    READY = "ready"
    RUNNING = "running"
    BLOCKED = "blocked"
    STOPPING = "stopping"
    RETRY = "retry"


class Kind(Enum):
    """Supported requests; only the controller can produce their native effects."""
    WAIT = "wait"
    START = "start"
    RESUME = "resume"
    TERMINATE = "terminate"


@dataclass(frozen=True)
class Job:
    """One unfinished action in the controller's scheduling scope.

    position is its original submission ordinal, retained through retries.
    started_at orders process attempts; blocked_at orders allocation-denial events.
    They are monotonic sequence numbers, not timestamps. retry_after is the earliest
    eligible completion epoch after an aborted attempt; RETRY requires it to be
    positive. peak_bytes is an estimated peak, or None for unmeasured work; resident_bytes is the observed current use.
    Both cover the same action process tree and use integer bytes. Inactive actions
    own no live process. Inputs remain immutable after construction.
    """
    name: str
    state: State
    position: int
    peak_bytes: int | None
    resident_bytes: int = 0
    started_at: int | None = None
    blocked_at: int | None = None
    retry_after: int = 0

    def __post_init__(self):
        """Reject invalid job state and measurements at construction."""
        _validate_job(self)


@dataclass(frozen=True)
class Observation:
    """Fresh measurements supplied by the controller's native monitor.

    available_bytes excludes current process residency. memory_pressure describes
    current native pressure; swap_recovery records an observed swap-growth incident
    still being monitored. Neither is inferred here. completions is a monotonic
    count of non-aborted action completions; reaping a selected termination must
    not advance it. This component never samples or modifies machine state.
    """
    available_bytes: int
    memory_pressure: bool = False
    swap_recovery: bool = False
    completions: int = 0

    def __post_init__(self):
        """Require integer measurements and explicit monitor booleans."""
        _require_count(self.available_bytes, "available_bytes")
        _require_count(self.completions, "completions")
        if type(self.memory_pressure) is not bool or type(self.swap_recovery) is not bool:
            raise TypeError("memory_pressure and swap_recovery must be booleans")


@dataclass(frozen=True)
class Budget:
    """Configured OS headroom in bytes and build tool's default process limit."""
    headroom_bytes: int
    jobs: int

    def __post_init__(self):
        """Require nonnegative byte headroom and a positive concurrency limit."""
        _require_count(self.headroom_bytes, "headroom_bytes")
        _require_count(self.jobs, "jobs", minimum=1)


@dataclass(frozen=True)
class Decision:
    """One control request to acknowledge before deciding again.

    kind selects the requested operation; job names its target, or is None
    for WAIT. reason explains the choice. The request itself has no native effect.
    """
    kind: Kind
    job: str | None
    reason: str


def choose_action(budget: Budget, jobs: tuple[Job, ...], observation: Observation) -> Decision:
    """Select one control request without mutating the supplied snapshot.

    Availability already excludes resident bytes of all processes. Reserve only
    expected remaining growth of RUNNING jobs, plus headroom. BLOCKED jobs reserve
    additional growth at resumption. Unknown peaks require exclusive measurement.
    Block-event order precedes original-position retries, which precede new work.
    A RETRY stays ineligible until its retry_after completion epoch is reached.

    A monitor latches swap_recovery after observed swap growth; memory_pressure
    must describe current native pressure. High occupancy alone cannot select a
    termination. Protect the oldest RUNNING process and request one termination
    at a time; STOPPING blocks every further decision until the controller reaps
    that process tree and obtains a new observation. No native effect is implied
    by a returned request. The controller must acknowledge it before another call.

    Raises ValueError for duplicate job names, positions or non-null start/block
    ordinals. Jobs and measurements must satisfy their construction contracts.
    """
    _validate_snapshot(jobs)
    stopping = [job for job in jobs if job.state == State.STOPPING]
    if stopping:
        return Decision(Kind.WAIT, None, "Wait for the selected process tree to be reaped")

    running = [job for job in jobs if job.state == State.RUNNING]
    blocked = [job for job in jobs if job.state == State.BLOCKED]
    live = running + blocked
    if observation.memory_pressure:
        if observation.swap_recovery and running:
            oldest = min(running, key=lambda job: job.started_at)
            eligible = [job for job in live if job.name != oldest.name]
            if eligible:
                newest = max(eligible, key=lambda job: job.started_at)
                return Decision(Kind.TERMINATE, newest.name, "Relieve observed swap pressure; retain the oldest running job")
        return Decision(Kind.WAIT, None, "Native memory pressure prevents admission")

    if blocked:
        candidate = min(blocked, key=lambda job: job.blocked_at)
        kind = Kind.RESUME
    else:
        retries = sorted((job for job in jobs if job.state == State.RETRY), key=lambda job: job.position)
        ready = sorted((job for job in jobs if job.state == State.READY), key=lambda job: job.position)
        if retries:
            candidate = retries[0]
            if observation.completions < candidate.retry_after:
                return Decision(Kind.WAIT, None, "The oldest retry awaits an actual job completion")
        elif ready:
            candidate = ready[0]
        else:
            return Decision(Kind.WAIT, None, "No unfinished work is ready")
        kind = Kind.START
        if len(live) >= budget.jobs:
            return Decision(Kind.WAIT, None, "Build-tool concurrency limit is occupied")

    other_running = [job for job in running if job.name != candidate.name]
    if any(job.peak_bytes is None for job in other_running):
        return Decision(Kind.WAIT, None, "An unknown-memory job is being measured exclusively")
    growth_bytes = sum(max(job.peak_bytes - job.resident_bytes, 0) for job in other_running)
    remaining_bytes = observation.available_bytes - budget.headroom_bytes - growth_bytes
    if candidate.peak_bytes is None:
        other_live = [job for job in live if job.name != candidate.name]
        if other_live:
            return Decision(Kind.WAIT, None, "Unknown-memory work requires exclusive measurement")
        if remaining_bytes <= 0:
            return Decision(Kind.WAIT, None, "No memory remains beyond reserved headroom")
    else:
        required_bytes = max(candidate.peak_bytes - candidate.resident_bytes, 0)
        if remaining_bytes < required_bytes:
            return Decision(Kind.WAIT, None, "Expected growth exceeds memory remaining after reservations")
    return Decision(kind, candidate.name, "Memory estimate fits available budget")


def _validate_snapshot(jobs):
    """Reject duplicate names, submission positions and non-null start/block ordinals.

    Raises ValueError when a repeated value makes job identity or ordering
    ambiguous. Per-job types, units and lifecycle checks occur at construction.
    """
    for name, values in (("name", [job.name for job in jobs]),
                         ("position", [job.position for job in jobs]),
                         ("started_at", [job.started_at for job in jobs if job.started_at is not None]),
                         ("blocked_at", [job.blocked_at for job in jobs if job.blocked_at is not None])):
        if len(set(values)) != len(values):
            raise ValueError(f"Duplicate job {name} values make ordering ambiguous")


def _require_count(value, name, minimum=0):
    """Require an exact integer at least minimum; reject booleans with ValueError.

    name identifies the invalid measurement or ordinal in the error.
    """
    if type(value) is not int or value < minimum:
        raise ValueError(f"{name} must be an integer >= {minimum}; received {value!r}")


def _validate_job(job):
    """Establish the per-job measurement and lifecycle invariants."""
    if isinstance(job.state, State) is False:
        raise TypeError("Job state must be State")
    if isinstance(job.name, str) is False or job.name == "":
        raise ValueError("Job name must be nonempty text")
    _require_count(job.position, "position")
    _require_count(job.resident_bytes, "resident_bytes")
    if job.state == State.RETRY:
        _require_count(job.retry_after, "retry_after", minimum=1)
    else:
        _require_count(job.retry_after, "retry_after")
    if job.peak_bytes is not None:
        _require_count(job.peak_bytes, "peak_bytes")
    has_live_process = job.state in (State.RUNNING, State.BLOCKED, State.STOPPING)
    if has_live_process:
        _require_count(job.started_at, "started_at")
    elif job.started_at is not None or job.resident_bytes != 0:
        raise ValueError("Inactive jobs must have no started_at and zero resident_bytes")
    if job.state == State.BLOCKED:
        _require_count(job.blocked_at, "blocked_at")
    elif job.blocked_at is not None:
        raise ValueError("Only BLOCKED jobs may have blocked_at")
