package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises session ordering, issued-outcome correlation and retained cleanup
 * using per-test action boundaries; it makes no native authentication claim.
 */
public final class LinuxBazelSessionTest
{
    /**
     * Creates independently runnable session tests without shared fixtures.
     */
    public LinuxBazelSessionTest() {}

    /**
     * Reserves global attempts across actions while preserving discovery order.
     * @throws IOException if a valid modeled session operation fails
     */
    @Test
    public void preservesDiscoveryOrderAndDistinctGlobalRetryOrdinals() throws IOException
    {
        Fixture fixture = new Fixture();
        Action first = new Action("first");
        Action second = new Action("second");
        try (LinuxBazelSession session = fixture.session())
        {
            session.registerAuthenticated(first);
            session.registerAuthenticated(second);
            requireThat(session.pollSubmission().orElseThrow().name(), "firstDiscovery").isEqualTo("first");
            requireThat(session.pollSubmission().orElseThrow().name(), "secondDiscovery").isEqualTo("second");
            requireThat(session.pollSubmission().isEmpty(), "noDuplicateSubmission").isTrue();

            JobQueue.Attempt<LinuxBazelControl.Result> aborted = session.attempts().create("first", 0);
            requireException(IllegalArgumentException.class, () -> session.attempts().create("first", 1));
            requireException(IllegalArgumentException.class, () -> session.attempts().create("second", 0));
            requireException(IllegalArgumentException.class, () -> session.registerAuthenticated(new Action("first")));
            JobQueue.Outcome<LinuxBazelControl.Result> abortedResult = first.finish(true, 9, true);
            requireThat(aborted.poll().orElseThrow(), "observedAbort").isEqualTo(abortedResult);
            aborted.close();
            JobQueue.Attempt<LinuxBazelControl.Result> peer = session.attempts().create("second", 1);
            JobQueue.Attempt<LinuxBazelControl.Result> retry = session.attempts().create("first", 2);
            session.acceptOutcome(new JobQueue.Completion<>("first", 0, abortedResult));
            requireThat(first.accepted.size(), "earlierAbortConsumedAfterRetryCreation").isEqualTo(1);
            requireThat(first.created, "sameActionDistinctGlobalSequences").isEqualTo(List.of(0L, 2L));
            requireThat(second.created, "peerGlobalSequence").isEqualTo(List.of(1L));
            peer.close();
            retry.close();
        }
    }

    /**
     * Rejects unpolled, unclosed, changed, unknown and already consumed outcomes.
     * @throws IOException if modeled ownership setup fails
     */
    @Test
    public void acceptsOnlyExactCollectedClosedAttemptOutcomeOnce() throws IOException
    {
        Fixture fixture = new Fixture();
        Action action = new Action("action");
        try (LinuxBazelSession session = fixture.session())
        {
            session.registerAuthenticated(action);
            JobQueue.Attempt<LinuxBazelControl.Result> attempt = session.attempts().create("action", 0);
            JobQueue.Outcome<LinuxBazelControl.Result> result = action.finish(false, 23, false);
            JobQueue.Completion<LinuxBazelControl.Result> completion = new JobQueue.Completion<>("action", 0, result);
            requireException(IllegalArgumentException.class, () -> session.acceptOutcome(completion));
            attempt.poll();
            requireException(IllegalArgumentException.class, () -> session.acceptOutcome(completion));
            attempt.close();
            requireException(IllegalArgumentException.class, () -> session.acceptOutcome(
                new JobQueue.Completion<>("other", 0, result)));
            requireException(IllegalArgumentException.class, () -> session.acceptOutcome(
                new JobQueue.Completion<>("action", 1, result)));
            requireException(IllegalArgumentException.class, () -> session.acceptOutcome(
                new JobQueue.Completion<>("action", 0,
                    new JobQueue.Outcome<>(new LinuxBazelControl.Result(false, 0), false))));
            requireThat(action.accepted.isEmpty(), "invalidOutcomesNeverReachAction").isTrue();
            session.acceptOutcome(completion);
            requireException(IllegalArgumentException.class, () -> session.acceptOutcome(completion));
            requireException(IllegalArgumentException.class, () -> session.attempts().create("action", 1));
            requireThat(action.accepted, "oneUnchangedOrdinaryOutcome").isEqualTo(List.of(completion));
        }
    }

    /**
     * Consumes an outcome before an uncertain delivery and never resends it.
     * @throws IOException if modeled setup fails
     */
    @Test
    public void uncertainOutcomeIsConsumedBeforeDeliveryAndNotResent() throws IOException
    {
        Fixture fixture = new Fixture();
        Action action = new Action("action");
        LinuxBazelSession session = fixture.session();
        session.registerAuthenticated(action);
        JobQueue.Attempt<LinuxBazelControl.Result> attempt = session.attempts().create("action", 0);
        JobQueue.Outcome<LinuxBazelControl.Result> result = action.finish(false, 23, false);
        attempt.poll();
        attempt.close();
        action.failAccept = true;
        JobQueue.Completion<LinuxBazelControl.Result> completion = new JobQueue.Completion<>("action", 0, result);
        requireException(IOException.class, () -> session.acceptOutcome(completion));
        requireException(IOException.class, () -> session.acceptOutcome(completion));
        requireThat(action.accepted.size(), "onePotentiallyEffectiveDelivery").isEqualTo(1);
        session.stopAccepting();
        session.stopAccepting();
        session.close();
        requireThat(fixture.stops, "oneProducerStop").isEqualTo(1);
        requireThat(action.closes, "oneActionClose").isEqualTo(1);
        requireThat(fixture.closes, "oneOuterClose").isEqualTo(1);
    }

    /**
     * Correlates a copied allocation wait with its action's current global attempt.
     * @throws IOException if modeled setup fails
     */
    @Test
    public void mapsAllocationBatchToCurrentIssuedAttemptAndRejectsDuplicates() throws IOException
    {
        Fixture fixture = new Fixture();
        Action action = new Action("action");
        LinuxBazelSession session = fixture.session();
        session.registerAuthenticated(action);
        JobQueue.Attempt<LinuxBazelControl.Result> attempt = session.attempts().create("action", 7);
        action.blocked = 3;
        requireThat(session.pollBlocked().orElseThrow(), "currentAttemptCorrelation").isEqualTo(
            new SchedulerCoordinator.Blocked("action", 7, 3));
        action.blocked = 3;
        requireException(IOException.class, session::pollBlocked);
        attempt.close();
        session.close();
    }

    /**
     * Prevents outer closure while an issued owner retains cleanup responsibility.
     * @throws IOException if modeled setup fails
     */
    @Test
    public void retainsOuterDependenciesUntilIssuedAttemptCloses() throws IOException
    {
        Fixture fixture = new Fixture();
        Action action = new Action("action");
        LinuxBazelSession session = fixture.session();
        session.registerAuthenticated(action);
        JobQueue.Attempt<LinuxBazelControl.Result> attempt = session.attempts().create("action", 0);
        requireException(IOException.class, session::close);
        requireThat(fixture.closes, "outerDependenciesRemainOpen").isEqualTo(0);
        requireThat(action.closes, "actionControlRemainsOpen").isEqualTo(0);
        attempt.close();
        session.close();
        session.close();
        requireThat(action.closes, "oneActionCloseAfterBarrier").isEqualTo(1);
        requireThat(fixture.closes, "oneOuterCloseAfterBarrier").isEqualTo(1);
    }

    /**
     * Supplies invocation callbacks and measurements unique to one test.
     */
    private static final class Fixture implements LinuxBazelSession.Invocation
    {
        /** Number of producer-stop calls in this test. */
        private int stops;
        /** Number of outer close calls in this test. */
        private int closes;

        /**
         * Creates one test-local root owner.
         */
        private Fixture() {}

        /**
         * Creates the real session with measurements unused by these unit cases.
         * @return newly owned operation-thread-confined session
         */
        private LinuxBazelSession session()
        {
            Controller.Measurements measurements = new Controller.Measurements()
            {
                @Override public Controller.Memory readHost() { throw new UnsupportedOperationException("Not sampled"); }
                @Override public long readResidentBytes(Policy.Job job) { throw new UnsupportedOperationException("Not sampled"); }
            };
            return new LinuxBazelSession(measurements, this);
        }

        @Override public void pump() {}
        @Override public boolean inputClosed() { return true; }
        @Override public OptionalInt pollToolResult() { return OptionalInt.of(1); }
        @Override public void stopAccepting() { ++stops; }
        @Override public void close() { ++closes; }
    }

    /**
     * Models the action-owned closed-attempt ledger and callback boundary only;
     * source contracts are LinuxBazelSession.Action and JobQueue.Attempt.
     */
    private static final class Action implements LinuxBazelSession.Action
    {
        /** Stable identity of this test's modeled authenticated action. */
        private final String name;
        /** Global ordinals supplied by the real session. */
        private final List<Long> created = new ArrayList<>();
        /** Exact outcome deliveries made by the real session. */
        private final List<JobQueue.Completion<LinuxBazelControl.Result>> accepted = new ArrayList<>();
        /** Current modeled native whole-job outcome, or null before collection. */
        private JobQueue.Outcome<LinuxBazelControl.Result> outcome;
        /** Indicates whether a test-local attempt remains unclosed. */
        private boolean open;
        /** Indicates whether delivery reports uncertainty after recording its effect. */
        private boolean failAccept;
        /** Next copied wait batch, or -1 when absent. */
        private long blocked = -1;
        /** Number of action-control closes in this test. */
        private int closes;

        /**
         * Retains this test's action identity.
         * @param name nonblank stable action identity used in correlation cases
         * @throws NullPointerException if {@code name} is null
         * @throws IllegalArgumentException if {@code name} is blank
         */
        private Action(String name)
        {
            this.name = Objects.requireNonNull(name, "name");
            if (name.isBlank()) throw new IllegalArgumentException("Modeled action name must be nonblank");
        }

        /**
         * Supplies a native outcome for session correlation, without claiming a
         * native process actually ran.
         * @param signaled indicates whether the supplied result is a signal
         * @param status supplied original native value
         * @param aborted supplied independent abortion classification
         * @return the supplied immutable outcome
         */
        private JobQueue.Outcome<LinuxBazelControl.Result> finish(boolean signaled, int status, boolean aborted)
        {
            outcome = new JobQueue.Outcome<>(new LinuxBazelControl.Result(signaled, status), aborted);
            return outcome;
        }

        @Override public String name() { return name; }
        @Override public Long peakBytes() { return null; }
        @Override public JobQueue.Attempt<LinuxBazelControl.Result> create(long sequence)
        {
            created.add(sequence);
            open = true;
            outcome = null;
            return new JobQueue.Attempt<>()
            {
                @Override public void start() {}
                @Override public void resume(long waitSequence) {}
                @Override public void terminate() {}
                @Override public Optional<JobQueue.Outcome<LinuxBazelControl.Result>> poll()
                {
                    return Optional.ofNullable(outcome);
                }
                @Override public void close() { open = false; }
            };
        }
        @Override public void pump() {}
        @Override public OptionalLong pollBlocked()
        {
            long next = blocked;
            blocked = -1;
            return next < 0 ? OptionalLong.empty() : OptionalLong.of(next);
        }
        @Override public boolean hasOpenAttempt() { return open; }
        @Override public void acceptOutcome(JobQueue.Completion<LinuxBazelControl.Result> completion) throws IOException
        {
            accepted.add(completion);
            if (failAccept) throw new IOException("Modeled uncertain action delivery");
        }
        @Override public void close() { ++closes; }
    }
}
