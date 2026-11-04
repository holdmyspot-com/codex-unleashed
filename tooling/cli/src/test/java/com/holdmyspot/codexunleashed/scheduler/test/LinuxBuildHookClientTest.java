package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.AllocationHook;
import com.holdmyspot.codexunleashed.scheduler.AllocationMessages;
import com.holdmyspot.codexunleashed.scheduler.AllocationWaits;
import com.holdmyspot.codexunleashed.scheduler.CargoTokenMessages;
import com.holdmyspot.codexunleashed.scheduler.LinuxBuildHookClient;
import com.holdmyspot.codexunleashed.scheduler.LinuxBuildHookFrames;
import com.holdmyspot.codexunleashed.scheduler.LinuxCargoTransport;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies how one build hook client's frames reach the allocation hook, the token hook and the tracer.
 */
public final class LinuxBuildHookClientTest
{
	/**
	 * The attempt that admits the client.
	 */
	private static final UUID ATTEMPT = UUID.fromString("00000000-0000-0000-0000-000000000003");
	/**
	 * The client's installation.
	 */
	private static final long INSTALLATION = 9;
	/**
	 * The client's process ID.
	 */
	private static final long PID = 42;
	/**
	 * The bound on each wait.
	 */
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	/**
	 * Creates the tests.
	 */
	public LinuxBuildHookClientTest()
	{
	}

	/**
	 * Starting the allocation view reports the client ready, as the library completed its handshake before main.
	 * @throws IOException if a socket step fails
	 */
	@Test
	public void startReportsReady() throws IOException
	{
		try (Fixture fixture = Fixture.open())
		{
			AllocationHook.Connection allocations = fixture.client.allocations();
			allocations.start("ignored");
			requireThat(fixture.allocationMessages().read(allocations.pollMessage().orElseThrow()), "event").
				isEqualTo(new AllocationMessages.Ready());
		}
	}

	/**
	 * A blocked frame reaches the allocation view and not the jobserver view.
	 * @throws IOException if a socket step fails
	 */
	@Test
	public void routesBlockedToAllocations() throws IOException
	{
		try (Fixture fixture = Fixture.open())
		{
			fixture.send("blocked", "5", "43", "4096", "12");
			String message = await(fixture.client.allocations());
			requireThat(fixture.allocationMessages().read(message), "event").isEqualTo(new AllocationMessages.Blocked(
				new AllocationWaits.Wait(INSTALLATION, 5), BigInteger.valueOf(43), BigInteger.valueOf(4096), 12));
			requireThat(fixture.client.tokens().pollMessage(), "tokens").isEqualTo(Optional.empty());
		}
	}

	/**
	 * A transfer frame reaches the jobserver view and not the allocation view.
	 * @throws IOException if a socket step fails
	 */
	@Test
	public void routesTransferToTokens() throws IOException
	{
		try (Fixture fixture = Fixture.open())
		{
			fixture.send("transfer", "1", "read");
			String message = await(fixture.client.tokens());
			requireThat(new CargoTokenMessages(fixture.scope, ATTEMPT, INSTALLATION, PID).read(message), "event").
				isEqualTo(new CargoTokenMessages.Read(new CargoTokenMessages.Request(ATTEMPT, INSTALLATION, PID, 1)));
			requireThat(fixture.client.allocations().pollMessage(), "allocations").isEqualTo(Optional.empty());
		}
	}

	/**
	 * A grant posted to the allocation view reaches the client as a grant frame.
	 * @throws IOException if a socket step fails
	 */
	@Test
	public void postsGrant() throws IOException
	{
		try (Fixture fixture = Fixture.open())
		{
			fixture.client.allocations().post(fixture.allocationMessages().
				permission(new AllocationWaits.Wait(INSTALLATION, 5)));
			requireThat(fixture.receive(), "frame").isEqualTo(List.of("grant", "5"));
		}
	}

	/**
	 * A static exec announcement is held for the tracer, and adoption reaches the client.
	 * @throws IOException if a socket step fails
	 */
	@Test
	public void handsStaticExecToTracer() throws IOException
	{
		try (Fixture fixture = Fixture.open())
		{
			fixture.send("exec-static", "/usr/bin/static-tool");
			Instant deadline = Instant.now().plus(TIMEOUT);
			Optional<String> target;
			do
			{
				target = fixture.client.pollExecStatic();
				Thread.onSpinWait();
			}
			while (target.isEmpty() && Instant.now().isBefore(deadline));
			requireThat(target, "target").isEqualTo(Optional.of("/usr/bin/static-tool"));
			fixture.client.adopt();
			requireThat(fixture.receive(), "frame").isEqualTo(List.of("adopt"));
		}
	}

	/**
	 * A client that closes its end between frames has detached, as an exiting or exec'ing process does.
	 * @throws IOException if a socket step fails
	 */
	@Test
	public void closedClientHasDetached() throws IOException
	{
		try (Fixture fixture = Fixture.open())
		{
			fixture.peer.close();
			Instant deadline = Instant.now().plus(TIMEOUT);
			boolean detached;
			do
			{
				requireThat(fixture.client.allocations().pollMessage(), "message").isEqualTo(Optional.empty());
				detached = fixture.client.allocations().detached();
				Thread.onSpinWait();
			}
			while (!detached && Instant.now().isBefore(deadline));
			requireThat(detached, "allocationsDetached").isTrue();
			requireThat(fixture.client.tokens().detached(), "tokensDetached").isTrue();
		}
	}

	/**
	 * A client that is still connected has not ended.
	 * @throws IOException if a socket step fails
	 */
	@Test
	public void connectedClientHasNotEnded() throws IOException
	{
		try (Fixture fixture = Fixture.open())
		{
			requireThat(fixture.client.ended(), "ended").isFalse();
		}
	}

	/**
	 * A frame that the client sent before it closed its end is read, and then the client reports that it ended.
	 * @throws IOException if a socket step fails
	 */
	@Test
	public void endedReadsFramesSentBeforeClose() throws IOException
	{
		try (Fixture fixture = Fixture.open())
		{
			fixture.send("transfer", "1", "read");
			fixture.peer.close();
			Instant deadline = Instant.now().plus(TIMEOUT);
			boolean ended;
			do
			{
				ended = fixture.client.ended();
				Thread.onSpinWait();
			}
			while (!ended && Instant.now().isBefore(deadline));
			requireThat(ended, "ended").isTrue();
			String message = await(fixture.client.tokens());
			requireThat(new CargoTokenMessages(fixture.scope, ATTEMPT, INSTALLATION, PID).read(message), "event").
				isEqualTo(new CargoTokenMessages.Read(new CargoTokenMessages.Request(ATTEMPT, INSTALLATION, PID, 1)));
		}
	}

	/**
	 * A frame that no handler understands fails the client rather than being dropped.
	 * @throws IOException always
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*unexpected.*")
	public void rejectsUnknownFrame() throws IOException
	{
		try (Fixture fixture = Fixture.open())
		{
			fixture.send("bogus");
			await(fixture.client.allocations());
		}
	}

	/**
	 * Waits for a view's next message.
	 * @param view the view
	 * @return the message
	 * @throws IOException if polling fails or no message arrives in time
	 */
	private static String await(AllocationHook.Connection view) throws IOException
	{
		Instant deadline = Instant.now().plus(TIMEOUT);
		do
		{
			Optional<String> message = view.pollMessage();
			if (message.isPresent())
				return message.get();
			Thread.onSpinWait();
		}
		while (Instant.now().isBefore(deadline));
		throw new IOException("No message arrived within " + TIMEOUT);
	}

	/**
	 * A listener, a connected peer that speaks the library's frames, and the client under test.
	 * @param directory the socket's directory
	 * @param scope     the native bindings
	 * @param listener  the listener
	 * @param peer      the library's end
	 * @param client    the client under test
	 */
	private record Fixture(Path directory, DefaultToolingScope scope, LinuxCargoTransport.Listener listener,
		SocketChannel peer, LinuxBuildHookClient client) implements AutoCloseable
	{
		/**
		 * Connects a peer and wraps the accepted connection.
		 * @return the fixture
		 * @throws IOException if a socket step fails
		 */
		static Fixture open() throws IOException
		{
			if (!Files.isDirectory(Path.of("/proc/self")))
				throw new SkipException("Requires Linux process records");
			Path directory = Files.createTempDirectory("lbc-");
			Path endpoint = directory.resolve("c");
			DefaultToolingScope scope = new DefaultToolingScope();
			LinuxCargoTransport.Listener listener = LinuxCargoTransport.listen(scope, endpoint, 1, TIMEOUT);
			SocketChannel peer = SocketChannel.open(StandardProtocolFamily.UNIX);
			peer.connect(UnixDomainSocketAddress.of(endpoint));
			LinuxCargoTransport.Connection accepted = listener.poll().
				orElseThrow(() -> new IOException("Connection was not accepted"));
			return new Fixture(directory, scope, listener, peer,
				new LinuxBuildHookClient(scope, ATTEMPT, INSTALLATION, PID, accepted));
		}

		/**
		 * Returns a codec for the client's allocation messages.
		 * @return the codec
		 */
		AllocationMessages allocationMessages()
		{
			return new AllocationMessages(scope, ATTEMPT, INSTALLATION, PID);
		}

		/**
		 * Sends one frame as the library would.
		 * @param fields the fields
		 * @throws IOException if sending fails
		 */
		void send(String... fields) throws IOException
		{
			byte[] payload = LinuxBuildHookFrames.encode(fields);
			peer.write(ByteBuffer.allocate(Integer.BYTES + payload.length).putInt(payload.length).put(payload).flip());
		}

		/**
		 * Receives one frame as the library would.
		 * @return its fields
		 * @throws IOException if receiving fails
		 */
		List<String> receive() throws IOException
		{
			ByteBuffer header = ByteBuffer.allocate(Integer.BYTES);
			while (header.hasRemaining())
				if (peer.read(header) < 0)
					throw new IOException("Server closed the connection");
			ByteBuffer payload = ByteBuffer.allocate(header.flip().getInt());
			while (payload.hasRemaining())
				if (peer.read(payload) < 0)
					throw new IOException("Server closed the connection");
			return LinuxBuildHookFrames.decode(payload.array());
		}

		@Override
		public void close() throws IOException
		{
			try
			{
				peer.close();
				client.close();
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
