package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Materializes Bazel V8 outputs using the shared remote execution policy before staging release artifacts. */
public final class V8Bazel
{
	/** The supported Bazel compilation modes. */
	public static final Set<String> COMPILATION_MODES = Set.of("fastbuild", "opt", "dbg");
	private static final String ARTIFACT_CONFIGURATION = "rusty-v8-upstream-libcxx";
	private static final Pattern EDGES = Pattern.compile("^[\\s\\u001c-\\u001f]+|[\\s\\u001c-\\u001f]+$",
		Pattern.UNICODE_CHARACTER_CLASS);

	/** Prevents construction. */
	private V8Bazel()
	{
	}

	/**
	 * Describes one artifact pair and its retained Bazel options.
	 *
	 * @param platform LLVM platform name
	 * @param target Cargo target
	 * @param outputDirectory staged artifact directory
	 * @param sandbox selects sandbox artifact names and label
	 * @param skipBuild retained option; requested outputs are still materialized before staging
	 * @param bazelConfigurations additional configurations, in caller order
	 * @param compilationMode fastbuild, opt or dbg
	 */
	public record Request(String platform, String target, Path outputDirectory, boolean sandbox, boolean skipBuild,
		List<String> bazelConfigurations, String compilationMode)
	{
		/**
		 * Retains explicit options and snapshots caller-owned configuration values.
		 *
		 * @param platform LLVM platform name
		 * @param target Cargo target
		 * @param outputDirectory staged artifact directory
		 * @param sandbox selects sandbox artifact names and label
		 * @param skipBuild retained materialization option
		 * @param bazelConfigurations additional configurations
		 * @param compilationMode fastbuild, opt or dbg
		 * @throws NullPointerException if a reference argument or configuration element is null
		 * @throws IllegalArgumentException if the compilation mode is unsupported
		 */
		public Request
		{
			Objects.requireNonNull(platform, "platform");
			Objects.requireNonNull(target, "target");
			Objects.requireNonNull(outputDirectory, "outputDirectory");
			bazelConfigurations = List.copyOf(bazelConfigurations);
			Objects.requireNonNull(compilationMode, "compilationMode");
			if (!COMPILATION_MODES.contains(compilationMode))
				throw new IllegalArgumentException("unsupported compilation mode: " + compilationMode);
		}
	}

	/**
	 * Builds the selected label, resolves query paths, and stages the first library and binding outputs.
	 *
	 * @param request artifact request
	 * @param root repository working directory used by the supplied runner
	 * @param environment Bazel planning environment
	 * @param runner external command boundary, executing in root and refusing nonzero status
	 * @return staged library, binding and checksum paths
	 * @throws NullPointerException if an argument is null
	 * @throws IOException if command execution, output resolution or artifact staging fails
	 */
	public static List<Path> stage(Request request, Path root, Map<String, String> environment, CommandRunner runner)
		throws IOException
	{
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(root, "root");
		Objects.requireNonNull(environment, "environment");
		Objects.requireNonNull(runner, "runner");
		String kind = "release_pair";
		if (request.sandbox())
			kind = "sandbox_release_pair";
		String label = "//third_party/v8:rusty_v8_" + kind + "_" + request.target().replace('-', '_');
		List<String> build = arguments("build", request);
		build.add("--remote_download_toplevel");
		build.add(label);
		run(build, root, environment, runner);

		List<String> query = arguments("cquery", request);
		query.add("--output=files");
		query.add("set(" + label + ")");
		String output = run(query, root, environment, runner);
		List<Path> paths = new ArrayList<>();
		for (String line : TextLines.split(output))
		{
			String path = EDGES.matcher(line).replaceAll("");
			if (path.isEmpty())
				continue;
			String info = "execution_root";
			if (path.startsWith("external/"))
				info = "output_base";
			String base = EDGES.matcher(run(List.of("info", info), root, environment, runner)).replaceAll("");
			paths.add(Path.of(base).resolve(path));
		}
		List<Path> missing = paths.stream().filter(path -> !Files.exists(path)).toList();
		if (!missing.isEmpty())
			throw new IOException("missing built outputs for " + request.target() + ": " + missing);

		Path library = paths.stream().filter(path -> suffix(path, ".a") || suffix(path, ".lib")).findFirst().
			orElseThrow(() -> new IOException("missing static library output for " + request.target()));
		Path binding = paths.stream().filter(path -> suffix(path, ".rs")).findFirst().
			orElseThrow(() -> new IOException("missing Rust binding output for " + request.target()));
		return V8Artifacts.stage(request.target(), library, binding, request.outputDirectory(), request.sandbox());
	}

	/**
	 * Supplies ordered, deduplicated configuration arguments for build and query.
	 *
	 * @param operation Bazel command
	 * @param request selected artifact options
	 * @return mutable command arguments without the executable
	 */
	private static List<String> arguments(String operation, Request request)
	{
		List<String> arguments = new ArrayList<>(List.of(operation, "-c", request.compilationMode(),
			"--platforms=@llvm//platforms:" + request.platform()));
		var configurations = new LinkedHashSet<String>();
		configurations.add(ARTIFACT_CONFIGURATION);
		configurations.addAll(request.bazelConfigurations());
		for (String configuration : configurations)
			arguments.add("--config=" + configuration);
		return arguments;
	}

	/**
	 * Executes arguments through the shared BuildBuddy command planner.
	 *
	 * @param arguments Bazel arguments without the executable
	 * @param root repository root
	 * @param environment planning environment
	 * @param runner explicit external boundary
	 * @return captured standard output
	 * @throws IOException if planning or execution fails
	 */
	private static String run(List<String> arguments, Path root, Map<String, String> environment, CommandRunner runner)
		throws IOException
	{
		return runner.run(BazelCommands.plan(arguments, environment, root).command());
	}

	/**
	 * Compares the final filename extension without folding case.
	 *
	 * @param path output path
	 * @param extension required suffix
	 * @return whether the final component has that suffix
	 */
	private static boolean suffix(Path path, String extension)
	{
		Path filename = path.getFileName();
		if (filename == null)
			return false;
		String name = filename.toString();
		return name.length() > extension.length() && name.endsWith(extension);
	}
}
