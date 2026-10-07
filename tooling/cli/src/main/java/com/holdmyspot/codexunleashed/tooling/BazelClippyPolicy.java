package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** Checks explicit Bazel Clippy flags against the Cargo workspace's authoritative lint levels. */
public final class BazelClippyPolicy
{
	private static final String PREFIX = "build:clippy --@rules_rust//rust/settings:clippy_flag=";
	private static final Set<String> LEVELS = Set.of("allow", "warn", "deny", "forbid");
	private static final Pattern LONG_FLAG = Pattern.compile("--(allow|warn|deny|forbid)=clippy::([a-z0-9_]+)");
	private static final Pattern SHORT_FLAG = Pattern.compile("-([AWDF])clippy::([a-z0-9_]+)");
	private static final Map<String, String> SHORT_LEVELS =
		Map.of("A", "allow", "W", "warn", "D", "deny", "F", "forbid");
	private static final Pattern TRIM = Pattern.compile(
		"^[\\p{javaWhitespace}\\p{javaSpaceChar}\\x{85}]+|[\\p{javaWhitespace}\\p{javaSpaceChar}\\x{85}]+$");
	private static final Comparator<String> NAMES =
		(first, second) -> Arrays.compare(first.codePoints().toArray(), second.codePoints().toArray());

	/** Prevents construction of the stateless policy. */
	private BazelClippyPolicy()
	{
	}

	/**
	 * Reads both inputs completely before reporting matching or differing levels.
	 *
	 * @param repository the repository used for relative paths and opt-in examples
	 * @param cargoToml the workspace manifest, including caller-selected overrides
	 * @param bazelrc the Bazel configuration, including caller-selected overrides
	 * @param out the successful match report
	 * @param err the mismatch report
	 * @return zero for equal lint maps, one for differing maps
	 * @throws IOException if reading, parsing, a malformed lint level, or a duplicate Bazel entry fails
	 */
	public static int check(Path repository, Path cargoToml, Path bazelrc, PrintStream out, PrintStream err)
		throws IOException
	{
		Objects.requireNonNull(repository, "repository");
		Objects.requireNonNull(cargoToml, "cargoToml");
		Objects.requireNonNull(bazelrc, "bazelrc");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		Path root = repository.toRealPath();
		Path cargo = cargoToml.toRealPath();
		Path bazel = bazelrc.toRealPath();
		Map<String, String> cargoLints = cargoLints(cargo);
		Map<String, String> bazelLints = bazelLints(bazel);
		if (cargoLints.equals(bazelLints))
		{
			out.println("Bazel clippy flags in " + display(root, bazel) + " match " + display(root, cargo) +
				" [workspace.lints.clippy].");
			return 0;
		}
		String example = findExample(root);
		report(root, cargo, bazel, cargoLints, bazelLints, example, err);
		return 1;
	}

	/**
	 * Requires string values and normalizes only supported Cargo lint levels.
	 *
	 * @param cargo the canonical workspace manifest
	 * @return the lint name to normalized level map
	 * @throws IOException if reading, parsing, or level validation fails
	 */
	private static Map<String, String> cargoLints(Path cargo) throws IOException
	{
		JsonNode lints = parse(cargo).path("workspace").path("lints").path("clippy");
		if (!lints.isObject())
			throw new IOException("Expected [workspace.lints.clippy] table in " + cargo);
		Map<String, String> result = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> lint : lints.properties())
		{
			JsonNode level = lint.getValue();
			if (!level.isString())
				throw new IOException("expected string lint level for clippy::" + lint.getKey() + " in " + cargo +
					", got " + level);
			String normalized = TRIM.matcher(level.stringValue()).replaceAll("").toLowerCase(Locale.ROOT);
			if (!LEVELS.contains(normalized))
				throw new IOException("unsupported lint level '" + level.stringValue() + "' for clippy::" +
					lint.getKey() + " in " + cargo);
			result.put(lint.getKey(), normalized);
		}
		return result;
	}

	/**
	 * Keeps only recognized flags beginning at the first character of a configuration line.
	 *
	 * @param bazel the canonical configuration path
	 * @return the lint name to level map
	 * @throws IOException if reading fails or a recognized lint appears twice
	 */
	private static Map<String, String> bazelLints(Path bazel) throws IOException
	{
		Map<String, String> result = new LinkedHashMap<>();
		Map<String, Integer> numbers = new LinkedHashMap<>();
		int number = 0;
		for (String line : TextLines.split(Files.readString(bazel)))
		{
			number += 1;
			if (!line.startsWith(PREFIX))
				continue;
			String flag = TRIM.matcher(line.substring(PREFIX.length())).replaceAll("");
			Matcher match = LONG_FLAG.matcher(flag);
			boolean shortForm = false;
			if (!match.matches())
			{
				match = SHORT_FLAG.matcher(flag);
				if (!match.matches())
					continue;
				shortForm = true;
			}
			String lint = match.group(2);
			if (result.containsKey(lint))
				throw new IOException("duplicate Bazel clippy entry for clippy::" + lint + " at " + bazel + ":" +
					numbers.get(lint) + " and " + bazel + ":" + number);
			String level = match.group(1);
			if (shortForm)
				level = SHORT_LEVELS.get(level);
			result.put(lint, level);
			numbers.put(lint, number);
		}
		return result;
	}

	/**
	 * Finds the first opt-in example in retained component path order.
	 *
	 * @param root the canonical repository
	 * @return a relative example path, or an empty string if no member opts in
	 * @throws IOException if walking, reading, or parsing fails
	 */
	private static String findExample(Path root) throws IOException
	{
		Path workspace = root.resolve("codex-rs");
		try (Stream<Path> files = Files.walk(workspace))
		{
			for (Path file : files.filter(path -> path.getFileName().toString().equals("Cargo.toml")).
				sorted(ArtifactPaths.comparator(workspace)).toList())
			{
				if (file.equals(workspace.resolve("Cargo.toml")))
					continue;
				JsonNode inherited = parse(file).path("lints").path("workspace");
				if (inherited.isBoolean() && inherited.booleanValue())
					return ArtifactPaths.relativeName(root, file);
			}
		}
		return "";
	}

	/**
	 * Preserves precise file context when TOML parsing fails.
	 *
	 * @param file the manifest
	 * @return the parsed document
	 * @throws IOException if reading or parsing fails
	 */
	private static JsonNode parse(Path file) throws IOException
	{
		try
		{
			return TomlDocuments.parse(Files.readString(file));
		}
		catch (JacksonException failure)
		{
			throw new IOException("Cannot parse " + file + ": " + failure.getMessage(), failure);
		}
	}

	/**
	 * Emits the retained guidance and sorted missing, mismatched, and extra entries.
	 *
	 * @param root the repository
	 * @param cargo the workspace manifest
	 * @param bazel the configuration
	 * @param cargoLints authoritative levels
	 * @param bazelLints explicit flags
	 * @param example an optional opt-in example
	 * @param err diagnostic output
	 */
	private static void report(Path root, Path cargo, Path bazel, Map<String, String> cargoLints,
		Map<String, String> bazelLints, String example, PrintStream err)
	{
		err.println("ERROR: Bazel clippy flags are out of sync with Cargo workspace clippy lints.");
		err.println();
		err.println("Cargo defines the source of truth in " + display(root, cargo) + " [workspace.lints.clippy].");
		if (!example.isEmpty())
			err.println("Cargo applies those lint levels to member crates that opt into " +
				"`[lints] workspace = true`, for example " + example + ".");
		err.println("Bazel clippy does not ingest Cargo lint levels automatically, and " +
			"`clippy.toml` can configure lint behavior but cannot set allow/warn/deny/forbid.");
		err.println("Update " + display(root, bazel) + " so its `build:clippy` `clippy_flag` entries match Cargo.");

		List<String> missing = cargoLints.keySet().stream().filter(lint -> !bazelLints.containsKey(lint)).
			sorted(NAMES).toList();
		if (!missing.isEmpty())
		{
			err.println();
			err.println("Missing Bazel entries:");
			for (String lint : missing)
				err.println("  " + render(lint, cargoLints.get(lint)));
		}
		List<String> mismatched = cargoLints.keySet().stream().filter(bazelLints::containsKey).
			filter(lint -> !cargoLints.get(lint).equals(bazelLints.get(lint))).sorted(NAMES).toList();
		if (!mismatched.isEmpty())
		{
			err.println();
			err.println("Mismatched lint levels:");
			for (String lint : mismatched)
			{
				err.println("  clippy::" + lint + ": Cargo has " + cargoLints.get(lint) +
					", Bazel has " + bazelLints.get(lint));
				err.println("    expected: " + render(lint, cargoLints.get(lint)));
			}
		}
		List<String> extra = bazelLints.keySet().stream().filter(lint -> !cargoLints.containsKey(lint)).
			sorted(NAMES).toList();
		if (!extra.isEmpty())
		{
			err.println();
			err.println("Extra Bazel entries with no Cargo counterpart:");
			for (String lint : extra)
				err.println("  " + render(lint, bazelLints.get(lint)));
		}
	}

	/**
	 * Renders a canonical long-form flag.
	 *
	 * @param lint the lint name
	 * @param level the normalized level
	 * @return the canonical configuration entry
	 */
	private static String render(String lint, String level)
	{
		return PREFIX + "--" + level + "=clippy::" + lint;
	}

	/**
	 * Displays repository paths relative to the root and external paths unchanged.
	 *
	 * @param root the repository
	 * @param path the input
	 * @return a relative in-tree path or unchanged external path
	 */
	private static String display(Path root, Path path)
	{
		if (path.startsWith(root))
			return ArtifactPaths.relativeName(root, path);
		return path.toString();
	}
}
