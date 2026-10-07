package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.time.DateTimeException;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

/** Converts exact schema timestamps between epoch nanoseconds and Java filesystem times. */
final class SourceInputTimes
{
	private static final long NANOS_PER_SECOND = 1_000_000_000L;
	private static final BigInteger INTEGER_NANOS_PER_SECOND = BigInteger.valueOf(NANOS_PER_SECOND);

	/** Prevents construction. */
	private SourceInputTimes()
	{
	}

	/**
	 * Preserves nanoseconds without signed-long epoch conversion overflow.
	 *
	 * @param instant the source timestamp
	 * @return exact epoch nanoseconds
	 */
	static BigInteger nanos(Instant instant)
	{
		return BigInteger.valueOf(instant.getEpochSecond()).multiply(INTEGER_NANOS_PER_SECOND).
			add(BigInteger.valueOf(instant.getNano()));
	}

	/**
	 * Preserves access and modification timestamps through the Unix JDK conversion defect.
	 *
	 * @param path the file or directory
	 * @param time the required timestamp
	 * @throws IOException if the filesystem update fails
	 */
	static void setTimes(Path path, FileTime time) throws IOException
	{
		Instant instant = time.toInstant();
		BigInteger nanos = nanos(instant);
		boolean unix = isUnix(path);
		boolean negativeFraction = instant.getEpochSecond() < 0 && instant.getNano() != 0;
		boolean outsideLong = nanos.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0 ||
			nanos.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0;
		if (unix && (negativeFraction || outsideLong))
			UnixFileTimes.set(path, instant);
		else
			Files.getFileAttributeView(path, BasicFileAttributeView.class).setTimes(time, time, null);
	}

	/**
	 * Reads exact modification time through the Unix JDK overflow conversion defect.
	 *
	 * @param path the source or ancestor directory
	 * @return its precise native timestamp
	 * @throws IOException if timestamp lookup fails
	 */
	static Instant lastModified(Path path) throws IOException
	{
		Instant result = Files.getLastModifiedTime(path).toInstant();
		long seconds = result.getEpochSecond();
		// Include boundary seconds because JDK microsecond rounding can cross the signed-long nanosecond boundary.
		if (isUnix(path) && (seconds <= Long.MIN_VALUE / NANOS_PER_SECOND ||
			seconds >= Long.MAX_VALUE / NANOS_PER_SECOND))
			return UnixFileMetadata.lastModified(path);
		return result;
	}

	/**
	 * Recognizes local Unix paths without applying native operations to another provider.
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
	 * Accepts stored integer nanoseconds, including retained boolean integer values.
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
