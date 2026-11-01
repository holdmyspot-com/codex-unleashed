package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns syscall tracing of the authenticated original child and reports copied observations over the helper pipe.
 * Every image remains held until the host independently admits its original kernel identity and executable.
 */
final class LinuxBazelTraceHelper implements LinuxBazelHelperMain.Backend
{
    /** Borrowed process values supplying the mapper and native linker. */
    private final ToolingScope scope;
    /** Original host configuration, copied before any native operation. */
    private final ObjectNode configuration;
    /** Original allocation correlation identity. */
    private final UUID attempt;
    /** Initial reserved image generation. */
    private final long installation;
    /** Original prepared local process identity. */
    private final int pid;
    /** Original child birth independently authenticated by the host before helper startup. */
    private final long preparedBirth;
    /** Readiness and final collection budget. */
    private final Duration timeout;
    /** Host-authorized execution observed after original exec. */
    private final ObjectNode execution;
    /** Original launch vectors used by the same child's injected execve. */
    private final ObjectNode launch;
    /** Explicit host-approved private input and runtime mappings for descendant images. */
    private final LinuxBazelNamespaceHooks.ActionImages imagePolicy;
    /** Retained admitted image generations, including retired original inodes. */
    private final Map<Long, Generation> generations = new LinkedHashMap<>();
    /** Current generation for each native process, used to bind allocating threads. */
    private final Map<Long, Generation> current = new LinkedHashMap<>();
    /** Native held events retained until separate host release. */
    private final Map<Long, LinuxAllocationTrace.Held> pending = new LinkedHashMap<>();
    /** Copied native reports waiting for the next helper response. */
    private final List<String> frames = new ArrayList<>();
    /** Copied held images waiting for independent host authentication. */
    private final List<FridaRuntime.Child> held = new ArrayList<>();
    /** Copied nonleader exec entries waiting for host thread authentication. */
    private final List<LinuxAllocationTrace.PreExec> preExec = new ArrayList<>();
    /** Copied exec transitions identifying the actual former native thread. */
    private final List<LinuxAllocationTrace.Held> migrations = new ArrayList<>();
    /** Actual ended generations waiting for host retirement validation. */
    private final List<Long> retired = new ArrayList<>();
    /** Dedicated native trace owner retained through cancellation and partial failure. */
    private LinuxAllocationTrace.Session session;
    /** Initial independently observed executable and isolation facts. */
    private LinuxBazelNamespaceHooks.ImageObservation root;
    /** Last host generation, never reused within this attempt. */
    private long lastInstallation;
    /** Sole initial continuation has been consumed. */
    private boolean resumed;
    /** Future grants and admissions have been withdrawn. */
    private boolean stopped;
    /** Last native root status observed by the operation thread before its response. */
    private LinuxAllocationTrace.Terminal collectedRoot;
    /** Per-attempt native denial sequence; this backend holds the whole attempt after one denial. */
    private long nextWait;

    /**
     * Retains the authenticated original host inputs without acquiring native ownership.
     * @param scope borrowed process values
     * @param configuration authenticated original INIT request
     * @throws IOException if mandatory host identities or execution records are absent
     */
    LinuxBazelTraceHelper(ToolingScope scope, ObjectNode configuration) throws IOException
    {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.configuration = configuration.deepCopy();
        this.attempt = UUID.fromString(LinuxBazelHelperMain.text(configuration, "attempt"));
        this.installation = LinuxBazelHelperMain.positive(configuration, "installation");
        this.lastInstallation = installation;
        this.pid = Math.toIntExact(LinuxBazelHelperMain.positive(configuration, "localPid"));
        this.preparedBirth = LinuxBazelHelperMain.positive(configuration, "preparedBirth");
        this.timeout = Duration.ofNanos(LinuxBazelHelperMain.positive(configuration, "timeoutNanos"));
        this.execution = LinuxBazelHelperMain.readExecution(configuration, "execution");
        this.launch = LinuxBazelHelperMain.readExecution(configuration, "launchExecution");
        JsonNode policy = configuration.path("actionImages");
        if (policy.isMissingNode()) this.imagePolicy = null;
        else
        {
            if (!(policy instanceof ObjectNode object) || !object.path("runtimeRoots").isArray())
                throw new IOException("Native trace action image policy differs");
            List<Path> roots = new ArrayList<>();
            for (JsonNode value : object.path("runtimeRoots"))
            {
                if (!value.isString()) throw new IOException("Runtime image root must be textual");
                roots.add(Path.of(value.asString()));
            }
            this.imagePolicy = new LinuxBazelNamespaceHooks.ActionImages(
                Path.of(LinuxBazelHelperMain.text(object, "inputRoot")), roots);
        }
    }

    @Override public boolean requiresRetryAfterDenial() { return true; }

    @Override public void prepareHeld() throws IOException
    {
        if (session != null || stopped) throw new IOException("Original native preparation cannot be repeated");
        Path proc = Path.of("/proc", Integer.toString(pid));
        long birth = Long.parseLong(LinuxBazelNamespaceHooks.birth(proc));
        if (birth != preparedBirth) throw new IOException("Original prepared child birth differs from host authentication");
        List<String> arguments = new ArrayList<>();
        for (JsonNode value : launch.path("arguments")) arguments.add(value.asString());
        Map<String, String> environment = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : launch.path("environment").properties())
            environment.put(entry.getKey(), entry.getValue().asString());
        session = LinuxAllocationTrace.adopt(scope.getNativeLinker(), new LinuxAllocationTrace.Identity(pid, birth),
            Path.of(LinuxBazelHelperMain.text(launch, "executable")), arguments, environment);
        session.awaitPrepared(timeout);
    }

    @Override public void install() throws IOException
    {
        if (root != null || session == null || stopped) throw new IOException("Original trace installation is unavailable");
        root = LinuxBazelNamespaceHooks.observeImage(Path.of("/proc", Integer.toString(pid)), pid, pid);
        Generation generation = retain(installation, root);
        current.put((long) pid, generation);
    }

    @Override public void resumeImage() throws IOException
    {
        if (root == null || resumed || stopped) throw new IOException("Original trace continuation is unavailable");
        root.requireUnchanged(inspect(pid));
        session.resume();
        resumed = true;
    }

    @Override public void pump() throws IOException
    {
        if (session == null) return;
        LinuxAllocationTrace.Terminal terminal = session.rootTerminal();
        collectedRoot = terminal;
        if (terminal != null && (terminal.waitStatus() & 127) != 0)
        {
            // A collected signaled root cannot authorize a new descendant image.
            stopped = true;
            session.terminate();
            return;
        }
        LinuxAllocationTrace.PreExec entry;
        while ((entry = session.pollPreExec()) != null)
        {
            if (preExec.size() >= 32) throw new IOException("Native pre-exec report bound exceeded");
            preExec.add(entry);
        }
        LinuxAllocationTrace.Held event;
        while ((event = session.pollHeld()) != null)
        {
            if (pending.size() >= 64 || held.size() >= 32) throw new IOException("Native held report bound exceeded");
            if (pending.putIfAbsent((long) event.task().tid(), event) != null)
                throw new IOException("Native held image was repeated before host authorization");
            held.add(new FridaRuntime.Child(event.task().tid(), event.parentPid(), event.origin(), event.path()));
            if (event.formerThread() != null) migrations.add(event);
        }
        LinuxAllocationTrace.Denial denial;
        while ((denial = session.pollDenial()) != null)
        {
            Generation generation = current.get((long) denial.processId());
            if (generation == null || generation.retired)
                throw new IOException("Authentic allocation denial has no admitted process generation");
            if (generation.image.birth() != Long.parseLong(LinuxBazelNamespaceHooks.birth(
                Path.of("/proc", Integer.toString(denial.processId())))))
                throw new IOException("Allocating native process birth differs from its admitted generation");
            ObjectNode body = envelope(generation, "blocked");
            body.put("wait", Long.toString(++nextWait)).put("thread", Integer.toString(denial.thread().tid()))
                .put("bytes", Long.toUnsignedString(denial.requestedBytes())).put("error", "12");
            emit(body);
        }
        for (Generation generation : generations.values())
        {
            if (generation.retired || pending.containsKey(generation.image.localPid())) continue;
            if (LinuxBazelNamespaceHooks.originalImageEnded(Path.of("/proc", Long.toString(generation.image.localPid())),
                generation.image.birth()))
            {
                generation.retired = true;
                current.remove(generation.image.localPid(), generation);
                retired.add(generation.installation);
            }
        }
    }

    @Override public void authorizeExec(ObjectNode request) throws IOException
    {
        if (stopped) throw new IOException("Native exec authority has been withdrawn");
        LinuxAllocationTrace.Identity identity = new LinuxAllocationTrace.Identity(
            Math.toIntExact(LinuxBazelHelperMain.positive(request, "thread")),
            LinuxBazelHelperMain.positive(request, "birth"));
        session.authorizeExec(identity);
    }

    /**
     * Authenticates a held exec against its admitted predecessor and actual executing thread.
     * A leader exec retains that leader's exact birth; a nonleader exec requires the original migration proof.
     * @param event copied event from the sole native wait owner
     * @param former exact executing thread retained by that owner before exec
     * @param target admitted process identifier
     * @param originalBirth independently admitted predecessor birth
     * @param observedBirth independently observed held image birth
     * @return true only for an unchanged leader identity or an authenticated nonleader migration
     */
    static boolean authenticatedHeldExec(LinuxAllocationTrace.Held event, LinuxAllocationTrace.Identity former,
        long target, long originalBirth, long observedBirth)
    {
        if (event == null || event.origin() != 1 || former == null || !former.equals(event.formerThread()) ||
            event.task().tid() != target || event.task().birthTicks() != observedBirth) return false;
        if (former.tid() == target)
            return former.birthTicks() == originalBirth && observedBirth == originalBirth;
        return LinuxBazelNamespaceHooks.authenticatedExecMigration(event, former, target, originalBirth, observedBirth);
    }

    @Override public void installHeld(ObjectNode request) throws IOException
    {
        if (stopped) throw new IOException("Native image admission authority has been withdrawn");
        long target = LinuxBazelHelperMain.positive(request, "localPid");
        LinuxAllocationTrace.Held event = pending.get(target);
        if (event == null) throw new IOException("Host image admission has no original native hold");
        LinuxBazelNamespaceHooks.ImageObservation observed = inspect(target);
        requireHostImage(request, observed);
        if (event.task().birthTicks() != observed.birth()) throw new IOException("Native held birth differs from proc");
        Generation parent = current.get((long) event.parentPid());
        Generation previous = current.get(target);
        if (event.origin() == 1)
        {
            if (previous == null) throw new IOException("Native held exec lacks its admitted predecessor");
            LinuxAllocationTrace.Identity former = event.formerThread();
            if (!authenticatedHeldExec(event, former, target, previous.image.birth(), observed.birth()))
                throw new IOException("Native exec lacks its original thread identity transition");
        }
        else
        {
            if (parent == null || observed.parent() != parent.image.localPid())
                throw new IOException("Native held child lacks its admitted original parent");
            parent.image.requireUnchanged(inspect(parent.image.localPid()));
        }
        LinuxBazelNamespaceHooks.requireRequestedImage(Path.of("/proc", Long.toString(target)),
            new FridaRuntime.Child(target, event.parentPid(), event.origin(), event.path()),
            parent == null ? null : parent.image.executable(), observed.arguments(), imagePolicy);
        long id = LinuxBazelHelperMain.positive(request, "installation");
        if (id <= lastInstallation) throw new IOException("Native installation generation was reused");
        lastInstallation = id;
        if (previous != null) previous.retired = true;
        retain(id, observed);
    }

    @Override public void resumeHeld(ObjectNode request) throws IOException
    {
        long id = LinuxBazelHelperMain.positive(request, "installation");
        Generation generation = generations.get(id);
        if (stopped || generation == null || generation.resumed || generation.retired)
            throw new IOException("Native held image has no fresh continuation authority");
        requireHostImage(request, generation.image);
        generation.image.requireUnchanged(inspect(generation.image.localPid()));
        LinuxAllocationTrace.Held event = pending.remove(generation.image.localPid());
        if (event == null) throw new IOException("Native held image lost its original trace stop");
        generation.resumed = true;
        current.put(generation.image.localPid(), generation);
        session.releaseHeld(event.task());
    }

    @Override public void grant(AllocationWaits.Wait wait) throws IOException
    { throw new IOException("Native denied allocation requires an explicit queue-authorized whole-attempt retry"); }

    @Override public LinuxAllocationTrace.Terminal rootTerminal() throws IOException
    {
        return collectedRoot;
    }

    @Override public void stop() { stopped = true; }

    @Override public void close() throws IOException
    {
        stop();
        if (session != null)
        {
            session.terminate();
            long began = System.nanoTime();
            while (session.outcome() == null)
            {
                if (Thread.currentThread().isInterrupted() || System.nanoTime() - began >= timeout.toNanos())
                    throw new IOException("Native trace collection remains incomplete after execution barrier");
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            }
        }
        for (Generation generation : generations.values()) generation.file.close();
    }

    @Override public LinuxBazelHelperMain.Snapshot drain()
    {
        LinuxBazelHelperMain.Snapshot result = new LinuxBazelHelperMain.Snapshot(frames, held, retired, preExec, migrations);
        frames.clear(); held.clear(); retired.clear(); preExec.clear(); migrations.clear();
        return result;
    }

    /**
     * Retains the actual executable inode and emits readiness for an independently admitted image.
     * @param id host-reserved installation
     * @param image original observed executable
     * @return retained generation
     * @throws IOException if the executable inode cannot be retained
     */
    private Generation retain(long id, LinuxBazelNamespaceHooks.ImageObservation image) throws IOException
    {
        FileChannel file = FileChannel.open(Path.of("/proc", Long.toString(image.localPid()), "exe"), StandardOpenOption.READ);
        Generation generation = new Generation(id, image, file);
        generations.put(id, generation);
        emit(envelope(generation, "ready"));
        return generation;
    }

    /**
     * Observes current action isolation, credentials and executable facts in the helper namespace.
     * @param target held local process
     * @return independently observed image
     * @throws IOException if containment, credentials or owner identity differs
     */
    private LinuxBazelNamespaceHooks.ImageObservation inspect(long target) throws IOException
    {
        Path proc = Path.of("/proc", Long.toString(target));
        String status = Files.readString(proc.resolve("status"));
        if (!AllocationHooks.credentialsMatch(status, Math.toIntExact(LinuxBazelHelperMain.positive(execution, "uid")),
            Math.toIntExact(LinuxBazelHelperMain.nonnegative(execution, "gid"))))
            throw new IOException("Native traced process credentials differ from action identity");
        LinuxBazelNamespaceHooks.ImageObservation image = LinuxBazelNamespaceHooks.observeImage(proc, target, target);
        if (imagePolicy != null && !imagePolicy.permits(image.executable()))
            throw new IOException("Native traced executable has no explicit action or runtime mapping");
        if (root != null && (!root.cgroup().equals(image.cgroup()) ||
            !root.pidNamespace().equals(image.pidNamespace()) || !root.mountNamespace().equals(image.mountNamespace())))
            throw new IOException("Native traced image escaped original action isolation");
        Path owner = Path.of("/proc", LinuxBazelHelperMain.text(configuration, "ownerLocalPid"));
        if (!LinuxBazelNamespaceHooks.birth(owner).equals(LinuxBazelHelperMain.text(configuration, "ownerBirth")))
            throw new IOException("Original native action owner birth changed");
        return image;
    }

    /**
     * Requires exact agreement with the independent host image admission.
     * @param request authenticated host admission
     * @param image independently observed local image
     * @throws IOException if identities, image bytes, arguments or cgroup differ
     */
    private void requireHostImage(ObjectNode request, LinuxBazelNamespaceHooks.ImageObservation image) throws IOException
    {
        if (LinuxBazelHelperMain.positive(request, "localPid") != image.localPid() ||
            LinuxBazelHelperMain.positive(request, "birth") != image.birth() ||
            !LinuxBazelHelperMain.text(request, "sha256").equals(image.sha256()) ||
            !LinuxBazelHelperMain.text(request, "executable").equals(image.executable().toString()) ||
            !LinuxBazelHelperMain.text(request, "cgroup").equals(image.cgroup()) ||
            !scope.getAllocationEnvelopeMapper().valueToTree(image.arguments()).equals(request.path("arguments")))
            throw new IOException("Host-admitted image differs from independent native trace observation");
    }

    /**
     * Builds the existing allocation protocol payload from an admitted native process generation.
     * @param generation admitted original process generation
     * @param event ready or blocked event name
     * @return owned correlated payload
     */
    private ObjectNode envelope(Generation generation, String event)
    {
        return scope.getAllocationEnvelopeMapper().createObjectNode().put("protocol", "codex-build-allocation")
            .put("version", 1).put("attempt", attempt.toString()).put("installation", Long.toString(generation.installation))
            .put("pid", Long.toString(generation.image.localPid())).put("event", event);
    }

    /**
     * Copies an allocation payload into the existing bounded host envelope.
     * @param body authentic native observation or installation readiness
     * @throws IllegalStateException if copied observations exceed the protocol batch bound
     */
    private void emit(ObjectNode body)
    {
        if (frames.size() >= 64) throw new IllegalStateException("Native copied allocation batch exceeds its bound");
        JsonMapper mapper = scope.getAllocationEnvelopeMapper();
        ObjectNode envelope = mapper.createObjectNode().put("type", "send");
        envelope.set("payload", body);
        frames.add(mapper.writeValueAsString(envelope));
    }

    /**
     * Retains one admitted process incarnation and its original executable descriptor through whole-action zero.
     */
    private static final class Generation
    {
        /** Host-reserved never-reused installation. */
        private final long installation;
        /** Independent held executable and process facts. */
        private final LinuxBazelNamespaceHooks.ImageObservation image;
        /** Original mapped executable inode retained until collection. */
        private final FileChannel file;
        /** Sole continuation was authorized. */
        private boolean resumed;
        /** Original executable incarnation can no longer execute. */
        private boolean retired;

        /**
         * Retains a caller-owned original executable descriptor after successful construction.
         * @param installation host-reserved generation
         * @param image observed held image
         * @param file original executable descriptor
         */
        private Generation(long installation, LinuxBazelNamespaceHooks.ImageObservation image, FileChannel file)
        { this.installation = installation; this.image = image; this.file = file; }
    }
}
