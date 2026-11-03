package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxBazelWorkspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.testng.SkipException;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises worker-visible publication and retry isolation without claiming native mount or process barriers.
 * Each merged-view fixture models the host-readable successful view supplied only after the producer proves zero.
 */
public final class LinuxBazelWorkspaceTest
{
    /**
     * Skips this class on file systems without POSIX attributes, which its POSIX-only production code requires.
     *
     * @throws org.testng.SkipException if the default file system lacks the POSIX attribute view
     */
    @org.testng.annotations.BeforeClass
    public void requirePosix()
    {
        TestRequirements.requirePosixFileSystem();
    }

    /**
     * Creates independent cases with no shared mutable fixtures.
     */
    public LinuxBazelWorkspaceTest() {}

    /**
     * An authenticated current-process proc root is the sole permitted followed merged anchor.
     * This fixture authenticates its own live process; it makes no native namespace or cgroup claim.
     * @throws IOException if fixture acquisition, freeze or cleanup fails
     */
    @Test
    public void authenticatedProcRootAllowsMergedFreezeWithoutRetainedDescriptors() throws IOException
    {
        if (!Files.isDirectory(Path.of("/proc/self")))
            throw new SkipException("Requires a Linux procfs");
        Path root = Files.createTempDirectory("bazel-proc-root-");
        try
        {
            Path input = Files.createDirectory(root.resolve("input"));
            Path temporary = Files.createDirectory(root.resolve("tmp"));
            Path merged = Files.createDirectory(root.resolve("merged"));
            Files.writeString(merged.resolve("output"), "accepted");
            Path anchor = Path.of("/proc", Long.toString(ProcessHandle.current().pid()), "root");
            Path anchoredMerged = anchor.resolve(merged.toString().substring(1));
            try (LinuxBazelWorkspace workspace = LinuxBazelWorkspace.open(input, temporary, root.resolve("owned")))
            {
                var attempt = workspace.begin(1);
                boolean rejected = false;
                try { attempt.bindMerged(anchoredMerged); }
                catch (IOException expected) { rejected = true; }
                requireThat(rejected, "genericMagicLinkRejected").isTrue();
                attempt.bindMerged(anchoredMerged, anchor);
                attempt.freeze();
                remove(merged);
                attempt.promote(() -> true);
                requireThat(Files.readString(input.resolve("output")), "frozenProcOutput").isEqualTo("accepted");
            }
        }
        finally { remove(root); }
    }

    /**
     * Successful changed/new outputs become visible inside the same worker-owned root object.
     * @throws IOException if fixture operations, publication or cleanup fails
     */
    @Test
    public void promotesIntoOriginalDirectoryObject() throws IOException
    {
        Path root = Files.createTempDirectory("bazel-publication-");
        try
        {
            Path input = Files.createDirectory(root.resolve("input"));
            Path temporary = Files.createDirectory(root.resolve("tmp"));
            Files.writeString(input.resolve("old"), "before");
            Object key = Files.readAttributes(input, BasicFileAttributes.class).fileKey();
            try (LinuxBazelWorkspace workspace = LinuxBazelWorkspace.open(input, temporary, root.resolve("owned")))
            {
                LinuxBazelWorkspace.Attempt attempt = workspace.begin(1);
                Path merged = Files.createDirectory(root.resolve("merged"));
                Files.writeString(merged.resolve("old"), "after");
                Files.writeString(merged.resolve("new"), "output");
                attempt.bindMerged(merged);
                attempt.promote(() -> true);
                requireThat(Files.readString(input.resolve("old")), "changedOutput").isEqualTo("after");
                requireThat(Files.readString(input.resolve("new")), "newOutput").isEqualTo("output");
                requireThat(Files.readAttributes(input, BasicFileAttributes.class).fileKey(), "sameWorkerRoot")
                    .isEqualTo(key);
            }
        }
        finally { remove(root); }
    }

    /**
     * Discarding a failed private view keeps the worker tree pristine and supplies a fresh retry upper tree.
     * @throws IOException if fixture operations, discard or cleanup fails
     */
    @Test
    public void discardedAttemptCannotPublishAndRetryIsFresh() throws IOException
    {
        Path root = Files.createTempDirectory("bazel-retry-");
        try
        {
            Path input = Files.createDirectory(root.resolve("input"));
            Path temporary = Files.createDirectory(root.resolve("tmp"));
            Files.writeString(input.resolve("source"), "original");
            try (LinuxBazelWorkspace workspace = LinuxBazelWorkspace.open(input, temporary, root.resolve("owned")))
            {
                LinuxBazelWorkspace.Attempt failed = workspace.begin(1);
                Files.writeString(failed.upper().resolve("failed-output"), "partial");
                failed.discard();
                LinuxBazelWorkspace.Attempt retry = workspace.begin(2);
                requireThat(Files.exists(retry.upper().resolve("failed-output")), "failedBytesInFreshUpper").isFalse();
                requireThat(Files.exists(input.resolve("failed-output")), "failedBytesPublished").isFalse();
                requireThat(Files.readString(workspace.immutableSnapshot().resolve("source")), "independentLower")
                    .isEqualTo("original");
                retry.discard();
            }
        }
        finally { remove(root); }
    }

    /**
     * Cancellation after publication starts rolls changed bytes and deleted entries back before reporting failure.
     * @throws IOException if fixture setup or cleanup fails
     */
    @Test
    public void cancellationDuringPromotionRollsBack() throws IOException
    {
        Path root = Files.createTempDirectory("bazel-rollback-");
        try
        {
            Path input = Files.createDirectory(root.resolve("input"));
            Path temporary = Files.createDirectory(root.resolve("tmp"));
            Files.writeString(input.resolve("a"), "original-a");
            Files.writeString(input.resolve("b"), "original-b");
            try (LinuxBazelWorkspace workspace = LinuxBazelWorkspace.open(input, temporary, root.resolve("owned")))
            {
                LinuxBazelWorkspace.Attempt attempt = workspace.begin(1);
                Path merged = Files.createDirectory(root.resolve("merged"));
                Files.writeString(merged.resolve("a"), "changed-a");
                Files.writeString(merged.resolve("c"), "new-c");
                attempt.bindMerged(merged);
                AtomicInteger checks = new AtomicInteger();
                IOException failure = null;
                try { attempt.promote(() -> checks.incrementAndGet() < 4); }
                catch (IOException expected) { failure = expected; }
                requireThat(failure != null, "cancelledPromotionRejected").isTrue();
                requireThat(Files.readString(input.resolve("a")), "changedEntryRestored").isEqualTo("original-a");
                requireThat(Files.readString(input.resolve("b")), "deletedEntryRestored").isEqualTo("original-b");
                requireThat(Files.exists(input.resolve("c")), "newEntryRemoved").isFalse();
            }
        }
        finally { remove(root); }
    }

    /**
     * A replacement root is rejected and cancellation/removal of the worker's original path cannot destroy the lower.
     * @throws IOException if fixture setup or cleanup fails
     */
    @Test
    public void rejectsReplacementAndKeepsIndependentSnapshot() throws IOException
    {
        Path root = Files.createTempDirectory("bazel-root-replaced-");
        try
        {
            Path input = Files.createDirectory(root.resolve("input"));
            Path temporary = Files.createDirectory(root.resolve("tmp"));
            Files.writeString(input.resolve("source"), "retained");
            try (LinuxBazelWorkspace workspace = LinuxBazelWorkspace.open(input, temporary, root.resolve("owned")))
            {
                LinuxBazelWorkspace.Attempt attempt = workspace.begin(1);
                Path merged = Files.createDirectory(root.resolve("merged"));
                Files.writeString(merged.resolve("output"), "late");
                attempt.bindMerged(merged);
                Files.move(input, root.resolve("removed-worker-root"));
                Files.createDirectory(input);
                IOException failure = null;
                try { attempt.promote(() -> true); }
                catch (IOException expected) { failure = expected; }
                requireThat(failure != null, "replacementRootRejected").isTrue();
                requireThat(Files.exists(input.resolve("output")), "replacementUntouched").isFalse();
                remove(root.resolve("removed-worker-root"));
                requireThat(Files.readString(workspace.immutableSnapshot().resolve("source")), "lowerSurvivesWorkerRemoval")
                    .isEqualTo("retained");
                attempt.discard();
            }
        }
        finally { remove(root); }
    }

    /**
     * Publication handles regular-to-directory and directory-to-symlink changes without following their targets.
     * @throws IOException if fixture operations, publication or cleanup fails
     */
    @Test
    public void promotesTypeChangesAndLiteralSymlinks() throws IOException
    {
        Path root = Files.createTempDirectory("bazel-types-");
        try
        {
            Path input = Files.createDirectory(root.resolve("input"));
            Path temporary = Files.createDirectory(root.resolve("tmp"));
            Files.writeString(input.resolve("file-to-dir"), "old");
            Files.createDirectory(input.resolve("dir-to-link"));
            Files.writeString(input.resolve("dir-to-link/old"), "old-child");
            try (LinuxBazelWorkspace workspace = LinuxBazelWorkspace.open(input, temporary, root.resolve("owned")))
            {
                LinuxBazelWorkspace.Attempt attempt = workspace.begin(1);
                Path merged = Files.createDirectory(root.resolve("merged"));
                Files.createDirectory(merged.resolve("file-to-dir"));
                Files.writeString(merged.resolve("file-to-dir/output"), "new-child");
                Files.createSymbolicLink(merged.resolve("dir-to-link"), Path.of("file-to-dir"));
                attempt.bindMerged(merged);
                attempt.promote(() -> true);
                requireThat(Files.readString(input.resolve("file-to-dir/output")), "directoryOutput").isEqualTo("new-child");
                requireThat(Files.isSymbolicLink(input.resolve("dir-to-link")), "publishedLink").isTrue();
                requireThat(Files.readSymbolicLink(input.resolve("dir-to-link")), "literalLinkTarget")
                    .isEqualTo(Path.of("file-to-dir"));
            }
        }
        finally { remove(root); }
    }

    /**
     * No-follow root acquisition rejects a symlink selected in the request path.
     * @throws IOException if fixture setup or cleanup fails
     */
    @Test
    public void rejectsSymlinkRoot() throws IOException
    {
        Path root = Files.createTempDirectory("bazel-root-link-");
        try
        {
            Path real = Files.createDirectory(root.resolve("real"));
            Path input = Files.createSymbolicLink(root.resolve("input"), real);
            Path temporary = Files.createDirectory(root.resolve("tmp"));
            IOException failure = null;
            try (LinuxBazelWorkspace ignored = LinuxBazelWorkspace.open(input, temporary, root.resolve("owned")))
            {
                requireThat(ignored != null, "workspaceOpened").isTrue();
            }
            catch (IOException expected) { failure = expected; }
            requireThat(failure != null, "symlinkRootRejected").isTrue();
        }
        finally { remove(root); }
    }

    /**
     * Frozen successful bytes survive removal of the mounted view before the producer accepts the final outcome.
     * @throws IOException if fixture operations, freeze or publication fails
     */
    @Test
    public void frozenStageSurvivesUnmappedView() throws IOException
    {
        Path root = Files.createTempDirectory("bazel-freeze-");
        try
        {
            Path input = Files.createDirectory(root.resolve("input"));
            Path temporary = Files.createDirectory(root.resolve("tmp"));
            Files.writeString(input.resolve("source"), "source");
            try (LinuxBazelWorkspace workspace = LinuxBazelWorkspace.open(input, temporary, root.resolve("owned")))
            {
                LinuxBazelWorkspace.Attempt attempt = workspace.begin(1);
                Path merged = Files.createDirectory(root.resolve("merged"));
                Files.writeString(merged.resolve("source"), "source");
                Files.writeString(merged.resolve("output"), "frozen-output");
                attempt.bindMerged(merged);
                attempt.freeze();
                requireThat(Files.exists(input.resolve("output")), "freezeDoesNotPublish").isFalse();
                remove(merged);
                attempt.promote(() -> true);
                requireThat(Files.readString(input.resolve("output")), "frozenBytesPublished").isEqualTo("frozen-output");
            }
        }
        finally { remove(root); }
    }

    /**
     * Cancellation during a file/directory/symlink type change restores every original entry and child.
     * @throws IOException if fixture operations or cleanup fails
     */
    @Test
    public void rollbackRestoresOriginalEntryTypes() throws IOException
    {
        Path root = Files.createTempDirectory("bazel-type-rollback-");
        try
        {
            Path input = Files.createDirectory(root.resolve("input"));
            Path temporary = Files.createDirectory(root.resolve("tmp"));
            Files.writeString(input.resolve("a"), "file-before");
            Files.createDirectory(input.resolve("b"));
            Files.writeString(input.resolve("b/child"), "child-before");
            try (LinuxBazelWorkspace workspace = LinuxBazelWorkspace.open(input, temporary, root.resolve("owned")))
            {
                LinuxBazelWorkspace.Attempt attempt = workspace.begin(1);
                Path merged = Files.createDirectory(root.resolve("merged"));
                Files.createDirectory(merged.resolve("a"));
                Files.writeString(merged.resolve("a/child"), "new-child");
                Files.createSymbolicLink(merged.resolve("b"), Path.of("a"));
                attempt.bindMerged(merged);
                AtomicInteger checks = new AtomicInteger();
                IOException failure = null;
                try { attempt.promote(() -> checks.incrementAndGet() < 4); }
                catch (IOException expected) { failure = expected; }
                requireThat(failure != null, "typeChangeCancelled").isTrue();
                requireThat(Files.readString(input.resolve("a")), "regularFileRestored").isEqualTo("file-before");
                requireThat(Files.isSymbolicLink(input.resolve("b")), "directoryLinkRemoved").isFalse();
                requireThat(Files.readString(input.resolve("b/child")), "originalDirectoryChildRestored")
                    .isEqualTo("child-before");
            }
        }
        finally { remove(root); }
    }

    /**
     * Trusted immutable read-only inputs reuse their inode while independent names survive worker removal/replacement.
     * Writable regular inputs retain independent bytes even when the worker changes its own original file.
     * @throws IOException if snapshot operations or fixture cleanup fails
     */
    @Test
    public void sharesOnlyTrustedReadOnlyInputsAndSurvivesReplacement() throws IOException
    {
        Path root = Files.createTempDirectory("bazel-immutable-share-");
        try
        {
            Path input = Files.createDirectory(root.resolve("input"));
            Path temporary = Files.createDirectory(root.resolve("tmp"));
            Path readOnly = Files.writeString(input.resolve("readonly"), "immutable-original");
            Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r--r--r--"));
            Path writable = Files.writeString(input.resolve("writable"), "mutable-original");
            try (LinuxBazelWorkspace workspace = LinuxBazelWorkspace.open(input, temporary, root.resolve("owned")))
            {
                Path lower = workspace.immutableSnapshot();
                requireThat(Files.isSameFile(readOnly, lower.resolve("readonly")), "immutableInodeReused").isTrue();
                requireThat(Files.isSameFile(writable, lower.resolve("writable")), "writableInodeReused").isFalse();
                Files.writeString(writable, "worker-changed");
                Files.delete(readOnly);
                Files.writeString(readOnly, "replacement-at-same-name");
                requireThat(Files.readString(lower.resolve("readonly")), "sharedOriginalSurvivesReplacement")
                    .isEqualTo("immutable-original");
                requireThat(Files.readString(lower.resolve("writable")), "writableCopyIndependent")
                    .isEqualTo("mutable-original");
            }
        }
        finally { remove(root); }
    }

    /**
     * Deletes the test's root without following any contained symlink.
     * @param root test-owned root
     * @throws IOException if cleanup fails
     */
    private static void remove(Path root) throws IOException
    {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> entries = Files.walk(root))
        {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
        }
    }
}
