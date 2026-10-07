package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Measures a test filesystem's timestamp range through an independent native writer and reader. */
final class NativeTimestampRange
{
	private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
	private static final String PROBE = "const fs=require('node:fs');" +
		"const seconds=Number(process.argv[2]);fs.utimesSync(process.argv[1],seconds,seconds);" +
		"process.stdout.write(fs.statSync(process.argv[1],{bigint:true}).mtimeNs.toString());";

	/** Prevents construction. */
	private NativeTimestampRange()
	{
	}

	/**
	 * Measures far-future timestamps beyond signed-long epoch nanoseconds through a whole-second native write.
	 * Keeps fractions at native precision when that second is stored; otherwise uses the independently measured boundary.
	 *
	 * @param directory the caller-owned fixture directory
	 * @param requested the test timestamp
	 * @return the expected timestamp within the filesystem's native range
	 * @throws IOException if independent measurement or cleanup fails
	 */
	static Instant expected(Path directory, Instant requested) throws IOException
	{
		Instant precise = requested;
		if (directory.getFileSystem().getSeparator().equals("\\"))
			precise = Instant.ofEpochSecond(requested.getEpochSecond(), requested.getNano() / 100 * 100);
		if (requested.getEpochSecond() <= Long.MAX_VALUE / 1_000_000_000L)
			return precise;
		Path reference = Files.createTempFile(directory, "native-timestamp-range-", ".probe");
		try
		{
			SystemCommands.Result result = SystemCommands.capture(List.of("node", "-e", PROBE,
				reference.toString(), Long.toString(requested.getEpochSecond())), directory, directory, Map.of());
			if (result.status() != 0)
				throw new IOException("Native timestamp measurement failed: " + result.stderr());
			BigInteger actual = new BigInteger(result.stdout());
			BigInteger required = BigInteger.valueOf(requested.getEpochSecond()).multiply(NANOS_PER_SECOND);
			if (actual.equals(required))
				return precise;
			BigInteger[] components = actual.divideAndRemainder(NANOS_PER_SECOND);
			return Instant.ofEpochSecond(components[0].longValueExact(), components[1].longValueExact());
		}
		finally
		{
			Files.delete(reference);
		}
	}
}
