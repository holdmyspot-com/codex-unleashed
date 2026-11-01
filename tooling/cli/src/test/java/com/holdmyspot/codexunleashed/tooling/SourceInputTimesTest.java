package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Verifies exact native no-follow queries and existing follow semantics with independent real link fixtures. */
public final class SourceInputTimesTest
{
    /** Creates independent method fixtures. */ public SourceInputTimesTest() {}

    /**
     * Keeps the original referenced-file operation distinct from the link's own timestamp.
     * @throws IOException if independent file or timestamp operations fail
     */
    @Test public void preservesFollowingAndNoFollowingLiveLinks() throws IOException
    {
        try (Fixture f = new Fixture())
        {
            Files.getFileAttributeView(f.link, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).
                setTimes(FileTime.from(Instant.parse("2010-01-01T00:00:00.123456700Z")), null, null);
            Instant target = Files.getLastModifiedTime(f.target).toInstant();
            Instant link = Files.getLastModifiedTime(f.link, LinkOption.NOFOLLOW_LINKS).toInstant();
            requireThat(link.equals(target), "independentlyDifferentTimes").isFalse();
            requireThat(SourceInputTimes.lastModified(f.scope, f.link), "existingFollowResult").isEqualTo(target);
            requireThat(SourceInputTimes.lastModified(f.scope, f.link, LinkOption.NOFOLLOW_LINKS), "noFollowResult").isEqualTo(link);
            requireThat(f.nativeRead(f.link, true), "nativeFollowResult").isEqualTo(target);
            requireThat(f.nativeRead(f.link, false), "nativeNoFollowResult").isEqualTo(link);
            requireThat(Files.readString(f.target), "untouchedReferencedBytes").isEqualTo("referenced bytes");
        }
    }

    /**
     * Retains native fractional bits beyond long epoch nanoseconds without altering the referenced file.
     * @throws IOException if independent native setup, query or cleanup fails
     */
    @Test public void readsNativeFarFutureLinkPrecisely() throws IOException
    {
        try (Fixture f = new Fixture())
        {
            Instant target = Files.getLastModifiedTime(f.target).toInstant();
            BigInteger actual = f.nativeLinkTimestamp();
            requireThat(SourceInputTimes.nanos(SourceInputTimes.lastModified(f.scope, f.link, LinkOption.NOFOLLOW_LINKS)),
                "exactIndependentNativeLinkTime").isEqualTo(actual);
            requireThat(SourceInputTimes.nanos(f.nativeRead(f.link, false)), "directNativeNoFollowTime").isEqualTo(actual);
            requireThat(SourceInputTimes.lastModified(f.scope, f.link), "originalFollowTime").isEqualTo(target);
            requireThat(Files.getLastModifiedTime(f.target).toInstant(), "untouchedReferencedTime").isEqualTo(target);
        }
    }

    /**
     * Reads a dangling link's exact native metadata while both following operations still refuse its missing target.
     * @throws IOException if independent link setup, query or cleanup fails
     */
    @Test public void danglingLinkDoesNotRequireItsReferent() throws IOException
    {
        try (Fixture f = new Fixture())
        {
            Files.delete(f.target);
            BigInteger actual = f.nativeLinkTimestamp();
            requireThat(SourceInputTimes.nanos(SourceInputTimes.lastModified(f.scope, f.link, LinkOption.NOFOLLOW_LINKS)),
                "danglingNoFollowTime").isEqualTo(actual);
            requireThat(SourceInputTimes.nanos(f.nativeRead(f.link, false)), "danglingDirectNativeTime").isEqualTo(actual);
            requireException(IOException.class, () -> SourceInputTimes.lastModified(f.scope, f.link));
            requireException(IOException.class, () -> f.nativeRead(f.link, true));
            requireThat(Files.exists(f.target), "referentNeverCreated").isFalse();
        }
    }

    /**
     * Missing entries fail with their actual query error instead of fabricating a timestamp or following fallback.
     * @throws IOException if fixture allocation or cleanup fails
     */
    @Test public void missingEntriesNeverProduceTimestamps() throws IOException
    {
        try (Fixture f = new Fixture())
        {
            Path missing = f.root.resolve("missing");
            requireException(IOException.class, () -> SourceInputTimes.lastModified(f.scope, missing));
            requireException(IOException.class, () -> SourceInputTimes.lastModified(f.scope, missing, LinkOption.NOFOLLOW_LINKS));
            requireException(IOException.class, () -> f.nativeRead(missing, true));
            requireException(IOException.class, () -> f.nativeRead(missing, false));
            requireThat(Files.exists(missing, LinkOption.NOFOLLOW_LINKS), "missingEntryNotCreated").isFalse();
        }
    }

    /** Owns one method's scope, actual native metadata queries, files and links. */
    private static final class Fixture implements AutoCloseable
    {
        /** Shared native bindings owned by this method. */ private final ToolingScope scope;
        /** Exclusively owned temporary root. */ private final Path root;
        /** Independently timestamped referenced file. */ private final Path target;
        /** Actual link, never a fixture-provided timestamp source. */ private final Path link;

        /**
         * Allocates the real referenced file and symbolic link without changing process-wide platform state.
         * @throws IOException if allocation or supported filesystem operations fail
         */
        private Fixture() throws IOException
        {
            ToolingScope acquiredScope = new DefaultToolingScope();
            Path acquiredRoot = null;
            try
            {
                acquiredRoot = Files.createTempDirectory("no-follow-");
                target = Files.writeString(acquiredRoot.resolve("target"), "referenced bytes");
                Files.setLastModifiedTime(target, FileTime.from(Instant.parse("2020-01-01T00:00:00.765432100Z")));
                link = Files.createSymbolicLink(acquiredRoot.resolve("link"), target.getFileName());
                scope = acquiredScope;
                root = acquiredRoot;
            }
            catch (IOException | RuntimeException | Error failure)
            {
                try (acquiredScope)
                {
                    if (acquiredRoot != null) deleteTree(acquiredRoot);
                }
                catch (IOException | RuntimeException | Error cleanupFailure)
                {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        /**
         * Writes through Node and independently reads the actual link without following it.
         * Windows uses the JDK reader because Node's observed bigint timestamp wraps far-future seconds there.
         * Unix retains Node's native bigint reader. Both expectations describe the actual filesystem value.
         * @return native observed link modification time in exact epoch nanoseconds
         * @throws IOException if the independent setup or read fails
         */
        private BigInteger nativeLinkTimestamp() throws IOException
        {
            String script = "const fs=require('node:fs');const p=process.argv[1];" +
                "fs.lutimesSync(p,10000000000.123456,10000000000.123456);" +
                "process.stdout.write(fs.lstatSync(p,{bigint:true}).mtimeNs.toString());";
            SystemCommands.Result result = SystemCommands.capture(List.of("node", "-e", script, link.toString()),
                root, root, Map.of());
            if (result.status() != 0) throw new IOException("Independent no-follow measurement failed: " + result.stderr());
            if (link.getFileSystem().getSeparator().equals("\\"))
            {
                Instant actual = Files.getLastModifiedTime(link, LinkOption.NOFOLLOW_LINKS).toInstant();
                return BigInteger.valueOf(actual.getEpochSecond()).multiply(BigInteger.valueOf(1_000_000_000L)).
                    add(BigInteger.valueOf(actual.getNano()));
            }
            return new BigInteger(result.stdout());
        }

        /**
         * Acquires the actual platform implementation through the owning Pouch scope.
         * @param path exact native path
         * @param followLinks true for referenced-file metadata, false for link metadata
         * @return precise native timestamp
         * @throws IOException if the actual platform query fails
         */
        private Instant nativeRead(Path path, boolean followLinks) throws IOException
        {
            if (path.getFileSystem().getSeparator().equals("\\"))
                return scope.getWindowsFileTimes().lastModified(path, followLinks);
            return scope.getUnixFileMetadata().lastModified(path, followLinks);
        }

        /**
         * Deletes only the method's tree, never following symbolic links, then releases its shared services.
         * @throws IOException if owned cleanup fails
         */
        @Override public void close() throws IOException
        {
            try (scope)
            {
                deleteTree(root);
            }
        }

        /**
         * Removes a fully or partially constructed owned tree without following any symbolic links.
         * @param directory exclusively owned temporary root
         * @throws IOException if walking or deleting the owned tree fails
         */
        private static void deleteTree(Path directory) throws IOException
        {
            try (var paths = Files.walk(directory))
            {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
