"""Exercise policy decisions with supplied snapshots, without native-process claims."""
import unittest
from build_scheduler.policy import Budget, Job, Kind, Observation, State, choose_action


class PolicyTest(unittest.TestCase):
    """Check scheduler decisions from immutable snapshots without launching native processes."""
    def test_remaining_growth_limits_new_admission(self):
        """Require headroom plus remaining running-job growth before admitting new work."""
        jobs = (Job("active", State.RUNNING, 0, 700, 300, started_at=0),
                Job("next", State.READY, 1, 300))
        self.assertEqual(choose_action(Budget(100, 2), jobs, Observation(799)).kind, Kind.WAIT)
        decision = choose_action(Budget(100, 2), jobs, Observation(800))
        self.assertEqual((decision.kind, decision.job), (Kind.START, "next"))

    def test_blocked_fifo_precedes_new_work_and_cannot_be_bypassed(self):
        """Require the earliest blocked job to fit before younger blocked jobs or new work can be admitted."""
        jobs = (Job("new", State.READY, 0, 10),
                Job("older", State.BLOCKED, 2, 500, 100, started_at=2, blocked_at=4),
                Job("younger", State.BLOCKED, 1, 200, 100, started_at=1, blocked_at=5))
        self.assertEqual(choose_action(Budget(100, 3), jobs, Observation(499)).kind, Kind.WAIT)
        decision = choose_action(Budget(100, 3), jobs, Observation(500))
        self.assertEqual((decision.kind, decision.job), (Kind.RESUME, "older"))

    def test_swap_recovery_terminates_newest_and_retains_last_running(self):
        """Require swap pressure to select the newest running job and preserve the sole runner."""
        jobs = (Job("oldest", State.RUNNING, 0, 400, 300, started_at=10),
                Job("newest", State.RUNNING, 1, 400, 300, started_at=12))
        observation = Observation(10, memory_pressure=True, swap_recovery=True)
        decision = choose_action(Budget(100, 2), jobs, observation)
        self.assertEqual((decision.kind, decision.job), (Kind.TERMINATE, "newest"))
        self.assertEqual(choose_action(Budget(100, 2), jobs[:1], observation).kind, Kind.WAIT)

    def test_unknown_work_is_measured_alone(self):
        """Require unknown peaks to run exclusively with positive memory beyond headroom."""
        unknown = Job("unknown", State.READY, 0, None)
        self.assertEqual(choose_action(Budget(100, 4), (unknown,), Observation(101)).kind, Kind.START)
        active = Job("active", State.RUNNING, 1, 10, 10, started_at=1)
        self.assertEqual(choose_action(Budget(100, 4), (unknown, active), Observation(1000)).kind, Kind.WAIT)
        active_unknown = Job("measuring", State.RUNNING, 0, None, 20, started_at=0)
        next_job = Job("next", State.READY, 1, 1)
        self.assertEqual(choose_action(Budget(100, 4), (active_unknown, next_job), Observation(1000)).kind, Kind.WAIT)

    def test_retry_keeps_original_position_and_waits_for_completion(self):
        """Require the earliest submitted retry to await its completion epoch before new work."""
        jobs = (Job("new", State.READY, 3, 10),
                Job("later-retry", State.RETRY, 2, 10, retry_after=1),
                Job("first-retry", State.RETRY, 1, 10, retry_after=2))
        self.assertEqual(choose_action(Budget(100, 3), jobs, Observation(1000, completions=1)).kind, Kind.WAIT)
        decision = choose_action(Budget(100, 3), jobs, Observation(1000, completions=2))
        self.assertEqual((decision.kind, decision.job), (Kind.START, "first-retry"))

    def test_swap_recovery_without_current_pressure_allows_admission(self):
        """Require admission when current pressure is false even while swap recovery remains latched."""
        jobs = (Job("oldest", State.RUNNING, 0, 100, 100, started_at=0),
                Job("next", State.READY, 1, 100))
        decision = choose_action(Budget(100, 2), jobs, Observation(200, swap_recovery=True))
        self.assertEqual((decision.kind, decision.job), (Kind.START, "next"))

    def test_termination_must_be_reaped_before_reassessment(self):
        """Require WAIT while a selected process tree remains STOPPING."""
        jobs = (Job("oldest", State.RUNNING, 0, 100, 100, started_at=0),
                Job("selected", State.STOPPING, 1, 100, 100, started_at=1),
                Job("next", State.READY, 2, 10))
        self.assertEqual(choose_action(Budget(100, 4), jobs, Observation(1000)).kind, Kind.WAIT)

    def test_invalid_state_and_bytes_fail_at_construction(self):
        """Require constructor failures for invalid limits, measurements, state and monitor booleans."""
        with self.assertRaises(ValueError):
            Budget(-1, 2)
        with self.assertRaises(ValueError):
            Job("job", State.READY, 0, 1.5)
        with self.assertRaises(TypeError):
            Job("job", "ready", 0, 1)
        with self.assertRaises(ValueError):
            Job("job", State.RUNNING, 0, 10)
        with self.assertRaises(TypeError):
            Observation(100, memory_pressure="false")

    def test_build_default_limit_blocks_new_process_but_allows_resumption(self):
        """Require the process limit to block starts while allowing a blocked job to resume."""
        active = Job("active", State.RUNNING, 0, 10, 10, started_at=0)
        ready = Job("next", State.READY, 1, 10)
        self.assertEqual(choose_action(Budget(100, 1), (active, ready), Observation(1000)).kind, Kind.WAIT)
        blocked = Job("blocked", State.BLOCKED, 0, 10, 10, started_at=0, blocked_at=0)
        self.assertEqual(choose_action(Budget(100, 1), (blocked,), Observation(1000)).kind, Kind.RESUME)

    def test_recovery_uses_attempt_start_order_and_protects_only_running_peer(self):
        """Require attempt-order termination and permit a blocked job to be chosen over the sole runner."""
        jobs = (Job("recent-retry", State.RUNNING, 0, 10, 10, started_at=7),
                Job("older-attempt", State.RUNNING, 1, 10, 10, started_at=5))
        observation = Observation(0, memory_pressure=True, swap_recovery=True)
        self.assertEqual(choose_action(Budget(100, 2), jobs, observation).job, "recent-retry")
        blocked = Job("blocked", State.BLOCKED, 0, 10, 10, started_at=0, blocked_at=0)
        peer = Job("peer", State.RUNNING, 1, 10, 10, started_at=1)
        self.assertEqual(choose_action(Budget(100, 2), (blocked, peer), observation).job, "blocked")

    def test_retry_is_still_subject_to_memory_budget(self):
        """Require a completion-eligible retry to fit the memory budget."""
        retry = Job("retry", State.RETRY, 0, 101, retry_after=1)
        self.assertEqual(choose_action(Budget(100, 2), (retry,), Observation(200, completions=1)).kind, Kind.WAIT)

    def test_no_pressure_kill_without_swap_recovery_and_no_unknown_without_headroom(self):
        """Require WAIT for pressure without swap recovery and for unknown work without spare headroom."""
        active = (Job("a", State.RUNNING, 0, 10, 10, started_at=0),
                  Job("b", State.RUNNING, 1, 10, 10, started_at=1))
        self.assertEqual(choose_action(Budget(100, 2), active, Observation(0, memory_pressure=True)).kind, Kind.WAIT)
        unknown = Job("unknown", State.READY, 0, None)
        self.assertEqual(choose_action(Budget(100, 2), (unknown,), Observation(100)).kind, Kind.WAIT)

    def test_retry_requires_a_positive_completion_epoch(self):
        """Require RETRY construction to reject the default zero completion epoch."""
        with self.assertRaises(ValueError):
            Job("retry", State.RETRY, 0, 10)

    def test_ambiguous_ordering_is_rejected(self):
        """Require duplicate submission positions to fail before a decision is returned."""
        jobs = (Job("a", State.READY, 0, 10), Job("b", State.READY, 0, 10))
        with self.assertRaises(ValueError):
            choose_action(Budget(100, 2), jobs, Observation(1000))

if __name__ == "__main__":
    unittest.main()
