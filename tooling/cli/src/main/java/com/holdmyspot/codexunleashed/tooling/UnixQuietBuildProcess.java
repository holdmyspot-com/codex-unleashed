package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Owns Linux spawn, raw completion and group cleanup for {@link QuietBuildLauncher}, without a JVM process reaper.
 */
public final class UnixQuietBuildProcess implements QuietBuildProcess
{
	/**
	 * Missing executable or path component.
	 */
	private static final int ENOENT = 2;
	/**
	 * POSIX fcntl descriptor-flags query from the native header oracle.
	 */
	private static final int F_GETFD = 1;
	/**
	 * POSIX error for a closed descriptor from the native header oracle.
	 */
	private static final int EBADF = 9;

	/**
	 * A path component is not a directory.
	 */
	private static final int ENOTDIR = 20;

	/**
	 * Native operation interrupted before completion.
	 */
	private static final int EINTR = 4;

	/**
	 * Owned group no longer exists.
	 */
	private static final int ESRCH = 3;

	/**
	 * Caller lacks permission to signal the group.
	 */
	private static final int EPERM = 1;

	/**
	 * Observe child state without blocking the invocation thread.
	 */
	private static final int WNOHANG = 1;

	/**
	 * Cooperative group termination signal.
	 */
	private static final int SIGTERM = 15;

	/**
	 * Forced group termination signal.
	 */
	private static final int SIGKILL = 9;

	/**
	 * Linux posix_spawn starts a new session.
	 */
	private static final int SETSID = 128;

	/**
	 * Linux posix_spawn applies the supplied child mask.
	 */
	private static final int SETSIGMASK = 8;

	/**
	 * Linux posix_spawn restores selected default dispositions.
	 */
	private static final int SETSIGDEF = 4;
	/**
	 * Darwin posix_spawn starts a new session.
	 */
	private static final int DARWIN_SETSID = 0x0400;
	/**
	 * Darwin posix_spawn closes descriptors not named in file actions.
	 */
	private static final int DARWIN_CLOEXEC_DEFAULT = 0x4000;

	/**
	 * One bounded TERM or KILL grace period.
	 */
	private static final Duration GRACE = Duration.ofSeconds(10);
	/**
	 * Maximum interval between group observations.
	 */
	private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

	/**
	 * Preserves PATH empty entries, relative cwd resolution, and the default Python POSIX search path.
	 *
	 * @param executable  executable name or explicit path
	 * @param cwd         absolute child cwd
	 * @param environment complete child environment
	 * @return ordered PATH candidates, or the sole explicit path
	 */
	public static List<Path> candidates(String executable, Path cwd, Map<String, String> environment)
	{
		if (executable.contains("/"))
			return List.of(cwd.resolve(executable));
		String path = environment.getOrDefault("PATH", "/bin:/usr/bin");
		List<Path> result = new ArrayList<>();
		for (String entry : path.split(":", -1))
			result.add(cwd.resolve(entry).resolve(executable));
		return result;
	}

	/**
	 * Native integer operation on one opaque object.
	 */
	@FunctionalInterface
	public interface Unary
	{
		/**
		 * Invokes the fixed native signature.
		 *
		 * @param first native object or pointer argument
		 * @return native result; errno-based calls capture errno in the supplied state
		 */
		int invoke(MemorySegment first);
	}
	/**
	 * Native integer operation on two pointers.
	 */
	@FunctionalInterface
	public interface Pointers
	{
		/**
		 * Invokes the fixed native signature.
		 *
		 * @param first  native object or pointer argument
		 * @param second second native argument
		 * @return native result; errno-based calls capture errno in the supplied state
		 */
		int invoke(MemorySegment first, MemorySegment second);
	}
	/**
	 * Native integer operation on an object and integer.
	 */
	@FunctionalInterface
	public interface IntegerArgument
	{
		/**
		 * Invokes the fixed native signature.
		 *
		 * @param first  native object or pointer argument
		 * @param second second native argument
		 * @return native result; errno-based calls capture errno in the supplied state
		 */
		int invoke(MemorySegment first, int second);
	}
	/**
	 * Native integer operation on an object and short flags.
	 */
	@FunctionalInterface
	public interface ShortArgument
	{
		/**
		 * Invokes the fixed native signature.
		 *
		 * @param first  native object or pointer argument
		 * @param second second native argument
		 * @return native result; errno-based calls capture errno in the supplied state
		 */
		int invoke(MemorySegment first, short second);
	}
	/**
	 * Spawn's fixed pointer signature and direct error result.
	 */
	@FunctionalInterface
	public interface Spawn
	{
		/**
		 * Invokes the fixed native signature.
		 *
		 * @param pid         owned child PID or output pointer
		 * @param path        NUL-terminated executable path
		 * @param actions     initialized file actions
		 * @param attributes  initialized spawn attributes
		 * @param arguments   NUL-terminated argv vector
		 * @param environment NUL-terminated environment vector
		 * @return native result; errno-based calls capture errno in the supplied state
		 */
		int invoke(MemorySegment pid, MemorySegment path, MemorySegment actions, MemorySegment attributes,
			MemorySegment arguments, MemorySegment environment);
	}
	/**
	 * Waitpid with immediately captured errno.
	 */
	@FunctionalInterface
	public interface Wait
	{
		/**
		 * Invokes the fixed native signature.
		 *
		 * @param state   captured native call state containing errno
		 * @param pid     owned child PID or output pointer
		 * @param status  output wait-status pointer
		 * @param options waitpid option flags
		 * @return native result; errno-based calls capture errno in the supplied state
		 */
		int invoke(MemorySegment state, int pid, MemorySegment status, int options);
	}
	/**
	 * Kill with immediately captured errno.
	 */
	@FunctionalInterface
	public interface Kill
	{
		/**
		 * Invokes the fixed native signature.
		 *
		 * @param state  captured native call state containing errno
		 * @param pid    owned child PID or output pointer
		 * @param signal platform signal number
		 * @return native result; errno-based calls capture errno in the supplied state
		 */
		int invoke(MemorySegment state, int pid, int signal);
	}
	/**
	 * Signal-set operation with immediately captured errno.
	 */
	@FunctionalInterface
	public interface SignalEmpty
	{
		/**
		 * Invokes the fixed native signature.
		 *
		 * @param state   captured native call state containing errno
		 * @param signals native signal-set storage
		 * @return native result; errno-based calls capture errno in the supplied state
		 */
		int invoke(MemorySegment state, MemorySegment signals);
	}
	/**
	 * Signal-add operation with immediately captured errno.
	 */
	@FunctionalInterface
	public interface SignalAdd
	{
		/**
		 * Invokes the fixed native signature.
		 *
		 * @param state   captured native call state containing errno
		 * @param signals native signal-set storage
		 * @param signal  platform signal number
		 * @return native result; errno-based calls capture errno in the supplied state
		 */
		int invoke(MemorySegment state, MemorySegment signals, int signal);
	}

	/**
	 * Starts the supported POSIX child with direct error and owned PID transfer.
	 *
	 * @param binding                   invocation-scoped immutable native calls
	 * @param command                   validated command with literal arguments
	 * @param workingDirectory          child cwd
	 * @param environment               complete child environment
	 * @param closedStandardDescriptors standard descriptors closed at launcher ingress
	 * @return fully owned child after native spawn-state cleanup
	 * @throws IOException                   if launch or native state cleanup fails
	 * @throws UnsupportedOperationException if the ABI or required libc function is unavailable
	 */
	public static UnixQuietBuildProcess start(Binding binding, List<String> command, Path workingDirectory,
		Map<String, String> environment, List<Integer> closedStandardDescriptors) throws IOException
	{
		Path cwd = workingDirectory.toAbsolutePath();
		UnixQuietBuildProcess owner = null;
		try (Arena arena = Arena.ofConfined();
			SpawnState state = new SpawnState(binding, arena))
		{
			state.configure(cwd, closedStandardDescriptors);
			MemorySegment argv = strings(arena, binding.pointer, command);
			List<String> variables = new ArrayList<>();
			for (Map.Entry<String, String> variable : environment.entrySet())
				variables.add(variable.getKey() + "=" + variable.getValue());
			MemorySegment envp = strings(arena, binding.pointer, variables);
			MemorySegment pid = arena.allocate(binding.integer);
			int firstError = 0;
			for (Path candidate : candidates(command.getFirst(), cwd, environment))
			{
				int error = binding.spawn.invoke(pid, arena.allocateFrom(candidate.toString()), state.actions,
					state.attributes, argv, envp);
				if (error == 0)
				{
					int child = pid.get(binding.integer, 0);
					if (child <= 0)
						throw new IOException("posix_spawn returned nonpositive child PID " + child);
					owner = new UnixQuietBuildProcess(binding, child, cwd, Map.copyOf(environment));
					break;
				}
				firstError = selectLaunchError(firstError, error);
			}
			if (owner == null)
			{
				if (firstError == 0)
					firstError = ENOENT;
				throw new IOException("Cannot launch " + command.getFirst() + ": errno=" + firstError);
			}
		}
		catch (IOException | RuntimeException failure)
		{
			// Destroying spawn state must not strand the child after posix_spawn transferred ownership.
			if (owner != null)
			{
				try
				{
					owner.close();
				}
				catch (IOException cleanupFailure)
				{
					failure.addSuppressed(cleanupFailure);
				}
			}
			throw failure;
		}
		return owner;
	}

	/**
	 * Retains the first non-search error while later executable candidates remain eligible to succeed.
	 *
	 * @param previous retained error, or zero
	 * @param current  native error from this candidate
	 * @return first error other than missing-file/not-a-directory, or zero
	 */
	public static int selectLaunchError(int previous, int current)
	{
		if (previous == 0 && current != ENOENT && current != ENOTDIR)
			return current;
		return previous;
	}

	/**
	 * Decodes a terminal POSIX wait status using the native-header macro contract.
	 *
	 * @param raw status returned by waitpid without stopped/continued options
	 * @return distinct ordinary exit or terminating signal
	 * @throws IOException if the status is nonterminal
	 */
	public static Completion decodeWaitStatus(int raw) throws IOException
	{
		int signal = raw & 0x7f;
		if (signal == 0)
			return new UnixExit((raw >>> 8) & 0xff);
		if (signal == 0x7f)
			throw new IOException("waitpid returned nonterminal status " + raw);
		return new UnixSignal(signal, (raw & 0x80) != 0);
	}

	/**
	 * Native descriptor query with immediately captured errno.
	 */
	@FunctionalInterface
	public interface Fcntl
	{
		/**
		 * Queries descriptor flags without changing the descriptor.
		 *
		 * @param state      captured native call state containing errno
		 * @param descriptor standard descriptor number
		 * @param command    header-measured F_GETFD command
		 * @return descriptor flags, or minus one with captured errno
		 */
		int invoke(MemorySegment state, int descriptor, int command);
	}

	/**
	 * Immutable platform binding borrowed throughout the owner's lifecycle.
	 */
	private final Binding binding;
	/**
	 * Positive root PID, also the new process group's ID.
	 */
	private final int root;
	/**
	 * Complete environment for the established sudo cleanup route.
	 */
	private final Map<String, String> environment;
	/**
	 * Child cwd used for cleanup helpers.
	 */
	private final Path workingDirectory;
	/**
	 * Cached terminal child result; null while the direct child is running.
	 */
	private Completion completion;
	/**
	 * Once true, never signal this group number again.
	 */
	private boolean groupGone;

	/**
	 * Constructs the sole reaper for a successfully started child.
	 *
	 * @param binding          borrowed native calls
	 * @param root             owned positive process-group leader PID
	 * @param workingDirectory absolute child cwd
	 * @param environment      copied complete child environment
	 */
	private UnixQuietBuildProcess(Binding binding, int root, Path workingDirectory, Map<String, String> environment)
	{
		this.binding = binding;
		this.root = root;
		this.workingDirectory = workingDirectory;
		this.environment = environment;
	}

	@Override
	public long pid()
	{
		return root;
	}

	@Override
	public Optional<Completion> poll() throws IOException
	{
		if (completion != null)
			return Optional.of(completion);
		try (Arena arena = Arena.ofConfined())
		{
			MemorySegment status = arena.allocate(binding.integer);
			MemorySegment errors = arena.allocate(binding.state);
			while (true)
			{
				int result = binding.wait.invoke(errors, root, status, WNOHANG);
				int error = binding.errno(errors);
				if (result == root)
				{
					int raw = status.get(binding.integer, 0);
					completion = decodeWaitStatus(raw);
					return Optional.of(completion);
				}
				if (result == 0)
					return Optional.empty();
				if (error != EINTR)
					throw new IOException("waitpid for owned PID " + root + ": errno=" + error);
			}
		}
	}

	@Override
	public Completion await() throws IOException, InterruptedException
	{
		while (poll().isEmpty())
			Thread.sleep(Duration.ofMillis(10));
		return completion;
	}

	@Override
	public StopOutcome stop() throws IOException, InterruptedException
	{
		return stop(null);
	}

	@Override
	public void close() throws IOException
	{
		if (groupGone && completion != null)
			return;
		try (Cleanup cleanup = new Cleanup())
		{
			StopOutcome outcome = stop(cleanup);
			if (poll().isEmpty() || outcome.state() != GroupState.GONE)
				throw new IOException("Owned build cleanup incomplete: " + outcome);
		}
		catch (InterruptedException failure)
		{
			// Noninterruptible cleanup records interrupts inside pause and helper waits instead of propagating them.
			Thread.currentThread().interrupt();
			throw new IOException("Owned build cleanup unexpectedly interrupted: " + root, failure);
		}
	}

	/**
	 * Terminates the owned group with one monotonic grace period per signal, even during interrupted close.
	 *
	 * @param cleanup noninterruptible cleanup scope, or null for interruptible waits
	 * @return observed group state and diagnostics
	 * @throws IOException          if a native observation fails
	 * @throws InterruptedException if an interruptible operation is interrupted
	 */
	private StopOutcome stop(Cleanup cleanup) throws IOException, InterruptedException
	{
		if (groupGone)
			return new StopOutcome(GroupState.GONE, List.of());
		for (int signal : new int[] {SIGTERM, SIGKILL})
		{
			GroupState result = signal(signal, cleanup);
			if (result != null)
				return outcome(result, "Cannot signal owned group " + root);
			long started = System.nanoTime();
			while (System.nanoTime() - started < GRACE.toNanos())
			{
				poll();
				result = signal(0, cleanup);
				if (result != null)
					return outcome(result, "Cannot inspect owned group " + root);
				pause(POLL_INTERVAL, cleanup);
			}
		}
		return new StopOutcome(GroupState.GRACE_EXPIRED, List.of("Owned group remains after TERM/KILL grace: " + root));
	}

	/**
	 * Keeps denial diagnostics without describing a disappeared group as a failure.
	 *
	 * @param state      observed group state
	 * @param diagnostic denial diagnostic
	 * @return cleanup outcome
	 */
	private static StopOutcome outcome(GroupState state, String diagnostic)
	{
		if (state == GroupState.GONE)
			return new StopOutcome(state, List.of());
		return new StopOutcome(state, List.of(diagnostic));
	}

	/**
	 * Waits within the current grace period; interrupted close continues cleanup with the same deadline.
	 *
	 * @param duration maximum pause
	 * @param cleanup  noninterruptible cleanup scope, or null for interruptible waits
	 * @throws InterruptedException if the interruptible wait is interrupted
	 */
	private static void pause(Duration duration, Cleanup cleanup) throws InterruptedException
	{
		try
		{
			Thread.sleep(duration);
		}
		catch (InterruptedException failure)
		{
			if (cleanup == null)
				throw failure;
			cleanup.interrupted = true;
		}
	}

	/**
	 * Signals only this owned group; null indicates the operation succeeded and the group may remain.
	 *
	 * @param signal  signal number, or zero to inspect existence
	 * @param cleanup noninterruptible cleanup scope, or null for interruptible waits
	 * @return terminal group state, or null if the group may still exist
	 * @throws IOException          if native observation or helper cleanup fails
	 * @throws InterruptedException if an interruptible helper wait is interrupted
	 */
	private GroupState signal(int signal, Cleanup cleanup) throws IOException, InterruptedException
	{
		if (groupGone)
			return GroupState.GONE;
		try (Arena arena = Arena.ofConfined())
		{
			MemorySegment errors = arena.allocate(binding.state);
			int result = binding.kill.invoke(errors, -root, signal);
			int error = binding.errno(errors);
			if (result == 0)
				return null;
			if (error == ESRCH)
			{
				groupGone = true;
				return GroupState.GONE;
			}
			if (error == EPERM && signal == 0)
			{
				// A child can exit between waitpid(WNOHANG) and kill(-pgid, 0). On Darwin the
				// transient zombie may make the existence probe report EPERM until we reap it.
				for (int attempt = 0; attempt < 3; ++attempt)
				{
					poll();
					result = binding.kill.invoke(errors, -root, 0);
					error = binding.errno(errors);
					if (result == 0)
						return null;
					if (error == ESRCH)
					{
						groupGone = true;
						return GroupState.GONE;
					}
					if (error != EPERM)
						break;
					if (attempt < 2)
						pause(POLL_INTERVAL, cleanup);
				}
			}
			if (error != EPERM)
				throw new IOException("Cannot signal owned group " + root + ": errno=" + error);
		}
		ProcessBuilder helper = SystemCommands.createBuilder(List.of("sudo", "-n", "kill", "-" + signal, "--", "-" + root)).
			directory(workingDirectory.toFile()).inheritIO();
		helper.environment().clear();
		helper.environment().putAll(environment);
		try (Helper helperProcess = new Helper(helper.start()))
		{
			if (!waitForHelper(helperProcess.process, GRACE, cleanup))
				return GroupState.ACCESS_DENIED;
			if (helperProcess.process.exitValue() == 0)
				return null;
			return GroupState.ACCESS_DENIED;
		}
	}

	/**
	 * Waits for an owned helper without extending its deadline when cleanup encounters interruption.
	 *
	 * @param process owned helper
	 * @param timeout maximum elapsed wait
	 * @param cleanup noninterruptible cleanup scope, or null for interruptible waits
	 * @return whether the helper exited
	 * @throws InterruptedException if the interruptible wait is interrupted
	 */
	private static boolean waitForHelper(Process process, Duration timeout, Cleanup cleanup) throws InterruptedException
	{
		long started = System.nanoTime();
		while (System.nanoTime() - started < timeout.toNanos())
		{
			try
			{
				long remaining = timeout.toNanos() - (System.nanoTime() - started);
				if (remaining <= 0)
					return !process.isAlive();
				return process.waitFor(remaining, TimeUnit.NANOSECONDS);
			}
			catch (InterruptedException failure)
			{
				if (cleanup == null)
					throw failure;
				cleanup.interrupted = true;
			}
		}
		return !process.isAlive();
	}

	/**
	 * Allocates literal null-terminated vectors for the duration of spawn.
	 *
	 * @param arena   owner of strings and pointer vector
	 * @param pointer canonical native pointer layout
	 * @param values  literal strings encoded as UTF-8
	 * @return NUL-terminated native pointer vector
	 */
	private static MemorySegment strings(Arena arena, AddressLayout pointer, List<String> values)
	{
		MemorySegment vector = arena.allocate(pointer.byteSize() * (values.size() + 1), pointer.byteAlignment());
		for (int i = 0; i < values.size(); i += 1)
			vector.setAtIndex(pointer, i, arena.allocateFrom(values.get(i)));
		vector.setAtIndex(pointer, values.size(), MemorySegment.NULL);
		return vector;
	}

	/**
	 * Rejects a direct-error-code native failure.
	 *
	 * @param result    native direct error code
	 * @param operation native function name
	 * @throws IOException if the native result indicates failure
	 */
	private static void check(int result, String operation) throws IOException
	{
		if (result != 0)
			throw new IOException(operation + ": errno=" + result);
	}

	/**
	 * Rejects a native -1 result using its already captured errno.
	 *
	 * @param result    native zero or minus-one result
	 * @param error     immediately captured errno
	 * @param operation native function name
	 * @throws IOException if the native result indicates failure
	 */
	private static void checkSignal(int result, int error, String operation) throws IOException
	{
		if (result < 0)
			throw new IOException(operation + ": errno=" + error);
	}

	/**
	 * Records interruption while bounded cleanup continues, restoring it at the operation boundary.
	 */
	private static final class Cleanup implements AutoCloseable
	{
		/**
		 * Interrupt to restore after cleanup and reaping finish.
		 */
		private boolean interrupted = Thread.interrupted();

		/**
		 * Begins noninterruptible resource cleanup.
		 */
		private Cleanup()
		{
		}

		@Override
		public void close()
		{
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	/**
	 * Terminates and reaps a cleanup helper for {@link UnixQuietBuildProcess} before allowing JDK resource closure.
	 */
	public static final class Helper implements AutoCloseable
	{
		/**
		 * Sole owned helper process.
		 */
		private final Process process;

		/**
		 * Accepts helper ownership immediately after successful startup.
		 *
		 * @param process owned cleanup helper
		 */
		public Helper(Process process)
		{
			this.process = process;
		}

		@Override
		public void close() throws IOException
		{
			try (Cleanup cleanup = new Cleanup())
			{
				// JDK Process.close can otherwise wait beyond the helper deadline for a resistant child.
				if (process.isAlive())
				{
					process.destroyForcibly();
					if (!waitForHelper(process, GRACE, cleanup))
						throw new IOException("Owned cleanup helper did not exit after forced termination: " + process.pid());
				}
				process.close();
			}
			catch (InterruptedException failure)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Owned helper cleanup unexpectedly interrupted: " + process.pid(), failure);
			}
		}
	}

	/**
	 * Owns initialized spawn state while its confined arena remains open.
	 */
	private static final class SpawnState implements AutoCloseable
	{
		/**
		 * Borrowed immutable native calls.
		 */
		private final Binding binding;
		/**
		 * Borrowed lifetime of argument and state memory.
		 */
		private final Arena arena;
		/**
		 * Native spawn attribute storage: inline on Linux and an opaque pointer cell on Darwin.
		 */
		private final MemorySegment attributes;
		/**
		 * Native file-action storage: inline on Linux and an opaque pointer cell on Darwin.
		 */
		private final MemorySegment actions;

		/**
		 * Initializes native objects, destroying the first if the second cannot initialize.
		 *
		 * @param binding native calls
		 * @param arena   owner of native storage
		 * @throws IOException if initialization fails
		 */
		private SpawnState(Binding binding, Arena arena) throws IOException
		{
			this.binding = binding;
			this.arena = arena;
			if (binding.darwin)
			{
				attributes = arena.allocate(binding.pointer);
				actions = arena.allocate(binding.pointer);
			}
			else
			{
				attributes = arena.allocate(336, 8);
				actions = arena.allocate(80, 8);
			}
			check(binding.attributeInit.invoke(attributes), "posix_spawnattr_init");
			int error = binding.actionInit.invoke(actions);
			if (error != 0)
			{
				IOException failure = new IOException("posix_spawn_file_actions_init: errno=" + error);
				try
				{
					check(binding.attributeDestroy.invoke(attributes), "posix_spawnattr_destroy");
				}
				catch (IOException cleanupFailure)
				{
					failure.addSuppressed(cleanupFailure);
				}
				throw failure;
			}
		}

		/**
		 * Applies the admitted child signal/session policy and cwd/fd actions.
		 *
		 * @param cwd                       absolute child working directory
		 * @param closedStandardDescriptors standard descriptors closed at launcher ingress
		 * @throws IOException if a native action or signal-set operation fails
		 */
		private void configure(Path cwd, List<Integer> closedStandardDescriptors) throws IOException
		{
			MemorySegment set;
			if (binding.darwin)
				set = arena.allocate(binding.integer);
			else
				set = arena.allocate(128, 8);
			MemorySegment errors = arena.allocate(binding.state);
			checkSignal(binding.empty.invoke(errors, set), binding.errno(errors), "sigemptyset");
			check(binding.mask.invoke(attributes, set), "posix_spawnattr_setsigmask");
			// The JVM changes INT/QUIT/TERM; build children must receive ordinary CI defaults as well as PIPE/XFSZ.
			for (int signal : new int[] {2, 3, SIGTERM, 13, 25})
				checkSignal(binding.add.invoke(errors, set, signal), binding.errno(errors), "sigaddset");
			check(binding.defaults.invoke(attributes, set), "posix_spawnattr_setsigdefault");
			short flags;
			if (binding.darwin)
				flags = (short) (DARWIN_SETSID | DARWIN_CLOEXEC_DEFAULT | SETSIGMASK | SETSIGDEF);
			else
				flags = (short) (SETSID | SETSIGMASK | SETSIGDEF);
			check(binding.flags.invoke(attributes, flags), "posix_spawnattr_setflags");
			check(binding.chdir.invoke(actions, arena.allocateFrom(cwd.toString())), "posix_spawn_file_actions_addchdir_np");
			if (binding.darwin)
			{
				for (int descriptor = 0; descriptor < 3; descriptor += 1)
				{
					if (!closedStandardDescriptors.contains(descriptor))
						check(binding.inheritDescriptor.invoke(actions, descriptor),
							"posix_spawn_file_actions_addinherit_np");
				}
			}
			else
			{
				check(binding.closeFrom.invoke(actions, 3), "posix_spawn_file_actions_addclosefrom_np");
				for (int descriptor : closedStandardDescriptors)
					check(binding.closeDescriptor.invoke(actions, descriptor), "posix_spawn_file_actions_addclose");
			}
		}

		@Override
		public void close() throws IOException
		{
			int actionError = binding.actionDestroy.invoke(actions);
			int attributeError = binding.attributeDestroy.invoke(attributes);
			if (actionError != 0)
			{
				IOException failure = new IOException("posix_spawn_file_actions_destroy: errno=" + actionError);
				if (attributeError != 0)
					failure.addSuppressed(new IOException("posix_spawnattr_destroy: errno=" + attributeError));
				throw failure;
			}
			check(attributeError, "posix_spawnattr_destroy");
		}
	}

	/**
	 * Immutable Linux x86-64 or Darwin x86-64/AArch64 calls for {@link UnixQuietBuildProcess}.
	 */
	public static final class Binding
	{
		/**
		 * Canonical C int layout.
		 */
		private final ValueLayout.OfInt integer;
		/**
		 * Canonical native pointer layout.
		 */
		private final AddressLayout pointer;
		/**
		 * Whether opaque Darwin spawn objects and default-close descriptor actions are in use.
		 */
		private final boolean darwin;
		/**
		 * Native captured-call-state layout.
		 */
		private final MemoryLayout state = Linker.Option.captureStateLayout();
		/**
		 * Captured errno offset.
		 */
		private final long errnoOffset = state.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
		/**
		 * Bound native attributeInit operation.
		 */
		private final Unary attributeInit;
		/**
		 * Bound native attributeDestroy operation.
		 */
		private final Unary attributeDestroy;
		/**
		 * Bound native actionInit operation.
		 */
		private final Unary actionInit;
		/**
		 * Bound native actionDestroy operation.
		 */
		private final Unary actionDestroy;
		/**
		 * Bound native empty operation.
		 */
		private final SignalEmpty empty;
		/**
		 * Bound native add operation.
		 */
		private final SignalAdd add;
		/**
		 * Bound native mask operation.
		 */
		private final Pointers mask;
		/**
		 * Bound native defaults operation.
		 */
		private final Pointers defaults;
		/**
		 * Bound native flags operation.
		 */
		private final ShortArgument flags;
		/**
		 * Bound native chdir operation.
		 */
		private final Pointers chdir;
		/**
		 * Bound native closeFrom operation.
		 */
		private final IntegerArgument closeFrom;
		/**
		 * Inherits one descriptor despite Darwin's default-close-on-exec flag.
		 */
		private final IntegerArgument inheritDescriptor;
		/**
		 * Closes a standard descriptor in the spawned child.
		 */
		private final IntegerArgument closeDescriptor;
		/**
		 * Queries ingress descriptor flags without opening files.
		 */
		private final Fcntl fcntl;
		/**
		 * Bound native spawn operation.
		 */
		private final Spawn spawn;
		/**
		 * Bound native wait operation.
		 */
		private final Wait wait;
		/**
		 * Bound native kill operation.
		 */
		private final Kill kill;

		/**
		 * Resolves only the admitted Linux/Darwin ABI and required libc capabilities.
		 *
		 * @param linker invocation-scoped native linker
		 * @throws UnsupportedOperationException if the ABI or libc capability is unavailable
		 */
		public Binding(Linker linker)
		{
			String osName = System.getProperty("os.name");
			String architecture = System.getProperty("os.arch");
			darwin = osName.equals("Mac OS X") && (architecture.equals("x86_64") || architecture.equals("aarch64"));
			boolean linux = osName.equals("Linux") &&
				(architecture.equals("amd64") || architecture.equals("aarch64"));
			if (!linux && !darwin)
				throw new UnsupportedOperationException("POSIX spawn ABI is supported only on Linux x86-64, " +
					"Linux AArch64, Darwin x86-64, and Darwin AArch64");
			integer = (ValueLayout.OfInt) linker.canonicalLayouts().get("int");
			ValueLayout.OfShort shortInteger = (ValueLayout.OfShort) linker.canonicalLayouts().get("short");
			pointer = (AddressLayout) linker.canonicalLayouts().get("void*");
			if (integer.byteSize() != 4 || shortInteger.byteSize() != 2 || pointer.byteSize() != 8)
				throw new UnsupportedOperationException("Native scalar ABI differs from the measured POSIX oracle");
			attributeInit = bind(linker, "posix_spawnattr_init", Unary.class, false, pointer);
			attributeDestroy = bind(linker, "posix_spawnattr_destroy", Unary.class, false, pointer);
			actionInit = bind(linker, "posix_spawn_file_actions_init", Unary.class, false, pointer);
			actionDestroy = bind(linker, "posix_spawn_file_actions_destroy", Unary.class, false, pointer);
			empty = bind(linker, "sigemptyset", SignalEmpty.class, true, pointer);
			add = bind(linker, "sigaddset", SignalAdd.class, true, pointer, integer);
			mask = bind(linker, "posix_spawnattr_setsigmask", Pointers.class, false, pointer, pointer);
			defaults = bind(linker, "posix_spawnattr_setsigdefault", Pointers.class, false, pointer, pointer);
			flags = bind(linker, "posix_spawnattr_setflags", ShortArgument.class, false, pointer, shortInteger);
			chdir = bind(linker, "posix_spawn_file_actions_addchdir_np", Pointers.class, false, pointer, pointer);
			if (darwin)
			{
				closeFrom = null;
				closeDescriptor = null;
				inheritDescriptor = bind(linker, "posix_spawn_file_actions_addinherit_np", IntegerArgument.class, false,
					pointer, integer);
			}
			else
			{
				closeFrom = bind(linker, "posix_spawn_file_actions_addclosefrom_np", IntegerArgument.class, false,
					pointer, integer);
				closeDescriptor = bind(linker, "posix_spawn_file_actions_addclose", IntegerArgument.class, false,
					pointer, integer);
				inheritDescriptor = null;
			}
			fcntl = MethodHandleProxies.asInterfaceInstance(Fcntl.class, linker.downcallHandle(
				linker.defaultLookup().find("fcntl").orElseThrow(() ->
					new UnsupportedOperationException("Required libc function unavailable: fcntl")),
				FunctionDescriptor.of(integer, integer, integer), Linker.Option.firstVariadicArg(2),
				Linker.Option.captureCallState("errno")));
			spawn = bind(linker, "posix_spawn", Spawn.class, false, pointer, pointer, pointer, pointer, pointer, pointer);
			wait = bind(linker, "waitpid", Wait.class, true, integer, pointer, integer);
			kill = bind(linker, "kill", Kill.class, true, integer, integer);
		}

		/**
		 * Snapshots closed standard descriptors before validation or argument allocation can load more classes.
		 *
		 * @return standard descriptors that the child must keep closed
		 * @throws IOException if a descriptor query fails for a reason other than EBADF
		 */
		public List<Integer> closedStandards() throws IOException
		{
			List<Integer> closed = new ArrayList<>();
			try (Arena arena = Arena.ofConfined())
			{
				MemorySegment errors = arena.allocate(state);
				for (int descriptor = 0; descriptor < 3; ++descriptor)
				{
					int result = fcntl.invoke(errors, descriptor, F_GETFD);
					int error = errno(errors);
					if (result >= 0)
						continue;
					if (error != EBADF)
						throw new IOException("fcntl F_GETFD for standard descriptor " + descriptor + ": errno=" + error);
					closed.add(descriptor);
				}
			}
			return List.copyOf(closed);
		}

		/**
		 * Resolves a typed canonical libc signature.
		 *
		 * @param <T>          fixed proxy interface type
		 * @param linker       native linker
		 * @param name         libc symbol
		 * @param type         fixed proxy interface class
		 * @param captureErrno whether the first proxy argument captures errno
		 * @param arguments    canonical native argument layouts
		 * @return typed native proxy
		 * @throws UnsupportedOperationException if the libc symbol is unavailable
		 */
		private <T> T bind(Linker linker, String name, Class<T> type, boolean captureErrno, MemoryLayout... arguments)
		{
			MemorySegment address = linker.defaultLookup().find(name).
				orElseThrow(() -> new UnsupportedOperationException("Required libc function unavailable: " + name));
			FunctionDescriptor descriptor = FunctionDescriptor.of(integer, arguments);
			MethodHandle handle;
			if (captureErrno)
				handle = linker.downcallHandle(address, descriptor, Linker.Option.captureCallState("errno"));
			else
				handle = linker.downcallHandle(address, descriptor);
			return MethodHandleProxies.asInterfaceInstance(type, handle);
		}

		/**
		 * Reads errno captured immediately by the preceding downcall.
		 *
		 * @param errors immediately captured native call state
		 * @return captured errno
		 */
		private int errno(MemorySegment errors)
		{
			return errors.get(integer, errnoOffset);
		}
	}
}
