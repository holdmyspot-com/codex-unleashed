package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Exercises actual queue/controller composition with modeled authenticated arrivals and native owners. */
public final class CargoSessionTest
{
    /** Creates focused session tests. */
    public CargoSessionTest() {}

    /** Late arrivals share one queue, lease and response lifetime through an ordered retry.
     * @throws IOException if valid modeled composition fails */
    @Test public void admitsLateDiscoveryAndPreservesOriginalRetryPosition() throws IOException
    {
        var h = new Harness();
        int result = h.coordinator().run();
        requireThat(result, "actualCargoStatus").isEqualTo(101);
        requireThat(h.started, "oneQueueOriginalRetryOrder").isEqualTo(List.of(h.a.name(), h.b.name(), h.a.name()));
        requireThat(h.positions, "originalDiscoveryPositions").isEqualTo(List.of(0L, 1L));
        requireThat(h.session.attempt(0).orElseThrow().actionSequence(), "firstA").isEqualTo(1L);
        requireThat(h.session.attempt(1).orElseThrow().actionSequence(), "firstB").isEqualTo(1L);
        requireThat(h.session.attempt(2).orElseThrow().actionSequence(), "secondA").isEqualTo(2L);
        requireThat(h.session.attempt(2).orElseThrow().globalSequence(), "independentGlobalOrdinal").isEqualTo(2L);
        requireThat(h.responses, "onlyFinalNativeResults").isEqualTo(List.of(new WindowsProcess.Result(23, false),
            new WindowsProcess.Result(0xFFFFFFFFL, false)));
        requireThat(h.session.acceptedOutcomes().size(), "abortReceiptRetained").isEqualTo(3);
        requireThat(h.events, "consumerBarrierBeforeLeaseAndRuntime").isEqualTo(List.of("wrappers-reaped", "lease-closed", "runtime-closed"));
        requireThat(h.stopCalls, "noFailureStop").isEqualTo(0);
        requireThat(h.closeCalls, "oneOuterLeaseLifetime").isEqualTo(1);
    }

    /** An uncertain final response is retained and never resent; remaining attempts are reaped first.
     * @throws IOException if setup fails */
    @Test public void uncertainResponseStopsWholeSessionWithoutResending() throws IOException
    {
        var h = new Harness();
        h.failResponse = true;
        var coordinator = h.coordinator();
        IOException failure = requireException(IOException.class, coordinator::run);
        requireThat(failure.getMessage(), "uncertainBoundary").isEqualTo("Compiler final response is uncertain");
        requireThat(h.responses.size(), "oneAttemptedPublication").isEqualTo(1);
        requireThat(h.session.acceptedOutcomes().size(), "nativeOutcomeRetained").isEqualTo(2);
        requireThat(h.stopCalls, "wholeSessionStopped").isEqualTo(1);
        requireThat(h.closeCalls, "allConsumersReaped").isEqualTo(1);
        requireThat(h.factory.hasOpenAttempts(), "noOrphanedAttempt").isFalse();
        requireException(IOException.class, () -> h.session.acceptOutcome(h.session.acceptedOutcomes().getLast()));
        requireThat(h.responses.size(), "noSecondDeliveryAfterFault").isEqualTo(1);
    }

    /** Native-identity-authenticated disconnect cancels through coordinator cleanup, never behind the queue.
     * @throws IOException if setup fails */
    @Test public void disconnectStopsAllActionsAndRetainsWrapperLifetime() throws IOException
    {
        var h = new Harness();
        h.disconnect = true;
        requireException(IOException.class, () -> h.coordinator().run());
        requireThat(h.stopCalls, "wholeInvocationStop").isEqualTo(1);
        requireThat(h.responses.isEmpty(), "noInventedCompilerStatus").isTrue();
        requireThat(h.factory.hasOpenAttempts(), "allQueueOwnersReaped").isFalse();
        requireThat(h.events, "leaseAfterWrapperBarrier").isEqualTo(List.of("wrappers-reaped", "lease-closed", "runtime-closed"));
    }

    /** Partial command registration reserves identity, exposes no submission and prevents uncertain repetition.
     * @throws IOException if unexpected setup fails */
    @Test public void registrationFailureIsStickyAndCannotLeakSubmission() throws IOException
    {
        var h = new Harness();
        int[] calls = {0};
        var session = new CargoSession<WindowsProcess.Result>(h.factory, h.measurements, (request, position) ->
        { ++calls[0]; throw new IOException("partial registration"); }, h);
        requireException(IOException.class, () -> session.registerAuthenticated(h.a, result -> {}));
        requireException(IOException.class, session::pollSubmission);
        requireException(IOException.class, () -> session.registerAuthenticated(h.a, result -> {}));
        requireThat(calls[0], "uncertainRegistrationNotRepeated").isEqualTo(1);
        session.stopAccepting(); session.close();
    }

    /** Duplicate and cross-thread arrivals do not mutate the registry or consume discovery order.
     * @throws Exception if thread coordination fails */
    @Test public void rejectsDuplicateAndWrongThreadWithoutChangingPosition() throws Exception
    {
        var h = new Harness();
        h.session.registerAuthenticated(h.a, h.responses::add);
        requireException(IllegalArgumentException.class, () -> h.session.registerAuthenticated(h.a, h.responses::add));
        var wrongThread = new AtomicReference<Throwable>();
        Thread thread = Thread.ofPlatform().start(() ->
        {
            try { h.session.registerAuthenticated(h.b, h.responses::add); }
            catch (Throwable failure) { wrongThread.set(failure); }
        });
        thread.join();
        requireThat(wrongThread.get() instanceof IllegalStateException, "wrongThreadRejected").isTrue();
        h.session.registerAuthenticated(h.b, h.responses::add);
        requireThat(h.positions, "unconsumedOriginalPosition").isEqualTo(List.of(0L, 1L));
        requireThat(h.session.pollSubmission().orElseThrow().name(), "first").isEqualTo(h.a.name());
        requireThat(h.session.pollSubmission().orElseThrow().name(), "second").isEqualTo(h.b.name());
        h.session.stopAccepting(); h.session.close();
    }

    /** A retained owner prevents outer cleanup, including after a failed queue cleanup.
     * @throws IOException if setup fails */
    @Test public void unresolvedAttemptPreventsLeaseOrRuntimeClosure() throws IOException
    {
        var h = new Harness();
        h.session.registerAuthenticated(h.a, h.responses::add);
        var attempt = h.session.attempts().create(h.a.name(), 0);
        attempt.start();
        requireException(IOException.class, h.session::close);
        requireThat(h.closeCalls, "outerOwnerRetained").isEqualTo(0);
        attempt.terminate(); attempt.poll(); attempt.close();
        h.session.close();
        requireThat(h.closeCalls, "outerCloseAfterReaping").isEqualTo(1);
        h.session.close();
        requireThat(h.closeCalls, "noRepeatedOuterClose").isEqualTo(1);
    }

    /** Reentrant command registration must not invert the original submission positions.
     * @throws IOException if unexpected setup fails */
    @Test public void rejectsReentrantRegistrationBeforePublishingEitherSubmission() throws IOException
    {
        var h = new Harness();
        var reference = new AtomicReference<CargoSession<WindowsProcess.Result>>();
        var session = new CargoSession<WindowsProcess.Result>(h.factory, h.measurements, (request, position) ->
        {
            if (request.name().equals(h.a.name())) reference.get().registerAuthenticated(h.b, result -> {});
        }, h);
        reference.set(session);
        requireException(IOException.class, () -> session.registerAuthenticated(h.a, result -> {}));
        requireException(IOException.class, session::pollSubmission);
        session.stopAccepting(); session.close();
    }

    /** Controlled platform owner and producer; it makes no native Windows execution claims. */
    private static final class Harness implements CargoSession.Invocation
    {
        /** Stable modeled authenticated compiler requests. */
        private final CargoCompilerRequest a = estimated(1), b = estimated(2);
        /** Shared factory with production registration and attempt lifecycle. */
        private final WindowsAttempts factory;
        /** Composed request/session owner. */
        private final CargoSession<WindowsProcess.Result> session;
        /** Actual request acceptance positions. */
        private final List<Long> positions = new ArrayList<>();
        /** Issued action order across retries. */
        private final List<String> started = new ArrayList<>();
        /** Latest modeled native owner for each action. */
        private final Map<String, WindowsAttemptTest.Resources> current = new HashMap<>();
        /** Published native final payloads. */
        private final List<WindowsProcess.Result> responses = new ArrayList<>();
        /** Outer dependency barrier events. */
        private final List<String> events = new ArrayList<>();
        /** Model event-loop round. */
        private int round;
        /** Stop and close attempt counts. */
        private int stopCalls, closeCalls;
        /** Fault modes selected by each focused test. */
        private boolean failResponse, disconnect;
        /** Physical host/tree measurements for actual controller decisions. */
        private final Controller.Measurements measurements = new Controller.Measurements()
        {
            @Override public Controller.Memory readHost() { return new Controller.Memory(1000, 0, false); }
            @Override public long readResidentBytes(Policy.Job job) { return 1; }
        };

        /**
         * Provides explicit known demand for the two-concurrent-action fixture.
         * @param number stable action suffix
         * @return modeled request with a known ten-byte whole-tree estimate
         */
        private static CargoCompilerRequest estimated(long number)
        {
            var r = CargoCompilerRequestTest.request(number);
            return new CargoCompilerRequest(r.identity(), r.wrapper(), r.arguments(), r.directory(), r.environment(), 10L);
        }
        /** Constructs resource-free collaborators without native access. */
        Harness()
        {
            factory = new WindowsAttempts(UUID.randomUUID(), Map.of(), Duration.ofSeconds(1), (command, sequence, identity, timeout) ->
            {
                var resource = new WindowsAttemptTest.Resources();
                resource.completeOnTerminate = true;
                current.put(command.action().name(), resource);
                started.add(command.action().name());
                return resource;
            });
            session = new CargoSession<>(factory, measurements, (request, position) ->
            {
                positions.add(position);
                factory.register(request.name(), new WindowsAttempts.Command(new Controller.Action(request.name(), position),
                    request.arguments(), request.directory(), request.environment(), MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL));
            }, this);
        }
        /**
         * Creates the real queue/controller coordinator around this modeled producer.
         * @return one run-once coordinator
         */
        SchedulerCoordinator<WindowsProcess.Result> coordinator()
        {
            return new SchedulerCoordinator<>(session, new Policy.Budget(0, 2), () ->
            { if (round > 10) throw new IOException("Model failed to progress"); });
        }
        @Override public void pump() throws IOException
        {
            ++round;
            if (round == 1) session.registerAuthenticated(a, result ->
            {
                responses.add(result);
                if (failResponse) throw new IOException("response may already be delivered");
            });
            if (round == 2) session.registerAuthenticated(b, result ->
            {
                responses.add(result);
                if (failResponse) throw new IOException("response may already be delivered");
            });
            if (round == 3)
            {
                if (disconnect) session.wrapperDisconnected(a.name());
                current.get(a.name()).result = new WindowsProcess.Result(37, true);
            }
            if (round == 4) current.get(b.name()).result = new WindowsProcess.Result(23, false);
            if (round == 5) current.get(a.name()).result = new WindowsProcess.Result(0xFFFFFFFFL, false);
            factory.pumpAll();
        }
        @Override public Optional<SchedulerCoordinator.Blocked> pollBlocked()
        {
            return factory.pollBlocked().map(b -> new SchedulerCoordinator.Blocked(b.name(), b.attemptSequence(), b.waitSequence()));
        }
        @Override public boolean inputClosed() { return round >= 5; }
        @Override public OptionalInt pollToolResult() { return round >= 5 ? OptionalInt.of(101) : OptionalInt.empty(); }
        @Override public boolean hasOpenAttempts() { return factory.hasOpenAttempts(); }
        @Override public void stopAccepting() { ++stopCalls; }
        @Override public void close()
        {
            requireThat(factory.hasOpenAttempts(), "nativeBarrierBeforeWrappers").isFalse();
            ++closeCalls;
            events.addAll(List.of("wrappers-reaped", "lease-closed", "runtime-closed"));
        }
    }
}
