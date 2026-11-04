package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxCargoTransport;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies that a connection distinguishes a peer that closed between frames from one that is idle or mid-frame.
 */
public final class LinuxCargoTransportEndTest
{
	/**
	 * The bound on each frame operation.
	 */
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	/**
	 * Creates the tests.
	 */
	public LinuxCargoTransportEndTest()
	{
	}

	/**
	 * A connected, silent peer has not ended.
	 * @throws IOException if a socket step fails
	 */
	@Test
	public void idlePeerHasNotEnded() throws IOException
	{
		try (Fixture fixture = Fixture.open())
		{
			requireThat(fixture.accepted.ended(), "ended").isFalse();
		}
	}

	/**
	 * A peer that closes after its last complete frame has ended once that frame is consumed.
	 * @throws IOException if a socket step fails
	 */
	@Test
	public void peerClosedBetweenFramesHasEnded() throws IOException
	{
		try (Fixture fixture = Fixture.open())
		{
			fixture.client.write(ByteBuffer.wrap(new byte[]{0, 0, 0, 1, 7}));
			fixture.client.close();
			requireThat(fixture.accepted.awaitFrame(), "frame").isEqualTo(new byte[]{7});
			requireThat(awaitEnded(fixture.accepted), "ended").isTrue();
		}
	}

	/**
	 * A peer that closes in the middle of a frame has not ended cleanly: the partial frame is still unread.
	 * @throws IOException if a socket step fails
	 */
	@Test
	public void peerClosedMidFrameHasNotEnded() throws IOException
	{
		try (Fixture fixture = Fixture.open())
		{
			fixture.client.write(ByteBuffer.wrap(new byte[]{0, 0, 0, 2, 7}));
			fixture.client.close();
			requireThat(fixture.accepted.ended(), "ended").isFalse();
		}
	}

	/**
	 * Polls a connection until it reports its end, bounded by {@link #TIMEOUT}.
	 * @param connection the connection
	 * @return the last result
	 * @throws IOException if the probe fails
	 */
	private static boolean awaitEnded(LinuxCargoTransport.Connection connection) throws IOException
	{
		Instant deadline = Instant.now().plus(TIMEOUT);
		boolean ended;
		do
		{
			ended = connection.ended();
			Thread.onSpinWait();
		}
		while (!ended && Instant.now().isBefore(deadline));
		return ended;
	}

	/**
	 * A listener, a connected client and the accepted connection.
	 * @param directory the socket's directory
	 * @param scope     the native bindings
	 * @param listener  the listener
	 * @param client    the client end
	 * @param accepted  the server end under test
	 */
	private record Fixture(Path directory, DefaultToolingScope scope, LinuxCargoTransport.Listener listener,
		SocketChannel client, LinuxCargoTransport.Connection accepted) implements AutoCloseable
	{
		/**
		 * Connects a client to a new listener.
		 * @return the fixture
		 * @throws IOException if a socket step fails
		 */
		static Fixture open() throws IOException
		{
			if (!Files.isDirectory(Path.of("/proc/self")))
				throw new SkipException("Requires Linux process records");
			Path directory = Files.createTempDirectory("lte-");
			Path endpoint = directory.resolve("c");
			DefaultToolingScope scope = new DefaultToolingScope();
			LinuxCargoTransport.Listener listener = LinuxCargoTransport.listen(scope, endpoint, 1, TIMEOUT);
			SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX);
			client.connect(UnixDomainSocketAddress.of(endpoint));
			LinuxCargoTransport.Connection accepted = listener.poll().
				orElseThrow(() -> new IOException("Connection was not accepted"));
			return new Fixture(directory, scope, listener, client, accepted);
		}

		@Override
		public void close() throws IOException
		{
			try
			{
				client.close();
				accepted.close();
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
}
