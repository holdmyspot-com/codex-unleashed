package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.QuietBuildCapture;
import com.holdmyspot.codexunleashed.tooling.QuietBuildResources;
import com.holdmyspot.codexunleashed.tooling.QuietBuildResources.LinuxCounters;
import com.holdmyspot.codexunleashed.tooling.QuietBuildResources.LinuxCpuState;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies Linux procfs counter interpretation and reader lifecycle.
 */
public final class LinuxQuietBuildResourcesTest
{
	/** Creates the Linux reader tests. */
	public LinuxQuietBuildResourcesTest()
	{
	}

	/**
	 * Reads the frozen Linux procfs fixture into the expected typed counters.
	 *
	 * @throws IOException if test fixtures cannot be created or read
	 */
	@Test
	public void readsLinuxCounters() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			LinuxCounters counters = (LinuxCounters) fixture.reader.read();
			requireThat(counters.cpuTicks(), "cpuTicks").isEqualTo(Map.of(LinuxCpuState.USER, BigInteger.TEN,
				LinuxCpuState.NICE, BigInteger.ONE, LinuxCpuState.SYSTEM, BigInteger.valueOf(20),
				LinuxCpuState.IDLE, BigInteger.valueOf(300), LinuxCpuState.IOWAIT, BigInteger.valueOf(4),
				LinuxCpuState.IRQ, BigInteger.valueOf(5), LinuxCpuState.SOFTIRQ, BigInteger.valueOf(6),
				LinuxCpuState.STEAL, BigInteger.valueOf(7)));
			requireThat(counters.processors(), "processors").isEqualTo(BigInteger.ONE);
			requireThat(counters.memoryTotalBytes(), "memoryTotalBytes").isEqualTo(BigInteger.valueOf(16_384_000));
			requireThat(counters.memoryAvailableBytes(), "memoryAvailableBytes").isEqualTo(
				BigInteger.valueOf(8_192_000));
			requireThat(counters.swapUsedBytes(), "swapUsedBytes").isEqualTo(BigInteger.valueOf(1_024_000));
			requireThat(counters.swapInPages(), "swapInPages").isEqualTo(BigInteger.valueOf(11));
			requireThat(counters.swapOutPages(), "swapOutPages").isEqualTo(BigInteger.valueOf(12));
			requireThat(counters.majorFaults(), "majorFaults").isEqualTo(BigInteger.valueOf(9));
			requireThat(counters.memoryStallMicroseconds(), "memoryStalls").isEqualTo(
				Map.of("some", BigInteger.valueOf(500), "full", BigInteger.valueOf(200)));
			requireThat(counters.cpuStallMicroseconds(), "cpuStalls").isEqualTo(
				Map.of("some", BigInteger.valueOf(700)));
		}
	}

	/**
	 * Retains wide CPU values, shorter CPU rows and a negative swap difference.
	 *
	 * @throws IOException if test fixtures cannot be created or read
	 */
	@Test
	public void keepsWideAndSignedCounters() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			String wideTicks = "1208925819614629174706176";
			Files.writeString(fixture.procfs.resolve("stat"), "cpu " + wideTicks + " 2\ncpu0 1 1\n",
				StandardCharsets.UTF_8);
			String memory = Files.readString(fixture.procfs.resolve("meminfo"), StandardCharsets.UTF_8).
				replace("SwapTotal:       4000 kB", "SwapTotal: 1000 kB").
				replace("SwapFree:        3000 kB", "SwapFree: 2000 kB");
			Files.writeString(fixture.procfs.resolve("meminfo"), memory, StandardCharsets.UTF_8);

			LinuxCounters counters = (LinuxCounters) fixture.reader.read();
			requireThat(counters.cpuTicks(), "cpuTicks").isEqualTo(Map.of(LinuxCpuState.USER,
				new BigInteger(wideTicks), LinuxCpuState.NICE, BigInteger.TWO));
			requireThat(counters.swapUsedBytes(), "swapUsedBytes").isEqualTo(BigInteger.valueOf(-1_024_000));
			requireThat(counters.processors(), "processors").isEqualTo(BigInteger.ONE);

			Files.writeString(fixture.procfs.resolve("stat"),
				"cpu 1 2 3 4 5 6 7 8 900 1000\ncpu0 1 1 1 1 1 1 1 1 0 0\n",
				StandardCharsets.UTF_8);
			LinuxCounters extraCpuFields = (LinuxCounters) fixture.reader.read();
			requireThat(extraCpuFields.cpuTicks(), "cpuTicks").isEqualTo(Map.of(LinuxCpuState.USER, BigInteger.ONE,
				LinuxCpuState.NICE, BigInteger.TWO, LinuxCpuState.SYSTEM, BigInteger.valueOf(3),
				LinuxCpuState.IDLE, BigInteger.valueOf(4), LinuxCpuState.IOWAIT, BigInteger.valueOf(5),
				LinuxCpuState.IRQ, BigInteger.valueOf(6), LinuxCpuState.SOFTIRQ, BigInteger.valueOf(7),
				LinuxCpuState.STEAL, BigInteger.valueOf(8)));
		}
	}

	/**
	 * Keeps arbitrary PSI labels and the last value for duplicate labels.
	 *
	 * @throws IOException if test fixtures cannot be created or read
	 */
	@Test
	public void preservesPressureLabels() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			ByteArrayOutputStream memoryPressure = new ByteArrayOutputStream();
			memoryPressure.write("arbitrary avg10=".getBytes(StandardCharsets.UTF_8));
			memoryPressure.write(0xff);
			memoryPressure.write(" total=3 total=4\narbitrary avg10=0 total=9\n".getBytes(StandardCharsets.UTF_8));
			Files.write(fixture.procfs.resolve("pressure/memory"), memoryPressure.toByteArray());
			Files.writeString(fixture.procfs.resolve("pressure/cpu"), "maintenance total=8\n",
				StandardCharsets.UTF_8);

			LinuxCounters counters = (LinuxCounters) fixture.reader.read();
			requireThat(counters.memoryStallMicroseconds(), "memoryStalls").isEqualTo(
				Map.of("arbitrary", BigInteger.valueOf(9)));
			requireThat(counters.cpuStallMicroseconds(), "cpuStalls").isEqualTo(
				Map.of("maintenance", BigInteger.valueOf(8)));
		}
	}

	/**
	 * Rejects missing required memory fields and malformed pressure totals with file context.
	 *
	 * @throws IOException if test fixtures cannot be created or read
	 */
	@Test
	public void rejectsMissingRequiredCounters() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			String memory = Files.readString(fixture.procfs.resolve("meminfo"), StandardCharsets.UTF_8).
				replaceFirst("MemAvailable:    8000 kB\\R", "");
			Files.writeString(fixture.procfs.resolve("meminfo"), memory, StandardCharsets.UTF_8);
			IOException missingMemory = readFailure(fixture);
			requireThat(missingMemory, "missingMemory").isNotNull();
			requireThat(missingMemory.getMessage(), "missingMemory").contains("meminfo");
			requireThat(missingMemory.getMessage(), "missingMemory").contains("MemAvailable");

			Files.copy(fixture.originalMeminfo, fixture.procfs.resolve("meminfo"), StandardCopyOption.REPLACE_EXISTING);
			Files.writeString(fixture.procfs.resolve("pressure/memory"), "some total=invalid\n",
				StandardCharsets.UTF_8);
			IOException malformedPressure = readFailure(fixture);
			requireThat(malformedPressure, "malformedPressure").isNotNull();
			requireThat(malformedPressure.getMessage(), "malformedPressure").contains(
				fixture.procfs.resolve("pressure/memory").toString());
			requireThat(malformedPressure.getMessage(), "malformedPressure").contains("some");
			requireThat(malformedPressure.getCause().getClass().getName(), "malformedPressureCause").
				isEqualTo("java.lang.NumberFormatException");

			Files.writeString(fixture.procfs.resolve("pressure/memory"), "some total=1\n",
				StandardCharsets.UTF_8);
			Files.delete(fixture.procfs.resolve("pressure/cpu"));
			IOException missingFile = readFailure(fixture);
			requireThat(missingFile, "missingFile").isNotNull();
			requireThat(missingFile.getMessage(), "missingFile").contains(
				fixture.procfs.resolve("pressure/cpu").toString());
			requireThat(missingFile.getMessage(), "missingFile").contains("pressure totals");
			requireThat(missingFile.getCause().getClass().getName(), "missingFileCause").
				isEqualTo("java.nio.file.NoSuchFileException");
		}
	}

	/**
	 * Allows repeated close calls and rejects reads after the reader closes.
	 *
	 * @throws IOException if test fixtures cannot be created
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void closesReaderOnce() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.reader.close();
			fixture.reader.close();
			fixture.reader.read();
		}
	}

	/**
	 * Reads once and returns the diagnostic from a required-counter failure.
	 *
	 * @param fixture Linux reader fixture to query
		 * @return failure, or {@code null} when the reader accepted the fixture
	 * @throws IOException if reading fails for a checked reason
	 */
	private static IOException readFailure(Fixture fixture) throws IOException
	{
		try
		{
			fixture.reader.read();
		}
		catch (IOException e)
		{
			return e;
		}
		return null;
	}

	/**
	 * Removes a fixture directory and its contents.
	 *
	 * @param root fixture-owned directory to remove
	 * @throws IOException if a fixture path cannot be removed
	 */
	private static void deleteTree(Path root) throws IOException
	{
		Files.walkFileTree(root, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException
			{
				if (error != null)
					throw error;
				Files.delete(directory);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/**
	 * Owns one procfs fixture, its reader and its private child context.
	 */
	private static final class Fixture implements AutoCloseable
	{
		/** Directory owned by this test fixture. */
		private final Path temporaryDirectory;
		/** Procfs files supplied to the Linux resource reader. */
		private final Path procfs;
		/** Original memory fixture used to restore valid input. */
		private final Path originalMeminfo;
		/** Reader opened with this fixture's context. */
		private final QuietBuildResources.Reader reader;

		/**
		 * Creates one isolated Linux reader fixture.
		 *
		 * @param temporaryDirectory fixture-owned temporary directory
		 * @param procfs procfs files supplied to the reader
		 * @param originalMeminfo unmodified memory input for failure variants
		 * @param reader reader opened with the fixture's child context
		 */
		private Fixture(Path temporaryDirectory, Path procfs, Path originalMeminfo,
			QuietBuildResources.Reader reader)
		{
			this.temporaryDirectory = temporaryDirectory;
			this.procfs = procfs;
			this.originalMeminfo = originalMeminfo;
			this.reader = reader;
		}

		/**
		 * Creates procfs files from the frozen fixture and opens a reader for them.
		 *
		 * @return owned fixture for one test method
		 * @throws IOException if the test context or fixture files cannot be prepared
		 */
		private static Fixture create() throws IOException
		{
			JsonNode sourceContext = readTestContext();
			Path testTemporaryRoot = Path.of(sourceContext.get("temporary_directory").asText());
			Path temporaryDirectory = Files.createTempDirectory(testTemporaryRoot, "linux-resource-test-");
			try
			{
				Path procfs = temporaryDirectory.resolve("procfs");
				Files.createDirectories(procfs.resolve("pressure"));
				copyFixture("stat.txt", procfs.resolve("stat"));
				copyFixture("meminfo.txt", procfs.resolve("meminfo"));
				copyFixture("vmstat.txt", procfs.resolve("vmstat"));
				copyFixture("memory-pressure.txt", procfs.resolve("pressure/memory"));
				copyFixture("cpu-pressure.txt", procfs.resolve("pressure/cpu"));
				Path originalMeminfo = temporaryDirectory.resolve("meminfo-original.txt");
				Files.copy(procfs.resolve("meminfo"), originalMeminfo);
				QuietBuildCapture.Context context = createContext(sourceContext, temporaryDirectory);
				QuietBuildResources.Reader reader = com.holdmyspot.codexunleashed.tooling.
					LinuxQuietBuildResources.factory(procfs).open(context);
				return new Fixture(temporaryDirectory, procfs, originalMeminfo, reader);
			}
			catch (IOException | RuntimeException error)
			{
				deleteTree(temporaryDirectory);
				throw error;
			}
		}

		/**
		 * Reads the explicit runner context file selected by the test system property.
		 *
		 * @return parsed test-runner context
		 * @throws IOException if the context file cannot be read or parsed
		 */
		private static JsonNode readTestContext() throws IOException
		{
			String contextProperty = System.getProperty("quiet.build.test.context");
			requireThat(contextProperty, "contextProperty").isNotNull();
			JsonNode context;
			try (BufferedReader input = Files.newBufferedReader(Path.of(contextProperty), StandardCharsets.UTF_8))
			{
				context = JsonMapper.builder().build().readTree(input);
			}
			requireThat(context.isObject(), "contextIsObject").isTrue();
			requireThat(context.size(), "contextSize").isEqualTo(4);
			requireThat(context.get("format_version").intValue(), "contextFormatVersion").isEqualTo(1);
			return context;
		}

		/**
		 * Creates a private child context whose temporary variables point to this fixture.
		 *
		 * @param sourceContext runner-captured parent context
		 * @param temporaryDirectory this test's private temporary directory
		 * @return immutable child context for the reader factory
		 */
		private static QuietBuildCapture.Context createContext(JsonNode sourceContext, Path temporaryDirectory)
		{
			Map<String, String> environment = new HashMap<>();
			for (Map.Entry<String, JsonNode> entry : sourceContext.get("environment").properties())
			{
				requireThat(entry.getValue().isTextual(), "environmentValueIsText").isTrue();
				environment.put(entry.getKey(), entry.getValue().textValue());
			}
			environment.put("TMPDIR", temporaryDirectory.toString());
			environment.put("TMP", temporaryDirectory.toString());
			environment.put("TEMP", temporaryDirectory.toString());
			Path workingDirectory = Path.of(sourceContext.get("working_directory").asText());
			return new QuietBuildCapture.Context(workingDirectory, temporaryDirectory, environment);
		}

		/**
		 * Copies one checked-in fixture resource into the test-owned procfs tree.
		 *
		 * @param name resource filename within the Linux fixture package
		 * @param destination path in the isolated procfs tree
		 * @throws IOException if the resource cannot be copied
		 */
		private static void copyFixture(String name, Path destination) throws IOException
		{
			String resource = "/com/holdmyspot/codexunleashed/tooling/test/linux-resources/" + name;
			try (InputStream input = LinuxQuietBuildResourcesTest.class.getResourceAsStream(resource))
			{
				requireThat(input, "fixtureInput").isNotNull();
				Files.copy(input, destination);
			}
		}

		/**
		 * Closes the reader and removes this test's temporary procfs tree.
		 *
		 * @throws IOException if reader or fixture cleanup fails
		 */
		@Override
		public void close() throws IOException
		{
			try
			{
				this.reader.close();
			}
			finally
			{
				deleteTree(this.temporaryDirectory);
			}
		}
	}
}
