package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.QuietBuildCapture;
import com.holdmyspot.codexunleashed.tooling.QuietBuildDump;
import com.holdmyspot.codexunleashed.tooling.QuietBuildDumpSpool;
import com.holdmyspot.codexunleashed.tooling.QuietBuildNativeFacts;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests host process dump reports.
 */
public final class QuietBuildDumpTest
{
	/**
	 * Creates process dump tests.
	 */
	public QuietBuildDumpTest()
	{
	}

	/**
	 * Writes process and task rows from the injected Linux process tree.
	 *
	 * @throws IOException if fixture setup or report writing fails
	 */
	@Test
	public void writesLinuxProcessAndTasks() throws IOException
	{
		try (TestContext context = loadContext())
		{
			Path procfs = Files.createTempDirectory(context.temporaryDirectory(), "procfs-");
			Path process = procfs.resolve("12");
			Path task = process.resolve("task/12");
			Files.createDirectories(task);
			Files.writeString(process.resolve("cmdline"), "/opt/runtime/bin/java\0-main\0");
			Files.writeString(process.resolve("comm"), "java\n");
			Files.writeString(process.resolve("status"), resource("linux-status.txt"));
			Files.writeString(process.resolve("wchan"), "futex_wait\n");
			Files.writeString(process.resolve("syscall"), "202 wait\n");
			Files.createDirectories(process.resolve("fd/0"));
			Files.createDirectories(process.resolve("fd/1"));
			Files.createDirectories(process.resolve("fd/2"));
			Files.writeString(process.resolve("limits"), resource("linux-limits.txt"));
			Files.writeString(task.resolve("comm"), "worker\n");
			Files.writeString(task.resolve("status"), resource("linux-task-status.txt"));
			Files.writeString(task.resolve("wchan"), "io_schedule\n");
			Files.writeString(task.resolve("stack"), "[<0>] io_schedule\n");
			StringWriter output = new StringWriter();
			try (DefaultToolingScope scope = new DefaultToolingScope())
			{
				QuietBuildDump dump = new QuietBuildDump(scope, (command, childContext, timeout) ->
				{
					throw new AssertionError("No diagnostic command was requested");
				}, context.captureContext(), QuietBuildDump.Platform.LINUX, procfs, noNativeFacts());
				dump.dump(null, output);
				requireThat(output.toString(), "output").contains("pid=12 ppid=4 tracer=0 uid=501 state=S");
				requireThat(output.toString(), "output").contains("wchan=futex_wait syscall=202 fds=3 nofile=1024");
				requireThat(output.toString(), "output").contains("task=12 comm=worker state=D tracer=0 wchan=io_schedule");
				requireThat(output.toString(), "output").contains("[<0>] io_schedule");
			}
		}
	}

	/**
	 * Writes held-process status and Java rows from an array-form Windows query.
	 *
	 * @throws IOException if fixture setup or report writing fails
	 */
	@Test
	public void writesWindowsHeldAndJvmRows() throws IOException
	{
		try (TestContext context = loadContext(); DefaultToolingScope scope = new DefaultToolingScope())
		{
			QuietBuildDump dump = new QuietBuildDump(scope, (command, childContext, timeout) ->
				captured(resource("windows-many.json"), ""), context.captureContext(), QuietBuildDump.Platform.WINDOWS,
				context.temporaryDirectory(), noNativeFacts());
			StringWriter output = new StringWriter();
			dump.dump(null, output);
			requireThat(output.toString(), "output").contains("pid=11 ppid=10 threads=3 suspended=3 held=yes");
			requireThat(output.toString(), "output").contains("pid=12 ppid=4 threads=30 suspended=0 held=no cmd=java.exe");
			requireThat(output.toString(), "output").contains("--- pid=12 java.exe\n<no jcmd>");
		}
	}

	/**
	 * Accepts a singleton Windows query object.
	 *
	 * @throws IOException if fixture setup or report writing fails
	 */
	@Test
	public void acceptsSingleWindowsRow() throws IOException
	{
		try (TestContext context = loadContext(); DefaultToolingScope scope = new DefaultToolingScope())
		{
			QuietBuildDump dump = new QuietBuildDump(scope, (command, childContext, timeout) ->
				captured(resource("windows-one.json"), ""), context.captureContext(), QuietBuildDump.Platform.WINDOWS,
				context.temporaryDirectory(), noNativeFacts());
			StringWriter output = new StringWriter();
			dump.dump(null, output);
			requireThat(output.toString(), "output").contains("pid=10 ppid=4 threads=2 suspended=0 held=no");
		}
	}

	/**
	 * Selects stopped processes, their direct children, and Java diagnostics from portable process text.
	 *
	 * @throws IOException if fixture setup or report writing fails
	 */
	@Test
	public void selectsPortableNativeSamples() throws IOException
	{
		try (TestContext context = loadContext(); DefaultToolingScope scope = new DefaultToolingScope())
		{
			List<List<String>> commands = new ArrayList<>();
			QuietBuildNativeFacts.Access nativeFacts = new QuietBuildNativeFacts.Access()
			{
				@Override
				public long effectiveUid()
				{
					return 1000;
				}

				@Override
				public void writeDarwinStop(long pid, java.io.Writer output) throws IOException
				{
					output.write("stop-facts-" + pid + "\n");
				}
			};
			QuietBuildDump dump = new QuietBuildDump(scope, (command, childContext, timeout) ->
			{
				commands.add(command);
				String listing = "";
				if (command.getFirst().equals("ps"))
					listing = resource("darwin-ps.txt");
				return captured(listing, "");
			}, context.captureContext(), QuietBuildDump.Platform.DARWIN, context.temporaryDirectory().resolve("absent"),
				nativeFacts);
			StringWriter output = new StringWriter();
			dump.dump(Path.of("jcmd"), output);
			requireThat(output.toString(), "output").contains("--- pid=6322 state=T");
			requireThat(output.toString(), "output").contains("--- pid=6327 state=S");
			requireThat(output.toString(), "output").contains("--- pid=91 state=S cmd=/opt/runtime/bin/java");
			requireThat(output.toString(), "output").doesNotContain("--- pid=90 state=S");
			requireThat(output.toString(), "output").contains("stop-facts-6322");
			requireThat(commands, "commands").contains(List.of("jcmd", "91", "Thread.print", "-l"));
			requireThat(commands.getFirst(), "first_command").isEqualTo(List.of("ps", "-axww", "-o",
				"pid=,ppid=,uid=,state=,pgid=,sess=,tt=,wchan=,command="));
		}
	}

	/**
	 * Renders zombies from comm when Linux cmdline has no usable command.
	 *
	 * @throws IOException if fixture setup or report writing fails
	 */
	@Test
	public void keepsZombieAndDeniedFacts() throws IOException
	{
		try (TestContext context = loadContext(); DefaultToolingScope scope = new DefaultToolingScope())
		{
			Path procfs = Files.createTempDirectory(context.temporaryDirectory(), "zombie-procfs-");
			Path zombie = procfs.resolve("5");
			Files.createDirectories(zombie);
			Files.writeString(zombie.resolve("cmdline"), "");
			Files.writeString(zombie.resolve("comm"), "sleeping-worker\n");
			Files.writeString(zombie.resolve("status"), "State: Z (zombie)\nPPid: 1\nTracerPid: 0\nUid: 1 1 1 1\n");
			StringWriter output = new StringWriter();
			new QuietBuildDump(scope, (command, childContext, timeout) ->
			{
				throw new AssertionError("Linux procfs reporting must not start a process");
			}, context.captureContext(), QuietBuildDump.Platform.LINUX, procfs, noNativeFacts()).dump(null, output);
			requireThat(output.toString(), "output").contains("cmd=[sleeping-worker] <defunct>");
			requireThat(output.toString(), "output").contains("pid=5 ppid=1 tracer=0 uid=1 state=Z");
			requireThat(output.toString(), "output").doesNotContain("task=");
		}
	}

	/**
	 * Sorts process identifiers numerically across bounded spill runs.
	 *
	 * @throws IOException if fixture setup or sorting fails
	 */
	@Test
	public void sortsLargePidSnapshot() throws IOException
	{
		try (TestContext context = loadContext(); QuietBuildDumpSpool spool =
			new QuietBuildDumpSpool(context.temporaryDirectory()))
		{
			Path source = Files.createDirectory(context.temporaryDirectory().resolve("many-pids"));
			for (int pid = 4100; pid >= 1; pid = pid - 1)
				Files.createFile(source.resolve(Integer.toString(pid)));
			List<String> rows = Files.readAllLines(spool.sortedIds(source));
			requireThat(rows.size(), "row_count").isEqualTo(4100);
			requireThat(rows.getFirst(), "first_pid").isEqualTo("1");
			requireThat(rows.getLast(), "last_pid").isEqualTo("4100");
		}
	}

	/**
	 * Deletes its private spool after the report closes.
	 *
	 * @throws IOException if fixture setup or spool cleanup fails
	 */
	@Test
	public void removesOwnedDumpSpools() throws IOException
	{
		try (TestContext context = loadContext())
		{
			Path owned;
			try (QuietBuildDumpSpool spool = new QuietBuildDumpSpool(context.temporaryDirectory()))
			{
				owned = spool.copy(new StringReader("bounded copy\n")).getParent();
				requireThat(Files.isDirectory(owned), "owned_spool").isTrue();
			}
			requireThat(Files.exists(owned), "owned_spool_after_close").isFalse();
		}
	}

	/**
	 * Supplies non-native facts to the Linux report test.
	  *
	  * @return the requested result
	 */
	private static QuietBuildNativeFacts.Access noNativeFacts()
	{
		return new QuietBuildNativeFacts.Access()
		{
			@Override
			public long effectiveUid()
			{
				return 1000;
			}

			@Override
			public void writeDarwinStop(long pid, java.io.Writer output)
		{
			}
		};
	}

	/**
	 * Reads one supplied process-dump fixture.
	 *
	 * @param name fixture filename
	 * @return fixture text
	 * @throws IOException if the fixture cannot be read
	 */
	private static String resource(String name) throws IOException
	{
		String resourcePath = "/com/holdmyspot/codexunleashed/tooling/test/process-dump/" + name;
		try (InputStream input = QuietBuildDumpTest.class.getResourceAsStream(resourcePath))
		{
			requireThat(input, "input").isNotNull();
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * Creates a bounded fake capture around supplied stream text.
	 *
	 * @param stdout fake standard output
	 * @param stderr fake standard error
	 * @return owned fake capture
	 */
	private static QuietBuildCapture.Captured captured(String stdout, String stderr)
	{
		return new QuietBuildCapture.Captured()
		{
			@Override
			public int status()
			{
				return 0;
			}

			@Override
			public java.io.Reader stdout()
			{
				return new StringReader(stdout);
			}

			@Override
			public java.io.Reader stderr()
			{
				return new StringReader(stderr);
			}

			@Override
			public void close()
			{
			}
		};
	}

	/**
	 * Loads the explicit runner context and creates a test-owned temporary child.
	 *
	 * @return private test context
	 * @throws IOException if the runner context cannot be read or the child directory cannot be created
	 */
	private static TestContext loadContext() throws IOException
	{
		String path = System.getProperty("quiet.build.test.context");
		Path contextPath = Path.of(path);
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			JsonNode json = scope.getJsonMapper().readTree(contextPath.toFile());
			Path workingDirectory = Path.of(json.get("working_directory").textValue());
			Path temporaryDirectory = Files.createTempDirectory(Path.of(json.get("temporary_directory").textValue()),
				"process-dump-test-");
			Map<String, String> environment = new HashMap<>();
			for (Map.Entry<String, JsonNode> entry : json.get("environment").properties())
				environment.put(entry.getKey(), entry.getValue().textValue());
			environment.put("TMPDIR", temporaryDirectory.toString());
			environment.put("TMP", temporaryDirectory.toString());
			environment.put("TEMP", temporaryDirectory.toString());
			return new TestContext(new QuietBuildCapture.Context(workingDirectory, temporaryDirectory, environment),
				temporaryDirectory);
		}
	}

	/**
	 * Private runner context and temporary directory owned by one test.
	 *
	 * @param captureContext child-process context for the test
	 * @param temporaryDirectory test-owned scratch directory
	 */
	private record TestContext(QuietBuildCapture.Context captureContext, Path temporaryDirectory) implements AutoCloseable
	{
		/**
		 * Deletes this test's private temporary directory.
		 *
		 * @throws IOException if an owned path cannot be removed
		 */
		@Override
		public void close() throws IOException
		{
			try (java.util.stream.Stream<Path> paths = Files.walk(temporaryDirectory))
			{
				for (Path path : paths.sorted((first, second) -> second.compareTo(first)).toList())
					Files.deleteIfExists(path);
			}
		}
	}
}
