package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.testng.SkipException;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises actual local Windows pipe framing and kernel PID observation; no source-creation authority is claimed.
 */
public final class WindowsCargoTransportTest
{
    /**
     * Creates independently owned native channel checks.
     */
    public WindowsCargoTransportTest() {}

    /**
     * A real same-process pipe preserves binary bytes and reports the actual peer PID on both ends.
     * Platform selection occurs before any native acquisition; Windows x64 and ARM64 must run this method.
     * @throws IOException if native connection, frame transfer or cleanup fails
     */
    @Test public void transfersBinaryFramesWithKernelPeerPid() throws IOException
    {
        if (!System.getProperty("os.name", "").startsWith("Windows")) throw new SkipException("Requires native Windows named pipes");
        try (DefaultToolingScope tooling = new DefaultToolingScope(); WindowsCargoScope scope = new WindowsCargoScope(tooling))
        {
            String endpoint = WindowsCargoTransport.endpoint();
            try (WindowsCargoTransport.Listener listener = WindowsCargoTransport.listen(scope, endpoint, Duration.ofSeconds(5));
                WindowsCargoTransport.Connection client = WindowsCargoTransport.connect(scope, endpoint, Duration.ofSeconds(5)))
            {
                WindowsCargoTransport.Connection accepted = listener.poll();
                requireThat(accepted != null, "accepted").isTrue();
                try (WindowsCargoTransport.Connection server = accepted)
                {
                    listener.stopAccepting();
                    requireException(IllegalStateException.class, listener::poll);
                    requireException(IllegalStateException.class, listener::close);
                    requireThat(client.peerPid(), "serverPid").isEqualTo(ProcessHandle.current().pid());
                    requireThat(server.peerPid(), "clientPid").isEqualTo(ProcessHandle.current().pid());
                    byte[] original = new byte[256];
                    for (int index = 0; index < original.length; index++) original[index] = (byte) index;
                    client.send(original);
                    requireThat(java.util.Arrays.equals(server.awaitFrame(), original), "binaryBytesPreserved").isTrue();
                    byte[] result = "response".getBytes(StandardCharsets.UTF_8);
                    server.send(result);
                    requireThat(java.util.Arrays.equals(client.awaitFrame(), result), "responsePreserved").isTrue();
                }
            }
        }
    }
    /**
     * Stops acceptance while accepted endpoints retain bindings, then permits disposal after their closure.
     * @throws IOException if stopping or final disposal fails
     */
    @Test public void stoppedListenerRetainsAcceptedPeersUntilEndpointClosure() throws IOException
    {
        List<String> effects = new ArrayList<>();
        var peers = new AtomicInteger(1);
        var lifetime = new WindowsCargoTransport.ListenerLifetime(() -> effects.add("stop"), peers::get,
            () -> effects.add("dispose"));
        lifetime.stopAccepting();
        requireThat(lifetime.accepting(), "noFurtherAcceptance").isFalse();
        requireThat(effects, "stoppedWithoutDisposingBorrowedBindings").isEqualTo(List.of("stop"));
        requireException(IllegalStateException.class, lifetime::close);
        requireThat(effects, "livePeerPreventsDisposal").isEqualTo(List.of("stop"));
        effects.add("endpoint-close");
        peers.decrementAndGet();
        lifetime.close();
        lifetime.close();
        lifetime.stopAccepting();
        requireThat(effects, "bindingsDisposedAfterLastEndpointExactlyOnce")
            .isEqualTo(List.of("stop", "endpoint-close", "dispose"));
    }

    /**
     * Retains an uncertain stop exactly and never disposes bindings or repeats the native stop operation.
     */
    @Test public void uncertainListenerStopRetainsNativeBindingsWithoutReplay()
    {
        for (Throwable primary : List.of(new IOException("stop"), new IllegalStateException("stop"), new AssertionError("stop")))
        {
            List<String> effects = new ArrayList<>();
            var lifetime = new WindowsCargoTransport.ListenerLifetime(() ->
            {
                effects.add("stop");
                if (primary instanceof IOException checked) throw checked;
                if (primary instanceof RuntimeException unchecked) throw unchecked;
                throw (Error) primary;
            }, () -> 0, () -> effects.add("dispose"));
            requireThat(requireException(primary.getClass(), lifetime::stopAccepting) == primary, "exactStopFailure").isTrue();
            requireThat(requireException(primary.getClass(), lifetime::close) == primary, "retainedStopFailure").isTrue();
            requireThat(lifetime.accepting(), "uncertainStopCannotAccept").isFalse();
            requireThat(effects, "stopNotReplayedAndBindingsRetained").isEqualTo(List.of("stop"));
        }
    }

    /**
     * Attempts final disposal once and reports the same checked, unchecked or fatal failure thereafter.
     */
    @Test public void uncertainListenerDisposalIsNotReplayed()
    {
        for (Throwable primary : List.of(new IOException("dispose"), new IllegalStateException("dispose"), new AssertionError("dispose")))
        {
            List<String> effects = new ArrayList<>();
            var lifetime = new WindowsCargoTransport.ListenerLifetime(() -> effects.add("stop"), () -> 0, () ->
            {
                effects.add("dispose");
                if (primary instanceof IOException checked) throw checked;
                if (primary instanceof RuntimeException unchecked) throw unchecked;
                throw (Error) primary;
            });
            requireThat(requireException(primary.getClass(), lifetime::close) == primary, "exactDisposalFailure").isTrue();
            requireThat(requireException(primary.getClass(), lifetime::close) == primary, "retainedDisposalFailure").isTrue();
            requireThat(effects, "noRepeatedDisposal").isEqualTo(List.of("stop", "dispose"));
        }
    }

}
