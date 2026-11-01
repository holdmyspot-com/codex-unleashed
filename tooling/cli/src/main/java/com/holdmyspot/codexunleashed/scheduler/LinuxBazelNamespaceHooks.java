package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns privileged instrumentation helpers inside the authenticated action namespace, outside its cgroup.
 * Each admission retains its original process and anonymous pipes before performing fallible setup. The original
 * helper alone calls Frida; copied native messages are decoded on the host without entering the allocation queue.
 * This implementation requires root-owned, sealed runtime inputs already visible in the action mount namespace.
 */
final class LinuxBazelNamespaceHooks implements LinuxBazelAdmission.HookFactory
{
    /** Borrowed pouch values, retained by the invoking scheduler. */
    private final ToolingScope scope;
    /** Unique workload identity used by the real native allocation protocol. */
    private final UUID attempt;
    /** Previously authenticated action owner, whose namespace is the launch target. */
    private final LinuxBazelIdentity.Owner owner;
    /** Exact executable transition authenticated again by the admission owner. */
    private final LinuxBazelIdentity.Execution execution;
    /** Original resolved launch, retaining its exact original argv independently of the held interpreter image. */
    private final LinuxBazelIdentity.Execution launchExecution;
    /** Immutable trusted helper execution policy. */
    private final Launch launch;
    /** Explicit private action/runtime image roots; absent policy permits only the original held image. */
    private final ActionImages images;
    /** Maximum duration of each synchronous pipe transition. */
    private final Duration timeout;

    /**
     * Retains a resource-free factory; no process, pipe, native handle or namespace is acquired here.
     * @param scope borrowed pouch values
     * @param attempt unique workload identity
     * @param owner original authenticated action namespace owner
     * @param execution exact approved held image
     * @param launch root-owned pinned helper runtime policy
     * @param timeout positive bounded command observation duration
     * @throws NullPointerException if an input is null
     * @throws IllegalArgumentException if the timeout is nonpositive or cannot fit nanoseconds
     */
    LinuxBazelNamespaceHooks(ToolingScope scope, UUID attempt, LinuxBazelIdentity.Owner owner,
        LinuxBazelIdentity.Execution execution, Launch launch, Duration timeout)
    {
        this(scope, attempt, owner, execution, execution, launch, timeout);
    }

    /**
     * Retains original launch inputs separately from the kernel-observed held executable/interpreter image.
     * @param scope borrowed pouch values
     * @param attempt unique workload identity
     * @param owner authenticated action namespace owner
     * @param execution exact held ELF/interpreter execution
     * @param launchExecution resolved requested executable with unchanged original argv, environment and cwd
     * @param launch sealed helper runtime policy
     * @param timeout positive bounded command observation duration
     */
    LinuxBazelNamespaceHooks(ToolingScope scope, UUID attempt, LinuxBazelIdentity.Owner owner,
        LinuxBazelIdentity.Execution execution, LinuxBazelIdentity.Execution launchExecution, Launch launch, Duration timeout)
    {
        this(scope, attempt, owner, execution, launchExecution, launch, null, timeout);
    }

    /**
     * Retains explicit action-visible image roots for independently authenticated descendant admission.
     * @param scope borrowed pouch values
     * @param attempt unique workload identity
     * @param owner authenticated action namespace owner
     * @param execution exact initial held image
     * @param launchExecution original resolved launch preserving its native vector
     * @param launch sealed privileged helper runtime
     * @param images explicit private input/runtime roots, or null for strict initial-image-only operation
     * @param timeout positive bounded observation duration
     */
    LinuxBazelNamespaceHooks(ToolingScope scope, UUID attempt, LinuxBazelIdentity.Owner owner,
        LinuxBazelIdentity.Execution execution, LinuxBazelIdentity.Execution launchExecution, Launch launch,
        ActionImages images, Duration timeout)
    {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.attempt = Objects.requireNonNull(attempt, "attempt");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.launchExecution = Objects.requireNonNull(launchExecution, "launchExecution");
        this.launch = Objects.requireNonNull(launch, "launch");
        this.images = images;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || timeout.toNanos() <= 0)
            throw new IllegalArgumentException("Namespace helper timeout must be positive and bounded");
    }

    @Override
    public LinuxBazelAdmission.HookOwner create(long sequence, LinuxBazelIdentity.Child child,
        LinuxBazelControl.Identity localIdentity, LinuxBazelContainment containment, AllocationWaits waits)
    {
        if (sequence < 0 || child.startTicks() != localIdentity.birth())
            throw new IllegalArgumentException("Namespace helper admission must retain the authenticated child birth");
        long installation = Math.incrementExact(sequence);
        return new Owner(scope, attempt, owner, execution, launchExecution, launch, images, timeout, installation, child, localIdentity, containment, waits);
    }

    /**
     * Explicit action-visible roots whose held programs may execute with the action credentials.
     * These paths confer no privileged loading or helper authority.
     * @param inputRoot current private attempt input tree, including generated programs
     * @param runtimeRoots exact runtime binding targets and independently mapped private temporary roots
     */
    record ActionImages(Path inputRoot, List<Path> runtimeRoots)
    {
        /**
         * Copies normalized absolute roots without observing an action-supplied process.
         * @param inputRoot private current attempt input tree
         * @param runtimeRoots configured runtime and private temporary targets
         */
        ActionImages
        {
            requireImagePath(inputRoot);
            runtimeRoots = List.copyOf(runtimeRoots);
            runtimeRoots.forEach(LinuxBazelNamespaceHooks::requireImagePath);
            if (inputRoot.equals(Path.of("/")) || runtimeRoots.contains(Path.of("/")))
                throw new IllegalArgumentException("An image root cannot expose the whole namespace");
        }

        /**
         * Checks component-aware membership in the explicit private/runtime mapping.
         * @param executable independently observed namespace-local executable path
         * @return whether the executable belongs to an explicit supported mapping
         */
        boolean permits(Path executable)
        {
            requireImagePath(executable);
            return executable.startsWith(inputRoot) || runtimeRoots.stream().anyMatch(executable::startsWith);
        }
    }

    /**
     * Rejects relative or unnormalized image authority paths.
     * @param path namespace-local path
     */
    private static void requireImagePath(Path path)
    {
        if (!path.isAbsolute() || !path.normalize().equals(path))
            throw new IllegalArgumentException("Image path must be normalized and absolute");
    }

    /**
     * Complete independently observed image facts, retained across hook readiness.
     * @param hostPid controller-visible process
     * @param localPid pinned namespace-local process
     * @param birth original kernel start ticks
     * @param parent current controller-visible parent
     * @param executable actual namespace-visible executable path
     * @param sha256 actual held executable bytes
     * @param inode actual executable device/inode/size/time identity
     * @param arguments exact observed native argument vector
     * @param cgroup complete stable cgroup membership text
     * @param pidNamespace actual PID namespace inode link
     * @param mountNamespace actual mount namespace inode link
     */
    record ImageObservation(long hostPid, long localPid, long birth, long parent, Path executable,
        String sha256, Map<String, Object> inode, List<String> arguments, String cgroup,
        String pidNamespace, String mountNamespace)
    {
        /**
         * Freezes independent observations before a fallible readiness operation.
         * @param hostPid controller PID
         * @param localPid namespace-local PID
         * @param birth kernel start ticks
         * @param parent controller parent PID
         * @param executable namespace-local actual executable
         * @param sha256 actual executable digest
         * @param inode complete actual executable inode attributes
         * @param arguments observed argv
         * @param cgroup actual cgroup text
         * @param pidNamespace PID namespace link
         * @param mountNamespace mount namespace link
         */
        ImageObservation
        {
            inode = Map.copyOf(inode);
            arguments = List.copyOf(arguments);
            requireImagePath(executable);
        }

        /**
         * Requires the same process incarnation, image, arguments and isolation after readiness.
         * @param current independently repeated observation
         * @throws IOException if any authenticated fact changed
         */
        void requireUnchanged(ImageObservation current) throws IOException
        {
            if (!equals(current)) throw new IOException("Held image changed across independent readiness authentication");
        }
    }

    /**
     * One immutable sealed file or directory interpreted in its owner's filesystem namespace.
     * A file seal is its SHA-256. A directory seal hashes sorted relative UTF-8 names, NUL, each regular file's
     * lowercase SHA-256 and NUL. Symlinks and special files are rejected; every traversed inode must be root-owned
     * and not writable by group or others. Directory seals cover all runtime native dependencies and class files.
     * @param path absolute normalized pathname; the nsenter seal is host-local, all others are namespace-local
     * @param sha256 lowercase SHA-256 supplied by the trusted invocation manifest
     */
    record Seal(Path path, String sha256)
    {
        /**
         * Validates policy without inspecting or acquiring filesystem resources.
         * @param path normalized absolute pathname
         * @param sha256 immutable trusted digest
         * @throws IllegalArgumentException if the path or digest is malformed
         * @throws NullPointerException if an input is null
         */
        Seal
        {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(sha256, "sha256");
            if (!path.isAbsolute() || !path.normalize().equals(path) || !sha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Namespace helper seal requires an absolute normalized path and SHA-256");
        }
    }

    /**
     * Exact trusted helper execution; no action-supplied executable, classpath or environment is consulted.
     * @param nsenter host-local supported nsenter executable seal
     * @param runtime sealed namespace-visible JDK directory, including native dependencies
     * @param java namespace-visible Java executable seal below runtime
     * @param classpath ordered sealed namespace-visible jars or class/resource directories
     * @param producer namespace-visible Frida producer library seal
     */
    record Launch(Seal nsenter, Seal runtime, Seal java, List<Seal> classpath, Seal producer)
    {
        /**
         * Copies execution policy and requires the Java image to belong to the sealed runtime tree.
         * @param nsenter host-local executable seal
         * @param runtime namespace-visible JDK directory seal
         * @param java namespace-visible Java image seal
         * @param classpath exact ordered classpath seals
         * @param producer namespace-visible native producer seal
         * @throws NullPointerException if an input or collection member is null
         * @throws IllegalArgumentException if Java is outside runtime or classpath is empty
         */
        Launch
        {
            Objects.requireNonNull(nsenter, "nsenter");
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(java, "java");
            Objects.requireNonNull(producer, "producer");
            classpath = List.copyOf(classpath);
            if (!java.path().startsWith(runtime.path()) || classpath.isEmpty())
                throw new IllegalArgumentException("Namespace helper requires a sealed JDK and explicit classpath");
        }

        /**
         * Builds the exact JVM argv, with native access explicitly enabled for the owned Frida binding.
         * @return immutable executable and arguments
         */
        List<String> arguments()
        {
            return List.of(java.path().toString(), "--enable-native-access=ALL-UNNAMED", "-cp",
                String.join(":", classpath.stream().map(seal -> seal.path().toString()).toList()),
                LinuxBazelHelperMain.class.getName(), "--namespace-helper");
        }
    }

    /**
     * Validates exact pre-exec authority against independently observed post-exec process identity.
     * Linux may retain the original leader birth or expose the original executing thread birth.
     * @param migration authentic copied held exec event
     * @param authorized exact independently authenticated pre-exec thread
     * @param localPid retained process identity
     * @param originalBirth independently admitted previous leader birth
     * @param observedBirth independently observed post-exec leader birth
     * @return true only for the exact authorized migration and one retained original birth
     */
    static boolean authenticatedExecMigration(LinuxAllocationTrace.Held migration,
        LinuxAllocationTrace.Identity authorized, long localPid, long originalBirth, long observedBirth)
    {
        return migration != null && migration.origin() == 1 && authorized != null &&
            authorized.tid() != localPid && authorized.equals(migration.formerThread()) &&
            migration.task().tid() == localPid && migration.task().birthTicks() == observedBirth &&
            (observedBirth == originalBirth || observedBirth == authorized.birthTicks());
    }

    /** One live-image admission sequence, retaining its original checked observation failure. */
    @FunctionalInterface
    interface LiveImageAdmission
    {
        /**
         * Performs the original live-image admission once.
         * @throws IOException if native image authentication or continuation is uncertain
         */
        void admit() throws IOException;
    }

    /**
     * Runs live-image admission while retaining containment ownership across a disappearing procfs image race.
     * Missing procfs entries or exact empty native command observations are tolerated only after a fresh
     * independently confirmed whole-tree zero; malformed vectors and other observation faults remain failures.
     * @param containment original independent whole-tree observer
     * @param admission original admission operation, invoked exactly once
     * @throws IOException if image admission is uncertain while native execution remains possible
     */
    static void admitLiveImages(LinuxBazelContainment containment, LiveImageAdmission admission) throws IOException
    {
        try { admission.admit(); }
        catch (java.nio.file.NoSuchFileException | LinuxBazelIdentity.EmptyCommandVectorException ended)
        {
            if (ended instanceof java.nio.file.NoSuchFileException missing &&
                (missing.getFile() == null || !Path.of(missing.getFile()).startsWith(Path.of("/proc")))) throw ended;
            boolean executionImpossible;
            try { executionImpossible = containment.observeExecutionImpossible(); }
            catch (IOException | RuntimeException uncertain)
            {
                if (uncertain != ended) ended.addSuppressed(uncertain);
                throw ended;
            }
            if (!executionImpossible) throw ended;
        }
    }

    /**
     * Original process owner retained by admission through actual whole-workload zero and helper cleanup.
     */
    private static final class Owner implements LinuxBazelAdmission.HookOwner
    {
        /** Original borrowed serialization scope. */
        private final ToolingScope scope;
        /** Scope mapper used only on the host operation thread. */
        private final JsonMapper json;
        /** Original attempt identity. */
        private final UUID attempt;
        /** Positive installation generation supplied by the admission owner, never reused. */
        private final long installation;
        /** Original authenticated namespace owner. */
        private final LinuxBazelIdentity.Owner owner;
        /** Backend denials require explicit whole-attempt retry rather than an allocation grant. */
        private boolean retryAfterDenial;
        /** Copied original nonleader exec entries awaiting independent host thread observations. */
        private final List<LinuxAllocationTrace.PreExec> pendingExec = new ArrayList<>();
        /** Independently observed original thread identities, keyed by their retained process group. */
        private final Map<Long, LinuxAllocationTrace.Identity> authorizedExec = new LinkedHashMap<>();
        /** Original root's opaque independently observed thread authorization, never native message authority alone. */
        private LinuxBazelIdentity.ExecAuthorization rootExec;
        /** Copied actual exec transitions, matched to the earlier independently observed thread. */
        private final Map<Long, LinuxAllocationTrace.Held> migrations = new LinkedHashMap<>();
        /** Approved held image. */
        private final LinuxBazelIdentity.Execution execution;
        /** Original resolved executable and unchanged original execution vector. */
        private final LinuxBazelIdentity.Execution launchExecution;
        /** Sealed execution policy. */
        private final Launch launch;
        /** Explicit image authority; null preserves strict initial-image-only behavior. */
        private final ActionImages images;
        /** Independently authenticated current generations indexed by namespace-local PID. */
        private final Map<Long, ImageObservation> currentImages = new LinkedHashMap<>();
        /** All admitted process image observations retained by never-reused generation. */
        private final Map<Long, ImageObservation> generationImages = new LinkedHashMap<>();
        /** Current hook identity per local process, retired only after independent exec/death proof. */
        private final Map<Long, Long> currentInstallations = new LinkedHashMap<>();
        /** Decoders retained for every partial and retired installation. */
        private final Map<Long, AllocationMessages> imageMessages = new LinkedHashMap<>();
        /** Installation identities whose actual native readiness was copied. */
        private final Set<Long> readyImages = new HashSet<>();
        /** Copied held events awaiting independent host authorization on the operation thread. */
        private final List<FridaRuntime.Child> pendingImages = new ArrayList<>();
        /** Authentic native root status; descendant ownership survives this observation. */
        private LinuxAllocationTrace.Terminal rootTerminal;
        /** Native owner is collecting descendants after an actual signaled root death. */
        private boolean rootSignalRetirement;
        /** Copied dead-generation claims deferred until outside any queue continuation. */
        private final List<Long> pendingRetirements = new ArrayList<>();
        /** Open actual executable inodes retained until whole-action zero. */
        private final List<FileChannel> heldFiles = new ArrayList<>();
        /** Next generation reservation, never reused after a partial effect. */
        private long nextInstallation;
        /** Bounded monotonic observation interval. */
        private final long timeoutNanos;
        /** Host-visible authenticated child birth retained until cleanup. */
        private final LinuxBazelIdentity.Child child;
        /** Independently authenticated namespace-local target identity. */
        private final LinuxBazelControl.Identity local;
        /** Original host workload cgroup authority. */
        private final LinuxBazelContainment containment;
        /** Original scheduler publication collection; never calls the allocation queue. */
        private final AllocationWaits waits;
        /** Native protocol decoder for local target PID and original installation. */
        private final AllocationMessages messages;
        /** Original nsenter process retained before any fallible subsequent acquisition. */
        private Process process;
        /** Original host pipe output. */
        private DataOutputStream output;
        /** Partial bounded response retained between nonblocking observations. */
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();
        /** Actual helper host PID authenticated independently of its HELLO claim. */
        private long helperPid;
        /** Actual helper birth retained to reject PID reuse. */
        private String helperBirth;
        /** Sole next request identity. */
        private long next = 1;
        /** Original transition failure prevents further work effects. */
        private IOException failure;
        /** Setup is never retried after its first attempt. */
        private boolean prepareAttempted;
        /** Permanent grant withdrawal marker. */
        private boolean stopped;
        /** STOP is never replayed after an uncertain pipe write. */
        private boolean stopAttempted;
        /** CLOSE is never replayed after an uncertain native effect. */
        private boolean closeAttempted;
        /** Completed cleanup observation. */
        private boolean closed;
        /** Exactly one observed native readiness, required before resume. */
        private boolean ready;
        /** Sole original image resume attempt. */
        private boolean resumeAttempted;
        /** Sole installation attempt. */
        private boolean installAttempted;
        /** INIT write reservation distinguishes a native-free EOF shutdown from protocol cleanup. */
        private boolean initAttempted;

        /**
         * Retains all ownership and identity inputs before external acquisition.
         * @param scope borrowed pouch scope
         * @param attempt original workload identity
         * @param owner original namespace owner
         * @param execution approved image
         * @param launchExecution original resolved executable and unchanged original launch vector
         * @param launch pinned runtime inputs
         * @param images explicit supported private/runtime roots, or null
         * @param timeout positive observation bound
         * @param installation positive original installation generation
         * @param child host-visible authenticated child
         * @param local namespace-local authenticated identity
         * @param containment actual host cgroup owner
         * @param waits original scheduler event collection
         */
        Owner(ToolingScope scope, UUID attempt, LinuxBazelIdentity.Owner owner,
            LinuxBazelIdentity.Execution execution, LinuxBazelIdentity.Execution launchExecution, Launch launch, ActionImages images, Duration timeout, long installation,
            LinuxBazelIdentity.Child child, LinuxBazelControl.Identity local,
            LinuxBazelContainment containment, AllocationWaits waits)
        {
            this.scope = scope;
            this.json = scope.getNamespaceControlMapper();
            this.attempt = attempt;
            this.installation = installation;
            this.owner = owner;
            this.execution = execution;
            this.launchExecution = launchExecution;
            this.launch = launch;
            this.images = images;
            this.nextInstallation = installation;
            this.timeoutNanos = timeout.toNanos();
            this.child = child;
            this.local = local;
            this.containment = Objects.requireNonNull(containment, "containment");
            this.waits = Objects.requireNonNull(waits, "waits");
            this.messages = new AllocationMessages(scope, attempt, installation, local.pid());
            imageMessages.put(installation, messages);
            currentInstallations.put(local.pid(), installation);
        }

        @Override public boolean requiresRetryAfterDenial() { return retryAfterDenial; }

        @Override
        public void prepareHeld() throws IOException
        {
            requireActive();
            if (prepareAttempted) throw new IOException("Original namespace helper setup cannot be repeated");
            prepareAttempted = true;
            try
            {
                containment.requireContained();
                Path proc = Path.of("/proc", Long.toString(owner.peer().pid()));
                String original = birth(proc);
                Path root = proc.resolve("root");
                verify(launch.nsenter(), Path.of("/"));
                verify(launch.runtime(), root);
                verify(launch.java(), root);
                for (Seal seal : launch.classpath()) verify(seal, root);
                verify(launch.producer(), root);
                List<String> argv = new ArrayList<>(List.of(launch.nsenter().path().toString(),
                    "--target=" + owner.peer().pid(), "--mount", "--pid", "--root=" + root,
                    "--wd=" + root, "--"));
                argv.addAll(launch.arguments());
                ProcessBuilder builder = new ProcessBuilder(argv);
                builder.environment().clear();
                builder.redirectError(ProcessBuilder.Redirect.INHERIT);
                process = builder.start();
                output = new DataOutputStream(process.getOutputStream());
                ObjectNode hello = receive();
                if (!"hello".equals(LinuxBazelHelperMain.text(hello, "kind")))
                    throw new IOException("Namespace helper did not provide its original HELLO");
                authenticateHelper(proc, LinuxBazelHelperMain.positive(hello, "localPid"));
                if (!original.equals(birth(proc)))
                    throw new IOException("Action namespace owner changed during helper launch");
                ObjectNode config = request("init").put("attempt", attempt.toString())
                    .put("installation", Long.toString(installation)).put("localPid", Long.toString(local.pid()))
                    .put("preparedBirth", Long.toString(local.birth())).put("producer", launch.producer().path().toString())
                    .put("producerSha256", launch.producer().sha256()).put("timeoutNanos", Long.toString(timeoutNanos))
                    .put("ownerLocalPid", Long.toString(owner.localPid())).put("ownerBirth", Long.toString(owner.peer().startTicks()));
                config.set("execution", encodeExecution(json, execution));
                config.set("launchExecution", encodeExecution(json, launchExecution));
                if (images != null)
                {
                    ObjectNode policy = json.createObjectNode().put("inputRoot", images.inputRoot().toString());
                    policy.set("runtimeRoots", json.valueToTree(images.runtimeRoots().stream().map(Path::toString).toList()));
                    config.set("actionImages", policy);
                }
                initAttempted = true;
                exchange(config);
                exchange(request("prepareHeld"));
            }
            catch (IOException | RuntimeException cause) { throw withdraw("Namespace helper prepare failed", cause); }
        }

        @Override
        public void install() throws IOException
        {
            requireActive();
            if (installAttempted || process == null) throw new IOException("Namespace hook installation is unavailable or repeated");
            installAttempted = true;
            exchange(request("install"));
            if (!ready) throw withdraw("Namespace helper did not copy the real original ready acknowledgement", null);
        }

        @Override
        public void resumeImage() throws IOException
        {
            requireActive();
            if (!ready || resumeAttempted) throw new IOException("Original image resume is unavailable or repeated");
            resumeAttempted = true;
            currentImages.put(local.pid(), observe(child.hostPid(), local.pid()));
            generationImages.put(installation, currentImages.get(local.pid()));
            exchange(request("resumeImage"));
            admitPending();
        }

        @Override
        public void pump() throws IOException
        {
            if (rootSignalRetirement) return;
            requireActive();
            if (!resumeAttempted) throw new IOException("Namespace helper has not resumed its original image");
            exchange(request("pump"));
            if (rootSignalRetirement) return;
            try
            {
                retirePending();
                admitPending();
            }
            catch (IOException original)
            {
                ImageObservation latest = latestRoot();
                if (!retryAfterDenial || !originalImageEnded(Path.of("/proc", Long.toString(latest.hostPid())), latest.birth()))
                    throw original;
                long start = System.nanoTime();
                while (rootTerminal == null)
                {
                    exchange(request("pump"));
                    if (rootTerminal == null) pause(start, "actual root terminal collection before descendant admission");
                }
                if (!rootSignalRetirement) throw original;
            }
        }

        @Override
        public void grant(AllocationWaits.Wait wait) throws IOException
        {
            requireActive();
            exchange(request("grant").put("installation", Long.toString(wait.installation()))
                .put("sequence", Long.toString(wait.sequence())));
        }

        @Override
        public void stop() throws IOException
        {
            stopped = true;
            if (stopAttempted || process == null) return;
            stopAttempted = true;
            exchange(request("stop"));
        }

        @Override
        public void close(AllocationHook.ExecutionBarrier barrier) throws IOException
        {
            if (closed) return;
            if (closeAttempted) throw new IOException("Original helper cleanup has uncertain completion", failure);
            if (!barrier.reached()) throw new IOException("Namespace helper cleanup requires actual whole-workload zero");
            closeAttempted = true;
            stopped = true;
            if (process == null) { closed = true; return; }
            if (initAttempted) exchange(request("close").put("executionImpossible", true));
            else output.close();
            long start = System.nanoTime();
            while (process.isAlive() || sameHelperAlive())
                pause(start, "original helper exit after acknowledged native close");
            if (initAttempted && process.exitValue() != 0) throw withdraw("Original namespace helper exited unsuccessfully", null);
            process.getInputStream().close();
            output.close();
            process.getErrorStream().close();
            for (FileChannel heldFile : heldFiles) heldFile.close();
            closed = true;
        }

        /**
         * Creates one original request identity before a potentially uncertain write; identities are never reused.
         * @param operation exact package-owned operation
         * @return mutable request with its reserved identity
         */
        private ObjectNode request(String operation)
        {
            long id = next;
            next = Math.incrementExact(next);
            return json.createObjectNode().put("schema", LinuxBazelHelperMain.SCHEMA)
                .put("id", Long.toString(id)).put("operation", operation);
        }

        /**
         * Sends once, verifies the matching response, and copies validated events without queue reentry.
         * @param request original request whose ID has already been reserved
         * @throws IOException if delivery, response, native authentication or bounded observation fails
         */
        private void exchange(ObjectNode request) throws IOException
        {
            try
            {
                LinuxBazelHelperMain.write(output, json, request);
                ObjectNode response = receive();
                if (!LinuxBazelHelperMain.text(response, "id").equals(LinuxBazelHelperMain.text(request, "id")) ||
                    !response.path("ok").isBoolean() || !response.path("ok").asBoolean())
                    throw new IOException("Original namespace helper response failed or differs: " + response);
                JsonNode retry = response.path("requiresRetryAfterDenial");
                if (retry.isBoolean()) retryAfterDenial = retry.asBoolean();
                JsonNode terminal = response.path("rootTerminal");
                if (!terminal.isMissingNode() && !terminal.isNull())
                {
                    LinuxAllocationTrace.Identity identity = nativeIdentity(terminal.path("identity"));
                    JsonNode status = terminal.path("waitStatus");
                    ImageObservation latest = latestRoot();
                    if (!retryAfterDenial || identity.tid() != local.pid() || identity.birthTicks() != latest.birth() ||
                        !status.isIntegralNumber() || !status.canConvertToInt() || status.asInt() < 0 ||
                        (status.asInt() & 255) == 127 ||
                        !originalImageEnded(Path.of("/proc", Long.toString(latest.hostPid())), latest.birth()))
                        throw new IOException("Native root terminal record lacks its retained original identity or terminal proof");
                    LinuxAllocationTrace.Terminal copied = new LinuxAllocationTrace.Terminal(identity, status.asInt());
                    if (rootTerminal != null && !rootTerminal.equals(copied))
                        throw new IOException("Original native root terminal record changed");
                    rootTerminal = copied;
                    rootSignalRetirement = (copied.waitStatus() & 127) != 0;
                }
                JsonNode entries = response.path("preExec");
                if (!entries.isMissingNode())
                {
                    if (!entries.isArray() || entries.size() > 32 || pendingExec.size() + entries.size() > 64)
                        throw new IOException("Native pre-exec reports exceed their bound");
                    for (JsonNode entry : entries)
                    {
                        LinuxAllocationTrace.Identity thread = nativeIdentity(entry.path("thread"));
                        int group = Math.toIntExact(entry.path("processId").asLong());
                        if (group <= 0) throw new IOException("Native exec group identity must be positive");
                        pendingExec.add(new LinuxAllocationTrace.PreExec(thread, group));
                    }
                }
                JsonNode transitions = response.path("migrations");
                if (!transitions.isMissingNode())
                {
                    if (!transitions.isArray() || transitions.size() > 32 || migrations.size() + transitions.size() > 64)
                        throw new IOException("Native exec transitions exceed their bound");
                    for (JsonNode transition : transitions)
                    {
                        LinuxAllocationTrace.Identity task = nativeIdentity(transition.path("task"));
                        LinuxAllocationTrace.Identity former = nativeIdentity(transition.path("formerThread"));
                        LinuxAllocationTrace.Held copied = new LinuxAllocationTrace.Held(task,
                            Math.toIntExact(transition.path("parentPid").asLong()), transition.path("origin").asInt(),
                            transition.path("path").asString(), former);
                        migrations.put((long) task.tid(), copied);
                    }
                }
                JsonNode held = response.path("held");
                if (!held.isArray() || held.size() > 32 || pendingImages.size() + held.size() > 64)
                    throw new IOException("Copied held-event batch differs or exceeds its bound");
                for (JsonNode event : held)
                {
                    if (!event.isObject() || !event.path("pid").isIntegralNumber() ||
                        !event.path("parentPid").isIntegralNumber() || !event.path("origin").isIntegralNumber() ||
                        (!event.path("path").isString() && !event.path("path").isNull()))
                        throw new IOException("Copied held image shape differs");
                    pendingImages.add(new FridaRuntime.Child(event.path("pid").asLong(), event.path("parentPid").asLong(),
                        event.path("origin").asInt(), event.path("path").isNull() ? null : event.path("path").asString()));
                }
                JsonNode retired = response.path("retired");
                if (!retired.isArray() || retired.size() > 64) throw new IOException("Retired image generation report differs");
                for (JsonNode generation : retired)
                {
                    if (!generation.isIntegralNumber() || !generation.canConvertToLong())
                        throw new IOException("Retired image generation is not an exact identity");
                    if (pendingRetirements.size() >= 64) throw new IOException("Pending image retirements exceed their bound");
                    pendingRetirements.add(generation.asLong());
                }
                JsonNode frames = response.path("frames");
                if (!frames.isArray() || frames.size() > 64) throw new IOException("Namespace helper frame batch differs");
                for (JsonNode frame : frames)
                {
                    if (!frame.isString()) throw new IOException("Copied native frame must retain its original string");
                    JsonNode envelope = json.readTree(frame.asString());
                    if (!(envelope.path("payload") instanceof ObjectNode body))
                        throw new IOException("Copied native allocation payload is not an object");
                    long generation = LinuxBazelHelperMain.positive(body, "installation");
                    AllocationMessages decoder = imageMessages.get(generation);
                    if (decoder == null) throw new IOException("Copied allocation frame has an unreserved installation");
                    AllocationMessages.Event event = decoder.read(frame.asString());
                    if (event instanceof AllocationMessages.Ready)
                    {
                        if (!readyImages.add(generation)) throw new IOException("Namespace hook readiness was repeated");
                        if (generation == installation) ready = true;
                    }
                    else if (event instanceof AllocationMessages.Blocked blocked) waits.blocked(blocked.identity());
                }
            }
            catch (IOException | RuntimeException cause) { throw withdraw("Original namespace helper exchange failed", cause); }
        }

        /**
         * Revalidates copied death claims outside queue continuations before retiring native wait identities.
         * @throws IOException if the original process birth still exists or retirement is uncertain
         */
        private void retirePending() throws IOException
        {
            while (!pendingRetirements.isEmpty())
            {
                long identity = pendingRetirements.removeFirst();
                ImageObservation image = generationImages.get(identity);
                if (image == null) throw new IOException("Retired generation was never independently admitted");
                Path proc = Path.of("/proc", Long.toString(image.hostPid()));
                if (!originalImageEnded(proc, image.birth()))
                    throw new IOException("Retired image still has its original executable host birth");
                waits.retireInstallation(identity);
                if (Objects.equals(currentInstallations.get(image.localPid()), identity))
                {
                    currentInstallations.remove(image.localPid());
                    currentImages.remove(image.localPid());
                }
            }
        }

        /**
         * Authenticates held notifications independently, waits for their actual hook readiness and rechecks before resume.
         * @throws IOException if any lineage, mapping, held image, native readiness or sole resume is uncertain
         */
        private void admitPending() throws IOException
        {
            admitLiveImages(containment, this::admitPendingLive);
        }

        /**
         * Admits original live images; a disappeared proc entry is resolved by the outer containment barrier.
         * @throws IOException if native identity, image or continuation is uncertain
         */
        private void admitPendingLive() throws IOException
        {
            authenticateExecEntries();
            while (!pendingImages.isEmpty())
            {
                retirePending();
                if (images == null) throw withdraw("Descendant image has no explicit private/runtime policy", null);
                FridaRuntime.Child event = pendingImages.removeFirst();
                if (event.pid() <= 0 || event.pid() > 4294967295L || event.origin() < 0 || event.origin() > 2)
                    throw withdraw("Unknown held descendant identity or origin", null);
                LinuxAllocationTrace.Held migration = migrations.get(event.pid());
                LinuxAllocationTrace.Identity authorized = authorizedExec.get(event.pid());
                boolean nonleaderExec = migration != null && migration.formerThread() != null &&
                    migration.formerThread().tid() != event.pid();
                if (nonleaderExec)
                {
                    if (authorized == null || !authorized.equals(migration.formerThread()) ||
                        migration.task().tid() != event.pid())
                        throw new IOException("Held nonleader exec lacks its exact independently observed original thread");
                    if (event.pid() == local.pid())
                    {
                        if (rootExec == null) throw new IOException("Original root exec lacks its containment authorization");
                        containment.admitExec(rootExec);
                        rootExec = null;
                    }
                }
                ImageObservation observed = map(event.pid());
                ImageObservation previous = currentImages.get(event.pid());
                ImageObservation parent = null;
                if (event.origin() == 1)
                {
                    if (event.parentPid() != event.pid()) throw new IOException("Held exec must be self-parented");
                    if (previous != null)
                    {
                        migrations.remove(event.pid());
                        LinuxAllocationTrace.Identity former = authorizedExec.remove(event.pid());
                        boolean sameBirth = previous.birth() == observed.birth();
                        boolean migratedBirth = authenticatedExecMigration(migration, former, event.pid(),
                            previous.birth(), observed.birth());
                        boolean authenticatedBirth = nonleaderExec ? migratedBirth : sameBirth;
                        if (previous.hostPid() != observed.hostPid() || !authenticatedBirth ||
                            previous.parent() != observed.parent()) throw new IOException("Held exec changed its retained process birth");
                    }
                    else parent = parent(observed.parent());
                }
                else
                {
                    parent = currentImages.get(event.parentPid());
                    if (previous != null || parent == null || parent.hostPid() != observed.parent())
                        throw new IOException("Held child has no retained original parent");
                }
                if (previous == null && parent == null) throw new IOException("Held image lacks an authenticated original lineage");
                requireRequestedImage(Path.of("/proc", Long.toString(observed.hostPid())), event,
                    parent == null ? null : parent.executable(), observed.arguments(), images);
                if (parent != null) parent.requireUnchanged(observe(parent.hostPid(), parent.localPid()));
                FileChannel image = FileChannel.open(Path.of("/proc", Long.toString(observed.hostPid()), "exe"), StandardOpenOption.READ);
                heldFiles.add(image);
                if (previous != null)
                {
                    Long retired = currentInstallations.get(observed.localPid());
                    if (retired == null) throw new IOException("Replaced image has no retained hook generation");
                    waits.retireInstallation(retired);
                }
                long generation = Math.incrementExact(nextInstallation);
                nextInstallation = generation;
                imageMessages.put(generation, new AllocationMessages(scope, attempt, generation, observed.localPid()));
                generationImages.put(generation, observed);
                ObjectNode command = imageRequest("installHeld", observed, generation);
                command.put("eventParent", Long.toString(event.parentPid())).put("origin", event.origin());
                if (event.path() != null) command.put("requested", event.path());
                else command.putNull("requested");
                exchange(command);
                if (!readyImages.contains(generation)) throw new IOException("Held image did not acknowledge its distinct native readiness");
                observed.requireUnchanged(observe(observed.hostPid(), observed.localPid()));
                if (parent != null) parent.requireUnchanged(observe(parent.hostPid(), parent.localPid()));
                if (Thread.currentThread().isInterrupted()) throw new IOException("Held descendant resume interrupted");
                exchange(imageRequest("resumeHeld", observed, generation));
                currentImages.put(event.pid(), observed);
                currentInstallations.put(event.pid(), generation);
            }
        }

        /**
         * Decodes a copied native task identity without treating it as host authority.
         * @param value bounded helper identity object
         * @return positive original task identity
         * @throws IOException if the task or birth has an invalid representation
         */
        private static LinuxAllocationTrace.Identity nativeIdentity(JsonNode value) throws IOException
        {
            JsonNode tid = value.path("tid");
            JsonNode birth = value.path("birthTicks");
            if (!tid.isIntegralNumber() || !tid.canConvertToInt() || tid.asInt() <= 0 ||
                !birth.isIntegralNumber() || !birth.canConvertToLong() || birth.asLong() <= 0)
                throw new IOException("Copied native task identity differs from its exact positive representation");
            return new LinuxAllocationTrace.Identity(tid.asInt(), birth.asLong());
        }

        /**
         * Independently observes a nonleader thread before its held exec syscall may proceed.
         * @throws IOException if thread birth, membership or namespace identity differs
         */
        private void authenticateExecEntries() throws IOException
        {
            while (!pendingExec.isEmpty())
            {
                LinuxAllocationTrace.PreExec entry = pendingExec.removeFirst();
                ImageObservation processImage = currentImages.get((long) entry.processId());
                if (processImage == null || entry.thread().tid() == entry.processId())
                    throw new IOException("Nonleader exec has no admitted original process group");
                processImage.requireUnchanged(observe(processImage.hostPid(), processImage.localPid()));
                if (entry.processId() == local.pid())
                {
                    if (rootExec != null) throw new IOException("Original root already has a pending exec authorization");
                    rootExec = containment.prepareExec(entry.thread().tid(), entry.thread().birthTicks());
                }
                else
                {
                    Path tasks = Path.of("/proc", Long.toString(processImage.hostPid()), "task");
                    Path matching = null;
                    try (java.nio.file.DirectoryStream<Path> directories = Files.newDirectoryStream(tasks))
                    {
                        for (Path task : directories)
                        {
                            String status = Files.readString(task.resolve("status"));
                            if (namespacePid(status) != entry.thread().tid()) continue;
                            if (matching != null) throw new IOException("Original exec thread maps to multiple host tasks");
                            matching = task;
                        }
                    }
                    if (matching == null || !birth(matching).equals(Long.toString(entry.thread().birthTicks())))
                        throw new IOException("Original nonleader exec thread birth or group membership differs");
                }
                if (authorizedExec.putIfAbsent((long) entry.processId(), entry.thread()) != null)
                    throw new IOException("Original process has another unresolved nonleader exec authorization");
                exchange(request("authorizeExec").put("thread", Integer.toString(entry.thread().tid()))
                    .put("birth", Long.toString(entry.thread().birthTicks())));
            }
        }

        /**
         * Retains authenticated image facts in an original private-pipe command.
         * @param operation installation or resume operation
         * @param image independent host observation
         * @param generation never-reused native hook identity
         * @return exact operation request
         */
        private ObjectNode imageRequest(String operation, ImageObservation image, long generation)
        {
            ObjectNode command = request(operation).put("localPid", Long.toString(image.localPid()))
                .put("birth", Long.toString(image.birth())).put("executable", image.executable().toString())
                .put("sha256", image.sha256()).put("cgroup", image.cgroup()).put("installation", Long.toString(generation));
            command.set("arguments", json.valueToTree(image.arguments()));
            return command;
        }

        /**
         * Selects a retained current parent by independently observed host PID.
         * @param hostPid observed parent
         * @return retained image, or null when this ancestry is unknown
         */
        private ImageObservation parent(long hostPid)
        {
            return currentImages.values().stream().filter(image -> image.hostPid() == hostPid).findFirst().orElse(null);
        }

        /**
         * Maps a copied local PID only through current members of the retained whole-action cgroup.
         * @param localPid copied namespace-local identity, never a host authority
         * @return unique independently authenticated image
         * @throws IOException if mapping is absent, ambiguous, mutable or outside the pinned namespace
         */
        private ImageObservation map(long localPid) throws IOException
        {
            ImageObservation result = null;
            Set<Long> candidates = new HashSet<>();
            try (java.util.stream.Stream<Path> directories = Files.walk(containment.directory()))
            {
                for (Path group : directories.filter(Files::isDirectory).toList())
                    for (String entry : Files.readAllLines(group.resolve("cgroup.procs")))
                        try { candidates.add(Long.parseLong(entry)); }
                        catch (NumberFormatException cause) { throw new IOException("Owned cgroup contains an invalid PID", cause); }
            }
            for (long candidate : candidates)
            {
                Path proc = Path.of("/proc", Long.toString(candidate));
                String status;
                try { status = Files.readString(proc.resolve("status")); }
                catch (java.nio.file.NoSuchFileException ended) { continue; }
                if (namespacePid(status) != localPid) continue;
                ImageObservation image = observe(candidate, localPid);
                if (result != null) throw new IOException("Held local PID maps to multiple owned host births");
                result = image;
            }
            if (result == null) throw new IOException("Held local PID has no authenticated owned host mapping");
            return result;
        }

        /**
         * Observes actual executable inode/hash/argv and stable process isolation through controller proc.
         * @param hostPid independently selected host PID
         * @param localPid expected innermost namespace PID
         * @return repeated and stable native observation
         * @throws IOException if executable mapping, birth, credentials, namespace or containment differs
         */
        private ImageObservation observe(long hostPid, long localPid) throws IOException
        {
            // Containment revalidates the pinned owner and child across their authenticated PID namespace.
            containment.requireContained();
            Path proc = Path.of("/proc", Long.toString(hostPid));
            Path action = Path.of("/proc", Long.toString(owner.peer().pid()));
            String before = birth(proc);
            String status = Files.readString(proc.resolve("status"));
            if (namespacePid(status) != localPid || !AllocationHooks.credentialsMatch(status, execution.uid(), execution.gid()) ||
                !AllocationHooks.contains(containment.directory(), hostPid))
                throw new IOException("Held process credentials, namespace mapping or cgroup differs");
            for (String namespace : List.of("pid", "mnt"))
                if (!Files.isSameFile(proc.resolve("ns").resolve(namespace), action.resolve("ns").resolve(namespace)))
                    throw new IOException("Held process escaped its retained namespace");
            ImageObservation image = observeImage(proc, hostPid, localPid);
            Path executable = image.executable();
            if (images == null ? !executable.equals(execution.image().executable()) : !images.permits(executable))
                throw new IOException("Held executable has no supported current private/runtime mapping: " + executable);
            if (!before.equals(birth(proc)) || !status.equals(Files.readString(proc.resolve("status"))))
            {
                String after = Files.readString(proc.resolve("status"));
                if (!before.equals(birth(proc)) || !AllocationHooks.credentialsMatch(after, execution.uid(), execution.gid()) ||
                    namespacePid(after) != localPid) throw new IOException("Held process changed during authentication");
            }
            containment.requireContained();
            return image;
        }

        /**
         * Incrementally reads one bounded frame using only available pipe bytes and a monotonic deadline.
         * @return exact complete original response
         * @throws IOException if the helper exits, a frame is malformed, observation expires or interruption occurs
         */
        private ObjectNode receive() throws IOException
        {
            long start = System.nanoTime();
            while (true)
            {
                int available = process.getInputStream().available();
                if (available > 0)
                {
                    int remaining = LinuxBazelHelperMain.MAX_FRAME_BYTES + 4 - received.size();
                    if (remaining <= 0) throw new IOException("Namespace helper pipe buffer exceeds its frame bound");
                    byte[] bytes = process.getInputStream().readNBytes(Math.min(available, remaining));
                    if (bytes.length == 0) throw new IOException("Namespace helper pipe ended within a frame");
                    received.write(bytes);
                    byte[] buffer = received.toByteArray();
                    if (buffer.length >= 4)
                    {
                        int size = ByteBuffer.wrap(buffer, 0, 4).getInt();
                        if (size <= 0 || size > LinuxBazelHelperMain.MAX_FRAME_BYTES)
                            throw new IOException("Namespace helper declared an invalid response size");
                        if (buffer.length >= size + 4)
                        {
                            if (buffer.length != size + 4) throw new IOException("Namespace helper sent unsolicited trailing data");
                            received.reset();
                            return LinuxBazelHelperMain.read(new DataInputStream(
                                new java.io.ByteArrayInputStream(buffer)), json);
                        }
                    }
                }
                else if (!process.isAlive()) throw new IOException("Original namespace helper exited before its response");
                pause(start, "original namespace helper response");
            }
        }

        /**
         * Authenticates the actual nsenter child using host proc before any native INIT is sent.
         * @param action original authenticated action proc directory
         * @param localPid untrusted HELLO claim checked against independently observed NSpid
         * @throws IOException if birth, parent, credentials, image, argv, environment, namespace or cgroup differs
         */
        private void authenticateHelper(Path action, long localPid) throws IOException
        {
            String children = Files.readString(Path.of("/proc", Long.toString(process.pid()), "task",
                Long.toString(process.pid()), "children")).strip();
            if (children.isEmpty() || children.split("\\s+").length != 1)
                throw new IOException("nsenter must retain exactly one independently observable helper child");
            try { helperPid = Long.parseLong(children); }
            catch (NumberFormatException cause) { throw new IOException("nsenter helper child identity differs", cause); }
            Path proc = Path.of("/proc", Long.toString(helperPid));
            helperBirth = birth(proc);
            String stat = Files.readString(proc.resolve("stat"));
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            if (!fields[1].equals(Long.toString(process.pid()))) throw new IOException("Helper is not the original nsenter child");
            String status = Files.readString(proc.resolve("status"));
            for (String name : List.of("Uid:", "Gid:"))
            {
                String ids = status.lines().filter(line -> line.startsWith(name)).findFirst()
                    .orElseThrow(() -> new IOException("Helper credential field is absent"));
                if (!List.of(ids.substring(name.length()).strip().split("\\s+")).equals(List.of("0", "0", "0", "0")))
                    throw new IOException("Helper must retain root credentials outside the action cgroup");
            }
            String nspid = status.lines().filter(line -> line.startsWith("NSpid:")).findFirst()
                .orElseThrow(() -> new IOException("Helper namespace PID mapping is absent"));
            String[] ids = nspid.substring(6).strip().split("\\s+");
            if (!ids[ids.length - 1].equals(Long.toString(localPid))) throw new IOException("Helper HELLO PID differs from host NSpid");
            for (String namespace : List.of("pid", "mnt"))
                if (!Files.isSameFile(proc.resolve("ns").resolve(namespace), action.resolve("ns").resolve(namespace)))
                    throw new IOException("Helper did not enter the authenticated action namespace");
            Path executable = action.resolve("root").resolve(launch.java().path().toString().substring(1));
            if (!Files.isSameFile(proc.resolve("exe"), executable) ||
                !LinuxBazelHelperMain.digest(proc.resolve("exe")).equals(launch.java().sha256()))
                throw new IOException("Actual helper JVM image differs from its seal");
            if (!nativeStrings(proc.resolve("cmdline")).equals(launch.arguments()) ||
                !nativeStrings(proc.resolve("environ")).isEmpty() || !Files.isSameFile(proc.resolve("cwd"), action.resolve("root")))
                throw new IOException("Actual helper argv, sanitized environment or cwd differs");
            String cgroup = Files.readString(proc.resolve("cgroup"));
            String workload = Files.readString(Path.of("/proc", Long.toString(child.hostPid()), "cgroup"));
            if (cgroup.equals(workload)) throw new IOException("Privileged instrumentation helper entered the workload cgroup");
            Path cgroupRoot = Path.of("/sys/fs/cgroup");
            if (!containment.directory().startsWith(cgroupRoot) ||
                cgroup.lines().filter(line -> line.startsWith("0::")).count() != 1)
                throw new IOException("Helper requires independently observable unified cgroup containment");
            String workloadRelative = "/" + cgroupRoot.relativize(containment.directory()).toString();
            for (String line : cgroup.lines().toList())
                if (line.startsWith("0::") && (line.substring(3).equals(workloadRelative) ||
                    line.substring(3).startsWith(workloadRelative + "/")))
                    throw new IOException("Privileged helper is contained by the workload subtree");
            if (!helperBirth.equals(birth(proc))) throw new IOException("Helper birth changed during independent authentication");
        }

        /**
         * Observes whether the original helper still exists without treating a reused PID as its owner.
         * @return whether original helper birth still exists
         * @throws IOException if proc observation fails for a still-live original helper
         */
        private boolean sameHelperAlive() throws IOException
        {
            if (helperPid == 0) return false;
            Path proc = Path.of("/proc", Long.toString(helperPid));
            if (!Files.exists(proc)) return false;
            if (!helperBirth.equals(birth(proc))) throw new IOException("Original helper PID was reused before cleanup proof");
            return true;
        }

        /**
         * Selects the latest independently authenticated original-root executable generation.
         * @return retained original root generation
         * @throws IOException if the initial root was never installed
         */
        private ImageObservation latestRoot() throws IOException
        {
            ImageObservation latest = null;
            for (ImageObservation image : generationImages.values())
                if (image.localPid() == local.pid()) latest = image;
            if (latest == null) throw new IOException("Original root generation is unavailable");
            return latest;
        }

        /**
         * Requires the original live work authority, preserving its first failure.
         * @throws IOException if stopped, failed, or already closed
         */
        private void requireActive() throws IOException
        {
            if (stopped || closed || rootSignalRetirement || failure != null) throw new IOException("Namespace helper work authority is withdrawn", failure);
        }

        /**
         * Latches an operational failure without discarding the original process or streams.
         * @param message ownership context
         * @param cause original failure, or null for an explicit gate failure
         * @return original latched failure
         */
        private IOException withdraw(String message, Throwable cause)
        {
            if (failure == null) failure = new IOException(message, cause);
            stopped = true;
            return failure;
        }

        /**
         * Waits a short observation interval without allowing an expired or interrupted control to continue.
         * @param start original monotonic observation start
         * @param operation context retained on failure
         * @throws IOException if interrupted or the original observation bound expires
         */
        private void pause(long start, String operation) throws IOException
        {
            if (System.nanoTime() - start >= timeoutNanos || Thread.currentThread().isInterrupted())
                throw new IOException("Bounded observation failed: " + operation);
            LockSupport.parkNanos(1_000_000);
        }
    }

    /**
     * Proves that an original process incarnation cannot execute, including an unreaped kernel zombie.
     * @param proc independently selected original proc directory
     * @param originalBirth retained original start ticks
     * @return whether the original birth is absent, replaced, zombie or dead
     * @throws IOException if a surviving kernel stat record is malformed or unavailable for another reason
     */
    static boolean originalImageEnded(Path proc, long originalBirth) throws IOException
    {
        String stat;
        try { stat = Files.readString(proc.resolve("stat")); }
        catch (java.nio.file.NoSuchFileException ended) { return true; }
        int end = stat.lastIndexOf(')');
        if (end < 0) throw new IOException("Original image stat lacks its command delimiter");
        String[] fields = stat.substring(end + 2).split(" ");
        if (fields.length < 20 || !fields[19].matches("[1-9][0-9]*") || fields[0].length() != 1)
            throw new IOException("Original image stat identity or state differs");
        return !fields[19].equals(Long.toString(originalBirth)) || fields[0].equals("Z") || fields[0].equals("X");
    }

    /**
     * Reads one process's actual current executable and its process-root-relative mapped inode.
     * @param proc independently selected proc directory
     * @param hostPid controller-visible PID, local PID for the namespace-local helper
     * @param localPid independently matched innermost PID
     * @return immutable executable, arguments, parent, birth and isolation facts
     * @throws IOException if a source inode, path or process fact is inconsistent
     */
    static ImageObservation observeImage(Path proc, long hostPid, long localPid) throws IOException
    {
        String before = birth(proc);
        String stat = Files.readString(proc.resolve("stat"));
        String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
        long parent;
        try { parent = Long.parseLong(fields[1]); }
        catch (NumberFormatException | IndexOutOfBoundsException cause) { throw new IOException("Held parent identity differs", cause); }
        Path executable = namespacePath(proc, "exe");
        Path actual = proc.resolve("exe");
        Path mapped = proc.resolve("root").resolve(executable.toString().substring(1));
        if (!Files.isSameFile(actual, mapped)) throw new IOException("Held executable differs from its namespace-visible mapped inode");
        Map<String, Object> inode = Files.readAttributes(actual, "unix:dev,ino,size,lastModifiedTime,ctime,mode,uid,gid");
        ImageObservation result = new ImageObservation(hostPid, localPid, Long.parseLong(before), parent, executable,
            LinuxBazelHelperMain.digest(actual), inode, nativeStrings(proc.resolve("cmdline")), Files.readString(proc.resolve("cgroup")),
            Files.readSymbolicLink(proc.resolve("ns/pid")).toString(), Files.readSymbolicLink(proc.resolve("ns/mnt")).toString());
        if (!before.equals(birth(proc)) || !inode.equals(Files.readAttributes(actual, "unix:dev,ino,size,lastModifiedTime,ctime,mode,uid,gid")) ||
            !Files.isSameFile(actual, mapped) || !executable.equals(namespacePath(proc, "exe")))
            throw new IOException("Held executable changed during independent observation");
        return result;
    }

    /**
     * Converts a controller-visible proc entry to one unambiguous action-visible inode mapping.
     * A host proc view may prefix the private process root; text removal alone grants no path authority.
     * @param proc independently selected held process directory
     * @param entry trusted proc entry name, executable or working directory
     * @return exact normalized path whose process-root mapping matches the actual proc inode
     * @throws IOException if the path is deleted, unmapped, ambiguous, unstable or cannot be read
     */
    private static Path namespacePath(Path proc, String entry) throws IOException
    {
        Path actual = proc.resolve(entry);
        Path observed = Files.readSymbolicLink(actual);
        if (!observed.isAbsolute() || !observed.normalize().equals(observed) || observed.toString().endsWith(" (deleted)"))
            throw new IOException("Held executable is deleted or lacks a normalized namespace path: " + observed);
        Set<Path> candidates = new java.util.LinkedHashSet<>();
        candidates.add(observed);
        Path root = Files.readSymbolicLink(proc.resolve("root"));
        if (root.isAbsolute() && root.normalize().equals(root) && !root.equals(root.getRoot()) && observed.startsWith(root))
            candidates.add(Path.of("/").resolve(root.relativize(observed)));
        Path result = null;
        for (Path candidate : candidates)
        {
            Path mapped = proc.resolve("root").resolve(candidate.toString().substring(1));
            boolean same;
            try { same = Files.isSameFile(actual, mapped); }
            catch (java.nio.file.NoSuchFileException absent) { same = false; }
            if (!same) continue;
            if (result != null) throw new IOException("Held executable has ambiguous namespace-visible inode mappings");
            result = candidate;
        }
        if (result == null) throw new IOException("Held executable lacks an exact namespace-visible inode mapping: " + observed);
        if (!observed.equals(Files.readSymbolicLink(actual)) || !root.equals(Files.readSymbolicLink(proc.resolve("root"))) ||
            !Files.isSameFile(actual, proc.resolve("root").resolve(result.toString().substring(1))))
            throw new IOException("Held proc inode mapping changed during authentication: " + entry);
        return result;
    }

    /**
     * Resolves the innermost PID from a complete kernel NSpid record.
     * @param status independently read proc status
     * @return positive namespace-local PID
     * @throws IOException if the kernel mapping is absent or malformed
     */
    static long namespacePid(String status) throws IOException
    {
        List<String> lines = status.lines().filter(line -> line.startsWith("NSpid:")).toList();
        if (lines.size() != 1) throw new IOException("Held namespace PID mapping differs");
        String[] values = lines.getFirst().substring(6).strip().split("\\s+");
        try
        {
            long result = Long.parseLong(values[values.length - 1]);
            if (result <= 0) throw new NumberFormatException("Nonpositive namespace PID");
            return result;
        }
        catch (NumberFormatException cause) { throw new IOException("Held namespace PID is malformed", cause); }
    }

    /**
     * Binds a copied fork/exec pathname to the actual mapped executable, including bounded direct shebang chains.
     * @param proc independently selected held process
     * @param event original copied manager event
     * @param parentExecutable authenticated parent image for a fork, otherwise null
     * @param arguments actual held native argv
     * @param policy explicit current action/runtime image roots
     * @throws IOException if source mapping, interpreter chain or kernel argv prefix differs
     */
    static void requireRequestedImage(Path proc, FridaRuntime.Child event, Path parentExecutable,
        List<String> arguments, ActionImages policy) throws IOException
    {
        Path requested;
        String nativeRequested;
        if (event.origin() == 0)
        {
            requested = LinuxCargoImages.executable(event, parentExecutable).normalize();
            nativeRequested = requested.toString();
        }
        else
        {
            if (event.path() == null) throw new IOException("Held executable path is absent");
            nativeRequested = event.path();
            try { requested = Path.of(nativeRequested); }
            catch (java.nio.file.InvalidPathException cause) { throw new IOException("Held executable path is malformed", cause); }
            if (!requested.isAbsolute()) requested = namespacePath(proc, "cwd").resolve(requested);
            requested = requested.normalize();
        }
        List<String> prefix = List.of(nativeRequested);
        for (int depth = 0; depth < 8; depth++)
        {
            if (!policy.permits(requested)) throw new IOException("Held requested image lies outside its explicit action/runtime mapping");
            Path source = proc.resolve("root").resolve(requested.toString().substring(1));
            if (Files.isSameFile(proc.resolve("exe"), source))
            {
                if (depth > 0 && (arguments.size() < prefix.size() || !arguments.subList(0, prefix.size()).equals(prefix)))
                    throw new IOException("Held direct interpreter argv prefix differs");
                return;
            }
            byte[] header;
            try (java.io.InputStream input = Files.newInputStream(source)) { header = input.readNBytes(256); }
            List<String> interpreter = LinuxCargoImages.directInterpreter(header);
            List<String> rewritten = new ArrayList<>(interpreter);
            rewritten.add(depth == 0 ? nativeRequested : requested.toString());
            rewritten.addAll(prefix.subList(1, prefix.size()));
            prefix = List.copyOf(rewritten);
            requested = Path.of(interpreter.getFirst());
        }
        throw new IOException("Held interpreter chain exceeds the supported bounded direct-exec contract");
    }

    /**
     * Encodes exact execution inputs without deriving an executable path from argv zero.
     * @param json borrowed scope mapper
     * @param execution original launch or independently approved held image
     * @return independently represented executable, argv, environment, directory and credentials
     */
    static ObjectNode encodeExecution(JsonMapper json, LinuxBazelIdentity.Execution execution)
    {
        ObjectNode value = json.createObjectNode().put("executable", execution.image().executable().toString())
            .put("sha256", execution.image().sha256()).put("directory", execution.directory().toString())
            .put("uid", Integer.toString(execution.uid())).put("gid", Integer.toString(execution.gid()));
        value.set("arguments", json.valueToTree(execution.arguments()));
        value.set("environment", json.valueToTree(execution.environment()));
        return value;
    }

    /**
     * Reads exact NUL-delimited native text, requiring its final NUL and preserving empty argv members.
     * @param path proc argv or environment file
     * @return exact text members; an empty file is an empty environment
     * @throws IOException if native text is truncated or not round-trip UTF-8
     */
    static List<String> nativeStrings(Path path) throws IOException
    {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length == 0) return List.of();
        if (bytes[bytes.length - 1] != 0) throw new IOException("Native text lacks its final terminator");
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) throw new IOException("Native text is not UTF-8");
        return List.of(value.substring(0, value.length() - 1).split("\u0000", -1));
    }

    /**
     * Reads a Linux PID birth token with the command-name parentheses handled explicitly.
     * @param proc one original process proc directory
     * @return canonical kernel start tick token
     * @throws IOException if the kernel stat observation is malformed
     */
    static String birth(Path proc) throws IOException
    {
        String stat = Files.readString(proc.resolve("stat"));
        int end = stat.lastIndexOf(')');
        if (end < 0) throw new IOException("Process birth observation lacks its command delimiter");
        String[] fields = stat.substring(end + 2).split(" ");
        if (fields.length < 20 || !fields[19].matches("[1-9][0-9]*"))
            throw new IOException("Process birth observation differs");
        return fields[19];
    }

    /**
     * Verifies trusted input bytes and immutable root ownership before privileged Java loading.
     * @param seal trusted namespace-local manifest entry
     * @param root host-visible root of that namespace
     * @throws IOException if any inode, permission or digest differs; symlinks and special files are unsupported
     */
    static void verify(Seal seal, Path root) throws IOException
    {
        Path path = root.resolve(seal.path().toString().substring(1));
        for (Path parent = path; parent != null && !parent.equals(root); parent = parent.getParent()) requireImmutable(parent);
        requireImmutable(root.resolve("."));
        if (!seal.sha256().equals(seal(path))) throw new IOException("Namespace helper input seal differs: " + seal.path());
    }

    /**
     * Calculates the documented seal while checking every traversed inode's ownership and permissions.
     * @param path root-owned regular file or directory without symbolic links
     * @return lowercase SHA-256 seal
     * @throws IOException if an inode is mutable, unsupported, or cannot be read
     */
    static String seal(Path path) throws IOException
    {
        requireImmutable(path);
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return LinuxBazelHelperMain.digest(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Helper input is not a regular file or directory");
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException cause) { throw new IOException("SHA-256 is unavailable", cause); }
        try (var entries = Files.walk(path))
        {
            for (Path entry : entries.sorted().toList())
            {
                requireImmutable(entry);
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Helper tree contains a special inode");
                digest.update(path.relativize(entry).toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(LinuxBazelHelperMain.digest(entry).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) 0);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Requires one root-owned inode immune to unprivileged writes and pathname substitution.
     * @param path existing inode
     * @throws IOException if ownership, Unix mode or symlink type differs
     */
    private static void requireImmutable(Path path) throws IOException
    {
        if (Files.isSymbolicLink(path)) throw new IOException("Helper policy rejects symbolic-link inputs: " + path);
        Map<String, Object> attributes = Files.readAttributes(path, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS);
        int uid = ((Number) attributes.get("uid")).intValue();
        int mode = ((Number) attributes.get("mode")).intValue();
        if (uid != 0 || (mode & 0022) != 0) throw new IOException("Privileged helper input is writable by an untrusted owner: " + path);
    }
}
