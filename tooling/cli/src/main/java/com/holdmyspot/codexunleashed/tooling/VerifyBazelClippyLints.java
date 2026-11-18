package com.holdmyspot.codexunleashed.tooling;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Checks that Bazel's Clippy flags match the Cargo workspace lint levels.
 */
public final class VerifyBazelClippyLints
{
	/**
	 * Command name registered by {@link Main}.
	 */
	public static final String NAME = "verify-bazel-clippy-lints";
	/**
	 * Prefix selecting explicit Clippy configuration lines in Bazel.
	 */
	private static final String BAZEL_PREFIX =
		"build:clippy --@rules_rust//rust/settings:clippy_flag=";
	/**
	 * Long-form Clippy lint flag syntax.
	 */
	private static final Pattern LONG_FLAG = Pattern.compile("--(allow|warn|deny|forbid)=clippy::([a-z0-9_]+)");
	/**
	 * Short-form Clippy lint flag syntax.
	 */
	private static final Pattern SHORT_FLAG = Pattern.compile("-([AWDF])clippy::([a-z0-9_]+)");
	/**
	 * Cargo/Bazel levels accepted by the legacy checker.
	 */
	private static final List<String> LEVELS = List.of("allow", "warn", "deny", "forbid");
	/**
	 * Root-relative manifest path used by the default invocation.
	 */
	private static final Path DEFAULT_CARGO = Path.of("codex-rs", "Cargo.toml");
	/**
	 * Prevents construction.
	 */
	private VerifyBazelClippyLints()
	{
	}

	/**
	 * Compares Cargo workspace Clippy levels with explicit Bazel Clippy flags.
	 *
	 * @param scope invocation-owned TOML parser
	 *
	 * @param arguments command arguments after the command name
	 * @param out success and completed policy report output
	 * @param err syntax, policy-input and operational diagnostics
	 * @return zero when maps match, one for rejected inputs or mismatches, or two for command syntax errors
	 * @throws NullPointerException if an argument is null
	 */
	public static int run(ToolingScope scope, String[] arguments, PrintStream out, PrintStream err)
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		for (int index = 0; index < arguments.length; index += 1)
			Objects.requireNonNull(arguments[index], "arguments[" + index + "]");

		ParseResult parsed = parseArguments(arguments);
		if (parsed.syntaxError())
		{
			usage(err);
			return 2;
		}
		if (parsed.help())
		{
			printHelp(out);
			return 0;
		}

		try
		{
			Path root = SourceInputPaths.resolve(Path.of(parsed.root()));
			Path cargo = resolveInput(root, parsed.cargoToml(), DEFAULT_CARGO);
			Path bazelrc = resolveInput(root, parsed.bazelrc(), Path.of(".bazelrc"));
			Map<String, String> cargoLints = loadCargoLints(scope, cargo);
			Map<String, String> bazelLints = loadBazelLints(bazelrc);
			List<String> missing = sortedDifference(cargoLints, bazelLints);
			List<String> extra = sortedDifference(bazelLints, cargoLints);
			List<String> mismatched = new ArrayList<>();
			for (String lint : sortedIntersection(cargoLints, bazelLints))
				if (!cargoLints.get(lint).equals(bazelLints.get(lint)))
					mismatched.add(lint);

			if (missing.isEmpty() && extra.isEmpty() && mismatched.isEmpty())
			{
				out.println("Bazel clippy flags in " + displayPath(root, bazelrc) + " match " +
					displayPath(root, cargo) + " [workspace.lints.clippy].");
				return 0;
			}

			String example = findWorkspaceLintsExample(scope, root);
			printSyncError(root, cargo, bazelrc, cargoLints, bazelLints, missing, mismatched, extra, example, err);
			return 1;
		}
		catch (PolicyInputException failure)
		{
			err.println(failure.getMessage());
			return 1;
		}
		catch (IOException | InvalidPathException failure)
		{
			err.println("ERROR: " + failure.getMessage());
			return 1;
		}
	}

	/**
	 * Parses the command options, retaining the last value of each override.
	 *
	 * @param arguments arguments value
	 * @return the computed value
	 */
	private static ParseResult parseArguments(String[] arguments)
	{
		int helpIndex = -1;
		for (int index = 0; index < arguments.length; index += 1)
			if (arguments[index].equals("--help") || arguments[index].equals("-h"))
				helpIndex = index;
		if (helpIndex >= 0)
		{
			if (arguments.length == 1)
				return new ParseResult(null, null, null, true, false);
			if (arguments.length == 2 && !arguments[1 - helpIndex].startsWith("-"))
				return new ParseResult(null, null, null, true, false);
			return invalidArguments();
		}

		String root = null;
		String cargo = null;
		String bazelrc = null;
		for (int index = 0; index < arguments.length; index += 1)
		{
			String argument = arguments[index];
			if (!argument.startsWith("--"))
			{
				if (argument.startsWith("-") || root != null)
					return invalidArguments();
				root = argument;
				continue;
			}

			int equals = argument.indexOf('=');
			String optionPrefix;
			if (equals < 0)
				optionPrefix = argument;
			else
				optionPrefix = argument.substring(0, equals);
			String option = resolveOption(optionPrefix);
			if (option == null)
				return invalidArguments();
			String value;
			if (equals >= 0)
				value = argument.substring(equals + 1);
			else if (index + 1 < arguments.length && !arguments[index + 1].startsWith("-"))
			{
				index += 1;
				value = arguments[index];
			}
			else
				return invalidArguments();
			if (value.isEmpty())
				return invalidArguments();
			if (option.equals("--cargo-toml"))
				cargo = value;
			else
				bazelrc = value;
		}
		if (root == null || root.isEmpty())
			return invalidArguments();
		return new ParseResult(root, cargo, bazelrc, false, false);
	}

	/**
	 * Resolves an argparse-compatible unambiguous option prefix.
	 *
	 * @param prefix prefix value
	 * @return the computed value
	 */
	private static String resolveOption(String prefix)
	{
		String cargo = "--cargo-toml";
		String bazelrc = "--bazelrc";
		boolean cargoMatch = prefix.length() > 2 && cargo.startsWith(prefix);
		boolean bazelrcMatch = prefix.length() > 2 && bazelrc.startsWith(prefix);
		if (cargoMatch == bazelrcMatch)
			return null;
		if (cargoMatch)
			return cargo;
		return bazelrc;
	}

	/**
	 * Creates a syntax-error result.
	 *
	 * @return the computed value
	 */
	private static ParseResult invalidArguments()
	{
		return new ParseResult(null, null, null, false, true);
	}

	/**
	 * Resolves an override relative to the explicit repository root, then canonicalizes it.
	 *
	 * @param root root value
	 * @param override override value
	 * @param defaultPath defaultPath value
	 * @return the computed value
	 * @throws IOException if the operation cannot complete
	 */
	private static Path resolveInput(Path root, String override, Path defaultPath) throws IOException
	{
		Path input;
		if (override == null)
			input = root.resolve(defaultPath);
		else
			input = Path.of(override);
		if (override != null && !input.isAbsolute())
			input = root.resolve(input);
		return SourceInputPaths.resolve(input);
	}

	/**
	 * Parses the required workspace lint mapping without coercing native TOML values.
	 *
	 * @param scope scope value
	 * @param cargo cargo value
	 * @return the computed value
	 * @throws IOException if the operation cannot complete
	 * @throws PolicyInputException if the operation cannot complete
	 */
	private static Map<String, String> loadCargoLints(ToolingScope scope, Path cargo) throws IOException,
		PolicyInputException
	{
		JsonNode root = readToml(scope, cargo);
		JsonNode workspace = root.get("workspace");
		JsonNode lints = null;
		if (workspace != null)
			lints = workspace.get("lints");
		JsonNode clippy = null;
		if (lints != null)
			clippy = lints.get("clippy");
		if (workspace == null || !workspace.isObject() || lints == null || !lints.isObject() || clippy == null ||
			!clippy.isObject())
			throw new IOException(cargo + ": missing required TOML table [workspace.lints.clippy]");

		Map<String, String> levels = new HashMap<>();
		for (Map.Entry<String, JsonNode> entry : clippy.properties())
		{
			JsonNode value = entry.getValue();
			if (!value.isString())
				throw new PolicyInputException("expected string lint level for clippy::" + entry.getKey() + " in " +
					cargo + ", got " + pythonRepr(value));
			String raw = value.asString();
			String normalized = pythonStrip(raw).toLowerCase(Locale.ROOT);
			if (!LEVELS.contains(normalized))
				throw new PolicyInputException("unsupported lint level " + pythonString(raw) + " for clippy::" +
					entry.getKey() + " in " + cargo);
			levels.put(entry.getKey(), normalized);
		}
		return levels;
	}

	/**
	 * Parses one TOML document through the invocation-owned mapper.
	 *
	 * @param scope scope value
	 * @param path path value
	 * @return the computed value
	 * @throws IOException if the operation cannot complete
	 */
	private static JsonNode readToml(ToolingScope scope, Path path) throws IOException
	{
		String document = readText(path);
		try
		{
			return scope.getTomlMapper().readTree(document);
		}
		catch (JacksonException failure)
		{
			throw new IOException(path + ": invalid TOML: " + failure.getOriginalMessage(), failure);
		}
	}

	/**
	 * Reads a strictly decoded UTF-8 configuration document.
	 *
	 * @param path path value
	 * @return the computed value
	 * @throws IOException if the operation cannot complete
	 */
	private static String readText(Path path) throws IOException
	{
		return Files.readString(path, StandardCharsets.UTF_8);
	}

	/**
	 * Parses only the recognized Bazel flags, preserving Python splitlines numbering and duplicate order.
	 *
	 * @param bazelrc bazelrc value
	 * @return the computed value
	 * @throws IOException if the operation cannot complete
	 * @throws PolicyInputException if the operation cannot complete
	 */
	private static Map<String, String> loadBazelLints(Path bazelrc) throws IOException, PolicyInputException
	{
		Map<String, String> levels = new HashMap<>();
		Map<String, Integer> lines = new HashMap<>();
		try (var reader = new BufferedReader(new InputStreamReader(Files.newInputStream(bazelrc),
			strictUtf8Decoder())))
		{
			forEachPythonLine(reader, (line, lineNumber) ->
		{
			if (!line.startsWith(BAZEL_PREFIX))
				return;
			String flag = pythonStrip(line.substring(BAZEL_PREFIX.length()));
			if (flag.equals("-Dwarnings"))
				return;
			LintFlag parsed = parseFlag(flag);
			if (parsed == null)
				return;
			Integer firstLine = lines.get(parsed.name());
			if (firstLine != null)
				throw new PolicyInputException("duplicate Bazel clippy entry for clippy::" + parsed.name() + " at " +
					bazelrc + ":" + firstLine + " and " + bazelrc + ":" + lineNumber);
			levels.put(parsed.name(), parsed.level());
			lines.put(parsed.name(), lineNumber);
		});
		}
		return levels;
	}

	/**
	 * Creates a strict UTF-8 decoder for streamed Bazel configuration.
	 *
	 * @return the computed value
	 */
	private static CharsetDecoder strictUtf8Decoder()
	{
		return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).
			onUnmappableCharacter(CodingErrorAction.REPORT);
	}

	/**
	 * Selects a supported complete flag spelling.
	 *
	 * @param flag flag value
	 * @return the computed value
	 */
	private static LintFlag parseFlag(String flag)
	{
		Matcher longForm = LONG_FLAG.matcher(flag);
		if (longForm.matches())
			return new LintFlag(longForm.group(2), longForm.group(1));
		Matcher shortForm = SHORT_FLAG.matcher(flag);
		if (!shortForm.matches())
			return null;
		String level = switch (shortForm.group(1))
		{
			case "A" -> "allow";
			case "W" -> "warn";
			case "D" -> "deny";
			case "F" -> "forbid";
			default -> throw new AssertionError("Unsupported short level");
		};
		return new LintFlag(shortForm.group(2), level);
	}

	/**
	 * Visits line contents using the Unicode boundaries recognized by Python {@code str.splitlines()}.
	 *
	 * @param reader reader value
	 * @param consumer consumer value
	 * @throws IOException if the operation cannot complete
	 * @throws PolicyInputException if the operation cannot complete
	 */
	private static void forEachPythonLine(BufferedReader reader, LineConsumer consumer) throws IOException,
		PolicyInputException
	{
		StringBuilder line = new StringBuilder();
		int lineNumber = 1;
		boolean previousWasCarriageReturn = false;
		int pendingHighSurrogate = -1;
		while (true)
		{
			int current = reader.read();
			if (current < 0)
				break;
			int codePoint = current;
			if (Character.isHighSurrogate((char) current))
			{
				if (pendingHighSurrogate >= 0)
					line.append((char) pendingHighSurrogate);
				pendingHighSurrogate = current;
				continue;
			}
			if (pendingHighSurrogate >= 0)
			{
				if (Character.isLowSurrogate((char) current))
					codePoint = Character.toCodePoint((char) pendingHighSurrogate, (char) current);
				else
					line.append((char) pendingHighSurrogate);
				pendingHighSurrogate = -1;
			}
			if (previousWasCarriageReturn && codePoint == '\n')
			{
				previousWasCarriageReturn = false;
				continue;
			}
			previousWasCarriageReturn = false;
			if (codePoint == '\r' || isPythonLineSeparator(codePoint))
			{
				consumer.accept(line.toString(), lineNumber);
				lineNumber += 1;
				line.setLength(0);
				previousWasCarriageReturn = codePoint == '\r';
			}
			else
				line.appendCodePoint(codePoint);
		}
		if (pendingHighSurrogate >= 0)
			line.append((char) pendingHighSurrogate);
		if (!line.isEmpty())
			consumer.accept(line.toString(), lineNumber);
	}

	/**
	 * Identifies the non-CR Python splitlines separators.
	 *
	 * @param codePoint codePoint value
	 * @return the computed value
	 */
	private static boolean isPythonLineSeparator(int codePoint)
	{
		return codePoint == '\n' || codePoint == '\u000b' || codePoint == '\f' ||
			(codePoint >= '\u001c' && codePoint <= '\u001e') || codePoint == '\u0085' ||
			codePoint == '\u2028' || codePoint == '\u2029';
	}

	/**
	 * Removes Python's Unicode whitespace set from both ends of a string.
	 *
	 * @param value value value
	 * @return the computed value
	 */
	private static String pythonStrip(String value)
	{
		int start = 0;
		int end = value.length();
		while (start < end)
		{
			int codePoint = value.codePointAt(start);
			if (!isPythonWhitespace(codePoint))
				break;
			start += Character.charCount(codePoint);
		}
		while (start < end)
		{
			int codePoint = value.codePointBefore(end);
			if (!isPythonWhitespace(codePoint))
				break;
			end -= Character.charCount(codePoint);
		}
		return value.substring(start, end);
	}

	/**
	 * Identifies Python's string whitespace characters.
	 *
	 * @param codePoint codePoint value
	 * @return the computed value
	 */
	private static boolean isPythonWhitespace(int codePoint)
	{
		return (codePoint >= 0x0009 && codePoint <= 0x000d) || (codePoint >= 0x001c && codePoint <= 0x001f) ||
			codePoint == 0x0085 || Character.isSpaceChar(codePoint);
	}

	/**
	 * Finds a literal workspace opt-in example only when a mismatch needs the explanatory paragraph.
	 *
	 * @param scope scope value
	 * @param root root value
	 * @return the computed value
	 * @throws IOException if the operation cannot complete
	 */
	private static String findWorkspaceLintsExample(ToolingScope scope, Path root) throws IOException
	{
		Path codexRs = root.resolve("codex-rs");
		if (!Files.exists(codexRs))
			return null;
		List<Path> manifests;
		try (java.util.stream.Stream<Path> paths = Files.walk(codexRs))
		{
			manifests = paths.filter(path -> path.getFileName().toString().equals("Cargo.toml")).
				filter(path -> !path.equals(root.resolve(DEFAULT_CARGO))).
				sorted((left, right) -> compareCodePoints(root.relativize(left).toString(),
					root.relativize(right).toString())).toList();
		}
		for (Path manifest : manifests)
		{
			JsonNode parsed = readToml(scope, manifest);
			JsonNode lints = parsed.get("lints");
			if (lints == null)
				continue;
			if (!lints.isObject())
				throw new IOException(manifest + ": expected table for lints while selecting workspace example");
			JsonNode workspace = lints.get("workspace");
			if (workspace != null && workspace.isBoolean() && workspace.booleanValue())
				return displayPath(root, manifest);
		}
		return null;
	}

	/**
	 * Writes the frozen comparison report with missing, mismatched and extra categories in order.
	 *
	 * @param root root value
	 * @param cargo cargo value
	 * @param bazelrc bazelrc value
	 * @param cargoLints cargoLints value
	 * @param bazelLints bazelLints value
	 * @param missing missing value
	 * @param mismatched mismatched value
	 * @param extra extra value
	 * @param example example value
	 * @param err err value
	 */
	private static void printSyncError(Path root, Path cargo, Path bazelrc, Map<String, String> cargoLints,
		Map<String, String> bazelLints, List<String> missing, List<String> mismatched, List<String> extra,
		String example, PrintStream err)
	{
		String cargoDisplay = displayPath(root, cargo);
		String bazelDisplay = displayPath(root, bazelrc);
		err.println("ERROR: Bazel clippy flags are out of sync with Cargo workspace clippy lints.");
		err.println();
		err.println("Cargo defines the source of truth in " + cargoDisplay + " [workspace.lints.clippy].");
		if (example != null)
		err.println("Cargo applies those lint levels to member crates that opt into `[lints] workspace = true`, " +
				"for example " + example + ".");
		err.println("Bazel clippy does not ingest Cargo lint levels automatically, and `clippy.toml` can configure " +
		"lint behavior but cannot set allow/warn/deny/forbid.");
		err.println("Update " + bazelDisplay + " so its `build:clippy` `clippy_flag` entries match Cargo.");
		if (!missing.isEmpty())
		{
			err.println();
			err.println("Missing Bazel entries:");
			for (String lint : missing)
				err.println("  " + BAZEL_PREFIX + "--" + cargoLints.get(lint) + "=clippy::" + lint);
		}
		if (!mismatched.isEmpty())
		{
			err.println();
			err.println("Mismatched lint levels:");
			for (String lint : mismatched)
			{
				err.println("  clippy::" + lint + ": Cargo has " + cargoLints.get(lint) + ", Bazel has " +
					bazelLints.get(lint));
				err.println("    expected: " + BAZEL_PREFIX + "--" + cargoLints.get(lint) + "=clippy::" + lint);
			}
		}
		if (!extra.isEmpty())
		{
			err.println();
			err.println("Extra Bazel entries with no Cargo counterpart:");
			for (String lint : extra)
				err.println("  " + BAZEL_PREFIX + "--" + bazelLints.get(lint) + "=clippy::" + lint);
		}
	}

	/**
	 * Sorts one key difference by Unicode code point.
	 *
	 * @param left left value
	 * @param right right value
	 * @return the computed value
	 */
	private static List<String> sortedDifference(Map<String, String> left, Map<String, String> right)
	{
		List<String> result = new ArrayList<>(left.keySet());
		result.removeAll(right.keySet());
		result.sort(VerifyBazelClippyLints::compareCodePoints);
		return result;
	}

	/**
	 * Sorts shared keys by Unicode code point.
	 *
	 * @param left left value
	 * @param right right value
	 * @return the computed value
	 */
	private static List<String> sortedIntersection(Map<String, String> left, Map<String, String> right)
	{
		List<String> result = new ArrayList<>(left.keySet());
		result.retainAll(right.keySet());
		result.sort(VerifyBazelClippyLints::compareCodePoints);
		return result;
	}

	/**
	 * Compares strings lexically by Unicode code point rather than UTF-16 code unit.
	 *
	 * @param left left value
	 * @param right right value
	 * @return the computed value
	 */
	private static int compareCodePoints(String left, String right)
	{
		int leftIndex = 0;
		int rightIndex = 0;
		while (leftIndex < left.length() && rightIndex < right.length())
		{
			int comparison = Integer.compare(left.codePointAt(leftIndex), right.codePointAt(rightIndex));
			if (comparison != 0)
				return comparison;
			leftIndex += Character.charCount(left.codePointAt(leftIndex));
			rightIndex += Character.charCount(right.codePointAt(rightIndex));
		}
		return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
	}

	/**
	 * Converts a TOML node to Python's diagnostic representation for rejected non-string levels.
	 *
	 * @param value value value
	 * @return the computed value
	 */
	private static String pythonRepr(JsonNode value)
	{
		if (value.isString())
			return pythonString(value.asString());
		if (value.isBoolean())
		{
			if (value.booleanValue())
				return "True";
			return "False";
		}
		if (value.isNull())
			return "None";
		if (value.isNumber())
			return value.asString();
		if (value.isArray())
		{
			StringJoiner items = new StringJoiner(", ", "[", "]");
			for (JsonNode item : value)
				items.add(pythonRepr(item));
			return items.toString();
		}
		if (value.isObject())
		{
			StringJoiner entries = new StringJoiner(", ", "{", "}");
			for (Map.Entry<String, JsonNode> entry : value.properties())
				entries.add(pythonString(entry.getKey()) + ": " + pythonRepr(entry.getValue()));
			return entries.toString();
		}
		String temporal = value.toString();
		if (temporal.length() >= 2 && temporal.startsWith("\"") && temporal.endsWith("\""))
			temporal = temporal.substring(1, temporal.length() - 1);
		if (temporal.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
		{
			LocalDate date = LocalDate.parse(temporal);
			return "datetime.date(" + date.getYear() + ", " + date.getMonthValue() + ", " + date.getDayOfMonth() + ")";
		}
		return temporal;
	}

	/**
	 * Renders a Python-compatible quoted string for policy diagnostics.
	 *
	 * @param value value value
	 * @return the computed value
	 */
	private static String pythonString(String value)
	{
		char quote = '\'';
		if (value.indexOf('\'') >= 0 && value.indexOf('"') < 0)
			quote = '"';
		StringBuilder result = new StringBuilder().append(quote);
		for (int index = 0; index < value.length();)
		{
			int codePoint = value.codePointAt(index);
			index += Character.charCount(codePoint);
			if (codePoint == '\\' || codePoint == quote)
				result.append('\\').appendCodePoint(codePoint);
			else if (codePoint == '\n')
				result.append("\\n");
			else if (codePoint == '\r')
				result.append("\\r");
			else if (codePoint == '\t')
				result.append("\\t");
			else if (Character.isISOControl(codePoint))
				result.append(String.format(Locale.ROOT, "\\x%02x", codePoint));
			else
				result.appendCodePoint(codePoint);
		}
		return result.append(quote).toString();
	}

	/**
	 * Displays paths inside the explicit root relatively and external overrides absolutely.
	 *
	 * @param root root value
	 * @param path path value
	 * @return the computed value
	 */
	private static String displayPath(Path root, Path path)
	{
		if (path.startsWith(root))
			return root.relativize(path).toString();
		return path.toString();
	}

	/**
	 * Writes command usage for syntax failures.
	 *
	 * @param err err value
	 */
	private static void usage(PrintStream err)
	{
		err.println("Usage: codex-tooling " + NAME + " <repository-root> [--cargo-toml PATH] [--bazelrc PATH]");
	}

	/**
	 * Writes command-specific help without consulting the filesystem.
	 *
	 * @param out out value
	 */
	private static void printHelp(PrintStream out)
	{
		out.println("usage: codex-tooling " + NAME + " [-h] [--cargo-toml PATH] [--bazelrc PATH] <repository-root>");
		out.println();
		out.println("Verify that Bazel clippy flags match Cargo workspace lint levels.");
		out.println();
		out.println("positional arguments:");
		out.println("  <repository-root>  Explicit repository root");
		out.println();
		out.println("options:");
		out.println("  -h, --help          Show this help message and exit");
		out.println("  --cargo-toml PATH   Workspace Cargo.toml (default: ROOT/codex-rs/Cargo.toml)");
		out.println("  --bazelrc PATH      Bazel configuration (default: ROOT/.bazelrc)");
	}

	/**
	 * One parsed Bazel lint and its normalized level.
	 *
	 * @param name name value
	 * @param level level value
	 */
	private record LintFlag(String name, String level)
	{
	}

	/**
	 * Parsed command-line selection.
	 *
	 * @param root root value
	 * @param cargoToml cargoToml value
	 * @param bazelrc bazelrc value
	 * @param help help value
	 * @param syntaxError syntaxError value
	 */
	private record ParseResult(String root, String cargoToml, String bazelrc, boolean help, boolean syntaxError)
	{
	}

	/**
	 * A recognized policy-input rejection whose text is part of the checker contract.
	 */
	private static final class PolicyInputException extends Exception
	{
		/** Stable serialization identifier. */
		private static final long serialVersionUID = 1L;
		/**
		 * Records a policy input error.
		 *
		 * @param message diagnostic text
		 */
		private PolicyInputException(String message)
		{
			super(message);
		}
	}

	/**
	 * Receives each Python-compatible split line and its one-based line number.
	 */
	@FunctionalInterface
	private interface LineConsumer
	{
		/**
		 * Receives one split line.
		 *
		 * @param line line contents
		 * @param lineNumber one-based line number
		 * @throws IOException if reading fails
		 * @throws PolicyInputException if a duplicate is detected
		 */
		void accept(String line, int lineNumber) throws IOException, PolicyInputException;
	}
}
