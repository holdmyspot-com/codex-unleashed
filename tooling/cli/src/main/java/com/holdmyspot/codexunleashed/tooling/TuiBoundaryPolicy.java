package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** Checks direct TUI core dependency keys and retained textual source imports. */
public final class TuiBoundaryPolicy
{
	private static final String FORBIDDEN_PACKAGE = "codex-core";
	private static final String WORD = "[\\p{L}\\p{N}_]";
	private static final String WHITESPACE = "[\\p{javaWhitespace}\\p{javaSpaceChar}\\x{85}]";
	private static final List<Pattern> IMPORTS = List.of(Pattern.compile("(?<!" + WORD + ")codex_core::"),
		Pattern.compile("(?<!" + WORD + ")use" + WHITESPACE + "+codex_core(?!" + WORD + ")"),
		Pattern.compile("(?<!" + WORD + ")extern" + WHITESPACE + "+crate" + WHITESPACE + "+codex_core(?!" + WORD + ")"));

	/** Prevents construction. */
	private TuiBoundaryPolicy()
	{
	}

	/**
	 * Checks the TUI manifest and all Rust sources without changing them or compiling Rust.
	 *
	 * @param repository the repository containing codex-rs/tui
	 * @param out the report destination
	 * @return zero when accepted or one when the boundary policy fails
	 * @throws IOException if a required input cannot be read or parsed
	 * @throws NullPointerException if an argument is null
	 */
	public static int check(Path repository, PrintStream out) throws IOException
	{
		Objects.requireNonNull(repository, "repository");
		Objects.requireNonNull(out, "out");
		Path root = repository.toRealPath();
		Path tui = root.resolve("codex-rs/tui");
		List<String> failures = manifestFailures(root, tui.resolve("Cargo.toml"));
		failures.addAll(sourceFailures(root, tui));
		if (failures.isEmpty())
			return 0;
		out.println("codex-tui must not depend on or import codex-core directly.");
		out.println("Use the app-server protocol/client boundary instead; temporary embedded startup gaps belong behind " +
			"codex_app_server_client::legacy_core.");
		out.println();
		for (String failure : failures)
			out.println("- " + failure);
		return 1;
	}

	/**
	 * Reports forbidden dependency keys in root and target tables in their retained order.
	 *
	 * @param root the canonical repository
	 * @param path the TUI manifest
	 * @return the mutable ordered failures
	 * @throws IOException if reading or TOML parsing fails
	 */
	private static List<String> manifestFailures(Path root, Path path) throws IOException
	{
		JsonNode manifest;
		try
		{
			manifest = TomlDocuments.parse(Files.readString(path));
		}
		catch (JacksonException failure)
		{
			throw new IOException("Cannot parse TUI manifest " + path, failure);
		}
		List<String> failures = new ArrayList<>();
		for (Map.Entry<String, JsonNode> section : dependencySections(manifest).entrySet())
			if (section.getValue().has(FORBIDDEN_PACKAGE))
				failures.add(root.relativize(path) + " declares `" + FORBIDDEN_PACKAGE + "` in `[" + section.getKey() + "]`");
		return failures;
	}

	/**
	 * Collects crate dependency tables while rejecting an invalid scalar target table.
	 *
	 * @param manifest the parsed manifest
	 * @return the ordered root and target tables
	 * @throws IOException if the target value is not a table
	 */
	private static Map<String, JsonNode> dependencySections(JsonNode manifest) throws IOException
	{
		Map<String, JsonNode> sections = new LinkedHashMap<>();
		for (String name : CargoDependencySections.NAMES)
			if (manifest.path(name).isObject())
				sections.put(name, manifest.path(name));
		JsonNode targets = manifest.get("target");
		if (targets == null)
			return sections;
		if (!targets.isObject())
			throw new IOException("TUI manifest target must be a table");
		for (Map.Entry<String, JsonNode> target : targets.properties())
			for (String name : CargoDependencySections.NAMES)
				if (target.getValue().path(name).isObject())
					sections.put("target." + target.getKey() + "." + name, target.getValue().path(name));
		return sections;
	}

	/**
	 * Reports matching source lines in retained filesystem and line order, including comments and strings.
	 *
	 * @param root the canonical repository
	 * @param tui the TUI tree
	 * @return the ordered source failures
	 * @throws IOException if enumeration, source reading, or strict UTF-8 decoding fails
	 */
	private static List<String> sourceFailures(Path root, Path tui) throws IOException
	{
		List<String> failures = new ArrayList<>();
		try (Stream<Path> paths = Files.walk(tui))
		{
			for (Path path : paths.filter(path -> path.getFileName().toString().endsWith(".rs")).
				sorted(ArtifactPaths.comparator(tui)).toList())
			{
				int line = 0;
				for (String text : TextLines.split(Files.readString(path)))
				{
					line += 1;
					if (IMPORTS.stream().anyMatch(pattern -> pattern.matcher(text).find()))
						failures.add(root.relativize(path) + ":" + line + " imports `codex_core`");
				}
			}
		}
		return failures;
	}
}
