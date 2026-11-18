package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.QuietBuildCapture;
import com.holdmyspot.codexunleashed.tooling.QuietBuildProcessAccess;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies child-process capture behavior at its caller boundary.
 */
public final class QuietBuildProcessAccessTest
{
	/**
	 * Creates process-access tests; every test owns its temporary directory.
	 */
	public QuietBuildProcessAccessTest()
	{
	}

	/**
	 * Capturing preserves literal arguments, working directory and supplied values, excludes parent-only variables,
	 * and permits only the documented Windows additions and the macOS CoreFoundation encoding variable.
	 *
	 * @throws IOException if child capture or temporary cleanup fails
	 */
	@Test
	public void capturePreservesArgumentsAndEnvironment() throws IOException
	{
		Path root = Files.createTempDirectory("quiet-build-capture-");
		Path spoolDirectory = Files.createDirectory(root.resolve("spools"));
		try
		{
			Path workingDirectory = Files.createDirectory(root.resolve("work"));
			List<String> command = childCommand("describe", "two words", "$HOME", "quote\"inside",
				"backslash\\tail");
			QuietBuildCapture.Context context = new QuietBuildCapture.Context(workingDirectory, spoolDirectory,
				Map.of("CAPTURE_VALUE", "expected"));

			try (QuietBuildCapture.Captured captured = new QuietBuildProcessAccess().capture(command, context,
				Optional.empty()))
			{
				requireThat(captured.status(), "status").isEqualTo(0);
				List<String> stdout = readLines(captured.stdout());
				requireThat(stdout.size(), "stdoutLineCount").isEqualTo(8);
				requireThat(stdout.getFirst(), "workingDirectory").
					isEqualTo(workingDirectory.toAbsolutePath().toString());

				List<String> environmentNames = new ArrayList<>(List.of(stdout.get(1).split(",")));
				if (System.getProperty("os.name").startsWith("Windows"))
					environmentNames.removeAll(List.of("SystemRoot", "PROCESSOR_ARCHITECTURE"));
				// CoreFoundation can add this variable during child JVM startup even after the launch environment is cleared.
				if (System.getProperty("os.name").equals("Mac OS X"))
					environmentNames.remove("__CF_USER_TEXT_ENCODING");
				requireThat(environmentNames, "environmentNamesWithoutReservedAdditions").
					isEqualTo(List.of("CAPTURE_VALUE"));
				requireThat(stdout.subList(2, stdout.size()), "suppliedValueParentAndArguments").isEqualTo(List.of(
					"expected", "null", "two words", "$HOME", "quote\"inside", "backslash\\tail"));
				List<Path> captureDirectories = listEntries(spoolDirectory);
				requireThat(captureDirectories.size(), "captureDirectoriesWhileCaptured").isEqualTo(1);
				requireThat(countEntries(captureDirectories.getFirst()), "spoolFilesWhileCaptured").isEqualTo(2L);
			}

			requireThat(countEntries(spoolDirectory), "spoolFilesAfterClose").isEqualTo(0L);
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Capturing drains large stdout and stderr streams and keeps their spool files until the result closes.
	 *
	 * @throws IOException if child capture or temporary cleanup fails
	 */
	@Test
	public void captureSpoolsLargeStreamsAndCleansFiles() throws IOException
	{
		Path root = Files.createTempDirectory("quiet-build-capture-");
		Path spoolDirectory = Files.createDirectory(root.resolve("spools"));
		try
		{
			Path workingDirectory = Files.createDirectory(root.resolve("work"));
			QuietBuildCapture.Context context = new QuietBuildCapture.Context(workingDirectory, spoolDirectory, Map.of());
			try (QuietBuildCapture.Captured captured = new QuietBuildProcessAccess().capture(
				childCommand("streams"), context, Optional.empty()))
			{
				requireThat(captured.status(), "status").isEqualTo(0);
				requireThat(countCharacters(captured.stdout()), "stdoutCharacters").isEqualTo(1_048_576L);
				requireThat(countCharacters(captured.stderr()), "stderrCharacters").isEqualTo(1_048_576L);
				List<Path> captureDirectories = listEntries(spoolDirectory);
				requireThat(captureDirectories.size(), "captureDirectoriesWhileCaptured").isEqualTo(1);
				requireThat(countEntries(captureDirectories.getFirst()), "spoolFilesWhileCaptured").isEqualTo(2L);
			}

			requireThat(countEntries(spoolDirectory), "spoolFilesAfterClose").isEqualTo(0L);
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Capturing returns the child's ordinary nonzero exit status with both output streams available.
	 *
	 * @throws IOException if child capture or temporary cleanup fails
	 */
	@Test
	public void captureReturnsNonzeroStatus() throws IOException
	{
		Path root = Files.createTempDirectory("quiet-build-capture-");
		Path spoolDirectory = Files.createDirectory(root.resolve("spools"));
		try
		{
			Path workingDirectory = Files.createDirectory(root.resolve("work"));
			QuietBuildCapture.Context context = new QuietBuildCapture.Context(workingDirectory, spoolDirectory, Map.of());
			try (QuietBuildCapture.Captured captured = new QuietBuildProcessAccess().capture(
				childCommand("exit", "23"), context, Optional.empty()))
			{
				requireThat(captured.status(), "status").isEqualTo(1);
			}
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * A command that remains active past its deadline raises the typed timeout and removes its spool files.
	 *
	 * @throws IOException if temporary setup or cleanup fails
	 */
	@Test
	public void timeoutRaisesTypedFailureAndCleansFiles() throws IOException
	{
		Path root = Files.createTempDirectory("quiet-build-capture-");
		Path spoolDirectory = Files.createDirectory(root.resolve("spools"));
		try
		{
			Path workingDirectory = Files.createDirectory(root.resolve("work"));
			QuietBuildCapture.Context context = new QuietBuildCapture.Context(workingDirectory, spoolDirectory, Map.of());
			QuietBuildProcessAccess.CaptureTimeoutException timeout = null;
			try
			{
				new QuietBuildProcessAccess().capture(childCommand("wait", root.resolve("timeout.pid").toString()),
					context, Optional.of(Duration.ofSeconds(3)));
			}
			catch (QuietBuildProcessAccess.CaptureTimeoutException failure)
			{
				timeout = failure;
			}

			requireThat(timeout, "timeout").isNotNull();
			requireThat(countEntries(spoolDirectory), "spoolFilesAfterTimeout").isEqualTo(0L);
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Interrupting a capture restores the caller's interrupt flag and kills and reaps its direct child.
	 *
	 * @throws IOException if temporary setup or cleanup fails
	 * @throws InterruptedException if the test thread is interrupted while joining the capture thread
	 */
	@Test
	public void interruptionStopsAndReapsChild() throws IOException, InterruptedException
	{
		Path root = Files.createTempDirectory("quiet-build-capture-");
		Path spoolDirectory = Files.createDirectory(root.resolve("spools"));
		Thread captureThread = null;
		try
		{
			Path workingDirectory = Files.createDirectory(root.resolve("work"));
			Path processIdFile = root.resolve("child.pid");
			QuietBuildCapture.Context context = new QuietBuildCapture.Context(workingDirectory, spoolDirectory, Map.of());
			AtomicReference<IOException> captureFailure = new AtomicReference<>();
			AtomicBoolean interruptRestored = new AtomicBoolean();
			captureThread = new Thread(() ->
			{
				try
				{
					new QuietBuildProcessAccess().capture(childCommand("wait", processIdFile.toString()), context,
						Optional.empty());
				}
				catch (IOException failure)
				{
					captureFailure.set(failure);
					interruptRestored.set(Thread.currentThread().isInterrupted());
				}
			});
			captureThread.start();
			waitForFile(processIdFile);
			long processId = Long.parseLong(Files.readString(processIdFile));

			captureThread.interrupt();
			captureThread.join(TimeUnit.SECONDS.toMillis(10));

			requireThat(captureThread.isAlive(), "captureThreadAlive").isFalse();
			requireThat(captureFailure.get(), "captureFailure").isNotNull();
			requireThat(interruptRestored.get(), "interruptRestored").isTrue();
			requireThat(ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false), "childAlive").isFalse();
			requireThat(countEntries(spoolDirectory), "spoolFilesAfterInterrupt").isEqualTo(0L);
		}
		finally
		{
			if (captureThread != null)
			{
				captureThread.interrupt();
				captureThread.join(TimeUnit.SECONDS.toMillis(10));
			}
			deleteTree(root);
		}
	}

	/**
	 * Builds a command that runs the Java child fixture with literal fixture arguments.
	 *
	 * @param arguments child fixture mode and its arguments
	 * @return Java executable, test class path and child fixture command
	 */
	private static List<String> childCommand(String... arguments)
	{
		String executableName = "java";
		if (System.getProperty("os.name").startsWith("Windows"))
			executableName = "java.exe";
		Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", executableName);
		String modulePath = System.getProperty("jdk.module.path");
		List<String> command = new ArrayList<>(List.of(javaExecutable.toString(), "--module-path", modulePath, "--module",
			"com.holdmyspot.codexunleashed.tooling.test/" + ChildProcess.class.getName()));
		command.addAll(List.of(arguments));
		return command;
	}

	/**
	 * Reads every line from a child output reader.
	 *
	 * @param reader child output reader
	 * @return lines in their original order
	 * @throws IOException if output cannot be read
	 */
	private static List<String> readLines(Reader reader) throws IOException
	{
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		int character;
		while (true)
		{
			character = reader.read();
			if (character == -1)
				break;
			if (character == '\n')
			{
				lines.add(line.toString());
				line.setLength(0);
			}
			else if (character != '\r')
				line.append((char) character);
		}
		if (!line.isEmpty())
			lines.add(line.toString());
		return lines;
	}

	/**
	 * Counts characters without storing the captured stream in memory.
	 *
	 * @param reader child output reader
	 * @return number of characters read
	 * @throws IOException if output cannot be read
	 */
	private static long countCharacters(Reader reader) throws IOException
	{
		long count = 0;
		char[] buffer = new char[8192];
		int read;
		while (true)
		{
			read = reader.read(buffer);
			if (read == -1)
				break;
			count += read;
		}
		return count;
	}

	/**
	 * Counts entries in a test-owned directory.
	 *
	 * @param directory directory to inspect
	 * @return number of direct entries
	 * @throws IOException if the directory cannot be read
	 */
	private static long countEntries(Path directory) throws IOException
	{
		return listEntries(directory).size();
	}

	/**
	 * Lists direct entries in a test-owned directory.
	 *
	 * @param directory directory to inspect
	 * @return direct entries
	 * @throws IOException if the directory cannot be read
	 */
	private static List<Path> listEntries(Path directory) throws IOException
	{
		try (Stream<Path> entries = Files.list(directory))
		{
			return entries.toList();
		}
	}

	/**
	 * Waits for the Java child fixture to record its process ID without delaying the test by a fixed interval.
	 *
	 * @param processIdFile child-created process ID file
	 * @throws IOException if the child does not record its process ID
	 */
	private static void waitForFile(Path processIdFile) throws IOException
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while ((!Files.isRegularFile(processIdFile) || Files.size(processIdFile) == 0) && System.nanoTime() < deadline)
			Thread.onSpinWait();
		if (!Files.isRegularFile(processIdFile) || Files.size(processIdFile) == 0)
			throw new IOException("Java child fixture did not write its process ID");
	}

	/**
	 * Removes only the test's own temporary directory and its children.
	 *
	 * @param root test-owned temporary directory
	 * @throws IOException if test files cannot be deleted
	 */
	private static void deleteTree(Path root) throws IOException
	{
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.delete(path);
		}
	}

	/**
	 * Java child fixture used to exercise real process-launch and output boundaries.
	 */
	public static final class ChildProcess
	{
		/**
		 * Prevents direct construction of the Java child fixture.
		 */
		private ChildProcess()
		{
		}

		/**
		 * Runs one child behavior selected by its first argument.
		 *
		 * @param arguments child mode and mode-specific values
		 * @throws IOException if the selected fixture cannot write its output
		 * @throws InterruptedException if the held child is interrupted
		 */
		public static void main(String[] arguments) throws IOException, InterruptedException
		{
			switch (arguments[0])
			{
			case "describe":
				System.out.println(Path.of("").toAbsolutePath());
				System.out.println(String.join(",", System.getenv().keySet().stream().sorted().toList()));
				System.out.println(System.getenv().get("CAPTURE_VALUE"));
				System.out.println(System.getenv().get("PARENT_ONLY"));
				for (int index = 1; index < arguments.length; index += 1)
					System.out.println(arguments[index]);
				break;
			case "streams":
				char[] stdout = new char[8192];
				char[] stderr = new char[8192];
				java.util.Arrays.fill(stdout, 'o');
				java.util.Arrays.fill(stderr, 'e');
				for (int index = 0; index < 128; index += 1)
				{
					System.out.print(stdout);
					System.err.print(stderr);
				}
				break;
			case "exit":
				throw new IOException("Child fixture exits unsuccessfully with code " + arguments[1]);
			case "wait":
				Files.writeString(Path.of(arguments[1]), Long.toString(ProcessHandle.current().pid()));
				new CountDownLatch(1).await();
				break;
			default:
				throw new IllegalArgumentException("Unknown child fixture mode: " + arguments[0]);
			}
		}
	}
}
