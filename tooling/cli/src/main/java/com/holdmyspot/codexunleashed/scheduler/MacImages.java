package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import tools.jackson.databind.json.JsonMapper;

/**
 * Retains macOS held-image instrumentation generations on its constructing thread.
 * The caller owns the held root, manager, process termination and whole-workload execution barrier.
 * Birth and parent observations authorize image lineage only: credentials, executable-file identity,
 * containment, stream authority and Cargo registration remain separate prerequisites. This owner installs
 * readiness scripts and caller-required instrumentation; allocation permissions and process authority remain caller duties.
 * Root arming returns before the caller releases its native gate. Held child events receive one resume
 * after both readiness acknowledgements and identity revalidation. Any uncertain effect stops subsequent admissions and resumes.
 */
public final class MacImages
{
    /**
     * Constructing thread required before every operation.
     */
    private final Thread thread = Thread.currentThread();
    /**
     * Scoped serializer borrowed through cleanup.
     */
    private final JsonMapper json;
    /**
     * Native operations or independently controlled test observations.
     */
    private final Operations operations;
    /**
     * Independently authenticates workload membership and image authority.
     */
    private final Authority authority;
    /**
     * Creates one resource-free caller instrumentation owner per validated held image generation.
     */
    private final InstrumentationFactory instrumentation;
    /**
     * Indicates whether an admission is executing caller or native code, excluding reentrant controls.
     */
    private boolean advancing;
    /**
     * Indicates whether native destruction is active, excluding reentrant cleanup.
     */
    private boolean closing;
    /**
     * Root birth retained by the caller's genuine process owner.
     */
    private final Identity root;
    /**
     * Monotonic readiness bound in nanoseconds.
     */
    private final long timeout;
    /**
     * Monotonic clock used only to bound readiness.
     */
    private final LongSupplier clock;
    /**
     * Latest authenticated image for each retained process birth.
     */
    private final Map<Long, Identity> current = new LinkedHashMap<>();
    /**
     * Every connection retained before start, including replaced and partial images.
     */
    private final List<Image> images = new ArrayList<>();
    /** Latest exact retained image owner for each process identifier. */
    private final Map<Long, Image> currentImages = new LinkedHashMap<>();
    /**
     * Indicates whether root acquisition has consumed its single opportunity.
     */
    private boolean attempted;
    /**
     * Indicates whether controls have been withdrawn.
     */
    private boolean stopped;
    /**
     * Indicates whether all instrumentation owners and image connections closed successfully.
     */
    private boolean closed;
    /**
     * First exact operation failure, retained without replay.
     */
    private Throwable failure;

    /**
     * Creates a resource-free owner borrowing an active manager and caller-retained root identity.
     * @param scope scoped parser services retained through cleanup
     * @param manager active manager whose lifetime remains with the caller
     * @param instrumentation required per-image owner factory; each owner acknowledges readiness before release
     * @param root caller-authenticated held root birth and parent
     * @param timeout positive representable readiness bound for the image connection; caller instrumentation bounds its own wait
     * @throws NullPointerException if any input is null
     * @throws IllegalArgumentException if the readiness bound is zero or negative
     * @throws ArithmeticException if the readiness bound exceeds signed nanoseconds
     */
    public MacImages(ToolingScope scope, FridaRuntime.Manager manager, InstrumentationFactory instrumentation, Identity root, Duration timeout)
    {
        this(scope, new NativeOperations(manager), instrumentation, root, timeout, System::nanoTime);
    }

    /**
     * Supplies independently controlled native observations without changing ownership policy.
     * @param scope scoped parser services
     * @param operations image inspection, connection factory and held-child resumption
     * @param instrumentation required per-image resource-free owner factory
     * @param root caller-authenticated held root
     * @param timeout positive representable readiness duration for the image connection
     * @param clock monotonic deadline observations
     * @throws NullPointerException if any input is null
     * @throws IllegalArgumentException if the readiness bound is zero or negative
     * @throws ArithmeticException if the readiness bound exceeds signed nanoseconds
     */
    public MacImages(ToolingScope scope, Operations operations, InstrumentationFactory instrumentation, Identity root, Duration timeout, LongSupplier clock)
    {
        this(scope, operations, identity -> {}, instrumentation, root, timeout, clock);
    }

    /**
     * Supplies independent workload authentication alongside the image transport.
     * @param scope scoped parser services
     * @param operations image transport and held-child controls
     * @param authority kernel-backed workload and executable authentication
     * @param instrumentation required per-image instrumentation
     * @param root caller-held original root lineage
     * @param timeout positive representable readiness duration
     * @param clock monotonic deadline observations
     * @throws NullPointerException if any input is null
     * @throws IllegalArgumentException if timeout is zero or negative
     * @throws ArithmeticException if timeout exceeds signed nanoseconds
     */
    public MacImages(ToolingScope scope, Operations operations, Authority authority,
        InstrumentationFactory instrumentation, Identity root, Duration timeout, LongSupplier clock)
    {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.json = Objects.requireNonNull(scope, "scope").getLeaderJsonMapper();
        this.operations = Objects.requireNonNull(operations, "operations");
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.root = Objects.requireNonNull(root, "root");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Positive readiness timeout required");
        this.timeout = timeout.toNanos();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Arms the caller-held root and acknowledges required instrumentation without releasing its external native gate.
     * Failure leaves partial owners retained, stops further admission and requires caller-owned workload cleanup.
     * @throws IllegalStateException if another thread calls or root acquisition was already attempted or stopped
     * @throws IOException if root identity, attachment or required instrumentation readiness cannot be established
     */
    public void start() throws IOException
    {
        requireThread();
        if (attempted || stopped) throw new IllegalStateException("Root arming is permitted once");
        attempted = true;
        advancing = true;
        try { install(root, null); }
        catch (IOException | RuntimeException | Error cause) { fail(cause); throw cause; }
        finally { advancing = false; }
    }

    /**
     * Authenticates one copied held child event, arms readiness and required instrumentation, then resumes once.
     * Failure before resumption leaves the child held, retains partial owners and stops further admissions.
     * An uncertain resume is never replayed.
     * @param child copied native event, never itself an ownership credential
     * @throws NullPointerException if {@code child} is null
     * @throws IllegalStateException if another thread calls, admission is inactive or a callback reenters admission
     * @return how the child was admitted; an exited child is never resumed
     * @throws IOException if lineage, required instrumentation readiness or resumption is uncertain
     */
    public Admission advance(FridaRuntime.Child child) throws IOException
    {
        requireThread();
        if (!attempted || images.isEmpty() || stopped || advancing) throw new IllegalStateException("Image owner is not active");
        Objects.requireNonNull(child, "child");
        advancing = true;
        try
        {
            if (child.origin() < 0 || child.origin() > 2) throw new IOException("Unknown native child origin");
            Identity observed = inspect(child.pid());
            Identity previous = current.get(child.pid());
            if (authority instanceof WorkloadAuthority workloadAuthority)
            {
                workloadAuthority.authenticateChild(child, observed);
                if (child.origin() == 1 && (previous == null || child.parentPid() != child.pid() ||
                    !previous.birth().equals(observed.birth())))
                    throw new IOException("Exec lacks its retained original process birth");
                if (child.origin() != 1 && previous != null)
                    throw new IOException("Fork reuses a retained process identifier");
            }
            else if (child.origin() == 1)
            {
                if (previous == null || child.parentPid() != child.pid() || !previous.equals(observed))
                    throw new IOException("Exec lacks its retained original process birth");
            }
            else
            {
                Identity parent = current.get(child.parentPid());
                if (previous != null || parent == null || observed.parent() != parent.pid() ||
                    !parent.equals(inspect(parent.pid())))
                    throw new IOException("Child lacks a retained live parent birth");
            }
            if (child.origin() == 1 && authority instanceof WorkloadAuthority)
            {
                retirePredecessor(currentImages.get(child.pid()));
                ((WorkloadAuthority) authority).authenticateChild(child, inspect(child.pid()));
            }
            Image nativeParent = child.origin() == 0 ? currentImages.get(child.parentPid()) : null;
            if (child.origin() == 0 && (nativeParent == null ||
                (nativeParent.protection == null && !nativeParent.uninstrumented)))
                throw new IOException("Native fork lacks its retained descriptor registry owner");
            Admission admission;
            if (operations.platformImage(observed))
            {
                admitUninstrumented(observed);
                admission = Admission.PLATFORM;
            }
            else if (install(observed, nativeParent == null || nativeParent.uninstrumented ? null :
                nativeParent.protection.registry()))
            {
                admission = Admission.INSTRUMENTED;
            }
            else
                return Admission.EXITED;
            if (authority instanceof WorkloadAuthority workloadAuthority)
                workloadAuthority.authenticateChild(child, inspect(child.pid()));
            else if (child.origin() != 1)
            {
                Identity parent = current.get(child.parentPid());
                if (!parent.equals(inspect(parent.pid()))) throw new IOException("Parent birth changed before child release");
            }
            if (Thread.currentThread().isInterrupted()) throw new IOException("Held resume interrupted");
            operations.resume(child.pid());
            return admission;
        }
        catch (IOException | RuntimeException | Error cause)
        {
            if (cause instanceof FridaRuntime.NativeFailure) suppress(cause, new IOException("Held image transition failed: pid=" + child.pid() +
                ", parent=" + child.parentPid() + ", origin=" + child.origin() + ", reportedPath=" + child.path()));
            fail(cause);
            throw cause;
        }
        finally { advancing = false; }
    }

    /**
     * Retains readiness and caller instrumentation owners before starting each one.
     * Both readiness acknowledgements and a final identity check precede publication or release.
     * <p>
     * A child can exit while Frida attaches to it, which makes the attachment fail. When the process is then absent, or
     * its identifier names another process, the image is left unadmitted and its partial owners remain retained for
     * cleanup, as for any other child that exits.
     * @param expected independently authenticated image lineage
     * @param inheritedRegistry exact native-fork parent mapping, otherwise null
     * @return {@code false} if the process exited during attachment, otherwise {@code true}
     * @throws IOException if acquisition, readiness or identity changes while the process remains live
     */
    private boolean install(Identity expected, String inheritedRegistry) throws IOException
    {
        if (!expected.equals(inspect(expected.pid()))) throw new IOException("Held image identity changed");
        var image = new Image(operations.connection(expected.pid()));
        images.add(image);
        String marker = "mac-image-ready:" + expected.pid() + ":" + images.size();
        image.protection = new MacDescriptorProtection(json, image.connection, expected, marker, operations, timeout, clock);
        try { image.protection.start(inheritedRegistry); }
        catch (FridaRuntime.NativeFailure cause)
        {
            if (exited(expected, cause))
                return false;
            suppress(cause, new IOException("Readiness attachment image: pid=" + expected.pid() +
                ", birth=" + expected.birth() + ", nativeParent=" + expected.parent()));
            throw cause;
        }
        if (!expected.equals(inspect(expected.pid()))) throw new IOException("Birth changed before release");
        image.instrumentation = Objects.requireNonNull(instrumentation.create(expected), "instrumentation owner");
        if (!expected.equals(inspect(expected.pid()))) throw new IOException("Birth changed before instrumentation start");
        image.instrumentation.start();
        if (Thread.currentThread().isInterrupted()) throw new IOException("Image instrumentation interrupted");
        if (!expected.equals(inspect(expected.pid()))) throw new IOException("Birth changed after instrumentation readiness");
        image.protection.verify();
        if (!expected.equals(inspect(expected.pid()))) throw new IOException("Birth changed after descriptor agreement");
        current.put(expected.pid(), expected);
        currentImages.put(expected.pid(), image);
        return true;
    }

    /**
     * Admits an Apple platform image without attaching to it, because the kernel refuses another process access to
     * its threads. The image is tracked as the process's current generation so that its exec and fork events are
     * classified in turn, but it owns no connection, protection or instrumentation.
     * @param expected independently authenticated image lineage
     * @throws IOException if the image's identity changes before admission
     */
    private void admitUninstrumented(Identity expected) throws IOException
    {
        if (!expected.equals(inspect(expected.pid()))) throw new IOException("Birth changed before uninstrumented release");
        current.put(expected.pid(), expected);
        currentImages.put(expected.pid(), Image.UNINSTRUMENTED);
    }

    /**
     * Indicates whether a held image's process has exited, judged after an attachment to it failed.
     * @param expected independently authenticated image lineage
     * @param attachment the attachment failure, which receives a failed re-inspection as suppressed evidence
     * @return {@code true} if the process is absent or its identifier now names another process
     */
    private boolean exited(Identity expected, FridaRuntime.NativeFailure attachment)
    {
        try { return !expected.equals(inspect(expected.pid())); }
        catch (IOException absent)
        {
            suppress(attachment, absent);
            return true;
        }
    }

    /**
     * Retires an exact predecessor only after independent successor authentication and native detach observations.
     * Failed destruction consumes its opportunity and remains retained for whole-workload diagnostics.
     * An uninstrumented predecessor owns nothing to retire.
     * @param image exact retained predecessor, never selected from a callback alone
     * @throws IOException if either observation or destruction remains uncertain
     */
    private void retirePredecessor(Image image) throws IOException
    {
        if (image != null && image.uninstrumented) return;
        if (image == null || image.instrumentation == null || !image.connection.detached() ||
            !image.instrumentation.detached())
            throw new IOException("Authenticated exec predecessor detachment is unproven");
        image.instrumentationCloseAttempted = true;
        try { image.instrumentation.closeDetached(); image.instrumentationClosed = true; }
        catch (IOException | RuntimeException | Error cause) { image.instrumentationFailure = cause; throw cause; }
        image.closeAttempted = true;
        try { image.connection.close(); image.closed = true; }
        catch (IOException | RuntimeException | Error cause) { image.failure = cause; throw cause; }
    }

    /**
     * Reads lineage and requires the independent workload authority before it can authorize an effect.
     * @param pid held process to inspect
     * @return authenticated live image lineage
     * @throws IOException if the observation names another PID or authority cannot be established
     */
    private Identity inspect(long pid) throws IOException
    {
        Identity observed = operations.inspect(pid);
        if (observed.pid() != pid) throw new IOException("Image inspection returned another PID");
        authority.authenticate(observed);
        return observed;
    }

    /**
     * Withdraws controls and closes independent images only after a trusted whole-workload barrier.
     * Each caller instrumentation owner closes before its image connection and is attempted once.
     * A failed instrumentation close retains that image connection; a failed connection retains its dependent session.
     * A connection failure never prevents cleanup of another independent image connection.
     * @param barrier caller-owned kernel-backed whole-workload execution observation
     * @throws NullPointerException if {@code barrier} is null
     * @throws IllegalStateException if another thread calls or an active transition reenters cleanup
     * @throws IOException if the barrier is unknown or any cleanup remains incomplete
     */
    public void close(AllocationHook.ExecutionBarrier barrier) throws IOException
    {
        requireThread();
        if (advancing || closing) throw new IllegalStateException("Image transitions cannot reenter cleanup");
        Objects.requireNonNull(barrier, "barrier");
        if (closed) return;
        stopped = true;
        closing = true;
        try
        {
            if (!barrier.reached()) throw new IOException("Whole-workload execution barrier is not established");
            Throwable cleanup = null;
            for (Image image : images.reversed())
            {
                if (image.instrumentation != null && !image.instrumentationCloseAttempted)
                {
                    image.instrumentationCloseAttempted = true;
                    try { image.instrumentation.close(); image.instrumentationClosed = true; }
                    catch (IOException | RuntimeException | Error cause) { image.instrumentationFailure = cause; }
                }
                if (image.instrumentationFailure != null)
                {
                    if (cleanup == null) cleanup = image.instrumentationFailure;
                    else suppress(cleanup, image.instrumentationFailure);
                    if (failure != null) suppress(failure, image.instrumentationFailure);
                }
                boolean bootstrapClosed = true;
                if (image.protection != null)
                {
                    try { image.protection.closeBootstrap(); }
                    catch (IOException | RuntimeException | Error cause)
                    {
                        bootstrapClosed = false;
                        if (cleanup == null) cleanup = cause;
                        else suppress(cleanup, cause);
                        if (failure != null) suppress(failure, cause);
                    }
                }
                if (bootstrapClosed && (image.instrumentation == null || image.instrumentationClosed) && !image.closeAttempted)
                {
                    image.closeAttempted = true;
                    try { image.connection.close(); image.closed = true; }
                    catch (IOException | RuntimeException | Error cause) { image.failure = cause; }
                }
                if (image.failure != null)
                {
                    if (cleanup == null) cleanup = image.failure;
                    else suppress(cleanup, image.failure);
                    if (failure != null) suppress(failure, image.failure);
                }
            }
            closed = images.stream().allMatch(image -> image.closed);
            rethrow(cleanup);
        }
        finally { closing = false; }
    }

    /**
     * Indicates whether every retained instrumentation owner and connection closed without retrying native effects.
     * @return true after all retained dependent resources closed successfully
     * @throws IllegalStateException if another thread calls
     */
    public boolean isClosed() { requireThread(); return closed; }

    /**
     * Preserves the first exact operation failure for subsequent cleanup diagnostics.
     * @param cause first uncertain operation
     */
    private void fail(Throwable cause) { stopped = true; if (failure == null) failure = cause; }

    /**
     * Adds independent cleanup evidence without self-suppression or duplicate references.
     * @param primary earlier failure
     * @param secondary independent later failure
     */
    private static void suppress(Throwable primary, Throwable secondary)
    {
        if (primary == secondary || references(secondary, primary, Collections.newSetFromMap(new IdentityHashMap<>()))) return;
        for (Throwable prior : primary.getSuppressed()) if (prior == secondary) return;
        primary.addSuppressed(secondary);
    }

    /**
     * Avoids cycles when a cleanup exception already references the original operation failure.
     * @param source throwable graph being inspected
     * @param target original failure
     * @param visited identities already inspected
     * @return whether the source graph already retains the target
     */
    private static boolean references(Throwable source, Throwable target, Set<Throwable> visited)
    {
        if (source == target) return true;
        if (source == null || !visited.add(source)) return false;
        if (references(source.getCause(), target, visited)) return true;
        for (Throwable suppressed : source.getSuppressed()) if (references(suppressed, target, visited)) return true;
        return false;
    }

    /**
     * Rethrows a retained cleanup failure with its original type and identity.
     * @param cause retained failure or null
     * @throws IOException if the original failure is checked
     */
    private static void rethrow(Throwable cause) throws IOException
    {
        if (cause instanceof IOException checked) throw checked;
        if (cause instanceof RuntimeException unchecked) throw unchecked;
        if (cause instanceof Error fatal) throw fatal;
    }

    /**

     * Requires the constructing thread before observing or changing ownership.

     */
    private void requireThread()
    {
        if (Thread.currentThread() != thread) throw new IllegalStateException("MacImages belongs to its constructing thread");
    }

    /**
     * How a held child was admitted.
     */
    public enum Admission
    {
        /** Attached, instrumented and resumed. */
        INSTRUMENTED,
        /** An Apple platform image, resumed without attaching. */
        PLATFORM,
        /** Exited while Frida attached to it, so it was left unresumed. */
        EXITED
    }

    /**
     * Retains process lineage; this value grants neither executable nor containment authority.
     * @param pid positive native process identifier
     * @param birth native process birth observation
     * @param parent positive actual parent identifier
     */
    public record Identity(long pid, Instant birth, long parent)
    {
        /**
         * Rejects incomplete lineage observations.
         * @param pid native process identifier
         * @param birth native process birth
         * @param parent actual parent identifier
         */
        public Identity
        {
            if (pid <= 0 || parent <= 0 || pid == parent) throw new IllegalArgumentException("Invalid process lineage");
            Objects.requireNonNull(birth, "birth");
        }
    }

    /**
     * Creates required instrumentation for one independently validated held image generation.
     * The factory must acquire no native resources and must not release the process. Ownership of the
     * returned resource-free owner transfers to MacImages, which retains it before its first start call.
     * Same-PID exec receives a new owner; an independently authenticated detached predecessor may retire earlier.
     */
    @FunctionalInterface
    public interface InstrumentationFactory
    {
        /**
         * Configures a fresh resource-free owner bound to this exact observed image.
         * @param identity validated held process birth and actual parent
         * @return nonnull fresh instrumentation owner, exclusively retained by this MacImages
         */
        Instrumentation create(Identity identity);
    }

    /**
     * Owns caller-required instrumentation for one held image generation.
     * The caller authenticates executable, credentials and any wider authority separately. Implementations
     * retain every partial native acquisition before its next fallible step and never resume the process.
     */
    public interface Instrumentation extends AutoCloseable
    {
        /**
         * Installs required instrumentation once and returns only after its readiness is acknowledged.
         * The implementation bounds its own readiness wait. Failure may follow partial acquisition; this owner
         * remains retained and the image stays held.
         * @throws IOException if installation or readiness cannot be established
         */
        void start() throws IOException;
        /**
         * Closes every retained partial or active instrumentation resource once after the trusted workload barrier.
         * A failed close remains uncertain and is never replayed by MacImages.
         * @throws IOException if native destruction remains incomplete
         */
        @Override void close() throws IOException;
        /**
         * Observes all exact-image sessions without inferring whole-workload completion.
         * @return true only when every retained image attachment independently reports detachment
         * @throws IOException if observation remains uncertain
         */
        default boolean detached() throws IOException { return false; }
        /**
         * Releases independently detached exact-image sessions without retiring workload authority.
         * @throws IOException if detached destruction is unsupported or uncertain
         */
        default void closeDetached() throws IOException { throw new IOException("Detached image cleanup is unsupported"); }

    }

    /**
     * Supplies independent native workload, credential and executable authentication.
     */
    @FunctionalInterface
    public interface Authority
    {
        /**
         * Authenticates exact live incarnation, workload membership, credentials and executable.
         * @param identity held image lineage already read independently of the child callback
         * @throws IOException if any required authority cannot be established
         */
        void authenticate(Identity identity) throws IOException;
    }

    /**
     * Authenticates a held child by invocation-private native workload authority even after reparenting.
     * Callback parent fields remain observations and never grant coalition membership.
     */
    public interface WorkloadAuthority extends Authority
    {
        /**
         * Requires exact native membership, image and credential authority for this held event.
         * @param child copied callback identifying the held event
         * @param observed independently acquired current native lineage
         * @throws IOException if event authority or native workload authentication fails
         */
        void authenticateChild(FridaRuntime.Child child, Identity observed) throws IOException;
    }

    /**
     * Supplies actual native operations or independent Java-controlled observations.
     */
    public interface Operations
    {
        /**
         * Reads one live image lineage without accepting callback fields as authority.
         * @param pid process to inspect
         * @return live native lineage
         * @throws IOException if native observations are unavailable
         */
        Identity inspect(long pid) throws IOException;
        /**
         * Reads a complete descriptor table bracketed by full native workload image authority.
         * @param identity retained independently authenticated held image
         * @return complete immutable native observation with SDK queue constants
         * @throws IOException if authority, completeness or descriptor lifetimes are uncertain
         */
        MacDescriptorProtection.Inventory descriptors(Identity identity) throws IOException;
        /**
         * Creates a resource-free connection subsequently retained before start.
         * @param pid authenticated process
         * @return existing allocation transport connection
         */
        AllocationHook.Connection connection(long pid);
        /**
         * Reads the kernel code-signing status of one held image, independently of any callback path.
         * The kernel marks an image as an Apple platform binary when it is signed as part of the operating system.
         * Such an image cannot be instrumented: the kernel refuses another process access to its threads.
         * @param identity retained independently authenticated held image
         * @return {@code true} if the image's current code-signing flags include {@code CS_PLATFORM_BINARY}
         * @throws IOException if the status cannot be read for this exact image
         */
        boolean platformImage(Identity identity) throws IOException;
        /**
         * Releases a genuinely gated child once after readiness.
         * @param pid held child
         * @throws IOException if release has uncertain effects
         */
        void resume(long pid) throws IOException;
    }

    /**

     * Uses the unchanged maintained Frida transport and native ProcessHandle observations.

     */
    private static final class NativeOperations implements Operations
    {
        /**
         * Borrowed active native manager.
         */
        private final FridaRuntime.Manager manager;
        /**
         * Retains a borrowed manager without acquiring native resources.
         * @param manager active manager
         */
        private NativeOperations(FridaRuntime.Manager manager) { this.manager = Objects.requireNonNull(manager, "manager"); }
        @Override public Identity inspect(long pid) throws IOException
        {
            if (!System.getProperty("os.name", "").startsWith("Mac")) throw new IOException("Native MacImages requires macOS");
            ProcessHandle process = ProcessHandle.of(pid).orElseThrow(() -> new IOException("Process is absent"));
            Instant before = process.info().startInstant().orElseThrow(() -> new IOException("Birth is absent"));
            long parent = process.parent().orElseThrow(() -> new IOException("Parent is absent")).pid();
            Instant after = process.info().startInstant().orElseThrow(() -> new IOException("Birth became unavailable"));
            if (!process.isAlive() || !before.equals(after) ||
                process.parent().map(ProcessHandle::pid).orElse(-1L) != parent) throw new IOException("Native process birth changed");
            return new Identity(pid, before, parent);
        }
        @Override public MacDescriptorProtection.Inventory descriptors(Identity identity) throws IOException
        {
            throw new IOException("Descriptor admission requires the authenticated native workload authority");
        }
        @Override public AllocationHook.Connection connection(long pid) { return AllocationHook.newNativeConnection(manager, pid); }
        /**
         * Exempts no image, because these observations lack the kernel code-signing bridge; every image is attached.
         * @param identity held image
         * @return {@code false}
         */
        @Override public boolean platformImage(Identity identity) { return false; }
        @Override public void resume(long pid) throws IOException { manager.resume(pid); }
    }

    /**

     * Retains one independent image connection and its exact once-only cleanup outcome.

     */
    private static final class Image
    {
        /**
         * Connection owning its dependent script/session resources; null only for the uninstrumented generation.
         */
        private final AllocationHook.Connection connection;
        /** Native descriptor protocol retained before attachment and through target termination. */
        private MacDescriptorProtection protection;
        /**
         * Caller instrumentation retained before start, including partial acquisition.
         */
        private Instrumentation instrumentation;
        /**
         * Indicates whether caller instrumentation close has consumed its single opportunity.
         */
        private boolean instrumentationCloseAttempted;
        /**
         * Indicates whether caller instrumentation closed successfully.
         */
        private boolean instrumentationClosed;
        /**
         * Exact uncertain caller instrumentation destruction failure.
         */
        private Throwable instrumentationFailure;
        /**
         * Whether native close has consumed its single opportunity.
         */
        private boolean closeAttempted;
        /**
         * Whether native close succeeded.
         */
        private boolean closed;
        /**
         * Exact retained uncertain close failure.
         */
        private Throwable failure;
        /**
         * The current generation of every Apple platform image, which owns no native resource and is never closed.
         */
        private static final Image UNINSTRUMENTED = new Image();
        /**
         * Indicates whether this is the shared uninstrumented platform generation.
         */
        private final boolean uninstrumented;
        /**
         * Retains a resource-free connection before start.
         * @param connection independent image owner
         */
        private Image(AllocationHook.Connection connection)
        {
            this.connection = Objects.requireNonNull(connection, "connection");
            this.uninstrumented = false;
        }
        /**
         * Creates the shared uninstrumented platform generation.
         */
        private Image()
        {
            this.connection = null;
            this.uninstrumented = true;
        }
    }
}
