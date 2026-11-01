package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Retains successful complete Bazel attempt measurements in an explicitly owned history directory.
 * Profiles predict sampled whole-containment demand, not exact future peaks. Missing, unreadable or corrupt matching
 * history means unknown demand. Independent atomic records preserve concurrent writers' greatest observation.
 * A versioned storage-key domain isolates these observations from Cargo even if a caller supplies the same directory.
 */
final class BazelMemoryHistory
{
    /** Versioned Bazel eligibility domain, independent of the shared immutable record storage format. */
    private static final String DOMAIN = "bazel-memory-history-v1:";
    /** Shared atomic positive successful-sample storage, borrowed through this Bazel-specific key domain. */
    private final CargoMemoryHistory history;

    /**
     * Retains an explicitly owned history boundary without creating it or accessing files.
     * @param directory absolute normalized caller-owned directory, retained across invocations
     * @throws NullPointerException if {@code directory} is null
     * @throws IllegalArgumentException if {@code directory} is relative or unnormalized
     */
    BazelMemoryHistory(Path directory)
    {
        this.history = new CargoMemoryHistory(directory);
    }

    /**
     * Reads the greatest accepted successful completed observation for this exact work identity.
     * @param identity lowercase SHA-256 Bazel work identity
     * @return greatest observed sampled whole-containment demand in bytes, or null for missing/unreadable/corrupt history
     * @throws NullPointerException if {@code identity} is null
     * @throws IllegalArgumentException if {@code identity} is not lowercase SHA-256 text
     */
    Long estimate(String identity)
    {
        return history.estimate(storageKey(identity));
    }

    /**
     * Atomically publishes an accepted successful complete attempt's positive maximum sampled whole-containment demand.
     * The caller supplies only complete accepted batches from the authenticated final successful attempt; aborted,
     * cancelled, failed or partial observations are ineligible. Existing records remain unchanged on publication failure.
     * @param identity lowercase SHA-256 Bazel work identity
     * @param observedBytes positive accepted maximum sampled whole-containment residency in bytes
     * @throws NullPointerException if {@code identity} is null
     * @throws IllegalArgumentException if {@code identity} is invalid or {@code observedBytes} is not positive
     * @throws IOException if the existing owned directory or atomic publication is unavailable
     */
    void record(String identity, long observedBytes) throws IOException
    {
        history.record(storageKey(identity), observedBytes);
    }

    /**
     * Maps validated content identity into the Bazel-specific persistent eligibility domain.
     * @param identity lowercase SHA-256 content identity
     * @return lowercase SHA-256 record key isolated from raw Cargo keys
     * @throws NullPointerException if {@code identity} is null
     * @throws IllegalArgumentException if {@code identity} is not lowercase SHA-256 text
     */
    private static String storageKey(String identity)
    {
        Objects.requireNonNull(identity, "identity");
        if (identity.length() != 64 || !identity.equals(identity.toLowerCase(java.util.Locale.ROOT)))
            throw new IllegalArgumentException("Bazel identity must be lowercase SHA-256 text");
        HexFormat.of().parseHex(identity);
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((DOMAIN + identity).getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException failure) { throw new ExceptionInInitializerError(failure); }
    }
}
