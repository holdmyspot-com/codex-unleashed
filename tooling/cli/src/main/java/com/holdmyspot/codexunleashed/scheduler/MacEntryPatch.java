package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Composes a packaged Darwin hook script with the entry-patch functions it calls to redirect a function entry to its
 * native replacement.
 */
public final class MacEntryPatch
{
    /** Packaged Darwin entry-patch functions, placed before each hook script that calls them. */
    private static final String PRELUDE = "mac-entry-patch.js";

    /**
     * Prevents instantiation.
     */
    private MacEntryPatch()
    {
    }

    /**
     * Reads a packaged Darwin hook script preceded by the entry-patch functions, naming the dylib that it loads.
     * @param resource name of the hook script in this package
     * @param module   installed dylib holding the script's native replacements
     * @return entry-patch functions followed by the hook script
     * @throws IOException           if either packaged resource is absent or unreadable
     * @throws IllegalStateException if the hook script does not name its native module exactly once
     */
    public static String withPrelude(String resource, Path module) throws IOException
    {
        return read(PRELUDE) + "\n" + MacNativeModules.bind(resource, read(resource), module);
    }

    /**
     * Reads one packaged script.
     * @param resource name of the script in this package
     * @return the script's UTF-8 text
     * @throws IOException if the resource is absent or unreadable
     */
    private static String read(String resource) throws IOException
    {
        try (InputStream input = MacEntryPatch.class.getResourceAsStream(resource))
        {
            if (input == null) throw new IOException("Packaged macOS hook script is missing: " + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
