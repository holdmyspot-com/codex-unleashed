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
	private static final String WINDOWS_READ = "$item=Get-Item -LiteralPath $env:TIMESTAMP_FILE;" +
		"[Console]::WriteLine(([bigint]$item.LastWriteTimeUtc.Ticks-621355968000000000)*100);" +
		"[Console]::WriteLine(([bigint]$item.LastAccessTimeUtc.Ticks-621355968000000000)*100);";
	private static final String WINDOWS_WRITE = "$value=[DateTime]::UnixEpoch.AddTicks(" +
		"[long]$env:TIMESTAMP_SECONDS*10000000);" +
		"[IO.File]::SetLastWriteTimeUtc($env:TIMESTAMP_FILE,$value);";

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
			SystemCommands.Result result;
			if (directory.getFileSystem().getSeparator().equals("\\"))
			{
				result = SystemCommands.capture(List.of("pwsh", "-NoProfile", "-NonInteractive", "-Command",
					WINDOWS_WRITE + WINDOWS_READ), directory, directory, Map.of("TIMESTAMP_FILE", reference.toString(),
					"TIMESTAMP_SECONDS", Long.toString(requested.getEpochSecond())));
			}
			else
				result = SystemCommands.capture(List.of("node", "-e", PROBE,
					reference.toString(), Long.toString(requested.getEpochSecond())), directory, directory, Map.of());
			if (result.status() != 0)
				throw new IOException("Native timestamp measurement failed: " + result.stderr());
			BigInteger actual = new BigInteger(result.stdout().lines().findFirst().orElseThrow());
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

	/**
	 * Reads modification and access times through a native consumer independent of Java metadata conversion.
	 *
	 * @param file the file to inspect
	 * @param temporary the caller-owned capture parent
	 * @return the native nanosecond values, modification first
	 * @throws IOException if the native reader or capture fails
	 */
	static List<String> inspect(Path file, Path temporary) throws IOException
	{
		SystemCommands.Result result;
		if (file.getFileSystem().getSeparator().equals("\\"))
			result = SystemCommands.capture(List.of("pwsh", "-NoProfile", "-NonInteractive", "-Command", WINDOWS_READ),
				file.getParent(), temporary, Map.of("TIMESTAMP_FILE", file.toString()));
		else
			result = SystemCommands.capture(List.of("node", "-e", "const fs=require('node:fs');" +
				"const value=fs.statSync(process.argv[1],{bigint:true});" +
				"process.stdout.write(value.mtimeNs+'\\n'+value.atimeNs+'\\n');", file.toString()),
				file.getParent(), temporary, Map.of());
		if (result.status() != 0)
			throw new IOException("Native timestamp inspection failed: " + result.stderr());
		return result.stdout().lines().toList();
	}
}
