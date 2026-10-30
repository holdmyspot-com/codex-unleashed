package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Owns leader gating and per-image allocation hooks for one contained Linux workload.
 * Native callbacks copy messages only. All transitions and peer service run on the constructing thread.
 * Fork-inherited hooks remain unsupported: such a child stays held and the attempt must be cancelled.
 */
final class AllocationHooks
{
	/** Serializer for leader configuration and copied envelopes. */
	private static final JsonMapper JSON = JsonMapper.builder().build();
	/** Packaged leader configuration insertion point. */
	private static final String LAUNCH_MARKER = "__LAUNCH__";
	/** Exclusive controller thread. */
	private final Thread owner = Thread.currentThread();
	/** Native attempt correlation. */
	private final UUID identity;
	/** Borrowed runtime; this owner closes only its own manager. */
	private final FridaRuntime runtime;
	/** Immutable launch configuration. */
	private final LinuxProcessTree.Launch launch;
	/** Borrowed authoritative whole-tree owner. */
	private final LinuxSupervisor supervisor;
	/** Borrowed attempt wait batches. */
	private final AllocationWaits waits;
	/** Bounded control interval. */
	private final long timeoutNanos;
	/** Peer service without queue reentry. */
	private final AllocationHook.Pump peers;
	/** Owned manager retained before native start. */
	private FridaRuntime.Manager manager;
	/** Owned original gate attachment retained before native start. */
	private FridaRuntime.Manager.Session gate;
	/** Owned original gate script retained before native start. */
	private FridaRuntime.Manager.Session.Script gateScript;
	/** Retained copied held-child events. */
	private final ArrayDeque<FridaRuntime.Child> children = new ArrayDeque<>();
	/** Every acquired installation, including partial starts and retired images. */
	private final Map<Long, AllocationHook> installations = new LinkedHashMap<>();
	/** Current verified image by PID. */
	private final Map<Long, Image> images = new LinkedHashMap<>();
	/** Next reserved installation ordinal. */
	private long nextInstallation = 1;
	/** Script text loaded after the owner was retained. */
	private String program;
	/** One start reservation. */
	private boolean startAttempted;
	/** Start/child/grant transition currently advancing; nested pumps only copy and deliver. */
	private boolean advancing;
	/** Terminal withdrawal of automatic permission authority. */
	private boolean stopped;
	/** Successful complete native cleanup. */
	private boolean closed;
	/** First delivery or control fault, retained until owner disposal. */
	private IOException failure;
	/** First actual native destruction failure. */
	private IOException closeFailure;

	/** Borrows configured build/attempt owners without native acquisition.
	 * @param identity attempt correlation
	 * @param runtime started runtime
	 * @param launch workload configuration
	 * @param supervisor prepared contained workload
	 * @param waits shared allocation batches
	 * @param timeout bounded control interval
	 * @param peers fair peer service without queue reentry
	 */
	AllocationHooks(UUID identity, FridaRuntime runtime, LinuxProcessTree.Launch launch, LinuxSupervisor supervisor,
		AllocationWaits waits, Duration timeout, AllocationHook.Pump peers)
	{
		this.identity = Objects.requireNonNull(identity, "identity");
		this.runtime = Objects.requireNonNull(runtime, "runtime");
		this.launch = Objects.requireNonNull(launch, "launch");
		this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
		this.waits = Objects.requireNonNull(waits, "waits");
		timeoutNanos = LinuxAttempts.checkedTimeout(timeout).toNanos();
		this.peers = Objects.requireNonNull(peers, "peers");
	}

	/** Acquires and verifies the gate, replaces its leader and installs before resuming the held root image.
	 * @throws IOException if any identity, acquisition or control cannot be established
	 */
	void start() throws IOException
	{
		requireOwner();
		requireCertain();
		if (startAttempted || stopped)
			throw new IllegalStateException("Instrumentation cannot start again");
		startAttempted = true;
		advancing = true;
		try
		{
			program = AllocationHook.linuxProgram();
			String leader;
			try (var input = AllocationHooks.class.getResourceAsStream("linux-leader.js"))
			{
				if (input == null)
					throw new IOException("Packaged Linux leader program is missing");
				leader = leaderProgram(new String(input.readAllBytes(), StandardCharsets.UTF_8), launch);
			}
			long root = supervisor.rootPid();
			Image gateIdentity = inspect(root, false, null);
			manager = runtime.newManager();
			manager.start();
			gate = manager.newSession(root);
			gate.start();
			gate.enableChildGating();
			gateScript = gate.newScript(leader);
			gateScript.start();
			awaitLeader("leader-script-ready:" + root);
			supervisor.release();
			long began = System.nanoTime();
			while (credentialsMatch(Files.readString(proc(root).resolve("status")), launch.uid(), launch.gid()) == false)
				waitForPeer(began, "Requested workload identity was not established");
			gateScript.post(JSON.createObjectNode().put("type", "execute"));
			awaitLeader("leader-enter:" + root + ":" + root + ":" + launch.uid() + ":" + launch.gid());
			began = System.nanoTime();
			while (children.isEmpty())
			{
				copyChildren();
				if (children.isEmpty())
					waitForPeer(began, "Held root executable was not delivered");
			}
			FridaRuntime.Child event = children.removeFirst();
			verifyRootEvent(event, root, launch.command().getFirst());
			Image image = inspect(root, true, Path.of(launch.command().getFirst()));
			if (image.started() != gateIdentity.started())
				throw new IOException("Prepared root identity changed before held exec");
			install(image);
			manager.resume(root);
		}
		catch (IOException | RuntimeException cause) { throw failed("Instrumentation start", cause); }
		catch (Error cause) { failed("Instrumentation start", cause); throw cause; }
		finally { advancing = false; }
	}

	/** Services one bounded frame round and at most one held child transition.
	 * @throws IOException if delivery, identity or unsupported fork is observed
	 */
	void pump() throws IOException
	{
		requireOwner();
		requireCertain();
		if (stopped || manager == null || closed)
			return;
		try
		{
			copyChildren();
			for (AllocationHook hook : List.copyOf(installations.values()))
				hook.pump();
			if (advancing || children.isEmpty())
				return;
			advancing = true;
			try { advanceChild(children.removeFirst()); }
			finally { advancing = false; }
		}
		catch (IOException | RuntimeException cause) { throw failed("Instrumentation delivery", cause); }
		catch (Error cause) { failed("Instrumentation delivery", cause); throw cause; }
	}

	/** Grants through the exact existing installation owner, never by PID alone.
	 * @param wait authentic wait selected by AllocationWaits
	 * @throws IOException if ownership or accepted permission cannot be established
	 */
	void grant(AllocationWaits.Wait wait) throws IOException
	{
		requireOwner();
		requireCertain();
		if (stopped || advancing)
			throw new IllegalStateException("Instrumentation cannot grant during another transition");
		AllocationHook hook = installation(installations, wait);
		advancing = true;
		try { hook.grant(wait, peers); }
		catch (IOException | RuntimeException cause) { throw failed("Native permission", cause); }
		catch (Error cause) { failed("Native permission", cause); throw cause; }
		finally { advancing = false; }
	}

	/** Withdraws grants without unloading code or resuming parked tasks. */
	void stop()
	{
		requireOwner();
		stopped = true;
		for (AllocationHook hook : installations.values())
			hook.stop();
	}

	/** Destroys owned native resources only after the authoritative whole-workload barrier.
	 * @param barrier trusted actual workload-zero observation
	 * @throws IOException if the barrier is unknown or native cleanup remains retained
	 */
	void close(AllocationHook.ExecutionBarrier barrier) throws IOException
	{
		requireOwner();
		Objects.requireNonNull(barrier, "barrier");
		if (closeFailure != null)
			throw new IOException("Instrumentation cleanup remains unresolved", closeFailure);
		if (closed)
			return;
		stop();
		if (barrier.reached() == false)
			throw new IOException("Instrumentation cleanup requires actual whole-workload zero");
		try
		{
			IOException cleanup = null;
			for (AllocationHook hook : installations.values())
			{
				try { hook.close(barrier); }
				catch (IOException cause)
				{
					if (cleanup == null) cleanup = cause;
					else cleanup.addSuppressed(cause);
				}
			}
			if (cleanup != null)
				throw cleanup;
			if (gateScript != null) gateScript.close();
			if (gate != null) gate.close();
			if (manager != null) manager.close();
			closed = true;
		}
		catch (IOException | RuntimeException cause)
		{
			closeFailure = new IOException("Native instrumentation destruction failed", cause);
			throw closeFailure;
		}
		catch (Error cause) { closeFailure = new IOException("Native instrumentation destruction did not complete", cause); throw cause; }
	}

	/** Copies a bounded number of native child events, retaining held children until validated.
	 * @throws IOException if callback transport failed
	 */
	private void copyChildren() throws IOException
	{
		for (int count = 0; count < 64; count++)
		{
			var event = manager.pollChild();
			if (event.isEmpty())
				return;
			children.addLast(event.get());
		}
	}

	/** Verifies and instruments one supported held image before resume.
	 * @param event copied held-image notification
	 * @throws IOException if unsupported or unowned
	 */
	private void advanceChild(FridaRuntime.Child event) throws IOException
	{
		verifyOrigin(event.origin());
		Image parent = images.get(event.parentPid());
		if (parent == null)
			throw new IOException("Held image has no owned parent context: " + event);
		Image previous = images.get(event.pid());
		if (previous != null)
		{
			// AllocationWaits has no safe per-installation retirement operation.
			// Conservatively reject any replacement of an installed image, including apparently quiet ones.
			throw new IOException("Replacement of an installed image is unsupported: " + event);
		}
		Image image = inspect(event.pid(), true, Path.of(event.path()));
		if (image.parent() != event.parentPid())
			throw new IOException("Held child parent differs from owned native context");
		Image currentParent = inspect(parent.pid(), true, parent.executable());
		if (currentParent.started() != parent.started())
			throw new IOException("Held child parent identity was replaced");
		install(image);
		manager.resume(event.pid());
	}

	/** Retains a fresh hook before start and awaits authentic ready before any process resume.
	 * @param image independently verified held image
	 * @throws IOException if attach or readiness fails
	 */
	private void install(Image image) throws IOException
	{
		long installation = nextInstallation;
		nextInstallation = Math.incrementExact(nextInstallation);
		AllocationHook hook = new AllocationHook(identity, installation, image.pid(), manager, waits, program,
			Duration.ofNanos(timeoutNanos));
		installations.put(installation, hook);
		images.put(image.pid(), image);
		hook.start();
		hook.awaitReady(peers);
	}

	/** Reads a stable actual process identity and checks retained cgroup membership.
	 * @param pid held process identity
	 * @param workload whether unprivileged workload credentials are required
	 * @param executable expected held executable, or null for trusted gate
	 * @return verified native identity
	 * @throws IOException if identity or actual containment cannot be established
	 */
	private Image inspect(long pid, boolean workload, Path executable) throws IOException
	{
		Path process = proc(pid);
		long began = startTime(Files.readString(process.resolve("stat")));
		String status = Files.readString(process.resolve("status"));
		if (workload && credentialsMatch(status, launch.uid(), launch.gid()) == false)
			throw new IOException("Held process credentials differ from workload identity");
		long parent = status.lines().filter(line -> line.startsWith("PPid:")).mapToLong(line ->
			Long.parseLong(line.substring(5).strip())).findFirst().orElseThrow(() -> new IOException("Process PPid is missing"));
		if (pid == supervisor.rootPid() && parent != supervisor.pid())
			throw new IOException("Prepared workload root has changed supervisor parent");
		Path actual = Files.readSymbolicLink(process.resolve("exe"));
		if (executable != null && actual.equals(executable) == false)
			throw new IOException("Held executable differs from native notification: " + actual);
		String group = Files.readString(process.resolve("cgroup"));
		boolean contained = false;
		try (var groups = Files.walk(supervisor.directory()))
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
		if (contained == false || group.equals(Files.readString(process.resolve("cgroup"))) == false ||
			began != startTime(Files.readString(process.resolve("stat"))))
			throw new IOException("Held process lacks stable actual owned cgroup identity");
		return new Image(pid, began, parent, actual);
	}

	/** Reads the Linux start-time field without splitting a command name containing spaces or parentheses.
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

	/** Checks all four real/effective/saved/filesystem UID and GID fields, preserving missing as failure.
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
				if (values.length != 4 || (isUser && user) || (isUser == false && group)) return false;
				int expected = isUser ? uid : gid;
				for (String value : values)
					if (value.equals(Integer.toString(expected)) == false) return false;
				if (isUser) user = true;
				else group = true;
			}
		}
		return user && group;
	}

	/** Validates the root replacement event before inspecting native state.
	 * @param event copied event
	 * @param root prepared root PID
	 * @param executable expected executable path
	 * @throws IOException if native event does not identify the exact root replacement
	 */
	static void verifyRootEvent(FridaRuntime.Child event, long root, String executable) throws IOException
	{
		if (event.pid() != root || event.parentPid() != root || event.origin() != 1 || executable.equals(event.path()) == false)
			throw new IOException("Unexpected held root replacement: " + event);
	}

	/** Rejects unproven fork inheritance and unknown origins without resuming held processes.
	 * @param origin native origin code
	 * @throws IOException if not a supported non-fork origin
	 */
	static void verifyOrigin(int origin) throws IOException
	{
		if (origin != 1 && origin != 2)
			throw new IOException("Held origin is unsupported; fork-inherited hooks are unproven: " + origin);
	}

	/** Finds the retained hook for an exact installation identity.
	 * @param installations retained owners by generation
	 * @param wait authentic selected native wait
	 * @return exact retained owner
	 * @throws IOException if installation is not owned
	 */
	static AllocationHook installation(Map<Long, AllocationHook> installations, AllocationWaits.Wait wait) throws IOException
	{
		AllocationHook hook = installations.get(wait.installation());
		if (hook == null) throw new IOException("Allocation wait installation is not owned: " + wait);
		return hook;
	}

	/** Embeds complete argv/environment using JSON, without shell interpolation.
	 * @param template packaged program with exactly one marker
	 * @param launch immutable launch
	 * @return initialized leader program
	 * @throws IOException if marker or serialization is invalid
	 */
	static String leaderProgram(String template, LinuxProcessTree.Launch launch) throws IOException
	{
		int marker = template.indexOf(LAUNCH_MARKER);
		if (marker < 0 || template.indexOf(LAUNCH_MARKER, marker + LAUNCH_MARKER.length()) >= 0)
			throw new IOException("Leader program must contain exactly one launch marker");
		return template.substring(0, marker) + JSON.writeValueAsString(Map.of("command", launch.command(),
			"environment", launch.environment())) + template.substring(marker + LAUNCH_MARKER.length());
	}

	/** Awaits one exact leader receipt, rejecting script errors and unexpected messages.
	 * @param expected exact payload
	 * @throws IOException if receipt is invalid or timed out
	 */
	private void awaitLeader(String expected) throws IOException
	{
		long began = System.nanoTime();
		while (true)
		{
			var frame = gateScript.pollMessage();
			if (frame.isPresent())
			{
				JsonNode envelope = JSON.readTree(frame.get());
				if (envelope == null || envelope.path("type").asString().equals("send") == false ||
					envelope.path("payload").asString().equals(expected) == false)
					throw new IOException("Unexpected leader receipt: " + frame.get());
				return;
			}
			waitForPeer(began, "Leader receipt deadline exceeded: " + expected);
		}
	}

	/** Services peers and waits a bounded interval without losing interruption.
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

	/** Locates the process in the retained Linux PID namespace.
	 * @param pid native process identity
	 * @return proc directory
	 */
	private static Path proc(long pid) { return Path.of("/proc", Long.toString(pid)); }
	/** Withdraws grants and retains the first genuine fault.
	 * @param operation failed operation
	 * @param cause retained original fault
	 * @return first contextual failure
	 */
	private IOException failed(String operation, Throwable cause)
	{
		if (failure == null) failure = new IOException(operation + " failed", cause);
		else if (cause != failure) failure.addSuppressed(cause);
		stop();
		return failure;
	}
	/** Prevents new automatic native controls after uncertainty.
	 * @throws IOException if delivery or permission is uncertain
	 */
	private void requireCertain() throws IOException
	{
		if (failure != null) throw new IOException("Instrumentation retains its first failure", failure);
	}
	/** Requires the constructing thread before effects. */
	private void requireOwner()
	{
		if (Thread.currentThread() != owner) throw new IllegalStateException("Allocation hooks belong to their constructing thread");
	}
	/** Stable independently verified native image.
	 * @param pid process identity
	 * @param started start-time ticks
	 * @param parent actual parent PID
	 * @param executable actual held executable
	 */
	private record Image(long pid, long started, long parent, Path executable) {}
}
