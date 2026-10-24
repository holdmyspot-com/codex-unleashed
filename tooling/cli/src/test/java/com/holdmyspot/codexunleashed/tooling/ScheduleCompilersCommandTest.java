package com.holdmyspot.codexunleashed.tooling;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Exercises the Cargo wrapper through the public CLI and the invocation-owned socket service. */
public final class ScheduleCompilersCommandTest
{
	/** Creates Cargo scheduler command tests. */
	public ScheduleCompilersCommandTest()
	{
	}

	/**
	 * Supplies scheduled compilation and an unscheduled compiler query.
	 *
	 * @return whether the fake build runs a compiler query
	 */
	@DataProvider
	public Object[][] compilerCases()
	{
		return new Object[][]{{false}, {true}};
	}

	/**
	 * Preserves literal compiler arguments and an ordinary compiler failure, then removes the wrapper and socket.
	 *
	 * @param query whether to run a query without access to the scheduler
	 * @throws IOException if fixture creation, execution or cleanup fails
	 */
	@Test(dataProvider = "compilerCases")
	public void ownsNativeWrapperLifecycle(boolean query) throws IOException
	{
		Path root = Files.createTempDirectory("cargo scheduler cli-");
		try
		{
			Path compiler = Files.writeString(root.resolve("compiler.cjs"), """
				const args = process.argv.slice(2);
				if (args.length === 1 && args[0] === 'query') {
				  process.stdout.write('query');
				  process.exit(5);
				}
				if (JSON.stringify(args) !== JSON.stringify(['--crate-name', 'fixture', 'λ and spaces']))
				  throw new Error('Compiler arguments changed: ' + JSON.stringify(args));
				process.stdout.write('compiled');
				process.stderr.write('diagnostic');
				process.exit(17);
				""");
			Path build = Files.writeString(root.resolve("build.cjs"), """
				const fs = require('node:fs');
				const endpoint = process.env.CODEX_COMPILER_SCHEDULER_SOCKET;
				if (!endpoint || !fs.existsSync(process.env.RUSTC_WRAPPER)) {
				  console.error('Missing native compiler scheduler configuration');
				  process.exit(91);
				}
				fs.writeFileSync('scheduler-socket', endpoint);
				fs.writeFileSync('scheduler-wrapper', process.env.RUSTC_WRAPPER);
				const env = {...process.env};
				let args = ['--crate-name', 'fixture', 'λ and spaces'];
				if (process.argv[4] === 'query') {
				  env.CODEX_COMPILER_SCHEDULER_SOCKET = '';
				  args = ['query'];
				}
				const direct = require('node:child_process').spawnSync(process.argv[2],
				  [process.argv[3], ...args], {encoding: 'utf8', env, timeout: 10000});
				const expected = process.argv[4] === 'query' ? 5 : 17;
				if (direct.error) throw direct.error;
				if (direct.status !== expected) {
				  console.error('Unscheduled compiler fixture failed: ' + direct.stderr);
				  process.exit(92);
				}
				const result = require('node:child_process').spawnSync(process.env.RUSTC_WRAPPER,
				  [process.argv[2], process.argv[3], ...args], {encoding: 'utf8', env, timeout: 10000});
				if (result.error) throw result.error;
				process.stdout.write(result.stdout);
				process.stderr.write(result.stderr);
				process.exit(result.status);
				""");
			Map<String, String> environment = new HashMap<>(System.getenv());
			environment.put("NODE_OPTIONS", "");
			String mode = "compile";
			int status = 17;
			String stdout = "compiled";
			String stderr = "diagnostic";
			if (query)
			{
				mode = "query";
				status = 5;
				stdout = "query";
				stderr = "";
			}
			SystemCommands.Result result = cli(root, environment,
				List.of("node", build.toString(), "node", compiler.toString(), mode));
			assertEquals(result.status(), status, result.stdout() + result.stderr());
			assertEquals(result.stdout(), stdout);
			assertEquals(result.stderr(), stderr);
			assertFalse(Files.exists(Path.of(Files.readString(root.resolve("scheduler-socket"))).getParent()));
			assertFalse(Files.exists(Path.of(Files.readString(root.resolve("scheduler-wrapper")))));
		}
		finally
		{
			try (Stream<Path> paths = Files.walk(root))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}

	/**
	 * Supplies a compiler launch failure and an actual compiler exit with the same status.
	 *
	 * @return indicates whether the compiler executable is missing
	 */
	@DataProvider
	public Object[][] exit125Cases()
	{
		return new Object[][]{{true}, {false}};
	}

	/**
	 * On Windows, identifies the producer of status 125 through the packaged wrapper and real scheduler admission.
	 *
	 * @param missing indicates whether the compiler executable is missing
	 * @throws IOException if fixture creation, execution or cleanup fails
	 */
	@Test(dataProvider = "exit125Cases")
	public void identifiesWindowsExit125(boolean missing) throws IOException
	{
		if (!System.getProperty("os.name").startsWith("Windows"))
			return;
		Path root = Files.createTempDirectory("compiler-status-125-");
		try
		{
			Path compiler = Files.writeString(root.resolve("compiler.cjs"), """
				process.stdout.write('compiled');
				process.stderr.write('diagnostic\\n');
				process.exit(125);
				""");
			Path build = Files.writeString(root.resolve("build.cjs"), """
				const result = require('node:child_process').spawnSync(process.env.RUSTC_WRAPPER,
				  [process.argv[2], process.argv[3], '--crate-name', 'fixture'],
				  {encoding: 'utf8', timeout: 10000});
				if (result.error) throw result.error;
				process.stdout.write(result.stdout);
				process.stderr.write(result.stderr);
				process.exit(result.status);
				""");
			String executable = "node";
			if (missing)
				executable = root.resolve("missing-compiler.exe").toString();
			Map<String, String> environment = new HashMap<>(System.getenv());
			environment.put("NODE_OPTIONS", "");
			SystemCommands.Result result = cli(root, environment,
				List.of("node", build.toString(), executable, compiler.toString()));

			requireThat(result.status(), "status").isEqualTo(125);
			if (missing)
			{
				requireThat(result.stdout(), "stdout").isEmpty();
				requireThat(result.stderr(), "stderr").contains("origin=wrapper operation=CreateProcessW");
				requireThat(result.stderr(), "stderr").contains("error_domain=win32 error=2");
				requireThat(result.stderr(), "stderr").contains("child_pid=0 generation=1 command=1");
				requireThat(result.stderr(), "stderr").contains("child_exit_known=false");
				requireThat(result.stderr(), "stderr").doesNotContain("origin=child");
			}
			else
			{
				requireThat(result.stdout(), "stdout").isEqualTo("compiled");
				requireThat(result.stderr(), "stderr").contains("diagnostic\n");
				requireThat(result.stderr(), "stderr").contains("origin=child operation=compiler_exit");
				requireThat(result.stderr(), "stderr").contains("error_domain=none error=0");
				requireThat(result.stderr(), "stderr").contains("child_exit_known=true child_exit=125");
				requireThat(result.stderr(), "stderr").doesNotContain("origin=wrapper");
				requireThat(result.stderr(), "stderr").doesNotContain("child_pid=0");
			}
			requireThat(result.stderr(), "stderr").contains("packet_generation=1");
			requireThat(result.stderr(), "stderr").contains("return=125");
			requireThat(result.stderr(), "stderr").doesNotContain("wrapper_pid=0");
		}
		finally
		{
			try (Stream<Path> paths = Files.walk(root))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}

	/**
	 * Provides successful and denied termination for the tooling and emitted Bazel controllers.
	 *
	 * @return whether native job termination fails and whether the emitted Bazel header is used
	 */
	@DataProvider
	public Object[][] pauseFailureCases()
	{
		return new Object[][] {{false, false}, {true, false}, {false, true}, {true, true}};
	}

	/**
	 * On Windows, retries a partially suspended compiler only after its job terminates successfully.
	 *
	 * @param stopFails whether the native fixture denies job termination
	 * @param bazelController whether the controller comes from the emitted rules_rust patch
	 * @throws IOException if native compilation, protocol or fixture operations fail
	 * @throws InterruptedException if waiting for the owned wrapper is interrupted
	 */
	@Test(dataProvider = "pauseFailureCases", timeOut = 60_000)
	public void recoversDeniedWindowsSuspension(boolean stopFails, boolean bazelController)
		throws IOException, InterruptedException
	{
		if (!System.getProperty("os.name").startsWith("Windows"))
			throw new SkipException("Requires native Windows thread and job control");
		Path root = Files.createTempDirectory("pause-");
		try
		{
			Path source = root.resolve("deny.cc");
			try (InputStream fixture = getClass().getResourceAsStream("/native/deny-second-suspension.cc"))
			{
				requireThat(fixture, "fixture").isNotNull();
				Files.copy(fixture, source);
			}
			Path project = Path.of(System.getProperty("tooling.release.workflow")).getParent().getParent().getParent();
			Path nativeSource = project.resolve("tooling/cli/src/main/native");
			if (bazelController)
			{
				SystemCommands.Result initialization = SystemCommands.capture(List.of("git", "init", "--quiet"),
					root, root, System.getenv());
				requireThat(initialization.status(), "fixtureRepositoryStatus").
					withContext(initialization.stderr(), "stderr").isEqualTo(0);
				Path patch = project.resolve(".github/diagnostics/compiler-scheduler-native.patch");
				SystemCommands.Result extraction = SystemCommands.capture(List.of("git", "apply",
					"--whitespace=error-all", "--include=util/process_wrapper/private/compiler_scheduler_windows.h",
					patch.toString()), root, root, System.getenv());
				requireThat(extraction.status(), "bazelControllerExtractionStatus").
					withContext(extraction.stderr(), "stderr").isEqualTo(0);
				nativeSource = root.resolve("util/process_wrapper/private");
			}
			Path executable = root.resolve("deny.exe");
			String controllerDefinition = "/DCODEX_SCHEDULER_FIXTURE_TOOLING";
			if (bazelController)
				controllerDefinition = "/DCODEX_SCHEDULER_FIXTURE_BAZEL";
			SystemCommands.Result compilation = SystemCommands.capture(List.of("cl.exe", "/nologo", "/std:c++17",
				"/O2", "/EHsc", "/utf-8", "/W4", "/WX", controllerDefinition, "/I" + nativeSource, source.toString(),
				"/F" + "o" + root.resolve("deny.obj"), "/Fe" + executable, "/link", "ws2_32.lib"),
				root, root, System.getenv());
			requireThat(compilation.status(), "compilationStatus").withContext(compilation.stdout(), "stdout").
				withContext(compilation.stderr(), "stderr").isEqualTo(0);
			Path compiler = Files.writeString(root.resolve("compiler.cjs"), """
				const fs = require('node:fs');
				if (fs.existsSync('attempt')) {
				  process.stdout.write('retry output');
				  process.stderr.write('retry diagnostic');
				  process.exit(0);
				}
				fs.writeFileSync('attempt', 'first');
				const child = require('node:child_process').spawn(process.execPath,
				  ['-e', 'setInterval(() => {}, 1000)'], {stdio: 'ignore'});
				fs.writeFileSync('ready.tmp', process.pid + '\\n' + child.pid + '\\n');
				fs.renameSync('ready.tmp', 'ready');
				setInterval(() => {}, 1000);
				""");
			Path errorLog = root.resolve("stderr");
			Path outputLog = root.resolve("stdout");
			Path endpoint = root.resolve("socket");
			try (WatchService watcher = root.getFileSystem().newWatchService();
				ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX))
			{
				root.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
				server.bind(UnixDomainSocketAddress.of(Path.of("").toAbsolutePath().relativize(endpoint)));
				ProcessBuilder builder = new ProcessBuilder(executable.toString(), "node", compiler.toString(),
					"--crate-name", "fixture");
				builder.directory(root.toFile()).redirectOutput(outputLog.toFile()).redirectError(errorLog.toFile());
				builder.environment().put("CODEX_COMPILER_SCHEDULER_SOCKET", endpoint.toString());
				String stopFailure = "0";
				if (stopFails)
					stopFailure = "1";
				builder.environment().put("CODEX_SCHEDULER_FIXTURE_STOP_FAILURE", stopFailure);
				try (Process wrapper = builder.start())
				{
					try (SocketChannel connection = server.accept();
						DataInputStream input = new DataInputStream(Channels.newInputStream(connection));
						DataOutputStream output = new DataOutputStream(Channels.newOutputStream(connection)))
					{
						sendSchedulerPacket(output, 1, 1);
						requireThat(readSchedulerPacket(input, errorLog), "started").isEqualTo(List.of(8, 1, 0));
						while (!Files.exists(root.resolve("ready")))
						{
							WatchKey key = watcher.poll(10, TimeUnit.SECONDS);
							requireThat(key, "compilerReadinessEvent").isNotNull();
							key.pollEvents();
							key.reset();
						}
						List<ProcessHandle> descendants = Files.readAllLines(root.resolve("ready")).stream().
							map(Long::parseLong).map(pid -> ProcessHandle.of(pid).orElseThrow()).toList();
						sendSchedulerPacket(output, 2, 1);
						if (stopFails)
						{
							requireThat(input.readNBytes(12).length, "failedStopReplyBytes").isEqualTo(0);
							requireThat(wrapper.waitFor(10, TimeUnit.SECONDS), "failedStopWrapperTerminated").isTrue();
							for (ProcessHandle descendant : descendants)
								descendant.onExit().orTimeout(10, TimeUnit.SECONDS).join();
							requireThat(wrapper.exitValue(), "failedStopWrapperStatus").isEqualTo(125);
							String expectedFailure = "origin=wrapper operation=TerminateJobObject error_domain=win32 error=5";
							if (bazelController)
								expectedFailure = "operation=TerminateJobObject error_domain=win32 error=5";
							requireThat(Files.readString(errorLog), "stderr").contains(expectedFailure);
							return;
						}
						requireThat(readSchedulerPacket(input, errorLog), "abortedAttempt").isEqualTo(List.of(11, 1, 137));
						for (ProcessHandle descendant : descendants)
							requireThat(descendant.isAlive(), "abortedDescendantAlive").isFalse();
						sendSchedulerPacket(output, 3, 1);
						sendSchedulerPacket(output, 2, 1);
						sendSchedulerPacket(output, 4, 1);
						sendSchedulerPacket(output, 5, 1);
						sendSchedulerPacket(output, 1, 2);
						requireThat(readSchedulerPacket(input, errorLog), "retryStarted").isEqualTo(List.of(8, 2, 0));
						requireThat(readSchedulerPacket(input, errorLog), "retryCompleted").isEqualTo(List.of(9, 2, 0));
						requireThat(wrapper.waitFor(10, TimeUnit.SECONDS), "wrapperTerminated").isTrue();
						requireThat(wrapper.exitValue(), "wrapperStatus").isEqualTo(0);
						requireThat(Files.readString(outputLog), "stdout").isEqualTo("retry output");
						requireThat(Files.readString(errorLog), "stderr").contains("retry diagnostic");
						requireThat(Files.readString(errorLog), "stderr").
							contains("fixture: denied suspension after one successful suspension");
						requireThat(Files.readString(errorLog), "stderr").contains("operation=SuspendThread");
						requireThat(Files.readString(errorLog), "stderr").contains("return=abort_attempt");
					}
					finally
					{
						wrapper.destroyForcibly();
						wrapper.waitFor();
					}
				}
			}
		}
		finally
		{
			try (Stream<Path> paths = Files.walk(root))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}

	/**
	 * Writes one raw scheduler command for an attempt generation.
	 *
	 * @param output borrowed scheduler connection
	 * @param command wire command
	 * @param generation attempt generation
	 * @throws IOException if the connection rejects the packet
	 */
	private static void sendSchedulerPacket(DataOutputStream output, int command, int generation) throws IOException
	{
		output.writeInt(command);
		output.writeInt(generation);
		output.writeInt(0);
		output.flush();
	}

	/**
	 * Reads a complete raw reply, including the controller's retained diagnostics when it closes early.
	 *
	 * @param input borrowed scheduler connection
	 * @param errorLog owned controller diagnostics
	 * @return command, generation and value
	 * @throws IOException if the connection or diagnostics cannot be read
	 */
	private static List<Integer> readSchedulerPacket(DataInputStream input, Path errorLog) throws IOException
	{
		byte[] packet = input.readNBytes(12);
		requireThat(packet.length, "replyBytes").withContext(Files.readString(errorLog), "stderr").isEqualTo(12);
		java.nio.ByteBuffer message = java.nio.ByteBuffer.wrap(packet);
		return List.of(message.getInt(), message.getInt(), message.getInt());
	}

	/**
	 * Retries a compiler ended by SIGKILL once and names the failed crate when that retry also dies.
	 *
	 * @throws IOException if fixture creation, execution or cleanup fails
	 */
	@Test
	public void failsCompilerKilledTwice() throws IOException
	{
		if (System.getProperty("os.name").startsWith("Windows"))
			throw new SkipException("SIGKILL is POSIX-specific; Windows job control has native controller tests");
		Path root = Files.createTempDirectory("compiler-killed-twice-");
		Path runs = root.resolve("twice.runs");
		try
		{
			String compiler = "printf 'run\\n' >> \"$0\"; kill -s KILL $$";
			SystemCommands.Result result = cli(root, System.getenv(), List.of("bash", "-c",
				"exec \"$RUSTC_WRAPPER\" sh -c \"$0\" \"$1\" --crate-name twice", compiler, runs.toString()));
			assertEquals(result.status(), 137, result.stdout() + result.stderr());
			assertEquals(Files.readAllLines(runs).size(), 2);
			assertTrue(result.stderr().contains("twice"), result.stderr());
		}
		finally
		{
			Files.deleteIfExists(runs);
			Files.delete(root);
		}
	}

	/**
	 * Runs a build through the real named-module scheduler command.
	 *
	 * @param root owned working and capture directory
	 * @param environment child environment overrides
	 * @param arguments literal build arguments
	 * @return the completed CLI's streams and status
	 * @throws IOException if the child cannot run or captures cannot be read
	 */
	private static SystemCommands.Result cli(Path root, Map<String, String> environment, List<String> arguments)
		throws IOException
	{
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		List<String> command = new ArrayList<>(List.of(java, "-Djava.io.tmpdir=" + root,
			"--module-path", System.getProperty("jdk.module.path"), "--module",
			"com.holdmyspot.codexunleashed.tooling/com.holdmyspot.codexunleashed.tooling.Main",
			"schedule-compilers", "--"));
		command.addAll(arguments);
		return SystemCommands.capture(command, root, root, environment);
	}
}
