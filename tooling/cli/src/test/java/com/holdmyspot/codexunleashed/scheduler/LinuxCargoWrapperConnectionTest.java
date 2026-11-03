package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.testng.SkipException;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Drives the controller end of one wrapper connection against a fake wrapper written in Java.
 * The fake wrapper is this test's own process: it connects to a real listener, forks a real child that blocks on its
 * standard input until released, and speaks protocol version 2. No privilege is required.
 */
public final class LinuxCargoWrapperConnectionTest
{
    /** Bound on each complete frame; every frame in these tests is already buffered when it is read. */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * Creates independently owned connection checks.
     */
    public LinuxCargoWrapperConnectionTest() {}

    /**
     * One attempt runs from registration through release, exit and the final status, and nothing reaches the
     * wrapper before the controller releases its child.
     * @throws IOException          if the socket, the child or a valid protocol step fails
     * @throws InterruptedException if waiting for the child is interrupted
     */
    @Test
    public void runsOneAttempt() throws IOException, InterruptedException
    {
        try (var fixture = Fixture.open())
        {
            var register = fixture.connection.register();
            requireThat(register.arguments(), "arguments").isEqualTo(List.of("/usr/bin/rustc", "--version"));
            requireThat(register.directory(), "directory").isEqualTo(Path.of("/"));
            requireThat(fixture.connection.state(), "registered").isEqualTo(LinuxCargoWrapperConnection.State.AWAITING_CHILD);
            Process child = fixture.wrapper.fork(1);
            fixture.connection.pump();
            requireThat(fixture.connection.state(), "held").isEqualTo(LinuxCargoWrapperConnection.State.HELD);
            requireThat(fixture.connection.child().pid(), "pid").isEqualTo(child.pid());
            requireThat(fixture.wrapper.pending(), "nothingBeforeRelease").isFalse();
            fixture.connection.release();
            requireThat(fixture.wrapper.receive(), "release").isEqualTo(List.of("release", "1"));
            fixture.wrapper.finish(child, 1);
            fixture.connection.pump();
            requireThat(fixture.connection.state(), "exited").isEqualTo(LinuxCargoWrapperConnection.State.EXITED);
            requireThat(fixture.connection.waitStatus(), "waitStatus").isEqualTo(3L << 8);
            fixture.connection.finish(3);
            requireThat(fixture.wrapper.receive(), "final").isEqualTo(List.of("final", "3"));
            requireThat(fixture.wrapper.closed(), "closedAfterFinal").isTrue();
        }
    }

    /**
     * A retry admits the next attempt number only, a starting attempt can wait for its child, and an unreleased child
     * can be aborted.
     * @throws IOException          if the socket, a child or a valid protocol step fails
     * @throws InterruptedException if waiting for a child is interrupted
     */
    @Test
    public void retriesAndAborts() throws IOException, InterruptedException
    {
        try (var fixture = Fixture.open())
        {
            fixture.connection.register();
            Process first = fixture.wrapper.fork(1);
            fixture.connection.pump();
            fixture.connection.release();
            fixture.wrapper.receive();
            fixture.wrapper.finish(first, 1);
            fixture.connection.pump();
            fixture.connection.retry();
            requireThat(fixture.wrapper.receive(), "retry").isEqualTo(List.of("retry"));
            Process second = fixture.wrapper.fork(2);
            requireThat(fixture.connection.awaitChild().attempt(), "attempt").isEqualTo(2L);
            requireThat(fixture.connection.child().pid(), "pid").isEqualTo(second.pid());
            fixture.connection.abort();
            requireThat(fixture.wrapper.receive(), "abort").isEqualTo(List.of("abort", "2"));
            second.destroyForcibly().waitFor();
            fixture.wrapper.send("exited", "2", "9");
            fixture.connection.pump();
            requireThat(fixture.connection.waitStatus(), "killed").isEqualTo(9L);
        }
    }

    /**
     * A child whose start time differs from the kernel's record closes the connection.
     * @throws IOException if the socket or the child fails
     */
    @Test
    public void rejectsStartTimeMismatch() throws IOException
    {
        try (var fixture = Fixture.open())
        {
            fixture.connection.register();
            Process child = fixture.wrapper.start();
            fixture.wrapper.send("child", "1", Long.toString(child.pid()), Long.toString(startTime(child.pid()) + 1));
            requireException(IOException.class, fixture.connection::pump);
            requireThat(fixture.wrapper.closed(), "closed").isTrue();
        }
    }

    /**
     * A process that is not the wrapper's own child closes the connection.
     * @throws IOException if the socket fails
     */
    @Test
    public void rejectsParentMismatch() throws IOException
    {
        try (var fixture = Fixture.open())
        {
            fixture.connection.register();
            long self = ProcessHandle.current().pid();
            fixture.wrapper.send("child", "1", Long.toString(self), Long.toString(startTime(self)));
            requireException(IOException.class, fixture.connection::pump);
            requireThat(fixture.wrapper.closed(), "closed").isTrue();
        }
    }

    /**
     * Commands outside their state are refused locally, and messages outside their state close the connection.
     * @throws IOException if the socket or the child fails
     */
    @Test
    public void rejectsOutOfOrderSteps() throws IOException
    {
        try (var fixture = Fixture.open())
        {
            fixture.connection.register();
            requireException(IllegalStateException.class, fixture.connection::release);
            requireException(IllegalStateException.class, () -> fixture.connection.finish(0));
            requireException(IllegalStateException.class, fixture.connection::retry);
            Process child = fixture.wrapper.start();
            fixture.wrapper.send("child", "2", Long.toString(child.pid()), Long.toString(startTime(child.pid())));
            requireException(IOException.class, fixture.connection::pump);
            requireThat(fixture.wrapper.closed(), "closed").isTrue();
            requireException(IOException.class, fixture.connection::pump);
        }
        try (var fixture = Fixture.open())
        {
            fixture.connection.register();
            fixture.wrapper.fork(1);
            fixture.connection.pump();
            fixture.wrapper.send("exited", "1", "0");
            requireException(IOException.class, fixture.connection::pump);
        }
    }

    /**
     * A first frame other than a registration, and a wrapper that disconnects, both fail the connection.
     * @throws IOException if the socket fails
     */
    @Test
    public void failsOnLostOrInvalidWrapper() throws IOException
    {
        try (var fixture = Fixture.open("child", "1", "1", "1"))
        {
            requireException(IOException.class, fixture.connection::register);
            requireThat(fixture.wrapper.closed(), "closed").isTrue();
        }
        try (var fixture = Fixture.open())
        {
            fixture.connection.register();
            fixture.wrapper.channel.close();
            requireException(IOException.class, fixture.connection::pump);
        }
    }

    /**
     * Reads field 22 of a process's stat record.
     * @param pid live process
     * @return kernel start time in clock ticks
     * @throws IOException if the record cannot be read
     */
    private static long startTime(long pid) throws IOException
    {
        return AllocationHooks.startTime(Files.readString(Path.of("/proc", Long.toString(pid), "stat")));
    }

    /**
     * Owns a listener, the controller end of one accepted connection and the fake wrapper end.
     * @param directory  test-owned socket directory
     * @param scope      native bindings borrowed by the transport
     * @param listener   real controller listener
     * @param connection controller end under test
     * @param wrapper    fake wrapper end
     */
    private record Fixture(Path directory, DefaultToolingScope scope, LinuxCargoTransport.Listener listener,
        LinuxCargoWrapperConnection connection, FakeWrapper wrapper) implements AutoCloseable
    {
        /**
         * Connects a fake wrapper that has already sent its registration.
         * @return open fixture
         * @throws IOException if the platform lacks Linux process records or a socket step fails
         */
        static Fixture open() throws IOException
        {
            return open("register", "2", "/", "2", "/usr/bin/rustc", "--version", "1", "PATH=/usr/bin");
        }

        /**
         * Connects a fake wrapper that has already sent the given first message.
         * @param first fields of the first message
         * @return open fixture
         * @throws IOException if the platform lacks Linux process records or a socket step fails
         */
        static Fixture open(String... first) throws IOException
        {
            if (!Files.isDirectory(Path.of("/proc/self")))
                throw new SkipException("Requires Linux process records");
            Path directory = Files.createTempDirectory("lcw-");
            Path endpoint = directory.resolve("c");
            if (endpoint.toString().length() >= 100) throw new SkipException("Temporary directory is too long for a socket");
            var scope = new DefaultToolingScope();
            var listener = LinuxCargoTransport.listen(scope, endpoint, 1, TIMEOUT);
            var wrapper = new FakeWrapper(SocketChannel.open(StandardProtocolFamily.UNIX));
            wrapper.channel.connect(UnixDomainSocketAddress.of(endpoint));
            wrapper.send(first);
            var accepted = listener.poll().orElseThrow(() -> new IOException("Connection was not accepted"));
            return new Fixture(directory, scope, listener, new LinuxCargoWrapperConnection(accepted), wrapper);
        }

        @Override public void close() throws IOException
        {
            try
            {
                wrapper.close();
                connection.close();
                listener.close();
            }
            finally
            {
                scope.close();
                Files.deleteIfExists(directory.resolve("c"));
                Files.delete(directory);
            }
        }
    }

    /**
     * Speaks the wrapper side of protocol version 2 and owns the children it forks.
     */
    private static final class FakeWrapper implements AutoCloseable
    {
        /** Connected wrapper end. */
        private final SocketChannel channel;
        /** Every child started, destroyed on close. */
        private final List<Process> children = new ArrayList<>();

        /**
         * Retains a connected channel.
         * @param channel connected wrapper end
         */
        FakeWrapper(SocketChannel channel)
        {
            this.channel = channel;
        }

        /**
         * Starts a child that blocks until a line arrives on its standard input, then exits with status 3.
         * @return the blocked child
         * @throws IOException if the child cannot start
         */
        Process start() throws IOException
        {
            Process child = new ProcessBuilder("/bin/sh", "-c", "read x; exit 3").start();
            children.add(child);
            return child;
        }

        /**
         * Starts a blocked child and reports it as the given attempt.
         * @param attempt attempt number
         * @return the blocked child
         * @throws IOException if the child or the message fails
         */
        Process fork(long attempt) throws IOException
        {
            Process child = start();
            send("child", Long.toString(attempt), Long.toString(child.pid()), Long.toString(startTime(child.pid())));
            return child;
        }

        /**
         * Releases a child, reaps it and reports its raw wait status.
         * @param child   blocked child
         * @param attempt attempt number
         * @throws IOException          if the child or the message fails
         * @throws InterruptedException if waiting is interrupted
         */
        void finish(Process child, long attempt) throws IOException, InterruptedException
        {
            child.getOutputStream().write('\n');
            child.getOutputStream().close();
            int status = child.waitFor();
            send("exited", Long.toString(attempt), Integer.toString(status << 8));
        }

        /**
         * Sends one framed message.
         * @param fields complete field sequence
         * @throws IOException if writing fails
         */
        void send(String... fields) throws IOException
        {
            var payload = new ByteArrayOutputStream();
            for (String field : fields)
            {
                payload.writeBytes(field.getBytes(StandardCharsets.UTF_8));
                payload.write(0);
            }
            ByteBuffer frame = ByteBuffer.allocate(4 + payload.size()).putInt(payload.size()).put(payload.toByteArray());
            frame.flip();
            while (frame.hasRemaining()) channel.write(frame);
        }

        /**
         * Reads one framed message that the controller has already sent.
         * @return its fields
         * @throws IOException if the connection ends or the frame is malformed
         */
        List<String> receive() throws IOException
        {
            ByteBuffer header = read(4);
            ByteBuffer payload = read(header.getInt());
            String text = new String(payload.array(), StandardCharsets.UTF_8);
            if (!text.endsWith("\0")) throw new IOException("Field terminator is missing");
            return List.of(text.substring(0, text.length() - 1).split("\0", -1));
        }

        /**
         * Reports whether any byte from the controller is waiting, without blocking.
         * @return whether a byte is readable
         * @throws IOException if the connection fails
         */
        boolean pending() throws IOException
        {
            channel.configureBlocking(false);
            try
            {
                return channel.read(ByteBuffer.allocate(1)) != 0;
            }
            finally
            {
                channel.configureBlocking(true);
            }
        }

        /**
         * Reports whether the controller has closed its end.
         * @return whether the next read reaches end of file
         * @throws IOException if the connection fails other than by closing
         */
        boolean closed() throws IOException
        {
            return channel.read(ByteBuffer.allocate(1)) < 0;
        }

        /**
         * Reads exactly the requested number of bytes.
         * @param length byte count
         * @return filled buffer, positioned at its start
         * @throws IOException if the connection ends first
         */
        private ByteBuffer read(int length) throws IOException
        {
            ByteBuffer buffer = ByteBuffer.allocate(length);
            while (buffer.hasRemaining())
                if (channel.read(buffer) < 0) throw new IOException("Controller closed the connection");
            return buffer.flip();
        }

        @Override public void close() throws IOException
        {
            for (Process child : children) child.destroyForcibly();
            channel.close();
        }
    }
}
