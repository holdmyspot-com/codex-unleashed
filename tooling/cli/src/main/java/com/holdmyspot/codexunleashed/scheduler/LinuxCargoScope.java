package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import io.github.cowwoc.pouch10.core.AbstractScope;
import io.github.cowwoc.pouch10.core.ConcurrentLazyFactory;
import io.github.cowwoc.pouch10.core.Scopes;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns shared Cargo message parsing and capability generation during one invocation.
 * Getters support concurrent callers while open. The composition boundary closes this scope after its borrowers
 * finish; the supplied tooling scope remains caller-owned and must outlive it. Native owners retain their own lifetimes.
 */
public final class LinuxCargoScope extends AbstractScope
{
    /** Number of random bytes in the borrowed-stream protocol's 256-bit attempt capability. */
    private static final int CAPABILITY_BYTES = 32;
    /** Borrowed tooling services, kept open by the composition boundary. */
    private final ToolingScope tooling;
    /** Strict Cargo parser, with independent limits from allocation messages and leader envelopes. */
    private final ConcurrentLazyFactory<JsonMapper> json = ConcurrentLazyFactory.create(() ->
        JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).
            streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(LinuxCargoTransport.MAX_FRAME_BYTES).
                maxNestingDepth(8).maxStringLength(262144).maxNumberLength(20).build()).build()).
            enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build(), value -> {});
    /** Unpredictable source shared by capabilities minted within this invocation. */
    private final ConcurrentLazyFactory<SecureRandom> random = ConcurrentLazyFactory.create(SecureRandom::new, value -> {});
    /** Indicates whether disposal has begun, permanently preventing acquisition. */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Borrows tooling services without acquiring a parser, generator or native resource.
     * @param tooling caller-owned tooling scope retained open through this scope's borrowers
     * @throws NullPointerException if {@code tooling} is null
     */
    public LinuxCargoScope(ToolingScope tooling)
    {
        this.tooling = Objects.requireNonNull(tooling, "tooling");
    }

    /**
     * Returns the supplied caller-owned tooling services.
     * @return borrowed tooling scope, retained open after this scope closes
     * @throws IllegalStateException if this scope is closed
     */
    public ToolingScope tooling()
    {
        requireOpen();
        return tooling;
    }

    /**
     * Returns the immutable parser for Cargo control messages.
     * It rejects duplicate fields and trailing documents and bounds documents to 1 MiB, nesting to eight levels,
     * individual strings to 262,144 characters and number spellings to twenty characters.
     * @return this scope's shared parser; callers must not close it
     * @throws IllegalStateException if this scope is closed
     */
    public JsonMapper json()
    {
        requireOpen();
        return json.getValue();
    }

    /**
     * Mints an independent capability for one borrowed-stream attempt authorization.
     * @return a new caller-owned array containing 32 unpredictable bytes
     * @throws IllegalStateException if this scope is closed
     */
    public byte[] capability()
    {
        requireOpen();
        byte[] capability = new byte[CAPABILITY_BYTES];
        random.getValue().nextBytes(capability);
        return capability;
    }

    @Override public boolean isClosed() { return closed.get(); }

    /**
     * Disposes this scope's shared services after its borrowers finish, without closing supplied tooling.
     * Repeated closure has no effects. Acquisition remains disabled if child cleanup fails.
     */
    @Override public void close()
    {
        if (!closed.compareAndSet(false, true)) return;
        Scopes.runAll(() -> children.shutdown(Duration.ZERO), random::close, json::close);
    }

    /**
     * Rejects acquisition after disposal begins.
     * @throws IllegalStateException if this scope is closed
     */
    private void requireOpen()
    {
        if (closed.get()) throw new IllegalStateException("Cargo scope is closed");
    }
}
