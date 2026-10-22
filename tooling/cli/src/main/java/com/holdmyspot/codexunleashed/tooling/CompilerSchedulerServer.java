package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Applies the compiler memory policy to local compiler actions over Unix-domain sockets.
 * The native action wrapper controls its own children, including inside a PID namespace.
 */
final class CompilerSchedulerServer implements AutoCloseable
{
	/**
	 * One action connection and its partially received and pending packets.
	 */
	private static final class Client
	{
		private final long id;
		private final SocketChannel channel;
		private final ByteBuffer incoming = ByteBuffer.allocate(12);
		private final Deque<ByteBuffer> outgoing = new ArrayDeque<>();
		private int generation;
		private boolean tracked = true;

		/**
		 * Tracks a channel borrowed from the service's accepted connections.
		 *
		 * @param id the service-assigned action identity
		 * @param channel the action connection
		 */
		Client(long id, SocketChannel channel)
		{
			this.id = id;
			this.channel = channel;
		}
	}

	private final Path endpoint;
	private final CompilerScheduler policy;
	private final LongSupplier memory;
	private final Duration interval;
	private final PrintStream log;
	private final Selector selector;
	private final ServerSocketChannel server;
	private final Thread thread;
	private final Map<Long, Client> clients = new HashMap<>();
	private volatile boolean closed;
	private volatile IOException failure;
	private long nextId;

	/**
	 * Starts a service at an endpoint in a caller-owned private directory.
	 *
	 * @param endpoint the socket path, which must not exist
	 * @param reserve the free-memory reserve in bytes
	 * @param memory supplies available RAM; read failures use {@link UncheckedIOException}
	 * @param interval the positive delay between memory observations
	 * @param log receives service failures
	 * @throws IOException if the socket or selector cannot be initialized
	 */
	CompilerSchedulerServer(Path endpoint, long reserve, LongSupplier memory, Duration interval, PrintStream log)
		throws IOException
	{
		this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
		this.policy = new CompilerScheduler(reserve);
		this.memory = Objects.requireNonNull(memory, "memory");
		this.interval = Objects.requireNonNull(interval, "interval");
		this.log = Objects.requireNonNull(log, "log");
		if (interval.isNegative() || interval.isZero())
			throw new IllegalArgumentException("interval must be positive: " + interval);
		this.selector = Selector.open();
		try
		{
			this.server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
		}
		catch (IOException | RuntimeException | Error exception)
		{
			try (selector)
			{
				throw exception;
			}
		}
		boolean bound = false;
		try
		{
			server.configureBlocking(false);
			server.bind(UnixDomainSocketAddress.of(endpoint));
			bound = true;
			server.register(selector, SelectionKey.OP_ACCEPT);
			policy.observe(memory.getAsLong());
			this.thread = Thread.ofPlatform().name("bazel-compiler-scheduler").daemon().start(this::run);
		}
		catch (IOException | RuntimeException | Error exception)
		{
			try (server; selector)
			{
				try
				{
					if (bound)
						Files.deleteIfExists(endpoint);
				}
				catch (IOException cleanupFailure)
				{
					exception.addSuppressed(cleanupFailure);
				}
				throw exception;
			}
		}
	}

	/**
	 * Reports a failure even when the Bazel invocation did not execute a compiler action.
	 *
	 * @throws IOException if the scheduler stopped unexpectedly
	 */
	void checkHealthy() throws IOException
	{
		if (failure != null)
			throw new IOException("The Bazel compiler scheduler stopped", failure);
	}

	/**
	 * Processes connection events and memory observations until shutdown or a service failure.
	 */
	private void run()
	{
		long nextObservation = System.nanoTime();
		try
		{
			while (!closed)
			{
				selector.select(Math.max(1, interval.toMillis()));
				if (closed)
					break;
				Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
				while (keys.hasNext())
				{
					SelectionKey key = keys.next();
					keys.remove();
					if (!key.isValid())
						continue;
					if (key.isAcceptable())
						accept();
					else
					{
						Client client = (Client) key.attachment();
						if (key.isReadable())
							read(client);
						if (key.isValid() && key.isWritable())
							write(client);
					}
				}
				if (System.nanoTime() - nextObservation >= 0)
				{
					perform(policy.observe(memory.getAsLong()));
					nextObservation = System.nanoTime() + interval.toNanos();
				}
			}
		}
		catch (UncheckedIOException exception)
		{
			recordFailure(exception.getCause());
		}
		catch (IOException | RuntimeException exception)
		{
			recordFailure(new IOException("Cannot schedule local Bazel compilers", exception));
		}
		finally
		{
			for (Client client : List.copyOf(clients.values()))
			{
				try (client.channel)
				{
					clients.remove(client.id);
				}
				catch (IOException exception)
				{
					log.println("Bazel compiler scheduler: cannot close action connection: " + exception.getMessage());
				}
			}
		}
	}

	/**
	 * Retains an unexpected service failure for the caller and its diagnostic stream.
	 *
	 * @param exception the failure before deliberate shutdown
	 */
	private void recordFailure(IOException exception)
	{
		if (!closed)
		{
			failure = exception;
			log.println("Bazel compiler scheduler: " + exception.getMessage());
		}
	}

	/**
	 * Registers one pending action connection and requests policy admission.
	 *
	 * @throws IOException if the connection cannot be accepted or registered
	 */
	private void accept() throws IOException
	{
		SocketChannel channel = server.accept();
		if (channel == null)
			return;
		try
		{
			channel.configureBlocking(false);
			++nextId;
			Client client = new Client(nextId, channel);
			channel.register(selector, SelectionKey.OP_READ, client);
			clients.put(client.id, client);
			perform(policy.request(client.id));
		}
		catch (IOException | RuntimeException | Error exception)
		{
			try (channel)
			{
				throw exception;
			}
		}
	}

	/**
	 * Receives a packet and applies the action's event to the memory policy.
	 *
	 * @param client the action connection
	 * @throws IOException if transport fails or the event violates the protocol
	 */
	private void read(Client client) throws IOException
	{
		int count = client.channel.read(client.incoming);
		if (count < 0)
		{
			forget(client);
			return;
		}
		if (client.incoming.hasRemaining())
			return;
		client.incoming.flip();
		int command = client.incoming.getInt();
		int generation = client.incoming.getInt();
		int value = client.incoming.getInt();
		client.incoming.clear();
		if (command == 10 && generation <= client.generation)
		{
			forget(client);
			return;
		}
		if (generation != client.generation || !client.tracked)
			throw new IOException("Unexpected compiler attempt generation " + generation + " for action " + client.id);
		switch (command)
		{
			case 8 -> policy.started(client.id);
			case 9, 10 -> forget(client);
			case 11, 12 ->
			{
				if (value != 137)
					throw new IOException("Unexpected killed-compiler status " + value);
				List<CompilerScheduler.Action> actions = policy.exited(client.id, true);
				boolean retry = actions.stream().anyMatch(action -> action instanceof CompilerScheduler.Retry);
				perform(actions);
				if (!retry)
					client.tracked = false;
			}
			default -> throw new IOException("Unexpected compiler event " + command);
		}
	}

	/**
	 * Closes an action connection and releases any admission it still owns.
	 *
	 * @param client the departing action
	 * @throws IOException if its channel cannot be closed
	 */
	private void forget(Client client) throws IOException
	{
		clients.remove(client.id);
		client.channel.close();
		if (client.tracked)
		{
			client.tracked = false;
			perform(policy.exited(client.id, false));
		}
	}

	/**
	 * Queues policy decisions for their action connections without blocking the event loop.
	 *
	 * @param actions the decisions in policy order
	 */
	private void perform(List<CompilerScheduler.Action> actions)
	{
		for (CompilerScheduler.Action action : actions)
		{
			long id;
			int command;
			switch (action)
			{
				case CompilerScheduler.Start(long compiler) ->
				{
					id = compiler;
					command = 1;
				}
				case CompilerScheduler.Pause(long compiler) ->
				{
					id = compiler;
					command = 2;
				}
				case CompilerScheduler.Resume(long compiler) ->
				{
					id = compiler;
					command = 3;
				}
				case CompilerScheduler.Kill(long compiler) ->
				{
					id = compiler;
					command = 4;
				}
				case CompilerScheduler.Retry(long compiler) ->
				{
					id = compiler;
					command = 5;
				}
				case CompilerScheduler.Fail(long compiler) ->
				{
					id = compiler;
					command = 6;
				}
			}
			Client client = clients.get(id);
			if (command == 1)
				++client.generation;
			client.outgoing.add(ByteBuffer.allocate(12).putInt(command).putInt(client.generation).putInt(0).flip());
			client.channel.keyFor(selector).interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
		}
	}

	/**
	 * Flushes queued decisions until the action connection would block.
	 *
	 * @param client the action receiving decisions
	 * @throws IOException if its connection fails
	 */
	private void write(Client client) throws IOException
	{
		while (!client.outgoing.isEmpty())
		{
			ByteBuffer packet = client.outgoing.getFirst();
			client.channel.write(packet);
			if (packet.hasRemaining())
				return;
			client.outgoing.removeFirst();
		}
		if (client.tracked)
			client.channel.keyFor(selector).interestOps(SelectionKey.OP_READ);
		else
			forget(client);
	}

	@Override
	public void close() throws IOException
	{
		closed = true;
		selector.wakeup();
		try
		{
			InterruptedException interruption = null;
			while (true)
			{
				try
				{
					thread.join();
					break;
				}
				catch (InterruptedException exception)
				{
					if (interruption == null)
						interruption = exception;
				}
			}
			if (interruption != null)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while closing the Bazel compiler scheduler", interruption);
			}
		}
		finally
		{
			try (server; selector)
			{
				Files.deleteIfExists(endpoint);
			}
		}
	}
}
