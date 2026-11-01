package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import io.github.cowwoc.pouch10.core.AbstractScope;
import io.github.cowwoc.pouch10.core.ConcurrentLazyFactory;
import io.github.cowwoc.pouch10.core.Scopes;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounds sequential Mac Cargo invocations, their shared runtime and borrowed tooling services.
 * Concurrent getters are supported while open. Native owners retain their explicit terminal barriers;
 * the composition boundary closes them and every borrower before closing this scope.
 * The supplied tooling scope remains caller-owned and must outlive all native destruction.
 */
final class MacCargoScope extends AbstractScope
{
    /** Borrowed shared parsers and native services retained by the composition boundary. */
    private final ToolingScope tooling;
    /** Distinct bounded strict administrator launch-document parser, owned by this invocation scope. */
    private final ConcurrentLazyFactory<JsonMapper> launchJson = ConcurrentLazyFactory.create(() ->
        JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).
            streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(1048576).
                maxNestingDepth(8).maxStringLength(262144).maxNumberLength(20).build()).build()).
            enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build(), value -> {});
    /** Indicates disposal has begun, permanently preventing acquisition. */
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Native owner thread, independent of concurrent immutable parser access. */
    private final Thread owner = Thread.currentThread();
    /** Creates resource-free original owners before their first fallible native effect. */
    private final RuntimeFactory runtimeFactory;
    /** Scoped holder retained by Pouch before initialization. */
    private final ConcurrentLazyFactory<RuntimeState> runtimes = ConcurrentLazyFactory.create(RuntimeState::new, RuntimeState::close);
    /** Exact holder retained independently through uncertain scoped destruction. */
    private RuntimeState retainedRuntime;
    /** Permanently rejects acquisitions after disposal begins. */
    private volatile boolean disposing;
    /** First uncertain scoped destruction, never replayed. */
    private Throwable closeFailure;


    /**
     * Borrows the caller's tooling services without acquiring native resources.
     * @param tooling caller-owned shared tooling services
     * @throws NullPointerException if tooling is null
     */
    MacCargoScope(ToolingScope tooling)
    {
        this(tooling, (services, path) ->
        {
            var runtime = new FridaRuntime(services, path);
            return new RuntimeOwner()
            {
                @Override public FridaRuntime value() { return runtime; }
                @Override public void start() throws IOException { runtime.start(); }
                @Override public void close() throws IOException { runtime.close(); }
            };
        });
    }

    /**
     * Supplies a controlled native acquisition boundary while retaining actual scoped ownership policy.
     * @param tooling borrowed tooling scope
     * @param runtimeFactory creates resource-free original runtime owners
     */
    MacCargoScope(ToolingScope tooling, RuntimeFactory runtimeFactory)
    {
        this.tooling = Objects.requireNonNull(tooling, "tooling");
        this.runtimeFactory = Objects.requireNonNull(runtimeFactory, "runtimeFactory");
    }

    /**
     * Borrows one original runtime for the exact selected producer, retaining it before startup.
     * @param producer trusted absolute normalized instrumentation library
     * @return invocation-owned lease whose release never destroys the shared runtime
     * @throws NullPointerException if the producer is null
     * @throws IOException if original initialization failed or producer identity differs
     * @throws IllegalStateException if off-thread or disposal has begun
     */
    RuntimeLease runtime(Path producer) throws IOException
    {
        requireOwner();
        tooling();
        if (!producer.isAbsolute() || !producer.equals(producer.normalize())) throw new IOException("Absolute normalized Frida producer required");
        retainedRuntime = runtimes.getValue();
        return retainedRuntime.borrow(producer);
    }

    /** Requires the original native controller thread. */
    private void requireOwner()
    {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Mac Cargo runtime belongs to its constructing thread");
    }

    /** Creates resource-free native owners; implementations must perform no native effect. */
    @FunctionalInterface interface RuntimeFactory
    {
        /**
         * Reserves original native ownership before start.
         * @param tooling borrowed services
         * @param producer exact selected library
         * @return resource-free retained owner
         */
        RuntimeOwner create(ToolingScope tooling, Path producer);
    }

    /** Owns original runtime acquisition and destruction, independently of invocation managers. */
    interface RuntimeOwner
    {
        /** Returns the original runtime.
         * @return original runtime borrowed by invocation-owned managers */
        FridaRuntime value();
        /** Initializes the original runtime once.
         * @throws IOException if original initialization fails, possibly after partial effects */
        void start() throws IOException;
        /** Destroys the original runtime after all borrowers finish.
         * @throws IOException if native destruction remains uncertain */
        void close() throws IOException;
    }

    /** Retains one scoped original owner and sticky acquisition outcome. */
    private final class RuntimeState
    {
        /** Exact selected producer, immutable after first reservation. */
        private Path producer;
        /** Original resource-free owner retained before start. */
        private RuntimeOwner runtime;
        /** Original failed initialization, never retried. */
        private Throwable failure;
        /** Number of invocation lifetimes retaining this owner. */
        private int borrowers;
        /** Indicates completed original destruction. */
        private boolean released;
        /** Excludes reentrant acquisition or disposal during original initialization. */
        private boolean starting;
        /** Creates a resource-free Pouch holder. */
        private RuntimeState() {}
        /**
         * Initializes once and retains one independent invocation borrow.
         * @param selected exact selected native producer
         * @return independent invocation lease
         * @throws IOException if initialization or producer authority is uncertain
         */
        private RuntimeLease borrow(Path selected) throws IOException
        {
            if (starting) throw new IllegalStateException("Scoped Frida initialization cannot reenter acquisition");
            if (producer != null && !producer.equals(selected)) throw new IOException("Scoped Frida producer cannot change");
            if (failure != null) throw new IOException("Original scoped Frida initialization failed", failure);
            if (runtime == null)
            {
                producer = selected;
                starting = true;
                try
                {
                    runtime = Objects.requireNonNull(runtimeFactory.create(tooling, producer), "runtime owner");
                    runtime.start();
                }
                catch (IOException | RuntimeException | Error cause) { failure = cause; throw cause; }
                finally { starting = false; }
            }
            borrowers++;
            return new RuntimeLease(this);
        }
        /** Releases the original runtime only after every invocation returned its lease. */
        private void close()
        {
            requireOwner();
            if (released) return;
            if (borrowers != 0) throw new IllegalStateException("Invocation still borrows scoped Frida runtime");
            try { if (runtime != null) runtime.close(); released = true; }
            catch (IOException cause) { throw new UncheckedIOException("Scoped Frida destruction remains uncertain", cause); }
        }
    }

    /** Retains shared runtime ownership through one invocation's complete dependency cleanup. */
    final class RuntimeLease implements AutoCloseable
    {
        /** Exact scoped owner, never selected by PID or invocation UUID. */
        private final RuntimeState state;
        /** Indicates this invocation returned its borrow. */
        private boolean returned;
        /** Retains the original scoped owner.
         * @param state original scoped owner */
        private RuntimeLease(RuntimeState state) { this.state = state; }
        /**
         * Returns the original runtime while its invocation lease remains retained.
         * @return original initialized runtime
         * @throws IllegalStateException if off-thread or returned
         */
        FridaRuntime value()
        {
            requireOwner();
            if (returned) throw new IllegalStateException("Runtime lease has returned");
            return state.runtime.value();
        }
        /** Returns only this borrow after all invocation managers and native resources close. */
        @Override public void close()
        {
            requireOwner();
            if (returned) return;
            state.borrowers--;
            returned = true;
        }
    }

    /**
     * Returns shared immutable parsers and native services for the invocation's explicit owners.
     * @return borrowed tooling scope retained open by its caller
     * @throws IllegalStateException if this Cargo scope has begun disposal
     */
    ToolingScope tooling()
    {
        if (closed.get() || disposing) throw new IllegalStateException("Mac Cargo scope is closed");
        return tooling;
    }

    /**
     * Returns the shared strict launch-document parser with independent bounds from instrumentation messages.
     * @return invocation-owned immutable parser; callers must not close it
     * @throws IllegalStateException if disposal has begun
     */
    JsonMapper launchJson()
    {
        tooling();
        return launchJson.getValue();
    }

    @Override public boolean isClosed() { return closed.get(); }

    /**
     * Closes registered child scopes after borrowers finish without closing the supplied tooling scope.
     * Acquisition stays disabled if child cleanup fails. Repeated disposal has no additional effects.
     */
    @Override public void close()
    {
        if (closed.get()) return;
        if (retainedRuntime != null) requireOwner();
        if (retainedRuntime != null && retainedRuntime.starting)
            throw new IllegalStateException("Scoped Frida initialization cannot reenter disposal");
        disposing = true;
        if (closeFailure instanceof RuntimeException failed) throw failed;
        if (closeFailure instanceof Error failed) throw failed;
        if (retainedRuntime != null && retainedRuntime.borrowers != 0)
            throw new IllegalStateException("Invocation still borrows scoped Frida runtime");
        try
        {
            Scopes.runAll(() -> children.shutdown(Duration.ZERO), launchJson::close, runtimes::close);
            closed.set(true);
        }
        catch (RuntimeException | Error cause) { closeFailure = cause; throw cause; }
    }
}
