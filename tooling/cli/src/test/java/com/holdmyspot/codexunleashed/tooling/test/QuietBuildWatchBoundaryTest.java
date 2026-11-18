package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.QuietBuildCapture;
import com.holdmyspot.codexunleashed.tooling.QuietBuildCommand;
import com.holdmyspot.codexunleashed.tooling.QuietBuildDump;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.testng.SkipException;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises the production watchdog composition through real Linux process and packaged-dump boundaries.
 */
public final class QuietBuildWatchBoundaryTest
{
	/**
	 * Creates the production-boundary test class.
	 */
	public QuietBuildWatchBoundaryTest()
	{
	}

	/**
	 * Returns the real child's status after its output and confirms the process has been reaped.
	 *
	 * @throws IOException if fixture setup or cleanup fails
	 */
	@Test
	public void returnsCompletedChildStatus() throws IOException
	{
		requireLinuxX64();
		try (Fixture fixture = fixture())
		{
			int status = run(fixture, "complete", Duration.ZERO, 0.45);
			requireThat(status, "status").isEqualTo(17);
			requireThat(fixture.childIsAlive(), "childIsAlive").isFalse();
		}
	}

	/**
	 * Keeps a silent CPU-active child alive beyond the quiet deadline and returns its actual status.
	 *
	 * @throws IOException if fixture setup or cleanup fails
	 */
	@Test
	public void preservesSilentCpuActiveChild() throws IOException
	{
		requireLinuxX64();
		try (Fixture fixture = fixture())
		{
			int status = run(fixture, "busy", Duration.ofSeconds(5), 2.5);
			requireThat(status, "status").isEqualTo(23);
			requireThat(fixture.childIsAlive(), "childIsAlive").isFalse();
		}
	}

	/**
	 * Times out an idle child while report and resource sidecar files are written below the watched directory.
	 *
	 * @throws IOException if fixture setup or cleanup fails
	 */
	@Test
	public void retiresIdleChildWithReportAndSamples() throws IOException
	{
		requireLinuxX64();
		try (Fixture fixture = fixture())
		{
			int status = run(fixture, "idle", Duration.ZERO, 0.45);
			requireThat(status, "status").isEqualTo(124);
			requireThat(fixture.childIsAlive(), "childIsAlive").isFalse();
			requireThat(fixture.report(), "report").isRegularFile();
			requireThat(Files.size(fixture.report()), "reportSize").isGreaterThan(0L);
			requireThat(fixture.samples(), "samples").isRegularFile();
			requireThat(Files.size(fixture.samples()), "sampleSize").isGreaterThan(0L);
		}
	}

	/**
	 * Invokes the production command with one actual child and the built packaged diagnostic launcher.
	 *
	 * @param fixture owned directory and process context
	 * @param mode child behavior
	 * @param childDuration duration used by the CPU-active child
	 * @param stopAfter quiet-time deadline
	 * @return status returned by the production watchdog command
	 * @throws IOException if the child process cannot start or report output cannot be written
	 */
	private static int run(Fixture fixture, String mode, Duration childDuration, double stopAfter) throws IOException
	{
		List<String> arguments = new ArrayList<>(List.of(
			"--watch", fixture.watchDirectory().toString(),
			"--report", fixture.report().toString(),
			"--first-report", "10",
			"--interval", "10",
			"--stop-after", Double.toString(stopAfter),
			"--sample-interval", "0.05",
			"--cpu-interval", "0.05",
			"--min-cpu", "0.01",
			"--"));
		arguments.addAll(fixture.childCommand(mode, childDuration));
		Path launcher = runtimeDirectory().resolve("bin/codex-tooling");
		try (DefaultToolingScope scope = new DefaultToolingScope();
			PrintStream diagnostics = new PrintStream(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8))
		{
			return QuietBuildCommand.run(scope, arguments.toArray(String[]::new), fixture.context(),
				QuietBuildDump.Platform.LINUX, launcher, diagnostics);
		}
	}

	/**
	 * Reads the matching packaged runtime path supplied by the focused test runner.
	 *
	 * @return packaged runtime directory
	 * @throws IOException if the matching runtime launcher is missing
	 */
	private static Path runtimeDirectory() throws IOException
	{
		String runtime = System.getProperty("quiet.build.runtime");
		if (runtime == null || runtime.isBlank())
			throw new SkipException("A matching packaged runtime path is required for the watchdog boundary claim");
		Path runtimeDirectory = Path.of(runtime);
		requireThat(runtimeDirectory.resolve("bin/codex-tooling"), "launcher").isRegularFile();
		return runtimeDirectory;
	}

	/**
	 * Skips this host-specific boundary claim when the Linux x64 process implementation is unavailable.
	 */
	private static void requireLinuxX64()
	{
		String osName = System.getProperty("os.name");
		String architecture = System.getProperty("os.arch");
		boolean linuxX64 = "Linux".equals(osName) &&
			("amd64".equals(architecture) || "x86_64".equals(architecture));
		if (!linuxX64)
			throw new SkipException("The watchdog process-boundary claim is verified only on Linux x64");
	}

	/**
	 * Creates a fixture from the explicit test-runner context, with all watcher outputs under its owned root.
	 *
	 * @return fixture whose child context and files belong to this test
	 * @throws IOException if the runner context is missing or the fixture cannot be created
	 */
	private static Fixture fixture() throws IOException
	{
		runtimeDirectory();
		String contextFile = System.getProperty("quiet.build.test.context");
		requireThat(contextFile, "contextFile").isNotBlank();
		Path temporaryRoot;
		Map<String, String> inheritedEnvironment = new HashMap<>();
		try (DefaultToolingScope scope = new DefaultToolingScope();
			BufferedReader reader = Files.newBufferedReader(Path.of(contextFile)))
		{
			JsonNode context = scope.getJsonMapper().readTree(reader);
			requireThat(context.isObject(), "contextIsObject").isTrue();
			temporaryRoot = Path.of(context.get("temporary_directory").textValue());
			for (Map.Entry<String, JsonNode> entry : context.get("environment").properties())
				inheritedEnvironment.put(entry.getKey(), entry.getValue().textValue());
		}
		Path root = Files.createTempDirectory(temporaryRoot, "quiet-build-watch-boundary-");
		String childMarker = UUID.randomUUID().toString();
		try
		{
			Path watchDirectory = Files.createDirectory(root.resolve("watch"));
			Map<String, String> environment = new HashMap<>(inheritedEnvironment);
			environment.put("TMPDIR", root.toString());
			environment.put("TMP", root.toString());
			environment.put("TEMP", root.toString());
			QuietBuildCapture.Context childContext = new QuietBuildCapture.Context(root, root, environment);
			return new Fixture(root, watchDirectory, childContext, childMarker);
		}
		catch (IOException | RuntimeException failure)
		{
			try
			{
				deleteFixture(root, childMarker);
			}
			catch (IOException cleanupFailure)
			{
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
	}

	/**
	 * Removes only one fixture tree after terminating any child still owned by that invocation.
	 *
	 * @param root fixture root
	 * @param childMarker unique marker identifying this test's Java child
	 * @throws IOException if child cleanup or fixture deletion fails
	 */
	private static void deleteFixture(Path root, String childMarker) throws IOException
	{
		Path pidFile = root.resolve("child.pid");
		if (Files.isRegularFile(pidFile))
		{
			long pid = Long.parseLong(Files.readString(pidFile));
			Optional<ProcessHandle> child = ProcessHandle.of(pid);
			if (child.isPresent() && child.get().isAlive() &&
				child.get().info().commandLine().orElse("").contains(childMarker))
			{
				child.get().destroyForcibly();
				try
				{
					child.get().onExit().get(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
				}
				catch (ExecutionException | TimeoutException failure)
				{
					throw new IOException("Could not reap the test-owned watchdog child", failure);
				}
				catch (InterruptedException failure)
				{
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while reaping the test-owned watchdog child", failure);
				}
			}
		}
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.sorted((first, second) -> second.compareTo(first)).toList())
				Files.deleteIfExists(path);
		}
	}

	/**
	 * Owns one test's working tree, child context and observable report destinations.
	 *
	 * @param root test-owned temporary directory
	 * @param watchDirectory directory whose build activity is observed
	 * @param context complete context supplied to the production watcher
	 * @param childMarker unique marker identifying this test's Java child
	 */
	private record Fixture(Path root, Path watchDirectory, QuietBuildCapture.Context context, String childMarker)
		implements AutoCloseable
	{
		/**
		 * Creates an invocation fixture.
		 *
		 * @param root test-owned temporary directory
		 * @param watchDirectory directory whose build activity is observed
		 * @param context complete context supplied to the production watcher
		 * @param childMarker unique marker identifying this test's Java child
		 */
		private Fixture
		{
		}

		/**
		 * Returns the report destination excluded from build activity.
		 *
		 * @return report destination excluded from build activity
		 */
		private Path report()
		{
			return watchDirectory.resolve("watch-report.txt");
		}

		/**
		 * Returns the resource sidecar destination excluded from build activity.
		 *
		 * @return resource sidecar destination excluded from build activity
		 */
		private Path samples()
		{
			return watchDirectory.resolve("build-resources.jsonl");
		}

		/**
		 * Builds a real Java child command using this fixture's unique pid file.
		 *
		 * @param mode child behavior
		 * @param duration CPU-active duration
		 * @return literal Java executable command and arguments
		 * @throws IOException if the child's class location cannot be resolved
		 */
		private List<String> childCommand(String mode, Duration duration) throws IOException
		{
			Path java = Path.of(System.getProperty("java.home"), "bin/java");
			Path classes;
			try
			{
				classes = Path.of(QuietBuildWatchChild.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			}
			catch (URISyntaxException failure)
			{
				throw new IOException("Could not resolve the test child's classes", failure);
			}
			int exitCode = 23;
			if (mode.equals("complete"))
				exitCode = 17;
			return List.of(java.toString(), "--class-path", classes.toString(), QuietBuildWatchChild.class.getName(),
				root.resolve("child.pid").toString(), mode, Long.toString(duration.toMillis()),
				Integer.toString(exitCode), childMarker);
		}

		/**
		 * Checks whether the exact child recorded by this invocation is still alive.
		 *
		 * @return whether the exact child recorded by this invocation is still alive
		 * @throws IOException if its pid record cannot be read
		 */
		private boolean childIsAlive() throws IOException
		{
			requireThat(root.resolve("child.pid"), "pidFile").isRegularFile();
			long pid = Long.parseLong(Files.readString(root.resolve("child.pid")));
			Optional<ProcessHandle> child = ProcessHandle.of(pid);
			return child.isPresent() && child.get().isAlive() &&
				child.get().info().commandLine().orElse("").contains(childMarker);
		}

		@Override
		public void close() throws IOException
		{
			deleteFixture(root, childMarker);
		}
	}
}
