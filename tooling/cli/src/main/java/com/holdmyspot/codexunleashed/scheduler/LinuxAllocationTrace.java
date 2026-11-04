package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Owns a Linux x86-64 or AArch64 command and every forked or cloned tracee until native collection completes.
 * A dedicated platform thread exclusively spawns, waits for, and resumes its tracees. Standard descriptors are
 * inherited unchanged. The command starts only after tracing is established; it must not already have another tracer.
 * A denied mmap can be reported only after its genuine return executes an injected trap, while the return remains
 * held. This operation supports whole-attempt termination, rather than restoring and retrying the denied instruction.
 */
public final class LinuxAllocationTrace
{
	/** Linux ptrace requests from linux/ptrace.h. */
	private static final int PEEK = 1, POKE = 4, CONT = 7, GETREGS = 12, DETACH = 17, SYSCALL = 24,
		GETEVENTMSG = 0x4201, SEIZE = 0x4206, INTERRUPT = 0x4207, GET_SYSCALL_INFO = 0x420e;
	/** Trace syscall stops, descendants, exec and seccomp stops; kernel kills tracees when their tracing thread exits. */
	private static final long TRACE_OPTIONS = 0x10009f;
	/** PTRACE_EVENT_SECCOMP: a seccomp filter returned SECCOMP_RET_TRACE for the stopped call. */
	private static final int SECCOMP_EVENT = 7;
	/** Linux wait flags: all traced children, restricted to children of this native thread. */
	private static final int WAIT_OWNED = 0x60000000;
	/** Linux WNOHANG permits caller cancellation to reach the sole tracing thread. */
	private static final int NOHANG = 1;
	/** Linux signals needed by stopped-shell startup, trap recognition, and terminal collection. */
	private static final int KILL = 9, STOP = 19, CONTINUE = 18, TRAP = 5, SYSCALL_TRAP = 133;
	/** Linux ESRCH: the task does not exist or, for ptrace, is no longer stopped for its tracer. */
	private static final int ESRCH = 3;
	/** Linux mmap syscall and ENOMEM number for the x86-64 syscall ABI. */
	private static final long MMAP = 9, NO_MEMORY = -12;
	/** Linux munmap, execve, execveat, prctl and seccomp syscall numbers for the x86-64 syscall ABI. */
	private static final long MUNMAP = 11, EXECVE = 59, EXECVEAT = 322, PRCTL = 157, SECCOMP = 317;
	/** Constant bootstrap receives cwd and command exclusively through distinct positional arguments. */
	private static final String BOOTSTRAP = "kill -STOP $$; cd -- \"$1\" || exit 125; shift; exec \"$@\"";
	/** Same-call errno offset from the JDK linker-owned capture layout. */
	private static final long ERRNO_OFFSET = Linker.Option.captureStateLayout()
		.byteOffset(MemoryLayout.PathElement.groupElement("errno"));

	/**
	 * Prevents construction of this command owner through an instance.
	 */
	private LinuxAllocationTrace()
	{
	}

    /**
     * Rejects unsupported native platforms before acquiring a tracing owner.
     * @throws UnsupportedOperationException if Linux LP64 x86-64 or AArch64 is unavailable
     */
    private static void checkPlatform()
    {
        boolean architecture = switch (System.getProperty("os.arch"))
        {
            case "amd64", "aarch64", "arm64" -> true;
            default -> false;
        };
        if (!System.getProperty("os.name").equals("Linux") || !architecture)
            throw new UnsupportedOperationException("Allocation tracing requires Linux x86-64 or AArch64");
    }

    /** Borrows the existing tracing owner's checked ptrace operation without acquiring another owner. */
    @FunctionalInterface
    public interface NativeTrace
    {
        /**
         * Performs one checked native ptrace request on the exclusive owner thread.
         * @param request native ptrace request
         * @param tid owned native task
         * @param address native address or request-specific scalar
         * @param data native request buffer or scalar
         * @return unmodified native ptrace result
         * @throws IOException if ptrace reports a captured native error
         */
        long invoke(int request, int tid, long address, MemorySegment data) throws IOException;
    }

	/**
	 * One kernel task's identity, combining its ID with Linux stat field 22 to distinguish PID reuse.
	 * @param tid Linux task ID
	 * @param birthTicks start time in kernel clock ticks since boot
	 */
	public record Identity(int tid, long birthTicks)
	{
		/**
		 * Validates a native task identity.
		 * @param tid positive Linux task ID
		 * @param birthTicks nonnegative start time in clock ticks since boot
		 * @throws IllegalArgumentException if either value is outside its native identity range
		 */
		public Identity
		{
			if (tid <= 0 || birthTicks < 0)
				throw new IllegalArgumentException("Task ID must be positive and birth ticks nonnegative");
		}
	}

	/**
	 * A genuine denied mmap whose next instruction executed a native trap and remains stopped for the handler.
	 * @param thread identity of the stopped allocating thread
	 * @param processId thread-group ID containing that thread
	 * @param syscall native syscall number for the observed instruction ABI
	 * @param requestedBytes mmap length in bytes
	 * @param nativeResult unmodified kernel return in RAX or x0
	 * @param returnAddress address immediately after the syscall instruction
	 * @param trapPc observed instruction pointer after executing the injected native trap
	 * @param syscallBytes syscall instruction immediately preceding the return address, as lowercase hexadecimal
	 * @param originalInstructionBits original unsigned x86-64 byte or raw AArch64 32-bit instruction
	 * @param executableMapping native maps record containing the executable return instruction
	 */
	public record Denial(Identity thread, int processId, long syscall, long requestedBytes, long nativeResult,
		long returnAddress, long trapPc, String syscallBytes, int originalInstructionBits, String executableMapping)
	{
		/**
		 * Retains one verified denial observation.
		 * @param thread allocating thread identity
		 * @param processId allocating process ID
		 * @param syscall native syscall number
		 * @param requestedBytes requested mapping length
		 * @param nativeResult unmodified native error result
		 * @param returnAddress native syscall return instruction address
		 * @param trapPc native instruction pointer after the injected trap
		 * @param syscallBytes native syscall opcode
		 * @param originalInstructionBits original unsigned x86-64 byte or raw AArch64 instruction bits
		 * @param executableMapping executable maps record
		 * @throws NullPointerException if a reference parameter is null
		 * @throws IllegalArgumentException if the observation does not describe an executed denied mmap trap
		 */
		public Denial
		{
			Objects.requireNonNull(thread, "thread");
			Objects.requireNonNull(syscallBytes, "syscallBytes");
			Objects.requireNonNull(executableMapping, "executableMapping");
			boolean x8664 = syscall == MMAP && trapPc == returnAddress + 1 && syscallBytes.equals("0f05") &&
				originalInstructionBits >= 0 && originalInstructionBits <= 255;
			boolean aarch64 = syscall == LinuxAarch64Trace.MMAP && trapPc == returnAddress &&
				(returnAddress & 3) == 0 && syscallBytes.equals("010000d4");
			if (processId <= 0 || requestedBytes <= 0 || nativeResult != NO_MEMORY || (!x8664 && !aarch64))
				throw new IllegalArgumentException("Observation must describe mmap ENOMEM and its executed native trap");
		}
	}

	/**
	 * Receives a denial while its allocating thread remains at the executed trap. Returning requests termination
	 * of the complete attempt; throwing also terminates and collects the attempt before propagating the failure.
	 */
	@FunctionalInterface
	public interface DenialHandler
	{
		/**
		 * Examines a held denial before choosing whole-attempt termination by returning.
		 * Runs on the dedicated tracing thread and must return for termination to proceed.
		 * @param denial identity-bound native observation
		 * @throws IOException if the consumer cannot process the observation
		 */
		void terminate(Denial denial) throws IOException;
	}

	/**
	 * Authentic native terminal status collected for one traced task.
	 * @param identity collected kernel task
	 * @param waitStatus unmodified Linux wait status; low seven bits distinguish exit from signal
	 */
	public record Terminal(Identity identity, int waitStatus)
	{
		/**
		 * Retains the collected status.
		 * @param identity collected kernel task
		 * @param waitStatus raw Linux wait status
		 * @throws NullPointerException if {@code identity} is null
		 * @throws IllegalArgumentException if the status describes a stopped task
		 */
		public Terminal
		{
			Objects.requireNonNull(identity, "identity");
			if ((waitStatus & 255) == 127)
				throw new IllegalArgumentException("Terminal status must not describe a stopped task");
		}
	}

	/**
	 * Fully collected attempt, preserving root and descendant native outcomes in collection order.
	 * @param root root task's authentic terminal status
	 * @param collected statuses for every task that this attempt traced
	 * @param denied indicates whether an executed mmap-denial trap was delivered to the handler
	 */
	public record Outcome(Terminal root, List<Terminal> collected, boolean denied)
	{
		/**
		 * Freezes an attempt's collected statuses.
		 * @param root root task's terminal status
		 * @param collected all collected task statuses
		 * @param denied indicates whether a denial reached the handler
		 * @throws NullPointerException if a reference parameter or collected entry is null
		 * @throws IllegalArgumentException if collected statuses omit the root
		 */
		public Outcome
		{
			Objects.requireNonNull(root, "root");
			collected = List.copyOf(collected);
			if (!collected.contains(root))
				throw new IllegalArgumentException("Collected statuses must include the root task");
		}
	}

	/**
	 * Executes one owned command with explicit environment and cwd, inheriting native descriptors 0, 1 and 2.
	 * A positive address-space limit applies only to the child before command execution; zero leaves its limit intact.
	 * On caller interruption the owner kills and collects the attempt before this method throws.
	 * @param linker borrowed JDK native linker, kept available throughout the operation
	 * @param argv executable name followed by its arguments; no element may contain NUL
	 * @param environment complete child environment; keys must be nonempty and contain neither equals nor NUL
	 * @param directory existing child working directory
	 * @param addressSpaceBytes child RLIMIT_AS soft and hard limit in bytes, or zero for no change
	 * @param handler consumer of held genuine allocation failures
	 * @return authentic root and descendant outcomes after every tracee is collected
	 * @throws NullPointerException if a reference parameter, argument, environment key or value is null
	 * @throws IllegalArgumentException if arguments, environment or limit violate their documented form
	 * @throws UnsupportedOperationException if Linux x86-64/AArch64 or a required libc symbol is unavailable
	 * @throws IOException if native startup, tracing, observation, callback or collection fails
	 * @throws InterruptedException if the caller is interrupted, after native collection completes
	 */
	public static Outcome run(Linker linker, List<String> argv, Map<String, String> environment,
		Path directory, long addressSpaceBytes, DenialHandler handler) throws IOException, InterruptedException
	{
		Objects.requireNonNull(linker, "linker");
		Objects.requireNonNull(directory, "directory");
		Objects.requireNonNull(handler, "handler");
		List<String> command = List.copyOf(argv);
		Map<String, String> variables = Map.copyOf(environment);
		if (command.isEmpty() || command.getFirst().isEmpty() || addressSpaceBytes < 0)
			throw new IllegalArgumentException("Command must name an executable and limit must be nonnegative");
		for (String argument : command)
			validateText(argument);
		for (Map.Entry<String, String> variable : variables.entrySet())
		{
			validateText(variable.getKey());
			validateText(variable.getValue());
			if (variable.getKey().isEmpty() || variable.getKey().contains("="))
				throw new IllegalArgumentException("Environment key must be nonempty and contain no equals sign");
		}
		if (!Files.isDirectory(directory))
			throw new IOException("Child working directory does not exist: " + directory);
		checkPlatform();

		AtomicBoolean cancel = new AtomicBoolean();
		AtomicReference<Outcome> result = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread owner = Thread.ofPlatform().name("linux-allocation-trace").unstarted(() ->
		{
			try
			{
				result.set(new Attempt(linker, cancel, handler).execute(command, variables, directory,
					addressSpaceBytes));
			}
			catch (Throwable problem)
			{
				// This is the platform-thread result boundary; the caller rethrows the original failure.
				failure.set(problem);
			}
		});
		owner.start();
		boolean interrupted = false;
		while (owner.isAlive())
		{
			try
			{
				owner.join();
			}
			catch (InterruptedException problem)
			{
				interrupted = true;
				cancel.set(true);
			}
		}
		if (interrupted)
		{
			InterruptedException problem = new InterruptedException("Tracing attempt collected after interruption");
			if (failure.get() != null)
				problem.addSuppressed(failure.get());
			throw problem;
		}
		Throwable problem = failure.get();
		if (problem instanceof IOException io)
			throw io;
		if (problem instanceof RuntimeException runtime)
			throw runtime;
		if (problem instanceof Error error)
			throw error;
		if (problem != null)
			throw new IOException("Tracing owner failed", problem);
		return Objects.requireNonNull(result.get(), "Tracing owner supplied no outcome");
	}

    /**
     * Adopts an authenticated, parked native child without replacing its original parent.
     * The caller must already own containment and prove that no other tracer owns the child.
     * The child keeps its cwd, credentials, namespaces, cgroup and inherited descriptors.
     * @param linker borrowed native linker
     * @param child independently authenticated prepared task identity
     * @param executable absolute native executable
     * @param argv exact native arguments, including argv zero
     * @param environment complete native environment
     * @return retained exclusive trace owner, initially holding the new executable before application execution
     * @throws NullPointerException if a reference or collection element is null
     * @throws IllegalArgumentException if executable, arguments or environment cannot be represented natively
     * @throws UnsupportedOperationException if Linux x86-64 or AArch64 is unavailable
     */
    public static Session adopt(Linker linker, Identity child, Path executable, List<String> argv,
        Map<String, String> environment)
    {
        checkPlatform();
        Objects.requireNonNull(linker, "linker");
        Objects.requireNonNull(child, "child");
        Objects.requireNonNull(executable, "executable");
        List<String> arguments = List.copyOf(argv);
        Map<String, String> variables = Map.copyOf(environment);
        if (!executable.isAbsolute() || arguments.isEmpty())
            throw new IllegalArgumentException("Adoption requires an absolute executable and explicit argv zero");
        validateText(executable.toString());
        arguments.forEach(LinuxAllocationTrace::validateText);
        variables.forEach((key, value) ->
        {
            validateText(key);
            validateText(value);
            if (key.isEmpty() || key.contains("="))
                throw new IllegalArgumentException("Native environment keys must be nonempty and contain no equals");
        });
        return new Session(linker, child, executable, arguments, variables);
    }

    /** Releases an original native exec gate while retaining a pending kernel stop.
     * @param linker borrowed native linker
     * @param child independently authenticated single-threaded held exec identity
     * @param release original gate owner's synchronous detach operation; must not send SIGCONT
     * @param timeout positive bounded readiness interval
     * @return sole native trace owner holding the unchanged executable before further execution
     * @throws IOException if identity, original detach, group-stop or sole tracing readiness fails
     * @throws NullPointerException if a reference is null
     * @throws IllegalArgumentException if timeout is nonpositive
     */
    static Session handoff(Linker linker, Identity child, GateRelease release, Duration timeout) throws IOException
    {
        checkPlatform();
        Objects.requireNonNull(linker, "linker");
        Objects.requireNonNull(child, "child");
        Objects.requireNonNull(release, "release");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Positive handoff timeout required");
        long budget = timeout.toNanos();
        if (!Attempt.identify(child.tid()).equals(child)) throw new IOException("Original gated child birth changed");
        MethodHandle kill = Attempt.bind(linker, "kill", FunctionDescriptor.of(ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), false);
        NativeResult sent = Attempt.call(kill, child.tid(), STOP);
        if (sent.value() != 0) throw new IOException("Queuing original child stop failed: " + sent.errno());
        release.release();
        long began = System.nanoTime();
        while (true)
        {
            if (!Attempt.identify(child.tid()).equals(child)) throw new IOException("Child birth changed during gate transfer");
            String status = Files.readString(Path.of("/proc", Integer.toString(child.tid()), "status"));
            boolean stopped = status.lines().anyMatch(line -> line.startsWith("State:") && line.contains("T (stopped)"));
            boolean untraced = status.lines().anyMatch(line -> line.startsWith("TracerPid:") && line.substring(10).strip().equals("0"));
            if (stopped && untraced) break;
            if (Thread.currentThread().isInterrupted() || System.nanoTime() - began >= budget)
                throw new IOException("Original child did not reach an untraced kernel group-stop");
            LockSupport.parkNanos(1_000_000);
        }
        Session session = new Session(linker, child, null, List.of(), Map.of());
        try { session.awaitPrepared(timeout); return session; }
        catch (IOException | RuntimeException | Error failure)
        {
            session.terminate();
            try { session.awaitCollected(timeout); }
            catch (IOException cleanup)
            {
                failure.addSuppressed(cleanup);
                throw new HandoffFailure(failure, session);
            }
            throw failure;
        }
    }

    /** Retains a partially collected native owner when original gate transfer fails.
     * The caller must preserve this owner and establish workload zero before retrying collection.
     */
    static final class HandoffFailure extends IOException
    {
        /** Serialization identity for the diagnostic exception; serialized values carry no native authority. */
        private static final long serialVersionUID = 1L;
        /** Original sole native owner, absent only after diagnostic deserialization. */
        private final transient Session retained;
        /** Preserves the primary failure and its partially collected owner.
         * @param cause primary transfer failure, including cleanup evidence
         * @param retained original native owner
         */
        private HandoffFailure(Throwable cause, Session retained)
        {
            super("Native gate transfer retains collection ownership", cause);
            this.retained = retained;
        }
    }

    /** Original exec gate's exclusive detach authority; it must preserve a separately queued SIGSTOP. */
    @FunctionalInterface
    interface GateRelease
    {
        /** Detaches the original tracer without sending SIGCONT.
         * @throws IOException if original gate release is uncertain
         */
        void release() throws IOException;
    }

    /** Owns native image handoffs and copied descendant events for one existing Cargo controller.
     * The caller authenticates every image before install and every continuation before resume, and supplies
     * whole-workload zero before close. Unless the caller hands over every image, dynamic images outside an acquired
     * native tree remain Frida-owned.
     */
    static final class Images
    {
        /** Borrowed native ABI linker retained through all native sessions. */
        private final Linker linker;
        /** Positive native readiness and cleanup deadline. */
        private final Duration timeout;
        /** Native owners retained even after their original process exits. */
        private final List<Session> sessions = new ArrayList<>();
        /** Actual process-to-owner mapping established by native fork and exec observations. */
        private final Map<Long, Session> owners = new LinkedHashMap<>();
        /** Latest identity copied from each actual native root, fork or exec stop. */
        private final Map<Long, Identity> identities = new LinkedHashMap<>();
        /** Initial sessions awaiting their independently authenticated first continuation. */
        private final Set<Long> initial = new HashSet<>();
        /** Exact descendant holds awaiting independently authenticated continuation. */
        private final Map<Long, Held> pending = new LinkedHashMap<>();

        /** Borrows the existing native services without acquiring a process.
         * @param linker scoped native linker
         * @param timeout positive native readiness interval
         */
        Images(Linker linker, Duration timeout)
        {
            this.linker = Objects.requireNonNull(linker, "linker");
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Positive native image timeout required");
        }

        /** Selects an existing native tree or transfers an authentic exec gate.
         * @param pid independently authenticated held process
         * @param birth independently authenticated current birth
         * @param release original Frida owner's single detach operation, preserving queued SIGSTOP
         * @param everyImage whether a dynamic image is transferred too, instead of only a static one
         * @return true when the image is held by this sole native owner; false for a dynamic image that stays with
         *     Frida because {@code everyImage} is false
         * @throws IOException if executable selection or exact native handoff fails
         */
        boolean install(long pid, long birth, GateRelease release, boolean everyImage) throws IOException
        {
            if (owners.containsKey(pid))
            {
                if (!new Identity(Math.toIntExact(pid), birth).equals(identities.get(pid)))
                    throw new IOException("Native image differs from its retained root or transition identity");
                return true;
            }
            try (java.nio.channels.FileChannel image = java.nio.channels.FileChannel.open(
                Path.of("/proc", Long.toString(pid), "exe")))
            {
                if (!everyImage && !LinuxBazelHelperMain.isStaticTraceImage(image)) return false;
            }
            Session session;
            try { session = handoff(linker, new Identity(Math.toIntExact(pid), birth), release, timeout); }
            catch (HandoffFailure failure)
            {
                sessions.add(Objects.requireNonNull(failure.retained, "retained native owner"));
                throw failure;
            }
            sessions.add(session);
            owners.put(pid, session);
            identities.put(pid, new Identity(Math.toIntExact(pid), birth));
            initial.add(pid);
            return true;
        }

        /** Reports whether an authentic native observation already assigned this process to this owner.
         * @param pid current process ID
         * @return true for a retained native root or descendant
         */
        boolean owns(long pid) { return owners.containsKey(pid); }

        /** Continues one independently authenticated held native image once.
         * @param pid authenticated process
         * @param birth current independently checked birth
         * @throws IOException if exact original continuation authority is absent
         */
        void resume(long pid, long birth) throws IOException
        {
            Session session = owners.get(pid);
            if (session == null) throw new IOException("Native image has no original owner");
            Identity identity = new Identity(Math.toIntExact(pid), birth);
            if (!Attempt.identify(identity.tid()).equals(identity)) throw new IOException("Native image birth changed before continuation");
            if (initial.remove(pid)) session.resume();
            else
            {
                Held event = pending.remove(pid);
                if (event == null || !event.task().equals(identity)) throw new IOException("Native image lacks its exact held transition");
                session.releaseHeld(identity);
            }
        }

        /** Releases one independently authenticated held exec image untraced, once.
         * Neither the image nor any process it later starts is traced by this owner afterward.
         * @param pid authenticated process
         * @param birth current independently checked birth
         * @throws IOException if the image is not this owner's exact pending exec stop
         */
        void detach(long pid, long birth) throws IOException
        {
            Session session = owners.get(pid);
            if (session == null) throw new IOException("Native image has no original owner");
            Identity identity = new Identity(Math.toIntExact(pid), birth);
            if (!Attempt.identify(identity.tid()).equals(identity)) throw new IOException("Native image birth changed before release");
            Held event = pending.get(pid);
            if (event == null || event.origin() != 1 || !event.task().equals(identity))
                throw new IOException("Native image lacks its exact held exec");
            pending.remove(pid);
            owners.remove(pid);
            identities.remove(pid);
            session.detachHeld(identity);
        }

        /** Copies one native image stop after authenticating any original nonleader exec entry.
         * The caller must independently authenticate the returned image before its continuation.
         * @return copied original native event, or null while none is pending
         * @throws IOException if native ownership, original thread membership or birth is uncertain
         */
        FridaRuntime.Child pollChild() throws IOException
        {
            for (Session session : sessions)
            {
                PreExec entry;
                while ((entry = session.pollPreExec()) != null)
                {
                    if (!Attempt.identify(entry.thread().tid()).equals(entry.thread()) ||
                        Attempt.processId(entry.thread().tid()) != entry.processId())
                        throw new IOException("Original native exec thread membership or birth changed");
                    session.authorizeExec(entry.thread());
                }
                Held event = session.pollHeld();
                if (event == null) continue;
                long pid = event.task().tid();
                if (pending.putIfAbsent(pid, event) != null) throw new IOException("Native image has an outstanding admission");
                owners.put(pid, session);
                identities.put(pid, event.task());
                return new FridaRuntime.Child(pid, event.parentPid(), event.origin(), event.path());
            }
            return null;
        }

        /** Copies one actual executed-trap denial without authorizing a continuation.
         * @return authentic native denial, or null when none is pending
         * @throws IOException if the original tracing owner failed
         */
        Denial pollDenial() throws IOException
        {
            for (Session session : sessions)
            {
                Denial denial = session.pollDenial();
                if (denial != null) return denial;
            }
            return null;
        }

        /** Joins every retained native owner after the caller has established whole-workload zero.
         * @throws IOException if a native owner failed or collection remains incomplete
         */
        void close() throws IOException
        {
            IOException failure = null;
            for (Session session : sessions)
            {
                try { session.awaitCollected(timeout); }
                catch (IOException cause)
                {
                    if (failure == null) failure = cause;
                    else failure.addSuppressed(cause);
                }
            }
            if (failure != null) throw failure;
        }
    }

    /**
     * One real fork or exec stop requiring separate host image admission before continuation.
     * @param task held process kernel identity
     * @param parentPid actual retained parent for fork, or same process ID for exec
     * @param origin zero for fork or one for exec, matching the existing image protocol
     * @param path actual mapped executable path
     * @param formerThread pre-exec thread identity, retained for nonleader exec migration
     */
    public record Held(Identity task, int parentPid, int origin, String path, Identity formerThread) {}

    /**
     * A nonleader thread stopped before exec, requiring independent host thread identity observation.
     * @param thread original native thread ID and birth
     * @param processId its current thread-group leader ID
     */
    public record PreExec(Identity thread, int processId) {}

    /**
     * Retains one dedicated native tracing thread from adoption through original-parent terminal collection.
     * Consumer operations only exchange copied values and cancellation flags; they never call ptrace or waitpid.
     */
    public static final class Session
    {
        /** Sole native owner; retained through all partial failures. */
        private final Thread thread;
        /** Request to terminate and collect all tracees. */
        private final AtomicBoolean terminate = new AtomicBoolean();
        /** Original held-executable readiness from the native owner. */
        private final AtomicBoolean prepared = new AtomicBoolean();
        /** Host authorization to continue the original image once. */
        private final AtomicBoolean released = new AtomicBoolean();
        /** Copied native denials, bounded by the one whole-attempt denial hold. */
        private final ConcurrentLinkedQueue<Denial> denials = new ConcurrentLinkedQueue<>();
        /** Copied process image stops awaiting host admission. */
        private final ConcurrentLinkedQueue<Held> held = new ConcurrentLinkedQueue<>();
        /** Copied pre-exec thread observations awaiting host authentication. */
        private final ConcurrentLinkedQueue<PreExec> preExec = new ConcurrentLinkedQueue<>();
        /** Exact image continuation capabilities posted by the host. */
        private final java.util.Set<Identity> imageReleases = ConcurrentHashMap.newKeySet();
        /** Exact held images the host releases untraced. */
        private final java.util.Set<Identity> imageDetaches = ConcurrentHashMap.newKeySet();
        /** Exact nonleader exec capabilities posted after independent host thread authentication. */
        private final java.util.Set<Identity> execReleases = ConcurrentHashMap.newKeySet();
        /** Authentic complete collection result, available only after native ownership ends. */
        private final AtomicReference<Outcome> outcome = new AtomicReference<>();
        /** Authentic root status, published before surviving descendants finish. */
        private final AtomicReference<Terminal> rootTerminal = new AtomicReference<>();
        /** Original native failure, including any retained cleanup failure. */
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        /**
         * Starts the sole platform thread; every native acquisition remains within that thread.
         * @param linker borrowed native linker
         * @param child authenticated original child
         * @param executable exact requested native image, or null to retain a transferred exec image
         * @param argv exact requested arguments
         * @param environment complete requested environment
         */
        private Session(Linker linker, Identity child, Path executable, List<String> argv,
            Map<String, String> environment)
        {
            thread = Thread.ofPlatform().daemon(false).name("linux-bazel-allocation-trace").unstarted(() ->
            {
                try
                {
                    Attempt attempt = new Attempt(linker, terminate, denials::add);
                    attempt.session = this;
                    outcome.set(attempt.adopt(child, executable, argv, environment, prepared, released));
                }
                catch (Throwable problem)
                {
                    failure.set(problem);
                }
            });
            thread.start();
        }

        /**
         * Waits for the genuine exec stop while retaining cancellation and native errors.
         * @param timeout positive readiness budget
         * @throws IOException if the owner fails, caller is interrupted or readiness times out
         * @throws IllegalArgumentException if timeout is nonpositive
         */
        public void awaitPrepared(Duration timeout) throws IOException
        {
            if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Positive timeout required");
            long start = System.nanoTime();
            while (!prepared.get())
            {
                check();
                if (Thread.currentThread().isInterrupted() || System.nanoTime() - start >= timeout.toNanos())
                    throw new IOException("Original prepared child did not reach its held exec boundary");
                LockSupport.parkNanos(1_000_000);
            }
        }

        /**
         * Authorizes the sole initial image continuation after independent host revalidation.
         * @throws IOException if readiness, ownership or one-shot continuation authority is absent
         */
        public void resume() throws IOException
        {
            check();
            if (!prepared.get() || !released.compareAndSet(false, true) || terminate.get())
                throw new IOException("Original image continuation is unavailable or repeated");
        }

        /**
         * Returns and removes the next copied authentic denial without entering the native owner.
         * @return native observation, or null when no denial is pending
         * @throws IOException if native tracing failed
         */
        public Denial pollDenial() throws IOException
        {
            check();
            return denials.poll();
        }

        /**
         * Removes the next actual held process image notification.
         * @return copied native stop, or null while no image is pending
         * @throws IOException if the native owner failed
         */
        public Held pollHeld() throws IOException { check(); return held.poll(); }

        /**
         * Removes the next original nonleader exec-entry identity.
         * @return copied pre-exec native identity, or null when none is pending
         * @throws IOException if the native owner failed
         */
        public PreExec pollPreExec() throws IOException { check(); return preExec.poll(); }

        /**
         * Authorizes one held image only after the host independently rechecks its identity and policy.
         * @param identity exact native held process ID and birth
         * @throws IOException if the native owner failed
         * @throws NullPointerException if identity is null
         */
        public void releaseHeld(Identity identity) throws IOException { check(); imageReleases.add(Objects.requireNonNull(identity)); }

        /**
         * Releases one held image untraced, only after the host independently rechecks its identity and policy.
         * @param identity exact native held process ID and birth
         * @throws IOException if the native owner failed
         * @throws NullPointerException if identity is null
         */
        public void detachHeld(Identity identity) throws IOException { check(); imageDetaches.add(Objects.requireNonNull(identity)); }

        /**
         * Authorizes a nonleader exec only after the host observes its original thread-group membership and birth.
         * @param identity original native thread ID and birth
         * @throws IOException if the native owner failed
         * @throws NullPointerException if identity is null
         */
        public void authorizeExec(Identity identity) throws IOException { check(); execReleases.add(Objects.requireNonNull(identity)); }

        /**
         * Requests whole-attempt termination without reporting completion or releasing ownership.
         */
        public void terminate()
        {
            terminate.set(true);
        }

        /**
         * Reads the sole native owner's collected root status while descendants remain owned.
         * This observation grants neither descendant continuation nor whole-attempt completion.
         * @return authentic root terminal record, or null before its native wait completes
         * @throws IOException if the native owner failed
         */
        public Terminal rootTerminal() throws IOException
        {
            check();
            return rootTerminal.get();
        }

        /**
         * Reads native completion only after every original tracee has been collected.
         * @return authentic complete outcome, or null while ownership remains live
         * @throws IOException if the owner failed
         */
        public Outcome outcome() throws IOException
        {
            check();
            return outcome.get();
        }

        /** Waits for the sole native thread to finish collection without releasing its ownership early.
         * @param timeout positive collection interval
         * @throws IOException if collection times out, interruption occurs or the native owner failed
         */
        void awaitCollected(Duration timeout) throws IOException
        {
            long began = System.nanoTime();
            boolean interrupted = Thread.interrupted();
            try
            {
                while (thread.isAlive())
                {
                    if (System.nanoTime() - began >= timeout.toNanos())
                        throw new IOException("Original native owner has not completed collection");
                    try { thread.join(1); }
                    catch (InterruptedException cause) { interrupted = true; }
                }
                check();
            }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }

        /**
         * Propagates the native owner's original failure without fabricating native completion.
         * @throws IOException if the dedicated owner failed
         */
        private void check() throws IOException
        {
            Throwable problem = failure.get();
            if (problem != null) throw new IOException("Original child tracing failed", problem);
        }
    }

	/**
	 * Rejects text that cannot be represented by a native NUL-terminated argument.
	 * @param text argument or environment component
	 * @throws IllegalArgumentException if text contains NUL
	 */
	private static void validateText(String text)
	{
		if (text.indexOf('\0') >= 0)
			throw new IllegalArgumentException("Native argument must contain no NUL character");
	}

	/**
	 * Native result paired with the errno captured by that same downcall.
	 * @param value native scalar return
	 * @param errno captured Linux errno, inspected only when that operation reports failure
	 */
	private record NativeResult(long value, int errno)
	{
	}

	/**
	 * Reports that ptrace found no stopped task, because the kernel killed it after its stop was reported.
	 * Its terminal status is still collected through the owner's wait, so the stop needs no further service.
	 */
	private static final class TaskVanished extends IOException
	{
		private static final long serialVersionUID = 1L;
		/**
		 * The task that ptrace no longer found.
		 */
		private final int tid;

		/**
		 * Creates a report for one task.
		 * @param message the failed request
		 * @param tid the task that ptrace no longer found
		 */
		TaskVanished(String message, int tid)
		{
			super(message);
			this.tid = tid;
		}
	}

	/**
	 * Owns one tracing thread's native calls, task identities, syscall observations and terminal collection.
	 */
	private static final class Attempt
	{
		/** Selects native AArch64 operations while sharing the complete tracing owner and lifecycle. */
		private final boolean aarch64 = switch (System.getProperty("os.arch"))
		{
			case "aarch64", "arm64" -> true;
			default -> false;
		};
		/** Same-thread cancellation request from the joining caller. */
		private final AtomicBoolean cancel;
		/** Consumer that chooses termination after receiving a held denial. */
		private final DenialHandler handler;
		/** Every tracee remains here until waitpid supplies its terminal status. */
		private final Map<Integer, Identity> owned = new LinkedHashMap<>();
		/**
		 * The task whose wait result is being serviced, or zero before a wait returns one.
		 */
		private int servicing;
		/** Entry mmap lengths keyed by task ID until the corresponding syscall exit. */
		private final Map<Integer, Long> mmapLengths = new HashMap<>();
		/** Tasks that exec'd a static image and await the filter at that exec's syscall exit. */
		private final Set<Integer> pendingFilters = new HashSet<>();
		/** Tasks under this tracer's seccomp filter, which stop only for mmap and exec calls. */
		private final Set<Integer> filtered = new HashSet<>();
		/** Every authentic terminal status, in collection order. */
		private final List<Terminal> collected = new ArrayList<>();
		/** libc ptrace, with promoted variadic pointer arguments on Linux LP64. */
		private final MethodHandle ptrace;
		/** libc waitpid, scoped to this dedicated native thread's children and tracees. */
		private final MethodHandle waitpid;
		/** libc posix_spawn; NULL actions and attributes preserve inherited descriptors. */
		private final MethodHandle spawn;
		/** libc kill for controlled startup and whole-attempt termination. */
		private final MethodHandle kill;
		/** libc prlimit64 limits only the stopped child, never the controller. */
		private final MethodHandle prlimit;
		/** Root identity, retained even after the root exits before its descendants. */
		private Identity root;
        /** Optional nonblocking host session; null retains the standalone callback operation. */
        private Session session;
        /** Initial child stops received before their parent creation event. */
        private final Set<Integer> initialStops = new HashSet<>();
        /** Native parents held at fork until their child has independent host image admission. */
        private final Map<Integer, Integer> forkParentHolds = new HashMap<>();
        /** Actual process image stops retained until independent host continuation. */
        private final Map<Integer, Identity> heldImages = new HashMap<>();
        /** Nonleader exec entries retained until independent host thread authentication. */
        private final Map<Integer, Identity> heldExec = new HashMap<>();
        /** Pre-exec thread identities retained until their native exec event. */
        private final Map<Integer, Identity> executing = new HashMap<>();
        /** Indicates whether the consumer received an executed denial trap. */
		private boolean denied;

		/**
		 * Resolves all libc operations before acquiring a process.
		 * @param linker borrowed JDK native linker
		 * @param cancel caller cancellation request
		 * @param handler held-denial consumer
		 * @throws UnsupportedOperationException if a required libc symbol is absent
		 */
		private Attempt(Linker linker, AtomicBoolean cancel, DenialHandler handler)
		{
			this.cancel = cancel;
			this.handler = handler;
			this.ptrace = bind(linker, "ptrace", FunctionDescriptor.of(ValueLayout.JAVA_LONG,
				ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), true);
			this.waitpid = bind(linker, "waitpid", FunctionDescriptor.of(ValueLayout.JAVA_INT,
				ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT), false);
			this.spawn = bind(linker, "posix_spawn", FunctionDescriptor.of(ValueLayout.JAVA_INT,
				ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
				ValueLayout.ADDRESS, ValueLayout.ADDRESS), false);
			this.kill = bind(linker, "kill", FunctionDescriptor.of(ValueLayout.JAVA_INT,
				ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), false);
			this.prlimit = bind(linker, "prlimit64", FunctionDescriptor.of(ValueLayout.JAVA_INT,
				ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), false);
		}

		/**
		 * Binds one libc symbol with same-call errno capture.
		 * @param linker borrowed ABI linker
		 * @param symbol required libc symbol name
		 * @param descriptor exact Linux LP64 signature
		 * @param variadic indicates whether arguments after the first are promoted ptrace varargs
		 * @return bound native operation
		 * @throws UnsupportedOperationException if the symbol is absent
		 */
		// These explicitly described Linux LP64 libc signatures require the restricted FFM downcall API.
		@SuppressWarnings("restricted")
		private static MethodHandle bind(Linker linker, String symbol, FunctionDescriptor descriptor,
			boolean variadic)
		{
			MemorySegment address = linker.defaultLookup().find(symbol)
				.orElseThrow(() -> new UnsupportedOperationException("Missing libc symbol: " + symbol));
			if (variadic)
				return linker.downcallHandle(address, descriptor, Linker.Option.captureCallState("errno"),
					Linker.Option.firstVariadicArg(1));
			return linker.downcallHandle(address, descriptor, Linker.Option.captureCallState("errno"));
		}

		/**
		 * Invokes a resolved native function and retains its scalar result and captured errno.
		 * @param function resolved native operation
		 * @param arguments arguments after the JDK capture-state parameter
		 * @return unmodified native result and errno
		 * @throws IOException if the foreign invocation cannot execute
		 */
		private static NativeResult call(MethodHandle function, Object... arguments) throws IOException
		{
			try (Arena arena = Arena.ofConfined())
			{
				MemorySegment state = arena.allocate(Linker.Option.captureStateLayout());
				List<Object> values = new ArrayList<>();
				values.add(state);
				values.addAll(List.of(arguments));
				try
				{
					long result = ((Number) function.invokeWithArguments(values)).longValue();
					return new NativeResult(result, state.get(ValueLayout.JAVA_INT, ERRNO_OFFSET));
				}
				catch (Throwable problem)
				{
					throw new IOException("libc downcall failed before a native result was obtained", problem);
				}
			}
		}

		/**
		 * Runs and collects the complete attempt, cleaning acquired tasks on every failure.
		 * @param argv command arguments
		 * @param environment complete child environment
		 * @param directory child working directory
		 * @param addressSpaceBytes child-only address-space cap, or zero
		 * @return authentic collected native outcomes
		 * @throws IOException if startup, tracing, callback or cleanup fails
		 */
		private Outcome execute(List<String> argv, Map<String, String> environment, Path directory,
			long addressSpaceBytes) throws IOException
		{
			try
			{
				start(argv, environment, directory, addressSpaceBytes);
				while (!owned.isEmpty())
				{
					if (cancel.get())
						throw new InterruptedIOException("Caller requested tracing cancellation");
					waitOne(true);
				}
				Terminal rootTerminal = collected.stream().filter(item -> item.identity().equals(root))
					.findFirst().orElseThrow(() -> new IOException("Root terminal status was not collected"));
				return new Outcome(rootTerminal, collected, denied);
			}
			catch (IOException | RuntimeException | Error problem)
			{
				try
				{
					terminateAll();
				}
				catch (IOException cleanup)
				{
					problem.addSuppressed(cleanup);
				}
				throw problem;
			}
		}

        /**
         * Adopts and replaces the parked native child, then owns all tracing until complete collection.
         * @param child authenticated original child
         * @param executable requested native image, or null for an unchanged group-stopped exec image
         * @param argv exact native arguments
         * @param environment exact native environment
         * @param prepared publishes the genuine initial exec stop
         * @param released host authorization for initial application execution
         * @return authentic complete native outcome
         * @throws IOException if adoption, native exec, tracing or cleanup fails
         */
        private Outcome adopt(Identity child, Path executable, List<String> argv, Map<String, String> environment,
            AtomicBoolean prepared, AtomicBoolean released) throws IOException
        {
            try
            {
                if (!identify(child.tid()).equals(child)) throw new IOException("Prepared child birth changed");
                trace(SEIZE, child.tid(), 0, MemorySegment.ofAddress(TRACE_OPTIONS));
                root = child;
                owned.put(child.tid(), child);
                trace(INTERRUPT, child.tid(), 0, MemorySegment.NULL);
                awaitStop(child.tid());
                if (executable == null)
                {
                    try (Arena arena = Arena.ofConfined()) { registers(child.tid(), arena); }
                    // A handed-over image has already exec'd, so its filter waits for its first syscall exit.
                    if (isStaticImage(child.tid())) pendingFilters.add(child.tid());
                }
                else replaceParkedChild(child.tid(), executable, argv, environment);
                prepared.set(true);
                while (!released.get() && !cancel.get()) LockSupport.parkNanos(1_000_000);
                if (cancel.get()) terminateAll();
                else
                {
                    if (executable == null) signal(child.tid(), CONTINUE);
                    trace(SYSCALL, child.tid(), 0, MemorySegment.NULL);
                }
                while (!owned.isEmpty())
                {
                    if (cancel.get()) terminateAll();
                    else waitOne(true);
                }
                Terminal terminal = collected.stream().filter(item -> item.identity().equals(root)).findFirst()
                    .orElseThrow(() -> new IOException("Original root native result was not collected"));
                return new Outcome(terminal, collected, denied);
            }
            catch (IOException | RuntimeException | Error problem)
            {
                try { terminateAll(); }
                catch (IOException cleanup) { problem.addSuppressed(cleanup); }
                throw problem;
            }
        }

        /**
         * Waits exclusively for one owned task's actual stop, remaining responsive to cancellation.
         * @param tid owned task
         * @return unmodified stopped wait status
         * @throws IOException if the task exits, cancellation arrives or wait fails
         */
        private int awaitStop(int tid) throws IOException
        {
            try (Arena arena = Arena.ofConfined())
            {
                MemorySegment value = arena.allocate(ValueLayout.JAVA_INT);
                while (true)
                {
                    if (cancel.get()) throw new InterruptedIOException("Original tracing owner cancelled");
                    NativeResult result = call(waitpid, tid, value, WAIT_OWNED | NOHANG);
                    if (result.value() == 0) { LockSupport.parkNanos(1_000_000); continue; }
                    if (result.value() == -1 && result.errno() == 4) continue;
                    if (result.value() != tid) throw new IOException("Original tracee wait failed: " + result.errno());
                    int status = value.get(ValueLayout.JAVA_INT, 0);
                    if ((status & 255) != 127)
                    {
                        collected.add(new Terminal(owned.remove(tid), status));
                        throw new IOException("Original tracee exited before its required native stop");
                    }
                    return status;
                }
            }
        }

        /**
         * Executes one native syscall at the parked waiter's own verified syscall instruction.
         * @param tid original stopped task
         * @param registers register values retained from its original image
         * @param pc verified syscall instruction address
         * @param number native syscall number
         * @param arguments six Linux syscall arguments
         * @return authentic syscall-exit or exec-event stop
         * @throws IOException if native syscall entry or completion cannot be verified
         */
        private int inject(int tid, MemorySegment registers, long pc, long number, long... arguments)
            throws IOException
        {
            if (aarch64)
            {
                LinuxAarch64Trace.prepareSyscall(registers, pc, number, arguments);
                LinuxAarch64Trace.writeRegisters(this::trace, tid, registers);
            }
            else
            {
            registers.set(ValueLayout.JAVA_LONG, 80, number);
            registers.set(ValueLayout.JAVA_LONG, 120, -1);
            registers.set(ValueLayout.JAVA_LONG, 128, pc);
            int[] offsets = {112, 104, 96, 56, 72, 64};
            for (int index = 0; index < arguments.length; ++index)
                registers.set(ValueLayout.JAVA_LONG, offsets[index], arguments[index]);
            trace(13, tid, 0, registers);
            }
            trace(SYSCALL, tid, 0, MemorySegment.NULL);
            int entered = awaitStop(tid);
            try (Arena arena = Arena.ofConfined())
            {
                MemorySegment info = syscallInfo(tid, arena);
                if (((entered >>> 8) & 255) != SYSCALL_TRAP || info.get(ValueLayout.JAVA_BYTE, 0) != 1 ||
                    info.get(ValueLayout.JAVA_LONG, 24) != number)
                    throw new IOException("Injected original-child syscall did not reach its genuine entry");
            }
            trace(SYSCALL, tid, 0, MemorySegment.NULL);
            return awaitStop(tid);
        }

        /**
         * Replaces the parked read with native mmap and execve while retaining the original child and parent.
         * @param tid original prepared task
         * @param executable requested native executable
         * @param argv exact native arguments
         * @param environment exact native environment
         * @throws IOException if the verified parked boundary or genuine exec stop cannot be established
         */
        private void replaceParkedChild(int tid, Path executable, List<String> argv, Map<String, String> environment)
            throws IOException
        {
            try (Arena arena = Arena.ofConfined())
            {
                MemorySegment before = registers(tid, arena);
                long pc = instructionPointer(before) - (aarch64 ? LinuxAarch64Trace.INSTRUCTION_BYTES : 2);
                if (aarch64 && !syscallOpcode(tid, pc).equals("010000d4"))
                    pc = instructionPointer(before);
                if (!syscallOpcode(tid, pc).equals(aarch64 ? "010000d4" : "0f05"))
                    throw new IOException("Original prepared child is not parked at its verified read syscall");
                int status = inject(tid, before, pc, mmapNumber(), 0, 131072, 3, 0x22, -1, 0);
                MemorySegment returned = registers(tid, arena);
                long address = syscallResult(returned);
                if (((status >>> 8) & 255) != SYSCALL_TRAP || address <= 0)
                    throw new IOException("Original prepared child could not allocate its native exec arguments");
                MemorySegment bytes = arena.allocate(131072, 8);
                List<String> strings = new ArrayList<>(List.of(executable.toString()));
                strings.addAll(argv);
                environment.forEach((key, value) -> strings.add(key + "=" + value));
                List<Long> pointers = new ArrayList<>();
                long offset = 0;
                for (String text : strings)
                {
                    byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
                    if (offset + encoded.length + 1 > bytes.byteSize()) throw new IOException("Native exec arguments exceed bound");
                    pointers.add(address + offset);
                    MemorySegment.copy(encoded, 0, bytes, ValueLayout.JAVA_BYTE, offset, encoded.length);
                    offset += encoded.length + 1L;
                }
                offset = (offset + 7) & -8L;
                long argvPointer = address + offset;
                for (int index = 1; index <= argv.size(); ++index)
                {
                    bytes.set(ValueLayout.JAVA_LONG, offset, pointers.get(index));
                    offset += 8;
                }
                offset += 8;
                long envPointer = address + offset;
                for (int index = 1 + argv.size(); index < pointers.size(); ++index)
                {
                    bytes.set(ValueLayout.JAVA_LONG, offset, pointers.get(index));
                    offset += 8;
                }
                offset += 8;
                for (long index = 0; index < offset; index += 8)
                    trace(POKE, tid, address + index, MemorySegment.ofAddress(bytes.get(ValueLayout.JAVA_LONG, index)));
                status = inject(tid, returned, pc, aarch64 ? LinuxAarch64Trace.EXECVE : 59,
                    address, argvPointer, envPointer);
                if (status >>> 16 != 4) throw new IOException("Original child execve did not reach its genuine exec event");
                if (!identify(tid).equals(root) || !Files.isSameFile(Path.of("/proc", Integer.toString(tid), "exe"), executable))
                    throw new IOException("Original exec changed its identity or selected a different image");
            }
        }

		/**
		 * Allocates a native argv or envp vector ending in NULL.
		 * @param arena argument lifetime extending through posix_spawn
		 * @param strings argument or environment strings
		 * @return native vector of NUL-terminated strings
		 */
		private static MemorySegment vector(Arena arena, List<String> strings)
		{
			MemorySegment values = arena.allocate(ValueLayout.ADDRESS, strings.size() + 1L);
			for (int index = 0; index < strings.size(); ++index)
				values.setAtIndex(ValueLayout.ADDRESS, index, arena.allocateFrom(strings.get(index)));
			return values;
		}

		/**
		 * Spawns a stopped bootstrap, establishes sole trace ownership and child limit, then releases its exec.
		 * @param argv command arguments
		 * @param environment complete environment
		 * @param directory child working directory
		 * @param addressSpaceBytes child-only limit, or zero
		 * @throws IOException if spawning, stopped readiness, tracing or limiting fails
		 */
		private void start(List<String> argv, Map<String, String> environment, Path directory,
			long addressSpaceBytes) throws IOException
		{
			try (Arena arena = Arena.ofConfined())
			{
				List<String> bootstrap = new ArrayList<>(List.of("sh", "-c", BOOTSTRAP,
					"allocation-bootstrap", directory.toAbsolutePath().toString()));
				bootstrap.addAll(argv);
				List<String> variables = environment.entrySet().stream()
					.map(item -> item.getKey() + "=" + item.getValue()).toList();
				MemorySegment pidValue = arena.allocate(ValueLayout.JAVA_INT);
				NativeResult spawned = call(spawn, pidValue, arena.allocateFrom("/bin/sh"), MemorySegment.NULL,
					MemorySegment.NULL, vector(arena, bootstrap), vector(arena, variables));
				if (spawned.value() != 0)
					throw new IOException("posix_spawn failed with error " + spawned.value());
				int pid = pidValue.get(ValueLayout.JAVA_INT, 0);
				// Register ownership immediately; a failed identity read must still kill and wait for this child.
				owned.put(pid, new Identity(pid, 0));
				root = identify(pid);
				owned.put(pid, root);
				MemorySegment status = arena.allocate(ValueLayout.JAVA_INT);
				NativeResult stopped = call(waitpid, pid, status, 2);
				if (stopped.value() != pid || status.get(ValueLayout.JAVA_INT, 0) != ((STOP << 8) | 127))
					throw new IOException("Bootstrap did not reach its owned SIGSTOP readiness boundary");
				trace(SEIZE, pid, 0, MemorySegment.ofAddress(TRACE_OPTIONS));
				trace(INTERRUPT, pid, 0, MemorySegment.NULL);
				NativeResult interrupted = call(waitpid, pid, status, WAIT_OWNED);
				if (interrupted.value() != pid || (status.get(ValueLayout.JAVA_INT, 0) & 255) != 127)
					throw new IOException("Seized bootstrap did not enter a ptrace stop");
				if (addressSpaceBytes > 0)
				{
					MemorySegment limits = arena.allocate(16, 8);
					limits.set(ValueLayout.JAVA_LONG, 0, addressSpaceBytes);
					limits.set(ValueLayout.JAVA_LONG, 8, addressSpaceBytes);
					NativeResult limited = call(prlimit, pid, 9, limits, MemorySegment.NULL);
					if (limited.value() != 0)
						throw new IOException("Child prlimit64 failed with errno " + limited.errno());
				}
				signal(pid, CONTINUE);
				trace(SYSCALL, pid, 0, MemorySegment.NULL);
			}
		}

		/**
		 * Reads the kernel birth identity of one already-owned task.
		 * @param tid owned kernel task ID
		 * @return task ID paired with stat start ticks
		 * @throws IOException if the native stat record cannot be read
		 */
		private static Identity identify(int tid) throws IOException
		{
			String stat = Files.readString(Path.of("/proc", Integer.toString(tid), "stat"));
			String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
			return new Identity(tid, Long.parseLong(fields[19]));
		}

		/**
		 * Invokes ptrace and rejects its same-call error without confusing a valid minus-one data word.
		 * @param operation ptrace request
		 * @param tid owned target task
		 * @param address request address or byte-count argument
		 * @param data request pointer or promoted scalar
		 * @return native ptrace result
		 * @throws IOException if ptrace returns minus one with a captured nonzero errno
		 */
		private long trace(int operation, int tid, long address, MemorySegment data) throws IOException
		{
			NativeResult result = call(ptrace, operation, tid, MemorySegment.ofAddress(address), data);
			if (result.value() == -1 && result.errno() == ESRCH)
				throw new TaskVanished("ptrace request " + operation + " for task " + tid +
					" failed with errno " + result.errno(), tid);
			if (result.value() == -1 && result.errno() != 0)
				throw new IOException("ptrace request " + operation + " for task " + tid +
					" failed with errno " + result.errno());
			return result.value();
		}

		/**
		 * Sends a signal to an owned task, accepting ESRCH when its terminal status is already pending.
		 * @param tid owned task ID
		 * @param signal native signal number
		 * @throws IOException if kill fails for a reason other than the target already exiting
		 */
		private void signal(int tid, int signal) throws IOException
		{
			NativeResult result = call(kill, tid, signal);
			if (result.value() != 0 && result.errno() != ESRCH)
				throw new IOException("kill for task " + tid + " failed with errno " + result.errno());
		}

		/**
		 * Collects or services one stop, preserving descendant tracking after the root exits.
		 * A stop whose own task the kernel kills while it is serviced is dropped; the task's terminal status is
		 * collected by a later wait. Any other task vanishing still fails, because the stopped task would stay held.
		 * @param running indicates whether normal syscall tracing remains enabled
		 * @throws IOException if wait, ptrace or a denial callback fails
		 */
        private void waitOne(boolean running) throws IOException
        {
            servicing = 0;
            try
            {
                serviceOne(running);
            }
            catch (TaskVanished vanished)
            {
                if (vanished.tid != servicing || owned.containsKey(vanished.tid) == false)
                    throw vanished;
            }
        }

		/**
		 * Collects or services one stop without accepting a task that vanishes during service.
		 * @param running indicates whether normal syscall tracing remains enabled
		 * @throws IOException if wait, ptrace or a denial callback fails
		 */
        private void serviceOne(boolean running) throws IOException
        {
            if (running && !denied) serviceContinuations();
            try (Arena arena = Arena.ofConfined())
			{
				MemorySegment statusValue = arena.allocate(ValueLayout.JAVA_INT);
				NativeResult waited = call(waitpid, -1, statusValue, WAIT_OWNED | NOHANG);
				if (waited.value() == 0)
				{
					LockSupport.parkNanos(1_000_000);
					return;
				}
				if (waited.value() < 0)
				{
					if (waited.errno() == 4)
						return;
					throw new IOException("Owned waitpid failed with errno " + waited.errno());
				}
				int tid = Math.toIntExact(waited.value());
				servicing = tid;
				int status = statusValue.get(ValueLayout.JAVA_INT, 0);
				boolean stopped = (status & 255) == 127;
                boolean unannounced = !owned.containsKey(tid);
				Identity identity = owned.get(tid);
				if (identity == null)
				{
					identity = identify(tid);
					owned.put(tid, identity);
				}
                if (unannounced && stopped && running)
                {
                    initialStops.add(tid);
                    return;
                }
				if (!stopped)
				{
					Terminal terminal = new Terminal(identity, status);
                    collected.add(terminal);
                    if (session != null && identity.equals(root)) session.rootTerminal.set(terminal);
					owned.remove(tid);
					mmapLengths.remove(tid);
					pendingFilters.remove(tid);
					filtered.remove(tid);
					return;
				}
				int event = status >>> 16;
				int stopSignal = (status >>> 8) & 255;
				switch (event)
				{
					case 1, 2, 3 ->
					{
						MemorySegment child = arena.allocate(ValueLayout.JAVA_LONG);
						trace(GETEVENTMSG, tid, 0, child);
						int childTid = Math.toIntExact(child.get(ValueLayout.JAVA_LONG, 0));
                        owned.putIfAbsent(childTid, identify(childTid));
                        // A new task inherits its creator's seccomp filter.
                        if (filtered.contains(tid)) filtered.add(childTid);
                        if (running)
                        {
                            if (!initialStops.remove(childTid)) awaitStop(childTid);
                            if (session != null && processId(childTid) == childTid)
                            {
                                Identity childIdentity = owned.get(childTid);
                                heldImages.put(childTid, childIdentity);
                                session.held.add(new Held(childIdentity, processId(tid), 0,
                                    Files.readSymbolicLink(Path.of("/proc", Integer.toString(childTid), "exe")).toString(), null));
                                forkParentHolds.put(childTid, tid);
                                return;
                            }
                            resume(childTid, 0);
                        }
                    }
                    case 4 ->
                    {
                        MemorySegment former = arena.allocate(ValueLayout.JAVA_LONG);
                        trace(GETEVENTMSG, tid, 0, former);
                        int formerTid = Math.toIntExact(former.get(ValueLayout.JAVA_LONG, 0));
                        Identity formerIdentity = executing.remove(formerTid);
                        if (formerIdentity == null) formerIdentity = owned.get(formerTid);
                        if (formerTid != tid)
                        {
                            owned.remove(formerTid);
                            mmapLengths.remove(formerTid);
                            // The exec'ing thread keeps its own filter as it takes over the leader's ID.
                            filtered.remove(tid);
                            if (filtered.remove(formerTid)) filtered.add(tid);
                            Identity migrated = identify(tid);
                            owned.put(tid, migrated);
                            if (root.tid() == tid) root = migrated;
                        }
                        mmapLengths.remove(tid);
                        if (!filtered.contains(tid) && isStaticImage(tid)) pendingFilters.add(tid);
                        if (session != null && running)
                        {
                            Identity current = owned.get(tid);
                            heldImages.put(tid, current);
                            session.held.add(new Held(current, tid, 1,
                                Files.readSymbolicLink(Path.of("/proc", Integer.toString(tid), "exe")).toString(), formerIdentity));
                            return;
                        }
                    }
					default -> { }
				}
				if (!running)
				{
					signal(tid, KILL);
					trace(CONT, tid, 0, MemorySegment.ofAddress(KILL));
					return;
				}
                if (stopSignal == SYSCALL_TRAP || event == SECCOMP_EVENT)
                {
                    if (gateNonleaderExec(tid, arena)) return;
                    handleSyscall(tid, arena);
                    // A clean user state exists only at a syscall exit, the first one after the image starts.
                    if (stopSignal == SYSCALL_TRAP && pendingFilters.contains(tid) && !isEntry(syscallInfo(tid, arena)))
                    {
                        pendingFilters.remove(tid);
                        installFilter(tid, arena);
                        filtered.add(tid);
                    }
                }
                if (denied)
                {
                    if (session == null) terminateAll();
                    return;
                }
                if (event == 128 && stopSignal != TRAP)
                {
                    trace(0x4208, tid, 0, MemorySegment.NULL);
                    return;
                }
                int deliver = switch (stopSignal)
                {
                    case SYSCALL_TRAP -> 0;
					case TRAP -> event == 0 ? TRAP : 0;
					default -> stopSignal;
				};
				resume(tid, deliver);
			}
		}

        /**
         * Reads the actual thread-group ID from the native proc view.
         * @param tid retained owned task
         * @return actual positive process ID
         * @throws IOException if the kernel process identity is unavailable
         */
        private static int processId(int tid) throws IOException
        {
            for (String line : Files.readAllLines(Path.of("/proc", Integer.toString(tid), "status")))
                if (line.startsWith("Tgid:")) return Integer.parseInt(line.substring(5).strip());
            throw new IOException("Owned task has no kernel thread-group identity");
        }

        /**
         * Applies exact host-issued capabilities on the sole tracing platform thread.
         * @throws IOException if birth, ownership or native continuation differs
         */
        private void serviceContinuations() throws IOException
        {
            if (session == null) return;
            for (Map.Entry<Integer, Identity> held : List.copyOf(heldImages.entrySet()))
            {
                boolean detach = session.imageDetaches.remove(held.getValue());
                if (!detach && !session.imageReleases.remove(held.getValue())) continue;
                if (!identify(held.getKey()).equals(held.getValue())) throw new IOException("Held image birth changed before continuation");
                if (detach)
                {
                    // Without its tracer, the filter would fail every mmap of the image and its children with ENOSYS.
                    if (filtered.contains(held.getKey()))
                        throw new IOException("An image started under a filtered static image cannot be released untraced");
                    // Detaching also stops following its forks, so its later children are never traced here.
                    trace(DETACH, held.getKey(), 0, MemorySegment.NULL);
                    owned.remove(held.getKey());
                    mmapLengths.remove(held.getKey());
                    pendingFilters.remove(held.getKey());
                }
                else resume(held.getKey(), 0);
                heldImages.remove(held.getKey());
                Integer parent = forkParentHolds.remove(held.getKey());
                if (parent != null) resume(parent, 0);
            }
            for (Map.Entry<Integer, Identity> held : List.copyOf(heldExec.entrySet()))
            {
                if (!session.execReleases.remove(held.getValue())) continue;
                if (!identify(held.getKey()).equals(held.getValue())) throw new IOException("Pre-exec thread birth changed before authorization");
                executing.put(held.getKey(), held.getValue());
                trace(SYSCALL, held.getKey(), 0, MemorySegment.NULL);
                heldExec.remove(held.getKey());
            }
        }

        /**
         * Holds a nonleader before native exec until the host independently observes its original identity.
         * @param tid stopped syscall task
         * @param arena native observation lifetime
         * @return true when the task remains held for host authorization
         * @throws IOException if syscall or thread identity cannot be observed
         */
        private boolean gateNonleaderExec(int tid, Arena arena) throws IOException
        {
            if (session == null) return false;
            MemorySegment info = syscallInfo(tid, arena);
            long number = info.get(ValueLayout.JAVA_LONG, 24);
            long execve = aarch64 ? LinuxAarch64Trace.EXECVE : EXECVE;
            long execveat = aarch64 ? LinuxAarch64Trace.EXECVEAT : EXECVEAT;
            if (!isEntry(info) || (number != execve && number != execveat)) return false;
            int process = processId(tid);
            if (tid == process) return false;
            Identity identity = owned.get(tid);
            heldExec.put(tid, identity);
            session.preExec.add(new PreExec(identity, process));
            return true;
        }

		/**
		 * Observes mmap entry and exit, injecting a trap only after a genuine ENOMEM return.
		 * @param tid stopped allocating task
		 * @param arena native argument lifetime
		 * @throws IOException if syscall observation fails
		 */
		private void handleSyscall(int tid, Arena arena) throws IOException
		{
			MemorySegment info = syscallInfo(tid, arena);
			int operation = Byte.toUnsignedInt(info.get(ValueLayout.JAVA_BYTE, 0));
			if (isEntry(info))
			{
				mmapLengths.remove(tid);
				if (info.get(ValueLayout.JAVA_LONG, 24) == mmapNumber())
					mmapLengths.put(tid, info.get(ValueLayout.JAVA_LONG, 40));
				return;
			}
			if (operation != 2) return;
			// Removing the length at exit lets a filtered task continue without syscall stops.
			Long requestedBytes = mmapLengths.remove(tid);
			boolean deniedMmap = requestedBytes != null && info.get(ValueLayout.JAVA_LONG, 24) == NO_MEMORY;
			if (deniedMmap)
				holdDeniedReturn(tid, requestedBytes, info.get(ValueLayout.JAVA_LONG, 8), arena);
		}

		/**
		 * Resumes a stopped task, stopping at every syscall unless the task is filtered and has no mmap in flight.
		 * @param tid stopped owned task
		 * @param signal signal to deliver, or zero
		 * @throws IOException if ptrace fails
		 */
		private void resume(int tid, long signal) throws IOException
		{
			boolean everySyscall = !filtered.contains(tid) || mmapLengths.containsKey(tid) || pendingFilters.contains(tid);
			trace(everySyscall ? SYSCALL : CONT, tid, 0, MemorySegment.ofAddress(signal));
		}

		/**
		 * Reports whether a syscall observation is an entry, from either a syscall-entry stop or a seccomp stop.
		 * @param info complete native syscall observation
		 * @return true for an entry, whose number and arguments use the same offsets in both forms
		 */
		private static boolean isEntry(MemorySegment info)
		{
			int operation = Byte.toUnsignedInt(info.get(ValueLayout.JAVA_BYTE, 0));
			return operation == 1 || operation == 3;
		}

		/**
		 * Reports whether a task's current image is statically linked.
		 * @param tid stopped owned task
		 * @return true for a static image
		 * @throws IOException if the image cannot be read
		 */
		private static boolean isStaticImage(int tid) throws IOException
		{
			try (FileChannel image = FileChannel.open(Path.of("/proc", Integer.toString(tid), "exe")))
			{
				return LinuxBazelHelperMain.isStaticTraceImage(image);
			}
		}

		/**
		 * Installs a seccomp filter that stops the task only at mmap, execve and execveat, which are the only calls
		 * this tracer inspects. The filter is inherited by every task the image creates, so its tasks are resumed with
		 * {@code PTRACE_CONT} and stop only for those calls. A static image does not exec our wrapper, so its tree is
		 * never released untraced, where the filter would fail every mmap with ENOSYS.
		 * <p>
		 * The task must be at a syscall exit, normally its exec's. The filter is installed by injected calls at the
		 * task's current instruction, which is temporarily replaced by a syscall instruction; the instruction and every
		 * register are restored afterward.
		 * @param tid task stopped at a syscall exit
		 * @param arena native storage lifetime
		 * @throws IOException if an injected call fails
		 */
		private void installFilter(int tid, Arena arena) throws IOException
		{
			MemorySegment original = registers(tid, arena);
			MemorySegment working = arena.allocate(original.byteSize(), 8).copyFrom(original);
			long pc = instructionPointer(original);
			int width = aarch64 ? LinuxAarch64Trace.INSTRUCTION_BYTES : 2;
			long opcode = aarch64 ? 0xd4000001L : 0x050fL;
			// Patch inside one aligned word, so the poke never reaches past the instruction's own mapping.
			long base = (pc & 7) + width <= 8 ? pc & -8L : pc - (8 - width);
			int shift = Math.toIntExact((pc - base) * 8);
			long mask = ((1L << (width * 8)) - 1) << shift;
			long word = trace(PEEK, tid, base, MemorySegment.NULL);
			trace(POKE, tid, base, MemorySegment.ofAddress((word & ~mask) | (opcode << shift)));
			try
			{
				long page = injectChecked(tid, working, pc, mmapNumber(), 0, 4096, 3, 0x22, -1, 0);
				byte[] program = filterProgram();
				MemorySegment bytes = arena.allocate(16 + program.length, 8);
				bytes.set(ValueLayout.JAVA_SHORT, 0, (short) (program.length / 8));
				bytes.set(ValueLayout.JAVA_LONG, 8, page + 16);
				MemorySegment.copy(program, 0, bytes, ValueLayout.JAVA_BYTE, 16, program.length);
				for (long index = 0; index < bytes.byteSize(); index += 8)
					trace(POKE, tid, page + index, MemorySegment.ofAddress(bytes.get(ValueLayout.JAVA_LONG, index)));
				// PR_SET_NO_NEW_PRIVS lets an unprivileged task install a filter.
				injectChecked(tid, registers(tid, arena), pc, aarch64 ? LinuxAarch64Trace.PRCTL : PRCTL, 38, 1, 0, 0, 0);
				// SECCOMP_SET_MODE_FILTER with no flags.
				injectChecked(tid, registers(tid, arena), pc, aarch64 ? LinuxAarch64Trace.SECCOMP : SECCOMP, 1, 0, page);
				injectChecked(tid, registers(tid, arena), pc, aarch64 ? LinuxAarch64Trace.MUNMAP : MUNMAP, page, 4096);
			}
			finally
			{
				trace(POKE, tid, base, MemorySegment.ofAddress(word));
				if (aarch64) LinuxAarch64Trace.writeRegisters(this::trace, tid, original);
				else trace(13, tid, 0, original);
			}
		}

		/**
		 * Injects one syscall and requires a successful result.
		 * @param tid stopped owned task
		 * @param registers registers to start from; modified in place
		 * @param pc address of a syscall instruction
		 * @param number native syscall number
		 * @param arguments syscall arguments
		 * @return the syscall's nonnegative result
		 * @throws IOException if the call does not complete or returns an error
		 */
		private long injectChecked(int tid, MemorySegment registers, long pc, long number, long... arguments)
			throws IOException
		{
			int status = inject(tid, registers, pc, number, arguments);
			if (((status >>> 8) & 255) != SYSCALL_TRAP)
				throw new IOException("Injected filter syscall " + number + " did not return to its tracer");
			try (Arena arena = Arena.ofConfined())
			{
				long result = syscallResult(registers(tid, arena));
				if (result < 0 && result >= -4095)
					throw new IOException("Injected filter syscall " + number + " failed with errno " + -result);
				return result;
			}
		}

		/**
		 * Builds the classic BPF seccomp program for the active ABI: trace mmap, execve and execveat; allow the rest.
		 * A call from another syscall ABI is allowed unobserved, as before the filter.
		 * @return little-endian {@code sock_filter} instructions
		 */
		private byte[] filterProgram()
		{
			long architecture = aarch64 ? 0xc00000b7L : 0xc000003eL;
			long mmap = mmapNumber();
			long execve = aarch64 ? LinuxAarch64Trace.EXECVE : EXECVE;
			long execveat = aarch64 ? LinuxAarch64Trace.EXECVEAT : EXECVEAT;
			// BPF_LD|BPF_W|BPF_ABS = 0x20, BPF_JMP|BPF_JEQ|BPF_K = 0x15, BPF_RET|BPF_K = 0x06.
			long allow = 0x7fff0000L, traced = 0x7ff00000L;
			long[][] instructions = {
				{0x20, 0, 0, 4},
				{0x15, 1, 0, architecture},
				{0x06, 0, 0, allow},
				{0x20, 0, 0, 0},
				{0x15, 3, 0, mmap},
				{0x15, 2, 0, execve},
				{0x15, 1, 0, execveat},
				{0x06, 0, 0, allow},
				{0x06, 0, 0, traced}};
			ByteBuffer buffer = ByteBuffer.allocate(instructions.length * 8).order(ByteOrder.LITTLE_ENDIAN);
			for (long[] instruction : instructions)
				buffer.putShort((short) instruction[0]).put((byte) instruction[1]).put((byte) instruction[2])
					.putInt((int) instruction[3]);
			return buffer.array();
		}

		/**
		 * Reads one task register block using the active native ABI.
		 * @param tid owned stopped task
		 * @param arena storage lifetime
		 * @return complete native general-register bytes
		 * @throws IOException if native register access fails
		 */
		private MemorySegment registers(int tid, Arena arena) throws IOException
		{
			if (aarch64)
				return LinuxAarch64Trace.readRegisters(this::trace, tid, arena);
			MemorySegment registers = arena.allocate(216, 8);
			trace(GETREGS, tid, 0, registers);
			return registers;
		}

        /**
         * Reads a complete syscall observation and rejects a different instruction ABI or truncated record.
         * @param tid stopped owned task
         * @param arena observation lifetime
         * @return authenticated native syscall observation
         * @throws IOException if ptrace fails, returns a partial record, or reports an unsupported instruction ABI
         */
        private MemorySegment syscallInfo(int tid, Arena arena) throws IOException
        {
            MemorySegment info = arena.allocate(88, 8);
            long actual = trace(GET_SYSCALL_INFO, tid, info.byteSize(), info);
            int operation = Byte.toUnsignedInt(info.get(ValueLayout.JAVA_BYTE, 0));
            long required = switch (operation)
            {
                case 0 -> 24;
                case 1 -> 80;
                case 2 -> 33;
                case 3 -> 84;
                default -> throw new IOException("Unsupported native syscall observation operation: " + operation);
            };
            if (actual < required)
                throw new IOException("Native syscall observation returned " + actual + " bytes; required " + required);
            int architecture = info.get(ValueLayout.JAVA_INT, 4);
            int expected = aarch64 ? 0xc00000b7 : 0xc000003e;
            if (architecture != expected)
                throw new IOException("Native syscall observation architecture is " +
                    Integer.toUnsignedString(architecture, 16) + "; required " +
                    Integer.toUnsignedString(expected, 16));
            return info;
        }

        /**
         * Reads the active ABI's signed syscall result without changing native registers.
         * @param registers complete native register block
         * @return signed native return value
         */
        private long syscallResult(MemorySegment registers)
        {
            return aarch64 ? LinuxAarch64Trace.result(registers) : registers.get(ValueLayout.JAVA_LONG, 80);
        }

        /**
         * Reads the active ABI's native instruction pointer.
         * @param registers complete native register block
         * @return native instruction address
         */
        private long instructionPointer(MemorySegment registers)
        {
            return aarch64 ? LinuxAarch64Trace.pc(registers) : registers.get(ValueLayout.JAVA_LONG, 128);
        }

        /**
         * Resolves the active native mmap syscall number.
         * @return mmap number for the active instruction ABI
         */
        private long mmapNumber()
        {
            return aarch64 ? LinuxAarch64Trace.MMAP : MMAP;
        }

        /**
         * Reads a complete native syscall instruction as little-endian hexadecimal bytes.
         * @param tid stopped owned task
         * @param pc start of the native instruction
         * @return two x86-64 bytes or four AArch64 bytes
         * @throws IOException if native instruction memory cannot be read
         */
        private String syscallOpcode(int tid, long pc) throws IOException
        {
            StringBuilder bytes = new StringBuilder();
            int width = aarch64 ? LinuxAarch64Trace.INSTRUCTION_BYTES : 2;
            for (int index = 0; index < width; ++index)
                bytes.append("%02x".formatted(instructionByte(tid, pc + index)));
            return bytes.toString();
        }

		/**
		 * Reads one instruction byte without reading across its aligned native word.
		 * @param tid owned stopped task
		 * @param address instruction address
		 * @return unsigned native instruction byte
		 * @throws IOException if ptrace cannot read the word
		 */
		private int instructionByte(int tid, long address) throws IOException
		{
			long word = trace(PEEK, tid, address & -8L, MemorySegment.NULL);
			int shift = Math.toIntExact((address & 7) * 8);
			return (int) ((word >>> shift) & 255);
		}

		/**
		 * Executes a native INT3 or BRK after the denied mmap without modifying the native return register.
		 * @param tid owned allocating thread at syscall exit
		 * @param requestedBytes genuine mmap length observed at entry
		 * @param returnAddress kernel-reported instruction pointer at syscall exit
		 * @param arena native observation lifetime
		 * @throws IOException if opcode, mapping, registers, or executed trap cannot be verified
		 */
		private void holdDeniedReturn(int tid, long requestedBytes, long returnAddress, Arena arena)
			throws IOException
		{
			MemorySegment before = registers(tid, arena);
			int instructionBytes = aarch64 ? LinuxAarch64Trace.INSTRUCTION_BYTES : 1;
			if (aarch64 && (returnAddress & 3) != 0)
				throw new IOException("AArch64 denied mmap return address is not four-byte aligned");
			String opcode = syscallOpcode(tid, returnAddress - (aarch64 ? instructionBytes : 2));
			if (syscallResult(before) != NO_MEMORY || instructionPointer(before) != returnAddress ||
				!opcode.equals(aarch64 ? "010000d4" : "0f05"))
				throw new IOException("Denied mmap did not stop immediately after its native syscall opcode");
			String mapping = null;
			for (String line : Files.readAllLines(Path.of("/proc", Integer.toString(tid), "maps")))
			{
				String[] fields = line.split("\\s+", 3);
				String[] range = fields[0].split("-");
				long start = Long.parseUnsignedLong(range[0], 16);
				long end = Long.parseUnsignedLong(range[1], 16);
                if (returnAddress >= start && returnAddress < end && end - returnAddress >= instructionBytes && fields[1].contains("x") &&
                    fields[1].endsWith("p"))
				{
					mapping = line;
					break;
				}
			}
			if (mapping == null)
				throw new IOException("Denied mmap return instruction has no executable native mapping");
			long wordAddress = returnAddress & -8L;
			int shift = Math.toIntExact((returnAddress & 7) * 8);
			long originalWord = trace(PEEK, tid, wordAddress, MemorySegment.NULL);
			int originalByte = aarch64 ? LinuxAarch64Trace.originalInstruction(originalWord, returnAddress) :
				(int) ((originalWord >>> shift) & 255);
			if (originalByte == (aarch64 ? 0xd4200000 : 0xcc))
				throw new IOException("Denied mmap return instruction already contains the injected trap");
			long mask = 255L << shift;
			long patchedWord = aarch64 ? LinuxAarch64Trace.patchTrap(originalWord, returnAddress) :
				(originalWord & ~mask) | (0xccL << shift);
			freezeOthers(tid);
			trace(POKE, tid, wordAddress, MemorySegment.ofAddress(patchedWord));
			trace(CONT, tid, 0, MemorySegment.NULL);
			MemorySegment statusValue = arena.allocate(ValueLayout.JAVA_INT);
			while (true)
			{
				if (cancel.get())
					throw new InterruptedIOException("Caller cancelled before injected trap completed");
				NativeResult waited = call(waitpid, tid, statusValue, WAIT_OWNED | NOHANG);
				if (waited.value() == 0)
				{
					LockSupport.parkNanos(1_000_000);
					continue;
				}
				if (waited.value() != tid)
					throw new IOException("Waiting for injected trap failed with errno " + waited.errno());
				int status = statusValue.get(ValueLayout.JAVA_INT, 0);
				if ((status & 255) != 127)
				{
					collected.add(new Terminal(owned.remove(tid), status));
					throw new IOException("Allocating thread exited before executing its injected trap");
				}
				int stoppedBy = (status >>> 8) & 255;
				if (stoppedBy == TRAP && (status >>> 16) == 0)
					break;
				trace(CONT, tid, 0, MemorySegment.ofAddress(stoppedBy));
			}
			MemorySegment trapped = registers(tid, arena);
			if (syscallResult(trapped) != NO_MEMORY ||
				instructionPointer(trapped) != returnAddress + (aarch64 ? 0 : 1))
				throw new IOException("Executed native trap did not preserve the genuine denied mmap return");
			int processId = 0;
			for (String line : Files.readAllLines(Path.of("/proc", Integer.toString(tid), "status")))
				if (line.startsWith("Tgid:"))
					processId = Integer.parseInt(line.substring(5).strip());
			Denial denial = new Denial(owned.get(tid), processId, mmapNumber(), requestedBytes, NO_MEMORY,
				returnAddress, instructionPointer(trapped), opcode, originalByte, mapping);
			denied = true;
			handler.terminate(denial);
		}

        /**
         * Stops every other owned task before modifying an instruction shared by an address space.
         * The complete attempt stays stopped thereafter until explicit whole-attempt termination.
         * @param allocatingTid original allocating task, already held at syscall exit
         * @throws IOException if the whole-tracee stop barrier cannot be established
         */
        private void freezeOthers(int allocatingTid) throws IOException
        {
            java.util.Set<Integer> pending = new java.util.HashSet<>(owned.keySet());
            pending.remove(allocatingTid);
            pending.removeAll(heldImages.keySet());
            pending.removeAll(heldExec.keySet());
            pending.removeAll(forkParentHolds.values());
            for (int tid : pending)
            {
                NativeResult interrupted = call(ptrace, INTERRUPT, tid, MemorySegment.NULL, MemorySegment.NULL);
                if (interrupted.value() == -1 && interrupted.errno() != 3 && interrupted.errno() != 5)
                    throw new IOException("Whole-attempt trace stop failed: " + interrupted.errno());
            }
            try (Arena arena = Arena.ofConfined())
            {
                MemorySegment value = arena.allocate(ValueLayout.JAVA_INT);
                while (!pending.isEmpty())
                {
                    if (cancel.get()) throw new InterruptedIOException("Whole-attempt trace stop cancelled");
                    NativeResult waited = call(waitpid, -1, value, WAIT_OWNED | NOHANG);
                    if (waited.value() == 0) { LockSupport.parkNanos(1_000_000); continue; }
                    if (waited.value() < 0)
                    {
                        if (waited.errno() == 4) continue;
                        throw new IOException("Whole-attempt trace stop wait failed: " + waited.errno());
                    }
                    int tid = Math.toIntExact(waited.value());
                    int status = value.get(ValueLayout.JAVA_INT, 0);
                    pending.remove(tid);
                    if ((status & 255) != 127)
                    {
                        Identity identity = owned.remove(tid);
                        if (identity == null) throw new IOException("Unknown terminal task at whole-attempt trace stop");
                        collected.add(new Terminal(identity, status));
                        continue;
                    }
                    int event = status >>> 16;
                    if (event == 1 || event == 2 || event == 3)
                    {
                        MemorySegment child = arena.allocate(ValueLayout.JAVA_LONG);
                        trace(GETEVENTMSG, tid, 0, child);
                        int childTid = Math.toIntExact(child.get(ValueLayout.JAVA_LONG, 0));
                        owned.putIfAbsent(childTid, identify(childTid));
                        pending.add(childTid);
                    }
                }
            }
        }

		/**
		 * Kills and collects every traced task, including children discovered during terminal collection.
		 * @throws IOException if native killing or collection fails
		 */
		private void terminateAll() throws IOException
		{
			for (int tid : List.copyOf(owned.keySet()))
				signal(tid, KILL);
			while (!owned.isEmpty())
				waitOne(false);
		}
	}
}
