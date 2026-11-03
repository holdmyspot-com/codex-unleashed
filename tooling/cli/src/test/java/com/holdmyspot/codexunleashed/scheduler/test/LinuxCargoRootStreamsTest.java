package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxBorrowedStreams;
import com.holdmyspot.codexunleashed.scheduler.LinuxCargoRootStreams;
import com.holdmyspot.codexunleashed.scheduler.LinuxProcessTree;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks transfer ownership and error ordering without invoking native descriptor transport.
 */
public final class LinuxCargoRootStreamsTest
{
    /**
     * Creates independent method-owned transfer checks.
     */
    public LinuxCargoRootStreamsTest() {}

    /**
     * Joins the sending actor before closing the listener and consumes listener cleanup once.
     * The transfer callback represents the documented bounded native send; its completion is the join boundary.
     * @throws IOException if owner lifecycle unexpectedly fails
     */
    @Test public void closesEndpointAfterSenderTermination() throws IOException
    {
        List<String> events = new ArrayList<>();
        LinuxBorrowedStreams.Binding binding = binding();
        LinuxCargoRootStreams owner = new LinuxCargoRootStreams(binding, () -> events.add("sent"),
            () -> events.add("closed"), Duration.ofSeconds(2));
        try
        {
            owner.await();
            requireThat(owner.binding(), "binding").isEqualTo(binding);
            owner.close();
            owner.close();
            requireThat(events, "lifecycle").isEqualTo(List.of("sent", "closed"));
        }
        finally { owner.close(); }
    }

    /**
     * Preserves the transfer error while still allowing the endpoint owner to finish cleanup.
     * @throws IOException if endpoint cleanup fails
     */
    @Test public void preservesTransferFailure() throws IOException
    {
        IOException original = new IOException("native transfer failed");
        List<String> events = new ArrayList<>();
        try (LinuxCargoRootStreams owner = new LinuxCargoRootStreams(binding(), () -> { throw original; },
            () -> events.add("closed"), Duration.ofSeconds(2)))
        {
            IOException observed = requireException(IOException.class, owner::await);
            requireThat(observed, "transferFailure").isEqualTo(original);
        }
        requireThat(events, "endpointCleanup").isEqualTo(List.of("closed"));
    }

    /**
     * Cancels the owned sender and joins it before listener cleanup.
     * The test's blocked callback models a native send whose bounded wait is interruptible.
     * @throws Exception if synchronization or cleanup fails
     */
    @Test public void cancellationJoinsBeforeEndpointClose() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        List<String> events = new ArrayList<>();
        LinuxCargoRootStreams owner = new LinuxCargoRootStreams(binding(), () ->
        {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException interrupted) { events.add("sender-terminal"); }
        }, () -> events.add("endpoint-closed"), Duration.ofSeconds(2));
        try
        {
            requireThat(started.await(2, TimeUnit.SECONDS), "senderStarted").isTrue();
            owner.close();
            requireThat(events, "cancellationOrder").isEqualTo(List.of("sender-terminal", "endpoint-closed"));
        }
        finally { owner.close(); }
    }

    /**
     * Never replays an uncertain native endpoint close.
     * @throws IOException if owner termination fails
     */
    @Test public void retainsUncertainCloseWithoutReplaying() throws IOException
    {
        IOException original = new IOException("close result unknown");
        List<String> events = new ArrayList<>();
        LinuxCargoRootStreams owner = new LinuxCargoRootStreams(binding(), () -> {}, () ->
        {
            events.add("close-attempt");
            throw original;
        }, Duration.ofSeconds(2));
        owner.await();
        requireThat(requireException(IOException.class, owner::close), "firstClose").isEqualTo(original);
        requireThat(requireException(IOException.class, owner::close), "retainedClose").isEqualTo(original);
        requireThat(events, "closeAttempts").isEqualTo(List.of("close-attempt"));
    }

    /**
     * Creates a modeled immutable receipt; no native peer or descriptor is accessed by lifecycle checks.
     * @return final environment containing an opaque FIFO authorization
     */
    private static LinuxBorrowedStreams.Binding binding()
    {
        Path root = Path.of("root-stream-model").toAbsolutePath().normalize();
        LinuxProcessTree.Launch launch = new LinuxProcessTree.Launch(root.resolve("groups"), root.resolve("scratch"),
            root.resolve("java"), root.resolve("modules"), List.of(root.resolve("cargo").toString()),
            Map.of("CARGO_MAKEFLAGS", "--jobserver-auth=fifo:opaque"), root, 1000, 1000,
            LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC);
        LinuxBorrowedStreams.Terminal pipe = new LinuxBorrowedStreams.Terminal(false, "", 0, 0, List.of());
        return new LinuxBorrowedStreams.Binding(UUID.randomUUID(), 1, new LinuxBorrowedStreams.Peer(10, 20, 0, 0),
            root.resolve("streams.sock"), new byte[32], new LinuxBorrowedStreams.TerminalContext(10, 10,
                List.of(pipe, pipe, pipe)), launch);
    }
}
