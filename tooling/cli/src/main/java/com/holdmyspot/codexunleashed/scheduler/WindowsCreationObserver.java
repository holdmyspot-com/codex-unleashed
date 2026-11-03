package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;

import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Owns creation observation and gating for a root Cargo Job without compiler allocation controls.
 * Each image receives original-manager child gating and any additional protocol before its sole resume.
 * Calls are confined to the thread that constructed the root Job owner.
 */
public final class WindowsCreationObserver
{
    /** Exact prepared Cargo Job retaining every original creation authority. */
    private final WindowsProcess process;
    /** Existing creation/event join coordinator, configured without allocation controls. */
    private final WindowsAllocationHooks hooks;
    /** Original creator authorities already delivered to the invocation. */
    private final Set<WindowsCreationReceipt> delivered = new HashSet<>();

    /**
     * Creates resource-free ownership for a prepared root Cargo Job.
     * @param scope borrowed Pouch values
     * @param identity invocation identity
     * @param runtime borrowed started Frida runtime
     * @param process authoritative prepared Cargo Job
     * @param timeout positive native-control interval
     * @param peers owner-thread peer service without queue controls
     * @param imageHooks additional per-image token protocol, or null when absent
     */
    WindowsCreationObserver(ToolingScope scope, UUID identity, FridaRuntime runtime, WindowsProcess process,
        Duration timeout, AllocationHook.Pump peers, WindowsAllocationHooks.ImageHooks imageHooks)
    {
        this.process = Objects.requireNonNull(process, "process");
        this.hooks = new WindowsAllocationHooks(WindowsAllocationHooks.creationBackend(scope, identity,
            runtime, process, timeout, imageHooks), peers);
    }

    /**
     * Installs the root observer before child gating and releases the prepared Cargo root once.
     * @throws IOException if original identity, observation, readiness or release fails
     */
    void start() throws IOException { hooks.start(); }

    /**
     * Delivers bounded native creation/event joins using the original manager.
     * @param advance indicates whether a ready held descendant may advance to its single resume
     * @throws IOException if native authority, correlation or readiness fails
     */
    void pump(boolean advance) throws IOException { hooks.pump(advance); }

    /**
     * Delivers each authenticated original creation authority at most once.
     * @return one retained receipt, or empty when no new authority is available
     */
    Optional<WindowsCreationReceipt> pollReceipt()
    {
        for (WindowsCreationReceipt receipt : process.creationReceipts())
        {
            if (delivered.add(receipt)) return Optional.of(receipt);
        }
        return Optional.empty();
    }

    /**
     * Finds retained original authority and checks its complete creation chain back to this Cargo root.
     * A transport PID only selects this candidate; the returned native authority establishes its identity.
     * @param pid transport-supplied candidate process identifier
     * @return authenticated live authority, or empty when no receipt exists
     * @throws IOException if birth, Job membership or retained ancestry differs
     */
    Optional<WindowsCreationReceipt> receipt(long pid) throws IOException
    {
        WindowsCreationReceipt receipt = process.creationReceipt(pid).orElse(null);
        if (receipt == null) return Optional.empty();
        receipt.requireLive();
        receipt.state();
        WindowsCreationReceipt cursor = receipt;
        Set<Long> ancestors = new HashSet<>();
        while (cursor.parentPid() != process.pid())
        {
            if (!ancestors.add(cursor.pid())) throw new IOException("Creation ancestry contains a cycle");
            WindowsCreationReceipt parent = process.creationReceipt(cursor.parentPid()).orElseThrow(
                () -> new IOException("Creation ancestry has no retained original parent"));
            if (parent.birth() != cursor.parentBirth()) throw new IOException("Creation ancestry parent birth differs");
            cursor = parent;
        }
        if (cursor.parentBirth() != process.birth()) throw new IOException("Creation ancestry root birth differs");
        return Optional.of(receipt);
    }

    /**
     * Withdraws new resume and grant authority while preserving native owners through whole-Job zero.
     */
    void stop() { hooks.stop(); }

    /**
     * Destroys creation instrumentation only after every process in this Cargo Job cannot execute.
     * @param barrier authoritative actual whole-Job-zero observation
     * @throws IOException if the barrier is absent or native cleanup is unresolved
     */
    void close(AllocationHook.ExecutionBarrier barrier) throws IOException { hooks.close(barrier); }
    /** Owns a strict parser for complete native process-state frames, independently of allocation notifications. */
    public static final class MessageScope extends io.github.cowwoc.pouch10.core.AbstractScope
    {
        /** Document bound shared by the pre-parser transport check and this immutable parser. */
        public static final int MAX_CHARACTERS = 1_048_576;
        /** Lazily constructed immutable parser shared by this observer's frames and controls. */
        private final io.github.cowwoc.pouch10.core.ConcurrentLazyFactory<tools.jackson.databind.json.JsonMapper> json =
            io.github.cowwoc.pouch10.core.ConcurrentLazyFactory.create(() ->
                tools.jackson.databind.json.JsonMapper.builder(tools.jackson.core.json.JsonFactory.builder().
                    enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).
                    streamReadConstraints(tools.jackson.core.StreamReadConstraints.builder().
                        maxDocumentLength(MAX_CHARACTERS).maxStringLength(262144).maxNestingDepth(8).
                        maxNumberLength(20).build()).build()).
                    enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build(), value -> {});
        /** Permanently prevents acquisition after disposal starts. */
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

        /** Creates an observer-local scope without constructing a parser or acquiring native resources. */
        public MessageScope() {}

        /**
         * Returns this scope's strict bounded parser for complete UTF-16 process-state observations.
         * @return the same immutable parser while this scope is open
         * @throws IllegalStateException if disposal began
         */
        public tools.jackson.databind.json.JsonMapper json()
        {
            if (closed.get()) throw new IllegalStateException("Creation message scope is closed");
            return json.getValue();
        }
        @Override public boolean isClosed() { return closed.get(); }
        /** Disposes shared values after native observers can no longer deliver frames; repeated closure has no effects. */
        @Override public void close()
        {
            if (!closed.compareAndSet(false, true)) return;
            io.github.cowwoc.pouch10.core.Scopes.runAll(() -> children.shutdown(Duration.ZERO), json::close);
        }
    }

}
