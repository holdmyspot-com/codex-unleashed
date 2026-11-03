package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.time.DateTimeException;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * Converts exact schema timestamps between epoch nanoseconds and Java filesystem times.
 */
public final class SourceInputTimes
{
	/** Number of nanoseconds per second for range-boundary checks. */
	private static final long NANOS_PER_SECOND = 1_000_000_000L;
	/** Exact integer conversion factor without signed-long overflow. */
	private static final BigInteger INTEGER_NANOS_PER_SECOND = BigInteger.valueOf(NANOS_PER_SECOND);

	/**
	 * Prevents construction.
	 */
	private SourceInputTimes()
	{
	}

	/**
	 * Converts an instant to exact nanoseconds since the Unix epoch without signed-long overflow.
	 *
	 * @param instant the source timestamp
	 * @return exact epoch nanoseconds
	 */
	public static BigInteger nanos(Instant instant)
	{
		return BigInteger.valueOf(instant.getEpochSecond()).multiply(INTEGER_NANOS_PER_SECOND).
			add(BigInteger.valueOf(instant.getNano()));
	}

	/**
	 * Sets access and modification time to the supplied timestamp without changing creation time.
	 *
	 * @param scope borrowed shared native bindings, kept open until the operation finishes
	 * @param path the file or directory
	 * @param time the required timestamp
	 * @throws IOException if the filesystem update fails
	 */
	public static void setTimes(ToolingScope scope, Path path, FileTime time) throws IOException
	{
		Instant instant = time.toInstant();
		BigInteger nanos = nanos(instant);
		boolean unix = isUnix(path);
		boolean negativeFraction = instant.getEpochSecond() < 0 && instant.getNano() != 0;
		boolean outsideLong = nanos.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0 ||
			nanos.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0;
		if (unix && (negativeFraction || outsideLong))
			scope.getUnixFileTimes().set(path, instant);
		else if (isWindows(path) && (negativeFraction || outsideLong))
			scope.getWindowsFileTimes().set(path, instant);
		else
			Files.getFileAttributeView(path, BasicFileAttributeView.class).setTimes(time, time, null);
	}

	/**
	 * Reads modification time while preserving native timestamp precision beyond signed-long epoch nanoseconds.
	 *
	 * @param scope borrowed shared native bindings, kept open until the operation finishes
	 * @param path the source or ancestor directory
	 * @return its precise native timestamp
	 * @throws IOException if timestamp lookup fails
	 */
	public static Instant lastModified(ToolingScope scope, Path path) throws IOException
	{
		return lastModified(scope, path, new LinkOption[0]);
	}

	/**
	 * Reads exact modification time without changing the caller's symbolic-link query semantics.
	 * The no-follow option also applies to the native precision lookup when the JDK rounds out-of-range times.
	 *
	 * @param scope borrowed shared native bindings, kept open until the operation finishes
	 * @param path the source, directory or symbolic link
	 * @param options no options to follow links, or NOFOLLOW_LINKS to query the link itself
	 * @return its precise native timestamp
	 * @throws IOException if timestamp lookup fails
	 */
	public static Instant lastModified(ToolingScope scope, Path path, LinkOption... options) throws IOException
	{
		Instant result = Files.getLastModifiedTime(path, options).toInstant();
		boolean followLinks = !java.util.Arrays.asList(options).contains(LinkOption.NOFOLLOW_LINKS);
		long seconds = result.getEpochSecond();
		// Include boundary seconds because JDK microsecond rounding can cross the signed-long nanosecond boundary.
		if (seconds <= Long.MIN_VALUE / NANOS_PER_SECOND || seconds >= Long.MAX_VALUE / NANOS_PER_SECOND)
		{
			if (isUnix(path))
				return scope.getUnixFileMetadata().lastModified(path, followLinks);
			if (isWindows(path))
				return scope.getWindowsFileTimes().lastModified(path, followLinks);
		}
		return result;
	}

	/**
	 * Indicates whether the path uses the file provider and a Unix path separator.
	 *
	 * @param path the filesystem path
	 * @return whether the provider uses native Unix paths
	 */
	private static boolean isUnix(Path path)
	{
		return path.getFileSystem().provider().getScheme().equals("file") &&
			path.getFileSystem().getSeparator().equals("/");
	}

	/**
	 * Indicates whether the path uses the file provider and a Windows path separator.
	 *
	 * @param path the filesystem path
	 * @return whether the provider uses native Windows paths
	 */
	private static boolean isWindows(Path path)
	{
		return path.getFileSystem().provider().getScheme().equals("file") &&
			path.getFileSystem().getSeparator().equals("\\");
	}

	/**
	 * Converts stored epoch nanoseconds to a filesystem time, accepting integer values and mapping
	 * boolean {@code false} to zero nanoseconds and {@code true} to one nanosecond.
	 *
	 * @param value the stored timestamp
	 * @return the precise filesystem time
	 * @throws IOException if the value lacks integer semantics or exceeds Java's instant range
	 */
	static FileTime fileTime(JsonNode value) throws IOException
	{
		BigInteger nanos;
		if (value.isIntegralNumber())
			nanos = value.bigIntegerValue();
		else if (value.isBoolean())
		{
			nanos = BigInteger.ZERO;
			if (value.booleanValue())
				nanos = BigInteger.ONE;
		}
		else
			throw new IOException("Source-input timestamp must be integer nanoseconds: " + value);
		BigInteger[] components = nanos.divideAndRemainder(INTEGER_NANOS_PER_SECOND);
		try
		{
			return FileTime.from(Instant.ofEpochSecond(components[0].longValueExact(), components[1].longValueExact()));
		}
		catch (ArithmeticException | DateTimeException failure)
		{
			throw new IOException("Source-input timestamp is outside the supported instant range: " + value, failure);
		}
	}
}
