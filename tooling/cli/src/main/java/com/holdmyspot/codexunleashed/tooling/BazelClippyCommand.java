package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

/** Exposes lint alignment with an explicit repository and retained file override options. */
public final class BazelClippyCommand
{
	/** The public command name. */
	public static final String NAME = "verify-bazel-clippy-lints";

	/** Prevents construction. */
	private BazelClippyCommand()
	{
	}

	/**
	 * Resolves default files from the explicit repository and reports alignment or argument errors.
	 *
	 * @param args repository and file options
	 * @param out success and help output
	 * @param err mismatch and argument diagnostics
	 * @return zero for matching levels or help, one for mismatches, two for invalid arguments
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if input processing fails
	 */
	public static int run(String[] args, PrintStream out, PrintStream err) throws IOException
	{
		Objects.requireNonNull(args, "args");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		CommandSpec specification = CommandSpec.create().name(NAME);
		specification.addPositional(PositionalParamSpec.builder().index("0").type(Path.class).required(true).build());
		for (String option : new String[]{"--cargo-toml", "--bazelrc"})
			specification.addOption(OptionSpec.builder(option).type(Path.class).build());
		specification.addOption(OptionSpec.builder("-h", "--help").usageHelp(true).build());
		CommandLine parser = new CommandLine(specification).setOverwrittenOptionsAllowed(true).
			setExpandAtFiles(false).setAbbreviatedOptionsAllowed(true);
		CommandLine.ParseResult result;
		try
		{
			result = parser.parseArgs(args);
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
		Path repository = result.matchedPositionalValue(0, Path.of("."));
		return BazelClippyPolicy.check(repository,
			result.matchedOptionValue("--cargo-toml", repository.resolve("codex-rs/Cargo.toml")),
			result.matchedOptionValue("--bazelrc", repository.resolve(".bazelrc")), out, err);
	}
}
