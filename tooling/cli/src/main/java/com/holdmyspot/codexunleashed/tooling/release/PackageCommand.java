package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * Builds canonical executable packages and their optional archive outputs.
 */
public final class PackageCommand
{
	/**
	 * Names the package assembly command.
	 */
	public static final String NAME = "build-codex-package";
	private static final URI V8_RELEASES = URI.create("https://github.com/openai/codex/releases/download/");

	/**
	 * Prevents construction.
	 */
	private PackageCommand()
	{
	}

	/**
	 * Builds packages using the process environment and the UTC archive clock.
	 *
	 * @param args the package options
	 * @param out the command output
	 * @param err the argument diagnostics
	 * @return zero on success or help, or two on invalid arguments
	 * @throws IOException if input resolution, building, copying, archiving, or temporary cleanup fails
	 * @throws NullPointerException if an argument is null
	 */
	public static int run(String[] args, PrintStream out, PrintStream err) throws IOException
	{
		return run(args, out, err, System.getenv(), Clock.systemUTC());
	}

	/**
	 * Composes source binaries, executable resources, metadata, legal materials, and repeated archive outputs.
	 *
	 * @param args the package options
	 * @param out the command output
	 * @param err the argument diagnostics
	 * @param environment the complete caller environment
	 * @param clock the archive clock
	 * @return zero on success or help, or two on invalid arguments
	 * @throws IOException if input resolution, building, copying, archiving, or temporary cleanup fails
	 * @throws NullPointerException if an argument or environment entry is null
	 */
	public static int run(String[] args, PrintStream out, PrintStream err, Map<String, String> environment, Clock clock)
		throws IOException
	{
		Objects.requireNonNull(args, "args");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		Objects.requireNonNull(clock, "clock");
		Map<String, String> variables = Map.copyOf(environment);
		CommandLine parser = parser();
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

		Path repository = result.<Path>matchedOptionValue("--repo", null).toRealPath();
		Path workspace = result.matchedOptionValue("--workspace", null);
		if (workspace == null)
			workspace = environmentPath(variables, "CODEX_PACKAGE_WORKSPACE_ROOT").orElse(repository);
		workspace = workspace.toRealPath();
		Path cache = result.matchedOptionValue("--cache-root", Path.of(System.getProperty("java.io.tmpdir"),
			"codex-package"));
		cache = cache.toAbsolutePath().normalize();
		PackageTarget target = result.matchedOptionValue("--target", null);
		if (target == null)
			target = PackageTarget.forHost(hostSystem(), System.getProperty("os.arch"));
		PackageVariant variant = result.matchedOptionValue("--variant", PackageVariant.CODEX);
		PackageSourceBuilds.Inputs prebuilt = new PackageSourceBuilds.Inputs(
			input(result, "--entrypoint-bin", "prebuilt entrypoint executable"),
			input(result, "--code-mode-host-bin", "prebuilt code-mode host executable"),
			input(result, "--bwrap-bin", "prebuilt Linux bwrap executable"),
			input(result, "--codex-command-runner-bin", "prebuilt Windows command-runner executable"),
			input(result, "--codex-windows-sandbox-setup-bin", "prebuilt Windows sandbox-setup executable"));
		PackageSourceBuilds.Options options = new PackageSourceBuilds.Options(result.matchedOptionValue("--cargo", "cargo"),
			result.matchedOptionValue("--cargo-profile", "dev-small"), cache, variables, V8_RELEASES);

		try (Destination destination = Destination.create(result.matchedOptionValue("--package-dir", null), cache))
		{
			PackageSourceBuilds.Outputs source = PackageSourceBuilds.build(new PackageSourceBuilds.Request(workspace, target,
				variant, prebuilt, options), out);
			String version = PackageVersions.resolve(workspace, variables);
			Path ripgrep;
			Path override = result.matchedOptionValue("--rg-bin", null);
			if (override != null)
				ripgrep = ExecutableInputs.resolve(override, "ripgrep executable");
			else
				ripgrep = DotSlashResources.fetch(new DotSlashResources.Request(target,
					result.matchedOptionValue("--rg-manifest", repository.resolve("scripts/codex_package/rg")), "ripgrep",
					target.triple() + "-rg", target.ripgrepName(), false, cache)).orElseThrow();
			Optional<Path> zsh = DotSlashResources.fetch(new DotSlashResources.Request(target,
				result.matchedOptionValue("--zsh-manifest", repository.resolve("scripts/codex_package/codex-zsh")), "zsh",
				target.triple() + "-zsh", "zsh", true, cache));
			PackageInputs inputs = new PackageInputs(source.entrypoint(), source.codeModeHost(), ripgrep, zsh, source.bwrap(),
				source.commandRunner(), source.sandboxSetup());
			boolean force = result.matchedOptionValue("--force", false);
			PackageLayout.prepare(destination.directory, force);
			Path metadataWorking = workspace;
			Path metadataTemporary = cache.resolve("process-temp");
			PackageLayout.build(new PackageLayout.Request(destination.directory, version, variant, target, inputs, repository,
				environmentPath(variables, "CODEX_PACKAGE_RUST_LICENSES_DIR"), Optional.of(workspace)), command ->
			{
				Files.createDirectories(metadataTemporary);
				SystemCommands.Result metadata = SystemCommands.capture(command, metadataWorking, metadataTemporary, variables);
				if (metadata.status() != 0)
				{
					err.print(metadata.stderr());
					throw new IOException("Cargo metadata failed with status " + metadata.status());
				}
				return metadata.stdout();
			});
			PackageLayout.validate(destination.directory, variant, target, zsh.isPresent());
			List<Path> archives = result.matchedOptionValue("--archive-output", List.of());
			for (Path archive : archives)
			{
				Path output = archive.toAbsolutePath().normalize();
				List<String> compressor = List.of();
				Path temporary = cache.resolve("archive-temp");
				if (output.getFileName().toString().endsWith(".tar.zst"))
				{
					compressor = ZstdCommands.resolve(repository.resolve(".github/workflows/zstd"),
						name -> findExecutable(name, variables));
					Files.createDirectories(temporary);
				}
				PackageArchives.write(new PackageArchives.Request(destination.directory, output, force, temporary,
					new ArchiveEnvironment(variables), compressor), clock, command ->
				{
					int status = SystemCommands.execute(command, metadataWorking, variables);
					if (status != 0)
						throw new IOException("Package compressor failed with status " + status);
					return "";
				});
				out.println("Built Codex package archive at " + output);
			}
			out.println("Built Codex package directory at " + destination.directory);
			destination.retain();
			return 0;
		}
	}

	/**
	 * Defines retained package options and explicit repository, workspace, and cache context.
	 *
	 * @return the configured argument parser
	 */
	private static CommandLine parser()
	{
		CommandSpec specification = CommandSpec.create().name(NAME);
		specification.usageMessage().description("Build a canonical Codex package directory and optional archives.");
		for (String option : new String[]{"--repo", "--workspace", "--cache-root", "--package-dir", "--entrypoint-bin",
			"--code-mode-host-bin", "--bwrap-bin", "--rg-bin", "--rg-manifest", "--zsh-manifest",
			"--codex-command-runner-bin", "--codex-windows-sandbox-setup-bin"})
			specification.addOption(OptionSpec.builder(option).type(Path.class).required(option.equals("--repo")).build());
		specification.addOption(OptionSpec.builder("--target").type(PackageTarget.class).
			converters(PackageTarget::fromTriple).build());
		specification.addOption(OptionSpec.builder("--variant").type(PackageVariant.class).
			converters(PackageVariant::fromName).defaultValue("codex").build());
		specification.addOption(OptionSpec.builder("--archive-output").type(List.class).auxiliaryTypes(Path.class).
			arity("1").build());
		specification.addOption(OptionSpec.builder("--cargo").type(String.class).defaultValue("cargo").build());
		specification.addOption(OptionSpec.builder("--cargo-profile").type(String.class).defaultValue("dev-small").build());
		specification.addOption(OptionSpec.builder("--force").type(boolean.class).arity("0").build());
		specification.addOption(OptionSpec.builder("-h", "--help").usageHelp(true).build());
		return new CommandLine(specification).setOverwrittenOptionsAllowed(true).setExpandAtFiles(false).
			setAbbreviatedOptionsAllowed(true);
	}

	/**
	 * Resolves a supplied prebuilt path while leaving absent source inputs available for Cargo.
	 *
	 * @param result the parsed arguments
	 * @param option the input option
	 * @param description the executable description
	 * @return the resolved override or absence
	 * @throws IOException if a supplied executable fails validation
	 */
	private static Optional<Path> input(CommandLine.ParseResult result, String option, String description)
		throws IOException
	{
		Path path = result.matchedOptionValue(option, null);
		if (path == null)
			return Optional.empty();
		return Optional.of(ExecutableInputs.resolve(path, description));
	}

	/**
	 * Retains truthy environment-path selection without treating an empty variable as the current directory.
	 *
	 * @param environment the caller environment
	 * @param variable the environment variable name
	 * @return the selected path or absence
	 */
	private static Optional<Path> environmentPath(Map<String, String> environment, String variable)
	{
		String value = environment.get(variable);
		if (value == null || value.isEmpty())
			return Optional.empty();
		return Optional.of(Path.of(value));
	}

	/**
	 * Maps Java host property names to the retained release system identities.
	 *
	 * @return the host release system name
	 */
	private static String hostSystem()
	{
		String system = System.getProperty("os.name");
		if (system.toLowerCase(Locale.ROOT).startsWith("windows"))
			return "Windows";
		return switch (system.toLowerCase(Locale.ROOT))
		{
			case "mac os x", "macos" -> "Darwin";
			default -> system;
		};
	}

	/**
	 * Locates a native compressor or DotSlash executable using the caller's host search path.
	 *
	 * @param name the executable name
	 * @param environment the caller environment
	 * @return the first executable candidate or absence
	 */
	private static Optional<Path> findExecutable(String name, Map<String, String> environment)
	{
		List<String> names = new ArrayList<>(List.of(name));
		if (File.separatorChar == '\\')
		{
			for (String suffix : environment.getOrDefault("PATHEXT", ".COM;.EXE;.BAT;.CMD").split(";"))
				names.add(name + suffix);
		}
		String search = environment.getOrDefault("PATH", "");
		for (String directory : search.split(Pattern.quote(File.pathSeparator), -1))
		{
			for (String candidate : names)
			{
				Path path = Path.of(directory).resolve(candidate);
				if (Files.isRegularFile(path) && Files.isExecutable(path))
					return Optional.of(path.toAbsolutePath().normalize());
			}
		}
		return Optional.empty();
	}

	/**
	 * Owns implicit output trees until a complete package and its requested archives are handed off.
	 */
	private static final class Destination implements AutoCloseable
	{
		private final Path directory;
		private boolean deleteOnClose;

		/**
		 * Records output ownership.
		 *
		 * @param directory the output path
		 * @param deleteOnClose whether the path is newly allocated temporary output
		 */
		private Destination(Path directory, boolean deleteOnClose)
		{
			this.directory = directory;
			this.deleteOnClose = deleteOnClose;
		}

		/**
		 * Preserves explicit caller destinations or allocates a uniquely owned implicit directory.
		 *
		 * @param explicit the optional explicit output path
		 * @param cache the managed temporary parent
		 * @return the output owner
		 * @throws IOException if temporary output allocation fails
		 */
		private static Destination create(Path explicit, Path cache) throws IOException
		{
			if (explicit != null)
				return new Destination(explicit.toAbsolutePath().normalize(), false);
			Files.createDirectories(cache);
			return new Destination(Files.createTempDirectory(cache, "codex-package-"), true);
		}

		/**
		 * Hands successful output ownership to the caller.
		 */
		private void retain()
		{
			deleteOnClose = false;
		}

		/**
		 * Removes partial implicit output without following directory symlinks or deleting explicit destinations.
		 *
		 * @throws IOException if traversal or deletion fails
		 */
		@Override
		public void close() throws IOException
		{
			if (!deleteOnClose)
				return;
			try (Stream<Path> paths = Files.walk(directory))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
			catch (UncheckedIOException failure)
			{
				throw failure.getCause();
			}
		}
	}
}
