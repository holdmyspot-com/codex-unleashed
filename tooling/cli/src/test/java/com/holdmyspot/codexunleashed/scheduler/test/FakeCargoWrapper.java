package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.AllocationHooks;
import com.holdmyspot.codexunleashed.scheduler.LinuxCargoTransport;
import com.holdmyspot.codexunleashed.scheduler.LinuxCargoWrapperConnection;
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

/**
 * Stands in for the native Linux Cargo wrapper on the far end of a real {@link LinuxCargoWrapperConnection}.
 * <p>
 * The fake wrapper is the test's own process. It connects to a real controller listener and speaks protocol version
 * 2 as the header of {@code linux-cargo-wrapper.c} specifies, and it forks real children that block on their standard
 * input the way the native wrapper's child blocks on its release pipe. Because the test process is the children's
 * parent, the controller's peer and parent checks see the same relationship as with the native wrapper. No privilege
 * is required.
 */
public final class FakeCargoWrapper implements AutoCloseable
{
	/** Bound on each complete frame; every frame in these tests is already buffered when it is read. */
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	/** Test-owned socket directory. */
	private final Path directory;
	/** Native bindings borrowed by the transport. */
	private final DefaultToolingScope scope;
	/** Real controller listener. */
	private final LinuxCargoTransport.Listener listener;
	/** Connected wrapper end. */
	private final SocketChannel channel;
	/** Controller end under test. */
	private final LinuxCargoWrapperConnection connection;
	/** Every child started, destroyed on close. */
	private final List<Process> children = new ArrayList<>();

	/**
	 * Retains the owned fixture parts.
	 *
	 * @param directory  test-owned socket directory
	 * @param scope      native bindings borrowed by the transport
	 * @param listener   real controller listener
	 * @param channel    connected wrapper end
	 * @param connection controller end of the accepted connection
	 */
	private FakeCargoWrapper(Path directory, DefaultToolingScope scope, LinuxCargoTransport.Listener listener,
		SocketChannel channel, LinuxCargoWrapperConnection connection)
	{
		this.directory = directory;
		this.scope = scope;
		this.listener = listener;
		this.channel = channel;
		this.connection = connection;
	}

	/**
	 * Connects a fake wrapper that has already sent the registration of {@code /usr/bin/rustc --version}.
	 *
	 * @return connected fixture
	 * @throws SkipException if the platform lacks Linux process records or the socket path would be too long
	 * @throws IOException   if a socket step fails
	 */
	public static FakeCargoWrapper connect() throws IOException
	{
		return connect("register", "3", "/", "2", "/usr/bin/rustc", "--version", "1", "PATH=/usr/bin");
	}

	/**
	 * Connects a fake wrapper that has already sent the given first message.
	 *
	 * @param first fields of the first message
	 * @return connected fixture
	 * @throws SkipException if the platform lacks Linux process records or the socket path would be too long
	 * @throws IOException   if a socket step fails
	 */
	public static FakeCargoWrapper connect(String... first) throws IOException
	{
		if (!Files.isDirectory(Path.of("/proc/self")))
			throw new SkipException("Requires Linux process records");
		Path directory = Files.createTempDirectory("lcw-");
		Path endpoint = directory.resolve("c");
		if (endpoint.toString().length() >= 100)
		{
			Files.delete(directory);
			throw new SkipException("Temporary directory is too long for a socket");
		}
		var scope = new DefaultToolingScope();
		var listener = LinuxCargoTransport.listen(scope, endpoint, 1, TIMEOUT);
		var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
		channel.connect(UnixDomainSocketAddress.of(endpoint));
		var accepted = listener.poll();
		var fixture = new FakeCargoWrapper(directory, scope, listener, channel,
			new LinuxCargoWrapperConnection(accepted.orElseThrow(() -> new IOException("Connection was not accepted"))));
		fixture.send(first);
		return fixture;
	}

	/**
	 * Returns the controller end of the connection.
	 *
	 * @return connection under test
	 */
	public LinuxCargoWrapperConnection connection()
	{
		return connection;
	}

	/**
	 * Starts a child that blocks until a line arrives on its standard input, then exits with status 3.
	 *
	 * @return the blocked child
	 * @throws IOException if the child cannot start
	 */
	public Process start() throws IOException
	{
		Process child = new ProcessBuilder("/bin/sh", "-c", "read x; exit 3").start();
		children.add(child);
		return child;
	}

	/**
	 * Starts a blocked child and reports it as the given attempt.
	 *
	 * @param attempt attempt number
	 * @return the blocked child
	 * @throws IOException if the child or the message fails
	 */
	public Process fork(long attempt) throws IOException
	{
		Process child = start();
		send("child", Long.toString(attempt), Long.toString(child.pid()), Long.toString(startTime(child.pid())));
		return child;
	}

	/**
	 * Releases a child as the native wrapper does after {@code release}, reaps it and reports its raw wait status.
	 *
	 * @param child   blocked child
	 * @param attempt attempt number
	 * @throws IOException          if the child or the message fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	public void finish(Process child, long attempt) throws IOException, InterruptedException
	{
		child.getOutputStream().write('\n');
		child.getOutputStream().close();
		int status = child.waitFor();
		send("exited", Long.toString(attempt), Integer.toString(status << 8));
	}

	/**
	 * Kills a child as the native wrapper does after {@code abort} or a lost connection, reaps it and reports its raw
	 * wait status.
	 *
	 * @param child   child to kill
	 * @param attempt attempt number
	 * @throws IOException          if the message fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	public void kill(Process child, long attempt) throws IOException, InterruptedException
	{
		child.destroyForcibly().waitFor();
		send("exited", Long.toString(attempt), "9");
	}

	/**
	 * Sends one framed message.
	 *
	 * @param fields complete field sequence
	 * @throws IOException if writing fails
	 */
	public void send(String... fields) throws IOException
	{
		var payload = new ByteArrayOutputStream();
		for (String field : fields)
		{
			payload.writeBytes(field.getBytes(StandardCharsets.UTF_8));
			payload.write(0);
		}
		ByteBuffer frame = ByteBuffer.allocate(4 + payload.size()).putInt(payload.size()).put(payload.toByteArray());
		frame.flip();
		while (frame.hasRemaining())
			channel.write(frame);
	}

	/**
	 * Reads one framed message that the controller has already sent.
	 *
	 * @return its fields
	 * @throws IOException if the connection ends or the frame is malformed
	 */
	public List<String> receive() throws IOException
	{
		ByteBuffer header = read(4);
		ByteBuffer payload = read(header.getInt());
		String text = new String(payload.array(), StandardCharsets.UTF_8);
		if (!text.endsWith("\0"))
			throw new IOException("Field terminator is missing");
		return List.of(text.substring(0, text.length() - 1).split("\0", -1));
	}

	/**
	 * Reports whether any byte from the controller is waiting, without blocking.
	 *
	 * @return whether a byte is readable
	 * @throws IOException if the connection fails
	 */
	public boolean pending() throws IOException
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
	 *
	 * @return whether the next read reaches end of file
	 * @throws IOException if the connection fails other than by closing
	 */
	public boolean closed() throws IOException
	{
		return channel.read(ByteBuffer.allocate(1)) < 0;
	}

	/**
	 * Closes the wrapper end, as the native wrapper's exit does.
	 *
	 * @throws IOException if closing fails
	 */
	public void disconnect() throws IOException
	{
		channel.close();
	}

	/**
	 * Reads field 22 of a process's stat record.
	 *
	 * @param pid live process
	 * @return kernel start time in clock ticks
	 * @throws IOException if the record cannot be read
	 */
	public static long startTime(long pid) throws IOException
	{
		return AllocationHooks.startTime(Files.readString(Path.of("/proc", Long.toString(pid), "stat")));
	}

	/**
	 * Reads exactly the requested number of bytes.
	 *
	 * @param length byte count
	 * @return filled buffer, positioned at its start
	 * @throws IOException if the connection ends first
	 */
	private ByteBuffer read(int length) throws IOException
	{
		ByteBuffer buffer = ByteBuffer.allocate(length);
		while (buffer.hasRemaining())
			if (channel.read(buffer) < 0)
				throw new IOException("Controller closed the connection");
		return buffer.flip();
	}

	@Override
	public void close() throws IOException
	{
		try
		{
			for (Process child : children)
				child.destroyForcibly();
			channel.close();
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
