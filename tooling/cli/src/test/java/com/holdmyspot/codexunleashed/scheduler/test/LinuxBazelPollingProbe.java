package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxCargoTransport;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises native polling and complete-frame failure semantics using independently owned Unix socket endpoints.
 * Run each scenario in its own process working directory to keep pathname aliases and native ownership isolated.
 */
public final class LinuxBazelPollingProbe
{
    /**
     * Prevents constructing the native socket verification entry point.
     */
    private LinuxBazelPollingProbe() {}

    /**
     * Runs one native idle, complete frame, partial frame or disconnect boundary.
     * The operation requires Linux/glibc and enabled Java native access; it compiles no native source.
     * @param args exactly one scenario: idle, frame, partial or eof
     * @throws Exception if the selected native behavior or cleanup differs
     */
    public static void main(String[] args) throws Exception
    {
        if (args.length != 1 || !System.getProperty("os.name").equals("Linux"))
            throw new IllegalArgumentException("Linux polling probe requires one explicit scenario");
        Path endpoint = Path.of("/proc/self/cwd/owned-poll.sock");
        try (var scope = new DefaultToolingScope();
            var listener = LinuxCargoTransport.listen(scope, endpoint, 1, Duration.ofMillis(100));
            var peer = SocketChannel.open(StandardProtocolFamily.UNIX))
        {
            peer.connect(UnixDomainSocketAddress.of(endpoint));
            try (var connection = listener.poll().orElseThrow(() -> new IOException("Connected peer was not accepted")))
            {
                requireThat(connection.pollFrame().isEmpty(), "idleHasNoFrame").isTrue();
                switch (args[0])
                {
                    case "idle" ->
                    {
                        for (int iteration = 0; iteration < 100; iteration++)
                            requireThat(connection.pollFrame().isEmpty(), "idlePoll").isTrue();
                    }
                    case "frame" ->
                    {
                        write(peer, ByteBuffer.allocate(7).putInt(3).put(new byte[] {65, 0, 66}).array());
                        requireThat(Arrays.equals(connection.pollFrame().orElseThrow(), new byte[] {65, 0, 66}),
                            "opaqueReadyPayload").isTrue();
                        requireThat(connection.pollFrame().isEmpty(), "frameConsumedOnce").isTrue();
                    }
                    case "partial" ->
                    {
                        write(peer, new byte[] {0});
                        IOException original = requireException(IOException.class, connection::pollFrame);
                        IOException later = requireException(IOException.class, connection::pollFrame);
                        requireThat(later.getCause() == original, "partialFrameFaultRetained").isTrue();
                        requireException(IOException.class, () -> connection.send(new byte[] {1}));
                    }
                    case "eof" ->
                    {
                        peer.shutdownOutput();
                        IOException original = requireException(IOException.class, connection::pollFrame);
                        IOException later = requireException(IOException.class, connection::pollFrame);
                        requireThat(later.getCause() == original, "disconnectFaultRetained").isTrue();
                    }
                    default -> throw new IllegalArgumentException("Unsupported polling scenario: " + args[0]);
                }
            }
        }
        System.out.println("PASS native-poll " + args[0]);
    }

    /**
     * Publishes the controlled raw peer bytes without using the production frame writer.
     * @param peer independently owned native test peer
     * @param bytes complete raw header and payload, or deliberate partial header
     * @throws IOException if the peer cannot publish its controlled input
     */
    private static void write(SocketChannel peer, byte[] bytes) throws IOException
    {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) peer.write(buffer);
    }
}
