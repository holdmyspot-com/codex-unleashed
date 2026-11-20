package com.holdmyspot.codexunleashed.tooling;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterReader;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;

/** Reads cumulative CPU time for the command process and its current descendants. */
public final class QuietBuildCpu
{
	/** Fixed command for the Unix process CPU listing. */
	private static final List<String> UNIX_COMMAND = List.of("ps", "-A", "-o", "pid=,pgid=,time=");
	/** Fixed query for the Windows process CPU listing. */
	private static final String WINDOWS_QUERY = "Get-CimInstance Win32_Process | Select-Object " +
		"ProcessId,ParentProcessId,KernelModeTime,UserModeTime | ConvertTo-Json -Compress";
	/** Maximum duration allowed for the Unix process listing. */
	private static final Duration UNIX_TIMEOUT = Duration.ofSeconds(60);
	/** Maximum duration allowed for the Windows process listing. */
	private static final Duration WINDOWS_TIMEOUT = Duration.ofSeconds(120);
	/** Windows CPU counter units per second. */
	private static final BigInteger TICKS_PER_SECOND = BigInteger.valueOf(10_000_000);
	/** Decimal digits accepted for process IDs and CPU counters. */
	private static final Pattern UNSIGNED_INTEGER = Pattern.compile("[0-9]+");

	/** Prevents construction of this declaration-only utility. */
	private QuietBuildCpu()
	{
	}

	/** Reads a complete cumulative CPU observation. */
	@FunctionalInterface
	public interface Reader
	{
		/**
		 * Reads cumulative CPU duration by process ID.
		 *
		 * @return immutable CPU snapshot, possibly empty
		 * @throws IOException if process observation fails
		 */
		Map<Long, Duration> read() throws IOException;
	}

	/** Creates observation readers for command invocations. */
	@FunctionalInterface
	public interface ReaderFactory
	{
		/**
		 * Creates a reader bound to the command process and its complete invocation context.
		 *
		 * @param commandPid root process ID
		 * @param context complete process context
		 * @return a reader for subsequent observations
		 */
		Reader create(long commandPid, QuietBuildCapture.Context context);
	}

	/**
	 * Creates a platform reader using the supplied process-capture boundary.
	 *
	 * @param scope invocation scope providing the shared JSON parser
	 * @param access process capture boundary
	 * @return platform reader factory
	 */
	public static ReaderFactory factory(ToolingScope scope, QuietBuildCapture.Access access)
	{
		return (commandPid, context) -> () ->
		{
			boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).
				startsWith("windows");
			List<String> command;
			Duration timeout;
			if (windows)
			{
				command = List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", WINDOWS_QUERY);
				timeout = WINDOWS_TIMEOUT;
			}
			else
			{
				command = UNIX_COMMAND;
				timeout = UNIX_TIMEOUT;
			}
			try (QuietBuildCapture.Captured captured = access.capture(command, context, Optional.of(timeout)))
			{
				if (captured.status() != 0)
					throw new IOException("CPU observation command failed with status " + captured.status() + ": " +
						readText(captured.stderr()));
				if (windows)
					return treeCpu(scope, captured.stdout(), commandPid, context.temporaryDirectory());
				return groupCpu(captured.stdout(), commandPid);
			}
		};
	}

	/**
	 * Selects process CPU rows in the requested Unix process group.
	 *
	 * @param listing command output
	 * @param processGroup requested process group ID
	 * @return immutable cumulative CPU snapshot
	 * @throws IOException if a nonempty row is malformed or cannot be read
	 */
	public static Map<Long, Duration> groupCpu(java.io.Reader listing, long processGroup) throws IOException
	{
		Map<Long, Duration> result = new HashMap<>();
		BufferedReader lines = new BufferedReader(listing);
		while (true)
		{
			String line = lines.readLine();
			if (line == null)
				break;
			String trimmed = line.trim();
			if (trimmed.isEmpty())
				continue;
			String[] fields = trimmed.split("\\s+");
			if (fields.length != 3)
				throw new IOException("Malformed process CPU row: " + trimmed);
			long pid = positiveLong(fields[0], "pid");
			long pgid = nonNegativeLong(fields[1], "process group");
			Duration cpu = parseClock(fields[2]);
			if (pgid == processGroup)
				result.put(pid, cpu);
		}
		return Map.copyOf(result);
	}

	/**
	 * Reads Windows process rows incrementally and returns the requested root and descendants.
	 *
	 * @param scope invocation scope providing the shared JSON parser
	 * @param listing JSON emitted by the fixed Windows process query
	 * @param root root process ID
	 * @param temporaryDirectory directory for bounded-memory process-row storage
	 * @return immutable cumulative CPU snapshot
	 * @throws IOException if JSON, required process fields, storage or input reading fails
	 */
	public static Map<Long, Duration> treeCpu(ToolingScope scope, java.io.Reader listing, long root,
		Path temporaryDirectory)
		throws IOException
	{
		Path records = Files.createTempFile(temporaryDirectory, "quiet-build-cpu-", ".bin");
		try
		{
			writeRows(scope, listing, records);
			Map<Long, Duration> result = new HashMap<>();
			boolean changed;
			do
			{
				changed = false;
				try (DataInputStream input = new DataInputStream(Files.newInputStream(records)))
				{
					while (true)
					{
						long pid;
						try
						{
							pid = input.readLong();
						}
						catch (java.io.EOFException _)
						{
							break;
						}
						long parent = input.readLong();
						Duration cpu = Duration.ofSeconds(input.readLong(), input.readInt());
						if ((pid == root || result.containsKey(parent)) && !result.containsKey(pid))
						{
							result.put(pid, cpu);
							changed = true;
						}
					}
				}
			}
			while (changed);
			return Map.copyOf(result);
		}
		finally
		{
			Files.deleteIfExists(records);
		}
	}

	/**
	 * Streams one JSON object at a time to fixed-width temporary records.
	 *
	 * @param scope invocation scope providing the shared JSON parser
	 * @param listing Windows process JSON
	 * @param records destination file for fixed-width rows
	 * @throws IOException if the JSON or a required process row is malformed or input fails
	 */
	private static void writeRows(ToolingScope scope, java.io.Reader listing, Path records) throws IOException
	{
		try (JsonParser parser = scope.getJsonMapper().createParser(new NonClosingReader(listing));
			DataOutputStream output = new DataOutputStream(Files.newOutputStream(records)))
		{
			JsonToken first = parser.nextToken();
			if (first == JsonToken.START_OBJECT)
				writeRow(readRow(scope, parser), output);
			else if (first == JsonToken.START_ARRAY)
			{
				while (true)
				{
					JsonToken token = parser.nextToken();
					if (token == JsonToken.END_ARRAY)
						break;
					if (token != JsonToken.START_OBJECT)
						throw new IOException("Expected a process object in the Windows process array");
					writeRow(readRow(scope, parser), output);
				}
			}
			else
				throw new IOException("Expected a Windows process object or array");
			if (parser.nextToken() != null)
				throw new IOException("Unexpected trailing content after Windows process JSON");
		}
	}

	/**
	 * Reads just the current object; document-level trailing input is checked after the outer value is consumed.
	 *
	 * @param scope invocation scope providing the shared JSON parser
	 * @param parser parser positioned at an object start
	 * @return the current process object
	 * @throws IOException if the object is malformed
	 */
	private static JsonNode readRow(ToolingScope scope, JsonParser parser) throws IOException
	{
		return scope.getJsonMapper().reader().without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(parser);
	}

	/**
	 * Validates one process object and writes its fixed-width representation.
	 *
	 * @param row current process object
	 * @param output destination for its fixed-width representation
	 * @throws IOException if required process fields are missing or malformed
	 */
	private static void writeRow(JsonNode row, DataOutputStream output) throws IOException
	{
		if (!row.isObject())
			throw new IOException("Windows process row must be an object");
		long pid = requiredNonnegativeInteger(row, "ProcessId");
		// Win32_Process includes PID 0, the system idle process, outside any build tree.
		if (pid == 0)
			return;
		long parent = requiredNonnegativeInteger(row, "ParentProcessId");
		BigInteger kernel = optionalTicks(row, "KernelModeTime");
		BigInteger user = optionalTicks(row, "UserModeTime");
		Duration cpu = durationFromTicks(kernel.add(user));
		output.writeLong(pid);
		output.writeLong(parent);
		output.writeLong(cpu.getSeconds());
		output.writeInt(cpu.getNano());
	}

	/**
	 * Reads a required integral field and enforces its process-ID range.
	 *
	 * @param row current process object
	 * @param name required integer field
	 * @return the validated field value
	 * @throws IOException if the field is missing, malformed or out of range
	 */
	private static long requiredNonnegativeInteger(JsonNode row, String name) throws IOException
	{
		JsonNode value = row.get(name);
		if (value == null || !value.isIntegralNumber())
			throw new IOException("Missing or malformed Windows process field " + name);
		long number;
		try
		{
			number = Long.parseLong(value.asText());
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Invalid Windows process field " + name, exception);
		}
		if (number < 0)
			throw new IOException("Out-of-range Windows process field " + name);
		return number;
	}

	/**
	 * Reads a nullable nonnegative Windows 100-nanosecond counter.
	 *
	 * @param row current process object
	 * @param name counter field
	 * @return counter value, or zero when the field is JSON null
	 * @throws IOException if the field is missing or malformed
	 */
	private static BigInteger optionalTicks(JsonNode row, String name) throws IOException
	{
		JsonNode value = row.get(name);
		if (value == null)
			throw new IOException("Missing Windows process field " + name);
		if (value.isNull())
			return BigInteger.ZERO;
		String text = value.asText();
		if (!UNSIGNED_INTEGER.matcher(text).matches())
			throw new IOException("Malformed Windows process field " + name);
		return new BigInteger(text);
	}

	/**
	 * Converts 100-nanosecond ticks without floating-point rounding.
	 *
	 * @param ticks total nonnegative CPU ticks
	 * @return exact CPU duration
	 * @throws IOException if the duration exceeds the supported range
	 */
	private static Duration durationFromTicks(BigInteger ticks) throws IOException
	{
		BigInteger[] seconds = ticks.divideAndRemainder(TICKS_PER_SECOND);
		try
		{
			return Duration.ofSeconds(seconds[0].longValueExact(), seconds[1].longValueExact() * 100L);
		}
		catch (ArithmeticException exception)
		{
			throw new IOException("Windows process CPU time exceeds the supported duration range", exception);
		}
	}

	/**
	 * Parses the supported ps clock forms into nanosecond precision.
	 *
	 * @param value process CPU clock text
	 * @return exact clock duration
	 * @throws IOException if the clock does not use a supported form
	 */
	private static Duration parseClock(String value) throws IOException
	{
		try
		{
			String[] dayParts = value.split("-", -1);
			if (dayParts.length > 2)
				throw new NumberFormatException();
			long days = 0;
			if (dayParts.length == 2)
				days = parseUnsigned(dayParts[0]);
			String[] clock = dayParts[dayParts.length - 1].split(":", -1);
			if (clock.length != 2 && clock.length != 3 || dayParts.length == 2 && clock.length != 3)
				throw new NumberFormatException();
			long hours = 0;
			if (clock.length == 3)
				hours = parseUnsigned(clock[0]);
			long minutes = parseUnsigned(clock[clock.length - 2]);
			String secondsText = clock[clock.length - 1];
			String[] fraction = secondsText.split("\\.", -1);
			if (fraction.length > 2 || fraction[0].length() != 2 || fraction.length == 2 &&
				(fraction[1].isEmpty() || fraction[1].length() > 9))
				throw new NumberFormatException();
			long seconds = parseUnsigned(fraction[0]);
			if (minutes >= 60 && clock.length == 3 || seconds >= 60 || dayParts.length == 2 && hours >= 24)
				throw new NumberFormatException();
			long total = Math.addExact(Math.multiplyExact(days, 86_400),
				Math.addExact(Math.multiplyExact(hours, 3_600), Math.addExact(Math.multiplyExact(minutes, 60), seconds)));
			int nanos = 0;
			if (fraction.length == 2)
				nanos = Integer.parseInt((fraction[1] + "000000000").substring(0, 9));
			return Duration.ofSeconds(total, nanos);
		}
		catch (NumberFormatException | ArithmeticException exception)
		{
			throw new IOException("Malformed process CPU clock: " + value, exception);
		}
	}

	/**
	 * Reads a positive decimal long.
	 *
	 * @param value decimal text
	 * @param field field name used in an error
	 * @return positive field value
	 * @throws IOException if the value is not positive or cannot be represented
	 */
	private static long positiveLong(String value, String field) throws IOException
	{
		long result = nonNegativeLong(value, field);
		if (result == 0)
			throw new IOException("Invalid " + field + ": " + value);
		return result;
	}

	/**
	 * Reads a nonnegative decimal long.
	 *
	 * @param value decimal text
	 * @param field field name used in an error
	 * @return nonnegative field value
	 * @throws IOException if the value is negative or cannot be represented
	 */
	private static long nonNegativeLong(String value, String field) throws IOException
	{
		try
		{
			return parseUnsigned(value);
		}
		catch (NumberFormatException exception)
		{
			throw new IOException("Invalid " + field + ": " + value, exception);
		}
	}

	/**
	 * Parses a decimal number without signs or whitespace.
	 *
	 * @param value decimal text
	 * @return parsed nonnegative number
	 * @throws NumberFormatException if the text is not a nonnegative long
	 */
	private static long parseUnsigned(String value)
	{
		if (!UNSIGNED_INTEGER.matcher(value).matches())
			throw new NumberFormatException();
		return Long.parseLong(value);
	}

	/**
	 * Reads a captured diagnostic stream for a command failure message.
	 *
	 * @param reader captured diagnostic stream
	 * @return diagnostic text without trailing whitespace
	 * @throws IOException if diagnostic reading fails
	 */
	private static String readText(java.io.Reader reader) throws IOException
	{
		StringBuilder result = new StringBuilder();
		BufferedReader input = new BufferedReader(reader);
		while (true)
		{
			String line = input.readLine();
			if (line == null)
				break;
			result.append(line).append('\n');
		}
		return result.toString().stripTrailing();
	}

	/** Adapts a capture-owned reader so closing a parser does not close the capture's borrowed stream. */
	private static final class NonClosingReader extends FilterReader
	{
		/**
		 * Wraps a capture-owned reader.
		 *
		 * @param reader stream whose lifetime remains with its capture
		 */
		private NonClosingReader(java.io.Reader reader)
		{
			super(reader);
		}

		/** Leaves stream closure to its capture owner. */
		@Override
		public void close()
		{
		}
	}
}
