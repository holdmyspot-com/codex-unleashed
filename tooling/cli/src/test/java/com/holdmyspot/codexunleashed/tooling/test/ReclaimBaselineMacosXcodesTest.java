package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.ReclaimBaselineMacosXcodes;
import com.holdmyspot.codexunleashed.tooling.ReclaimBaselineMacosXcodes.Host;
import com.holdmyspot.codexunleashed.tooling.ReclaimBaselineMacosXcodes.NativeTools;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.commons.io.file.PathUtils;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.tooling.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies that inactive Xcode versions are removed while the selected Xcode, unrelated applications and symbolic links
 * are preserved, and that the evidence is recorded in the required order.
 */
public final class ReclaimBaselineMacosXcodesTest
{
	/**
	 * The environment of a disposable GitHub-hosted runner.
	 */
	private static final Map<String, String> HOSTED = Map.of("GITHUB_ACTIONS", "true", "RUNNER_ENVIRONMENT",
		"github-hosted");

	/**
	 * Creates the tests.
	 */
	public ReclaimBaselineMacosXcodesTest()
	{
	}

	/**
	 * Selection chooses the older version only, preserving the active and unrelated directories.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void preservesSelectedAndUnrelatedApps() throws IOException
	{
		try (Applications fixture = new Applications())
		{
			Path unrelated = fixture.directory.createDirectories("Applications/Browser.app");
			requireThat(ReclaimBaselineMacosXcodes.inactiveXcodes(fixture.root, fixture.selected), "inactive").
				isEqualTo(List.of(fixture.old));
			requireThat(Files.isDirectory(fixture.selected), "selected").isTrue();
			requireThat(Files.isDirectory(unrelated), "unrelated").isTrue();
		}
	}

	/**
	 * A selected unversioned symbolic link preserves its versioned Xcode target.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void preservesSelectedViaUnversionedAlias() throws IOException
	{
		TestRequirements.requirePosixFileSystem();
		try (Applications fixture = new Applications())
		{
			Path alias = Files.createSymbolicLink(fixture.root.resolve("Xcode.app"),
				fixture.selected.getParent().getParent());
			requireThat(ReclaimBaselineMacosXcodes.inactiveXcodes(fixture.root,
				alias.resolve("Contents").resolve("Developer")), "inactive").isEqualTo(List.of(fixture.old));
		}
	}

	/**
	 * Selection skips symbolic links and preserves their external targets.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void doesNotSelectSymbolicLinksToOtherTrees() throws IOException
	{
		TestRequirements.requirePosixFileSystem();
		try (Applications fixture = new Applications())
		{
			Path outside = fixture.directory.createDirectories("external-tools");
			Files.createSymbolicLink(fixture.root.resolve("Xcode_alias.app"), outside);
			requireThat(ReclaimBaselineMacosXcodes.inactiveXcodes(fixture.root, fixture.selected), "inactive").
				isEqualTo(List.of(fixture.old));
			requireThat(Files.isDirectory(outside), "outside").isTrue();
		}
	}

	/**
	 * A selected directory outside an Xcode application is rejected before any removal is selected.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void rejectsCommandLineToolsBeforePlan() throws IOException
	{
		try (Applications fixture = new Applications())
		{
			Path tools = fixture.directory.createDirectories("CommandLineTools");
			IllegalArgumentException failure = requireException(IllegalArgumentException.class,
				() -> ReclaimBaselineMacosXcodes.inactiveXcodes(fixture.root, tools));
			requireThat(failure.getMessage(), "message").contains("not an Xcode application");
			requireThat(Files.isDirectory(fixture.old), "old").isTrue();
		}
	}

	/**
	 * A missing selected developer directory is an error, not an empty selection.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void rejectsMissingSelectedDeveloperDirectory() throws IOException
	{
		try (Applications fixture = new Applications())
		{
			requireException(NoSuchFileException.class, () -> ReclaimBaselineMacosXcodes.inactiveXcodes(
				fixture.root, fixture.selected.resolve("missing")));
			requireThat(Files.isDirectory(fixture.old), "old").isTrue();
		}
	}

	/**
	 * The command records the plan, removes the inactive application, checks the tools and the compiler, and records the
	 * result.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void removesInactiveXcodesAndRecordsEvidence() throws IOException
	{
		try (Applications fixture = new Applications())
		{
			FakeHost host = fixture.host();
			Path evidence = fixture.directory.getPath().resolve("evidence");
			Output output = fixture.run(host, "Mac OS X", HOSTED, "--evidence-directory", evidence.toString());

			requireThat(output.status, "status").isEqualTo(0);
			requireThat(output.err, "err").isEqualTo("");
			requireThat(output.out, "out").isEqualTo("Removed 1 inactive Xcode versions; recovered 150 bytes" +
				System.lineSeparator());
			requireThat(Files.exists(fixture.old), "oldRemoved").isFalse();
			requireThat(Files.isDirectory(fixture.selected), "selected").isTrue();
			requireThat(host.removed, "removed").isEqualTo(List.of(fixture.old));
			requireThat(host.planWasWrittenBeforeRemoval, "planFirst").isTrue();
			String developer = fixture.selected.toString().replace("\\", "\\\\");
			requireThat(Files.readString(evidence.resolve("xcode-cleanup-plan.json")), "plan").isEqualTo("""
				{
				  "applications_base": "%1$s",
				  "planned_removals": [
				    "Xcode_16.3.app"
				  ],
				  "free_bytes": 100,
				  "selected_tools": {
				    "developer": "%2$s",
				    "sdk": "/sdk",
				    "compiler": "clang version 17\\n"
				  }
				}
				""".formatted(fixture.root.toString().replace("\\", "\\\\"), developer));
			requireThat(Files.readString(evidence.resolve("xcode-cleanup-result.json")), "result").isEqualTo("""
				{
				  "free_bytes": 250,
				  "selected_tools": {
				    "developer": "%1$s",
				    "sdk": "/sdk",
				    "compiler": "clang version 17\\n"
				  },
				  "compiler_smoke": "passed",
				  "removed_applications": [
				    "Xcode_16.3.app"
				  ]
				}
				""".formatted(developer));
			try (Stream<Path> entries = Files.list(evidence))
			{
				requireThat(entries.map(entry -> entry.getFileName().toString()).sorted().toList(), "evidence").
					isEqualTo(List.of("xcode-cleanup-plan.json", "xcode-cleanup-result.json"));
			}
		}
	}

	/**
	 * The command refuses to run, without touching the runner, anywhere but a disposable hosted macOS runner.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesNonDisposableRunners() throws IOException
	{
		try (Applications fixture = new Applications())
		{
			Path evidence = fixture.directory.getPath().resolve("evidence");
			List<Output> outputs = new ArrayList<>();
			FakeHost host = fixture.host();
			outputs.add(fixture.run(host, "Linux", HOSTED, "--evidence-directory", evidence.toString()));
			outputs.add(fixture.run(host, "Mac OS X", Map.of("GITHUB_ACTIONS", "true", "RUNNER_ENVIRONMENT",
				"self-hosted"), "--evidence-directory", evidence.toString()));
			outputs.add(fixture.run(host, "Mac OS X", Map.of("RUNNER_ENVIRONMENT", "github-hosted"),
				"--evidence-directory", evidence.toString()));
			for (Output output : outputs)
			{
				requireThat(output.status, "status").isEqualTo(1);
				requireThat(output.err, "err").isEqualTo("ERROR: Xcode reclamation requires a disposable GitHub-hosted " +
					"macOS runner" + System.lineSeparator());
			}
			requireThat(host.calls, "hostCalls").isEqualTo(0);
			requireThat(Files.isDirectory(fixture.old), "old").isTrue();
			requireThat(Files.exists(evidence), "evidence").isFalse();
		}
	}

	/**
	 * The command rejects arguments other than the evidence directory.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void rejectsInvalidArguments() throws IOException
	{
		try (Applications fixture = new Applications())
		{
			FakeHost host = fixture.host();
			Output output = fixture.run(host, "Mac OS X", HOSTED);
			requireThat(output.status, "status").isEqualTo(2);
			requireThat(output.err, "err").contains("Usage: codex-tooling reclaim-baseline-macos-xcodes");
			requireThat(host.calls, "hostCalls").isEqualTo(0);
		}
	}

	/**
	 * A command-line-tools selection fails before anything is removed or recorded.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void failsBeforeRemovalOnCommandLineTools() throws IOException
	{
		try (Applications fixture = new Applications())
		{
			Path tools = fixture.directory.createDirectories("CommandLineTools");
			FakeHost host = new FakeHost(fixture, new NativeTools(tools.toString(), "/sdk", "clang version 17\n"));
			Path evidence = fixture.directory.getPath().resolve("evidence");
			Output output = fixture.run(host, "Mac OS X", HOSTED, "--evidence-directory", evidence.toString());
			requireThat(output.status, "status").isEqualTo(1);
			requireThat(output.err, "err").contains("not an Xcode application");
			requireThat(host.removed, "removed").isEqualTo(List.of());
			requireThat(Files.isDirectory(fixture.old), "old").isTrue();
			requireThat(Files.exists(evidence), "evidence").isFalse();
		}
	}

	/**
	 * A change of the selected tools after the removal fails without recording a result.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void failsWhenTheSelectedToolsChange() throws IOException
	{
		try (Applications fixture = new Applications())
		{
			FakeHost host = fixture.host();
			host.afterRemoval = new NativeTools(fixture.selected.toString(), "/sdk", "clang version 18\n");
			Path evidence = fixture.directory.getPath().resolve("evidence");
			Output output = fixture.run(host, "Mac OS X", HOSTED, "--evidence-directory", evidence.toString());
			requireThat(output.status, "status").isEqualTo(1);
			requireThat(output.err, "err").contains("Selected native tools changed");
			requireThat(Files.exists(evidence.resolve("xcode-cleanup-plan.json")), "plan").isTrue();
			requireThat(Files.exists(evidence.resolve("xcode-cleanup-result.json")), "result").isFalse();
		}
	}

	/**
	 * A compiler smoke test that prints something unexpected fails without recording a result, and its scratch directory
	 * is removed.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void failsWhenTheCompilerSmokeIsWrong() throws IOException
	{
		try (Applications fixture = new Applications())
		{
			FakeHost host = fixture.host();
			host.smokeOutput = "something else\n";
			Path evidence = fixture.directory.getPath().resolve("evidence");
			Output output = fixture.run(host, "Mac OS X", HOSTED, "--evidence-directory", evidence.toString());
			requireThat(output.status, "status").isEqualTo(1);
			requireThat(output.err, "err").contains("Native compiler smoke produced unexpected output: something else");
			requireThat(Files.exists(evidence.resolve("xcode-cleanup-result.json")), "result").isFalse();
			try (Stream<Path> entries = Files.list(evidence))
			{
				requireThat(entries.map(entry -> entry.getFileName().toString()).toList(), "evidence").
					isEqualTo(List.of("xcode-cleanup-plan.json"));
			}
		}
	}

	/**
	 * A removal that leaves the application in place fails.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void failsWhenApplicationRemains() throws IOException
	{
		try (Applications fixture = new Applications())
		{
			FakeHost host = fixture.host();
			host.deletes = false;
			Path evidence = fixture.directory.getPath().resolve("evidence");
			Output output = fixture.run(host, "Mac OS X", HOSTED, "--evidence-directory", evidence.toString());
			requireThat(output.status, "status").isEqualTo(1);
			requireThat(output.err, "err").contains("Inactive Xcode application remains after removal: " + fixture.old);
		}
	}

	/**
	 * A command's status and printed streams.
	 *
	 * @param status the exit status
	 * @param out    the standard output
	 * @param err    the standard error
	 */
	private record Output(int status, String out, String err)
	{
	}

	/**
	 * A fixture Applications directory with a selected and an older versioned Xcode.
	 */
	private static final class Applications implements AutoCloseable
	{
		/**
		 * The fixture's root.
		 */
		private final TestDirectory directory;
		/**
		 * The directory that holds the Xcode versions.
		 */
		private final Path root;
		/**
		 * The selected developer directory.
		 */
		private final Path selected;
		/**
		 * The older Xcode application.
		 */
		private final Path old;

		/**
		 * Creates the fixture.
		 *
		 * @throws IOException if the fixture cannot be created
		 */
		private Applications() throws IOException
		{
			// The real path matters because the command compares resolved paths, and macOS temporary directories are
			// reached through a symbolic link.
			directory = new TestDirectory("xcodes-");
			Path base = directory.getPath().toRealPath();
			root = Files.createDirectories(base.resolve("Applications"));
			selected = Files.createDirectories(root.resolve("Xcode_16.4.app/Contents/Developer"));
			old = Files.createDirectories(root.resolve("Xcode_16.3.app/Contents/Developer")).getParent().
				getParent();
		}

		/**
		 * Creates a host that selects the fixture's selected Xcode.
		 *
		 * @return the host
		 */
		private FakeHost host()
		{
			return new FakeHost(this, new NativeTools(selected.toString(), "/sdk", "clang version 17\n"));
		}

		/**
		 * Runs the command against this fixture.
		 *
		 * @param host            the runner's tools
		 * @param operatingSystem the operating system name
		 * @param environment     the environment
		 * @param arguments       the command arguments
		 * @return the status and the printed streams
		 */
		private Output run(FakeHost host, String operatingSystem, Map<String, String> environment, String... arguments)
		{
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ByteArrayOutputStream err = new ByteArrayOutputStream();
			int status;
			try (DefaultToolingScope scope = new DefaultToolingScope();
				PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
				PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8))
			{
				status = ReclaimBaselineMacosXcodes.run(scope, arguments, operatingSystem, environment, root, host,
					outStream, errStream);
			}
			return new Output(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
		}

		@Override
		public void close() throws IOException
		{
			directory.close();
		}
	}

	/**
	 * A runner whose tools and disk are scripted.
	 */
	private static final class FakeHost implements Host
	{
		/**
		 * The fixture whose plan file the host checks.
		 */
		private final Applications fixture;
		/**
		 * The tools that the selected Xcode provides.
		 */
		private final NativeTools tools;
		/**
		 * The applications that were removed.
		 */
		private final List<Path> removed = new ArrayList<>();
		/**
		 * The tools after the first removal, or null if they do not change.
		 */
		private NativeTools afterRemoval;
		/**
		 * The output of the compiler smoke test.
		 */
		private String smokeOutput = "baseline native tool smoke\n";
		/**
		 * Whether a removal deletes the application.
		 */
		private boolean deletes = true;
		/**
		 * Whether the plan file existed at the first removal.
		 */
		private boolean planWasWrittenBeforeRemoval;
		/**
		 * The number of calls of any method.
		 */
		private int calls;
		/**
		 * The number of free-space reads.
		 */
		private int spaceReads;

		/**
		 * Creates a host.
		 *
		 * @param fixture the fixture whose plan file the host checks
		 * @param tools   the tools that the selected Xcode provides
		 */
		private FakeHost(Applications fixture, NativeTools tools)
		{
			this.fixture = fixture;
			this.tools = tools;
		}

		@Override
		public NativeTools nativeTools()
		{
			++calls;
			if (!removed.isEmpty() && afterRemoval != null)
				return afterRemoval;
			return tools;
		}

		@Override
		public long availableBytes(Path path)
		{
			++calls;
			++spaceReads;
			if (spaceReads == 1)
				return 100;
			return 250;
		}

		@Override
		public void remove(Path application) throws IOException
		{
			++calls;
			planWasWrittenBeforeRemoval = Files.exists(fixture.directory.getPath().resolve("evidence").
				resolve("xcode-cleanup-plan.json"));
			removed.add(application);
			if (deletes)
				PathUtils.deleteDirectory(application);
		}

		@Override
		public String compileAndRunSmoke(Path scratch) throws IOException
		{
			++calls;
			Files.writeString(scratch.resolve("smoke.c"), "int main(void) { return 0; }\n");
			return smokeOutput;
		}
	}
}
