package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Owns an independent ready-input snapshot and transactional output publication into the worker's pinned root object.
 * One coordination thread owns this workspace. The producer authenticates all request roots and prevents concurrent
 * writers, freezes inputs before acquisition, and proves native whole-attempt zero plus helper closure before freezing
 * or discarding an attempt. After native unmapping, only a frozen private stage is used for publication.
 * This class creates no mounts or processes and cannot prove those external barriers itself.
 * Original worker input and temporary directories are borrowed and never removed. Closing removes only the fresh
 * owned tree after every attempt has been discarded or promoted; uncertain rollback retains that tree and fails close.
 */
public final class LinuxBazelWorkspace implements AutoCloseable
{
    /** Immutable independent input directory under the invocation-owned workspace. */
    private static final String SNAPSHOT = "lower";
    /** Prefix of private attempt directories; queue attempt ordinals make each name unique. */
    private static final String ATTEMPT_PREFIX = "attempt-";
    /** Borrowed original worker-visible input root pathname used to verify continued attachment. */
    private final Path originalInputRoot;
    /** Borrowed original temporary root pathname used to verify continued attachment before launch. */
    private final Path originalTmp;
    /** Fresh independent tree owned only by this workspace. */
    private final Path ownedRoot;
    /** Complete independent ready inputs, retained even if the worker removes its original root. */
    private final Path immutableSnapshot;
    /** Opened original input object, used for all publication changes rather than swapping its pathname. */
    private final SecureDirectoryStream<Path> input;
    /** Pinned original temporary object, retained until workspace closure. */
    private final SecureDirectoryStream<Path> temporary;
    /** Original input directory object's filesystem identity. */
    private final Object inputKey;
    /** Original temporary directory object's filesystem identity. */
    private final Object temporaryKey;
    /** Reserved attempt ordinals, preventing retry reuse of old upper or journal contents. */
    private final Set<Long> sequences = new HashSet<>();
    /** Current attempt, including a recovery lease until publication or rollback is proven. */
    private Attempt active;
    /** Indicates whether this owner has closed both pinned directory descriptors. */
    private boolean closed;

    /**
     * Retains already acquired roots after the factory has completed snapshot creation.
     * @param originalInputRoot original worker-visible root
     * @param originalTmp original worker-visible temporary directory
     * @param ownedRoot newly created workspace-owned tree
     * @param input acquired original input descriptor transferred on successful construction
     * @param temporary acquired original temporary descriptor transferred on successful construction
     * @throws IOException if descriptor identity is unavailable
     */
    private LinuxBazelWorkspace(Path originalInputRoot, Path originalTmp, Path ownedRoot,
        SecureDirectoryStream<Path> input, SecureDirectoryStream<Path> temporary) throws IOException
    {
        this.originalInputRoot = originalInputRoot;
        this.originalTmp = originalTmp;
        this.ownedRoot = ownedRoot;
        this.immutableSnapshot = ownedRoot.resolve(SNAPSHOT);
        this.input = input;
        this.temporary = temporary;
        this.inputKey = directoryKey(input);
        this.temporaryKey = directoryKey(temporary);
    }

    /**
     * Pins no-follow original directory objects and copies complete ready inputs into a fresh independent owned tree.
     * The owned path must be absent, have an existing trusted parent, lie outside the original input/temporary trees,
     * and share the input filesystem so journal moves are atomic. The trusted fixed worker guarantees that regular
     * inputs with no write permission bits remain immutable: only those inputs may share an inode with the snapshot.
     * Writable inputs are copied independently. Every directory entry is independent, so worker path removal or
     * replacement cannot remove the snapshot; neither caller nor native bridge may chmod/chown shared input inodes.
     * Snapshot entries retain literal symlink text and POSIX modes.
     * @param originalInputRoot absolute normalized authenticated worker input directory
     * @param originalTmp absolute normalized authenticated worker temporary directory
     * @param ownedRoot absolute normalized absent workspace path under a trusted owner directory
     * @return owner of the new tree and pinned original descriptors
     * @throws NullPointerException if any input is null
     * @throws IllegalArgumentException if paths are relative, unnormalized or owned/input/temp trees overlap
     * @throws IOException if roots contain symlink ancestors, are replaced, copy fails or atomic storage is unavailable
     * @throws UnsupportedOperationException if secure directory streams or stable file keys are unavailable
     */
    public static LinuxBazelWorkspace open(Path originalInputRoot, Path originalTmp, Path ownedRoot) throws IOException
    {
        absolute(originalInputRoot);
        absolute(originalTmp);
        absolute(ownedRoot);
        if (overlaps(ownedRoot, originalInputRoot) || overlaps(ownedRoot, originalTmp) ||
            overlaps(originalInputRoot, originalTmp))
            throw new IllegalArgumentException("Workspace, worker input and worker temporary trees must be disjoint");
        try (SecureDirectoryStream<Path> parent = openDirectory(ownedRoot.getParent()))
        {
            Objects.requireNonNull(parent);
            privateDirectory(ownedRoot);
        }
        SecureDirectoryStream<Path> input = null;
        SecureDirectoryStream<Path> temporary = null;
        try
        {
            input = openDirectory(originalInputRoot);
            temporary = openDirectory(originalTmp);
            if (!Files.getFileStore(originalInputRoot).equals(Files.getFileStore(ownedRoot)))
                throw new IOException("Workspace staging must share the worker input filesystem for atomic publication");
            LinuxBazelWorkspace workspace = new LinuxBazelWorkspace(originalInputRoot, originalTmp, ownedRoot,
                input, temporary);
            copyTree(originalInputRoot, workspace.immutableSnapshot, true);
            workspace.requireAttached();
            return workspace;
        }
        catch (IOException | RuntimeException | Error failure)
        {
            if (temporary != null)
                try { temporary.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            if (input != null)
                try { input.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            try { remove(ownedRoot); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /**
     * Returns complete independent ready inputs for identity calculation and the read-only native lower mount.
     * The producer must prevent action access that could mutate this owner-only storage.
     * @return independent immutable snapshot path, retained until successful close
     */
    public Path immutableSnapshot() { return immutableSnapshot; }

    /**
     * Creates administrative storage with immutable host-owned ancestors regardless of the process umask.
     * Action-visible input modes are copied independently and are preserved unchanged.
     * @param directory absent host-owned directory
     * @return the newly created private directory
     * @throws IOException if creation or explicit private permissions fail
     */
    private static Path privateDirectory(Path directory) throws IOException
    {
        return Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwx------")));
    }

    /**
     * Creates empty private overlay upper/work and temporary directories for one new native attempt.
     * Retry ordinals never reuse a previous attempt's directory. At most one live or unresolved attempt is permitted.
     * @param sequence nonnegative queue-wide native attempt ordinal
     * @return new attempt storage lease
     * @throws IllegalArgumentException if {@code sequence} is negative or already reserved
     * @throws IllegalStateException if closed or the previous attempt still owns storage
     * @throws IOException if the original roots are detached/replaced or private directories cannot be created
     */
    public Attempt begin(long sequence) throws IOException
    {
        if (closed || active != null) throw new IllegalStateException("Workspace is closed or has an outstanding attempt lease");
        if (sequence < 0 || !sequences.add(sequence))
            throw new IllegalArgumentException("Attempt sequence must be nonnegative and reserved only once");
        requireAttached();
        Path root = privateDirectory(ownedRoot.resolve(ATTEMPT_PREFIX + sequence));
        try
        {
            Attempt attempt = new Attempt(root);
            this.active = attempt;
            return attempt;
        }
        catch (IOException | RuntimeException | Error failure)
        {
            try { remove(root); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /**
     * Verifies that the originally pinned roots still occupy their original no-follow absolute paths.
     * @throws IOException if either object was removed, replaced or reached through a symlink ancestor
     */
    private void requireAttached() throws IOException
    {
        try (SecureDirectoryStream<Path> current = openDirectory(originalInputRoot);
            SecureDirectoryStream<Path> currentTmp = openDirectory(originalTmp))
        {
            if (!inputKey.equals(directoryKey(current)) || !temporaryKey.equals(directoryKey(currentTmp)))
                throw new IOException("Worker input or temporary root was replaced");
        }
    }

    @Override public void close() throws IOException
    {
        if (closed) return;
        if (active != null)
            throw new IOException("Workspace still owns an attempt lease: " + active.state);
        IOException failure = null;
        try { input.close(); } catch (IOException closeFailure) { failure = closeFailure; }
        try { temporary.close(); }
        catch (IOException closeFailure)
        {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        this.closed = true;
        if (failure != null) throw failure;
        remove(ownedRoot);
    }

    /**
     * Owns one attempt's private upper/work/tmp and publication journal until zero/unmap is proven externally.
     * The producer calls freeze after successful native whole-group zero and helper closure, then unmaps its native
     * view, obtains the authentic closed final outcome, and calls promote while its publication lease remains valid.
     * Failure and cancellation call discard only after native zero and unmapping; neither operation asserts those facts.
     */
    public final class Attempt
    {
        /** Private root reserved for this attempt alone. */
        private final Path root;
        /** Writable overlay upper supplied to the native layer. */
        private final Path upper;
        /** Empty overlay work directory on the upper filesystem. */
        private final Path work;
        /** Private temporary directory mounted at the action's original temporary path. */
        private final Path attemptTmp;
        /** Frozen output data independent of the native mount after unmapping. */
        private final Path stage;
        /** Original entries moved here while publication remains incomplete. */
        private final Path backup;
        /** Host-readable merged native view, provided only by the trusted producer. */
        private Path merged;
        /** Caller-authenticated supervisor proc-root capability; null for an ordinary no-follow merged path. */
        private Path authenticatedProcRoot;
        /** Pinned merged directory object's identity at bind, checked again before and after freezing. */
        private Object mergedKey;
        /** Complete staged changes and per-change rollback progress. */
        private final List<Change> changes = new ArrayList<>();
        /** Current storage/publication lifecycle; recovery-required retains all cleanup authority. */
        private State state = State.PREPARED;

        /**
         * Creates private empty attempt directories inside the newly allocated attempt root.
         * @param root fresh owned attempt root
         * @throws IOException if a private directory cannot be created
         */
        private Attempt(Path root) throws IOException
        {
            this.root = root;
            this.upper = privateDirectory(root.resolve("upper"));
            this.work = privateDirectory(root.resolve("work"));
            this.attemptTmp = privateDirectory(root.resolve("temporary"));
            this.stage = privateDirectory(root.resolve("stage"));
            this.backup = privateDirectory(root.resolve("backup"));
        }

        /**
         * Returns the complete ready-input lower supplied to a read-only native overlay mount.
         * @return independent immutable lower tree
         */
        Path lower() { return immutableSnapshot; }

        /**
         * Returns private writable overlay storage, excluded from the worker's original input root.
         * @return fresh attempt upper directory
         */
        public Path upper() { return upper; }

        /**
         * Returns the private empty native overlay work directory.
         * @return work directory on the upper filesystem
         */
        Path work() { return work; }

        /**
         * Returns private temporary storage to mount at the original action temporary path.
         * @return private attempt temporary directory
         */
        Path temporary() { return attemptTmp; }

        /**
         * Returns the current storage lifecycle without asserting native process or mount state.
         * @return lease/publication state
         */
        State state() { return state; }

        /**
         * Supplies the trusted host-readable complete merged view without rewriting any action path.
         * The producer retains that view until freeze completes and excludes concurrent action writers.
         * @param merged absolute normalized host-readable mounted merged directory
         * @throws IllegalStateException if the attempt is no longer prepared or was already bound
         * @throws NullPointerException if {@code merged} is null
         * @throws IllegalArgumentException if {@code merged} is relative or unnormalized
         * @throws IOException if the merged root is not a real no-follow directory
         */
        public void bindMerged(Path merged) throws IOException
        {
            absolute(merged);
            if (state != State.PREPARED || this.merged != null)
                throw new IllegalStateException("Merged view may be bound once before freezing");
            try (SecureDirectoryStream<Path> view = openDirectory(merged)) { this.mergedKey = directoryKey(view); }
            this.merged = merged;
        }

        /**
         * Binds a merged tree beneath one independently authenticated live supervisor's proc-root capability.
         * The trusted caller verifies supervisor PID birth, image, namespaces and containment before this call and
         * keeps that same supervisor alive until freeze returns. Only the exact proc-root magic link is followed;
         * every component beneath it remains no-follow. This method does not authenticate a process from its pathname.
         * @param merged absolute normalized host-readable merged path strictly beneath the supplied proc root
         * @param authenticatedProcRoot exact absolute /proc/PID/root of the caller-authenticated live supervisor
         * @throws NullPointerException if either path is null
         * @throws IllegalArgumentException if paths are relative/unnormalized or the capability path has another form
         * @throws IllegalStateException if a view is already bound or the attempt is no longer prepared
         * @throws IOException if the capability or remaining no-follow directories cannot be opened
         */
        public void bindMerged(Path merged, Path authenticatedProcRoot) throws IOException
        {
            absolute(merged);
            absolute(authenticatedProcRoot);
            if (state != State.PREPARED || this.merged != null)
                throw new IllegalStateException("Merged view may be bound once before freezing");
            boolean exactProcRoot = authenticatedProcRoot.getNameCount() == 3 &&
                authenticatedProcRoot.getName(0).toString().equals("proc") &&
                authenticatedProcRoot.getFileName().toString().equals("root") &&
                authenticatedProcRoot.getName(1).toString().matches("[1-9][0-9]*");
            if (!exactProcRoot || !merged.startsWith(authenticatedProcRoot) || merged.equals(authenticatedProcRoot))
                throw new IllegalArgumentException("Merged capability must be beneath one authenticated /proc/PID/root");
            try (SecureDirectoryStream<Path> view = openMerged(merged, authenticatedProcRoot))
            {
                this.mergedKey = directoryKey(view);
            }
            this.authenticatedProcRoot = authenticatedProcRoot;
            this.merged = merged;
        }

        /**
         * Stages the complete changed output set privately after producer-proven native zero/helper closure.
         * Original worker entries remain unchanged. The stage survives subsequent native unmapping. Unsupported file
         * types or escaping output symlinks reject the entire freeze; no partial stage is eligible for publication.
         * @throws IllegalStateException if no merged view is bound or the attempt is not prepared
         * @throws IOException if a stable complete supported merged view cannot be staged
         */
        public void freeze() throws IOException
        {
            if (state != State.PREPARED || merged == null)
                throw new IllegalStateException("Freezing requires one prepared bound merged view");
            try
            {
                requireAttached();
                requireMerged();
                plan(immutableSnapshot, merged, Path.of(""));
                for (int index = 0; index < changes.size(); index++)
                {
                    Change change = changes.get(index);
                    change.slot = Path.of(Integer.toString(index));
                    if (change.replacement != null) copyTree(change.replacement, stage.resolve(change.slot));
                }
                requireMerged();
                this.state = State.FROZEN;
            }
            catch (IOException | RuntimeException failure)
            {
                this.state = State.REJECTED;
                throw failure;
            }
        }

        /**
         * Publishes the frozen successful attempt into the same pinned worker directory object.
         * A direct caller may omit freeze only while its merged view is still available. The publication supplier must
         * remain true through commit; it represents the producer's uncancelled authentic-success lease. Every failure
         * after a change begins rolls the journal back before returning an error. Uncertain rollback retains storage in
         * RECOVERY_REQUIRED and the whole invocation must fail without claiming clean output publication.
         * @param publicationAllowed producer-owned authentic success and uncancelled publication lease
         * @throws NullPointerException if {@code publicationAllowed} is null
         * @throws IllegalStateException if the attempt is neither prepared nor frozen
         * @throws IOException if cancelled, root identity changes, publication fails or rollback cannot be proven
         */
        public void promote(BooleanSupplier publicationAllowed) throws IOException
        {
            Objects.requireNonNull(publicationAllowed, "publicationAllowed");
            if (state == State.PREPARED) freeze();
            if (state != State.FROZEN) throw new IllegalStateException("Publication requires a frozen successful attempt");
            requirePublication(publicationAllowed);
            this.state = State.PROMOTING;
            try (SecureDirectoryStream<Path> staged = openDirectory(stage);
                SecureDirectoryStream<Path> saved = openDirectory(backup))
            {
                try
                {
                    for (Change change : changes)
                    {
                        requirePublication(publicationAllowed);
                        try (SecureDirectoryStream<Path> parent = parent(change.relative))
                        {
                            Path name = change.relative.getFileName();
                            if (change.modeOnly)
                            {
                                parent.getFileAttributeView(name, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                                    .setPermissions(change.newPermissions);
                                change.modeApplied = true;
                            }
                            else
                            {
                                if (change.existed)
                                {
                                    parent.move(name, saved, change.slot);
                                    change.oldMoved = true;
                                }
                                if (change.replacement != null)
                                {
                                    staged.move(change.slot, parent, name);
                                    change.newInstalled = true;
                                }
                            }
                        }
                    }
                    requirePublication(publicationAllowed);
                }
                catch (IOException | RuntimeException failure)
                {
                    rollback(saved, failure);
                    throw failure;
                }
            }
            catch (IOException | RuntimeException failure)
            {
                if (state == State.PROMOTING) this.state = State.RECOVERY_REQUIRED;
                throw failure;
            }
            this.state = State.PROMOTED;
            LinuxBazelWorkspace.this.active = null;
        }

        /**
         * Discards private failed/cancelled storage after producer-proven native zero and complete unmapping.
         * The original worker root remains unchanged; a recovery-required journal may never be discarded here.
         * @throws IllegalStateException if publication is in progress or rollback remains uncertain
         * @throws IOException if private storage cannot be removed
         */
        public void discard() throws IOException
        {
            if (state == State.PROMOTING || state == State.RECOVERY_REQUIRED)
                throw new IllegalStateException("An unresolved publication journal cannot be discarded");
            if (state == State.DISCARDED || state == State.PROMOTED) return;
            remove(root);
            this.state = State.DISCARDED;
            LinuxBazelWorkspace.this.active = null;
        }

        /**
         * Verifies that the retained merged view remains the same attached directory object.
         * @throws IOException if the supervisor capability or merged directory changed before freezing completed
         */
        private void requireMerged() throws IOException
        {
            try (SecureDirectoryStream<Path> view = openMerged(merged, authenticatedProcRoot))
            {
                if (!mergedKey.equals(directoryKey(view))) throw new IOException("Merged view directory was replaced");
            }
        }

        /**
         * Selects non-overlapping changed entries and separate directory permission changes.
         * @param before corresponding immutable lower entry
         * @param after corresponding successful merged entry
         * @param relative original-root relative path, empty for the root
         * @throws IOException if entries cannot be compared or an unsupported output type is encountered
         */
        private void plan(Path before, Path after, Path relative) throws IOException
        {
            boolean oldExists = Files.exists(before, LinkOption.NOFOLLOW_LINKS);
            boolean newExists = Files.exists(after, LinkOption.NOFOLLOW_LINKS);
            boolean oldDirectory = oldExists && Files.isDirectory(before, LinkOption.NOFOLLOW_LINKS);
            boolean newDirectory = newExists && Files.isDirectory(after, LinkOption.NOFOLLOW_LINKS);
            if (oldDirectory && newDirectory)
            {
                Set<Path> names = new TreeSet<>();
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(before))
                {
                    for (Path entry : entries) names.add(entry.getFileName());
                }
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(after))
                {
                    for (Path entry : entries) names.add(entry.getFileName());
                }
                for (Path name : names) plan(before.resolve(name), after.resolve(name), relative.resolve(name));
                Set<PosixFilePermission> oldPermissions = Files.getPosixFilePermissions(before, LinkOption.NOFOLLOW_LINKS);
                Set<PosixFilePermission> newPermissions = Files.getPosixFilePermissions(after, LinkOption.NOFOLLOW_LINKS);
                if (!relative.toString().isEmpty() && !oldPermissions.equals(newPermissions))
                    changes.add(new Change(relative, null, true, true, oldPermissions, newPermissions));
            }
            else if (!oldExists || !newExists || !sameEntry(before, after))
            {
                if (newExists) validateTree(after, relative);
                changes.add(new Change(relative, newExists ? after : null, oldExists, false, Set.of(), Set.of()));
            }
        }

        /**
         * Rejects unsupported generated entries and links escaping the original authenticated root.
         * @param entry generated file, directory or symlink to validate
         * @param relative its original-root relative position
         * @throws IOException if a generated type is unsupported or a link escapes the input root
         */
        private void validateTree(Path entry, Path relative) throws IOException
        {
            BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink())
            {
                Path target = Files.readSymbolicLink(entry);
                Path originalEntry = originalInputRoot.resolve(relative);
                Path resolved = target.isAbsolute() ? target.normalize() : originalEntry.getParent().resolve(target).normalize();
                if (!resolved.startsWith(originalInputRoot)) throw new IOException("Generated symlink escapes worker input root");
            }
            else if (attributes.isDirectory())
            {
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(entry))
                {
                    for (Path child : entries) validateTree(child, relative.resolve(child.getFileName()));
                }
            }
            else if (!attributes.isRegularFile()) throw new IOException("Generated output is not a regular file, directory or symlink");
        }

        /**
         * Opens the selected entry's parent through the retained original root descriptor without following links.
         * @param relative nonempty path selected by the complete tree comparison
         * @return owned parent descriptor for one publication or rollback operation
         * @throws IOException if an intermediate directory was removed or replaced by a symlink
         */
        private SecureDirectoryStream<Path> parent(Path relative) throws IOException
        {
            SecureDirectoryStream<Path> current = input.newDirectoryStream(Path.of("."), LinkOption.NOFOLLOW_LINKS);
            try
            {
                Path parent = relative.getParent();
                if (parent != null)
                    for (Path name : parent)
                    {
                        SecureDirectoryStream<Path> next = current.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
                        current.close();
                        current = next;
                    }
                return current;
            }
            catch (IOException | RuntimeException | Error failure)
            {
                try { current.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        /**
         * Rechecks the producer lease and original attachment before changing any publication entry.
         * @param allowed producer-owned authentic-success and cancellation predicate
         * @throws IOException if publication has been revoked or root objects are no longer attached
         */
        private void requirePublication(BooleanSupplier allowed) throws IOException
        {
            if (!allowed.getAsBoolean()) throw new IOException("Successful attempt publication was cancelled");
            requireAttached();
        }

        /**
         * Reverses every journaled change before returning publication failure.
         * @param saved pinned private backup descriptor
         * @param failure original publication failure to receive rollback diagnostics
         */
        private void rollback(SecureDirectoryStream<Path> saved, Throwable failure)
        {
            boolean restored = true;
            for (Change change : changes.reversed())
            {
                try (SecureDirectoryStream<Path> parent = parent(change.relative))
                {
                    Path name = change.relative.getFileName();
                    if (change.modeApplied)
                        parent.getFileAttributeView(name, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                            .setPermissions(change.oldPermissions);
                    if (change.newInstalled) removeEntry(parent, name);
                    if (change.oldMoved) saved.move(change.slot, parent, name);
                }
                catch (IOException | RuntimeException rollbackFailure)
                {
                    restored = false;
                    failure.addSuppressed(rollbackFailure);
                }
            }
            try { requireAttached(); }
            catch (IOException attachmentFailure)
            {
                restored = false;
                failure.addSuppressed(attachmentFailure);
            }
            this.state = restored ? State.REJECTED : State.RECOVERY_REQUIRED;
            if (restored)
            {
                try
                {
                    remove(root);
                    LinuxBazelWorkspace.this.active = null;
                    this.state = State.DISCARDED;
                }
                catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
        }
    }

    /**
     * Storage lifecycle reported to the producer; native zero and unmapping remain external prerequisites.
     */
    enum State
    {
        /** Empty private directories may be mapped and run by the producer. */
        PREPARED,
        /** A complete successful output delta is privately staged and survives native unmapping. */
        FROZEN,
        /** Original directory changes are in progress under a rollback journal. */
        PROMOTING,
        /** Complete successful output publication committed. */
        PROMOTED,
        /** Freeze/publication failed before any unresolved original change remains. */
        REJECTED,
        /** Private attempt storage was removed after external zero/unmap barriers. */
        DISCARDED,
        /** Rollback or original attachment is unproven; retain journal and fail the invocation. */
        RECOVERY_REQUIRED
    }

    /**
     * Retains one staged replacement/deletion or directory-mode change and its rollback progress.
     */
    private static final class Change
    {
        /** Original-root relative entry name selected by tree comparison. */
        private final Path relative;
        /** Successful merged entry to stage, or null for a deletion/mode-only change. */
        private final Path replacement;
        /** Indicates whether the original entry must be backed up before replacement/deletion. */
        private final boolean existed;
        /** Indicates whether this change updates only an existing directory's permissions. */
        private final boolean modeOnly;
        /** Original directory permissions retained for mode-only rollback. */
        private final Set<PosixFilePermission> oldPermissions;
        /** Successful directory permissions applied after child changes. */
        private final Set<PosixFilePermission> newPermissions;
        /** Private numeric stage/backup entry name, assigned only after the complete delta is selected. */
        private Path slot;
        /** Indicates whether the old entry now resides in the private backup. */
        private boolean oldMoved;
        /** Indicates whether a new stage entry now occupies the original name. */
        private boolean newInstalled;
        /** Indicates whether new directory permissions require rollback. */
        private boolean modeApplied;

        /**
         * Retains one selected publication operation before any original entry changes.
         * @param relative original-root relative entry
         * @param replacement successful view entry, or null for deletion/mode-only
         * @param existed indicates whether an original entry exists
         * @param modeOnly indicates whether only directory permissions change
         * @param oldPermissions original mode-only permissions
         * @param newPermissions successful mode-only permissions
         */
        private Change(Path relative, Path replacement, boolean existed, boolean modeOnly,
            Set<PosixFilePermission> oldPermissions, Set<PosixFilePermission> newPermissions)
        {
            this.relative = relative;
            this.replacement = replacement;
            this.existed = existed;
            this.modeOnly = modeOnly;
            this.oldPermissions = Set.copyOf(oldPermissions);
            this.newPermissions = Set.copyOf(newPermissions);
        }
    }

    /**
     * Compares regular contents/modes, directory type or literal link targets without following links.
     * @param before immutable original entry
     * @param after successful merged entry
     * @return true when entry type, content and relevant POSIX permissions are equal
     * @throws IOException if entry metadata or bytes cannot be compared
     */
    private static boolean sameEntry(Path before, Path after) throws IOException
    {
        BasicFileAttributes left = Files.readAttributes(before, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        BasicFileAttributes right = Files.readAttributes(after, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (left.isSymbolicLink() && right.isSymbolicLink())
            return Files.readSymbolicLink(before).equals(Files.readSymbolicLink(after));
        if (left.isRegularFile() && right.isRegularFile())
            return Files.getPosixFilePermissions(before, LinkOption.NOFOLLOW_LINKS)
                .equals(Files.getPosixFilePermissions(after, LinkOption.NOFOLLOW_LINKS)) && Files.mismatch(before, after) == -1;
        return left.isDirectory() && right.isDirectory();
    }

    /**
     * Copies independent regular bytes, directories and literal symlinks without following links.
     * @param source stable source entry
     * @param destination absent owned entry
     * @throws IOException if an unsupported type or unavailable source prevents the complete copy
     */
    private static void copyTree(Path source, Path destination) throws IOException
    {
        copyTree(source, destination, false);
    }

    /**
     * Materializes a complete tree, optionally sharing only worker-guaranteed immutable read-only regular inodes.
     * @param source stable source entry
     * @param destination absent owned entry
     * @param shareImmutable indicates that the trusted worker guarantees no-write-bit input inodes remain immutable
     * @throws IOException if copying or immutable inode sharing fails
     */
    private static void copyTree(Path source, Path destination, boolean shareImmutable) throws IOException
    {
        BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink()) Files.createSymbolicLink(destination, Files.readSymbolicLink(source));
        else if (attributes.isDirectory())
        {
            Files.createDirectory(destination);
            try (DirectoryStream<Path> children = Files.newDirectoryStream(source))
            {
                for (Path child : children) copyTree(child, destination.resolve(child.getFileName()), shareImmutable);
            }
            Files.setPosixFilePermissions(destination, Files.getPosixFilePermissions(source, LinkOption.NOFOLLOW_LINKS));
        }
        else if (attributes.isRegularFile())
        {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(source, LinkOption.NOFOLLOW_LINKS);
            boolean immutable = shareImmutable && !permissions.contains(PosixFilePermission.OWNER_WRITE) &&
                !permissions.contains(PosixFilePermission.GROUP_WRITE) && !permissions.contains(PosixFilePermission.OTHERS_WRITE);
            if (immutable) Files.createLink(destination, source);
            else Files.copy(source, destination, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES);
        }
        else throw new IOException("Workspace entry is not a regular file, directory or symlink: " + source);
    }

    /**
     * Acquires an absolute directory by opening every ancestor without following symbolic links.
     * @param directory absolute normalized directory path
     * @return owned secure directory descriptor
     * @throws IOException if an ancestor is missing, replaced or a symbolic link
     * @throws UnsupportedOperationException if the filesystem provides no secure directory stream
     */
    private static SecureDirectoryStream<Path> openDirectory(Path directory) throws IOException
    {
        DirectoryStream<Path> root = Files.newDirectoryStream(directory.getRoot());
        if (!(root instanceof SecureDirectoryStream<Path> secure))
        {
            root.close();
            throw new UnsupportedOperationException("Bazel publication requires secure directory streams");
        }
        SecureDirectoryStream<Path> current = secure;
        try
        {
            for (Path name : directory)
            {
                SecureDirectoryStream<Path> next = current.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
                current.close();
                current = next;
            }
            return current;
        }
        catch (IOException | RuntimeException | Error failure)
        {
            try { current.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /**
     * Opens a merged view beneath an explicitly authenticated live supervisor or through ordinary no-follow paths.
     * @param merged absolute complete merged directory path
     * @param authenticatedProcRoot caller-authenticated proc-root capability, or null for ordinary paths
     * @return owned secure descriptor of the complete merged directory
     * @throws IOException if the capability or a remaining no-follow component cannot be opened
     */
    private static SecureDirectoryStream<Path> openMerged(Path merged, Path authenticatedProcRoot) throws IOException
    {
        if (authenticatedProcRoot == null) return openDirectory(merged);
        SecureDirectoryStream<Path> current;
        try (SecureDirectoryStream<Path> process = openDirectory(authenticatedProcRoot.getParent()))
        {
            current = process.newDirectoryStream(authenticatedProcRoot.getFileName());
        }
        try
        {
            for (Path name : authenticatedProcRoot.relativize(merged))
            {
                SecureDirectoryStream<Path> next = current.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
                current.close();
                current = next;
            }
            return current;
        }
        catch (IOException | RuntimeException | Error failure)
        {
            try { current.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /**
     * Reads the stable filesystem identity of an already opened directory object.
     * @param directory pinned descriptor
     * @return nonnull filesystem file key
     * @throws IOException if descriptor metadata cannot be read
     * @throws UnsupportedOperationException if the filesystem supplies no stable file key
     */
    private static Object directoryKey(SecureDirectoryStream<Path> directory) throws IOException
    {
        Object key = directory.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey();
        if (key == null) throw new UnsupportedOperationException("Bazel publication requires stable directory file keys");
        return key;
    }

    /**
     * Deletes a selected entry under an already pinned parent without following contained symbolic links.
     * @param parent pinned parent descriptor
     * @param name one relative entry name
     * @throws IOException if the entry cannot be completely removed
     */
    private static void removeEntry(SecureDirectoryStream<Path> parent, Path name) throws IOException
    {
        BasicFileAttributes attributes = parent.getFileAttributeView(name, BasicFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS).readAttributes();
        if (attributes.isDirectory())
        {
            try (SecureDirectoryStream<Path> directory = parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS))
            {
                List<Path> names = new ArrayList<>();
                for (Path entry : directory) names.add(entry.getFileName());
                for (Path child : names) removeEntry(directory, child);
            }
            parent.deleteDirectory(name);
        }
        else parent.deleteFile(name);
    }

    /**
     * Removes only an independently owned private tree without following any symlink.
     * @param root private tree allocated by this workspace
     * @throws IOException if complete private-tree cleanup fails
     */
    private static void remove(Path root) throws IOException
    {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> paths = Files.walk(root))
        {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    /**
     * Validates an absolute normalized caller-selected root.
     * @param path filesystem boundary
     * @throws NullPointerException if {@code path} is null
     * @throws IllegalArgumentException if {@code path} is relative or unnormalized
     */
    private static void absolute(Path path)
    {
        Objects.requireNonNull(path, "path");
        if (!path.isAbsolute() || !path.equals(path.normalize()))
            throw new IllegalArgumentException("Workspace paths must be absolute and normalized");
    }

    /**
     * Indicates whether either filesystem root contains the other.
     * @param first one root
     * @param second other root
     * @return true when the roots overlap or are equal
     */
    private static boolean overlaps(Path first, Path second)
    {
        return first.startsWith(second) || second.startsWith(first);
    }
}
