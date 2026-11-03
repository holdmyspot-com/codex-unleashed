package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Owns exec gating and per-image allocation and optional Cargo token hooks for one contained Linux workload.
 * <p>
 * The workload root is a process that its native parent holds before {@code execve}. Instrumentation attaches to that
 * held process, enables child gating, and only then lets the parent release it, so the root's exec is reported as a
 * held child event and the compiler image is instrumented before its first instruction runs.
 * <p>
 * Native callbacks copy messages only. All transitions and peer service run on the constructing thread.
 * Forked children and verified held exec replacements receive fresh hooks before resume. Replaced image owners remain
 * retained until actual whole-workload zero; their pending allocation waits lose grant authority before replacement.
 */
public final class AllocationHooks implements LinuxAttempts.Instrumentation
{
    /**
     * Invocation-owned device-event dispatcher. Current containment selects a controller; that controller still
     * authenticates lineage and resumes only through its own retained original manager. No native event creates an
     * action identity. Duplicate pending deliveries are coalesced. The callback API carries no transition identifier,
     * so after a successful continuation an identical event from a source that already reported it is a new held
     * transition (a manager reports each hold once), while another source's first copy is a late duplicate that grants
     * nothing. A delivery after an uncertain continuation is rejected.
     */
    public static final class Routing
    {
        /** Exclusive controller thread. */
        private final Thread thread = Thread.currentThread();
        /** Active original image controllers. */
        private final List<Registration> owners = new java.util.ArrayList<>();
        /** Retained pending and completed transition dispositions for this invocation. */
        private final Map<FridaRuntime.Child, Transition> transitions = new LinkedHashMap<>();

        /** Creates an empty invocation-owned route with no native resources. */
        public Routing() {}

        /**
         * Registers one original controller before polling shared device events.
         * @param membership independent current containment selection
         * @param delivery original controller's held-event queue
         * @return source and ownership capability retained until successful controller closure
         */
        public Registration register(Membership membership, Delivery delivery)
        {
            requireThread();
            var registration = new Registration(Objects.requireNonNull(membership, "membership"),
                Objects.requireNonNull(delivery, "delivery"));
            owners.add(registration);
            return registration;
        }

        /**
         * Routes a copied device event without transferring continuation authority to its receiving manager.
         * @param source original manager's registered receiver
         * @param child copied held-image event
         * @throws IOException if current ownership is absent, ambiguous, canceled or already continued
         */
        public void route(Registration source, FridaRuntime.Child child) throws IOException
        {
            requireThread();
            if (!owners.contains(source)) throw new IOException("Device event source is not retained");
            Objects.requireNonNull(child, "child");
            verifyOrigin(child.origin());
            Registration target = null;
            for (Registration candidate : List.copyOf(owners))
            {
                if (!candidate.membership.owns(child.pid())) continue;
                if (target != null) throw new IOException("Held image has ambiguous controller ownership");
                target = candidate;
            }
            if (target == null || target.canceled) throw new IOException("Held image has no active owning controller");
            Transition previous = transitions.get(child);
            if (previous != null)
            {
                if (previous.completed && previous.sources.contains(source))
                    previous = null;
                else if (previous.completed)
                {
                    // Another manager's late copy of a transition that already continued needs no action.
                    previous.sources.add(source);
                    return;
                }
                else if (previous.target != target || previous.attempted)
                    throw new IOException("Held image delivery cannot authorize a second continuation");
                else
                {
                    previous.sources.add(source);
                    return;
                }
            }
            var transition = new Transition(target);
            transition.sources.add(source);
            transitions.put(child, transition);
            target.delivery.enqueue(child);
        }

        /**
         * Reserves the one possibly effective native continuation before the original controller calls its manager.
         * @param owner independently selected original controller
         * @param child exact dequeued held event
         * @throws IOException if ownership was canceled or continuation was already attempted
         */
        public void prepare(Registration owner, FridaRuntime.Child child) throws IOException
        {
            requireThread();
            Transition transition = transitions.get(child);
            if (transition == null || transition.target != owner || transition.attempted || owner.canceled)
                throw new IOException("Held image continuation is canceled, unowned or already attempted");
            transition.attempted = true;
        }

        /**
         * Records the sole original owner's successful continuation without issuing another native operation.
         * @param owner original owning controller
         * @param child exact dequeued transition
         * @throws IOException if the continuation owner or transition differs
         */
        public void completed(Registration owner, FridaRuntime.Child child) throws IOException
        {
            requireThread();
            Transition transition = transitions.get(child);
            if (transition == null || transition.target != owner || !transition.attempted || transition.completed || owner.canceled)
                throw new IOException("Held image continuation differs from retained owning controller");
            transition.completed = true;
        }

        /** Requires the same controller thread before examining source or ownership capabilities. */
        private void requireThread()
        {
            if (Thread.currentThread() != thread) throw new IllegalStateException("Image routing belongs to its controller thread");
        }

        /** One current controller's membership, original event queue and terminal cancellation state. */
        public final class Registration implements AutoCloseable
        {
            /** Independent current containment reader. */
            private final Membership membership;
            /** Original controller's queue; only that controller may issue continuation. */
            private final Delivery delivery;
            /** Cancellation prevents later delivery or continuation acknowledgement. */
            private boolean canceled;

            /** Retains explicit controller boundaries without native acquisition.
             * @param membership current containment reader
             * @param delivery original event queue
             */
            private Registration(Membership membership, Delivery delivery)
            { this.membership = membership; this.delivery = delivery; }

            /** Prevents further automatic continuation while retaining ownership for cleanup. */
            public void cancel() { requireThread(); canceled = true; }

            /** Removes selection only after the caller's authentic native controller closure. */
            @Override public void close() { requireThread(); canceled = true; owners.remove(this); }
        }

        /** One original transition owner, every receiving source and its irreversible disposition. */
        private static final class Transition
        {
            /** Controller selected from independent current membership. */
            private final Registration target;
            /** Every source manager whose copied callback named this still-pending transition. */
            private final java.util.Set<Registration> sources = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            /** A native continuation may already have taken effect, including an uncertain failure. */
            private boolean attempted;
            /** Successful continuation has already returned. */
            private boolean completed;

            /** Retains the original sole controller before delivering its held event.
             * @param target independently selected original owner
             */
            private Transition(Registration target) { this.target = target; }
        }
    }

    /** Independent containment selection; a positive result alone grants no image or action authority. */
    @FunctionalInterface
    public interface Membership
    {
        /** Checks actual current membership.
         * @param pid candidate process
         * @return whether the retained controller's containment currently contains this process
         * @throws IOException if membership cannot be established
         */
        boolean owns(long pid) throws IOException;
    }

    /** Original controller's retained held-event queue. */
    @FunctionalInterface
    public interface Delivery
    {
        /** Retains one event without resuming or granting allocation permission.
         * @param child copied held event
         */
        void enqueue(FridaRuntime.Child child);
    }

    /**
     * Selects actual native cgroup membership without treating callback metadata as authority.
     * Full image, credential, executable and ancestry authentication remains the selected controller's operation.
     * @param containment original controller's retained native subtree
     * @param pid current process identity
     * @return whether an actual retained cgroup lists this process
     * @throws IOException if a current membership read fails
     */
    static boolean contains(Path containment, long pid) throws IOException
    {
        try (var groups = Files.walk(containment))
        {
            for (Path directory : groups.filter(Files::isDirectory).toList())
                if (Files.readAllLines(directory.resolve("cgroup.procs")).contains(Long.toString(pid))) return true;
        }
        return false;
    }


	/** Borrowed invocation values; the attempt never closes this scope. */
	private final ToolingScope scope;
	/** Exclusive controller thread. */
	private final Thread owner = Thread.currentThread();
	/** Native attempt correlation. */
	private final UUID identity;
	/** Borrowed build device whose manager reports, gates and resumes this attempt's held images; never closed here. */
	private final LinuxFridaDevice device;
	/** Immutable launch configuration. */
	private final LinuxProcessTree.Launch launch;
	/** Held root process and the owned cgroup subtree that contains the whole attempt. */
	private final Root root;
	/** Borrowed attempt wait batches. */
	private final AllocationWaits waits;
	/** Bounded control interval. */
	private final long timeoutNanos;
	/** Peer service without queue reentry. */
	private final AllocationHook.Pump peers;
	/** Optional invocation FIFO identity and borrowed ledger for this whole attempt. */
	private final Optional<CargoTokens> cargoTokens;
    /** Explicit invocation-owned routing shared with Cargo metadata and sibling attempts. */
    private final Routing routing;
    /** This controller's source/ownership capability retained through actual native closure. */
    private Routing.Registration route;

	/** Borrowed device manager, null until start reaches the device. */
	private FridaRuntime.Manager manager;
    /** Sole static-image native owners retained through authentic whole-workload zero. */
    private final LinuxAllocationTrace.Images nativeImages;
    /** Installation generations backed by the native tracer rather than a Frida script. */
    private final java.util.Set<Long> nativeInstallations = new java.util.HashSet<>();
    /** Whether a genuine executed native denial requires whole-attempt retry. */
    private boolean nativeRetry;
	/** Owned attachment to the held root before its exec, which gates the exec and every later child. */
	private FridaRuntime.Manager.Session gate;
	/** Retained copied held-child events. */
	private final ArrayDeque<FridaRuntime.Child> children = new ArrayDeque<>();
	/** Every acquired installation, including partial starts and retired images. */
	private final Map<Long, AllocationHook> installations = new LinkedHashMap<>();
	/** Every Cargo token owner, including partial starts and retired images, keyed by the same generation. */
	private final Map<Long, CargoTokenHook> tokenInstallations = new LinkedHashMap<>();
	/** Current installation by PID; retired owners remain in installations for whole-workload cleanup. */
	private final Map<Long, Long> activeInstallations = new LinkedHashMap<>();
	/** Current verified image by PID. */
	private final Map<Long, Image> images = new LinkedHashMap<>();
	/** Next reserved installation ordinal. */
	private long nextInstallation = 1;
	/** Script text loaded after the owner was retained. */
	private String program;
	/** Packaged Cargo token source, loaded only for attempts with an invocation lease. */
	private String tokenProgram;
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

    /**
     * Borrows explicit invocation routing without acquiring resources or granting image/action authority.
     * @param scope borrowed shared values
     * @param identity authentic attempt correlation
     * @param device borrowed build device whose manager this attempt's sessions use
     * @param launch immutable admitted attempt launch, whose first argument is the executable the root execs
     * @param root held root process, already moved into and verified in its owned cgroup subtree
     * @param waits authentic attempt allocation waits
     * @param timeout bounded native control interval
     * @param peers peer pumping without queue reentry
     * @param cargoTokens authentic optional attempt FIFO/ledger
     * @param routing invocation-owned original-controller event dispatcher
     */
    AllocationHooks(ToolingScope scope, UUID identity, LinuxFridaDevice device, LinuxProcessTree.Launch launch, Root root,
        AllocationWaits waits, Duration timeout, AllocationHook.Pump peers, Optional<CargoTokens> cargoTokens,
        Routing routing)
	{
		this.routing = Objects.requireNonNull(routing, "routing");
		this.scope = Objects.requireNonNull(scope, "scope");
		this.identity = Objects.requireNonNull(identity, "identity");
		this.device = Objects.requireNonNull(device, "device");
		this.launch = Objects.requireNonNull(launch, "launch");
		this.root = Objects.requireNonNull(root, "root");
		this.waits = Objects.requireNonNull(waits, "waits");
		timeoutNanos = LinuxAttempts.checkedTimeout(timeout).toNanos();
        nativeImages = new LinuxAllocationTrace.Images(scope.getNativeLinker(), timeout);
		this.peers = Objects.requireNonNull(peers, "peers");
		this.cargoTokens = Objects.requireNonNull(cargoTokens, "cargoTokens");
	}

	/**
	 * Attaches to the held root, releases it, and instruments the image it executes before that image runs.
	 * <p>
	 * The root is blocked before its exec by the process that forked it, so attaching first precedes every instruction
	 * of the compiler. Child gating holds the root at its exec, which Frida reports as a child event of origin 1 whose
	 * pid and parent pid are both the root; the new image is verified and instrumented before it is resumed, and later
	 * forks and execs go through the same gate. If the root stops running before its exec is reported, the exec failed
	 * or the root was killed: nothing is installed and the attempt's outcome comes from the root's exit status.
	 * @param release lets the held root run its exec; called once, after the attachment gates the exec
	 * @throws NullPointerException  if {@code release} is null
	 * @throws IllegalStateException if instrumentation was already started or stopped
	 * @throws IOException           if any identity, acquisition or control cannot be established
	 */
	@Override
	public void start(Release release) throws IOException
	{
		requireOwner();
		Objects.requireNonNull(release, "release");
		requireCertain();
		if (startAttempted || stopped)
			throw new IllegalStateException("Instrumentation cannot start again");
		startAttempted = true;
		advancing = true;
		try
		{
			program = AllocationHook.linuxProgram();
			if (cargoTokens.isPresent()) tokenProgram = CargoTokenHook.linuxProgram();
			long pid = root.pid();
			Image held = inspect(pid, true, null);
			if (held.started() != root.started())
				throw new IOException("Held attempt root differs from its verified identity");
			route = routing.register(member -> contains(root.directory(), member), children::addLast);
			manager = device.manager();
			gate = manager.newSession(pid);
			gate.start();
			gate.enableChildGating();
			if (!held.equals(inspect(pid, true, null)))
				throw new IOException("Held attempt root changed during attachment");
			release.release();
			long began = System.nanoTime();
			while (children.isEmpty())
			{
				copyChildren();
				if (children.isEmpty())
				{
					if (!isRunning(pid, root.started()))
						return;
					waitForPeer(began, "Held root executable was not delivered");
				}
			}
			FridaRuntime.Child event = children.removeFirst();
			verifyRootEvent(event, pid, launch.command().getFirst());
			Image image = inspect(pid, true, Path.of(launch.command().getFirst()));
			if (image.started() != root.started())
				throw new IOException("Attempt root identity changed before its held exec");
			install(image);
            if (!image.equals(inspect(pid, true, image.executable())))
                throw new IOException("Attempt root changed during allocation/token readiness");
            resume(event);
		}
		catch (IOException | RuntimeException cause) { throw failed("Instrumentation start", cause); }
		catch (Error cause) { failed("Instrumentation start", cause); throw cause; }
		finally { advancing = false; }
	}

	/**
	 * Reports whether a process with the given start time still exists and has not exited.
	 * @param pid     process to look for
	 * @param started kernel start time of that process in clock ticks
	 * @return false if no such process exists, its pid now names another process, or it is a zombie
	 * @throws IOException if the process record exists but cannot be read
	 */
	private static boolean isRunning(long pid, long started) throws IOException
	{
		String stat;
		try
		{
			stat = Files.readString(proc(pid).resolve("stat"));
		}
		catch (java.nio.file.NoSuchFileException _)
		{
			return false;
		}
		String state = stat.substring(stat.lastIndexOf(')') + 1).strip();
		return startTime(stat) == started && !state.startsWith("Z") && !state.startsWith("X");
	}

	/** Services one bounded frame round and at most one held child transition.
	 * @throws IOException if delivery or native image identity cannot be established
	 */
	@Override
	public void pump() throws IOException
	{
		requireOwner();
		requireCertain();
		if (stopped || manager == null || closed)
			return;
		try
		{
			copyChildren();
			if (advancing == false && children.isEmpty() == false)
			{
				advancing = true;
				try { advanceChild(children.removeFirst()); }
				finally { advancing = false; }
			}
            LinuxAllocationTrace.Denial nativeDenial = nativeImages.pollDenial();
            if (nativeDenial != null)
            {
                AllocationWaits.Wait nativeWait = nativeWait(nativeDenial, activeInstallations, nativeInstallations);
                nativeRetry = true;
                waits.blocked(nativeWait);
            }
			for (long installation : List.copyOf(activeInstallations.values()))
			{
                AllocationHook hook = installations.get(installation);
                if (hook != null) hook.pump();
				CargoTokenHook tokens = tokenInstallations.get(installation);
				if (tokens != null) tokens.pump();
			}
		}
		catch (IOException | RuntimeException cause) { throw failed("Instrumentation delivery", cause); }
		catch (Error cause) { failed("Instrumentation delivery", cause); throw cause; }
	}

    /** Binds a genuine held native denial to its active independently authenticated image generation.
     * Native tracing permits one denied hold before whole-attempt termination, so its wait ordinal is one.
     * @param denial copied executed-trap observation from this controller's original native owner
     * @param active current independently authenticated process-to-installation assignments
     * @param nativeGenerations installation generations retained by this native trace owner
     * @return positive generation-bound queue wait for the actual allocating process
     * @throws IOException if no active native image owns the actual allocating process
     */
    public static AllocationWaits.Wait nativeWait(LinuxAllocationTrace.Denial denial, Map<Long, Long> active,
        java.util.Set<Long> nativeGenerations) throws IOException
    {
        Long installation = active.get((long) denial.processId());
        if (installation == null || !nativeGenerations.contains(installation))
            throw new IOException("Native allocation denial has no authenticated image installation");
        return new AllocationWaits.Wait(installation, 1);
    }

    /** Reports a genuine native denial requiring the queue's existing whole-attempt retry path.
     * @return true only after this attempt observes an executed native allocation-denial trap
     */
    boolean requiresRetryAfterDenial() { requireOwner(); return nativeRetry; }

	/** Grants through the exact existing installation owner, never by PID alone.
	 * @param wait authentic wait selected by AllocationWaits
	 * @throws IOException if ownership or accepted permission cannot be established
	 */
	@Override
	public void grant(AllocationWaits.Wait wait) throws IOException
	{
		requireOwner();
		requireCertain();
		if (stopped || advancing)
			throw new IllegalStateException("Instrumentation cannot grant during another transition");
        if (nativeInstallations.contains(wait.installation()))
            throw new IOException("Native denied allocation requires whole-attempt retry");
		AllocationHook hook = installation(installations, wait);
		advancing = true;
		try { hook.grant(wait, peers); }
		catch (IOException | RuntimeException cause) { throw failed("Native permission", cause); }
		catch (Error cause) { failed("Native permission", cause); throw cause; }
		finally { advancing = false; }
	}

	/** Withdraws grants without unloading code or resuming parked tasks. */
	@Override
	public void stop()
	{
		requireOwner();
		stopped = true;
        if (route != null) route.cancel();
		for (AllocationHook hook : installations.values())
			hook.stop();
		for (CargoTokenHook hook : tokenInstallations.values())
			hook.stop();
	}

	/** Destroys owned native resources only after the authoritative whole-workload barrier.
	 * @param barrier trusted actual workload-zero observation
	 * @throws IOException if the barrier is unknown or native cleanup remains retained
	 */
	@Override
	public void close(AllocationHook.ExecutionBarrier barrier) throws IOException
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
			for (CargoTokenHook hook : tokenInstallations.values())
			{
				try { hook.close(barrier); }
				catch (IOException cause)
				{
					if (cleanup == null) cleanup = cause;
					else cleanup.addSuppressed(cause);
				}
			}
            try { nativeImages.close(); }
            catch (IOException cause)
            {
                if (cleanup == null) cleanup = cause;
                else cleanup.addSuppressed(cause);
            }
			if (cleanup != null)
				throw cleanup;
			if (gate != null) gate.close();
			closed = true;
            if (route != null) route.close();
		}
		catch (IOException | RuntimeException cause)
		{
			closeFailure = new IOException("Native instrumentation destruction failed", cause);
			throw closeFailure;
		}
		catch (Error cause) { closeFailure = new IOException("Native instrumentation destruction did not complete", cause); throw cause; }
	}

	/** Routes this attempt's native tracer event and a bounded number of shared device events to their controllers.
	 * @throws IOException if callback transport failed or routing rejects an event
	 */
	private void copyChildren() throws IOException
	{
        FridaRuntime.Child nativeChild = nativeImages.pollChild();
        if (nativeChild != null) routing.route(route, nativeChild);
		device.copy();
	}

	/** Verifies and instruments one supported held image before resume.
	 * @param event copied held-image notification
	 * @throws IOException if unsupported or unowned
	 */
	private void advanceChild(FridaRuntime.Child event) throws IOException
	{
		verifyOrigin(event.origin());
        Image previous = images.get(event.pid());
        Image observed = inspect(event.pid(), true, null);
        Map<Long, LinuxCargoImages.Identity> retained = new LinkedHashMap<>();
        images.forEach((pid, image) -> retained.put(pid, lineage(image)));
        LinuxCargoImages.Identity parent = LinuxCargoImages.retainedParent(event, lineage(observed), retained,
            pid -> lineage(inspect(pid, true, null)));
        Path executable = LinuxCargoImages.executable(event, parent == null ? null : parent.executable());
        LinuxCargoImages.ExecutableIdentity source = LinuxCargoImages.executableIdentity(lineage(observed), executable,
            pid -> lineage(inspect(pid, true, null)));
        Image image = observed;
        if (!observed.equals(image)) throw new IOException("Held image changed during executable verification");
        if (previous != null)
        {
            long retired = activeInstallations.get(event.pid());
            // Verified same-process exec removed old-image threads; retain owners through whole-workload zero.
            AllocationHook oldHook = installations.get(retired);
            if (oldHook != null) oldHook.stop();
            CargoTokenHook tokens = tokenInstallations.get(retired);
            if (tokens != null) tokens.stop();
            waits.retireInstallation(retired);
            activeInstallations.remove(event.pid());
        }
        install(image);
        if (parent != null && !parent.equals(lineage(inspect(parent.pid(), true, parent.executable()))))
            throw new IOException("Held child parent changed during hook readiness");
        if (!source.equals(LinuxCargoImages.executableIdentity(lineage(image), executable,
            pid -> lineage(inspect(pid, true, null)))))
            throw new IOException("Held executable source changed during hook readiness");
        if (!image.equals(inspect(event.pid(), true, null)))
            throw new IOException("Held child changed during hook readiness");
        resume(event);
    }

    /** Converts an already independently verified attempt image to the shared current-membership contract.
     * @param image independently inspected native attempt image
     * @return complete retained lineage with verified workload credentials
     */
    private LinuxCargoImages.Identity lineage(Image image)
    {
        return new LinuxCargoImages.Identity(image.pid(), image.started(), image.parent(), launch.uid(), launch.gid(),
            image.executable(), image.group());
    }

    /** Reserves and performs the original owning manager's sole continuation after both native owners are ready.
     * @param event exact retained held transition
     * @throws IOException if cancellation, ownership or native continuation is uncertain
     */
    private void resume(FridaRuntime.Child event) throws IOException
    {
        if (stopped || Thread.currentThread().isInterrupted()) throw new IOException("Held attempt continuation canceled");
        routing.prepare(route, event);
        if (nativeImages.owns(event.pid()))
            nativeImages.resume(event.pid(), startTime(Files.readString(proc(event.pid()).resolve("stat"))));
        else manager.resume(event.pid());
        routing.completed(route, event);
    }

	/** Retains a fresh hook before start and awaits authentic ready before any process resume.
	 * @param image independently verified held image
	 * @throws IOException if attach or readiness fails
	 */
	private void install(Image image) throws IOException
	{
		long installation = nextInstallation;
		nextInstallation = Math.incrementExact(nextInstallation);
        if (nativeImages.install(image.pid(), image.started(), () -> manager.resume(image.pid())))
        {
            nativeInstallations.add(installation);
            activeInstallations.put(image.pid(), installation);
            images.put(image.pid(), image);
            return;
        }
		AllocationHook hook = new AllocationHook(scope, identity, installation, image.pid(), manager, waits, program,
			Duration.ofNanos(timeoutNanos));
		installations.put(installation, hook);
		activeInstallations.put(image.pid(), installation);
		images.put(image.pid(), image);
		hook.start();
		hook.awaitReady(peers);
		if (cargoTokens.isPresent())
		{
			CargoTokens cargo = cargoTokens.orElseThrow();
			CargoTokenHook tokens = new CargoTokenHook(scope, identity, installation, image.pid(), cargo.fifo(), manager,
				cargo.tokens(), tokenProgram, Duration.ofNanos(timeoutNanos));
			tokenInstallations.put(installation, tokens);
			tokens.start();
			tokens.awaitReady(peers);
		}
	}

	/**
	 * Immutable invocation endpoint identity and ledger borrowed by this attempt's authenticated images.
	 * This value does not authenticate an image, own the descriptor or retire the ledger.
	 * @param fifo independently observed identity from the invocation's retained lease
	 * @param tokens whole-attempt ledger retained by LinuxAttempt until trusted whole-descendant zero
	 */
	public record CargoTokens(LinuxFifoBindings.Identity fifo, LinuxCargoTokens.Attempt tokens)
	{
		/**
		 * Validates borrowed inputs without acquiring native resources.
		 * @param fifo independently observed identity from the invocation's retained lease
		 * @param tokens whole-attempt ledger retained by LinuxAttempt until trusted whole-descendant zero
		 * @throws NullPointerException if any input is null
		 */
		public CargoTokens
		{
			Objects.requireNonNull(fifo, "fifo");
			Objects.requireNonNull(tokens, "tokens");
		}
	}

	/**
	 * Identifies the held root process of one attempt and the cgroup subtree that contains it.
	 * @param pid       root process, blocked before its exec by the process that forked it
	 * @param started   kernel start time of the root process in clock ticks
	 * @param parent    process that forked the root and holds it until release
	 * @param directory owned cgroup subtree containing the root and every descendant
	 */
	public record Root(long pid, long started, long parent, Path directory)
	{
		/**
		 * Validates the identity.
		 * @param pid       root process, blocked before its exec by the process that forked it
		 * @param started   kernel start time of the root process in clock ticks
		 * @param parent    process that forked the root and holds it until release
		 * @param directory owned cgroup subtree containing the root and every descendant
		 * @throws NullPointerException     if {@code directory} is null
		 * @throws IllegalArgumentException if a number is nonpositive or {@code directory} is relative
		 */
		public Root
		{
			Objects.requireNonNull(directory, "directory");
			if (pid <= 0 || started <= 0 || parent <= 0 || !directory.isAbsolute())
				throw new IllegalArgumentException("Attempt root requires positive identities and an absolute group");
		}
	}

	/**
	 * Lets the held root process run its exec, called once after the root is attached and gated.
	 */
	@FunctionalInterface
	public interface Release
	{
		/**
		 * Releases the held root process.
		 * @throws IOException if the release cannot be delivered
		 */
		void release() throws IOException;
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
		if (pid == root.pid() && parent != root.parent())
			throw new IOException("Attempt root has changed parent");
		Path actual = Files.readSymbolicLink(process.resolve("exe"));
		if (executable != null) requireHeldExecutable(process, executable);
		String group = Files.readString(process.resolve("cgroup"));
		Path contained = null;
		try (var groups = Files.walk(root.directory()))
		{
			for (Path directory : groups.filter(Files::isDirectory).toList())
			{
				Path members = directory.resolve("cgroup.procs");
				if (Files.isRegularFile(members) && Files.readAllLines(members).contains(Long.toString(pid)))
				{
					contained = directory;
					break;
				}
			}
		}
		if (contained == null || group.equals(Files.readString(process.resolve("cgroup"))) == false ||
			began != startTime(Files.readString(process.resolve("stat"))))
			throw new IOException("Held process lacks stable actual owned cgroup identity");
		return new Image(pid, began, parent, actual, contained);
	}

	/** Checks that a held process image came from the requested executable, either directly or as a kernel
	 * shebang script whose interpreter image and kernel-inserted arguments match the script.
	 * @param process the held process's proc directory
	 * @param executable the absolute executable the notification requested
	 * @throws IOException if the held image did not come from {@code executable}
	 */
	public static void requireHeldExecutable(Path process, Path executable) throws IOException
	{
		Path image = process.resolve("exe");
		if (executable.isAbsolute() && Files.isSameFile(image, executable)) return;
		try
		{
			if (executable.isAbsolute() == false) throw new IOException("Requested executable is not absolute");
			byte[] command = Files.readAllBytes(process.resolve("cmdline"));
			if (command.length == 0 || command[command.length - 1] != 0)
				throw new IOException("Held image command line is incomplete");
			String text = LinuxCargoImages.utf8(java.util.Arrays.copyOf(command, command.length - 1));
			LinuxCargoImages.executableIdentity(image, executable, List.of(text.split(String.valueOf((char) 0), -1)));
		}
		catch (IOException cause)
		{
			throw new IOException("Held executable differs from native notification for " + process.getFileName() +
				": expected " + executable + "; observed " + Files.readSymbolicLink(image), cause);
		}
	}

	/** Reads the Linux start-time field without splitting a command name containing spaces or parentheses.
	 * @param stat complete proc stat record
	 * @return positive kernel start-time ticks
	 * @throws IOException if required identity is malformed
	 */
	public static long startTime(String stat) throws IOException
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
	public static boolean credentialsMatch(String status, int uid, int gid)
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
	public static void verifyRootEvent(FridaRuntime.Child event, long root, String executable) throws IOException
	{
		if (event.pid() != root || event.parentPid() != root || event.origin() != 1 || executable.equals(event.path()) == false)
			throw new IOException("Unexpected held root replacement: " + event);
	}

	/** Accepts known notification origins for subsequent native identity checks and fresh hook installation.
	 * @param origin native origin code
	 * @throws IOException if the native origin is unknown
	 */
	public static void verifyOrigin(int origin) throws IOException
	{
		if (origin != 0 && origin != 1 && origin != 2)
			throw new IOException("Held origin is unsupported: " + origin);
	}

	/** Finds the retained hook for an exact installation identity.
	 * @param installations retained owners by generation
	 * @param wait authentic selected native wait
	 * @return exact retained owner
	 * @throws IOException if installation is not owned
	 */
	public static AllocationHook installation(Map<Long, AllocationHook> installations, AllocationWaits.Wait wait) throws IOException
	{
		AllocationHook hook = installations.get(wait.installation());
		if (hook == null) throw new IOException("Allocation wait installation is not owned: " + wait);
		return hook;
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
     * @param group exact independently observed owned cgroup
	 */
	private record Image(long pid, long started, long parent, Path executable, Path group) {}
}
