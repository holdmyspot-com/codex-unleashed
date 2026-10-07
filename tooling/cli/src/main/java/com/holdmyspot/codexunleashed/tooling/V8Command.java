package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/** Runs V8 artifact staging and module checksum operations with an explicit checkout. */
public final class V8Command
{
	/** The maintained V8 operations command. */
	public static final String NAME = "rusty-v8-bazel";

	/** Prevents construction. */
	private V8Command()
	{
	}

	/**
	 * Parses operation-specific options and reports the operation's results.
	 *
	 * @param arguments checkout, operation and options
	 * @param out result output
	 * @param err diagnostics
	 * @return zero on success or help, or two for invalid arguments
	 * @throws NullPointerException if an argument is null
	 * @throws IOException if artifact or module processing fails
	 */
	public static int run(String[] arguments, PrintStream out, PrintStream err) throws IOException
	{
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		if (arguments.length < 2)
		{
			err.println("Usage: " + NAME + " <checkout> <operation> [options]");
			return 2;
		}
		Path root = Path.of(arguments[0]);
		String operation = arguments[1];
		CommandSpec specification = CommandSpec.create().name(NAME + " " + operation);
		switch (operation)
		{
			case "check-module-bazel", "update-module-bazel" ->
			{
				specification.addOption(OptionSpec.builder("--version").type(String.class).build());
				specification.addOption(OptionSpec.builder("--manifest").type(Path.class).build());
				specification.addOption(OptionSpec.builder("--module-bazel").type(Path.class).build());
			}
			case "stage-upstream-release-pair" ->
			{
				for (String option : new String[]{"--target-dir", "--output-dir"})
					specification.addOption(OptionSpec.builder(option).type(Path.class).required(true).build());
				specification.addOption(OptionSpec.builder("--target").type(String.class).required(true).build());
				specification.addOption(OptionSpec.builder("--sandbox").type(boolean.class).arity("0").build());
			}
			case "stage-release-pair" ->
			{
				for (String option : new String[]{"--platform", "--target"})
					specification.addOption(OptionSpec.builder(option).type(String.class).required(true).build());
				specification.addOption(OptionSpec.builder("--output-dir").type(Path.class).required(true).build());
				for (String option : new String[]{"--sandbox", "--skip-build"})
					specification.addOption(OptionSpec.builder(option).type(boolean.class).arity("0").build());
				specification.addOption(OptionSpec.builder("--bazel-config").type(String[].class).arity("1").build());
				specification.addOption(OptionSpec.builder("--compilation-mode").type(String.class).
					defaultValue("fastbuild").build());
			}
			case "resolved-v8-crate-version", "check-consumer-selectors" ->
			{
				// These checkout operations have no options beyond help.
			}
			default ->
			{
				err.println("ERROR: unknown V8 operation: " + operation);
				return 2;
			}
		}
		specification.addOption(OptionSpec.builder("-h", "--help").usageHelp(true).build());
		CommandLine parser = new CommandLine(specification).setExpandAtFiles(false).
			setOverwrittenOptionsAllowed(true).setAbbreviatedOptionsAllowed(true);
		CommandLine.ParseResult result;
		try
		{
			result = parser.parseArgs(Arrays.copyOfRange(arguments, 2, arguments.length));
		}
		catch (CommandLine.ParameterException failure)
		{
			err.println("ERROR: " + failure.getMessage());
			return 2;
		}
		if (result.isUsageHelpRequested())
		{
			parser.usage(out);
			return 0;
		}
		if (operation.equals("stage-release-pair") &&
			!V8Bazel.COMPILATION_MODES.contains(result.matchedOptionValue("--compilation-mode", "fastbuild")))
		{
			err.println("ERROR: --compilation-mode must be fastbuild, opt or dbg");
			return 2;
		}

		switch (operation)
		{
			case "check-module-bazel", "update-module-bazel" -> reconcile(root, operation, result, out);
			case "stage-upstream-release-pair" ->
			{
				String target = result.matchedOptionValue("--target", "");
				V8Artifacts.Pair pair = V8Artifacts.upstreamPaths(target, result.matchedOptionValue("--target-dir", null));
				for (Path path : V8Artifacts.stage(target, pair.library(), pair.binding(),
					result.matchedOptionValue("--output-dir", null), result.matchedOptionValue("--sandbox", false)))
					out.println(path);
			}
			case "resolved-v8-crate-version" -> out.println(V8Versions.resolve(root));
			case "check-consumer-selectors" -> out.println(root.resolve("third_party/v8/BUILD.bazel") +
				" V8 consumer selectors match " + V8Consumers.checkCheckout(root));
			case "stage-release-pair" -> stageBazel(root, result, out, err);
			default -> throw new IllegalStateException("Unrecognized parsed V8 operation: " + operation);
		}
		return 0;
	}

	/**
	 * Captures Bazel in the selected checkout and releases each owned capture directory before staging.
	 *
	 * @param root repository working directory
	 * @param options parsed artifact options
	 * @param out staging results and build output
	 * @param err child diagnostics
	 * @throws IOException if Bazel execution or staging fails
	 */
	private static void stageBazel(Path root, CommandLine.ParseResult options, PrintStream out, PrintStream err)
		throws IOException
	{
		var request = new V8Bazel.Request(options.matchedOptionValue("--platform", ""),
			options.matchedOptionValue("--target", ""), options.matchedOptionValue("--output-dir", null),
			options.matchedOptionValue("--sandbox", false), options.matchedOptionValue("--skip-build", false),
			List.of(options.matchedOptionValue("--bazel-config", new String[0])),
			options.matchedOptionValue("--compilation-mode", "fastbuild"));
		Map<String, String> environment = System.getenv();
		Path temporary = Path.of(System.getProperty("java.io.tmpdir"));
		CommandRunner runner = command ->
		{
			SystemCommands.Result result = SystemCommands.capture(command, root, temporary, environment);
			err.print(result.stderr());
			if (command.contains("build"))
				out.print(result.stdout());
			if (result.status() != 0)
				throw new IOException("Bazel command failed with status " + result.status());
			return result.stdout();
		};
		for (Path path : V8Bazel.stage(request, root, environment, runner))
			out.println(path);
	}

	/**
	 * Checks or edits selected checksums using universal input and native output newline semantics.
	 *
	 * @param root explicit repository root
	 * @param operation check or update operation
	 * @param options parsed module options
	 * @param out result output
	 * @throws IOException if module selection, manifest validation or writing fails
	 */
	private static void reconcile(Path root, String operation, CommandLine.ParseResult options, PrintStream out)
		throws IOException
	{
		Path module = root.resolve(options.matchedOptionValue("--module-bazel", Path.of("MODULE.bazel")));
		String text = Files.readString(module).replace("\r\n", "\n").replace('\r', '\n');
		String version = options.matchedOptionValue("--version", null);
		if (version == null)
		{
			String versionSource = Files.readString(root.resolve("MODULE.bazel")).replace("\r\n", "\n").
				replace('\r', '\n');
			List<String> versions = V8ModuleChecksums.versions(versionSource);
			if (versions.size() > 1)
				throw new IOException("expected at most one rusty_v8 http_file version in MODULE.bazel, found: " +
					versions + "; pass --version explicitly");
			if (versions.isEmpty())
				version = V8Versions.resolve(root);
			else
				version = versions.getFirst();
		}
		Path manifest = root.resolve(options.matchedOptionValue("--manifest",
			Path.of("third_party/v8/rusty_v8_" + version.replace('.', '_') + ".sha256")));
		Map<String, String> checksums = V8ModuleChecksums.readManifest(manifest);
		if (operation.equals("check-module-bazel"))
		{
			V8ModuleChecksums.check(text, checksums, version);
			out.println(module + " rusty_v8 " + version + " checksums match " + manifest);
			return;
		}

		String updated = V8ModuleChecksums.update(text, checksums, version);
		if (updated.equals(text))
			out.println(module + " rusty_v8 " + version + " checksums are already current");
		else
		{
			Files.writeString(module, updated.replace("\n", System.lineSeparator()));
			out.println("updated " + module + " rusty_v8 " + version + " checksums");
		}
	}
}
