package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import com.holdmyspot.codexunleashed.tooling.github.GitHubHttpClient;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * Builds npm release families from complete package archives and optionally publishes them to the selected registry.
 */
public final class NpmPublishCommand
{
	/** Names the npm publication command. */
	public static final String NAME = "publish-npm-from-release";

	/** Prevents construction. */
	private NpmPublishCommand()
	{
	}

	/**
	 * Packs all native packages and selectors, then optionally publishes them in platform-first family order.
	 *
	 * @param args the publication options
	 * @param out the progress and completion stream
	 * @param err the argument diagnostic stream
	 * @return zero on success or help, or two on parser failure
	 * @throws NullPointerException if an argument is null
	 * @throws IllegalArgumentException if required release identity or scope is invalid
	 * @throws IOException if lookup, extraction, assembly, npm execution, or temporary cleanup fails
	 */
	public static int run(String[] args, PrintStream out, PrintStream err) throws IOException
	{
		Objects.requireNonNull(args, "args");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		CommandLine parser = parser();
		CommandLine.ParseResult options;
		try
		{
			options = parser.parseArgs(args);
		}
		catch (CommandLine.ParameterException failure)
		{
			err.println("ERROR: " + failure.getMessage());
			return 2;
		}
		if (options.isUsageHelpRequested())
		{
			parser.usage(out);
			return 0;
		}

		String tag = options.matchedOptionValue("--tag", "");
		String supplied = options.matchedOptionValue("--version", "");
		if (tag.isEmpty() && supplied.isEmpty())
			throw new IllegalArgumentException("provide --tag or --version");
		if (supplied.isEmpty())
			supplied = NpmVersions.removeTagPrefix(tag);
		String version = NpmVersions.toNpmVersion(supplied);
		String scope = options.matchedOptionValue("--scope", "@holdmyspot");
		if (!scope.startsWith("@"))
			throw new IllegalArgumentException("--scope must include the @ prefix");
		Path explicitArchives = options.matchedOptionValue("--archive-dir", null);
		if (explicitArchives == null && tag.isEmpty())
			throw new IllegalArgumentException("--tag is required when --archive-dir is not used");
		String registry = options.matchedOptionValue("--registry", "http://127.0.0.1:4873");
		Path temporary = Path.of(System.getProperty("java.io.tmpdir"));
		try (Directory output = Directory.output(options.matchedOptionValue("--output-dir", null), temporary))
		{
			Path archives = explicitArchives;
			if (archives == null)
				archives = output.path.resolve("archives");
			Files.createDirectories(archives);
			if (explicitArchives == null)
			{
				URI endpoint = URI.create(options.matchedOptionValue("--api-base", "https://api.github.com/"));
				try (GitHubHttpClient api = GitHubHttpClient.anonymous(endpoint))
				{
					NpmReleaseDownloads.download(options.matchedOptionValue("--repository",
						"holdmyspot-com/codex-unleashed"), tag, archives, api, out);
				}
			}
			List<NpmPackages.Directory> packages = assemble(archives, output.path.resolve("packages"), temporary,
				scope, version, registry);
			try (Directory cache = Directory.temporary(temporary, "codex-npm-cache-"))
			{
				Map<String, String> environment = new HashMap<>(System.getenv());
				environment.put("NPM_CONFIG_CACHE", cache.path.toString());
				environment.put("XDG_CACHE_HOME", cache.path.resolve("xdg").toString());
				Path npmrc = options.matchedOptionValue("--npmrc", null);
				for (NpmPackages.Directory directory : packages)
					executeNpm(directory.path(), List.of("pack", "--pack-destination", output.path.toString()),
						npmrc, registry, environment);
				if (options.matchedOptionValue("--publish", false))
					for (NpmPackages.Directory directory : packages)
					{
						String publicationTag = publicationTag(directory.path(), version, npmrc, registry, environment);
						executeNpm(directory.path(), List.of("publish", "--access", directory.access(), "--tag", publicationTag),
							npmrc, registry, environment);
					}
			}
			out.println("Created " + packages.size() + " npm packages in " + output.path);
			if (!options.matchedOptionValue("--publish", false))
				out.println("Packages were not published; pass --publish to publish to the configured registry.");
			output.retain();
			return 0;
		}
	}

	/**
	 * Chooses latest only for a strictly newer version, using a version-specific tag for older or equal builds.
	 *
	 * @param directory the assembled package whose own name identifies the registry lookup
	 * @param version the candidate npm vendor version
	 * @param npmrc the optional authentication configuration
	 * @param registry the selected registry
	 * @param environment the child environment with managed cache storage
	 * @return latest or the isolated release tag
	 * @throws IOException if registry lookup or metadata validation fails
	 */
	private static String publicationTag(Path directory, String version, Path npmrc, String registry,
		Map<String, String> environment) throws IOException
	{
		JsonMapper mapper = JsonMapper.builder().build();
		String name = mapper.readTree(Files.readString(directory.resolve("package.json"))).path("name").stringValue();
		List<String> command = new ArrayList<>(NpmCommands.command(directory,
			Path.of(environment.get("NPM_CONFIG_CACHE")), environment));
		command.addAll(List.of("view", name, "dist-tags", "--json", "--prefer-online", "--registry", registry));
		if (npmrc != null)
			command.addAll(List.of("--userconfig", npmrc.toString()));
		SystemCommands.Result result = SystemCommands.capture(command, directory,
			Path.of(environment.get("NPM_CONFIG_CACHE")), environment);
		JsonNode tags = mapper.readTree(result.stdout());
		if (result.status() != 0)
		{
			String summary = "";
			if (tags != null)
				summary = tags.path("error").path("summary").asString("");
			if (tags != null && "E404".equals(tags.path("error").path("code").asString("")) &&
				(summary.startsWith("Not Found - GET ") || summary.startsWith("404 Not Found - GET ")))
				return "latest";
			throw new IOException("Cannot read npm dist-tags for " + name + ": status " + result.status() +
				"; " + result.stderr());
		}
		if (tags != null && tags.isArray() && tags.size() == 1)
			tags = tags.get(0);
		if (tags == null || !tags.isObject())
			throw new IOException("npm dist-tags for " + name + " must be a JSON object");
		JsonNode latest = tags.get("latest");
		if (latest == null)
			return "latest";
		if (!latest.isString())
			throw new IOException("npm latest tag for " + name + " must be a stable vendor version string");
		try
		{
			if (ReleaseOrder.compareVersions(version.replace('-', '+'), latest.stringValue().replace('-', '+')) > 0)
				return "latest";
			return "release-" + version;
		}
		catch (IllegalArgumentException failure)
		{
			throw new IOException("Cannot compare npm latest tag for " + name + ": " + latest.stringValue(), failure);
		}
	}

	/**
	 * Extracts the six source archives and closes source staging immediately after package assembly.
	 *
	 * @param archives the downloaded archive directory
	 * @param packages the output package directory
	 * @param temporary the owned temporary parent
	 * @param scope the npm scope
	 * @param version the npm version
	 * @param registry the publication registry
	 * @return the complete ordered package directories
	 * @throws IOException if extraction, assembly, output replacement, or cleanup fails
	 */
	private static List<NpmPackages.Directory> assemble(Path archives, Path packages, Path temporary,
		String scope, String version, String registry) throws IOException
	{
		if (Files.exists(packages, LinkOption.NOFOLLOW_LINKS))
		{
			if (!Files.isDirectory(packages, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("npm packages output is not a directory: " + packages);
			delete(packages);
		}
		try (Directory extracted = Directory.temporary(temporary, "codex-npm-extract-"))
		{
			Map<String, Path> sources = new LinkedHashMap<>();
			for (NpmPlatform platform : NpmPlatform.values())
			{
				Path archive = archives.resolve("codex-package-" + platform.target() + ".tar.gz");
				if (!Files.isRegularFile(archive))
					throw new IOException("missing archive: " + archive);
				Path source = Files.createDirectory(extracted.path.resolve(platform.target()));
				NpmArchiveExtractor.extract(archive, source, extracted.path);
				sources.put(platform.target(), source);
			}
			return NpmPackages.assemble(new NpmPackages.Request(sources, packages, scope, version, registry));
		}
	}

	/**
	 * Runs npm with direct arguments, the individual package cwd, and managed ephemeral cache storage.
	 *
	 * @param directory the package cwd
	 * @param arguments the npm operation arguments
	 * @param npmrc the optional caller configuration path
	 * @param registry the explicit registry
	 * @param environment the complete child environment
	 * @throws IOException if npm startup, waiting, cleanup, or exit status fails
	 */
	private static void executeNpm(Path directory, List<String> arguments, Path npmrc, String registry,
		Map<String, String> environment) throws IOException
	{
		List<String> command = new ArrayList<>(NpmCommands.command(directory,
			Path.of(environment.get("NPM_CONFIG_CACHE")), environment));
		command.addAll(arguments);
		if (npmrc != null)
			command.addAll(List.of("--userconfig", npmrc.toString()));
		command.addAll(List.of("--registry", registry));
		int status = SystemCommands.execute(command, directory, environment);
		if (status != 0)
			throw new IOException("npm " + arguments.getFirst() + " failed with status " + status);
	}

	/**
	 * Defines the retained publisher options and the explicit local API fixture endpoint.
	 *
	 * @return the configured argument parser
	 */
	private static CommandLine parser()
	{
		CommandSpec specification = CommandSpec.create().name(NAME);
		specification.usageMessage().description("Build and optionally publish npm packages from a GitHub Codex release.");
		for (String option : List.of("--repository", "--tag", "--version", "--registry", "--scope", "--api-base"))
			specification.addOption(OptionSpec.builder(option).type(String.class).build());
		for (String option : List.of("--output-dir", "--archive-dir", "--npmrc"))
			specification.addOption(OptionSpec.builder(option).type(Path.class).build());
		specification.addOption(OptionSpec.builder("--publish").type(boolean.class).arity("0").build());
		specification.addOption(OptionSpec.builder("-h", "--help").usageHelp(true).build());
		return new CommandLine(specification).setOverwrittenOptionsAllowed(true).setExpandAtFiles(false).
			setAbbreviatedOptionsAllowed(true);
	}

	/**
	 * Deletes a caller-approved replacement tree or a newly owned temporary tree without following links.
	 *
	 * @param root the output tree
	 * @throws IOException if traversal or deletion fails
	 */
	private static void delete(Path root) throws IOException
	{
		try (Stream<Path> files = Files.walk(root))
		{
			for (Path file : files.sorted(Comparator.reverseOrder()).toList())
				Files.delete(file);
		}
		catch (UncheckedIOException failure)
		{
			throw failure.getCause();
		}
	}

	/** Owns temporary trees and partial implicit output until successful handoff. */
	private static final class Directory implements AutoCloseable
	{
		private final Path path;
		private boolean deleteOnClose;

		/**
		 * Records an allocated tree's ownership.
		 *
		 * @param path the absolute tree path
		 * @param deleteOnClose whether cleanup belongs to this owner
		 */
		private Directory(Path path, boolean deleteOnClose)
		{
			this.path = path;
			this.deleteOnClose = deleteOnClose;
		}

		/**
		 * Retains explicit output or allocates implicit output with failure cleanup.
		 *
		 * @param explicit the caller output or absence
		 * @param temporary the temporary parent
		 * @return the output owner
		 * @throws IOException if directory creation fails
		 */
		private static Directory output(Path explicit, Path temporary) throws IOException
		{
			if (explicit == null)
				return temporary(temporary, "codex-npm-");
			Path output = explicit.toAbsolutePath().normalize();
			Files.createDirectories(output);
			return new Directory(output, false);
		}

		/**
		 * Allocates a uniquely owned directory beneath explicit temporary storage.
		 *
		 * @param parent the temporary parent
		 * @param prefix the purpose-specific directory prefix
		 * @return the temporary owner
		 * @throws IOException if directory creation fails
		 */
		private static Directory temporary(Path parent, String prefix) throws IOException
		{
			Files.createDirectories(parent);
			return new Directory(Files.createTempDirectory(parent, prefix), true);
		}

		/** Hands successful output ownership to the caller. */
		private void retain()
		{
			deleteOnClose = false;
		}

		/**
		 * Removes owned resources on success and failure, preserving explicit caller outputs.
		 *
		 * @throws IOException if cleanup fails
		 */
		@Override
		public void close() throws IOException
		{
			if (deleteOnClose)
				delete(path);
		}
	}
}
