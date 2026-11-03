package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxBorrowedStreams;
import com.holdmyspot.codexunleashed.scheduler.LinuxCargoTransport;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises real native frame waits independently of compiler lifetime and request authority.
 */
public final class LinuxCargoTransportWaitProbe
{
    /** Bounds transfer after readiness; idle peer waits deliberately exceed this duration. */
    private static final Duration FRAME_TIMEOUT = Duration.ofMillis(100);

    /**
     * Prevents construction of this diagnostic command.
     */
    private LinuxCargoTransportWaitProbe() {}

    /**
     * Checks either the reported counterexample or all supported wait outcomes.
     * @param args optional red mode limits execution to the delayed response
     * @throws Exception if native behavior or owned cleanup differs
     */
    public static void main(String[] args) throws Exception
    {
        String[] scenarios = args.length == 0 ?
            new String[] {"delayed", "partial-header", "partial-payload", "eof", "interrupt", "mid-idle-interrupt", "bounded"} :
            new String[] {"delayed"};
        for (String scenario : scenarios) verify(scenario);
        System.out.println("IDLE_FRAME_CASES=" + scenarios.length + " passed");
    }

    /**
     * Checks one connection with an independently controlled raw Unix peer and joins its owned thread.
     * @param scenario selected response, failure or cancellation
     * @throws Exception if transfer, failure retention or cleanup differs
     */
    private static void verify(String scenario) throws Exception
    {
        Path endpoint = Path.of("/proc/self/cwd/idle.sock");
        var start = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        Thread reader = Thread.currentThread();
        var interruptedAt = new AtomicLong();
        Thread peerThread = null;
        try (var scope = new DefaultToolingScope();
            var listener = LinuxCargoTransport.listen(scope, endpoint, 1, FRAME_TIMEOUT);
            var peer = SocketChannel.open(StandardProtocolFamily.UNIX))
        {
            var task = new FutureTask<Void>(() ->
            {
                peer.connect(UnixDomainSocketAddress.of(endpoint));
                requireThat(start.await(10, TimeUnit.SECONDS), "parentBeginsRead").isTrue();
                switch (scenario)
                {
                    case "delayed" ->
                    {
                        Thread.sleep(500);
                        write(peer, ByteBuffer.allocate(5).putInt(1).put((byte) 81).array());
                    }
                    case "partial-header" -> write(peer, new byte[] {0});
                    case "partial-payload" -> write(peer, ByteBuffer.allocate(5).putInt(2).put((byte) 81).array());
                    case "eof" -> peer.shutdownOutput();
                    case "mid-idle-interrupt" ->
                    {
                        long observation = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                        while (Arrays.stream(reader.getStackTrace()).noneMatch(frame ->
                            frame.getClassName().equals(LinuxBorrowedStreams.NativeBindings.Socket.class.getName()) &&
                            frame.getMethodName().equals("awaitReadable")))
                        {
                            requireThat(System.nanoTime() < observation, "readerEntersIdleWait").isTrue();
                            Thread.sleep(1);
                        }
                        interruptedAt.set(System.nanoTime());
                        reader.interrupt();
                    }
                    case "interrupt", "bounded" -> { }
                    default -> throw new IllegalArgumentException(scenario);
                }
                requireThat(finish.await(10, TimeUnit.SECONDS), "parentFinishesRead").isTrue();
                return null;
            });
            peerThread = Thread.ofPlatform().name("owned-idle-frame-peer").start(task);
            LinuxCargoTransport.Connection accepted = null;
            long acceptance = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (accepted == null)
            {
                accepted = listener.poll().orElse(null);
                if (accepted == null)
                {
                    requireThat(System.nanoTime() < acceptance, "peerAccepted").isTrue();
                    if (task.isDone()) task.get();
                    Thread.sleep(1);
                }
            }
            try (var connection = accepted)
            {
                start.countDown();
                if (scenario.equals("interrupt")) Thread.currentThread().interrupt();
                IOException failure = null;
                try
                {
                    byte[] received = scenario.equals("bounded") ? connection.read() : connection.awaitFrame();
                    requireThat(scenario, "successfulScenario").isEqualTo("delayed");
                    requireThat(Arrays.equals(received, new byte[] {81}), "exactDelayedResponse").isTrue();
                }
                catch (IOException actual) { failure = actual; }
                finally
                {
                    if (scenario.endsWith("interrupt"))
                        requireThat(Thread.interrupted(), "interruptRemainsVisible").isTrue();
                }
                if (scenario.equals("delayed"))
                {
                    if (failure != null) throw new IOException("Idle response exceeded only the frame timeout", failure);
                }
                else
                {
                    requireThat(failure != null, "incompleteOrCancelledFrameRejected").isTrue();
                    if (scenario.endsWith("interrupt"))
                        requireThat(failure instanceof InterruptedIOException, "typedInterruption").isTrue();
                    if (scenario.equals("mid-idle-interrupt"))
                        requireThat(System.nanoTime() - interruptedAt.get() < Duration.ofSeconds(2).toNanos(),
                            "idleInterruptionReturnsWithinBound").isTrue();
                    IOException repeated = null;
                    try { connection.awaitFrame(); }
                    catch (IOException actual) { repeated = actual; }
                    requireThat(repeated != null && repeated.getCause() == failure, "failedReadNeverReplayed").isTrue();
                    IOException sent = null;
                    try { connection.send(new byte[] {81}); }
                    catch (IOException actual) { sent = actual; }
                    requireThat(sent != null && sent.getCause() == failure, "failedConnectionNeverResent").isTrue();
                }
            }
            finally { finish.countDown(); }
            task.get(10, TimeUnit.SECONDS);
            peerThread.join();
        }
        finally
        {
            start.countDown();
            finish.countDown();
            if (peerThread != null) peerThread.join();
        }
        requireThat(Files.exists(endpoint), "ownedEndpointRemoved").isFalse();
        System.out.println("CASE=" + scenario + " passed");
    }

    /**
     * Sends every byte of a raw test response without imposing production framing behavior.
     * @param peer independently owned raw Unix socket
     * @param bytes exact response bytes
     * @throws IOException if the test peer cannot publish its response
     */
    private static void write(SocketChannel peer, byte[] bytes) throws IOException
    {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) peer.write(buffer);
    }
}
