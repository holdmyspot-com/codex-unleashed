package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.CargoCacheKeys;
import com.holdmyspot.codexunleashed.tooling.Main;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Verifies public cache identities from independent immutable method fixtures. */
public final class CargoCacheKeysTest
{
    /** Creates independent method fixtures. */
    public CargoCacheKeysTest() {}

    /**
     * Retains exact input byte order, including binary bytes and line endings, in the exact tag. The compatible tag
     * hashes only the cache format version and the compiler inputs, so upstream, wrapper and helper changes keep it.
     * @throws IOException if fixture storage fails
     * @throws NoSuchAlgorithmException if SHA-256 is unavailable
     */
    @Test public void exactBytesAndCompatibleSeeds() throws IOException, NoSuchAlgorithmException
    {
        try (Fixture fixture = new Fixture())
        {
            byte[][] bytes = {new byte[]{65, 0, 13, 10}, "wrapper\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "helper\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), "compiler λ".getBytes(java.nio.charset.StandardCharsets.UTF_8)};
            for (int i = 0; i < bytes.length; ++i) Files.write(fixture.inputs.get(i), bytes[i]);
            requireThat(fixture.run("target"), "publicStatus").isEqualTo(0);
            MessageDigest exact = MessageDigest.getInstance("SHA-256"); MessageDigest seed = MessageDigest.getInstance("SHA-256");
            for (byte[] value : bytes) exact.update(value);
            seed.update(("cargo-cache-format=" + CargoCacheKeys.FORMAT_VERSION + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            seed.update(bytes[3]);
            requireThat(fixture.output.toString(), "orderedIdentityBytes").isEqualTo("tag=cargo-v3-target-" + HexFormat.of().formatHex(exact.digest()) +
                "\nseed_tag=cargo-seed-v2-target-" + HexFormat.of().formatHex(seed.digest()) + "\n");
            String originalSeed = fixture.output.toString().lines().toList().getLast();
            for (int changed = 0; changed < 3; ++changed)
            {
                fixture.output.reset(); Files.writeString(fixture.inputs.get(changed), "changed " + changed);
                requireThat(fixture.run("target"), "changedStatus").isEqualTo(0);
                requireThat(fixture.output.toString().lines().toList().getLast(), "compilerOnlySeed").isEqualTo(originalSeed);
            }
            fixture.output.reset(); Files.writeString(fixture.inputs.get(3), "new compiler"); fixture.run("target");
            requireThat(fixture.output.toString().lines().toList().getLast().equals(originalSeed), "compilerPartitionsSeed").isFalse();
        }
    }

    /**
     * An empty applied-patch record keeps the pristine tags, and applied patches partition only the exact tag, so a
     * patched build seeds from pristine outputs without ever matching them exactly.
     * @throws IOException if fixture storage fails
     */
    @Test public void appliedPatchesPartitionOnlyExactTags() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            requireThat(fixture.run("target"), "pristineStatus").isEqualTo(0);
            List<String> pristine = fixture.output.toString().lines().toList();
            Path patches = fixture.root.resolve("patches");
            Files.writeString(patches, "");
            fixture.output.reset();
            requireThat(fixture.run("target", patches), "emptyPatchesStatus").isEqualTo(0);
            requireThat(fixture.output.toString().lines().toList(), "emptyPatchesTags").isEqualTo(pristine);

            Files.writeString(patches, "0123  patches/one.patch\n");
            fixture.output.reset();
            requireThat(fixture.run("target", patches), "patchedStatus").isEqualTo(0);
            List<String> patched = fixture.output.toString().lines().toList();
            requireThat(patched.getFirst(), "patchedExactTag").isNotEqualTo(pristine.getFirst());
            requireThat(patched.getLast(), "sharedSeedTag").isEqualTo(pristine.getLast());
        }
    }

    /**
     * Invalid targets and unreadable inputs return two without publishing partial tags.
     * @throws IOException if fixture storage fails
     */
    @Test public void invalidInputsPublishNoTags() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            for (String target : List.of("", "../target", "target:other", "λ", "target with spaces"))
                requireThat(fixture.run(target), "invalidTarget").isEqualTo(2);
            Files.delete(fixture.inputs.getLast());
            requireThat(fixture.run("target"), "missingInput").isEqualTo(2);
            requireThat(fixture.output.size(), "noPartialOutputs").isEqualTo(0);
        }
    }

    /** Owns independent original recorded bytes and borrowed command output. */
    private static final class Fixture implements AutoCloseable
    {
        /** Owned source root. */ private final Path root;
        /** Exact input files. */ private final List<Path> inputs = new ArrayList<>();
        /** Public command output. */ private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        /** Borrowed output wrapper. */ private final PrintStream out = new PrintStream(output);
        /** Independent diagnostics. */ private final PrintStream err = new PrintStream(new ByteArrayOutputStream());
        /**
         * Writes four independent source records.
         * @throws IOException if storage fails
         */
        private Fixture() throws IOException
        {
            root = Files.createTempDirectory("keys-");
            for (String name : List.of("upstream", "wrappers", "helper", "compiler"))
            { Path file = root.resolve(name); Files.writeString(file, name); inputs.add(file); }
        }
        /**
         * Invokes the actual public command without closing borrowed streams.
         * @param target supplied target name
         * @param extra optional trailing input paths
         * @return public status
         */
        private int run(String target, Path... extra)
        {
            List<String> args = new ArrayList<>(List.of("cargo-cache-keys", target)); inputs.forEach(path -> args.add(path.toString()));
            for (Path path : extra) args.add(path.toString());
            return Main.run(args.toArray(String[]::new), new ByteArrayInputStream(new byte[0]), out, err);
        }
        /**
         * Deletes only the owned fixture.
         * @throws IOException if deletion fails
         */
        @Override public void close() throws IOException
        {
            out.close(); err.close(); for (Path input : inputs) Files.deleteIfExists(input);
            Files.deleteIfExists(root.resolve("patches")); Files.delete(root);
        }
    }
}
