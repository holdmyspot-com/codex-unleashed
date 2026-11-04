package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;

/**
 * Serves one admitted linux-build-hook.c client: routes its allocation frames to an {@link AllocationHook}, its
 * jobserver frames to a {@link CargoTokenHook}, and its static exec announcements to the attempt's tracer.
 * <p>
 * Each hook drives one of this client's two connection views. Closing a view ends only that hook's use of it; the
 * client's owner closes the socket. The views and this client must be used from one thread.
 */
public final class LinuxBuildHookClient implements AutoCloseable
{
	/**
	 * The admitted connection.
	 */
	private final LinuxCargoTransport.Connection connection;
	/**
	 * Translates allocation frames.
	 */
	private final LinuxBuildHookMessages allocationMessages;
	/**
	 * Translates jobserver frames.
	 */
	private final LinuxBuildHookTokens tokenMessages;
	/**
	 * Allocation messages not yet polled.
	 */
	private final Queue<String> allocationQueue = new ArrayDeque<>();
	/**
	 * Jobserver messages not yet polled.
	 */
	private final Queue<String> tokenQueue = new ArrayDeque<>();
	/**
	 * Static exec targets not yet polled.
	 */
	private final Queue<String> execQueue = new ArrayDeque<>();
	/**
	 * The allocation view.
	 */
	private final View allocations;
	/**
	 * The jobserver view.
	 */
	private final View tokens;
	/**
	 * Whether the client closed its end between frames.
	 */
	private boolean ended;

	/**
	 * Creates a client for an admitted connection, taking ownership of it.
	 * @param scope        supplies the message mappers; the caller keeps ownership
	 * @param attempt      the attempt that admitted the client
	 * @param installation the identifier sent in the client's {@code welcome}
	 * @param pid          the client's process ID
	 * @param connection   the admitted connection
	 * @throws NullPointerException if an argument is null
	 */
	public LinuxBuildHookClient(ToolingScope scope, UUID attempt, long installation, long pid,
		LinuxCargoTransport.Connection connection)
	{
		this.connection = Objects.requireNonNull(connection, "connection");
		this.allocationMessages = new LinuxBuildHookMessages(scope, attempt, installation, pid);
		this.tokenMessages = new LinuxBuildHookTokens(scope, attempt, installation, pid);
		this.allocations = new View(allocationQueue, allocationMessages.ready(),
			permission -> connection.send(allocationMessages.grant(permission)));
		this.tokens = new View(tokenQueue, tokenMessages.ready(),
			control -> connection.send(tokenMessages.token(control)));
	}

	/**
	 * Returns the view that an {@link AllocationHook} drives.
	 * @return the allocation view
	 */
	public AllocationHook.Connection allocations()
	{
		return allocations;
	}

	/**
	 * Returns the view that a {@link CargoTokenHook} drives.
	 * @return the jobserver view
	 */
	public AllocationHook.Connection tokens()
	{
		return tokens;
	}

	/**
	 * Returns the next static target that the client announced it will exec once adopted.
	 * @return the target's path, or empty if none is pending
	 * @throws IOException if reading the connection fails or the client sent an unknown frame
	 */
	public Optional<String> pollExecStatic() throws IOException
	{
		pump();
		return Optional.ofNullable(execQueue.poll());
	}

	/**
	 * Tells the client that the tracer has seized it, so it may stop itself and exec its static target.
	 * @throws IOException if sending fails
	 */
	public void adopt() throws IOException
	{
		connection.send(LinuxBuildHookFrames.encode("adopt"));
	}

	/**
	 * Reports whether the client closed its end and left no unread frame.
	 * @return true once every frame the client sent has been read and it has closed its end
	 * @throws IOException if reading fails or the client sent a frame that no handler understands
	 */
	public boolean ended() throws IOException
	{
		pump();
		return ended;
	}

	/**
	 * Reads every complete frame that has arrived and queues it for its handler.
	 * @throws IOException if reading fails or the client sent a frame that no handler understands
	 */
	private void pump() throws IOException
	{
		while (!ended)
		{
			Optional<byte[]> frame = connection.pollFrameUntilEnd();
			if (frame.isEmpty())
			{
				ended = connection.ended();
				return;
			}
			List<String> fields = LinuxBuildHookFrames.decode(frame.get());
			Optional<String> allocation = allocationMessages.allocation(fields);
			if (allocation.isPresent())
			{
				allocationQueue.add(allocation.get());
				continue;
			}
			Optional<String> transfer = tokenMessages.transfer(fields);
			if (transfer.isPresent())
			{
				tokenQueue.add(transfer.get());
				continue;
			}
			if (fields.size() == 2 && fields.getFirst().equals("exec-static"))
			{
				execQueue.add(fields.get(1));
				continue;
			}
			throw new IOException("The build hook client sent an unexpected frame: " + fields);
		}
	}

	@Override
	public void close() throws IOException
	{
		connection.close();
	}

	/**
	 * Sends one hook's control to the client.
	 */
	@FunctionalInterface
	private interface Sender
	{
		/**
		 * Translates and sends a control.
		 * @param control the control
		 * @throws IOException if it is not for this client or sending fails
		 */
		void send(JsonNode control) throws IOException;
	}

	/**
	 * One hook's view of the client.
	 */
	private final class View implements AllocationHook.Connection
	{
		/**
		 * This hook's messages not yet polled.
		 */
		private final Queue<String> queue;
		/**
		 * The message that reports the client ready.
		 */
		private final String ready;
		/**
		 * Sends this hook's controls.
		 */
		private final Sender sender;

		/**
		 * Creates a view.
		 * @param queue  this hook's messages
		 * @param ready  the message that reports the client ready
		 * @param sender sends this hook's controls
		 */
		View(Queue<String> queue, String ready, Sender sender)
		{
			this.queue = queue;
			this.ready = ready;
			this.sender = sender;
		}

		/**
		 * Reports the client ready. The library is already loaded, so the program is not used.
		 * @param source ignored
		 */
		@Override
		public void start(String source)
		{
			queue.add(ready);
		}

		@Override
		public Optional<String> pollMessage() throws IOException
		{
			if (queue.isEmpty())
				pump();
			return Optional.ofNullable(queue.poll());
		}

		@Override
		public void post(JsonNode permission) throws IOException
		{
			sender.send(permission);
		}

		/**
		 * Ends this hook's use of the client; the client's owner closes the socket.
		 */
		@Override
		public void close()
		{
		}

		/**
		 * Reports whether the client closed its end, as it does when it exits or execs.
		 * @return true once the client has closed its end between frames
		 */
		@Override
		public boolean detached()
		{
			return ended;
		}
	}
}
