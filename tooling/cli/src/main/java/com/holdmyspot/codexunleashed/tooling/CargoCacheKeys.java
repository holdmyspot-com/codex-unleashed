package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Derives exact and compatible cache tags from the unchanged ordered recorded input bytes. */
public final class CargoCacheKeys
{
    /** Prevents construction. */
    private CargoCacheKeys() {}

    /**
     * Hashes upstream, wrappers, helper and compiler bytes in that exact order; seeds omit only wrappers.
     * @param arguments target and four recorded input paths
     * @param out borrowed output receiving both tags after every input has been read
     * @param err borrowed diagnostics
     * @return zero on success or two for invalid or unavailable inputs
     */
    static int run(String[] arguments, PrintStream out, PrintStream err)
    {
        if (arguments.length != 5) { err.println("Expected target, upstream, wrappers, helper and compiler paths"); return 2; }
        try
        {
            String target = arguments[0];
            if (!target.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid cache target name");
            byte[][] values = new byte[4][];
            for (int i = 0; i < values.length; ++i) values[i] = Files.readAllBytes(Path.of(arguments[i + 1]));
            MessageDigest exact = digest(); MessageDigest seed = digest();
            for (int i = 0; i < values.length; ++i)
            {
                exact.update(values[i]);
                if (i != 1) seed.update(values[i]);
            }
            // Write LF on every platform: callers append these lines to GITHUB_OUTPUT.
            out.print("tag=cargo-v3-" + target + "-" + HexFormat.of().formatHex(exact.digest()) + "\n");
            out.print("seed_tag=cargo-seed-v1-" + target + "-" + HexFormat.of().formatHex(seed.digest()) + "\n");
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
