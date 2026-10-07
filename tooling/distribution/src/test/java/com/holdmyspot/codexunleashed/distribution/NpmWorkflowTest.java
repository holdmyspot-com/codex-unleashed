package com.holdmyspot.codexunleashed.distribution;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Verifies the maintained npm publication step through a fresh image and a controlled npm command boundary.
 */
public final class NpmWorkflowTest
{
	private static final List<String> TARGETS = List.of("x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl",
		"x86_64-apple-darwin", "aarch64-apple-darwin", "x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc");
	private static final List<String> PLATFORMS = List.of("linux-x64", "linux-arm64", "darwin-x64", "darwin-arm64",
		"win32-x64", "win32-arm64", "main");

	/** Creates the workflow tests. */
	public NpmWorkflowTest()
	{
	}

	/**
	 * Executes the actual workflow with native tar inputs, verifies all npm arguments and cwd values,
	 * and rejects external Java, Python, and Codex commands.
	 *
	 * @throws IOException if fixture, linking, native processes, or cleanup fail
	 * @throws URISyntaxException if rejected-command fixture locations cannot be represented
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test
	public void publishesThroughMaintainedWorkflow() throws IOException, URISyntaxException, InterruptedException
	{
		Path root = Files.createTempDirectory("npm-workflow-");
		try
		{
			Path archives = Files.createDirectory(root.resolve("release-stage"));
			Path stdout = root.resolve("stdout");
			Path stderr = root.resolve("stderr");
			String tar = NativeUtilities.tarExecutable();
			for (String target : TARGETS)
			{
				Path source = Files.createDirectory(root.resolve(target));
				for (String name : List.of("bin/codex", "LICENSE.md", "docs/LICENSE.html", "docs/terms.html",
					"docs/privacy.html", "licenses/example/LICENSE"))
				{
					Path file = source.resolve(name);
					Files.createDirectories(file.getParent());
					Files.write(file, new byte[]{0, (byte) 255, 1});
				}
				assertEquals(run(List.of(tar, "-czf", archives.resolve("codex-package-" + target + ".tar.gz").toString(),
					"-C", source.toString(), "."), root, Map.of(), stdout, stderr), 0, Files.readString(stderr));
			}
			Path runtime = root.resolve("runtime with spaces");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), runtime.toString()});
			Path commands = Files.createDirectory(root.resolve("commands"));
			for (String name : List.of("java", "python", "python3", "codex"))
				JavaCommandFixtures.writeLauncher(commands.resolve(name), RejectedCommandFixture.class);
			writeNpmFixture(commands);
			Path temporary = Files.createDirectory(root.resolve("temporary"));
			Path calls = root.resolve("npm-calls");
			Map<String, String> environment = new HashMap<>();
			environment.put("PATH", commands + File.pathSeparator + System.getenv("PATH"));
			environment.put("CODEX_UNLEASHED_TOOLING", runtime.resolve("bin/codex-tooling").toString());
			environment.put("GITHUB_REPOSITORY", "holdmyspot-com/codex-unleashed");
			environment.put("NPM_CALLS", calls.toString());
			environment.put("NPM_FIXTURE_TAR", tar);
			environment.put("TMPDIR", temporary.toString());
			environment.put("XDG_CACHE_HOME", root.resolve("xdg").toString());
			String command = WorkflowCommands.readStepCommand("publish", "Publish npm packages").
				replace("${{ needs.prepare.outputs.patched_tag }}", "rust-v0.160.0+34");
			assertEquals(run(List.of("bash", "-c", command), root, environment, stdout, stderr), 0,
				Files.readString(stderr));
			assertCalls(Files.readAllLines(calls), root.resolve("npm-release"));
			try (Stream<Path> files = Files.list(root.resolve("npm-release")))
			{
				assertEquals(files.filter(path -> path.toString().endsWith(".tgz")).count(), 14L);
			}
			assertTrue(Files.readString(root.resolve("npm-release/packages/public/main/bin/codex.js")).
				contains("@holdmyspot/codex-unleashed-${platformName}"));
			assertEquals(Files.readAllBytes(root.resolve("npm-release/packages/ea/main/licenses/example/LICENSE")),
				new byte[]{0, (byte) 255, 1});
			assertTemporaryEmpty(temporary);
			environment.put("NPM_FAIL_OP", "publish");
			assertEquals(run(List.of("bash", "-c", command), root, environment, stdout, stderr), 1);
			assertTrue(Files.readString(stderr).contains("npm publish failed with status 7"));
			assertTemporaryEmpty(temporary);
		}
		finally
		{
			try (Stream<Path> files = Files.walk(root))
			{
				for (Path file : files.sorted(Comparator.reverseOrder()).toList())
					Files.delete(file);
			}
		}
	}

	/**
	 * Supplies npm's platform launcher and a controlled Node entry point with native tar packaging.
	 *
	 * @param commands the fixture command directory
	 * @throws IOException if the fixture cannot be written
	 */
	private static void writeNpmFixture(Path commands) throws IOException
	{
		Path bin = Files.createDirectories(commands.resolve("node_modules/npm/bin"));
		Files.writeString(bin.resolve("npm-prefix.js"), "");
		Files.writeString(bin.resolve("npm-cli.js"), """
			const fs = require('node:fs');
			const path = require('node:path');
			const {spawnSync} = require('node:child_process');
			const args = process.argv.slice(2);
			const cwd = process.cwd();
			fs.appendFileSync(process.env.NPM_CALLS, [cwd, ...args].join('\\n') + '\\n');
			if (process.env.NPM_FAIL_OP === args[0]) process.exit(7);
			if (args[0] === 'pack') {
			  const family = path.basename(path.dirname(cwd));
			  const archive = path.join(args[2], family + '-' + path.basename(cwd) + '.tgz');
			  const result = spawnSync(process.env.NPM_FIXTURE_TAR, ['-czf', archive, '-C', cwd, '.'], {stdio: 'inherit'});
			  if (result.error) throw result.error;
			  process.exit(result.status === null ? 1 : result.status);
			}
			""");
		if (File.separatorChar == '\\')
			Files.writeString(commands.resolve("npm.cmd"), "");
		else
		{
			Path npm = commands.resolve("npm");
			Files.writeString(npm, "#!/bin/sh\nexec node \"${0%/*}/node_modules/npm/bin/npm-cli.js\" \"$@\"\n");
			if (Files.getFileAttributeView(npm, PosixFileAttributeView.class) != null)
				Files.setPosixFilePermissions(npm, PosixFilePermissions.fromString("rwx------"));
		}
	}

	/**
	 * Checks all pack and publication argument boundaries and package working directories in order.
	 *
	 * @param calls the controlled npm invocation log
	 * @param output the resolved output directory
	 */
	private static void assertCalls(List<String> calls, Path output)
	{
		int offset = 0;
		for (String operation : List.of("pack", "publish"))
			for (String family : List.of("public", "ea"))
				for (String platform : PLATFORMS)
				{
					assertEquals(calls.get(offset), output.resolve("packages").resolve(family).resolve(platform).toString());
					offset += 1;
					List<String> expected;
					if (operation.equals("pack"))
						expected = List.of("pack", "--pack-destination", output.toString(), "--registry",
							"https://registry.npmjs.org");
					else
					{
						String access = "public";
						if (family.equals("ea"))
							access = "restricted";
						expected = List.of("publish", "--access", access, "--tag", "latest", "--registry",
							"https://registry.npmjs.org");
					}
					assertEquals(calls.subList(offset, offset + expected.size()), expected);
					offset += expected.size();
				}
		assertEquals(calls.size(), offset);
	}

	/**
	 * Checks extraction and npm-cache resources are absent after success and failure.
	 *
	 * @param temporary the fixture's managed temporary directory
	 * @throws IOException if directory enumeration fails
	 */
	private static void assertTemporaryEmpty(Path temporary) throws IOException
	{
		try (Stream<Path> files = Files.list(temporary))
		{
			assertEquals(files.count(), 0L);
		}
	}

	/**
	 * Captures native processes with explicit fixture storage and independent EOF input.
	 *
	 * @param command the literal command arguments
	 * @param working the process cwd
	 * @param environment the explicit environment overrides
	 * @param stdout the owned raw output capture
	 * @param stderr the owned diagnostic capture
	 * @return the ordinary exit status
	 * @throws IOException if process startup or input closing fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static int run(List<String> command, Path working, Map<String, String> environment, Path stdout, Path stderr)
		throws IOException, InterruptedException
	{
		ProcessBuilder builder = NativeCommands.createBuilder(command).directory(working.toFile()).
			redirectOutput(stdout.toFile()).
			redirectError(stderr.toFile());
		builder.environment().putAll(environment);
		try (Process process = builder.start())
		{
			process.getOutputStream().close();
			return process.waitFor();
		}
	}
}
