package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
