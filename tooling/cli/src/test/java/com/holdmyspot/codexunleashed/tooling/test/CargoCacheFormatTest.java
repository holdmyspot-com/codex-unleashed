package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.CargoCacheKeys;
import com.holdmyspot.codexunleashed.tooling.CargoCacheParts;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.SourceInputInventory;
import com.holdmyspot.codexunleashed.tooling.SourceInputSnapshots;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.testng.SkipException;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Pins the restored-state format that the compatible cache tag relies on.
 * <p>
 * The compatible tag hashes {@link CargoCacheKeys#FORMAT_VERSION} instead of the helper's source, so state saved by an
 * earlier helper is restored by a later one. When a test here fails, the archive, ownership, digest-index or
 * source-snapshot format changed: increment {@code FORMAT_VERSION} and record the new fingerprints under it.
 */
public final class CargoCacheFormatTest
{
    /** Fingerprint of the platform-neutral format for each format version. */
    private static final Map<Integer, String> FINGERPRINTS =
        Map.of(1, "70fc06b320b818b9b9e350ce24818dd7c21c4ecf01c584f29c4352e445fadfde");
    /** Digest-index field names and version for each format version; the index exists only on POSIX file systems. */
    private static final Map<Integer, String> INDEX_SHAPES = Map.of(1,
        "[files, format_version, hashed_at_nanos] [changed_nanos, digest, file_key, modified_nanos, size] format_version=1");
    /** A fixed modification time for every fixture path. */
    private static final FileTime FIXED = FileTime.from(Instant.parse("2026-01-02T03:04:05Z"));

    /** Creates independent method fixtures. */
    public CargoCacheFormatTest() {}

    /**
     * Collects a fixed output tree and writes a fixed source snapshot, then compares their format with the recorded one.
     * @throws IOException if fixture storage fails
     */
    @Test public void restoredStateFormatMatchesItsVersion() throws IOException
    {
        try (ToolingScope scope = new DefaultToolingScope(); Fixture fixture = new Fixture())
        {
            Map<String, Object> format = new LinkedHashMap<>();
            format.put("artifact", CargoCacheParts.ARTIFACT);
            format.put("owners", CargoCacheParts.OWNERS);
            format.put("digests", CargoCacheParts.DIGESTS);
            format.put("part_entries", CargoCacheParts.PART_ENTRIES);
            format.put("groups", normalized(CargoCacheParts.collect(scope, fixture.target, "codex")));
            format.put("ownership_record", Files.readString(fixture.target.resolve(CargoCacheParts.OWNERS)));
            Path snapshot = fixture.root.resolve("snapshot.json");
            SourceInputSnapshots.write(scope, snapshot, new SourceInputInventory.Snapshot(fixture.root,
                Map.of("src/lib.rs", "a".repeat(64)), Map.of("src/lib.rs", BigInteger.valueOf(1_767_323_045_000_000_000L))));
            format.put("source_snapshot", Files.readString(snapshot));
            String text = scope.getJsonMapper().writeValueAsString(format);
            String fingerprint = HexFormat.of().formatHex(CargoCacheKeys.digest().digest(text.getBytes(StandardCharsets.UTF_8)));
            requireThat(fingerprint, "formatFingerprint").withContext(text, "format").
                isEqualTo(FINGERPRINTS.get(CargoCacheKeys.FORMAT_VERSION));
        }
    }

    /**
     * Compares the digest index's field names and version with the recorded ones.
     * @throws IOException if fixture storage fails
     * @throws SkipException if the file system has no POSIX change times, so no index is written
     */
    @Test public void digestIndexShapeMatchesItsVersion() throws IOException
    {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("unix"))
            throw new SkipException("The digest index requires POSIX change times");
        try (ToolingScope scope = new DefaultToolingScope(); Fixture fixture = new Fixture())
        {
            CargoCacheParts.collect(scope, fixture.target, "codex");
            JsonNode index = scope.getJsonMapper().readTree(Files.readString(fixture.target.resolve(CargoCacheParts.DIGESTS)));
            TreeSet<String> fields = new TreeSet<>(index.propertyNames());
            TreeSet<String> fileFields = new TreeSet<>();
            for (var file : index.get("files").properties()) fileFields.addAll(file.getValue().propertyNames());
            String shape = fields + " " + fileFields + " format_version=" + index.get("format_version");
            requireThat(shape, "digestIndexShape").isEqualTo(INDEX_SHAPES.get(CargoCacheKeys.FORMAT_VERSION));
        }
    }

    /**
     * Keeps each entry's path, file type, size and content, and its time except for the ownership record's.
     * <p>
     * Permission bits differ between POSIX and Windows hosts, and the ownership record is rewritten at collection time.
     * @param groups collected groups
     * @return platform-neutral groups
     */
    private static Map<String, List<List<Object>>> normalized(Map<String, List<List<Object>>> groups)
    {
        Map<String, List<List<Object>>> result = new LinkedHashMap<>();
        for (var group : groups.entrySet())
        {
            List<List<Object>> entries = new ArrayList<>();
            for (List<Object> entry : group.getValue())
            {
                List<Object> copy = new ArrayList<>(entry);
                copy.set(1, ((Number) entry.get(1)).intValue() & 0170000);
                if (entry.getFirst().equals(CargoCacheParts.OWNERS)) copy.set(2, 0);
                entries.add(copy);
            }
            result.put(group.getKey(), entries);
        }
        return result;
    }

    /** Owns a fixed Cargo output tree. */
    private static final class Fixture implements AutoCloseable
    {
        /** Owned fixture root. */
        private final Path root;
        /** Cargo output directory. */
        private final Path target;

        /**
         * Writes a library, its dependency file and a fingerprint, all with fixed times.
         * @throws IOException if storage fails
         */
        private Fixture() throws IOException
        {
            root = Files.createTempDirectory("cache-format-");
            target = Files.createDirectory(root.resolve("target"));
            Path deps = Files.createDirectories(target.resolve("release/deps"));
            Path fingerprint = Files.createDirectories(target.resolve("release/.fingerprint/a-0123"));
            Files.writeString(deps.resolve("liba-0123.rlib"), "library");
            Files.writeString(deps.resolve("a-0123.d"), "release/deps/liba-0123.rlib: src/lib.rs\n");
            Files.writeString(fingerprint.resolve("lib-a"), "0123456789abcdef");
            try (Stream<Path> paths = Files.walk(target))
            {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.setLastModifiedTime(path, FIXED);
            }
        }

        /**
         * Deletes only the owned fixture.
         * @throws IOException if deletion fails
         */
        @Override public void close() throws IOException
        {
            try (Stream<Path> paths = Files.walk(root))
            {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
