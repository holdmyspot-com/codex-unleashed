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
		Path root = Files.createTempDirectory("cargo-scheduler-cli-");
		try
		{
			Path compiler = Files.writeString(root.resolve("Compiler.java"), """
				class Compiler {
				    public static void main(String[] args) {
				        if (args.length == 1 && args[0].equals("query")) {
				            System.out.print("query");
				            System.exit(5);
				        }
				        if (args.length != 3 || !args[0].equals("--crate-name") ||
				                !args[1].equals("fixture") || !args[2].equals("λ and spaces"))
				            throw new AssertionError("Compiler arguments changed");
				        System.out.print("compiled");
				        System.err.print("diagnostic");
				        System.exit(17);
				    }
				}
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
				const result = require('node:child_process').spawnSync(process.env.RUSTC_WRAPPER,
				  [process.argv[2], '--source=27', process.argv[3], ...args], {encoding: 'utf8', env, timeout: 10000});
				if (result.error) throw result.error;
				process.stdout.write(result.stdout);
				process.stderr.write(result.stderr);
				process.exit(result.status);
				""");
			Map<String, String> environment = new HashMap<>(System.getenv());
			environment.put("NODE_OPTIONS", "");
			String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
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
				List.of("node", build.toString(), java, compiler.toString(), mode));
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
