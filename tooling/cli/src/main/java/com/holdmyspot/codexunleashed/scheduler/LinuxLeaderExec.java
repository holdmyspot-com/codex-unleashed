package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replaces a prepared Linux gate on its verified leader thread and retains its native attachments through workload zero.
 * The constructing thread owns every transition. Callers retain the scope, device and supervisor until this owner's
 * successful closure; after start, the device routes every later child event of the Cargo tree.
 */
final class LinuxLeaderExec
{
	/**
	 * Packaged leader configuration insertion point.
	 */
	private static final String LAUNCH_MARKER = "__LAUNCH__";
	/**
	 * Exclusive controller thread.
	 */
	private final Thread owner = Thread.currentThread();
	/**
	 * Borrowed serializer owner.
	 */
	private final ToolingScope scope;
	/**
	 * Borrowed scoped leader serializer.
	 */
	private final JsonMapper json;
	/**
	 * Borrowed build device whose manager gates and resumes the Cargo root; never closed here.
	 */
	private final LinuxFridaDevice device;
	/**
	 * Immutable launch configuration.
	 */
	private final LinuxProcessTree.Launch launch;
	/**
	 * Borrowed authoritative whole-tree owner.
	 */
	private final LinuxSupervisor supervisor;
	/**
	 * Jobserver FIFO and descriptor numbers the leader installs before exec, or null when the launch has no jobserver.
	 */
	private final LinuxCargoJobserver.Descriptors jobserver;
	/**
	 * Bounded control interval in nanoseconds.
	 */
	private final long timeoutNanos;
	/**
	 * Peer service without scheduler reentry.
	 */
	private final AllocationHook.Pump peers;
	/**
	 * Borrowed device manager, null until start reaches the device.
	 */
	private FridaRuntime.Manager manager;
	/**
	 * Original gate session retained before its start.
	 */
	private FridaRuntime.Manager.Session gate;
	/**
	 * Original gate script retained before its start.
	 */
	private FridaRuntime.Manager.Session.Script gateScript;
	/**
	 * One start reservation, including uncertain failures.
	 */
	private boolean startAttempted;
	/**
	 * Terminal withdrawal of permission to start.
	 */
	private boolean stopped;
	/**
	 * Successful complete native cleanup.
	 */
	private boolean closed;
	/**
	 * First start fault prevents replay of execute or resume.
	 */
	private IOException failure;
	/**
	 * First native destruction fault retains unresolved ownership.
	 */
	private IOException closeFailure;

	/**
	 * Borrows launch and native owners without acquiring native resources.
	 * @param scope serializer scope retained until closure
	 * @param device started build device retained until closure
	 * @param launch immutable workload configuration
	 * @param jobserver FIFO and descriptor numbers to install before exec, or null when the launch has no jobserver
	 * @param supervisor prepared contained workload retained until closure
	 * @param timeout positive control interval whose nanoseconds fit in a signed long
	 * @param peers peer service while waiting for copied native events
	 * @throws NullPointerException if an input is null
	 * @throws IllegalArgumentException if {@code timeout} is nonpositive or exceeds the supported interval
	 */
	LinuxLeaderExec(ToolingScope scope, LinuxFridaDevice device, LinuxProcessTree.Launch launch,
		LinuxCargoJobserver.Descriptors jobserver,
		LinuxSupervisor supervisor, Duration timeout, AllocationHook.Pump peers)
	{
		this.scope = scope;
		this.json = scope.getLeaderJsonMapper();
		this.device = Objects.requireNonNull(device, "device");
		this.launch = Objects.requireNonNull(launch, "launch");
		this.jobserver = jobserver;
		this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
		this.timeoutNanos = LinuxAttempts.checkedTimeout(timeout).toNanos();
		this.peers = Objects.requireNonNull(peers, "peers");
	}

	/**
	 * Acquires and verifies the gate, replaces its leader and installs before resuming the held root image.
	 * @param installer attaches the held root and returns only when it is ready for resume
	 * @throws NullPointerException if {@code installer} is null
	 * @throws IllegalStateException if another thread calls or start was already attempted or stopped
	 * @throws IOException if any identity, acquisition, installation or control cannot be established
	 */
	void start(RootInstaller installer) throws IOException
	{
		requireOwner();
		Objects.requireNonNull(installer, "installer");
		requireCertain();
		if (startAttempted || stopped)
			throw new IllegalStateException("Linux leader cannot start again");
		startAttempted = true;
		try
		{
			String leader;
			try (InputStream input = LinuxLeaderExec.class.getResourceAsStream("linux-leader.js"))
			{
				if (input == null)
					throw new IOException("Packaged Linux leader program is missing");
				leader = leaderProgram(scope, new String(input.readAllBytes(), StandardCharsets.UTF_8), launch, jobserver);
			}
			long root = supervisor.rootPid();
			HeldImage gateIdentity = inspect(root, false, null);
			manager = device.manager();
			gate = manager.newSession(root);
			gate.start();
			gate.enableChildGating();
			gateScript = gate.newScript(leader);
			gateScript.start();
			awaitLeader("leader-script-ready:" + root);
			supervisor.release();
			long began = System.nanoTime();
			while (!credentialsMatch(Files.readString(proc(root).resolve("status")), launch.uid(), launch.gid()))
				waitForPeer(began, "Requested workload identity was not established");
			gateScript.post(json.createObjectNode().put("type", "execute"));
			awaitLeader("leader-enter:" + root + ":" + root + ":" + launch.uid() + ":" + launch.gid());
			// The root exec is read from the manager directly rather than through the device's routing: until this
			// method resumes Cargo, no wrapper has connected and no attempt or image controller exists to receive it.
			began = System.nanoTime();
			FridaRuntime.Child event;
			while (true)
			{
				event = manager.pollChild().orElse(null);
				if (event != null)
					break;
				waitForPeer(began, "Held root executable was not delivered");
			}
			verifyRootEvent(event, root, launch.command().getFirst());
			HeldImage image = inspect(root, true, Path.of(launch.command().getFirst()));
			if (image.started() != gateIdentity.started())
				throw new IOException("Prepared root identity changed before held exec");
			installer.install(image, manager);
			manager.resume(root);
		}
		catch (IOException | RuntimeException cause) { failed(cause); throw cause; }
		catch (Error cause) { failed(cause); throw cause; }
	}

	/**
	 * Returns the retained manager for attachment and descendant service.
	 * @return borrowed manager, or null before native acquisition
	 */
	FridaRuntime.Manager manager()
	{
		requireOwner();
		return manager;
	}

	/**
	 * Withdraws start authority without unloading code or resuming a held process.
	 * @throws IllegalStateException if called from another thread
	 */
	void stop()
	{
		requireOwner();
		stopped = true;
	}

	/**
	 * Destroys the original gate script and gate session after actual whole-workload zero; the device keeps the manager.
	 * Failed destruction remains retained and cannot be retried. Successful repeated closure is harmless.
	 * @param barrier trusted actual whole-workload-zero observation
	 * @throws NullPointerException if {@code barrier} is null
	 * @throws IllegalStateException if called from another thread
	 * @throws IOException if zero is unproven or native destruction fails or remains unresolved
	 */
	void close(AllocationHook.ExecutionBarrier barrier) throws IOException
	{
		requireOwner();
		Objects.requireNonNull(barrier, "barrier");
		if (closeFailure != null)
			throw new IOException("Leader cleanup remains unresolved", closeFailure);
		if (closed) return;
		stop();
		if (!barrier.reached())
			throw new IOException("Leader cleanup requires actual whole-workload zero");
		try
		{
			if (gateScript != null) gateScript.close();
			if (gate != null) gate.close();
			closed = true;
		}
		catch (IOException | RuntimeException cause)
		{
			closeFailure = new IOException("Native leader destruction failed", cause);
			throw cause;
		}
		catch (Error cause)
		{
			closeFailure = new IOException("Native leader destruction did not complete", cause);
			throw cause;
		}
	}

	/**
	 * Retains the first start failure and withdraws start authority.
	 * @param cause original failed native, identity or installation operation
	 * @return first contextual failure
	 */
	private IOException failed(Throwable cause)
	{
		if (failure == null) failure = new IOException("Linux leader start failed", cause);
		else if (cause != failure) failure.addSuppressed(cause);
		stop();
		return failure;
	}

	/**
	 * Rejects controls after uncertain start effects.
	 * @throws IOException if a start failure is retained
	 */
	private void requireCertain() throws IOException
	{
		if (failure != null) throw new IOException("Linux leader retains its first failure", failure);
	}

	/**
	 * Rejects access from any thread other than the constructing controller.
	 * @throws IllegalStateException if called from another thread
	 */
	private void requireOwner()
	{
		if (Thread.currentThread() != owner)
			throw new IllegalStateException("Linux leader belongs to its constructing thread");
	}

	/**
	 * Reads a stable actual process identity and checks retained cgroup membership.
	 * @param pid held process identity
	 * @param workload indicates whether unprivileged workload credentials are required
	 * @param executable expected held executable, or null for trusted gate
	 * @return verified native identity
	 * @throws IOException if identity or actual containment cannot be established
	 */
	HeldImage inspect(long pid, boolean workload, Path executable) throws IOException
	{
		requireOwner();
		Path process = proc(pid);
		long began = startTime(Files.readString(process.resolve("stat")));
		String status = Files.readString(process.resolve("status"));
		if (workload && !credentialsMatch(status, launch.uid(), launch.gid()))
			throw new IOException("Held process credentials differ from workload identity");
		long parent = status.lines().filter(line -> line.startsWith("PPid:")).mapToLong(line ->
			Long.parseLong(line.substring(5).strip())).findFirst().orElseThrow(() -> new IOException("Process PPid is missing"));
		if (pid == supervisor.rootPid() && parent != supervisor.pid())
			throw new IOException("Prepared workload root has changed supervisor parent");
		Path actual = Files.readSymbolicLink(process.resolve("exe"));
		if (executable != null && (!executable.isAbsolute() ||
			!Files.isSameFile(process.resolve("exe"), executable)))
			throw new IOException("Held executable differs from native notification: " + actual);
		String group = Files.readString(process.resolve("cgroup"));
		boolean contained = false;
		try (Stream<Path> groups = Files.walk(supervisor.directory()))
		{
			for (Path directory : groups.filter(Files::isDirectory).toList())
			{
				Path members = directory.resolve("cgroup.procs");
				if (Files.isRegularFile(members) && Files.readAllLines(members).contains(Long.toString(pid)))
				{
					contained = true;
					break;
				}
			}
		}
		if (!contained || !group.equals(Files.readString(process.resolve("cgroup"))) ||
			began != startTime(Files.readString(process.resolve("stat"))))
			throw new IOException("Held process lacks stable actual owned cgroup identity");
		return new HeldImage(pid, began, parent, actual);
	}

	/**
	 * Reads the Linux start-time field without splitting a command name containing spaces or parentheses.
	 * @param stat complete proc stat record
	 * @return positive kernel start-time ticks
	 * @throws IOException if required identity is malformed
	 */
	static long startTime(String stat) throws IOException
	{
		try
		{
			int end = stat.lastIndexOf(')');
			if (end < 0) throw new IllegalArgumentException("Missing command terminator");
			long value = Long.parseLong(stat.substring(end + 1).strip().split("\\s+")[19]);
			if (value <= 0) throw new IllegalArgumentException("Nonpositive process start time");
			return value;
		}
		catch (RuntimeException cause) { throw new IOException("Process start identity is malformed", cause); }
	}

	/**
	 * Indicates whether all four real/effective/saved/filesystem UID and GID fields match the required identity.
	 * @param status proc status record
	 * @param uid required user identity
	 * @param gid required group identity
	 * @return true only for complete exact identity
	 */
	static boolean credentialsMatch(String status, int uid, int gid)
	{
		boolean user = false;
		boolean group = false;
		for (String line : status.lines().toList())
		{
			if (line.startsWith("Uid:") || line.startsWith("Gid:"))
			{
				boolean isUser = line.startsWith("Uid:");
				String[] values = line.substring(4).strip().split("\\s+");
				if (values.length != 4 || (isUser && user) || (!isUser && group)) return false;
				int expected = isUser ? uid : gid;
				for (String value : values)
					if (!value.equals(Integer.toString(expected))) return false;
				if (isUser) user = true;
				else group = true;
			}
		}
		return user && group;
	}

	/**
	 * Validates the root replacement event before inspecting native state.
	 * @param event copied event
	 * @param root prepared root PID
	 * @param executable expected executable path
	 * @throws IOException if native event does not identify the exact root replacement
	 */
	static void verifyRootEvent(FridaRuntime.Child event, long root, String executable) throws IOException
	{
		if (event.pid() != root || event.parentPid() != root || event.origin() != 1 || !executable.equals(event.path()))
			throw new IOException("Unexpected held root replacement: " + event);
	}

	/**
	 * Embeds complete argv/environment using JSON, without shell interpolation.
	 * @param scope borrowed shared values; the caller retains ownership through all operations
	 * @param template packaged program with exactly one marker
	 * @param launch immutable launch
	 * @param jobserver FIFO and descriptor numbers the leader opens before exec, or null when the launch has no jobserver
	 * @return initialized leader program
	 * @throws IOException if marker or serialization is invalid
	 */
	static String leaderProgram(ToolingScope scope, String template, LinuxProcessTree.Launch launch,
		LinuxCargoJobserver.Descriptors jobserver) throws IOException
	{
		int marker = template.indexOf(LAUNCH_MARKER);
		if (marker < 0 || template.indexOf(LAUNCH_MARKER, marker + LAUNCH_MARKER.length()) >= 0)
			throw new IOException("Leader program must contain exactly one launch marker");
		return template.substring(0, marker) + scope.getLeaderJsonMapper().writeValueAsString(launchValues(launch, jobserver)) +
			template.substring(marker + LAUNCH_MARKER.length());
	}

	/**
	 * Returns the values a packaged leader program reads: the workload's argv and environment, and, for a Cargo-tree
	 * process, the jobserver FIFO that the leader opens on the named descriptor numbers before exec.
	 * @param launch immutable launch
	 * @param jobserver FIFO and descriptor numbers to install, or null when the launch has no jobserver
	 * @return launch values in the leader program's input form
	 */
	static Map<String, Object> launchValues(LinuxProcessTree.Launch launch, LinuxCargoJobserver.Descriptors jobserver)
	{
		Map<String, Object> values = new java.util.LinkedHashMap<>();
		values.put("command", launch.command());
		values.put("environment", launch.environment());
		if (jobserver != null)
			values.put("jobserver", Map.of("fifo", jobserver.fifo().toString(), "read", jobserver.read(), "write", jobserver.write()));
		return values;
	}

	/**
	 * Awaits one exact leader receipt, rejecting script errors and unexpected messages.
	 * @param expected exact payload
	 * @throws LeaderDispatchFailure if the leader could not dispatch the launch
	 * @throws IOException if another receipt is invalid or the receipt timed out
	 */
	private void awaitLeader(String expected) throws IOException
	{
		long began = System.nanoTime();
		while (true)
		{
			String frame = gateScript.pollMessage().orElse(null);
			if (frame != null)
			{
				JsonNode envelope = json.readTree(frame);
				if (envelope == null || !envelope.path("type").asString().equals("send") ||
					!envelope.path("payload").asString().equals(expected))
					throw LeaderDispatchFailure.unexpected(envelope == null ? "" : envelope.path("payload").asString(),
						frame);
				return;
			}
			waitForPeer(began, "Leader receipt deadline exceeded: " + expected);
		}
	}

	/**
	 * Services peers and waits a bounded interval without losing interruption.
	 * @param began monotonic operation start
	 * @param message deadline diagnostic
	 * @throws IOException if peers, timeout or interruption prevent progress
	 */
	private void waitForPeer(long began, String message) throws IOException
	{
		if (System.nanoTime() - began >= timeoutNanos) throw new IOException(message);
		peers.pump();
		try { Thread.sleep(10); }
		catch (InterruptedException cause)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while retaining held native process", cause);
		}
	}

	/**
	 * Locates the process in the retained Linux PID namespace.
	 * @param pid native process identity
	 * @return proc directory
	 */
	private static Path proc(long pid) { return Path.of("/proc", Long.toString(pid)); }
	/**
	 * Attaches one independently verified root while it remains held.
	 */
	@FunctionalInterface
	interface RootInstaller
	{
		/**
		 * Retains any partial attachment before starting it and returns only when the root is ready for resume.
		 * @param image stable native root identity, supervisor parent and executable
		 * @param manager borrowed manager that owns the held root event
		 * @throws IOException if attachment or readiness fails; the root remains held
		 */
		void install(HeldImage image, FridaRuntime.Manager manager) throws IOException;
	}

	/**
	 * Stable independently verified native image.
	 * @param pid process identity
	 * @param started positive kernel start-time ticks
	 * @param parent actual parent PID
	 * @param executable actual held executable
	 */
	record HeldImage(long pid, long started, long parent, Path executable)
	{
		/**
		 * Retains a complete independently observed process identity.
		 * @param pid positive process identity
		 * @param started positive kernel start-time ticks
		 * @param parent positive actual parent PID
		 * @param executable actual held executable
		 * @throws NullPointerException if {@code executable} is null
		 * @throws IllegalArgumentException if {@code pid}, {@code started} or {@code parent} is nonpositive
		 */
		HeldImage
		{
			Objects.requireNonNull(executable, "executable");
			if (pid <= 0 || started <= 0 || parent <= 0)
				throw new IllegalArgumentException("Held image process, start time and parent must be positive");
		}
	}
}
