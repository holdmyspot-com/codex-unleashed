package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.QuietBuildLauncher;
import com.holdmyspot.codexunleashed.tooling.QuietBuildProcess;
import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import com.holdmyspot.codexunleashed.tooling.UnixQuietBuildProcess;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.nio.file.WatchKey;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.testng.SkipException;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies native ownership and bounded cleanup through {@link QuietBuildLauncher}.
 */
public final class UnixQuietBuildProcessTest
{
	/**
	 * Creates independent native process tests.
	 */
	public UnixQuietBuildProcessTest()
	{
	}

	/**
	 * Ordinary exit 143 and signal 15 retain different typed results, including the core indicator.
	 *
	 * @throws IOException if a terminal fixture status is rejected
	 */
	@Test
	public void decodesDistinctUnixCompletion() throws IOException
	{
		requireThat(UnixQuietBuildProcess.decodeWaitStatus(143 << 8), "ordinary").
			isEqualTo(new QuietBuildProcess.UnixExit(143));
		requireThat(UnixQuietBuildProcess.decodeWaitStatus(15), "signal").
			isEqualTo(new QuietBuildProcess.UnixSignal(15, false));
		requireThat(UnixQuietBuildProcess.decodeWaitStatus(11 | 0x80), "core").
			isEqualTo(new QuietBuildProcess.UnixSignal(11, true));
	}

	/**
	 * PATH selection preserves empty entries and interprets relative candidates under the supplied cwd.
	 */
	@Test
	public void preservesPathSearchCandidates()
	{
		if (System.getProperty("os.name").startsWith("Windows"))
			throw new SkipException("POSIX path semantics require a Unix lane");
		Path cwd = Path.of("owned-cwd").toAbsolutePath();
		requireThat(UnixQuietBuildProcess.candidates("build", cwd, Map.of("PATH", ":relative:/bin:")), "candidates").
			isEqualTo(List.of(cwd.resolve("build"), cwd.resolve("relative/build"), Path.of("/bin/build"),
				cwd.resolve("build")));
		requireThat(UnixQuietBuildProcess.candidates("build", cwd, Map.of()), "absentPath").
			isEqualTo(List.of(Path.of("/bin/build"), Path.of("/usr/bin/build")));
		requireThat(UnixQuietBuildProcess.candidates("./build", cwd, Map.of("PATH", "/ignored")), "explicitPath").
			isEqualTo(List.of(cwd.resolve("./build")));
	}

	/**
	 * Missing-file and not-a-directory errors do not hide the first permission or executable-format failure.
	 */
	@Test
	public void retainsFirstLaunchError()
	{
		requireThat(UnixQuietBuildProcess.selectLaunchError(0, 2), "missing").isEqualTo(0);
		requireThat(UnixQuietBuildProcess.selectLaunchError(0, 20), "notDirectory").isEqualTo(0);
		requireThat(UnixQuietBuildProcess.selectLaunchError(0, 13), "permission").isEqualTo(13);
		requireThat(UnixQuietBuildProcess.selectLaunchError(13, 8), "earlierPermission").isEqualTo(13);
		requireThat(UnixQuietBuildProcess.selectLaunchError(8, 13), "earlierFormat").isEqualTo(8);
	}

	/**
	 * Launching native exit and signal commands preserves distinct completions and caches each result after await.
	 *
	 * @throws IOException          if fixture creation or native completion fails
	 * @throws InterruptedException if waiting for native completion is interrupted
	 */
	@Test
	public void preservesNativeCompletionKinds() throws IOException, InterruptedException
	{
		try (NativeChild child = new NativeChild();
			ToolingScope scope = new DefaultToolingScope())
		{
			try (QuietBuildProcess process = new QuietBuildLauncher(scope).start(
				List.of(child.executable.toString(), "exit", "143"), child.directory, Map.of("PATH", "/bin:/usr/bin")))
			{
				QuietBuildProcess.Completion completion = process.await();
				requireThat(completion, "exitCompletion").isEqualTo(new QuietBuildProcess.UnixExit(143));
				requireThat(process.poll().orElseThrow(), "cachedExitCompletion").isEqualTo(completion);
			}

			try (QuietBuildProcess process = new QuietBuildLauncher(scope).start(
				List.of(child.executable.toString(), "signal", "15"), child.directory, Map.of("PATH", "/bin:/usr/bin")))
			{
				QuietBuildProcess.Completion completion = process.await();
				requireThat(completion, "signalCompletion").isEqualTo(new QuietBuildProcess.UnixSignal(15, false));
				requireThat(process.poll().orElseThrow(), "cachedSignalCompletion").isEqualTo(completion);
			}
		}
	}

	/**
	 * Closing an interrupted owner still terminates and reaps its direct child, preserving the interrupt flag.
	 *
	 * @throws IOException          if fixture creation or native cleanup fails
	 * @throws InterruptedException if fixture readiness is interrupted
	 */
	@Test
	public void interruptedCloseReapsChild() throws IOException, InterruptedException
	{
		try (NativeChild child = new NativeChild();
			ToolingScope scope = new DefaultToolingScope();
			WatchService changes = child.directory.getFileSystem().newWatchService())
		{
			child.directory.register(changes, StandardWatchEventKinds.ENTRY_CREATE);
			try (QuietBuildProcess process = new QuietBuildLauncher(scope).start(
				List.of(child.executable.toString(), "pause-ready", child.ready.toString(), "ignore-term"), child.directory,
				Map.of("PATH", "/bin:/usr/bin")))
			{
				try
				{
					child.awaitReady(changes);
					Thread.currentThread().interrupt();
					process.close();
					requireThat(Thread.currentThread().isInterrupted(), "interrupted").isTrue();
					requireThat(process.poll().orElseThrow(), "completion").
						isEqualTo(new QuietBuildProcess.UnixSignal(9, false));
				}
				finally
				{
					Thread.interrupted();
				}
			}
		}
	}

	/**
	 * Group stop reaches a native child and lets its parent reap it before the launcher reaps the root.
	 *
	 * @throws IOException          if fixture creation or native cleanup fails
	 * @throws InterruptedException if native readiness or cleanup is interrupted
	 */
	@Test
	public void stopsCooperativeParentAndChild() throws IOException, InterruptedException
	{
		try (NativeChild child = new NativeChild();
			ToolingScope scope = new DefaultToolingScope();
			WatchService changes = child.directory.getFileSystem().newWatchService())
		{
			child.directory.register(changes, StandardWatchEventKinds.ENTRY_CREATE);
			try (QuietBuildProcess process = new QuietBuildLauncher(scope).start(
				List.of(child.executable.toString(), "tree-ready", child.ready.toString()), child.directory,
				Map.of("PATH", "/bin:/usr/bin")))
			{
				child.awaitReady(changes);
				requireThat(process.stop().state(), "groupState").isEqualTo(QuietBuildProcess.GroupState.GONE);
				requireThat(process.await(), "completion").isEqualTo(new QuietBuildProcess.UnixExit(0));
				requireThat(process.poll().orElseThrow(), "cachedCompletion").isEqualTo(process.await());
			}
		}
	}

	/**
	 * Closing the permission-helper owner forcibly terminates and reaps its resistant child before JDK closure.
	 *
	 * @throws IOException          if the native fixture or cleanup fails
	 * @throws InterruptedException if child readiness is interrupted
	 */
	@Test
	public void closesResistantPermissionHelper() throws IOException, InterruptedException
	{
		try (NativeChild child = new NativeChild();
			WatchService changes = child.directory.getFileSystem().newWatchService())
		{
			child.directory.register(changes, StandardWatchEventKinds.ENTRY_CREATE);
			try (UnixQuietBuildProcess.Helper cleanup = new UnixQuietBuildProcess.Helper(
				new ProcessBuilder(child.executable.toString(), "pause-ready", child.ready.toString(), "ignore-term").
					inheritIO().start()))
			{
				child.awaitReady(changes);
				ProcessHandle helper = ProcessHandle.of(Long.parseLong(Files.readString(child.ready).strip())).orElseThrow();
				cleanup.close();
				requireThat(helper.isAlive(), "helperAlive").isFalse();
			}
		}
	}

	/**
	 * Owns the compiled native fixture and all of one test's files.
	 */
	private static final class NativeChild implements AutoCloseable
	{
		/**
		 * Isolated directory deleted after process cleanup.
		 */
		private final Path directory = Files.createTempDirectory("quiet-build-process-");
		/**
		 * Compiled child used by the launcher under test.
		 */
		private final Path executable = directory.resolve("native-child");
		/**
		 * Child-created readiness receipt.
		 */
		private final Path ready = directory.resolve("ready");

		/**
		 * Compiles the fixture on the admitted Linux x86-64 or AArch64 lane.
		 *
		 * @throws IOException if compiling or copying the fixture fails
		 */
		private NativeChild() throws IOException
		{
			String architecture = System.getProperty("os.arch");
			boolean isLinuxProcessFixtureSupported = System.getProperty("os.name").equals("Linux") &&
				(architecture.equals("amd64") || architecture.equals("aarch64"));
			if (!isLinuxProcessFixtureSupported)
			{
				close();
				throw new SkipException("Linux x86-64 or AArch64 native lane required; other platform gates remain unrun");
			}
			try
			{
				Path source = directory.resolve("quiet-build-process.c");
				try (InputStream input = UnixQuietBuildProcessTest.class.getResourceAsStream("quiet-build-process.c"))
				{
					Files.copy(input, source);
				}
				SystemCommands.Result result = SystemCommands.capture(List.of("cc", "-std=c11", "-Wall", "-Wextra",
					"-Werror", "-O0", source.toString(), "-o", executable.toString()), directory, directory);
				requireThat(result.status(), "compilerStatus").withContext(result.stderr(), "stderr").isEqualTo(0);
			}
			catch (IOException | RuntimeException failure)
			{
				close();
				throw failure;
			}
		}

		/**
		 * Waits for the child-created readiness receipt without changing the owner's lifecycle.
		 *
		 * @param changes watcher registered before startup
		 * @throws IOException          if the child does not become ready
		 * @throws InterruptedException if the wait is interrupted
		 */
		private void awaitReady(WatchService changes) throws IOException, InterruptedException
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
