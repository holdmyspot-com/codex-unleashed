package com.holdmyspot.codexunleashed.scheduler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Retains successful completed compiler sampled-demand profiles in an explicitly supplied owned directory.
 * Callers retain that directory across invocations and remove it when its history is no longer useful.
 * Missing or corrupt history yields unknown demand. Profiles are best-effort predictions, not exact future peaks.
 * Concurrent readers see only complete immutable records; concurrent writers never replace one another's samples.
 */
public final class CargoMemoryHistory
{
    /** Version of the private completed-sample record and its eligibility semantics; version 2 added the input size. */
    private static final int FORMAT = 2;
    /** Final completed-sample file extension; temporary files never match reader discovery. */
    private static final String EXTENSION = ".profile";
    /** Caller-owned directory shared by equivalent compiler invocations. */
    private final Path directory;

    /**
     * Retains a caller-owned history boundary without creating it or reading history.
     * @param directory absolute normalized history directory, exclusively controlled by the invocation owner
     * @throws NullPointerException if {@code directory} is null
     * @throws IllegalArgumentException if {@code directory} is relative or not normalized
     */
    public CargoMemoryHistory(Path directory)
    {
        this.directory = Objects.requireNonNull(directory, "directory");
        if (!directory.isAbsolute() || !directory.equals(directory.normalize()))
            throw new IllegalArgumentException("History directory must be absolute and normalized");
    }

    /**
     * Reads the greatest successful completed sampled demand for matching work.
     * @param identity lowercase SHA-256 work identity
     * @return greatest observed demand in bytes, or null when history is missing, unreadable or corrupt
     * @throws IllegalArgumentException if {@code identity} is not lowercase SHA-256 text
     */
    public Long estimate(String identity)
    {
        requireIdentity(identity);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return null;
        Long greatest = null;
        try (DirectoryStream<Path> records = Files.newDirectoryStream(directory, identity + "-*" + EXTENSION))
        {
            for (Path record : records)
            {
                Sample sample;
                try { sample = read(record); }
                catch (NoSuchFileException pruned)
                {
                    // A concurrent publisher removed this smaller profile after listing.
                    continue;
                }
                if (sample == null || !sample.identity().equals(identity)) return null;
                greatest = greatest == null ? sample.observedBytes() : Math.max(greatest, sample.observedBytes());
            }
            return greatest;
        }
        catch (IOException | DirectoryIteratorException failure) { return null; }
    }

    /**
     * Reads every valid profile, skipping unreadable or corrupt ones.
     * @return the profiles in no particular order; empty when the directory is missing or unreadable
     */
    public List<Sample> samples()
    {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<Sample> samples = new ArrayList<>();
        try (DirectoryStream<Path> records = Files.newDirectoryStream(directory, "*" + EXTENSION))
        {
            for (Path record : records)
            {
                try
                {
                    Sample sample = read(record);
                    if (sample != null) samples.add(sample);
                }
                catch (IOException failure)
                {
                    // A profile removed or damaged while listing supplies no sample, like a corrupt one.
                }
            }
        }
        catch (IOException | DirectoryIteratorException failure)
        {
            // An unreadable directory supplies only the profiles read before the failure.
        }
        return List.copyOf(samples);
    }

    /**
     * Publishes one successful complete attempt's maximum sampled residency, with no input size.
     * @param identity lowercase SHA-256 work identity
     * @param observedBytes positive maximum tree residency from accepted complete measurement batches
     * @throws IOException if the owned directory or atomic publication is unavailable
     * @throws IllegalArgumentException if {@code identity} is invalid or {@code observedBytes} is not positive
     * @see #record(String, long, long)
     */
    public void record(String identity, long observedBytes) throws IOException
    {
        record(identity, 0, observedBytes);
    }

    /**
     * Publishes one successful complete attempt's maximum sampled residency as an immutable profile, then removes
     * that identity's profiles whose residency is smaller or equal, so history stays bounded while the greatest
     * observation survives. Publishes nothing when a valid profile of that identity already has an equal or greater
     * residency. Concurrent publishers retain independent records, so one cannot remove another's larger
     * observation. The directory must already exist under the caller's exclusive control; this operation creates no
     * global cache.
     * @param identity lowercase SHA-256 work identity
     * @param inputBytes nonnegative size of the inputs the work read, or zero when unknown
     * @param observedBytes positive maximum tree residency from accepted complete measurement batches
     * @throws IOException if the owned directory or atomic publication is unavailable
     * @throws IllegalArgumentException if {@code identity} is invalid, {@code inputBytes} is negative or
     * {@code observedBytes} is not positive
     */
    public void record(String identity, long inputBytes, long observedBytes) throws IOException
    {
        requireIdentity(identity);
        if (inputBytes < 0) throw new IllegalArgumentException("Input size may not be negative");
        if (observedBytes <= 0) throw new IllegalArgumentException("Completed observed residency must be positive");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Owned compiler history directory is unavailable: " + directory);
        Long greatest = estimate(identity);
        if (greatest != null && greatest >= observedBytes) return;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes))
        {
            output.writeInt(FORMAT);
            output.writeUTF(identity);
            output.writeLong(inputBytes);
            output.writeLong(observedBytes);
        }
        byte[] payload = bytes.toByteArray();
        Path temporary = Files.createTempFile(directory, ".pending-", ".profile-part");
        Path target = directory.resolve(identity + "-" + UUID.randomUUID() + EXTENSION);
        try
        {
            try (OutputStream output = Files.newOutputStream(temporary))
            {
                output.write(payload);
                output.write(digest(payload));
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        }
        finally { Files.deleteIfExists(temporary); }
        try (DirectoryStream<Path> records = Files.newDirectoryStream(directory, identity + "-*" + EXTENSION))
        {
            for (Path record : records)
            {
                if (record.equals(target)) continue;
                Sample sample = read(record);
                if (sample != null && sample.observedBytes() <= observedBytes) Files.deleteIfExists(record);
            }
        }
        catch (IOException | DirectoryIteratorException failure)
        {
            // Pruning only bounds growth; the new profile is already published and every retained one stays valid.
        }
    }

    /**
     * Reads one profile.
     * @param record the profile file
     * @return the profile, or null if it is not a regular file of the current format with a valid checksum
     * @throws IOException if the file cannot be read
     */
    private static Sample read(Path record) throws IOException
    {
        if (!Files.isRegularFile(record, LinkOption.NOFOLLOW_LINKS) || Files.size(record) > 1024) return null;
        byte[] bytes = Files.readAllBytes(record);
        if (bytes.length < 32) return null;
        byte[] payload = Arrays.copyOf(bytes, bytes.length - 32);
        byte[] checksum = Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length);
        if (!MessageDigest.isEqual(checksum, digest(payload))) return null;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload)))
        {
            if (input.readInt() != FORMAT) return null;
            String identity = input.readUTF();
            long inputBytes = input.readLong();
            long observed = input.readLong();
            if (inputBytes < 0 || observed <= 0 || input.read() != -1) return null;
            if (!record.getFileName().toString().startsWith(identity + "-")) return null;
            return new Sample(identity, inputBytes, observed);
        }
    }

    /**
     * One completed profile.
     * @param identity lowercase SHA-256 work identity
     * @param inputBytes nonnegative size of the inputs the work read, or zero when unknown
     * @param observedBytes positive maximum sampled tree residency
     */
    public record Sample(String identity, long inputBytes, long observedBytes) {}

    /**
     * Rejects work keys that could escape the owned storage directory.
     * @param identity lowercase SHA-256 text
     * @throws IllegalArgumentException if {@code identity} has another length, case or encoding
     */
    private static void requireIdentity(String identity)
    {
        Objects.requireNonNull(identity, "identity");
        if (identity.length() != 64 || !identity.equals(identity.toLowerCase(java.util.Locale.ROOT)))
            throw new IllegalArgumentException("Work identity must be lowercase SHA-256 text");
        HexFormat.of().parseHex(identity);
    }

    /**
     * Hashes one private record payload using the mandatory JDK SHA-256 implementation.
     * @param payload complete versioned record bytes
     * @return 32-byte integrity checksum
     */
    private static byte[] digest(byte[] payload)
    {
        try { return MessageDigest.getInstance("SHA-256").digest(payload); }
        catch (NoSuchAlgorithmException failure) { throw new ExceptionInInitializerError(failure); }
    }
}
