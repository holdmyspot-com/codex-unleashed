package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Authenticates a sandbox action owner and maps its prepared direct children into the controller PID namespace.
 * The caller supplies connection credentials captured by the kernel and immutable invocation policy. Observations
 * establish current identity only; the caller must retain the owner and stopped child through containment and use.
 * Instances belong to one operation thread. Authentication does not authorize execution or install hooks.
 */
final class LinuxBazelIdentity
{
    /** Trusted original parent birth and image, retained by the invocation owner. */
    private final Parent parent;
    /** Complete expected action-owner execution, supplied outside the untrusted registration. */
    private final Execution execution;
    /** Independent operating-system observations, never supplied by the registering process. */
    private final Observer observer;

    /**
     * Creates an authenticator using actual Linux proc observations without acquiring resources.
     * @param parent retained original parent birth and image
     * @param execution trusted action-owner execution
     * @throws NullPointerException if an input is null
     */
    LinuxBazelIdentity(Parent parent, Execution execution)
    {
        this(parent, execution, new NativeObserver());
    }

    /**
     * Supplies independent observations to the same production authentication policy.
     * @param parent retained original parent birth and image
     * @param execution trusted action-owner execution
     * @param observer independent native observation boundary
     * @throws NullPointerException if an input is null
     */
    LinuxBazelIdentity(Parent parent, Execution execution, Observer observer)
    {
        this.parent = Objects.requireNonNull(parent, "parent");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    /**
     * Authenticates the kernel-captured connection peer and its claimed namespace-local process ID.
     * A different PID namespace from the controller is permitted. No claimed host PID supplies authority.
     * @param captured actual connection peer in the controller's proc PID namespace
     * @param localPid untrusted process ID claimed inside the owner's PID namespace
     * @return retained owner identity for subsequent child mapping by this authenticator
     * @throws NullPointerException if {@code captured} is null
     * @throws IllegalArgumentException if {@code localPid} is nonpositive
     * @throws IOException if birth, parent, image, credentials, execution or namespace mapping differs or changes
     */
    Owner authenticate(LinuxBorrowedStreams.Peer captured, long localPid) throws IOException
    {
        Objects.requireNonNull(captured, "captured");
        requirePositive(localPid, "localPid");
        Observation originalParent = requireParent();
        Observation owner = observer.observe(captured.pid(), execution.image().executable());
        requirePeer(captured, owner);
        requireExecution(captured.pid(), owner, execution);
        if (owner.parentPid() != parent.pid()) throw new IOException("Action owner has another original parent");
        requireMapping(captured.pid(), localPid, owner);
        requireUnchanged(originalParent, requireParent());
        requireUnchanged(owner, observer.observe(captured.pid(), execution.image().executable()));
        return new Owner(this, captured, localPid, owner);
    }

    /**
     * Finds exactly one live direct child matching a prepared child's local PID and birth before containment changes.
     * The caller must keep that child stopped; this method does not stop, move, resume or reap it.
     * @param owner identity returned by this authenticator
     * @param localPid untrusted namespace-local prepared child PID
     * @param startTicks untrusted prepared child birth in kernel clock ticks since boot
     * @param prepared trusted gate execution expected before the first exec
     * @return independently mapped host child identity
     * @throws NullPointerException if an input is null
     * @throws IllegalArgumentException if a process ID or birth is nonpositive, or {@code owner} belongs elsewhere
     * @throws IOException if no unique child matches or any retained identity or execution changes
     */
    Child authenticateChild(Owner owner, long localPid, long startTicks, Execution prepared) throws IOException
    {
        requireOwner(owner);
        requirePositive(localPid, "localPid");
        requirePositive(startTicks, "startTicks");
        Objects.requireNonNull(prepared, "prepared");
        requireOwnerLive(owner);
        List<Child> matches = new ArrayList<>();
        for (long hostPid : observer.children(owner.peer.pid()))
        {
            Observation child = observer.observe(hostPid, prepared.image().executable());
            boolean matchesLocalIdentity = child.namespacePids().getLast() == localPid && child.startTicks() == startTicks;
            if (matchesLocalIdentity)
            {
                requireChild(owner, hostPid, localPid, child);
                requireExecution(hostPid, child, prepared);
                requireUnchanged(child, observer.observe(hostPid, prepared.image().executable()));
                matches.add(new Child(this, owner, hostPid, localPid, child, prepared.image().executable()));
            }
        }
        if (matches.size() != 1) throw new IOException("Prepared child does not map to exactly one live direct child: " + matches.size());
        requireOwnerLive(owner);
        return matches.getFirst();
    }

    /**
     * Checks the retained child after exec while it remains held for hook installation.
     * Executable identity is resolved inside the child's root and compared with its actual proc executable inode.
     * @param owner retained action owner
     * @param child independently mapped prepared child
     * @param held trusted execution expected at the held executable initialization boundary
     * @throws NullPointerException if an input is null
     * @throws IllegalArgumentException if either retained identity belongs to another authenticator or the child belongs to another owner
     * @throws IOException if the child birth, parent, namespaces, credentials or held execution differs or changes
     */
    void requireHeld(Owner owner, Child child, Execution held) throws IOException
    {
        requireOwner(owner);
        Objects.requireNonNull(child, "child");
        Objects.requireNonNull(held, "held");
        if (child.authenticator != this || child.owner != owner)
            throw new IllegalArgumentException("Child belongs to another authenticator or owner");
        requireOwnerLive(owner);
        Observation observed = observer.observe(child.hostPid, held.image().executable());
        requireChild(owner, child.hostPid, child.localPid, observed);
        if (observed.startTicks() != child.observation.startTicks()) throw new IOException("Held child birth changed");
        requireExecution(child.hostPid, observed, held);
        requireUnchanged(observed, observer.observe(child.hostPid, held.image().executable()));
        requireOwnerLive(owner);
    }

    /**
     * Revalidates retained child birth, direct parent, namespaces and workload credentials across an approved exec.
     * The root manager separately authenticates each held executable before releasing it; this operation grants no
     * authority to resume or instrument a changed image. The gate pathname remains an immutable declared input.
     * @param owner original authenticated action owner
     * @param child independently authenticated child retained across exec
     * @throws NullPointerException if an input is null
     * @throws IllegalArgumentException if the child or owner belongs to another policy or original owner
     * @throws IOException if birth, parentage, namespaces or workload credentials differ or cannot be observed
     */
    void requireChildIdentity(Owner owner, Child child) throws IOException
    {
        requireOwner(owner);
        Objects.requireNonNull(child, "child");
        if (child.authenticator != this || child.owner != owner)
            throw new IllegalArgumentException("Child belongs to another authenticator or owner");
        requireOwnerLive(owner);
        for (int read = 0; read < 2; read++)
        {
            Observation observed = observer.observe(child.hostPid, child.preparedExecutable);
            requireChild(owner, child.hostPid, child.localPid, observed);
            if (observed.startTicks() != child.observation.startTicks()) throw new IOException("Retained child birth changed");
        }
        requireOwnerLive(owner);
    }

    /**
     * Captures an original nonleader thread while its exec syscall remains held.
     * @param owner original authenticated native parent
     * @param child current authenticated root process
     * @param localThreadPid namespace-local nonleader thread selected by the native exec-entry event
     * @param startTicks native exec-entry thread birth that must match independent observations
     * @return one-use authorization bound to this exact process and independently observed thread
     * @throws IllegalArgumentException if either identity value is nonpositive or the thread names the process leader
     * @throws IOException if current root identity, thread membership or stable native facts differ
     */
    ExecAuthorization prepareExec(Owner owner, Child child, long localThreadPid, long startTicks) throws IOException
    {
        requirePositive(localThreadPid, "localThreadPid");
        requirePositive(startTicks, "startTicks");
        requireChildIdentity(owner, child);
        if (localThreadPid == child.localPid) throw new IllegalArgumentException("Exec thread must be a nonleader");
        ThreadObservation thread = observer.observeThread(child.hostPid, localThreadPid, child.preparedExecutable);
        if (thread.processId() != child.hostPid || thread.hostPid() == child.hostPid)
            throw new IOException("Exec thread is outside the authenticated original process group");
        requireChild(owner, thread.hostPid(), localThreadPid, thread.observation());
        if (thread.observation().startTicks() != startTicks)
            throw new IOException("Native exec-entry birth differs from the independently observed original thread");
        ThreadObservation repeated = observer.observeThread(child.hostPid, localThreadPid, child.preparedExecutable);
        if (!thread.equals(repeated)) throw new IOException("Original exec thread changed during authentication");
        requireChildIdentity(owner, child);
        return new ExecAuthorization(this, owner, child, thread);
    }

    /**
     * Authenticates the held original process after its authorized nonleader exec.
     * @param owner retained original native parent
     * @param child current process receipt bound to the authorization
     * @param authorization independently captured original exec thread
     * @return new immutable receipt for the current process identity
     * @throws NullPointerException if {@code authorization} is null
     * @throws IllegalArgumentException if receipts belong to different authorities or processes
     * @throws IOException if authorization was consumed or native identity differs
     */
    Child authenticateExec(Owner owner, Child child, ExecAuthorization authorization) throws IOException
    {
        Objects.requireNonNull(authorization, "authorization");
        if (authorization.authenticator != this || authorization.owner != owner)
            throw new IllegalArgumentException("Exec authorization belongs to another authority");
        if (authorization.consumed) throw new IOException("Original exec authorization was already consumed");
        if (authorization.child != child)
            throw new IllegalArgumentException("Exec authorization belongs to another process receipt");
        authorization.consumed = true;
        requireOwnerLive(owner);
        Observation observed = observer.observe(child.hostPid, child.preparedExecutable);
        requireChild(owner, child.hostPid, child.localPid, observed);
        boolean originalLeaderBirth = observed.startTicks() == child.observation.startTicks();
        boolean formerThreadBirth = observed.startTicks() == authorization.thread.observation().startTicks();
        if (!originalLeaderBirth && !formerThreadBirth)
            throw new IOException("Held exec birth differs from both independently authenticated original identities");
        requireUnchanged(observed, observer.observe(child.hostPid, child.preparedExecutable));
        requireOwnerLive(owner);
        return new Child(this, owner, child.hostPid, child.localPid, observed, child.preparedExecutable);
    }

    /**
     * Exports exact retained host identities for the private host-to-reader sandbox registration operation.
     * These fields carry previously authenticated kernel observations, not action-supplied registration authority.
     * The reader independently revalidates births, parentage, all credentials, namespace inodes and NSpid mappings.
     * @param owner original authenticated kernel peer outside the attempt group
     * @param child retained authenticated prepared child inside the attempt group
     * @return immutable exact native observation fields
     * @throws IOException if current retained identity cannot be revalidated
     */
    Map<String, String> registrationFields(Owner owner, Child child) throws IOException
    {
        requireChildIdentity(owner, child);
        return Map.ofEntries(
            Map.entry("peer.pid", Long.toString(owner.peer.pid())),
            Map.entry("peer.birth", Long.toString(owner.peer.startTicks())),
            Map.entry("peer.local", Long.toString(owner.localPid)),
            Map.entry("peer.uid", Integer.toString(owner.peer.uid())),
            Map.entry("peer.gid", Integer.toString(owner.peer.gid())),
            Map.entry("root.birth", Long.toString(child.observation.startTicks())),
            Map.entry("root.local", Long.toString(child.localPid)),
            Map.entry("namespace.pid", child.observation.pidNamespace()),
            Map.entry("namespace.mount", child.observation.mountNamespace()));
    }

    /**
     * Rejects an identity issued by another authentication policy.
     * @param owner retained action owner
     * @throws NullPointerException if {@code owner} is null
     * @throws IllegalArgumentException if {@code owner} belongs elsewhere
     */
    private void requireOwner(Owner owner)
    {
        Objects.requireNonNull(owner, "owner");
        if (owner.authenticator != this) throw new IllegalArgumentException("Owner belongs to another authenticator");
    }

    /**
     * Checks the original parent and complete owner observation again before consuming child authority.
     * @param owner retained action owner
     * @throws IOException if retained identities differ or cannot be observed
     */
    private void requireOwnerLive(Owner owner) throws IOException
    {
        requireParent();
        requireUnchanged(owner.observation, observer.observe(owner.peer.pid(), execution.image().executable()));
    }

    /**
     * Revalidates the retained native namespace owner independently of an exited workload root.
     * Descendant membership, credentials and executable authorization remain separate caller obligations.
     * @param owner original independently authenticated native owner
     * @throws IllegalArgumentException if the owner belongs to another authenticator
     * @throws IOException if the original parent or namespace owner changed
     */
    void requireNamespaceOwner(Owner owner) throws IOException
    {
        requireOwner(owner);
        requireOwnerLive(owner);
    }

    /**
     * Checks the retained original parent birth and trusted executable image.
     * @return current original parent observation
     * @throws IOException if the retained parent is stale or has another executable
     */
    private Observation requireParent() throws IOException
    {
        Observation observed = observer.observe(parent.pid(), parent.image().executable());
        if (observed.startTicks() != parent.startTicks()) throw new IOException("Original parent birth changed");
        requireImage(observed, parent.image());
        return observed;
    }

    /**
     * Checks a live observation against connection credentials, including all four UID/GID fields.
     * @param captured kernel connection identity
     * @param observed independently read native facts
     * @throws IOException if birth or credentials differ
     */
    private static void requirePeer(LinuxBorrowedStreams.Peer captured, Observation observed) throws IOException
    {
        if (observed.startTicks() != captured.startTicks()) throw new IOException("Connection peer birth changed");
        if (!AllocationHooks.credentialsMatch(observed.status(), captured.uid(), captured.gid()))
            throw new IOException("Connection peer real/effective/saved/filesystem credentials differ");
    }

    /**
     * Checks a direct child against the retained owner and independently observed namespace hierarchy.
     * @param owner retained action owner
     * @param hostPid host child PID
     * @param localPid claimed namespace-local child PID
     * @param observed independently read child facts
     * @throws IOException if parentage, namespaces, PID mapping or credentials differ
     */
    private static void requireChild(Owner owner, long hostPid, long localPid, Observation observed) throws IOException
    {
        if (observed.parentPid() != owner.peer.pid()) throw new IOException("Prepared child has another direct parent");
        if (!observed.pidNamespace().equals(owner.observation.pidNamespace()) ||
            !observed.mountNamespace().equals(owner.observation.mountNamespace()))
            throw new IOException("Child PID or mount namespace differs from its retained owner");
        requireMapping(hostPid, localPid, observed);
        if (observed.namespacePids().size() != owner.observation.namespacePids().size())
            throw new IOException("Child PID namespace hierarchy depth differs from its retained owner");
        if (!AllocationHooks.credentialsMatch(observed.status(), owner.peer.uid(), owner.peer.gid()))
            throw new IOException("Child real/effective/saved/filesystem credentials differ from its retained owner");
    }

    /**
     * Checks both ends of the independently read PID namespace vector.
     * @param hostPid process ID visible through the controller's proc mount
     * @param localPid claimed innermost process ID
     * @param observed independent native observation
     * @throws IOException if either process ID differs
     */
    private static void requireMapping(long hostPid, long localPid, Observation observed) throws IOException
    {
        if (observed.namespacePids().getFirst() != hostPid || observed.namespacePids().getLast() != localPid)
            throw new IOException("Claimed process ID differs from independently observed NSpid mapping");
    }

    /**
     * Checks every trusted execution field against a current native observation.
     * @param hostPid observed process in the controller namespace
     * @param observed independent native observation
     * @param expected trusted execution policy
     * @throws IOException if executable, arguments, environment or directory differs
     */
    private void requireExecution(long hostPid, Observation observed, Execution expected) throws IOException
    {
        requireImage(observed, expected.image());
        if (!AllocationHooks.credentialsMatch(observed.status(), expected.uid(), expected.gid()))
            throw new IOException("Native credentials differ from trusted execution");
        if (!observed.arguments().equals(expected.arguments())) throw new IOException("Native argument vector differs from trusted execution");
        if (!observed.environment().equals(expected.environment())) throw new IOException("Native environment differs from trusted execution");
        if (!observer.sameDirectory(hostPid, expected.directory())) throw new IOException("Native working directory inode differs from trusted execution");
    }

    /**
     * Checks namespace-resolved executable inode identity and sealed image bytes.
     * @param observed independent native observation
     * @param expected trusted image policy
     * @throws IOException if executable inode or SHA-256 differs
     */
    private static void requireImage(Observation observed, Image expected) throws IOException
    {
        if (!observed.sameExecutable() || !observed.executableSha256().equals(expected.sha256()))
            throw new IOException("Native executable inode or SHA-256 differs from trusted image");
    }

    /**
     * Rejects a process that changed while its related facts were being collected.
     * @param before earlier complete observation
     * @param after later complete observation
     * @throws IOException if observations differ
     */
    private static void requireUnchanged(Observation before, Observation after) throws IOException
    {
        if (!before.equals(after)) throw new IOException("Native process observation changed during authentication");
    }

    /**
     * Rejects nonpositive kernel identities.
     * @param value process ID or birth tick count
     * @param name caller-visible parameter name
     * @throws IllegalArgumentException if {@code value} is nonpositive
     */
    private static void requirePositive(long value, String name)
    {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    /**
     * Trusted executable location in the workload namespace and sealed SHA-256 from immutable invocation inputs.
     * @param executable normalized absolute pathname as seen inside the observed process root
     * @param sha256 lowercase hexadecimal SHA-256 of the approved executable bytes
     */
    record Image(Path executable, String sha256)
    {
        /**
         * Validates the trusted namespace-local path and exact digest representation.
         * @param executable normalized absolute executable pathname
         * @param sha256 lowercase hexadecimal SHA-256 from the trusted invocation seal
         * @throws NullPointerException if an input is null
         * @throws IllegalArgumentException if the path or digest representation is invalid
         */
        Image
        {
            requireAbsolute(executable);
            Objects.requireNonNull(sha256, "sha256");
            if (sha256.length() != 64 || !sha256.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')))
                throw new IllegalArgumentException("Image SHA-256 must contain 64 lowercase hexadecimal digits");
        }
    }

    /**
     * Retained original parent identity supplied by the trusted invocation owner.
     * @param pid original parent PID in the controller namespace
     * @param startTicks retained kernel birth tick count since boot
     * @param image trusted original parent executable
     */
    record Parent(long pid, long startTicks, Image image)
    {
        /**
         * Validates the retained parent identity before observations.
         * @param pid original parent host PID
         * @param startTicks original parent kernel birth ticks
         * @param image trusted parent executable image
         * @throws NullPointerException if {@code image} is null
         * @throws IllegalArgumentException if PID or birth is nonpositive
         */
        Parent
        {
            requirePositive(pid, "pid");
            requirePositive(startTicks, "startTicks");
            Objects.requireNonNull(image, "image");
        }
    }

    /**
     * Exact approved execution, with paths interpreted in the observed process's filesystem namespace.
     * @param image trusted running executable
     * @param arguments complete native argv, including argv zero and empty arguments
     * @param environment complete native environment, including empty values
     * @param directory normalized absolute working directory in the process namespace
     * @param uid approved unprivileged real/effective/saved/filesystem user ID
     * @param gid approved real/effective/saved/filesystem group ID
     */
    record Execution(Image image, List<String> arguments, Map<String, String> environment, Path directory, int uid, int gid)
    {
        /**
         * Copies execution inputs and rejects native text that cannot be represented faithfully.
         * @param image trusted running executable
         * @param arguments complete native argument vector
         * @param environment complete native environment
         * @param directory namespace-local absolute working directory
         * @param uid approved positive unprivileged user ID
         * @param gid approved nonnegative group ID
         * @throws NullPointerException if an input or collection member is null
         * @throws IllegalArgumentException if arguments, credentials, native text or paths are invalid
         */
        Execution
        {
            Objects.requireNonNull(image, "image");
            if (uid <= 0 || gid < 0) throw new IllegalArgumentException("Execution requires an unprivileged UID and nonnegative GID");
            arguments = List.copyOf(arguments);
            if (arguments.isEmpty()) throw new IllegalArgumentException("Native argument vector must contain argv zero");
            arguments.forEach(LinuxBazelIdentity::requireText);
            environment = Map.copyOf(environment);
            environment.forEach((name, value) ->
            {
                requireText(name);
                requireText(value);
                if (name.isEmpty() || name.indexOf('=') >= 0) throw new IllegalArgumentException("Environment name is empty or contains '='");
            });
            requireAbsolute(directory);
        }
    }

    /**
     * Authentication receipt whose constructor is inaccessible to registration callers.
     */
    static final class Owner
    {
        /** Policy that issued this receipt. */
        private final LinuxBazelIdentity authenticator;
        /** Kernel-captured connection identity. */
        private final LinuxBorrowedStreams.Peer peer;
        /** Independently verified innermost process ID. */
        private final long localPid;
        /** Complete observed owner state retained for subsequent checks. */
        private final Observation observation;

        /**
         * Retains a successfully authenticated owner.
         * @param authenticator issuing policy
         * @param peer kernel-captured connection identity
         * @param localPid independently verified namespace-local PID
         * @param observation verified owner state
         */
        private Owner(LinuxBazelIdentity authenticator, LinuxBorrowedStreams.Peer peer, long localPid, Observation observation)
        {
            this.authenticator = authenticator;
            this.peer = peer;
            this.localPid = localPid;
            this.observation = observation;
        }

        /**
         * Returns the authenticated connection identity in the controller namespace.
         * @return kernel-captured owner birth and credentials
         */
        LinuxBorrowedStreams.Peer peer() { return peer; }

        /**
         * Returns the independently verified owner PID inside its namespace.
         * @return positive innermost namespace PID
         */
        long localPid() { return localPid; }
    }

    /**
     * Independently observed original thread, with the host group membership read from that task's proc status.
     * @param hostPid controller-visible thread ID
     * @param processId controller-visible thread-group ID
     * @param observation stable thread birth, credentials and namespace mapping
     */
    record ThreadObservation(long hostPid, long processId, Observation observation)
    {
        /**
         * Validates native identity values before retaining them.
         * @param hostPid positive controller-visible thread ID
         * @param processId positive controller-visible group ID
         * @param observation nonnull stable original thread observation
         * @throws IllegalArgumentException if either ID is nonpositive
         * @throws NullPointerException if observation is null
         */
        ThreadObservation
        {
            requirePositive(hostPid, "hostPid");
            requirePositive(processId, "processId");
            Objects.requireNonNull(observation, "observation");
        }
    }

    /**
     * One-use authority to recognize the birth change caused by one independently observed original thread exec.
     * Only the issuing operation thread may consume it.
     */
    static final class ExecAuthorization
    {
        /** Issuing policy, checked when this capability is consumed. */
        private final LinuxBazelIdentity authenticator;
        /** Original native parent whose wait authority remains unchanged. */
        private final Owner owner;
        /** Exact current immutable process receipt before exec. */
        private final Child child;
        /** Independently observed original thread and group identity. */
        private final ThreadObservation thread;
        /** Indicates whether the sole migration authentication has been attempted. */
        private boolean consumed;

        /**
         * Retains an independently authenticated original thread; only the issuing policy constructs this capability.
         * @param authenticator issuing policy
         * @param owner original native parent
         * @param child exact process before exec
         * @param thread stable independently observed original thread
         * @throws NullPointerException if any input is null
         */
        private ExecAuthorization(LinuxBazelIdentity authenticator, Owner owner, Child child, ThreadObservation thread)
        {
            this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
            this.owner = Objects.requireNonNull(owner, "owner");
            this.child = Objects.requireNonNull(child, "child");
            this.thread = Objects.requireNonNull(thread, "thread");
        }
    }

    /**
     * Mapped direct-child receipt, retained across its gate-to-workload exec transition.
     */
    static final class Child
    {
        /** Issuing authentication policy. */
        private final LinuxBazelIdentity authenticator;
        /** Original authenticated action owner whose direct child this receipt names. */
        private final Owner owner;
        /** Independently mapped controller-visible child PID. */
        private final long hostPid;
        /** Independently verified innermost child PID. */
        private final long localPid;
        /** Authenticated process state captured when this immutable receipt was issued. */
        private final Observation observation;
        /** Immutable declared gate path retained for independent proc reads after workload exec. */
        private final Path preparedExecutable;

        /**
         * Retains an independently authenticated direct child.
         * @param authenticator issuing policy
         * @param owner original authenticated parent of this child
         * @param hostPid independently mapped controller PID
         * @param localPid namespace-local PID
         * @param observation verified current process state
         * @param preparedExecutable retained declared gate pathname in the child namespace
         */
        private Child(LinuxBazelIdentity authenticator, Owner owner, long hostPid, long localPid, Observation observation,
            Path preparedExecutable)
        {
            this.authenticator = authenticator;
            this.owner = owner;
            this.hostPid = hostPid;
            this.localPid = localPid;
            this.observation = observation;
            this.preparedExecutable = preparedExecutable;
        }

        /**
         * Returns the mapped child PID for host containment and instrumentation.
         * @return positive controller-visible child PID
         */
        long hostPid() { return hostPid; }

        /**
         * Returns the kernel birth captured by this immutable receipt.
         * @return positive kernel clock tick count since boot
         */
        long startTicks() { return observation.startTicks(); }
    }

    /**
     * Complete independent facts for one process, stable within a native read.
     * @param startTicks kernel process birth ticks since boot
     * @param parentPid current direct parent in the controller namespace
     * @param status complete proc status used to check all UID/GID entries
     * @param namespacePids outermost through innermost independently read NSpid entries
     * @param pidNamespace kernel PID namespace inode link text
     * @param mountNamespace kernel mount namespace inode link text
     * @param sameExecutable indicates whether actual exe and expected process-root executable share an inode
     * @param executableSha256 SHA-256 of the actual proc executable bytes
     * @param arguments complete observed native argv
     * @param environment complete observed native environment
     * @param directory observed namespace-local absolute cwd link
     */
    record Observation(long startTicks, long parentPid, String status, List<Long> namespacePids,
        String pidNamespace, String mountNamespace, boolean sameExecutable, String executableSha256,
        List<String> arguments, Map<String, String> environment, Path directory)
    {
        /**
         * Copies observation collections and rejects incomplete facts.
         * @param startTicks positive observed kernel birth ticks
         * @param parentPid observed direct parent PID, possibly zero for a namespace init
         * @param status complete proc status
         * @param namespacePids positive PID namespace vector
         * @param pidNamespace nonempty PID namespace inode link text
         * @param mountNamespace nonempty mount namespace inode link text
         * @param sameExecutable indicates whether the actual and expected process-root image share an inode
         * @param executableSha256 actual executable SHA-256
         * @param arguments complete observed argv
         * @param environment complete observed environment
         * @param directory namespace-local absolute observed cwd
         * @throws NullPointerException if an input or collection member is null
         * @throws IllegalArgumentException if identity, namespaces or paths are incomplete
         */
        Observation
        {
            requirePositive(startTicks, "startTicks");
            if (parentPid < 0) throw new IllegalArgumentException("parentPid must be nonnegative");
            Objects.requireNonNull(status, "status");
            namespacePids = List.copyOf(namespacePids);
            if (namespacePids.isEmpty()) throw new IllegalArgumentException("NSpid must contain a process ID");
            namespacePids.forEach(pid -> requirePositive(pid, "namespacePid"));
            if (Objects.requireNonNull(pidNamespace, "pidNamespace").isEmpty() ||
                Objects.requireNonNull(mountNamespace, "mountNamespace").isEmpty())
                throw new IllegalArgumentException("Namespace inode link text must be nonempty");
            Objects.requireNonNull(executableSha256, "executableSha256");
            arguments = List.copyOf(arguments);
            environment = Map.copyOf(environment);
            requireAbsolute(directory);
        }
    }

    /**
     * Supplies kernel and proc facts independently of the untrusted connection payload.
     */
    interface Observer
    {
        /**
         * Captures one process's complete facts and verifies stable birth across that collection.
         * @param pid controller-visible process ID
         * @param executable approved absolute executable pathname inside that process's root
         * @return independent native facts, including root-relative executable inode comparison
         * @throws IOException if any required fact is unavailable, malformed or changing
         */
        Observation observe(long pid, Path executable) throws IOException;

        /**
         * Observes one namespace-local nonleader under the authenticated host process's task directory.
         * @param hostPid authenticated controller-visible process-group ID
         * @param localThreadPid expected innermost thread ID
         * @param executable original declared gate used for independent proc observation
         * @return stable thread identity with independently read host group membership
         * @throws IOException if task membership, mapping or native facts cannot be observed
         */
        default ThreadObservation observeThread(long hostPid, long localThreadPid, Path executable) throws IOException
        { throw new IOException("Independent original thread observation is unavailable"); }

        /**
         * Enumerates the owner's current direct children in controller-visible PID numbers.
         * @param pid authenticated action owner PID
         * @return complete direct-child PID vector
         * @throws IOException if direct-child enumeration is unavailable or malformed
         */
        List<Long> children(long pid) throws IOException;

        /**
         * Checks the actual cwd inode against a directory resolved inside the observed process root.
         * @param pid controller-visible process ID
         * @param directory approved absolute namespace-local working directory
         * @return true if both paths refer to the same directory inode
         * @throws IOException if either directory cannot be observed
         */
        boolean sameDirectory(long pid, Path directory) throws IOException;
    }

    /** Identifies an actually empty native procfs command vector, independently of other decoder failures. */
    static final class EmptyCommandVectorException extends IOException
    {
        /** Stable serialization identity of this native observation distinction. */
        private static final long serialVersionUID = 1L;

        /**
         * Retains the exact absent native vector observation.
         * @param pid observed controller-visible process
         */
        private EmptyCommandVectorException(long pid)
        {
            super("Native command vector is empty for /proc/" + pid + "/cmdline");
        }
    }

    /**
     * Reads the existing bounded native argument format without translating other decoder failures.
     * @param pid independently selected controller-visible process
     * @param input borrowed original procfs command stream; caller retains closure
     * @return unchanged native argument vector
     * @throws IOException if the native vector is empty, malformed or unavailable
     */
    static List<String> readNativeArguments(long pid, InputStream input) throws IOException
    {
        java.io.PushbackInputStream observed = new java.io.PushbackInputStream(input, 1);
        int first = observed.read();
        if (first == -1) throw new EmptyCommandVectorException(pid);
        observed.unread(first);
        return LinuxCargoRegistration.SharedSecrets.arguments(observed);
    }

    /**
     * Reads Linux proc through the controller's existing proc mount using JDK filesystem APIs.
     */
    private static final class NativeObserver implements Observer
    {
        /**
         * Creates a stateless observer without acquiring resources.
         */
        private NativeObserver() {}

        @Override
        public Observation observe(long pid, Path executable) throws IOException
        {
            Path process = Path.of("/proc", Long.toString(pid));
            String stat = Files.readString(process.resolve("stat"));
            long birth = AllocationHooks.startTime(stat);
            String status = Files.readString(process.resolve("status"));
            long parentPid = parentPid(stat);
            List<Long> namespacePids = namespacePids(status);
            String pidNamespace = Files.readSymbolicLink(process.resolve("ns/pid")).toString();
            String mountNamespace = Files.readSymbolicLink(process.resolve("ns/mnt")).toString();
            Path rootedExecutable = process.resolve("root").resolve(executable.getRoot().relativize(executable));
            boolean sameExecutable = Files.isSameFile(process.resolve("exe"), rootedExecutable);
            String hash = sha256(process.resolve("exe"));
            List<String> arguments;
            try (InputStream input = Files.newInputStream(process.resolve("cmdline")))
            {
                arguments = readNativeArguments(pid, input);
            }
            Map<String, String> environment;
            try (InputStream input = Files.newInputStream(process.resolve("environ")))
            {
                environment = LinuxCargoRegistration.SharedSecrets.environment(input);
            }
            Path directory = Files.readSymbolicLink(process.resolve("cwd"));
            if (birth != AllocationHooks.startTime(Files.readString(process.resolve("stat"))))
                throw new IOException("Process birth changed during native observation");
            String credentials = String.join("\n", status.lines().filter(line -> line.startsWith("Uid:") || line.startsWith("Gid:")).toList());
            return new Observation(birth, parentPid, credentials, namespacePids, pidNamespace, mountNamespace,
                sameExecutable, hash, arguments, environment, directory);
        }

        @Override public ThreadObservation observeThread(long hostPid, long localThreadPid, Path executable)
            throws IOException
        {
            Path tasks = Path.of("/proc", Long.toString(hostPid), "task");
            ThreadObservation result = null;
            try (java.nio.file.DirectoryStream<Path> directories = Files.newDirectoryStream(tasks))
            {
                for (Path task : directories)
                {
                    String status = Files.readString(task.resolve("status"));
                    List<Long> mapping = namespacePids(status);
                    if (mapping.getLast() != localThreadPid) continue;
                    String group = status.lines().filter(line -> line.startsWith("Tgid:")).findFirst()
                        .orElseThrow(() -> new IOException("Original exec task group identity is absent"));
                    long processId;
                    try { processId = Long.parseLong(group.substring(5).strip()); }
                    catch (NumberFormatException cause) { throw new IOException("Original exec task group is invalid", cause); }
                    long threadPid = mapping.getFirst();
                    Observation observation = observe(threadPid, executable);
                    String after = Files.readString(task.resolve("status"));
                    if (!observation.namespacePids().equals(mapping) || !mapping.equals(namespacePids(after)) ||
                        !sameGroupAndCredentials(status, after))
                        throw new IOException("Original exec task identity changed during observation");
                    if (result != null) throw new IOException("Original exec thread has multiple host task mappings");
                    result = new ThreadObservation(threadPid, processId, observation);
                }
            }
            if (result == null) throw new IOException("Original exec thread is absent from its authenticated process group");
            return result;
        }

        /**
         * Indicates whether repeated task status retains group identity and all native credentials.
         * @param before original task status
         * @param after repeated task status
         * @return true when group and credential records match exactly
         */
        private static boolean sameGroupAndCredentials(String before, String after)
        {
            List<String> original = before.lines().filter(line -> line.startsWith("Tgid:") ||
                line.startsWith("Uid:") || line.startsWith("Gid:")).toList();
            List<String> repeated = after.lines().filter(line -> line.startsWith("Tgid:") ||
                line.startsWith("Uid:") || line.startsWith("Gid:")).toList();
            return original.equals(repeated);
        }

        @Override
        public List<Long> children(long pid) throws IOException
        {
            Path children = Path.of("/proc", Long.toString(pid), "task", Long.toString(pid), "children");
            return positiveVector(Files.readString(children));
        }

        @Override
        public boolean sameDirectory(long pid, Path directory) throws IOException
        {
            Path process = Path.of("/proc", Long.toString(pid));
            Path rootedDirectory = process.resolve("root").resolve(directory.getRoot().relativize(directory));
            return Files.isSameFile(process.resolve("cwd"), rootedDirectory);
        }
    }

    /**
     * Reads the direct parent from the same proc stat record that supplies birth identity.
     * @param stat complete kernel stat record
     * @return nonnegative parent PID
     * @throws IOException if the parent field is malformed
     */
    private static long parentPid(String stat) throws IOException
    {
        try
        {
            long parentPid = Long.parseLong(stat.substring(stat.lastIndexOf(')') + 1).strip().split("\\s+")[1]);
            if (parentPid < 0) throw new NumberFormatException("negative parent");
            return parentPid;
        }
        catch (IndexOutOfBoundsException | NumberFormatException cause)
        {
            throw new IOException("Native parent PID is malformed", cause);
        }
    }

    /**
     * Reads the independently supplied process hierarchy from Linux proc status.
     * @param status complete proc status text
     * @return nonempty positive NSpid vector
     * @throws IOException if NSpid is missing, duplicated, malformed or empty
     */
    private static List<Long> namespacePids(String status) throws IOException
    {
        List<Long> result = null;
        for (String line : status.lines().toList())
        {
            if (line.startsWith("NSpid:"))
            {
                if (result != null) throw new IOException("Native status repeats NSpid");
                result = positiveVector(line.substring(6));
            }
        }
        if (result == null || result.isEmpty()) throw new IOException("Native status lacks a nonempty NSpid vector");
        return result;
    }

    /**
     * Reads whitespace-delimited positive kernel process IDs without accepting malformed entries.
     * @param text complete kernel PID vector
     * @return immutable vector, empty when no child exists
     * @throws IOException if a process ID is malformed or nonpositive
     */
    private static List<Long> positiveVector(String text) throws IOException
    {
        if (text.isBlank()) return List.of();
        List<Long> result = new ArrayList<>();
        try
        {
            for (String value : text.strip().split("\\s+"))
            {
                long pid = Long.parseLong(value);
                if (pid <= 0) throw new NumberFormatException("nonpositive process ID");
                result.add(pid);
            }
        }
        catch (NumberFormatException cause) { throw new IOException("Native PID vector is malformed", cause); }
        return List.copyOf(result);
    }

    /**
     * Hashes the actual executable file using the JDK SHA-256 implementation.
     * @param executable actual proc executable link
     * @return lowercase SHA-256 hex
     * @throws IOException if executable bytes cannot be read
     */
    private static String sha256(Path executable) throws IOException
    {
        try (InputStream input = Files.newInputStream(executable))
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException cause)
        {
            throw new IOException("Required JDK SHA-256 implementation is unavailable", cause);
        }
    }

    /**
     * Checks an absolute normalized namespace-local pathname.
     * @param path required namespace-local path
     * @throws NullPointerException if {@code path} is null
     * @throws IllegalArgumentException if the path is relative, unnormalized or contains NUL
     */
    private static void requireAbsolute(Path path)
    {
        Objects.requireNonNull(path, "path");
        if (!path.isAbsolute() || !path.normalize().equals(path))
            throw new IllegalArgumentException("Namespace-local path must be normalized and absolute");
        requireText(path.toString());
    }

    /**
     * Rejects native text that cannot be represented in a NUL-terminated vector.
     * @param text complete native text
     * @throws IllegalArgumentException if {@code text} contains NUL
     */
    private static void requireText(String text)
    {
        if (text.indexOf('\0') >= 0) throw new IllegalArgumentException("Native text contains NUL");
    }
}
