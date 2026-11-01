package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks wrapper protocol decisions using complete codec messages and an owned in-memory session.
 * Native controller credentials and descriptor transfer are verified at the native integration boundary.
 */
public final class LinuxCargoWrapperTest
{
    /**
     * Creates tests whose mutable fixtures belong to each test invocation.
     */
    public LinuxCargoWrapperTest() {}

    /**
     * One execution registers once, forwards consecutive attempts and returns the final compiler status.
     * @throws IOException if valid messages or the wrapper fail
     */
    @Test
    public void registersOnceAndRetainsSessionThroughTwoAttempts() throws IOException
    {
        try (var tooling = new DefaultToolingScope(); var scope = new LinuxCargoScope(tooling))
        {
            var codec = new LinuxCargoMessages(scope);
            var original = binding(1, "value");
            var first = authorized(original, 1, (byte) 17);
            var second = authorized(original, 2, (byte) 42);
            var session = new ScriptedSession(original, List.of(codec.attempt(first),
                codec.attempt(second), codec.finalResult(7)));
            var wrapper = new LinuxCargoWrapper(scope, original.approvedLaunch(), () -> session);

            requireThat(wrapper.run(), "compilerStatus").isEqualTo(7);
            requireThat(session.events, "protocolOrder").isEqualTo(List.of("register", "read", "transfer:1",
                "read", "transfer:2", "read", "close"));
            requireThat(properties(codec.readRegistration(session.registrationMessage)), "registration").
                isEqualTo(properties(original));
            requireThat(session.transferred.stream().map(LinuxCargoWrapperTest::properties).toList(), "forwardedReceipts").
                isEqualTo(List.of(properties(first), properties(second)));
        }
    }

    /**
     * An attempt with a changed compiler environment is rejected before descriptor transfer.
     * @throws IOException if valid fixture encoding fails
     */
    @Test
    public void rejectsChangedLaunchBeforeTransfer() throws IOException
    {
        try (var tooling = new DefaultToolingScope(); var scope = new LinuxCargoScope(tooling))
        {
            var codec = new LinuxCargoMessages(scope);
            var original = binding(1, "value");
            var session = new ScriptedSession(original, List.of(codec.attempt(binding(1, "changed")), codec.finalResult(0)));
            var wrapper = new LinuxCargoWrapper(scope, original.approvedLaunch(), () -> session);

            requireException(IOException.class, wrapper::run);
            requireThat(session.events, "protocolOrder").isEqualTo(List.of("register", "read", "close"));
        }
    }

    /**
     * Invalid complete responses terminate execution instead of consuming a later valid response.
     * @throws IOException if valid fixture encoding fails
     */
    @Test
    public void rejectsInvalidResponseWithoutReplay() throws IOException
    {
        for (String invalid : List.of("{}", "{\"kind\":\"other\",\"value\":0}",
            "{\"kind\":\"final\",\"value\":256}", "{\"kind\":\"final\",\"value\":0} {}"))
        {
            try (var tooling = new DefaultToolingScope(); var scope = new LinuxCargoScope(tooling))
            {
                var codec = new LinuxCargoMessages(scope);
                var original = binding(1, "value");
                var session = new ScriptedSession(original,
                    List.of(invalid.getBytes(StandardCharsets.UTF_8), codec.finalResult(0)));
                var wrapper = new LinuxCargoWrapper(scope, original.approvedLaunch(), () -> session);

                requireException(IOException.class, wrapper::run);
                requireThat(session.events, "protocolOrder").isEqualTo(List.of("register", "read", "close"));
                requireException(IllegalStateException.class, wrapper::run);
                requireThat(session.events, "afterRepeatedExecution").isEqualTo(List.of("register", "read", "close"));
            }
        }
    }

    /**
     * An uncertain transfer failure prevents all later controller operations and preserves the primary failure.
     * @throws IOException if valid fixture encoding fails
     */
    @Test
    public void stopsAfterTransferFailureAndSuppressesCleanupFailure() throws IOException
    {
        try (var tooling = new DefaultToolingScope(); var scope = new LinuxCargoScope(tooling))
        {
            var codec = new LinuxCargoMessages(scope);
            var original = binding(1, "value");
            var session = new ScriptedSession(original, List.of(codec.attempt(original), codec.finalResult(0)));
            session.transferFailure = new IOException("Uncertain native descriptor transfer");
            session.closeFailure = new IOException("Owned endpoint cleanup failed");
            var wrapper = new LinuxCargoWrapper(scope, original.approvedLaunch(), () -> session);

            var failure = requireException(IOException.class, wrapper::run);
            requireThat(failure, "primaryFailure").isEqualTo(session.transferFailure);
            requireThat(List.of(failure.getSuppressed()), "suppressedFailures").isEqualTo(List.of(session.closeFailure));
            requireThat(session.events, "protocolOrder").isEqualTo(List.of("register", "read", "transfer:1", "close"));
            requireException(IllegalStateException.class, wrapper::run);
            requireThat(session.events, "afterRepeatedExecution").isEqualTo(List.of("register", "read", "transfer:1", "close"));
        }
    }

    /**
     * Failed registration publication closes the session without reading or sending another message.
     */
    @Test
    public void stopsAfterRegistrationFailure()
    {
        try (var tooling = new DefaultToolingScope(); var scope = new LinuxCargoScope(tooling))
        {
            var original = binding(1, "value");
            var session = new ScriptedSession(original, List.of());
            session.registrationFailure = new IOException("Uncertain registration publication");
            var wrapper = new LinuxCargoWrapper(scope, original.approvedLaunch(), () -> session);

            requireThat(requireException(IOException.class, wrapper::run), "primaryFailure").isEqualTo(session.registrationFailure);
            requireThat(session.events, "protocolOrder").isEqualTo(List.of("register", "close"));
        }
    }

    /**
     * A failed response read closes the session and prevents another read or acquisition.
     */
    @Test
    public void stopsAfterReadFailure()
    {
        try (var tooling = new DefaultToolingScope(); var scope = new LinuxCargoScope(tooling))
        {
            var original = binding(1, "value");
            var session = new ScriptedSession(original, List.of());
            var acquisitions = new ArrayList<String>();
            var wrapper = new LinuxCargoWrapper(scope, original.approvedLaunch(), () ->
            {
                acquisitions.add("open");
                return session;
            });

            requireException(IOException.class, wrapper::run);
            requireException(IllegalStateException.class, wrapper::run);
            requireThat(session.events, "protocolOrder").isEqualTo(List.of("register", "read", "close"));
            requireThat(acquisitions, "acquisitions").isEqualTo(List.of("open"));
        }
    }

    /**
     * Failed session acquisition consumes execution and leaves partial cleanup with the opener.
     */
    @Test
    public void doesNotRetryFailedAcquisition()
    {
        try (var tooling = new DefaultToolingScope(); var scope = new LinuxCargoScope(tooling))
        {
            var acquisitions = new ArrayList<String>();
            var original = binding(1, "value");
            var failure = new IOException("Session acquisition failed before publication");
            var wrapper = new LinuxCargoWrapper(scope, original.approvedLaunch(), () ->
            {
                acquisitions.add("open");
                throw failure;
            });

            requireThat(requireException(IOException.class, wrapper::run), "primaryFailure").isEqualTo(failure);
            requireException(IllegalStateException.class, wrapper::run);
            requireThat(acquisitions, "acquisitions").isEqualTo(List.of("open"));
        }
    }

    /**
     * Failed cleanup after a valid final result is reported instead of publishing apparent success.
     * @throws IOException if valid fixture encoding fails
     */
    @Test
    public void reportsCleanupFailureAfterFinalResult() throws IOException
    {
        try (var tooling = new DefaultToolingScope(); var scope = new LinuxCargoScope(tooling))
        {
            var codec = new LinuxCargoMessages(scope);
            var original = binding(1, "value");
            var session = new ScriptedSession(original, List.of(codec.finalResult(0)));
            session.closeFailure = new IOException("Owned endpoint cleanup failed");
            var wrapper = new LinuxCargoWrapper(scope, original.approvedLaunch(), () -> session);

            requireThat(requireException(IOException.class, wrapper::run), "cleanupFailure").isEqualTo(session.closeFailure);
            requireThat(session.events, "protocolOrder").isEqualTo(List.of("register", "read", "close"));
        }
    }

    /**
     * Creates an untrusted receipt without accessing its represented process or paths.
     * @param sequence controller-selected attempt sequence represented in the response
     * @param value represented compiler environment value used to distinguish approved launches
     * @return complete receipt with one immutable compiler launch
     */
    private static LinuxBorrowedStreams.Binding binding(long sequence, String value)
    {
        Path directory = Path.of("fixture").toAbsolutePath();
        var launch = new LinuxProcessTree.Launch(directory, directory, directory, directory,
            List.of(directory.resolve("rustc").toString(), "", "a b"), Map.of("VALUE", value), directory, 1000, 1000,
            LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC);
        var stream = new LinuxBorrowedStreams.Terminal(false, "", 0, 0, List.of());
        return new LinuxBorrowedStreams.Binding(new UUID(1, 2), sequence,
            new LinuxBorrowedStreams.Peer(71, 81, 1000, 1000), directory.resolve("streams.sock"), new byte[32],
            new LinuxBorrowedStreams.TerminalContext(1, 1, List.of(stream, stream, stream)), launch);
    }

    /**
     * Represents a controller-selected receipt whose nonzero capability must survive owner forwarding.
     * @param original immutable registered wrapper and compiler context
     * @param sequence controller-selected attempt ordinal
     * @param capabilityValue distinct first capability byte for this attempt
     * @return receipt representing the controller response without establishing native authority
     * @throws IOException if the represented valid receipt cannot be decoded
     */
    private static LinuxBorrowedStreams.Binding authorized(LinuxBorrowedStreams.Binding original,
        long sequence, byte capabilityValue) throws IOException
    {
        byte[] capability = new byte[32];
        capability[0] = capabilityValue;
        var values = properties(original);
        values.setProperty("borrowed.sequence", Long.toString(sequence));
        values.setProperty("borrowed.capability", Base64.getEncoder().encodeToString(capability));
        return LinuxBorrowedStreams.readFrom(values);
    }

    /**
     * Copies all represented receipt values into the existing canonical properties for value assertions.
     * @param binding receipt forwarded or encoded by the wrapper
     * @return complete comparable values including capability bytes and terminal context
     */
    private static Properties properties(LinuxBorrowedStreams.Binding binding)
    {
        var result = new Properties();
        LinuxBorrowedStreams.writeTo(result, binding);
        return result;
    }

    /**
     * Supplies complete frames from the LinuxCargoMessages contract and records wrapper requests.
     * This fixture represents an already authenticated native session; it supplies no native authority proof.
     */
    private static final class ScriptedSession implements LinuxCargoWrapper.Session
    {
        /** Original endpoint receipt supplied to the wrapper for registration encoding. */
        private final LinuxBorrowedStreams.Binding original;
        /** Remaining complete controller response frames in receive order. */
        private final ArrayDeque<byte[]> responses;
        /** Wrapper requests and resource closure in observed order. */
        private final List<String> events = new ArrayList<>();
        /** Complete descriptor transfer requests forwarded by the production protocol owner. */
        private final List<LinuxBorrowedStreams.Binding> transferred = new ArrayList<>();
        /** Registration bytes produced by the wrapper, retained for decoding assertions. */
        private byte[] registrationMessage;
        /** Optional native publication failure supplied instead of successful publication. */
        private IOException registrationFailure;
        /** Optional native descriptor transfer failure supplied after recording its request. */
        private IOException transferFailure;
        /** Optional native cleanup failure supplied after recording closure. */
        private IOException closeFailure;

        /**
         * Creates one test-owned session without sockets or native handles.
         * @param original registration receipt returned to the wrapper
         * @param responses complete codec-generated controller messages in delivery order
         */
        private ScriptedSession(LinuxBorrowedStreams.Binding original, List<byte[]> responses)
        {
            this.original = original;
            this.responses = new ArrayDeque<>(responses);
        }

        @Override public LinuxBorrowedStreams.Binding registration() { return original; }

        @Override public void sendRegistration(byte[] bytes) throws IOException
        {
            events.add("register");
            registrationMessage = bytes.clone();
            if (registrationFailure != null) throw registrationFailure;
        }

        @Override public byte[] awaitFrame() throws IOException
        {
            events.add("read");
            if (responses.isEmpty()) throw new IOException("No scripted controller response remains");
            return responses.removeFirst();
        }

        @Override public void transfer(LinuxBorrowedStreams.Binding binding) throws IOException
        {
            events.add("transfer:" + binding.attemptSequence());
            transferred.add(binding);
            if (transferFailure != null) throw transferFailure;
        }

        @Override public void close() throws IOException
        {
            events.add("close");
            if (closeFailure != null) throw closeFailure;
        }
    }
}
