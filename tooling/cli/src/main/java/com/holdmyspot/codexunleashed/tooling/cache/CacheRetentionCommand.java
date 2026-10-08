package com.holdmyspot.codexunleashed.tooling.cache;

import com.holdmyspot.codexunleashed.tooling.CommandRunner;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exposes release retention and cache pruning with explicit active upstream references.
 */
public final class CacheRetentionCommand
{
	/**
	 * Prevents construction.
	 */
	private CacheRetentionCommand()
	{
	}

	/**
	 * Prints retained releases or prunes obsolete Actions caches.
	 *
	 * @param args the command name and options
	 * @param out the result destination
	 * @param err the argument diagnostic destination
	 * @param runner the external-command boundary
	 * @return zero on success or two on malformed arguments
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if a repository or retained reference is invalid
	 * @throws IOException if discovery or pruning fails
	 */
	public static int run(String[] args, PrintStream out, PrintStream err, CommandRunner runner) throws IOException
	{
		Objects.requireNonNull(args, "args");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		Objects.requireNonNull(runner, "runner");
		if (args.length == 0 || (!args[0].equals("stable-releases") && !args[0].equals("prune-actions-caches")))
			throw new IllegalArgumentException("Expected stable-releases or prune-actions-caches");

		boolean prune = args[0].equals("prune-actions-caches");
		CommandSpec spec = CommandSpec.create().name(args[0]);
		spec.addOption(OptionSpec.builder("--retain-upstream-ref").type(String[].class).arity("1").build());
		if (prune)
		{
			spec.addOption(OptionSpec.builder("--repository").type(String.class).required(true).build());
			spec.addOption(OptionSpec.builder("--dry-run").type(boolean.class).build());
		}
		CommandLine parser = new CommandLine(spec).setExpandAtFiles(false);
		CommandLine.ParseResult options;
		try
		{
			options = parser.parseArgs(Arrays.copyOfRange(args, 1, args.length));
		}
		catch (CommandLine.ParameterException failure)
		{
			err.println("ERROR: " + failure.getMessage());
			return 2;
		}

		List<String> retained = Arrays.asList(options.matchedOptionValue("--retain-upstream-ref", new String[0]));
		if (prune)
		{
			CachePruner.Result result = CachePruner.prune(options.matchedOptionValue("--repository", ""),
				options.matchedOptionValue("--dry-run", false), retained, runner);
			out.println(JsonMapper.builder().build().writeValueAsString(Map.of(
				"retained_releases", result.retainedReleases(), "obsolete_cache_ids", result.obsoleteCacheIds(),
				"dry_run", result.dryRun())));
		}
		else
			for (String tag : CachePruner.discoverRetainedReleases(retained, runner))
				out.println(tag);
		return 0;
	}
}
