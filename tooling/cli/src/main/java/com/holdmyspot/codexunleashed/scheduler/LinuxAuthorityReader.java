package com.holdmyspot.codexunleashed.scheduler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * Reads memory only after establishing an actual cgroup root in its own private, nonpropagating mount namespace.
 * This entry point is launched by LinuxAuthority; invoking it directly requires the same privileged launch contract.
 * It does not launch workloads or alter their limits. A rejected observation produces no numeric result; a new batch
 * may retry on the retained helper. Lost protocol acknowledgement makes the owning authority unavailable.
 * Counters are fresh sequential observations, rather than an atomic reservation. The trusted owner must retain the
 * registered topology and avoid changing controller delegation during a sampling batch; observed changes are rejected.
 */
public final class LinuxAuthorityReader implements AutoCloseable
{
	/**
	 * Required cgroup ABI files present on the actual root as well as ordinary live groups.
	 */
	private static final List<String> REQUIRED_ROOT_FILES = List.of("cgroup.procs", "cgroup.controllers");
	/**
	 * cgroup-v2 core files marked CFTYPE_NOT_ON_ROOT by the Linux cgroup implementation.
	 */
	private static final List<String> NONROOT_FILES = List.of("cgroup.events", "cgroup.type");
	/**
	 * Required host counter paths, checked for inherited per-file bind mounts before sampling.
	 */
	private static final List<Path> HOST_FILES = List.of(Path.of("meminfo"), Path.of("vmstat"),
		Path.of("pressure/memory"), Path.of("sys/kernel/random/boot_id"));
	/**
	 * Retained native syscall boundary, confined to this reader's main thread.
	 */
	private final NativeCalls calls = new NativeCalls();
	/**
	 * Final private mountpoint through which libcgroup and residency reads resolve paths.
	 */
	private final Path cgroupRoot;
	/**
	 * Borrowed proc mount with the same PID and cgroup namespace view as this reader.
	 */
	private final Path procRoot;
	/**
	 * Actual group in which the scheduler may admit new work.
	 */
	private final Path admissionParent;
	/**
	 * Actual helper group used to verify proc membership against the final complete mount.
	 */
	private final Path helperGroup;
	/**
	 * Open clean mount root, retained until reader exit.
	 */
	private Descriptor root;
	/**
	 * Open proc root for mount-constrained file validation.
	 */
	private Descriptor proc;
	/**
	 * Public library owner used to verify its selected mount throughout the session.
	 */
	private Libcgroup library;
	/**
	 * Accepted candidate's unchanged numeric and availability implementation.
	 */
	private CgroupBudget budget;
	/**
	 * Retained admission ancestry, independently required even when no attempt is live.
	 */
	private Chain admission;
	/**
	 * Exact registered supervisor attempts and retained actual group chains.
	 */
	private final Map<LinuxMeasurements.Attempt, Workload> workloads = new HashMap<>();
	/**
	 * Identity of the private mount namespace established by the launcher before JVM startup.
	 */
	private Object mountNamespace;
	/**
	 * Identity of the cgroup namespace whose proc path mapping is checked at setup.
	 */
	private Object cgroupNamespace;
	/**
	 * Identity of the PID namespace matching the selected proc view.
	 */
	private Object pidNamespace;

	/**
	 * Retains path settings before acquiring handles.
	 * @param cgroupRoot final private cgroup mountpoint
	 * @param procRoot matching proc mountpoint
	 * @param admissionParent scheduler admission group
	 * @param helperGroup owned reader group
	 */
	private LinuxAuthorityReader(Path cgroupRoot, Path procRoot, Path admissionParent, Path helperGroup)
	{
		for (Path path : new Path[] {cgroupRoot, procRoot, admissionParent, helperGroup})
		{
			Objects.requireNonNull(path, "reader path");
			if (!path.isAbsolute() || !path.normalize().equals(path))
				throw new IllegalArgumentException("Reader paths must be absolute and normalized: " + path);
		}
		if (!admissionParent.startsWith(cgroupRoot) || !helperGroup.startsWith(cgroupRoot))
			throw new IllegalArgumentException("Reader admission and containment must be inside its cgroup mount");
		this.cgroupRoot = cgroupRoot;
		this.procRoot = procRoot;
		this.admissionParent = admissionParent;
		this.helperGroup = helperGroup;
	}

	/**
	 * Acquires a nonrecursive clean mount and opens the accepted library only after final mount selection.
	 * @param cgroupRoot configured cgroup mountpoint
	 * @param procRoot matching proc mountpoint
	 * @param admissionParent scheduler admission group
	 * @param helperGroup reader containment group
	 * @param nativeLibrary libcgroup shared object
	 * @return owned complete reader
	 * @throws IOException if visibility, mount selection, native capabilities or a required observation is unavailable
	 */
	private static LinuxAuthorityReader open(Path cgroupRoot, Path procRoot, Path admissionParent, Path helperGroup,
		Path nativeLibrary) throws IOException
	{
		LinuxAuthorityReader reader = new LinuxAuthorityReader(cgroupRoot, procRoot, admissionParent, helperGroup);
		try
		{
			reader.calls.requireUnfiltered();
			if (!cgroupRoot.toRealPath().equals(cgroupRoot) || !procRoot.toRealPath().equals(procRoot))
				throw new IOException("Configured mountpoints must resolve without aliases");
			try (Descriptor source = reader.calls.openDirectory(cgroupRoot))
			{
				reader.calls.requireFilesystem(source, NativeCalls.CGROUP2_MAGIC);
				reader.root = reader.calls.cloneMount(source);
			}
			// unshare --mount --propagation private ran before JVM startup. No global mount is changed here.
			reader.calls.installMount(reader.root, cgroupRoot);
			reader.calls.requireSamePath(reader.root, cgroupRoot);
			reader.requireActualRoot();
			reader.proc = reader.calls.openDirectory(procRoot);
			reader.calls.requireFilesystem(reader.proc, NativeCalls.PROC_MAGIC);
			reader.mountNamespace = namespace(procRoot, "mnt");
			reader.cgroupNamespace = namespace(procRoot, "cgroup");
			reader.pidNamespace = namespace(procRoot, "pid");
			long pid = ProcessHandle.current().pid();
			if (!Files.isSameFile(procRoot.resolve("self/ns/pid"), procRoot.resolve(pid + "/ns/pid")) ||
				!Files.isSameFile(procRoot.resolve("self/ns/pid"), procRoot.resolve("1/ns/pid")))
				throw new IOException("Selected proc filesystem does not expose the reader's PID namespace");
			Path actual = reader.membership(pid);
			if (!actual.equals(helperGroup))
				throw new IOException("Reader proc membership does not map to its contained group: " + actual);
			reader.library = Libcgroup.open(nativeLibrary);
			reader.verifySelectedMount();
			reader.budget = CgroupBudget.open(nativeLibrary);
			reader.admission = reader.holdChain(admissionParent);
			reader.verify();
			return reader;
		}
		catch (IOException | RuntimeException | Error failure)
		{
			try
			{
				reader.close();
			}
			catch (IOException cleanup)
			{
				failure.addSuppressed(cleanup);
			}
			throw failure;
		}
	}

	/**
	 * Runs the contained helper after its parent releases the one-byte setup gate.
	 * @param arguments IPC directory, cgroup root, proc root, admission group, library, helper group, and parent PID
	 * @throws Exception if setup or session cleanup fails
	 */
	public static void main(String[] arguments) throws Exception
	{
		if (arguments.length != 7)
			throw new IllegalArgumentException("Expected seven authority-reader launch arguments");
		Path directory = Path.of(arguments[0]);
		NativeCalls bootstrap = new NativeCalls();
		bootstrap.bindParent(Long.parseLong(arguments[6]));
		if (System.in.read() != 1)
			throw new IOException("Authority setup gate was not released by its parent");
		bootstrap.requirePrivateNamespace(Path.of(arguments[2]), Long.parseLong(arguments[6]));
		try (LinuxAuthorityReader reader = open(Path.of(arguments[1]), Path.of(arguments[2]), Path.of(arguments[3]),
			Path.of(arguments[5]), Path.of(arguments[4])))
		{
			Properties ready = new Properties();
			ready.setProperty("status", "ok");
			LinuxAuthority.publish(directory.resolve(LinuxAuthority.READY_FILE), ready);
			long sequence = 1;
			while (true)
			{
				Path request = directory.resolve("request-" + sequence + ".properties");
				while (!Files.exists(request))
					LinuxAuthority.pause();
				Properties result;
				try
				{
					result = reader.execute(LinuxAuthority.load(request));
					result.setProperty("status", "ok");
				}
				catch (IOException | RuntimeException failure)
				{
					result = rejection(failure);
					failure.printStackTrace(System.err);
				}
				LinuxAuthority.publish(directory.resolve("response-" + sequence + ".properties"), result);
				sequence = Math.incrementExact(sequence);
			}
		}
		catch (IOException | RuntimeException failure)
		{
			Properties result = new Properties();
			result.setProperty("status", "unavailable");
			result.setProperty("error", failure.toString());
			if (!Files.exists(directory.resolve(LinuxAuthority.READY_FILE)))
				LinuxAuthority.publish(directory.resolve(LinuxAuthority.READY_FILE), result);
			throw failure;
		}
	}

	/**
	 * Serializes a rejection only after its complete reader-side trust checks have finished.
	 * @param failure rejected request diagnostic
	 * @return unavailable reply, including exact removed-leaf metadata only for verified candidates
	 */
	private static Properties rejection(Throwable failure)
	{
		var result = new Properties();
		result.setProperty("status", "unavailable");
		result.setProperty("error", failure.toString());
		if (failure instanceof RemovedLeaf removed)
		{
			result.setProperty("reason", LinuxAuthority.MISSING_LEAF);
			result.setProperty("count", Integer.toString(removed.attempts.size()));
			for (int index = 0; index < removed.attempts.size(); index++)
			{
				var attempt = removed.attempts.get(index);
				result.setProperty("name." + index, attempt.name());
				result.setProperty("attempt." + index, Long.toString(attempt.sequence()));
			}
		}
		return result;
	}

	/**
	 * Executes one request against this retained namespace and registry.
	 * @param request ordinal-bound protocol document
	 * @return response fields, without status until execution succeeds
	 * @throws IOException if a required observation or exact mapping is unavailable
	 */
	private Properties execute(Properties request) throws IOException
	{
		try
		{
			return executeChecked(request);
		}
		catch (NativeFailure failure)
		{
			if (failure.errno == 2 && failure.getSuppressed().length == 0 &&
				isObservation(request.getProperty("operation", "")))
			{
				try { verifyObservation(); }
				catch (RemovedLeaf removed)
				{
					removed.addSuppressed(failure);
					throw removed;
				}
			}
			throw failure;
		}
	}

	/**
	 * Executes one request between complete authority checks.
	 * @param request ordinal-bound request
	 * @return fields of a complete successful response
	 * @throws IOException if observation or request execution fails
	 */
	private Properties executeChecked(Properties request) throws IOException
	{
		Properties result = new Properties();
		String operation = request.getProperty("operation", "");
		switch (operation)
		{
			case "unregister" ->
			{
				LinuxMeasurements.Attempt attempt = attempt(request);
				Workload workload = workloads.get(attempt);
				if (workload == null)
					throw new IOException("Attempt is not registered: " + attempt);
				// Completed supervisors may already have removed their inner leaf. Releasing held FDs needs no path lookup.
				workload.chain().close();
				workloads.remove(attempt);
				return result;
			}
			default -> verifyForOperation(operation);
		}
		switch (operation)
		{
			case "register" ->
			{
				register(request);
				return result;
			}
			case "host" ->
			{
				verifyHostFiles();
				LinuxMemory.Sample memory = LinuxMemory.read(procRoot);
				long available = readBudget(memory.availableBytes(), admission);
				for (Workload workload : workloads.values())
					available = Math.min(available, readBudget(memory.availableBytes(), workload.chain()));
				verifyHostFiles();
				result.setProperty("total", Long.toString(memory.totalBytes()));
				result.setProperty("hostAvailable", Long.toString(memory.availableBytes()));
				result.setProperty("swapTotal", Long.toString(memory.swapTotalBytes()));
				result.setProperty("swapUsed", Long.toString(memory.swapUsedBytes()));
				result.setProperty("swapOut", Long.toString(memory.swapOutPages()));
				result.setProperty("stall", Long.toString(memory.stallMicros()));
				result.setProperty("available", Long.toString(available));
				result.setProperty("boot", Files.readString(procRoot.resolve("sys/kernel/random/boot_id")).strip());
			}
			case "resident" ->
			{
				LinuxMeasurements.Attempt attempt = attempt(request);
				Workload workload = workloads.get(attempt);
				if (workload == null)
					throw new IOException("Attempt is not registered: " + attempt);
				LinuxResidency.Sample sample = LinuxResidency.read(cgroupRoot, procRoot, workload.directory());
				result.setProperty("resident", Long.toString(sample.residentBytes()));
			}
			case "verify" -> { }
			default -> throw new IOException("Unsupported authority request: " + request.getProperty("operation"));
		}
		verifyForOperation(operation);
		return result;
	}

	/**
	 * Indicates whether an operation only observes memory authority.
	 * @param operation request discriminator
	 * @return true for host, residency and complete-batch verification
	 */
	private static boolean isObservation(String operation)
	{
		return switch (operation)
		{
			case "host", "resident", "verify" -> true;
			default -> false;
		};
	}

	/**
	 * Applies strict mutation verification or observation-transition verification.
	 * @param operation request discriminator
	 * @throws IOException if authority is unavailable
	 */
	private void verifyForOperation(String operation) throws IOException
	{
		if (isObservation(operation)) verifyObservation();
		else verify();
	}

	/**
	 * Verifies all authority before reporting a supported removed-leaf observation.
	 * @throws IOException if any trust check fails or a registered leaf was removed
	 */
	private void verifyObservation() throws IOException
	{
		ArrayList<Check> checks = new ArrayList<>();
		for (var entry : workloads.entrySet())
			checks.add(() -> verifyWorkloadTransition(entry.getKey(), entry.getValue()));
		verifyObservation(() -> { verifyContext(); verifyHostFiles(); }, checks);
	}

	/**
	 * Checks global authority before and after all workloads, including a deferred missing-leaf candidate.
	 * @param context global namespace, mapping and admission check
	 * @param checks complete registered-workload checks
	 * @throws IOException if a required trust check fails or a supported leaf was removed
	 */
	private static void verifyObservation(Check context, List<Check> checks) throws IOException
	{
		context.verify();
		RemovedLeaf removed = null;
		try { verifyWorkloads(checks); }
		catch (RemovedLeaf candidate) { removed = candidate; }
		context.verify();
		if (removed != null) throw removed;
	}

	/**
	 * Verifies a workload, permitting only nomination of its exact absent immediate leaf.
	 * @param attempt registered action and ordinal
	 * @param workload retained native ownership
	 * @throws IOException if trust changed or the verified immediate leaf was removed
	 */
	private void verifyWorkloadTransition(LinuxMeasurements.Attempt attempt, Workload workload) throws IOException
	{
		boolean immediateLeaf = workload.directory().equals(workload.leaf().getParent());
		if (immediateLeaf && isLeafAbsent(workload.leaf()))
		{
			Held leaf = workload.chain().groups().getFirst();
			if (!leaf.path().equals(workload.leaf()) || !leaf.identity().equals(calls.identity(leaf.descriptor())))
				throw new IOException("Retained workload leaf identity changed: " + workload.leaf());
			Chain chain = workload.chain();
			verifyChain(new Chain(chain.groups().subList(1, chain.groups().size()),
				chain.ancestors().subList(1, chain.ancestors().size())));
			verifyTopology(workload, true);
			throw new RemovedLeaf(attempt);
		}
		verifyChain(workload.chain());
		verifyTopology(workload);
	}

	/**
	 * Indicates whether opening the exact registered leaf reports ENOENT.
	 * @param leaf exact immediate workload leaf in the retained clean mount
	 * @return true only for failed path acquisition with native ENOENT
	 * @throws IOException if acquisition fails differently or an acquired descriptor cannot close
	 */
	private boolean isLeafAbsent(Path leaf) throws IOException
	{
		Descriptor current;
		try { current = calls.openBelow(root, cgroupRoot.relativize(leaf), true); }
		catch (NativeFailure absent)
		{
			if (absent.errno == 2) return true;
			throw absent;
		}
		try (current) { return false; }
	}

	/**
	 * Registers the real gated workload path rather than a caller-supplied ancestor list.
	 * @param request action identity, outer group and gated root PID
	 * @throws IOException if the group is outside admission, replaced, or mismatched with proc membership
	 */
	private void register(Properties request) throws IOException
	{
		LinuxMeasurements.Attempt attempt = attempt(request);
		if (workloads.containsKey(attempt))
			throw new IOException("Attempt is already registered: " + attempt);
		Path directory = Path.of(request.getProperty("directory"));
		if (!directory.isAbsolute() || !directory.normalize().equals(directory) ||
			!directory.startsWith(admissionParent) || directory.equals(admissionParent))
			throw new IOException("Workload group must be a strict descendant of the admission parent: " + directory);
		long pid = LinuxAuthority.number(request, "pid");
		if (pid == 0)
			throw new IOException("Workload root PID must be positive");
		if (!Files.isSameFile(procRoot.resolve("self/ns/pid"), procRoot.resolve(pid + "/ns/pid")))
			throw new IOException("Workload PID belongs to a different PID namespace: " + pid);
		Path leaf = membership(pid);
		if (!leaf.startsWith(directory) || leaf.equals(directory))
			throw new IOException("Gated root is not in a child of its supervisor's owned group: " + leaf);
		Chain chain = holdChain(leaf);
		try
		{
			Workload workload = new Workload(directory, leaf, chain);
			verifyTopology(workload);
			if (!membership(pid).equals(leaf))
				throw new IOException("Gated workload membership changed during registration");
			verifyChain(chain);
			workloads.put(attempt, workload);
		}
		catch (IOException | RuntimeException | Error failure)
		{
			try
			{
				chain.close();
			}
			catch (IOException cleanup)
			{
				failure.addSuppressed(cleanup);
			}
			throw failure;
		}
	}

	/**
	 * Converts the exact unified-v2 proc record to a path in the verified complete mount.
	 * @param pid process in the reader's PID namespace
	 * @return actual mapped group path
	 * @throws IOException if the record is unavailable, hybrid, relative, or not losslessly representable
	 */
	private Path membership(long pid) throws IOException
	{
		String record = Files.readString(procRoot.resolve(pid + "/cgroup"));
		if (!record.startsWith("0::/") || !record.endsWith("\n") || record.indexOf('\n') != record.length() - 1)
			throw new IOException("Process has no exact unified-v2 membership record: " + pid);
		String text = record.substring(3, record.length() - 1);
		Path group = Path.of(text);
		if (!group.normalize().equals(group) || !group.toString().equals(text))
			throw new IOException("Process group path is not normalized and losslessly representable: " + pid);
		return cgroupRoot.resolve(Path.of("/").relativize(group));
	}

	/**
	 * Requires the two nonroot sentinels to be absent on the held live clean root.
	 * Required core files are read again after sentinel observations to reject cgroup deletion interleavings.
	 * @throws IOException if this is a nonroot group or the observation is incomplete
	 */
	private void requireActualRoot() throws IOException
	{
		calls.requireFilesystem(root, NativeCalls.CGROUP2_MAGIC);
		for (String name : REQUIRED_ROOT_FILES)
			calls.readCore(root, Path.of(name));
		boolean[] absent = new boolean[NONROOT_FILES.size()];
		for (int index = 0; index < NONROOT_FILES.size(); index++)
		{
			try
			{
				calls.readCore(root, Path.of(NONROOT_FILES.get(index)));
			}
			catch (NativeFailure failure)
			{
				if (failure.errno != 2)
					throw failure;
				absent[index] = true;
			}
		}
		for (String name : REQUIRED_ROOT_FILES)
			calls.readCore(root, Path.of(name));
		if (!absent[0] || !absent[1])
			throw new IOException("Clean cgroup mount does not identify the actual complete hierarchy root");
	}

	/**
	 * Gives focused tests typed access to native ABI selection and the held-directory classifier without changing mounts.
	 */
	static final class SharedSecrets
	{
		/**
		 * Exercises the production all-workload verification decision.
		 * @param checks controlled workload observations
		 * @throws IOException if any required observation fails
		 */
		static void verifyWorkloads(List<Check> checks) throws IOException
		{
			LinuxAuthorityReader.verifyWorkloads(checks);
		}
		/**
		 * Exercises global checks around the complete workload observation decision.
		 * @param context global authority check
		 * @param checks complete controlled workload observations
		 * @throws IOException if authority or a registered observation is unavailable
		 */
		static void verifyObservation(Check context, List<Check> checks) throws IOException
		{
			LinuxAuthorityReader.verifyObservation(context, checks);
		}
		/**
		 * Models the already-verified missing-leaf outcome, not native absence detection.
		 * @param attempt registered action identity
		 * @return modeled reader outcome
		 */
		static IOException removedLeaf(LinuxMeasurements.Attempt attempt)
		{
			return new RemovedLeaf(attempt);
		}
		/**
		 * Exercises the actual rejection serializer after controlled workload checks.
		 * @param failure observation result to serialize
		 * @return structured failed reply
		 */
		static Properties rejection(IOException failure) { return LinuxAuthorityReader.rejection(failure); }

		/**
		 * Prevents construction of the resource-free test facade.
		 */
		private SharedSecrets()
		{
		}

		/**
		 * Selects public open flags without acquiring native resources.
		 * @param architecture supported JVM architecture name
		 * @param directory whether a directory is required
		 * @return architecture-specific flags
		 * @throws UnsupportedOperationException if this architecture is unsupported
		 */
		static int openFlags(String architecture, boolean directory)
		{
			return NativeCalls.openFlags(architecture, directory);
		}

		/**
		 * Runs the actual held-directory classifier on one owned descriptor; it never clones or changes a mount.
		 * @param directory directory whose actual-root evidence is being checked
		 * @throws IOException if this directory cannot establish actual-root evidence
		 */
		static void requireActualRoot(Path directory) throws IOException
		{
			try (LinuxAuthorityReader reader = new LinuxAuthorityReader(directory, directory, directory, directory))
			{
				reader.root = reader.calls.openDirectory(directory);
				reader.requireActualRoot();
			}
		}
	}

	/**
	 * Retains every contiguous ancestor through the proven actual root.
	 * @param leaf actual group in the clean mount
	 * @return owned group descriptors with the unchanged candidate's matching request chain
	 * @throws IOException if an ancestor cannot be opened without crossing mounts or symlinks
	 */
	private Chain holdChain(Path leaf) throws IOException
	{
		if (!leaf.startsWith(cgroupRoot) || !leaf.normalize().equals(leaf))
			throw new IOException("Group is outside the complete cgroup mount: " + leaf);
		ArrayList<Held> held = new ArrayList<>();
		ArrayList<CgroupBudget.Ancestor> ancestors = new ArrayList<>();
		Chain chain = new Chain(held, ancestors);
		try
		{
			Path current = leaf;
			while (true)
			{
				Path relative = cgroupRoot.relativize(current);
				Descriptor descriptor = calls.openBelow(root, relative, true);
				held.add(new Held(current, descriptor, calls.identity(descriptor)));
				boolean actualRoot = current.equals(cgroupRoot);
				ancestors.add(new CgroupBudget.Ancestor(Path.of("/").resolve(relative), actualRoot ?
					CgroupBudget.Requirement.NO_LIMIT_BY_AUTHORITY : CgroupBudget.Requirement.LIMIT_REQUIRED));
				if (actualRoot)
					break;
				current = current.getParent();
			}
			return chain;
		}
		catch (IOException | RuntimeException | Error failure)
		{
			try
			{
				chain.close();
			}
			catch (IOException cleanup)
			{
				failure.addSuppressed(cleanup);
			}
			throw failure;
		}
	}

	/**
	 * Invokes the accepted public budget reader between retained ancestry identity checks.
	 * @param hostAvailable fresh host available bytes
	 * @param chain complete retained actual ancestry
	 * @return effective nonnegative bytes; a measured zero is valid
	 * @throws IOException if an identity or required candidate reading is unavailable
	 */
	private long readBudget(long hostAvailable, Chain chain) throws IOException
	{
		verifyChain(chain);
		List<String> delegation = readDelegation(chain);
		ArrayList<CgroupBudget.Ancestor> ancestors = new ArrayList<>();
		for (int index = 0; index < chain.ancestors().size(); index++)
		{
			CgroupBudget.Ancestor ancestor = chain.ancestors().get(index);
			boolean actualRoot = index == chain.ancestors().size() - 1;
			boolean localMemory = !actualRoot && List.of(delegation.get(index).strip().split("\\s+")).contains("memory");
			ancestors.add(new CgroupBudget.Ancestor(ancestor.group(), localMemory ?
				CgroupBudget.Requirement.LIMIT_REQUIRED : CgroupBudget.Requirement.NO_LIMIT_BY_AUTHORITY));
		}
		CgroupBudget.Sample sample = budget.read(hostAvailable, ancestors, CgroupBudget.Visibility.COMPLETE_BY_AUTHORITY);
		if (!delegation.equals(readDelegation(chain)))
			throw new IOException("Memory-controller delegation changed during ancestor sampling");
		verifyChain(chain);
		if (sample.status() != CgroupBudget.Status.COMPLETE)
			throw new IOException("Required ancestor budget reading is unavailable: " + sample);
		return sample.availableBytes();
	}

	/**
	 * Reads each actual parent's controller delegation before deciding whether its child must expose memory files.
	 * The kernel creates child controller interfaces only while that controller is enabled in its parent's subtree_control.
	 * @param chain retained complete actual ancestors, leaf first
	 * @return required parent delegation records, one per nonroot child
	 * @throws IOException if a required core observation is unavailable
	 */
	private List<String> readDelegation(Chain chain) throws IOException
	{
		ArrayList<String> states = new ArrayList<>();
		for (int index = 1; index < chain.groups().size(); index++)
			states.add(calls.readCore(chain.groups().get(index).descriptor(), Path.of("cgroup.subtree_control")));
		return List.copyOf(states);
	}

	/**
	 * Checks the retained namespace, clean root, selected library mount and all registered group mappings.
	 * @throws IOException if context or any required identity changed
	 */
	private void verify() throws IOException
	{
		verifyContext();
		ArrayList<Check> checks = new ArrayList<>();
		for (Workload workload : workloads.values())
			checks.add(() -> { verifyChain(workload.chain()); verifyTopology(workload); });
		verifyWorkloads(checks);
	}

	/**
	 * Verifies the reader context independently of individual workload lifetimes.
	 * @throws IOException if global authority changed
	 */
	private void verifyContext() throws IOException
	{
		calls.requireUnfiltered();
		if (!mountNamespace.equals(namespace(procRoot, "mnt")) || !cgroupNamespace.equals(namespace(procRoot, "cgroup")) ||
			!pidNamespace.equals(namespace(procRoot, "pid")))
			throw new IOException("Memory reader namespace identity changed");
		calls.requireSamePath(root, cgroupRoot);
		calls.requireSamePath(proc, procRoot);
		requireActualRoot();
		verifySelectedMount();
		if (!membership(ProcessHandle.current().pid()).equals(helperGroup))
			throw new IOException("Memory reader left its contained mapped group");
		verifyChain(admission);
	}

	/**
	 * One complete authority or workload observation.
	 */
	@FunctionalInterface
	interface Check
	{
		/**
		 * Verifies one observation.
		 * @throws IOException if it cannot establish its required authority
		 */
		void verify() throws IOException;
	}

	/**
	 * A verified missing registered leaf, awaiting all other authority checks.
	 */
	private static final class RemovedLeaf extends IOException
	{
		/**
		 * Registered action whose immediate leaf is absent.
		 */
		private final List<LinuxMeasurements.Attempt> attempts;
		/**
		 * Retains the exact action identity without implying completion.
		 * @param attempt registered action and process-attempt ordinal
		 */
		private RemovedLeaf(LinuxMeasurements.Attempt attempt)
		{
			this(List.of(attempt));
		}

		/**
		 * Retains every verified candidate after checking the complete registry.
		 * @param attempts exact distinct candidates, without supplying completion authority
		 */
		private RemovedLeaf(List<LinuxMeasurements.Attempt> attempts)
		{
			super("Registered workload leaves were removed: " + attempts);
			this.attempts = List.copyOf(attempts);
		}
	}

	/**
	 * Verifies every workload before granting any observation-transition classification.
	 * @param checks checks in the current complete registry
	 * @throws IOException if a required observation fails
	 */
	private static void verifyWorkloads(List<Check> checks) throws IOException
	{
		var attempts = new ArrayList<LinuxMeasurements.Attempt>();
		for (Check check : checks)
		{
			try { check.verify(); }
			catch (RemovedLeaf candidate) { attempts.addAll(candidate.attempts); }
		}
		if (!attempts.isEmpty()) throw new RemovedLeaf(attempts);
	}

	/**
	 * Rejects any library-selected pathname that is not the retained clean root and mount.
	 * @throws IOException if libcgroup selected an alias or a different mount
	 */
	private void verifySelectedMount() throws IOException
	{
		Path selected = library.selectedMemoryMount();
		if (!selected.equals(cgroupRoot))
			throw new IOException("libcgroup selected a different memory mount: " + selected);
		calls.requireSamePath(root, selected);
	}

	/**
	 * Checks retained group identities and live required core files before and after pathname-based library calls.
	 * @param chain retained contiguous ancestors
	 * @throws IOException if a path was replaced, crossed mounts, or its group was removed
	 */
	private void verifyChain(Chain chain) throws IOException
	{
		for (Held held : chain.groups())
		{
			try (Descriptor current = calls.openBelow(root, cgroupRoot.relativize(held.path()), true))
			{
				if (!held.identity().equals(calls.identity(current)) || !held.identity().equals(calls.identity(held.descriptor())))
					throw new IOException("Retained cgroup mapping changed: " + held.path());
				calls.readCore(held.descriptor(), Path.of("cgroup.procs"));
				calls.readCore(held.descriptor(), Path.of("cgroup.controllers"));
			}
		}
	}

	/**
	 * Requires the supervisor's known outer-to-gated-leaf topology; unknown descendants cannot hide tighter limits.
	 * @param workload exact registered topology
	 * @throws IOException if another group appeared or a registered path disappeared
	 */
	private void verifyTopology(Workload workload) throws IOException
	{
		verifyTopology(workload, false);
	}

	/**
	 * Verifies exact topology after a separately confirmed immediate-leaf removal.
	 * @param workload retained registered ownership
	 * @param leafRemoved indicates whether the immediate registered leaf was confirmed absent
	 * @throws IOException if any other topology changed
	 */
	private void verifyTopology(Workload workload, boolean leafRemoved) throws IOException
	{
		Set<Path> allowed = new HashSet<>();
		for (Path current = workload.leaf(); current.startsWith(workload.directory()); current = current.getParent())
			if (!(leafRemoved && current.equals(workload.leaf()))) allowed.add(current);
		Set<Path> observed = new HashSet<>();
		ArrayList<Path> pending = new ArrayList<>();
		pending.add(workload.directory());
		while (!pending.isEmpty())
		{
			Path current = pending.removeLast();
			if (!allowed.contains(current))
				throw new IOException("Registered workload has an unregistered descendant cgroup: " + current);
			try (Descriptor checked = calls.openBelow(root, cgroupRoot.relativize(current), true);
				var entries = Files.newDirectoryStream(current))
			{
				observed.add(current);
				for (Path entry : entries)
					if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
						pending.add(entry);
			}
		}
		if (!observed.equals(allowed))
			throw new IOException("Registered workload cgroup topology is incomplete: " + workload.directory());
	}

	/**
	 * Rejects proc counter overlays before a pathname-based host read.
	 * @throws IOException if a required file crosses a mount or is not a proc regular file
	 */
	private void verifyHostFiles() throws IOException
	{
		for (Path path : HOST_FILES)
			calls.readCore(proc, path);
	}

	/**
	 * Reads a proc namespace inode identity without treating PID1 equality as initial-namespace authority.
	 * @param procRoot matching proc root
	 * @param name namespace kind
	 * @return underlying namespace identity
	 * @throws IOException if namespace identity is unavailable
	 */
	private static Object namespace(Path procRoot, String name) throws IOException
	{
		Object identity = Files.readAttributes(procRoot.resolve("self/ns/" + name),
			java.nio.file.attribute.BasicFileAttributes.class).fileKey();
		if (identity == null)
			throw new IOException("Proc filesystem did not expose namespace identity: " + name);
		return identity;
	}

	/**
	 * Reads the exact action and ordinal identity from the IPC request.
	 * @param request action fields
	 * @return validated attempt identity
	 */
	private static LinuxMeasurements.Attempt attempt(Properties request)
	{
		return new LinuxMeasurements.Attempt(request.getProperty("name"), LinuxAuthority.number(request, "attempt"));
	}

	/**
	 * Retained workload group scope.
	 * @param directory borrowed supervisor-owned outer group
	 * @param leaf actual gated root group
	 * @param chain retained complete ancestry
	 */
	private record Workload(Path directory, Path leaf, Chain chain) { }

	/**
	 * One bound cgroup pathname.
	 * @param path path in the private clean mount
	 * @param descriptor retained group handle
	 * @param identity device, inode and mount identity captured on acquisition
	 */
	private record Held(Path path, Descriptor descriptor, Identity identity) { }

	/**
	 * Owns a contiguous list of actual ancestor handles.
	 * @param groups owned descriptors, appended only during acquisition
	 * @param ancestors matching accepted-budget requests, appended only during acquisition
	 */
	private record Chain(List<Held> groups, List<CgroupBudget.Ancestor> ancestors) implements AutoCloseable
	{
		/**
		 * Releases every ancestor descriptor even when another close reports failure.
		 * @throws IOException if a descriptor close fails
		 */
		@Override
		public void close() throws IOException
		{
			IOException failure = null;
			for (Held held : groups)
			{
				try
				{
					held.descriptor().close();
				}
				catch (IOException thrown)
				{
					if (failure == null) failure = thrown;
					else failure.addSuppressed(thrown);
				}
			}
			if (failure != null) throw failure;
		}
	}

	/**
	 * Releases all native owners; the process exit also destroys its private mount namespace.
	 * @throws IOException if a descriptor close fails
	 */
	@Override
	public void close() throws IOException
	{
		IOException failure = null;
		ArrayList<AutoCloseable> owners = new ArrayList<>();
		for (Workload workload : workloads.values()) owners.add(workload.chain());
		if (admission != null) owners.add(admission);
		if (budget != null) owners.add(budget);
		if (library != null) owners.add(library);
		if (proc != null) owners.add(proc);
		if (root != null) owners.add(root);
		for (AutoCloseable owner : owners)
		{
			try
			{
				owner.close();
			}
			catch (Exception thrown)
			{
				IOException current = thrown instanceof IOException io ? io : new IOException("Native reader cleanup failed", thrown);
				if (failure == null) failure = current;
				else failure.addSuppressed(current);
			}
		}
		if (failure != null) throw failure;
	}

	/**
	 * Device/inode plus mount identity, preventing a same-filesystem bind alias from satisfying path ownership.
	 * @param deviceMajor backing device major number
	 * @param deviceMinor backing device minor number
	 * @param inode filesystem inode
	 * @param mountId mount identity within this retained namespace
	 * @param mode kernel file kind and permissions
	 */
	private record Identity(int deviceMajor, int deviceMinor, long inode, long mountId, short mode) { }

	/**
	 * Reports the exact Linux errno without converting unavailable observations into absence.
	 */
	private static final class NativeFailure extends IOException
	{
		/**
		 * errno returned by the failing invocation, captured by the JDK.
		 */
		private final int errno;

		/**
		 * Preserves the failed operation and its captured errno.
		 * @param operation operation and safe argument context
		 * @param errno captured Linux errno
		 */
		private NativeFailure(String operation, int errno)
		{
			super(operation + " failed with errno " + errno);
			this.errno = errno;
		}
	}

	/**
	 * Owns one Linux file descriptor; Linux close errors never justify retrying a possibly reused number.
	 */
	private static final class Descriptor implements AutoCloseable
	{
		/**
		 * Native owner performing the final close.
		 */
		private final NativeCalls calls;
		/**
		 * Open file descriptor, or -1 after the single close attempt.
		 */
		private int value;

		/**
		 * Takes ownership of one successfully opened descriptor.
		 * @param calls native boundary used for release
		 * @param value nonnegative descriptor
		 */
		private Descriptor(NativeCalls calls, int value)
		{
			if (value < 0)
				throw new IllegalArgumentException("Owned descriptor must be nonnegative");
			this.calls = Objects.requireNonNull(calls, "calls");
			this.value = value;
		}

		/**
		 * Releases the descriptor exactly once.
		 * @throws IOException if Linux reports a close failure
		 */
		@Override
		public void close() throws IOException
		{
			if (value < 0) return;
			int closing = value;
			value = -1;
			calls.checked(calls.close, "close", closing);
		}
	}

	/**
	 * Linux x64/ARM64 public libc and stable syscall ABI; no private kernel memory or BPF is read.
	 */
	private static final class NativeCalls
	{
		/**
		 * Linux cgroup-v2 superblock magic from the public filesystem ABI.
		 */
		private static final long CGROUP2_MAGIC = 0x63677270;
		/**
		 * Linux proc filesystem superblock magic from the public filesystem ABI.
		 */
		private static final long PROC_MAGIC = 0x9fa0;
		/**
		 * Native directory-open flags for the running supported architecture.
		 */
		private final int directoryFlags = openFlags(System.getProperty("os.arch"), true);
		/**
		 * Linux statx mask requiring basic inode/type information plus a mount ID.
		 */
		private static final int STATX_MASK = 0x7ff | 0x1000;
		/**
		 * AT_EMPTY_PATH for statx and open_tree on an already-held descriptor.
		 */
		private static final int AT_EMPTY_PATH = 0x1000;
		/**
		 * Fixed openat2 syscall number in the supported Linux x64 and ARM64 ABIs.
		 */
		private static final long OPENAT2 = 437;
		/**
		 * Prevents accidental unbounded reads of malformed or unexpected core files.
		 */
		private static final int MAX_CORE_BYTES = 1024 * 1024;
		/**
		 * Linux-native linker and libc symbol lookup.
		 */
		private final Linker linker = Linker.nativeLinker();
		/**
		 * Offset of captured errno in the JDK call-state layout.
		 */
		private final long errnoOffset = Linker.Option.captureStateLayout().byteOffset(MemoryLayout.PathElement.groupElement("errno"));
		/**
		 * libc openat, used only for the initial configured mount directories.
		 */
		private final MethodHandle openat = bind("openat", FunctionDescriptor.of(ValueLayout.JAVA_INT,
			ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT), -1);
		/**
		 * Public libc nonrecursive mount-clone operation.
		 */
		private final MethodHandle openTree = bind("open_tree", FunctionDescriptor.of(ValueLayout.JAVA_INT,
			ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT), -1);
		/**
		 * Public libc operation attaching the retained detached mount.
		 */
		private final MethodHandle moveMount = bind("move_mount", FunctionDescriptor.of(ValueLayout.JAVA_INT,
			ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT), -1);
		/**
		 * Public libc private-namespace detach operation.
		 */
		private final MethodHandle unmount = bind("umount2", FunctionDescriptor.of(ValueLayout.JAVA_INT,
			ValueLayout.ADDRESS, ValueLayout.JAVA_INT), -1);
		/**
		 * Public libc mount operation used only to make the already separate namespace nonpropagating.
		 */
		private final MethodHandle mount = bind("mount", FunctionDescriptor.of(ValueLayout.JAVA_INT,
			ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS), -1);
		/**
		 * Public libc filesystem-type observation.
		 */
		private final MethodHandle statfs = bind("fstatfs", FunctionDescriptor.of(ValueLayout.JAVA_INT,
			ValueLayout.JAVA_INT, ValueLayout.ADDRESS), -1);
		/**
		 * Public libc stable 256-byte statx ABI, including mount identity.
		 */
		private final MethodHandle statx = bind("statx", FunctionDescriptor.of(ValueLayout.JAVA_INT,
			ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS), -1);
		/**
		 * libc syscall, used only for openat2 because libc provides no named wrapper.
		 */
		private final MethodHandle syscall = bind("syscall", FunctionDescriptor.of(ValueLayout.JAVA_LONG,
			ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG), 1);
		/**
		 * Public libc descriptor read.
		 */
		private final MethodHandle read = bind("read", FunctionDescriptor.of(ValueLayout.JAVA_LONG,
			ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG), -1);
		/**
		 * Public libc single-attempt descriptor close.
		 */
		private final MethodHandle close = bind("close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), -1);
		/**
		 * Public libc prctl for parent-death containment and seccomp capability checking.
		 */
		private final MethodHandle prctl = bind("prctl", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
			ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG), 1);
		/**
		 * Public libc direct parent identity, checked after installing the parent-death signal.
		 */
		private final MethodHandle getppid = bind("getppid", FunctionDescriptor.of(ValueLayout.JAVA_INT), -1);

		/**
		 * Selects O_DIRECTORY and O_NOFOLLOW from the architecture's public fcntl ABI.
		 * @param architecture supported JVM architecture name
		 * @param directory whether the open must require a directory
		 * @return O_CLOEXEC, O_NOFOLLOW and optional O_DIRECTORY
		 * @throws UnsupportedOperationException if this architecture is unsupported
		 */
		private static int openFlags(String architecture, boolean directory)
		{
			int noFollow = switch (architecture)
			{
				case "amd64", "x86_64" -> 0400000;
				case "aarch64" -> 0100000;
				default -> throw new UnsupportedOperationException("Native authority supports Linux x64 and ARM64 only");
			};
			int directoryFlag = architecture.equals("aarch64") ? 040000 : 0200000;
			return 02000000 | noFollow | (directory ? directoryFlag : 0);
		}

		/**
		 * Resolves the supported Linux ABI before native state changes.
		 * @throws UnsupportedOperationException if the running ABI is outside Linux x64 or ARM64
		 */
		private NativeCalls()
		{
			String architecture = System.getProperty("os.arch");
			boolean supported = switch (architecture) { case "amd64", "x86_64", "aarch64" -> true; default -> false; };
			if (!System.getProperty("os.name").equals("Linux") || !supported)
				throw new UnsupportedOperationException("Native authority supports Linux x64 and ARM64 only");
		}

		/**
		 * Resolves one public libc function with per-call errno capture.
		 * @param name libc symbol
		 * @param descriptor native signature
		 * @param variadic first variadic argument, or -1 for fixed arguments
		 * @return bound operation
		 */
		private MethodHandle bind(String name, FunctionDescriptor descriptor, int variadic)
		{
			MemorySegment symbol = linker.defaultLookup().find(name)
				.orElseThrow(() -> new UnsupportedOperationException("Required public libc symbol is missing: " + name));
			if (variadic >= 0)
				return linker.downcallHandle(symbol, descriptor, Linker.Option.captureCallState("errno"),
					Linker.Option.firstVariadicArg(variadic));
			return linker.downcallHandle(symbol, descriptor, Linker.Option.captureCallState("errno"));
		}

		/**
		 * Invokes a libc operation and preserves its errno on failure.
		 * @param operation bound operation
		 * @param description operation and safe argument context
		 * @param arguments native arguments after the captured-state pointer
		 * @return nonnegative operation result
		 * @throws IOException if the native invocation or operation fails
		 */
		private long checked(MethodHandle operation, String description, Object... arguments) throws IOException
		{
			try (Arena arena = Arena.ofConfined())
			{
				MemorySegment state = arena.allocate(Linker.Option.captureStateLayout());
				ArrayList<Object> values = new ArrayList<>();
				values.add(state);
				values.addAll(List.of(arguments));
				long result;
				try
				{
					result = ((Number) operation.invokeWithArguments(values)).longValue();
				}
				catch (RuntimeException | Error failure)
				{
					throw failure;
				}
				catch (Throwable failure)
				{
					throw new IOException("Native invocation failed: " + description, failure);
				}
				if (result < 0)
					throw new NativeFailure(description, state.get(ValueLayout.JAVA_INT, errnoOffset));
				return result;
			}
		}

		/**
		 * Installs SIGKILL on direct-parent death and checks the launch race before awaiting the release gate.
		 * @param parent expected direct parent PID in this unchanged PID namespace
		 * @throws IOException if parent binding cannot be established
		 */
		private void bindParent(long parent) throws IOException
		{
			checked(prctl, "prctl(PR_SET_PDEATHSIG)", 1, 9L, 0L, 0L, 0L);
			if (checked(getppid, "getppid") != parent)
				throw new IOException("Authority reader's direct parent changed before containment");
		}

		/**
		 * Requires a mount namespace distinct from the launcher and makes only that namespace nonpropagating.
		 * @param procRoot matching proc filesystem
		 * @param parent retained direct launcher PID
		 * @throws IOException if namespace isolation cannot be established
		 */
		private void requirePrivateNamespace(Path procRoot, long parent) throws IOException
		{
			if (Files.isSameFile(procRoot.resolve("self/ns/mnt"), procRoot.resolve(parent + "/ns/mnt")))
				throw new IOException("Reader must start in a mount namespace separate from its retained parent");
			try (Arena arena = Arena.ofConfined())
			{
				// MS_REC | MS_PRIVATE affects this namespace's cloned mount objects before any detach.
				checked(mount, "mount(MS_REC|MS_PRIVATE)", MemorySegment.NULL, arena.allocateFrom("/"),
					MemorySegment.NULL, 16384L | (1L << 18), MemorySegment.NULL);
			}
		}

		/**
		 * Rejects filtered syscall results rather than treating synthesized ENOENT as actual-root evidence.
		 * @throws IOException if seccomp mode is unavailable or nonzero
		 */
		private void requireUnfiltered() throws IOException
		{
			long mode = checked(prctl, "prctl(PR_GET_SECCOMP)", 21, 0L, 0L, 0L, 0L);
			if (mode != 0)
				throw new IOException("Authority root identification requires an unfiltered reader; seccomp mode=" + mode);
		}

		/**
		 * Opens one configured directory without following its final symlink.
		 * @param path absolute configured mountpoint
		 * @return owned descriptor
		 * @throws IOException if opening fails
		 */
		private Descriptor openDirectory(Path path) throws IOException
		{
			try (Arena arena = Arena.ofConfined())
			{
				return new Descriptor(this, (int) checked(openat, "openat " + path, -100, arena.allocateFrom(path.toString()), directoryFlags));
			}
		}

		/**
		 * Clones only this mount, excluding inherited subordinate and per-file bind mounts.
		 * @param source held cgroup mount directory
		 * @return retained detached mount descriptor
		 * @throws IOException if the kernel or current privilege cannot clone the mount
		 */
		private Descriptor cloneMount(Descriptor source) throws IOException
		{
			try (Arena arena = Arena.ofConfined())
			{
				// OPEN_TREE_CLONE | OPEN_TREE_CLOEXEC | AT_EMPTY_PATH; deliberately omit AT_RECURSIVE.
				int flags = 1 | 02000000 | AT_EMPTY_PATH;
				return new Descriptor(this, (int) checked(openTree, "open_tree(nonrecursive clone)", source.value,
					arena.allocateFrom(""), flags));
			}
		}

		/**
		 * Replaces the inherited mount tree only inside the launcher's private namespace.
		 * @param clone retained detached clean mount
		 * @param path configured mountpoint
		 * @throws IOException if detach or move fails
		 */
		private void installMount(Descriptor clone, Path path) throws IOException
		{
			try (Arena arena = Arena.ofConfined())
			{
				checked(unmount, "umount2(MNT_DETACH) " + path, arena.allocateFrom(path.toString()), 2);
				checked(moveMount, "move_mount(clean clone) " + path, clone.value, arena.allocateFrom(""), -100,
					arena.allocateFrom(path.toString()), 4);
			}
		}

		/**
		 * Opens a relative descendant through openat2 without mount crossings, symlinks or parent escape.
		 * @param parent retained root or group directory
		 * @param relative normalized descendant path, empty for the parent itself
		 * @param directory indicates whether a directory is required
		 * @return owned descriptor
		 * @throws IOException if openat2 is unsupported or the observation violates the resolution constraints
		 */
		private Descriptor openBelow(Descriptor parent, Path relative, boolean directory) throws IOException
		{
			if (relative.isAbsolute() || !relative.normalize().equals(relative) || relative.startsWith(".."))
				throw new IOException("Native relative path escapes its retained root: " + relative);
			try (Arena arena = Arena.ofConfined())
			{
				MemorySegment how = arena.allocate(24, 8);
				how.set(ValueLayout.JAVA_LONG, 0, (long) (directory ? directoryFlags : openFlags(System.getProperty("os.arch"), false)));
				how.set(ValueLayout.JAVA_LONG, 8, 0);
				// RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS | RESOLVE_NO_XDEV.
				how.set(ValueLayout.JAVA_LONG, 16, 8L | 4L | 1L);
				String text = relative.toString().isEmpty() ? "." : relative.toString();
				return new Descriptor(this, (int) checked(syscall, "openat2 " + relative, OPENAT2, parent.value,
					arena.allocateFrom(text), how, 24L));
			}
		}

		/**
		 * Reads one regular core file bound to the same mount as its retained parent.
		 * @param parent retained cgroup or proc root/group
		 * @param relative required core file path
		 * @return strictly decoded UTF-8 contents
		 * @throws IOException if the required observation fails, is overlaid, or exceeds one MiB
		 */
		private String readCore(Descriptor parent, Path relative) throws IOException
		{
			try (Descriptor file = openBelow(parent, relative, false); Arena arena = Arena.ofConfined())
			{
				Identity parentIdentity = identity(parent);
				Identity fileIdentity = identity(file);
				if ((fileIdentity.mode() & 0170000) != 0100000 || fileIdentity.mountId() != parentIdentity.mountId())
					throw new IOException("Required core file is not regular on the retained mount: " + relative);
				MemorySegment buffer = arena.allocate(8192);
				ByteArrayOutputStream output = new ByteArrayOutputStream();
				while (true)
				{
					long size = checked(read, "read " + relative, file.value, buffer, buffer.byteSize());
					if (size == 0) break;
					if (output.size() + size > MAX_CORE_BYTES)
						throw new IOException("Core file exceeds the supported one-MiB observation bound: " + relative);
					output.writeBytes(buffer.asSlice(0, size).toArray(ValueLayout.JAVA_BYTE));
				}
				try
				{
					return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
						.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
				}
				catch (CharacterCodingException failure)
				{
					throw new IOException("Core file cannot be losslessly decoded: " + relative, failure);
				}
			}
		}

		/**
		 * Reads stable public statx identity fields from an already held descriptor.
		 * @param descriptor held object
		 * @return device/inode, mount and file kind
		 * @throws IOException if required statx fields are unavailable
		 */
		private Identity identity(Descriptor descriptor) throws IOException
		{
			try (Arena arena = Arena.ofConfined())
			{
				MemorySegment values = arena.allocate(256, 8);
				checked(statx, "statx(AT_EMPTY_PATH)", descriptor.value, arena.allocateFrom(""), AT_EMPTY_PATH,
					STATX_MASK, values);
				int mask = values.get(ValueLayout.JAVA_INT, 0);
				if ((mask & (0x1000 | 0x100 | 1)) != (0x1000 | 0x100 | 1))
					throw new IOException("statx did not return inode, type and mount identity: mask=" + mask);
				return new Identity(values.get(ValueLayout.JAVA_INT, 136), values.get(ValueLayout.JAVA_INT, 140),
					values.get(ValueLayout.JAVA_LONG, 32), values.get(ValueLayout.JAVA_LONG, 144), values.get(ValueLayout.JAVA_SHORT, 28));
			}
		}

		/**
		 * Requires a pathname to retain the held object's inode and exact mount identity.
		 * @param descriptor retained root
		 * @param path current configured or library-selected path
		 * @throws IOException if the path changed, was overlaid, or became unreadable
		 */
		private void requireSamePath(Descriptor descriptor, Path path) throws IOException
		{
			try (Descriptor current = openDirectory(path))
			{
				if (!identity(descriptor).equals(identity(current)))
					throw new IOException("Retained root no longer matches its selected mount pathname: " + path);
			}
		}

		/**
		 * Checks the public Linux filesystem type on a held descriptor.
		 * @param descriptor retained filesystem object
		 * @param expected required Linux superblock magic
		 * @throws IOException if fstatfs fails or reports another filesystem
		 */
		private void requireFilesystem(Descriptor descriptor, long expected) throws IOException
		{
			try (Arena arena = Arena.ofConfined())
			{
				MemorySegment values = arena.allocate(256, 8);
				checked(statfs, "fstatfs", descriptor.value, values);
				long actual = values.get(ValueLayout.JAVA_LONG, 0);
				if (actual != expected)
					throw new IOException("Required filesystem magic " + expected + " differs from observed " + actual);
			}
		}
	}
}
