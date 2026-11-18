package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.io.file.PathUtils;

/**
 * Reclaims disk space on a disposable GitHub-hosted macOS runner by removing the Xcode versions that are not selected.
 * <p>
 * The command refuses to run anywhere else. It records its plan before it deletes anything, then verifies that the
 * selected developer directory, SDK and compiler are unchanged and that the compiler still builds and runs a C program.
 * It records the result only after those checks pass. A failure does not restore applications that were already
 * removed.
 */
public final class ReclaimBaselineMacosXcodes
{
	/**
	 * The command name.
	 */
	public static final String NAME = "reclaim-baseline-macos-xcodes";

	/**
	 * The directory that holds the runner's Xcode versions.
	 */
	private static final Path APPLICATIONS = Path.of("/Applications");
	/**
	 * The option that names the directory that receives the plan and the result.
	 */
	private static final String EVIDENCE_OPTION = "--evidence-directory";
	/**
	 * Names the arguments that the command accepts.
	 */
	private static final String USAGE = "Usage: codex-tooling " + NAME + " " + EVIDENCE_OPTION + " DIRECTORY";
	/**
	 * The program that proves that the selected compiler works.
	 */
	private static final String SMOKE_SOURCE = """
		#include <stdio.h>
		int main(void) { puts("baseline native tool smoke"); return 0; }
		""";
	/**
	 * What the program prints.
	 */
	private static final String SMOKE_OUTPUT = "baseline native tool smoke\n";

	/**
	 * The native tools that the selected Xcode provides.
	 *
	 * @param developer the selected developer directory, from {@code xcode-select -p}
	 * @param sdk       the SDK path, from {@code xcrun --show-sdk-path}
	 * @param compiler  the compiler's complete version report, from {@code xcrun clang --version}
	 */
	public record NativeTools(String developer, String sdk, String compiler)
	{
		/**
		 * Creates the tool identities.
		 *
		 * @param developer the selected developer directory
		 * @param sdk       the SDK path
		 * @param compiler  the compiler's version report
		 * @throws NullPointerException if an argument is null
		 */
		public NativeTools
		{
			Objects.requireNonNull(developer, "developer");
			Objects.requireNonNull(sdk, "sdk");
			Objects.requireNonNull(compiler, "compiler");
		}

		/**
		 * Describes the tools as the plan and the result record them.
		 *
		 * @return the tools by name, in the order that the evidence lists them
		 */
		public Map<String, String> toDocument()
		{
			Map<String, String> document = new LinkedHashMap<>();
			document.put("developer", developer);
			document.put("sdk", sdk);
			document.put("compiler", compiler);
			return document;
		}
	}

	/**
	 * The runner's native tools and disk, which the command reads and changes.
	 */
	public interface Host
	{
		/**
		 * Reads the identity of the selected native tools.
		 *
		 * @return the tools
		 * @throws IOException if a native command cannot run or exits unsuccessfully
		 */
		NativeTools nativeTools() throws IOException;

		/**
		 * Returns the bytes that an unprivileged process can still allocate on a filesystem.
		 *
		 * @param path a path on the filesystem
		 * @return the bytes, excluding blocks that the system reserves
		 * @throws NullPointerException if {@code path} is null
		 * @throws IOException          if the filesystem cannot be read
		 */
		long availableBytes(Path path) throws IOException;

		/**
		 * Removes a directory tree with administrator privileges.
		 *
		 * @param application the tree to remove
		 * @throws NullPointerException if {@code application} is null
		 * @throws IOException          if the removal cannot run or fails
		 */
		void remove(Path application) throws IOException;

		/**
		 * Compiles a C program with the selected compiler, runs it and returns what it printed.
		 *
		 * @param scratch an empty directory for the source and the executable
		 * @return the program's standard output
		 * @throws NullPointerException if {@code scratch} is null
		 * @throws IOException          if compiling or running fails
		 */
		String compileAndRunSmoke(Path scratch) throws IOException;
	}

	/**
	 * Prevents construction.
	 */
	private ReclaimBaselineMacosXcodes()
	{
	}

	/**
	 * Removes the inactive Xcode versions of this runner.
	 *
	 * @param scope       shared tooling values borrowed until the command finishes
	 * @param arguments   {@code --evidence-directory DIRECTORY}
	 * @param environment the process environment
	 * @param out         the summary
	 * @param err         diagnostics
	 * @return zero on success, one if this is not a disposable hosted macOS runner or a step fails, or two for invalid
	 *         command arguments
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(ToolingScope scope, String[] arguments, Map<String, String> environment, PrintStream out,
		PrintStream err)
	{
		Objects.requireNonNull(environment, "environment");
		return run(scope, arguments, System.getProperty("os.name"), environment, APPLICATIONS,
			new SystemHost(environment), out, err);
	}

	/**
	 * Removes the inactive Xcode versions of a runner.
	 *
	 * @param scope           shared tooling values borrowed until the command finishes
	 * @param arguments       {@code --evidence-directory DIRECTORY}
	 * @param operatingSystem the host's operating system name
	 * @param environment     the process environment
	 * @param applications    the directory that holds the Xcode versions
	 * @param host            the runner's native tools and disk
	 * @param out             the summary
	 * @param err             diagnostics
	 * @return zero on success, one if this is not a disposable hosted macOS runner or a step fails, or two for invalid
	 *         command arguments
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(ToolingScope scope, String[] arguments, String operatingSystem,
		Map<String, String> environment, Path applications, Host host, PrintStream out, PrintStream err)
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(operatingSystem, "operatingSystem");
		Objects.requireNonNull(environment, "environment");
		Objects.requireNonNull(applications, "applications");
		Objects.requireNonNull(host, "host");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");

		if (arguments.length != 2 || !arguments[0].equals(EVIDENCE_OPTION))
		{
			err.println(USAGE);
			return 2;
		}

		try
		{
			boolean hostedMacos = operatingSystem.toLowerCase(Locale.ROOT).startsWith("mac") &&
				"true".equals(environment.get("GITHUB_ACTIONS")) &&
				"github-hosted".equals(environment.get("RUNNER_ENVIRONMENT"));
			if (!hostedMacos)
				throw new IllegalStateException("Xcode reclamation requires a disposable GitHub-hosted macOS runner");
			Path evidence = Path.of(arguments[1]);
			out.println(reclaim(scope, evidence, applications, host));
			return 0;
		}
		catch (IOException | IllegalArgumentException | IllegalStateException failure)
		{
			err.println("ERROR: " + failure.getMessage());
			return 1;
		}
	}

	/**
	 * Finds the Xcode versions that can be removed: the sorted {@code Xcode_*.app} directories that are not symbolic
	 * links and are not the selected application. Nothing is changed.
	 *
	 * @param applications      the directory that holds the Xcode versions
	 * @param selectedDeveloper the selected developer directory; it must resolve to an existing
	 *                          {@code Xcode*.app/Contents/Developer} directory directly inside {@code applications}
	 * @return the inactive applications under {@code applications}, or an empty list if none qualifies
	 * @throws NullPointerException     if an argument is null
	 * @throws IllegalArgumentException if the selected developer directory is not inside an Xcode application
	 * @throws IOException              if a path cannot be resolved, such as a missing selected developer directory
	 */
	public static List<Path> inactiveXcodes(Path applications, Path selectedDeveloper) throws IOException
	{
		Objects.requireNonNull(applications, "applications");
		Objects.requireNonNull(selectedDeveloper, "selectedDeveloper");

		Path selected = selectedDeveloper.toRealPath();
		Path selectedApp = null;
		boolean selectedIsXcode = false;
		if (Files.isDirectory(selected) && selected.getNameCount() >= 3 &&
			selected.getFileName().toString().equals("Developer") &&
			selected.getParent().getFileName().toString().equals("Contents"))
		{
			selectedApp = selected.getParent().getParent();
			String name = selectedApp.getFileName().toString();
			selectedIsXcode = selectedApp.getParent().equals(applications.toRealPath()) && name.startsWith("Xcode") &&
				name.endsWith(".app");
		}
		if (!selectedIsXcode)
		{
			throw new IllegalArgumentException("Selected developer directory is not an Xcode application: " + selected);
		}

		List<Path> candidates = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(applications, "Xcode_*.app"))
		{
			for (Path app : stream)
			{
				if (!Files.isSymbolicLink(app) && Files.isDirectory(app) && !app.toRealPath().equals(selectedApp))
					candidates.add(app);
			}
		}
		candidates.sort(null);
		return candidates;
	}

	/**
	 * Removes the inactive Xcode versions, with the plan recorded first and the result recorded last.
	 *
	 * @param scope        shared tooling values
	 * @param evidence     the directory that receives the plan and the result; created if missing
	 * @param applications the directory that holds the Xcode versions
	 * @param host         the runner's native tools and disk
	 * @return the summary line
	 * @throws IllegalArgumentException if the selected developer directory is not inside an Xcode application
	 * @throws IOException              if a step fails; earlier removals are not undone
	 */
	private static String reclaim(ToolingScope scope, Path evidence, Path applications, Host host) throws IOException
	{
		NativeTools beforeTools = host.nativeTools();
		List<Path> candidates = inactiveXcodes(applications, Path.of(beforeTools.developer()));
		Files.createDirectories(evidence);
		List<String> removals = new ArrayList<>();
		for (Path app : candidates)
			removals.add(app.getFileName().toString());
		long freeBefore = host.availableBytes(applications);
		Map<String, Object> plan = new LinkedHashMap<>();
		plan.put("applications_base", applications.toString());
		plan.put("planned_removals", removals);
		plan.put("free_bytes", freeBefore);
		plan.put("selected_tools", beforeTools.toDocument());
		Files.writeString(evidence.resolve("xcode-cleanup-plan.json"),
			ArtifactJson.formatPreservingKeyOrder(scope, plan));

		for (Path app : candidates)
		{
			host.remove(app);
			if (Files.exists(app))
				throw new IOException("Inactive Xcode application remains after removal: " + app);
		}
		NativeTools afterTools = host.nativeTools();
		if (!afterTools.equals(beforeTools))
		{
			throw new IOException("Selected native tools changed: before=" + beforeTools.toDocument() + "; after=" +
				afterTools.toDocument());
		}
		verifyCompiler(host, evidence);

		long freeAfter = host.availableBytes(applications);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("free_bytes", freeAfter);
		result.put("selected_tools", afterTools.toDocument());
		result.put("compiler_smoke", "passed");
		result.put("removed_applications", removals);
		Files.writeString(evidence.resolve("xcode-cleanup-result.json"),
			ArtifactJson.formatPreservingKeyOrder(scope, result));
		return "Removed " + candidates.size() + " inactive Xcode versions; recovered " + (freeAfter - freeBefore) +
			" bytes";
	}

	/**
	 * Requires the selected compiler to build and run a C program.
	 *
	 * @param host     the runner's native tools
	 * @param evidence the directory in which to create the scratch directory
	 * @throws IOException if compiling or running fails or the program prints something unexpected
	 */
	private static void verifyCompiler(Host host, Path evidence) throws IOException
	{
		Path scratch = Files.createTempDirectory(evidence, "native-compiler-");
		try
		{
			String output = host.compileAndRunSmoke(scratch);
			if (!output.equals(SMOKE_OUTPUT))
				throw new IOException("Native compiler smoke produced unexpected output: " + output);
		}
		finally
		{
			PathUtils.deleteDirectory(scratch);
		}
	}

	/**
	 * The runner's real native tools and disk.
	 */
	private static final class SystemHost implements Host
	{
		/**
		 * The environment of the removal command, which {@code sudo} needs whole to find its tools.
		 */
		private final Map<String, String> environment;
		/**
		 * The working directory of every command, which is the caller's.
		 */
		private final Path workingDirectory = Path.of("").toAbsolutePath();
		/**
		 * The parent of the directories that hold a command's captured output.
		 */
		private final Path temporaryDirectory = Path.of(System.getProperty("java.io.tmpdir"));

		/**
		 * Creates a host.
		 *
		 * @param environment the process environment
		 */
		private SystemHost(Map<String, String> environment)
		{
			this.environment = new HashMap<>(environment);
		}

		@Override
		public NativeTools nativeTools() throws IOException
		{
			return new NativeTools(capture(List.of("xcode-select", "-p")).strip(),
				capture(List.of("xcrun", "--show-sdk-path")).strip(), capture(List.of("xcrun", "clang", "--version")));
		}

		@Override
		public long availableBytes(Path path) throws IOException
		{
			return Files.getFileStore(path).getUsableSpace();
		}

		@Override
		public void remove(Path application) throws IOException
		{
			List<String> command = List.of("sudo", "rm", "-rf", "--", application.toString());
			int status = SystemCommands.execute(command, workingDirectory, environment);
			if (status != 0)
				throw new IOException("sudo rm failed with status " + status + " for " + application);
		}

		@Override
		public String compileAndRunSmoke(Path scratch) throws IOException
		{
			Path source = scratch.resolve("smoke.c");
			Path executable = scratch.resolve("smoke");
			Files.writeString(source, SMOKE_SOURCE);
			capture(List.of("xcrun", "clang", "-x", "c", source.toString(), "-o", executable.toString()));
			return capture(List.of(executable.toString()));
		}

		/**
		 * Runs a command to completion and returns its standard output.
		 *
		 * @param command the executable followed by its arguments
		 * @return the standard output
		 * @throws IOException if the command cannot run or exits unsuccessfully
		 */
		private String capture(List<String> command) throws IOException
		{
			SystemCommands.Result result = SystemCommands.capture(command, workingDirectory, temporaryDirectory);
			if (result.status() != 0)
			{
				throw new IOException(command.getFirst() + " failed with status " + result.status() + ": " +
					result.stderr().strip());
			}
			return result.stdout();
		}
	}
}
