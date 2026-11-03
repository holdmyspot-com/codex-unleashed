package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxBazelControl;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks host conversation decisions using local frames representing native owner receipts.
 * Native fork, waitpid, namespace authentication and real Bazel status propagation remain separate gates.
 */
public final class LinuxBazelControlTest
{
    /**
     * Creates isolated conversation checks without shared fixtures.
     */
    public LinuxBazelControlTest() {}

    /**
     * Preserves ordinary exit23 in a sole FINAL command without requesting a retry.
     * @throws IOException if a valid conversation fails
     */
    @Test public void ordinaryErrorFinishesWithoutReset() throws IOException
    {
        var frames = new Frames("PREPARED 1 7 51", "EXIT 1 EXIT 23");
        try (var control = new LinuxBazelControl(frames, "ab".repeat(32)))
        {
            requireThat(control.prepare(), "preparedClaim").isEqualTo(new LinuxBazelControl.Identity(7, 51));
            control.collect();
            requireThat(control.pollResult().orElseThrow().status(), "nativeStatus").isEqualTo(23);
            requireThat(control.finish(() -> true), "finalStatus").isEqualTo(23);
            requireThat(frames.sent, "commands").isEqualTo(List.of("CREATE 1", "FINAL 1 23"));
            requireException(IllegalStateException.class, control::prepare);
        }
    }

    /**
     * Requires collected native status and tree zero before output reset, then allocates a fresh local attempt.
     * @throws IOException if a valid conversation fails
     */
    @Test public void abortedResetRequiresReapingAndZeroBeforeFreshAttempt() throws IOException
    {
        var frames = new Frames("PREPARED 1 7 51", "EXIT 1 SIGNAL 9", "RESET 1", "PREPARED 2 8 52");
        try (var control = new LinuxBazelControl(frames, "ab".repeat(32)))
        {
            control.prepare();
            requireException(IllegalStateException.class, () -> control.resetAborted(() -> true));
            control.collect();
            control.pollResult();
            requireException(IOException.class, () -> control.resetAborted(() -> false));
            requireThat(frames.sent, "beforeZero").isEqualTo(List.of("CREATE 1"));
            control.resetAborted(() -> true);
            requireThat(control.prepare(), "freshIdentity").isEqualTo(new LinuxBazelControl.Identity(8, 52));
            requireThat(frames.sent, "resetBeforeCreate").isEqualTo(List.of("CREATE 1", "RESET 1", "CREATE 2"));
        }
    }

    /**
     * Rejects a stale wait receipt permanently and never finalizes or retries it.
     * @throws IOException if initial preparation fails
     */
    @Test public void staleReceiptPermanentlyWithdrawsControl() throws IOException
    {
        var frames = new Frames("PREPARED 1 7 51", "EXIT 2 EXIT 0");
        try (var control = new LinuxBazelControl(frames, "ab".repeat(32)))
        {
            control.prepare(); control.collect();
            requireException(IOException.class, control::pollResult);
            requireException(IOException.class, () -> control.finish(() -> true));
            requireException(IOException.class, control::prepare);
            requireThat(frames.sent, "commandsAfterFault").isEqualTo(List.of("CREATE 1"));
        }
    }

    /**
     * Rejects an invocation capability mismatch before sending action controls.
     */
    @Test public void capabilityMismatchClosesWithoutActionControl()
    {
        var frames = new Frames();
        requireException(IOException.class, () -> new LinuxBazelControl(frames, "cd".repeat(32)));
        requireThat(frames.sent.isEmpty(), "noCommands").isTrue();
        requireThat(frames.closed, "originalConnectionClosed").isTrue();
    }

    /**
     * Permanently withdraws control after a CREATE send with uncertain effects, rather than resending it.
     * @throws IOException if initial registration fails
     */
    @Test public void uncertainCreateIsNeverResent() throws IOException
    {
        var frames = new Frames();
        frames.failSend = true;
        try (var control = new LinuxBazelControl(frames, "ab".repeat(32)))
        {
            requireException(IOException.class, control::prepare);
            requireException(IOException.class, control::prepare);
            requireThat(frames.sent, "soleUncertainCreate").isEqualTo(List.of("CREATE 1"));
        }
    }

    /**
     * Retains uncertain cleanup instead of reporting a successful second close or repeating native close.
     * @throws IOException if initial registration fails
     */
    @Test public void uncertainCloseRemainsVisibleWithoutReplay() throws IOException
    {
        var frames = new Frames();
        frames.failClose = true;
        var control = new LinuxBazelControl(frames, "ab".repeat(32));
        requireException(IOException.class, control::close);
        requireException(IOException.class, control::close);
        requireThat(frames.closeCalls, "soleNativeClose").isEqualTo(1);
    }

    /**
     * Checks canonical identity and status parsing without accepting aliases or trailing fields.
     * @throws IOException if initial registration fails
     */
    @Test public void rejectsMalformedPreparedIdentities() throws IOException
    {
        for (String receipt : List.of("PREPARED 01 7 51", "PREPARED 1 0 51", "PREPARED 1 7 051",
            "PREPARED 1 7 51 extra", "PREPARED 1 7 51\n"))
        {
            var frames = new Frames(receipt);
            try (var control = new LinuxBazelControl(frames, "ab".repeat(32)))
            {
                requireException(IOException.class, control::prepare);
                requireException(IOException.class, control::prepare);
                requireThat(frames.sent.size(), "singleCreate").isEqualTo(1);
            }
        }
    }

    /**
     * Represents native framed receipts; assertions concern the host's command and rejection decisions.
     * Scripted frames do not establish the actual native owner's behavior.
     */
    private static final class Frames implements LinuxBazelControl.Connection
    {
        /** Native owner receipts available to the single host reader. */
        private final ArrayDeque<byte[]> received = new ArrayDeque<>();
        /** Commands whose publication the host actually attempted. */
        private final List<String> sent = new ArrayList<>();
        /** Indicates whether the next send has unknown external effects. */
        private boolean failSend;
        /** Indicates whether the host retired this original connection. */
        private boolean closed;
        /** Indicates whether modeled native close has uncertain effects. */
        private boolean failClose;
        /** Number of native close operations attempted by production control. */
        private int closeCalls;

        /**
         * Supplies a valid owner receipt followed by controlled native responses.
         * @param receipts native response payloads in receive order
         */
        Frames(String... receipts)
        {
            received.add(("OWNER " + "ab".repeat(32) + " 2 50").getBytes(StandardCharsets.US_ASCII));
            for (String receipt : receipts) received.add(receipt.getBytes(StandardCharsets.US_ASCII));
        }
        @Override public void send(byte[] bytes) throws IOException
        {
            sent.add(new String(bytes, StandardCharsets.US_ASCII));
            if (failSend) throw new IOException("Modeled send returned after uncertain publication");
        }
        @Override public byte[] receive() throws IOException
        {
            if (received.isEmpty()) throw new IOException("No scripted native receipt");
            return received.remove();
        }
        @Override public Optional<byte[]> poll() { return Optional.ofNullable(received.poll()); }
        @Override public void close() throws IOException
        {
            closeCalls++;
            if (failClose) throw new IOException("Modeled uncertain native close");
            closed = true;
        }
    }
}
