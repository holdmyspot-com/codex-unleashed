package com.holdmyspot.codexunleashed.tooling;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Reads cumulative resource counters from an injected Linux procfs root.
 */
public final class LinuxQuietBuildResources implements QuietBuildResources.Reader
{
	/** Root directory containing the procfs counter files. */
	private final Path procfs;
	/** Whether this reader has been closed. */
	private boolean closed;

	/**
	 * Creates a reader for the supplied procfs root.
	 *
	 * @param procfs root directory containing Linux process counter files
	 */
	public LinuxQuietBuildResources(Path procfs)
	{
		requireThat(procfs, "procfs").isNotNull();
		this.procfs = procfs;
	}

	/**
	 * Creates a reader factory bound to the supplied procfs root.
	 *
	 * @param procfs root directory containing Linux process counter files
	 * @return factory that opens readers for this procfs root
	 */
	public static QuietBuildResources.ReaderFactory factory(Path procfs)
	{
		requireThat(procfs, "procfs").isNotNull();
		return _ -> new LinuxQuietBuildResources(procfs);
	}

	@Override
	public QuietBuildResources.LinuxCounters read() throws IOException
	{
		if (this.closed)
			throw new IllegalStateException("Linux resource reader is closed");

		Path statPath = this.procfs.resolve("stat");
		CpuReading cpu = readCpu(statPath);
		Path meminfoPath = this.procfs.resolve("meminfo");
		Map<String, String> memoryFields = readNamedValues(meminfoPath, ':');
		Path vmstatPath = this.procfs.resolve("vmstat");
		Map<String, String> virtualMemoryFields = readNamedValues(vmstatPath, ' ');
		Path memoryPressurePath = this.procfs.resolve("pressure/memory");
		Map<String, BigInteger> memoryPressure = readPressureTotals(memoryPressurePath);
		Path cpuPressurePath = this.procfs.resolve("pressure/cpu");
		Map<String, BigInteger> cpuPressure = readPressureTotals(cpuPressurePath);

		BigInteger memoryTotalBytes = memoryKilobytes(meminfoPath, memoryFields, "MemTotal");
		BigInteger memoryAvailableBytes = memoryKilobytes(meminfoPath, memoryFields, "MemAvailable");
		BigInteger swapTotalBytes = memoryKilobytes(meminfoPath, memoryFields, "SwapTotal");
		BigInteger swapFreeBytes = memoryKilobytes(meminfoPath, memoryFields, "SwapFree");
		BigInteger swapUsedBytes = swapTotalBytes.subtract(swapFreeBytes);
		BigInteger swapInPages = requiredCounter(vmstatPath, virtualMemoryFields, "pswpin");
		BigInteger swapOutPages = requiredCounter(vmstatPath, virtualMemoryFields, "pswpout");
		BigInteger majorFaults = requiredCounter(vmstatPath, virtualMemoryFields, "pgmajfault");

		return new QuietBuildResources.LinuxCounters(cpu.ticks(), cpu.processors(), memoryTotalBytes,
			memoryAvailableBytes, swapUsedBytes, swapInPages, swapOutPages, majorFaults, memoryPressure,
			cpuPressure);
	}

	/**
	 * Releases this reader; repeated calls have no effect.
	 */
	@Override
	public void close()
	{
		this.closed = true;
	}

	/**
	 * Reads aggregate CPU ticks and counts per-processor rows from stat.
	 *
	 * @param statPath Linux stat file
	 * @return aggregate CPU ticks and processor count
	 * @throws IOException if stat is missing, unreadable or contains an invalid CPU counter
	 */
	private static CpuReading readCpu(Path statPath) throws IOException
	{
		Map<QuietBuildResources.LinuxCpuState, BigInteger> ticks = new EnumMap<>(
			QuietBuildResources.LinuxCpuState.class);
		BigInteger processors = BigInteger.ZERO;
		try (BufferedReader input = openReader(statPath, "CPU counters"))
		{
			String line = readLine(input, statPath, "CPU counters");
			if (line == null)
				throw missingCounter(statPath, "aggregate CPU row");

			String trimmedLine = line.strip();
			String[] fields;
			if (trimmedLine.isEmpty())
				fields = new String[0];
			else
				fields = trimmedLine.split("\\s+");
			int cpuFieldCount = Math.min(fields.length, QuietBuildResources.LinuxCpuState.values().length + 1);
			for (int index = 1; index < cpuFieldCount; index = index + 1)
			{
				QuietBuildResources.LinuxCpuState state = QuietBuildResources.LinuxCpuState.values()[index - 1];
				ticks.put(state, parseCounter(statPath, state.name(), fields[index]));
			}

			line = readLine(input, statPath, "processor count");
			while (line != null)
			{
				boolean hasCpuPrefix = line.startsWith("cpu");
				boolean hasNumericCpuIndex = line.length() > 3 && Character.isDigit(line.charAt(3));
				if (hasCpuPrefix && hasNumericCpuIndex)
					processors = processors.add(BigInteger.ONE);
				line = readLine(input, statPath, "processor count");
			}
		}
		return new CpuReading(Map.copyOf(ticks), processors);
	}

	/**
	 * Reads named values, retaining the last row for each name.
	 *
	 * @param path counter file to read
	 * @param separator delimiter used before each value
	 * @return the last value recorded for every parsed name
	 * @throws IOException if the file cannot be read
	 */
	private static Map<String, String> readNamedValues(Path path, char separator) throws IOException
	{
		Map<String, String> values = new HashMap<>();
		String requiredFields;
		if (separator == ':')
			requiredFields = "memory fields";
		else
			requiredFields = "virtual-memory fields";
		try (BufferedReader input = openReader(path, requiredFields))
		{
			String line;
			line = readLine(input, path, requiredFields);
			while (line != null)
			{
				if (separator == ':')
				{
					int delimiter = line.indexOf(separator);
					if (delimiter >= 0)
						values.put(line.substring(0, delimiter).strip(), line.substring(delimiter + 1).strip());
				}
				else if (!line.isBlank())
				{
					String[] fields = line.strip().split("\\s+");
					if (fields.length >= 2)
						values.put(fields[0], fields[1]);
				}
				line = readLine(input, path, requiredFields);
			}
		}
		return values;
	}

	/**
	 * Reads PSI totals keyed by each row's label.
	 *
	 * @param path pressure file to read
	 * @return the last total recorded for each row label
	 * @throws IOException if a row has no total or contains an invalid total
	 */
	private static Map<String, BigInteger> readPressureTotals(Path path) throws IOException
	{
		Map<String, BigInteger> totals = new HashMap<>();
		try (BufferedReader input = openReader(path, "pressure totals"))
		{
			String line;
			line = readLine(input, path, "pressure totals");
			while (line != null)
			{
				if (line.isBlank())
				{
					line = readLine(input, path, "pressure totals");
					continue;
				}
				String[] fields = line.strip().split("\\s+");
				String label = fields[0];
				String total = null;
				for (int index = 1; index < fields.length; index = index + 1)
				{
					if (fields[index].startsWith("total="))
					{
						total = fields[index].substring("total=".length());
						break;
					}
				}
				if (total == null)
					throw missingCounter(path, label + " total");
				totals.put(label, parseCounter(path, label + " total", total));
				line = readLine(input, path, "pressure totals");
			}
		}
		return totals;
	}

	/**
	 * Converts one meminfo field from KiB to bytes.
	 *
	 * @param path meminfo file containing the field
	 * @param values parsed meminfo fields
	 * @param field required field name
	 * @return field value multiplied by 1024
	 * @throws IOException if the field is absent or malformed
	 */
	private static BigInteger memoryKilobytes(Path path, Map<String, String> values, String field) throws IOException
	{
		String raw = firstToken(path, values, field);
		return parseCounter(path, field, raw).multiply(BigInteger.valueOf(1024));
	}

	/**
	 * Reads one required integer counter from named values.
	 *
	 * @param path file containing the counter
	 * @param values parsed values from the file
	 * @param field required counter name
	 * @return parsed counter
	 * @throws IOException if the counter is absent or malformed
	 */
	private static BigInteger requiredCounter(Path path, Map<String, String> values, String field) throws IOException
	{
		return parseCounter(path, field, firstToken(path, values, field));
	}

	/**
	 * Finds the first whitespace-delimited value for a required named field.
	 *
	 * @param path file containing the field
	 * @param values parsed values from the file
	 * @param field required field name
	 * @return first token recorded for the field
	 * @throws IOException if the field is absent
	 */
	private static String firstToken(Path path, Map<String, String> values, String field) throws IOException
	{
		String value = values.get(field);
		if (value == null)
			throw missingCounter(path, field);
		String trimmedValue = value.strip();
		return trimmedValue.split("\\s+")[0];
	}

	/**
	 * Parses a required integer counter without narrowing its value.
	 *
	 * @param path file containing the counter
	 * @param field required counter name
	 * @param value raw integer text
	 * @return exact integer value
	 * @throws IOException if the value is malformed
	 */
	private static BigInteger parseCounter(Path path, String field, String value) throws IOException
	{
		try
		{
			return new BigInteger(value);
		}
		catch (NumberFormatException error)
		{
			throw malformedCounter(path, field, value, error);
		}
	}

	/**
	 * Creates a diagnostic for a missing required counter.
	 *
	 * @param path file expected to contain the counter
	 * @param field missing counter name
	 * @return contextual read failure
	 */
	private static IOException missingCounter(Path path, String field)
	{
		return new IOException("Missing required Linux counter " + field + " in " + path);
	}

	/**
	 * Creates a diagnostic for an invalid required counter value.
	 *
	 * @param path file containing the counter
	 * @param field required counter name
	 * @param value invalid text
	 * @param cause integer parsing failure
	 * @return contextual read failure preserving the parsing cause
	 */
	private static IOException malformedCounter(Path path, String field, String value, NumberFormatException cause)
	{
		String message = "Malformed required Linux counter " + field + " in " + path + ": " + value;
		return new IOException(message, cause);
	}

	/**
	 * Opens a UTF-8 reader that replaces malformed byte sequences.
	 *
	 * @param path counter file to open
	 * @param field required counters read from the file
	 * @return open buffered reader
	 * @throws IOException if the file cannot be opened
	 */
	private static BufferedReader openReader(Path path, String field) throws IOException
	{
		try
		{
			return new BufferedReader(new InputStreamReader(Files.newInputStream(path),
				StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).
					onUnmappableCharacter(CodingErrorAction.REPLACE)));
		}
		catch (IOException error)
		{
			throw new IOException("Failed to open Linux counter file " + path + " for " + field, error);
		}
	}

	/**
	 * Reads one line and adds file and field context to an I/O failure.
	 *
	 * @param input open counter reader
	 * @param path counter file being read
	 * @param field required counters read from the file
	 * @return next line, or null at end of file
	 * @throws IOException if the file cannot be read
	 */
	private static String readLine(BufferedReader input, Path path, String field) throws IOException
	{
		try
		{
			return input.readLine();
		}
		catch (IOException error)
		{
			throw new IOException("Failed to read Linux counter file " + path + " for " + field, error);
		}
	}

	/**
	 * One aggregate CPU row and the processor rows counted from stat.
	 *
	 * @param ticks available aggregate CPU tick counters
	 * @param processors number of per-processor CPU rows
	 */
	private record CpuReading(Map<QuietBuildResources.LinuxCpuState, BigInteger> ticks, BigInteger processors)
	{
	}
}
