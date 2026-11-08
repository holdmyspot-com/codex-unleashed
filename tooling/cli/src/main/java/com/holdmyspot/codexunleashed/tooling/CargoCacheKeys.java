package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Derives exact and compatible cache tags from recorded input bytes. */
public final class CargoCacheKeys
{
    /**
     * Version of the restored-state format: the archive layout, ownership record, digest index and source snapshot.
     * <p>
     * The compatible tag hashes this instead of the helper's source, so a helper change that keeps the format still
     * restores earlier state. {@code CargoCacheFormatTest} fails when the format changes without an increment.
     */
    public static final int FORMAT_VERSION = 1;

    /** Longest registry tag the cache stores: the compatible tag plus its part-format and partial-state suffixes. */
    private static final int LONGEST_TAG_LIMIT = 128;
    /** Characters a compatible tag adds around the target: its prefix, separator, key digest and longest suffix. */
    private static final int LONGEST_TAG_OVERHEAD = "cargo-seed-v2-".length() + 1 + 64 + "-parts-v2-partial".length();

    /** Prevents construction. */
    private CargoCacheKeys() {}

    /**
     * Hashes upstream, wrappers, helper and compiler bytes in that exact order for the exact tag, followed by an optional
     * applied-patch record. An empty record leaves the exact tag unchanged.
     * <p>
     * The compatible tag hashes only {@link #FORMAT_VERSION} and the compiler bytes, which include the lockfile, toolchain,
     * target and build command. Every build of any upstream commit, patch set or helper revision with those inputs shares
     * it, so the most recent one seeds the next: Cargo then rebuilds only the crates whose sources differ.
     * @param arguments target, four recorded input paths and an optional applied-patch record path
     * @param out borrowed output receiving both tags after every input has been read
     * @param err borrowed diagnostics
     * @return zero on success or two for invalid or unavailable inputs, or a target so long that a derived registry tag
     *     would exceed 128 characters
     */
    static int run(String[] arguments, PrintStream out, PrintStream err)
    {
        if (arguments.length != 5 && arguments.length != 6)
        {
            err.println("Expected target, upstream, wrappers, helper and compiler paths and an optional applied-patch record");
            return 2;
        }
        try
        {
            String target = arguments[0];
            if (!target.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid cache target name");
            if (target.length() + LONGEST_TAG_OVERHEAD > LONGEST_TAG_LIMIT)
                throw new IllegalArgumentException("Cache target name " + target + " is longer than " +
                    (LONGEST_TAG_LIMIT - LONGEST_TAG_OVERHEAD) + " characters, the most a " + LONGEST_TAG_LIMIT +
                    "-character registry tag allows");
            byte[][] values = new byte[4][];
            for (int i = 0; i < values.length; ++i) values[i] = Files.readAllBytes(Path.of(arguments[i + 1]));
            byte[] patches = new byte[0];
            if (arguments.length == 6) patches = Files.readAllBytes(Path.of(arguments[5]));
            MessageDigest exact = digest(); MessageDigest seed = digest();
            for (byte[] value : values) exact.update(value);
            exact.update(patches);
            seed.update(("cargo-cache-format=" + FORMAT_VERSION + "\n").getBytes(StandardCharsets.UTF_8));
            seed.update(values[3]);
            // Write LF on every platform: callers append these lines to GITHUB_OUTPUT.
            out.print("tag=cargo-v3-" + target + "-" + HexFormat.of().formatHex(exact.digest()) + "\n");
            out.print("seed_tag=cargo-seed-v2-" + target + "-" + HexFormat.of().formatHex(seed.digest()) + "\n");
            return 0;
        }
        catch (IOException | IllegalArgumentException failure) { err.println(failure.getMessage()); return 2; }
    }

    /**
     * Acquires an independent standard digest with no shared mutable algorithm state.
     * @return the required platform SHA-256 implementation
     */
    public static MessageDigest digest()
    {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("Required SHA-256 unavailable", impossible); }
    }
}
