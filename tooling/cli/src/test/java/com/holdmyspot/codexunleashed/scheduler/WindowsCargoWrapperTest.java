package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies wrapper protocol decisions with complete response inputs and independently owned per-test sessions.
 */
public final class WindowsCargoWrapperTest
{
    /**
     * Creates independent wrapper checks.
     */
    public WindowsCargoWrapperTest() {}

    /**
     * The wrapper drains output before closing and preserves the full native DWORD result.
     * @throws IOException if a valid exchange fails
     */
    @Test public void drainsBeforeReturningDword() throws IOException
    {
        try (DefaultToolingScope tooling = new DefaultToolingScope(); WindowsCargoScope scope = new WindowsCargoScope(tooling))
        {
            WindowsCargoMessages codec = new WindowsCargoMessages(scope);
            Fixture session = new Fixture(List.of(codec.accepted(), codec.finalResult(0xc0000005L)));
            WindowsCargoWrapper wrapper = new WindowsCargoWrapper(scope, session.registration(), () -> session);
            requireThat(wrapper.run(), "status").isEqualTo(0xc0000005L);
            requireThat(session.events, "events").isEqualTo(List.of("registration", "accepted", "finish", "close"));
            requireException(IllegalStateException.class, wrapper::run);
            requireThat(session.registrations, "registrations").isEqualTo(1);
        }
    }

    /**
     * A final response before stream-import acceptance closes the session without draining or replaying registration.
     * @throws IOException if message construction fails
     */
    @Test public void rejectsPrematureFinal() throws IOException
    {
        try (DefaultToolingScope tooling = new DefaultToolingScope(); WindowsCargoScope scope = new WindowsCargoScope(tooling))
        {
            Fixture session = new Fixture(List.of(new WindowsCargoMessages(scope).finalResult(0)));
            WindowsCargoWrapper wrapper = new WindowsCargoWrapper(scope, session.registration(), () -> session);
            requireException(IOException.class, wrapper::run);
            requireThat(session.events, "events").isEqualTo(List.of("registration", "close"));
            requireThat(session.registrations, "registrations").isEqualTo(1);
        }
    }

    /**
     * Duplicate acceptance fails instead of reopening streams or accepting a later final response.
     * @throws IOException if message construction fails
     */
    @Test public void rejectsRepeatedAcceptance() throws IOException
    {
        try (DefaultToolingScope tooling = new DefaultToolingScope(); WindowsCargoScope scope = new WindowsCargoScope(tooling))
        {
            WindowsCargoMessages codec = new WindowsCargoMessages(scope);
            Fixture session = new Fixture(List.of(codec.accepted(), codec.accepted(), codec.finalResult(0)));
            requireException(IOException.class, () -> new WindowsCargoWrapper(scope, session.registration(), () -> session).run());
            requireThat(session.events, "events").isEqualTo(List.of("registration", "accepted", "close"));
            requireThat(session.frames.size(), "unconsumedFinals").isEqualTo(1);
        }
    }

    /**
     * Supplies complete protocol responses as defined by WindowsCargoMessages, recording wrapper-owned decisions.
     */
    private static final class Fixture implements WindowsCargoWrapper.Session
    {
        /** Immutable offered logical request. */
        private final WindowsCargoMessages.Registration registration = new WindowsCargoMessages.Registration(
            new CargoCompilerRequest(UUID.randomUUID(), new CargoCompilerRequest.Wrapper(31, 102, 30, 101),
                List.of(Path.of("rustc").toAbsolutePath().toString(), ""), Path.of("").toAbsolutePath(), Map.of(), null), 11, 12, 12);
        /** Complete controller inputs, independent of wrapper output decisions. */
        private final ArrayDeque<byte[]> frames;
        /** Wrapper decisions, in the order observed at its session boundary. */
        private final List<String> events = new ArrayList<>();
        /** Number of registration publications requested by production code. */
        private int registrations;

        /**
         * Owns complete response fixtures for one test only.
         * @param frames controller input messages
         */
        Fixture(List<byte[]> frames) { this.frames = new ArrayDeque<>(frames); }

        @Override public WindowsCargoMessages.Registration registration() { return registration; }
        @Override public void sendRegistration(byte[] bytes) { registrations++; events.add("registration"); }
        @Override public byte[] awaitFrame() { return frames.remove(); }
        @Override public void accepted() { events.add("accepted"); }
        @Override public void finish() { events.add("finish"); }
        @Override public void close() { events.add("close"); }
    }
}
