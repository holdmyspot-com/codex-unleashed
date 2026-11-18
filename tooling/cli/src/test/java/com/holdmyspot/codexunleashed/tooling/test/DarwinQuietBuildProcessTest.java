package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.QuietBuildLauncher;
import com.holdmyspot.codexunleashed.tooling.QuietBuildProcess;
import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import com.holdmyspot.codexunleashed.tooling.UnixQuietBuildProcess;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.testng.SkipException;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies Darwin's spawn ABI through the native process binding.
 */
public final class DarwinQuietBuildProcessTest
{
	/**
	 * Creates independent Darwin process tests.
	 */
	public DarwinQuietBuildProcessTest()
	{
	}

	/**
	 * Starts a child in a new Darwin session with literal arguments, environment, cwd, descriptors and signal policy.
	 *
	 * @throws IOException          if fixture creation, launch, or receipt reading fails
	 * @throws InterruptedException if process completion is interrupted
	 */
	@Test
	public void startsInNewDarwinSession() throws IOException, InterruptedException
	{
		requireDarwin();
		try (NativeFixture fixture = new NativeFixture();
			ToolingScope scope = new DefaultToolingScope())
		{
			UnixQuietBuildProcess.Binding binding = new UnixQuietBuildProcess.Binding(scope.getNativeLinker());
			Path receipt = fixture.directory.resolve("identity.txt");
			List<String> arguments = List.of(fixture.executable.toString(), "identity", receipt.toString(),
				"two words ; $(literal) 'quoted' \\");
			Map<String, String> environment = Map.of("PATH", "/usr/bin:/bin",
				"CODEX_TEST_VALUE", "selected=value with spaces");
			List<Integer> closed = binding.closedStandards();
			try (QuietBuildProcess process = new QuietBuildLauncher(scope).start(arguments, fixture.directory, environment))
			{
				requireThat(process.await(), "completion").isEqualTo(new QuietBuildProcess.UnixExit(0));
				Map<String, String> values = readReceipt(receipt);
				requireThat(values.get("cwd"), "cwd").isEqualTo(fixture.directory.toRealPath().toString());
				requireThat(values.get("environment"), "environment").isEqualTo(environment.get("CODEX_TEST_VALUE"));
				requireThat(values.get("pid"), "pid").isEqualTo(Long.toString(process.pid()));
				requireThat(values.get("pgid"), "pgid").isEqualTo(values.get("pid"));
				requireThat(values.get("sid"), "sid").isEqualTo(values.get("pid"));
				requireThat(values.get("argumentCount"), "argumentCount").isEqualTo(Integer.toString(arguments.size()));
				for (int index = 0; index < arguments.size(); index += 1)
				{
					String encoded = values.get("argument" + index);
					String actual = new String(HexFormat.of().parseHex(encoded), StandardCharsets.UTF_8);
					requireThat(actual, "argument" + index).isEqualTo(arguments.get(index));
				}
				for (int descriptor = 0; descriptor < 3; descriptor += 1)
				{
					boolean expectedOpen = !closed.contains(descriptor);
					String expectedState = "closed";
					if (expectedOpen)
						expectedState = "open";
					requireThat(values.get("fd" + descriptor), "fd" + descriptor).
						isEqualTo(expectedState);
				}
				requireThat(values.get("emptySignalMask"), "emptySignalMask").isEqualTo("true");
				for (String signal : List.of("INT", "QUIT", "TERM", "PIPE", "XFSZ"))
					requireThat(values.get("defaultSIG" + signal), "defaultSIG" + signal).isEqualTo("true");
			}
		}
	}

	/**
	 * Stops the owned Darwin process group and lets its parent reap the forked child before root reaping.
	 *
	 * @throws IOException          if fixture creation, readiness, launch, or cleanup fails
	 * @throws InterruptedException if readiness or group cleanup is interrupted
	 */
	@Test
	public void stopsDarwinOwnedGroupAndReapsRoot() throws IOException, InterruptedException
	{
		requireDarwin();
		try (NativeFixture fixture = new NativeFixture();
			ToolingScope scope = new DefaultToolingScope();
			WatchService changes = fixture.directory.getFileSystem().newWatchService())
		{
			fixture.watchForReady(changes);
			UnixQuietBuildProcess.Binding binding = new UnixQuietBuildProcess.Binding(scope.getNativeLinker());
			Path ready = fixture.directory.resolve("tree-ready.txt");
			try (UnixQuietBuildProcess process = fixture.start(binding,
				List.of(fixture.executable.toString(), "tree", ready.toString()), Map.of("PATH", "/usr/bin:/bin")))
			{
				fixture.awaitReady(changes, ready);
				requireThat(process.stop().state(), "groupState").isEqualTo(QuietBuildProcess.GroupState.GONE);
				requireThat(process.await(), "completion").isEqualTo(new QuietBuildProcess.UnixExit(0));
				requireThat(process.poll().orElseThrow(), "cachedCompletion").isEqualTo(process.await());
			}
		}
	}

	/**
	 * Closing a Darwin owner escalates from TERM to KILL and reaps its direct child.
	 *
	 * @throws IOException          if fixture creation, readiness, launch, or cleanup fails
	 * @throws InterruptedException if readiness or cleanup is interrupted
	 */
	@Test
	public void closesResistantDarwinOwner() throws IOException, InterruptedException
	{
		closeResistantOwner(false);
	}

	/**
	 * Interrupted Darwin close still reaps its direct child and restores the caller's interrupt flag.
	 *
	 * @throws IOException          if fixture creation, readiness, launch, or cleanup fails
	 * @throws InterruptedException if readiness is interrupted
	 */
	@Test
	public void interruptedCloseReapsDarwinRoot() throws IOException, InterruptedException
	{
		closeResistantOwner(true);
	}

	/**
	 * Closes the signal-resistant child and verifies bounded escalation and interruption handling.
	 *
	 * @param interrupt whether to set the current thread's interrupt before close
	 * @throws IOException          if fixture creation, readiness, launch, or cleanup fails
	 * @throws InterruptedException if readiness is interrupted
	 */
	private static void closeResistantOwner(boolean interrupt) throws IOException, InterruptedException
	{
		requireDarwin();
		try (NativeFixture fixture = new NativeFixture();
			ToolingScope scope = new DefaultToolingScope();
			WatchService changes = fixture.directory.getFileSystem().newWatchService())
		{
			fixture.watchForReady(changes);
			UnixQuietBuildProcess.Binding binding = new UnixQuietBuildProcess.Binding(scope.getNativeLinker());
			Path ready = fixture.directory.resolve("resistant-ready.txt");
			try (UnixQuietBuildProcess process = fixture.start(binding,
				List.of(fixture.executable.toString(), "resistant", ready.toString()), Map.of("PATH", "/usr/bin:/bin")))
			{
				fixture.awaitReady(changes, ready);
				try
				{
					if (interrupt)
						Thread.currentThread().interrupt();
					process.close();
					requireThat(process.poll().orElseThrow(), "completion").
						isEqualTo(new QuietBuildProcess.UnixSignal(9, false));
					if (interrupt)
						requireThat(Thread.currentThread().isInterrupted(), "interrupted").isTrue();
				}
				finally
				{
					Thread.interrupted();
				}
			}
		}
	}

	/**
	 * Fails on a nonterminal Darwin wait status.
	 *
	 * @throws IOException when the stopped status is rejected
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*nonterminal status.*")
	public void rejectsNonterminalDarwinStatus() throws IOException
	{
		UnixQuietBuildProcess.decodeWaitStatus(0x7f);
	}

	/**
	 * Requires a supported Darwin process ABI before compiling or launching a native fixture.
	 */
	private static void requireDarwin()
	{
		if (!System.getProperty("os.name").equals("Mac OS X"))
			throw new SkipException("Native Darwin process tests require macOS");
		String architecture = System.getProperty("os.arch");
		if (!architecture.equals("x86_64") && !architecture.equals("aarch64"))
			throw new AssertionError("Unsupported Darwin architecture: " + architecture);
	}

	/**
	 * Reads the fixture's line-based native process receipt.
	 *
	 * @param path receipt created by the native child
	 * @return receipt values indexed by their field names
	 * @throws IOException if the receipt cannot be read or is malformed
	 */
	private static Map<String, String> readReceipt(Path path) throws IOException
	{
		Map<String, String> values = new HashMap<>();
		for (String line : Files.readAllLines(path))
		{
			int separator = line.indexOf('=');
			if (separator < 1)
				throw new IOException("Malformed native child receipt line");
			values.put(line.substring(0, separator), line.substring(separator + 1));
		}
		return values;
	}

	/**
	 * Compiles, starts, observes and cleans one Darwin native fixture.
	 */
	private static final class NativeFixture implements AutoCloseable
	{
		/**
		 * Isolated directory removed after every process using it has closed.
		 */
		private final Path directory;
		/**
		 * Compiled C fixture launched directly by the binding.
		 */
		private final Path executable;

		/**
		 * Compiles the fixture in this test's owned directory.
		 *
		 * @throws IOException if fixture compilation fails
		 */
		private NativeFixture() throws IOException
		{
			directory = Files.createTempDirectory("darwin-quiet-build-process-");
			executable = directory.resolve("native-child");
			try
			{
				Path source = directory.resolve("darwin-quiet-build-process.c");
				try (InputStream input = DarwinQuietBuildProcessTest.class.getResourceAsStream("darwin-quiet-build-process.c"))
				{
					Files.copy(input, source);
				}
				SystemCommands.Result result = SystemCommands.capture(List.of("cc", "-std=c11", "-Wall", "-Wextra",
					"-Werror", "-O0", source.toString(), "-o", executable.toString()), directory, directory);
				if (result.status() != 0)
					throw new IOException("Native fixture compilation failed: " + result.stderr());
			}
			catch (IOException | RuntimeException failure)
			{
				close();
				throw failure;
			}
		}

		/**
		 * Starts a process after preserving the standard descriptors that were open at binding ingress.
		 *
		 * @param binding native calls scoped to the supplied tooling scope
		 * @param command literal executable and arguments
		 * @param environment complete child environment
		 * @return process owning the root and its process group
		 * @throws IOException if descriptor inspection or process startup fails
		 */
		private UnixQuietBuildProcess start(UnixQuietBuildProcess.Binding binding, List<String> command,
			Map<String, String> environment) throws IOException
		{
			List<Integer> closed = binding.closedStandards();
			return UnixQuietBuildProcess.start(binding, command, directory, environment, closed);
		}

		/**
		 * Registers for the native fixture's readiness file before starting the child.
		 *
		 * @param changes test-owned filesystem watcher
		 * @throws IOException if the watcher cannot register
		 */
		private void watchForReady(WatchService changes) throws IOException
		{
			directory.register(changes, StandardWatchEventKinds.ENTRY_CREATE);
		}

		/**
		 * Waits for a bounded native-child readiness receipt without polling or sleeping.
		 *
		 * @param changes registered watcher
		 * @param ready   child-created readiness file
		 * @throws IOException          if the child does not become ready
		 * @throws InterruptedException if the wait is interrupted
		 */
		private void awaitReady(WatchService changes, Path ready) throws IOException, InterruptedException
		{
			long started = System.nanoTime();
			while (!Files.isRegularFile(ready))
			{
				long remaining = Duration.ofSeconds(10).toNanos() - (System.nanoTime() - started);
				if (remaining <= 0)
					throw new IOException("Native child did not create its readiness receipt");
				WatchKey event = changes.poll(remaining, TimeUnit.NANOSECONDS);
				if (event == null)
					throw new IOException("Native child did not create its readiness receipt");
				event.pollEvents();
				event.reset();
			}
		}

		@Override
		public void close() throws IOException
		{
			try (Stream<Path> paths = Files.walk(directory))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}
}
