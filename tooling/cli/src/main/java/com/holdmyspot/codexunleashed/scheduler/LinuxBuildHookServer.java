package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * Instruments one contained attempt through {@code linux-build-hook.c}, which every dynamic image of the attempt
 * preloads, instead of attaching Frida to each image.
 * <p>
 * The server listens on the attempt's own socket and releases the held root with {@code LD_PRELOAD} naming the library,
 * {@code CODEX_BUILD_HOOK} naming the socket and, when the attempt mediates jobserver tokens,
 * {@code CODEX_BUILD_HOOK_FIFO} naming the FIFO. Each image that loads the library connects before its {@code main}
 * and waits for {@code welcome}. The server admits a connection only from a process whose kernel peer receipt matches
 * its {@code hello}, whose credentials are the workload's and whose cgroup is inside the attempt's; it then assigns an
 * installation, drives an {@link AllocationHook} and, with tokens, a {@link CargoTokenHook} on the client, and welcomes
 * it. A process that execs connects again under the same PID, which retires its earlier installation.
 * <p>
 * A static image ignores the library, so the library announces it with {@code exec-static} and runs the trampoline
 * instead. The server seizes the trampoline with {@link LinuxAllocationTrace.Images}, which then traces the static
 * image and its descendants, and tells it to proceed with {@code adopt}.
 * <p>
 * One thread owns this object: the thread that constructed it.
 */
public final class LinuxBuildHookServer implements LinuxAttempts.Instrumentation
{
	/** Connections that may wait to be accepted, enough for a burst of forked compilers. */
	private static final int BACKLOG = 64;
	/** Interval between checks while the server waits for a client, so that waiting does not occupy a CPU. */
	private static final Duration POLL_INTERVAL = Duration.ofMillis(10);
	/** Borrowed native functions and message mappers. */
	private final ToolingScope scope;
	/** The thread that owns this object. */
	private final Thread owner = Thread.currentThread();
	/** Native attempt correlation. */
	private final UUID identity;
	/** Held root and its owned cgroup subtree. */
	private final AllocationHooks.Root root;
	/** The attempt's cgroup v2 path, such as {@code /scheduler/attempt-3}, that admitted clients are inside. */
	private final String group;
	/** Workload user identity that every admitted client has. */
	private final int uid;
	/** Workload group identity that every admitted client has. */
	private final int gid;
	/** The library, which the trampoline is installed beside. */
	private final Path library;
	/** Libraries that the workload already preloads, kept after the library. */
	private final List<Path> preloads;
	/** The attempt's socket path. */
	private final Path endpoint;
	/** Attempt allocation waits. */
	private final AllocationWaits waits;
	/** Bound on each native control. */
	private final Duration timeout;
	/** Peer service without queue reentry. */
	private final AllocationHook.Pump peers;
	/** Attempt jobserver FIFO and ledger, when tokens are mediated. */
	private final Optional<AllocationHooks.CargoTokens> cargoTokens;
	/** Tracer of static images and their descendants. */
	private final LinuxAllocationTrace.Images nativeImages;
	/** Accepted connections that have not sent {@code hello}. */
	private final List<Pending> pending = new ArrayList<>();
	/** Admitted clients by installation, retained until close. */
	private final Map<Long, Client> clients = new LinkedHashMap<>();
	/** Allocation hooks by installation, retained until close. */
	private final Map<Long, AllocationHook> installations = new LinkedHashMap<>();
	/** Token hooks by installation, retained until close. */
	private final Map<Long, CargoTokenHook> tokenInstallations = new LinkedHashMap<>();
	/** Each process's current installation. */
	private final Map<Long, Long> activeInstallations = new LinkedHashMap<>();
	/** Installations of images that the tracer owns. */
	private final Set<Long> nativeInstallations = new LinkedHashSet<>();
	/** The listener, or null before start. */
	private LinuxCargoTransport.Listener listener;
	/** The next installation to assign. */
	private long nextInstallation = 1;
	/** Whether start was called. */
	private boolean startAttempted;
	/** Whether grant authority was withdrawn. */
	private boolean stopped;
	/** Whether native resources were destroyed. */
	private boolean closed;
	/** The first failure, after which no automatic control is sent. */
	private IOException failure;
	/** A failed cleanup, which close reports again. */
	private IOException closeFailure;

	/**
	 * Borrows the attempt's owners without acquiring anything.
	 * @param scope       borrowed native functions and message mappers
	 * @param identity    native attempt correlation
	 * @param root        held root, already moved into and verified in its owned cgroup subtree
	 * @param cgroupRoot  absolute mountpoint of the cgroup-v2 hierarchy that contains {@code root.directory()}
	 * @param uid         workload user identity
	 * @param gid         workload group identity
	 * @param library     absolute path of {@code libbuildhook.so}, with {@code codex-build-hook-exec} beside it
	 * @param preloads    absolute libraries that the workload already preloads, kept after the library
	 * @param endpoint    absolute path for the attempt's socket, shorter than 108 UTF-8 bytes, that does not exist
	 * @param waits       attempt allocation waits
	 * @param timeout     positive bound on each native control
	 * @param peers       peer service without queue reentry
	 * @param cargoTokens the attempt's jobserver FIFO and ledger, or empty if tokens are not mediated
	 * @throws NullPointerException     if an argument is null
	 * @throws IllegalArgumentException if {@code root.directory()} is not strictly inside {@code cgroupRoot}, a path is
	 *                                  relative, a library path contains a colon or whitespace, an identity is negative
	 *                                  or the timeout is not positive
	 */
	public LinuxBuildHookServer(ToolingScope scope, UUID identity, AllocationHooks.Root root, Path cgroupRoot, int uid,
		int gid, Path library, List<Path> preloads, Path endpoint, AllocationWaits waits, Duration timeout,
		AllocationHook.Pump peers, Optional<AllocationHooks.CargoTokens> cargoTokens)
	{
		this.scope = Objects.requireNonNull(scope, "scope");
		this.identity = Objects.requireNonNull(identity, "identity");
		this.root = Objects.requireNonNull(root, "root");
		Objects.requireNonNull(cgroupRoot, "cgroupRoot");
		if (!cgroupRoot.isAbsolute() || !root.directory().startsWith(cgroupRoot) || root.directory().equals(cgroupRoot))
		{
			throw new IllegalArgumentException("The attempt's cgroup " + root.directory() + " is not inside " +
				cgroupRoot);
		}
		this.group = "/" + cgroupRoot.relativize(root.directory());
		if (uid < 0 || gid < 0)
			throw new IllegalArgumentException("Workload identities must not be negative: " + uid + ":" + gid);
		this.uid = uid;
		this.gid = gid;
		this.library = requirePreload(library);
		this.preloads = List.copyOf(preloads);
		for (Path preload : this.preloads)
			requirePreload(preload);
		this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
		if (!endpoint.isAbsolute())
			throw new IllegalArgumentException("The attempt's socket path must be absolute: " + endpoint);
		this.waits = Objects.requireNonNull(waits, "waits");
		this.timeout = LinuxAttempts.checkedTimeout(timeout);
		this.peers = Objects.requireNonNull(peers, "peers");
		this.cargoTokens = Objects.requireNonNull(cargoTokens, "cargoTokens");
		this.nativeImages = new LinuxAllocationTrace.Images(scope.getNativeLinker(), timeout);
	}

	/**
	 * Checks that a library can be named in {@code LD_PRELOAD}, which separates entries with colons and whitespace.
	 * @param preload the library
	 * @return {@code preload}
	 * @throws NullPointerException     if {@code preload} is null
	 * @throws IllegalArgumentException if {@code preload} is relative or contains a separator
	 */
	private static Path requirePreload(Path preload)
	{
		Objects.requireNonNull(preload, "preload");
		String text = preload.toString();
		if (!preload.isAbsolute() || text.chars().anyMatch(c -> c == ':' || Character.isWhitespace(c)))
			throw new IllegalArgumentException("A preloaded library must be absolute without separators: " + text);
		return preload;
	}

	/**
	 * Listens on the attempt's socket, releases the held root into the library and admits the root's image.
	 * <p>
	 * If the root stops running before it connects, its exec failed or it was killed: nothing is installed and the
	 * attempt's outcome comes from the root's exit status.
	 * @param release lets the held root set the hook's environment and exec; called once
	 * @throws NullPointerException  if {@code release} is null
	 * @throws IllegalStateException if the server was already started or stopped
	 * @throws IOException           if listening, releasing or admitting the root fails, or the root neither connects
	 *                               nor exits within the timeout
	 */
	@Override
	public void start(AllocationHooks.Release release) throws IOException
	{
		requireOwner();
		Objects.requireNonNull(release, "release");
		requireCertain();
		if (startAttempted || stopped)
			throw new IllegalStateException("The build hook server cannot start again");
		startAttempted = true;

		try
		{
			listener = LinuxCargoTransport.listen(scope, endpoint, BACKLOG, timeout);
			// Only the workload may connect; other users' processes must not reach the admission checks at all.
			Files.setAttribute(endpoint, "unix:uid", uid);
			Files.setAttribute(endpoint, "unix:gid", gid);
			Files.setPosixFilePermissions(endpoint, PosixFilePermissions.fromString("rw-------"));
			release.release(environment());
			long began = System.nanoTime();
			while (!activeInstallations.containsKey(root.pid()))
			{
				service();
				if (activeInstallations.containsKey(root.pid()) || !AllocationHooks.isRunning(root.pid(), root.started()))
					return;
				waitForClient(began, "The attempt root neither connected to its build hook server nor exited");
			}
		}
		catch (IOException | RuntimeException cause)
		{
			throw failed("Build hook server start", cause);
		}
		catch (Error cause)
		{
			failed("Build hook server start", cause);
			throw cause;
		}
	}

	/**
	 * Returns the entries that the held root sets before its exec.
	 * @return {@code NAME=value} entries
	 */
	private List<String> environment()
	{
		StringJoiner preload = new StringJoiner(":");
		preload.add(library.toString());
		for (Path inherited : preloads)
			preload.add(inherited.toString());
		List<String> entries = new ArrayList<>(List.of("LD_PRELOAD=" + preload, "CODEX_BUILD_HOOK=" + endpoint));
		if (cargoTokens.isPresent())
		{
			LinuxFifoBindings.Identity fifo = cargoTokens.get().fifo();
			entries.add("CODEX_BUILD_HOOK_FIFO=" + fifo.major() + ":" + fifo.minor() + ":" +
				Long.toUnsignedString(fifo.inode()));
		}
		return entries;
	}

	/**
	 * Admits new clients, hands static images to the tracer, and services every installation once.
	 * @throws IOException if a control or an admitted client fails
	 */
	@Override
	public void pump() throws IOException
	{
		requireOwner();
		requireCertain();
		if (stopped || closed || listener == null)
			return;
		try
		{
			service();
		}
		catch (IOException | RuntimeException cause)
		{
			throw failed("Build hook delivery", cause);
		}
		catch (Error cause)
		{
			failed("Build hook delivery", cause);
			throw cause;
		}
	}

	/**
	 * Performs one round of {@link #pump()}.
	 * @throws IOException if a control or an admitted client fails
	 */
	private void service() throws IOException
	{
		for (Optional<LinuxCargoTransport.Connection> accepted = listener.poll(); accepted.isPresent();
			accepted = listener.poll())
		{
			pending.add(new Pending(accepted.get(), System.nanoTime()));
		}
		admitPending();
		for (Map.Entry<Long, Client> entry : List.copyOf(clients.entrySet()))
		{
			Client client = entry.getValue();
			if (!Objects.equals(activeInstallations.get(client.pid()), entry.getKey()))
				continue;
			if (client.connection().pollExecStatic().isPresent())
				adoptStatic(client);
		}
		LinuxAllocationTrace.Denial denial = nativeImages.pollDenial();
		if (denial != null)
			waits.blocked(AllocationHooks.nativeWait(denial, activeInstallations, nativeInstallations));
		FridaRuntime.Child traced = nativeImages.pollChild();
		if (traced != null)
			continueTraced(traced);
		for (long installation : List.copyOf(activeInstallations.values()))
		{
			AllocationHook hook = installations.get(installation);
			if (hook != null)
				hook.pump();
			CargoTokenHook tokens = tokenInstallations.get(installation);
			if (tokens != null)
				tokens.pump();
		}
	}

	/**
	 * Admits each pending connection whose {@code hello} arrived and closes each that failed admission, ended or
	 * timed out. A rejected process receives no {@code welcome}, so the library ends it before its {@code main}.
	 * @throws IOException if closing a rejected connection or installing an admitted client fails
	 */
	private void admitPending() throws IOException
	{
		for (Iterator<Pending> iterator = pending.iterator(); iterator.hasNext();)
		{
			Pending candidate = iterator.next();
			LinuxCargoTransport.Connection connection = candidate.connection();
			Optional<Admission> admission;
			try
			{
				admission = admission(connection);
			}
			catch (IOException _)
			{
				iterator.remove();
				connection.close();
				continue;
			}
			if (admission.isEmpty())
			{
				if (connection.ended() || System.nanoTime() - candidate.accepted() >= timeout.toNanos())
				{
					iterator.remove();
					connection.close();
				}
				continue;
			}
			iterator.remove();
			install(admission.get(), connection);
		}
	}

	/**
	 * Reads a connection's {@code hello} if it arrived and checks that the client belongs to the attempt.
	 * @param connection the connection
	 * @return the client's identity, or empty if {@code hello} has not arrived
	 * @throws IOException if the frame is malformed or the client does not belong to the attempt
	 */
	private Optional<Admission> admission(LinuxCargoTransport.Connection connection) throws IOException
	{
		Optional<byte[]> frame = connection.pollFrameUntilEnd();
		if (frame.isEmpty())
			return Optional.empty();
		List<String> hello = LinuxBuildHookFrames.decode(frame.get());
		LinuxBorrowedStreams.Peer peer = connection.peer();
		if (peer.uid() != uid || peer.gid() != gid)
		{
			throw new IOException("Process " + peer.pid() + " runs as " + peer.uid() + ":" + peer.gid() +
				" instead of the workload's " + uid + ":" + gid);
		}
		Path process = Path.of("/proc", Long.toString(peer.pid()));
		String membership;
		try
		{
			membership = Files.readString(process.resolve("cgroup"));
		}
		catch (NoSuchFileException cause)
		{
			throw new IOException("Process " + peer.pid() + " exited before admission", cause);
		}
		long pid = LinuxBuildHookHandshake.admit(hello, peer, membership, group);
		// The membership names the peer only if the PID was not reused while it was read.
		if (AllocationHooks.startTime(Files.readString(process.resolve("stat"))) != peer.startTicks())
			throw new IOException("Process " + pid + " exited during admission");
		return Optional.of(new Admission(pid, peer.startTicks()));
	}

	/**
	 * Installs hooks on an admitted client and welcomes it, retiring the installation of the image that it replaced.
	 * @param admission  the client's identity
	 * @param connection its connection, whose ownership transfers to the client
	 * @throws IOException if a hook cannot become ready or the welcome cannot be sent
	 */
	private void install(Admission admission, LinuxCargoTransport.Connection connection) throws IOException
	{
		long installation = nextInstallation;
		nextInstallation = Math.incrementExact(nextInstallation);
		LinuxBuildHookClient client = new LinuxBuildHookClient(scope, identity, installation, admission.pid(),
			connection);
		clients.put(installation, new Client(admission.pid(), admission.started(), client));
		retire(admission.pid());
		activeInstallations.put(admission.pid(), installation);

		// The library is already loaded, so the hooks need no program.
		AllocationHook hook = new AllocationHook(scope, identity, installation, admission.pid(), client.allocations(),
			waits, "", timeout, System::nanoTime);
		installations.put(installation, hook);
		hook.start();
		hook.awaitReady(peers);
		if (cargoTokens.isPresent())
		{
			AllocationHooks.CargoTokens cargo = cargoTokens.get();
			CargoTokenHook tokens = new CargoTokenHook(scope, identity, installation, admission.pid(), cargo.fifo(),
				client.tokens(), cargo.tokens(), "", timeout, System::nanoTime);
			tokenInstallations.put(installation, tokens);
			tokens.start();
			tokens.awaitReady(peers);
		}
		connection.send(LinuxBuildHookHandshake.welcome(installation));
	}

	/**
	 * Retires a process's current installation, whose image an exec replaced.
	 * @param pid the process
	 * @throws IOException if the waits cannot retire the installation
	 */
	private void retire(long pid) throws IOException
	{
		Long previous = activeInstallations.remove(pid);
		if (previous == null)
			return;
		AllocationHook hook = installations.get(previous);
		if (hook != null)
			hook.stop();
		CargoTokenHook tokens = tokenInstallations.get(previous);
		if (tokens != null)
			tokens.stop();
		waits.retireInstallation(previous);
	}

	/**
	 * Seizes a trampoline that announced a static target, then lets it exec the target under trace.
	 * @param client the trampoline
	 * @throws IOException if the trampoline does not stop, changes identity, or cannot be seized or continued
	 */
	private void adoptStatic(Client client) throws IOException
	{
		long pid = client.pid();
		client.connection().adopt();
		Path stat = Path.of("/proc", Long.toString(pid), "stat");
		long began = System.nanoTime();
		while (!state(Files.readString(stat)).equals("T"))
			waitForClient(began, "The trampoline " + pid + " did not stop for adoption");
		long birth = AllocationHooks.startTime(Files.readString(stat));
		if (birth != client.started())
			throw new IOException("The trampoline " + pid + " changed before adoption");
		continueUnderTrace(pid, birth);
	}

	/**
	 * Continues a fork or exec that the tracer holds in an image it owns.
	 * @param event the held transition
	 * @throws IOException if the process is not the tracer's or cannot be continued
	 */
	private void continueTraced(FridaRuntime.Child event) throws IOException
	{
		long pid = event.pid();
		if (!nativeImages.owns(pid))
			throw new IOException("The tracer reported process " + pid + ", which it does not own");
		continueUnderTrace(pid, AllocationHooks.startTime(Files.readString(Path.of("/proc", Long.toString(pid),
			"stat"))));
	}

	/**
	 * Gives a held process's image a traced installation, replacing its current one, and continues it.
	 * <p>
	 * For a process that the tracer does not own yet, installing seizes it; no Frida session holds it, so releasing
	 * that session does nothing.
	 * @param pid   the held process
	 * @param birth its start time in clock ticks
	 * @throws IOException if the tracer cannot own or continue the process
	 */
	private void continueUnderTrace(long pid, long birth) throws IOException
	{
		nativeImages.install(pid, birth, () ->
		{
		}, true);
		long traced = nextInstallation;
		nextInstallation = Math.incrementExact(nextInstallation);
		retire(pid);
		nativeInstallations.add(traced);
		activeInstallations.put(pid, traced);
		nativeImages.resume(pid, birth);
	}

	/**
	 * Reads the state letter of a {@code /proc/<pid>/stat} record.
	 * @param stat the record
	 * @return the state
	 */
	private static String state(String stat)
	{
		return stat.substring(stat.lastIndexOf(')') + 2, stat.lastIndexOf(')') + 3);
	}

	/**
	 * Grants one published wait through the installation that reported it.
	 * @param wait the wait
	 * @throws IllegalStateException if the server was stopped
	 * @throws IOException           if the wait came from a traced image, which only a retry of the attempt can
	 *                               continue, or its installation is not owned or does not acknowledge the grant
	 */
	@Override
	public void grant(AllocationWaits.Wait wait) throws IOException
	{
		requireOwner();
		requireCertain();
		if (stopped)
			throw new IllegalStateException("The build hook server was stopped");
		if (nativeInstallations.contains(wait.installation()))
			throw new IOException("A traced image's denied allocation requires a retry of the attempt");
		AllocationHook hook = AllocationHooks.installation(installations, wait);
		try
		{
			hook.grant(wait, peers);
		}
		catch (IOException | RuntimeException cause)
		{
			throw failed("Build hook permission", cause);
		}
		catch (Error cause)
		{
			failed("Build hook permission", cause);
			throw cause;
		}
	}

	@Override
	public void stop()
	{
		requireOwner();
		stopped = true;
		for (AllocationHook hook : installations.values())
			hook.stop();
		for (CargoTokenHook hook : tokenInstallations.values())
			hook.stop();
	}

	/**
	 * Closes every hook, client and connection, the tracer and the listener once no process of the attempt can
	 * execute.
	 * @param barrier observation that no process of the attempt can execute
	 * @throws IOException if the barrier is not reached or cleanup fails; close may be retried only after the
	 *                     failure's cause is resolved
	 */
	@Override
	public void close(AllocationHook.ExecutionBarrier barrier) throws IOException
	{
		requireOwner();
		Objects.requireNonNull(barrier, "barrier");
		if (closeFailure != null)
			throw new IOException("Build hook server cleanup remains unresolved", closeFailure);
		if (closed)
			return;
		stop();
		if (!barrier.reached())
			throw new IOException("Build hook server cleanup requires that no process of the attempt can execute");

		IOException cleanup = null;
		for (AllocationHook hook : installations.values())
			cleanup = closeRecording(cleanup, () -> hook.close(barrier));
		for (CargoTokenHook hook : tokenInstallations.values())
			cleanup = closeRecording(cleanup, () -> hook.close(barrier));
		for (Client client : clients.values())
			cleanup = closeRecording(cleanup, client.connection()::close);
		for (Pending candidate : pending)
			cleanup = closeRecording(cleanup, candidate.connection()::close);
		cleanup = closeRecording(cleanup, nativeImages::close);
		if (listener != null)
			cleanup = closeRecording(cleanup, listener::close);
		if (cleanup != null)
		{
			closeFailure = cleanup;
			throw cleanup;
		}
		closed = true;
	}

	/**
	 * Closes one resource, recording its failure with earlier ones.
	 * @param cleanup the first earlier failure, or null
	 * @param closer  closes the resource
	 * @return the first failure, or null if none occurred
	 */
	private static IOException closeRecording(IOException cleanup, Closer closer)
	{
		try
		{
			closer.close();
			return cleanup;
		}
		catch (IOException | RuntimeException cause)
		{
			if (cleanup == null)
				return new IOException("Build hook server cleanup failed", cause);
			cleanup.addSuppressed(cause);
			return cleanup;
		}
	}

	/**
	 * Services peers and waits one interval for a client, without losing interruption.
	 * @param began   when the wait began, from {@link System#nanoTime()}
	 * @param message the failure if the timeout has elapsed
	 * @throws IOException if the timeout elapsed, peers fail or the thread is interrupted
	 */
	private void waitForClient(long began, String message) throws IOException
	{
		if (System.nanoTime() - began >= timeout.toNanos())
			throw new IOException(message);
		peers.pump();
		try
		{
			Thread.sleep(POLL_INTERVAL);
		}
		catch (InterruptedException cause)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for a build hook client", cause);
		}
	}

	/**
	 * Withdraws grants and retains the first failure.
	 * @param operation the failed operation
	 * @param cause     the failure
	 * @return the first failure
	 */
	private IOException failed(String operation, Throwable cause)
	{
		if (failure == null)
			failure = new IOException(operation + " failed", cause);
		else if (cause != failure)
			failure.addSuppressed(cause);
		stop();
		return failure;
	}

	/**
	 * Refuses another control after a failure.
	 * @throws IOException if a failure was retained
	 */
	private void requireCertain() throws IOException
	{
		if (failure != null)
			throw new IOException("The build hook server retains its first failure", failure);
	}

	/**
	 * Requires the owning thread.
	 * @throws IllegalStateException if another thread calls
	 */
	private void requireOwner()
	{
		if (Thread.currentThread() != owner)
			throw new IllegalStateException("The build hook server belongs to its constructing thread");
	}

	/**
	 * Closes one resource.
	 */
	@FunctionalInterface
	private interface Closer
	{
		/**
		 * Closes the resource.
		 * @throws IOException if closing fails
		 */
		void close() throws IOException;
	}

	/**
	 * An accepted connection that has not sent {@code hello}.
	 * @param connection the connection
	 * @param accepted   when it was accepted, from {@link System#nanoTime()}
	 */
	private record Pending(LinuxCargoTransport.Connection connection, long accepted)
	{
	}

	/**
	 * The identity of a client that belongs to the attempt.
	 * @param pid     the client's process
	 * @param started its start time in clock ticks
	 */
	private record Admission(long pid, long started)
	{
	}

	/**
	 * An admitted client.
	 * @param pid        the client's process
	 * @param started    its start time in clock ticks
	 * @param connection the client
	 */
	private record Client(long pid, long started, LinuxBuildHookClient connection)
	{
	}
}
