package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;

/**
 * Verifies that the {@code rusty_v8} {@code http_file} entries in {@code MODULE.bazel} match the checked-in checksum
 * manifest for the selected V8 version.
 */
public final class VerifyV8ModuleChecksums
{
	/**
	 * The command name that selects this verifier.
	 */
	public static final String NAME = "verify-v8-module-checksums";
	/**
	 * The only diagnostic written for invalid command syntax.
	 */
	private static final String USAGE = "Usage: codex-tooling " + NAME + " <repository-root>\n";
	/**
	 * The module file, relative to the repository root.
	 */
	private static final String MODULE_FRAGMENT = "MODULE.bazel";
	/**
	 * The Cargo lock file, relative to the repository root.
	 */
	private static final String LOCK_FRAGMENT = "codex-rs/Cargo.lock";
	/**
	 * The checksum manifest directory, relative to the repository root.
	 */
	private static final String MANIFEST_DIRECTORY = "third_party/v8";
	/**
	 * Marks a number that is not a number, which sorts after every other number.
	 */
	private static final int NOT_A_NUMBER = 2;
	/**
	 * A character class holding every character that Python's {@code str.isspace} accepts.
	 */
	private static final String SPACE = "[\\t-\\r\\x{1c}-\\x{20}\\x{85}\\x{a0}\\x{1680}\\x{2000}-\\x{200a}\\x{2028}" +
		"\\x{2029}\\x{202f}\\x{205f}\\x{3000}]";
	/**
	 * Matches one {@code http_file(...)} block.
	 */
	private static final Pattern HTTP_FILE_BLOCK = Pattern.compile("^http_file\\(\\n.*?^\\)\\n?",
		Pattern.MULTILINE | Pattern.DOTALL | Pattern.UNIX_LINES);
	/**
	 * Extracts the version from a {@code rusty_v8} repository name.
	 */
	private static final Pattern VERSION_NAME = Pattern.compile("rusty_v8_([0-9]+)_([0-9]+)_([0-9]+)_");
	/**
	 * Matches the pinned download location of the V8 crate.
	 */
	private static final Pattern CRATE_URL = Pattern.compile(
		"https://static\\.crates\\.io/crates/v8/v8-([0-9]+\\.[0-9]+\\.[0-9]+)\\.crate");
	/**
	 * Matches a lowercase SHA-256 digest.
	 */
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	/**
	 * Matches the {@code name} field of a block.
	 */
	private static final Pattern NAME_FIELD = fieldPattern("name");
	/**
	 * Matches the {@code downloaded_file_path} field of a block.
	 */
	private static final Pattern PATH_FIELD = fieldPattern("downloaded_file_path");
	/**
	 * Matches the {@code sha256} field of a block.
	 */
	private static final Pattern DIGEST_FIELD = fieldPattern("sha256");

	/**
	 * Prevents construction.
	 */
	private VerifyV8ModuleChecksums()
	{
	}

	/**
	 * Checks the module checksums of one repository without modifying it or closing the caller's streams.
	 *
	 * @param scope     invocation-owned tooling scope
	 * @param arguments command arguments after the command name
	 * @param out       caller-owned output
	 * @param err       caller-owned diagnostics
	 * @return zero if the checksums match, one for drift, rejected input or an operational failure, or two for invalid
	 *         syntax
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(ToolingScope scope, String[] arguments, PrintStream out, PrintStream err)
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		if (arguments.length != 1)
		{
			err.print(USAGE);
			return 2;
		}
		Path root;
		try
		{
			root = SourceInputPaths.resolve(Path.of(arguments[0]));
		}
		catch (IOException | InvalidPathException failure)
		{
			err.print("ERROR: " + arguments[0] + ": " + describe(failure) + "\n");
			return 1;
		}
		try
		{
			out.print(check(scope, root) + "\n");
			return 0;
		}
		catch (Rejection rejection)
		{
			err.print(rejection.getMessage() + "\n");
			return 1;
		}
		catch (OperationalFailure failure)
		{
			err.print("ERROR: " + failure.location() + ": " + failure.getMessage() + "\n");
			return 1;
		}
	}

	/**
	 * Describes a failure without a stack trace or a class name unless nothing else is known.
	 *
	 * @param failure the failure to describe
	 * @return the reason of the failure
	 */
	private static String describe(Exception failure)
	{
		if (failure instanceof NoSuchFileException)
			return "No such file or directory";
		if (failure instanceof FileSystemException fileFailure && fileFailure.getReason() != null)
			return fileFailure.getReason();
		String message = failure.getMessage();
		if (message == null)
			return failure.getClass().getSimpleName();
		return message;
	}

	/**
	 * Selects the version, validates the manifest and compares it with the module.
	 *
	 * @param scope invocation-owned tooling scope
	 * @param root  the real path of the repository root
	 * @return the success line
	 * @throws OperationalFailure if an input cannot be read or interpreted
	 * @throws Rejection          if the inputs violate the checksum policy
	 */
	private static String check(ToolingScope scope, Path root) throws OperationalFailure, Rejection
	{
		Path module = root.resolve(MODULE_FRAGMENT);
		String text = readOperational(module);
		String version = selectVersion(scope, root, text);
		Path manifest = manifestPath(root, version, module);
		Map<String, String> checksums = parseManifest(manifest);
		checkEntries(text, checksums, version);
		return module + " rusty_v8 " + version + " checksums match " + manifest;
	}

	/**
	 * Reads a text file as strict UTF-8 with universal newlines, the way the reference tool does.
	 *
	 * @param path the file to read
	 * @return the decoded text with every line terminator replaced by a line feed
	 * @throws IOException if the file cannot be read or is not valid UTF-8
	 */
	private static String readText(Path path) throws IOException
	{
		byte[] bytes = Files.readAllBytes(path);
		CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).
			onUnmappableCharacter(CodingErrorAction.REPORT);
		ByteBuffer input = ByteBuffer.wrap(bytes);
		CharBuffer decoded = CharBuffer.allocate(bytes.length);
		CoderResult result = decoder.decode(input, decoded, true);
		if (result.isError())
			throw new IOException(VerifyReadmeCharacters.describeMalformedInput(bytes, input.position(), result.length()));
		decoded.flip();
		return decoded.toString().replace("\r\n", "\n").replace('\r', '\n');
	}

	/**
	 * Reads a text file and reports every failure as operational.
	 *
	 * @param path the file to read
	 * @return the decoded text
	 * @throws OperationalFailure if the file cannot be read or decoded
	 */
	private static String readOperational(Path path) throws OperationalFailure
	{
		try
		{
			return readText(path);
		}
		catch (IOException failure)
		{
			throw new OperationalFailure(path, describe(failure));
		}
	}

	/**
	 * Selects the V8 version from the module or, if the module names none, from the lock file.
	 *
	 * @param scope  invocation-owned tooling scope
	 * @param root   the real path of the repository root
	 * @param module the module text
	 * @return the version
	 * @throws OperationalFailure if the lock file cannot be interpreted
	 * @throws Rejection          if the module or the lock file names several versions
	 */
	private static String selectVersion(ToolingScope scope, Path root, String module)
		throws OperationalFailure, Rejection
	{
		Set<String> versions = new TreeSet<>();
		Matcher blocks = HTTP_FILE_BLOCK.matcher(module);
		while (blocks.find())
		{
			String name = field(NAME_FIELD, blocks.group());
			if (name != null)
			{
				Matcher version = VERSION_NAME.matcher(name);
				if (version.lookingAt())
					versions.add(version.group(1) + "." + version.group(2) + "." + version.group(3));
			}
		}
		if (versions.size() == 1)
			return versions.iterator().next();
		if (versions.size() > 1)
		{
			throw new Rejection("expected at most one rusty_v8 http_file version in MODULE.bazel, found: " +
				reprList(versions) + "; pass --version explicitly");
		}
		return lockVersion(scope, root, module);
	}

	/**
	 * Selects the V8 version resolved by the Cargo lock file, or pinned by the module if the lock file has none.
	 *
	 * @param scope  invocation-owned tooling scope
	 * @param root   the real path of the repository root
	 * @param module the module text
	 * @return the version
	 * @throws OperationalFailure if the lock file cannot be read or interpreted
	 * @throws Rejection          if the lock file or the module names several versions
	 */
	private static String lockVersion(ToolingScope scope, Path root, String module)
		throws OperationalFailure, Rejection
	{
		Path lock = root.resolve(LOCK_FRAGMENT);
		Object document;
		try
		{
			document = scope.getTomlMapper().readValue(readOperational(lock), Object.class);
		}
		catch (JacksonException failure)
		{
			throw new OperationalFailure(lock, "invalid TOML: " + failure.getOriginalMessage());
		}
		List<Object> distinct = distinctVersions(lock, packageVersions(lock, document));
		if (distinct.size() == 1)
		{
			if (distinct.get(0) instanceof String version)
				return version;
			throw new OperationalFailure(lock, "the resolved v8 version must be a string");
		}
		if (distinct.size() > 1)
		{
			Kind kind = kindOf(distinct.get(0));
			for (Object candidate : distinct)
			{
				if (kindOf(candidate) != kind)
					throw new OperationalFailure(lock, "the resolved v8 versions cannot be ordered");
			}
			distinct.sort((first, second) -> compare(kind, first, second));
			throw new Rejection("expected exactly one resolved v8 version, found: " + reprList(distinct));
		}
		return crateVersion(module);
	}

	/**
	 * Collects the {@code version} of every {@code v8} package of a lock file.
	 *
	 * @param lock     the lock file location
	 * @param document the parsed lock file
	 * @return the version values, in file order
	 * @throws OperationalFailure if the document does not have the lock file structure
	 */
	private static List<Object> packageVersions(Path lock, Object document) throws OperationalFailure
	{
		if (!(document instanceof Map<?, ?> root))
			throw new OperationalFailure(lock, "expected a TOML table");
		if (!root.containsKey("package"))
			throw new OperationalFailure(lock, "missing package");
		Object packages = root.get("package");
		List<Object> versions = new ArrayList<>();
		if (packages instanceof List<?> entries)
		{
			for (Object entry : entries)
				addVersion(lock, entry, versions);
			return versions;
		}
		if ((packages instanceof Map<?, ?> table && table.isEmpty()) ||
			(packages instanceof String text && text.isEmpty()))
		{
			return versions;
		}
		throw new OperationalFailure(lock, "package must be an array of tables");
	}

	/**
	 * Adds the version of one package if the package is {@code v8}.
	 *
	 * @param lock     the lock file location
	 * @param entry    one element of the {@code package} array
	 * @param versions the collected versions
	 * @throws OperationalFailure if the package lacks a name or the selected package lacks a version
	 */
	private static void addVersion(Path lock, Object entry, List<Object> versions) throws OperationalFailure
	{
		if (!(entry instanceof Map<?, ?> table))
			throw new OperationalFailure(lock, "package entry must be a table");
		if (!table.containsKey("name"))
			throw new OperationalFailure(lock, "package entry is missing name");
		if (!"v8".equals(table.get("name")))
			return;
		if (!table.containsKey("version"))
			throw new OperationalFailure(lock, "package v8 is missing version");
		versions.add(table.get("version"));
	}

	/**
	 * Removes versions that Python considers equal, keeping the first of each.
	 *
	 * @param lock     the lock file location
	 * @param versions the version values
	 * @return the distinct versions
	 * @throws OperationalFailure if a version has a type that Python cannot hash
	 */
	private static List<Object> distinctVersions(Path lock, List<Object> versions) throws OperationalFailure
	{
		List<Object> distinct = new ArrayList<>();
		for (Object raw : versions)
		{
			Kind kind = kindOf(raw);
			if (kind == Kind.UNSUPPORTED)
			{
				throw new OperationalFailure(lock, "the resolved v8 version has an unsupported type: " +
					raw.getClass().getSimpleName());
			}
			Object version = truncateToMicroseconds(raw);
			boolean seen = false;
			for (Object existing : distinct)
			{
				if (kindOf(existing) == kind && sameValue(kind, existing, version))
					seen = true;
			}
			if (!seen)
				distinct.add(version);
		}
		return distinct;
	}

	/**
	 * Truncates a temporal value to the microsecond precision of Python.
	 *
	 * @param value a version value
	 * @return the value with at most microsecond precision, or the value itself if it is not temporal
	 */
	private static Object truncateToMicroseconds(Object value)
	{
		if (value instanceof LocalTime time)
			return time.truncatedTo(ChronoUnit.MICROS);
		if (value instanceof LocalDateTime stamp)
			return stamp.truncatedTo(ChronoUnit.MICROS);
		if (value instanceof OffsetDateTime stamp)
			return stamp.truncatedTo(ChronoUnit.MICROS);
		return value;
	}

	/**
	 * Classifies a version value by the Python type that it models.
	 *
	 * @param value a version value
	 * @return the kind of the value
	 */
	private static Kind kindOf(Object value)
	{
		if (value instanceof String)
			return Kind.STRING;
		if (value instanceof Boolean || value instanceof Number)
			return Kind.NUMBER;
		if (value instanceof LocalDate)
			return Kind.DATE;
		if (value instanceof LocalTime)
			return Kind.TIME;
		if (value instanceof LocalDateTime)
			return Kind.LOCAL_DATETIME;
		if (value instanceof OffsetDateTime)
			return Kind.OFFSET_DATETIME;
		return Kind.UNSUPPORTED;
	}

	/**
	 * Determines whether Python considers two values of one kind equal.
	 *
	 * @param kind   the kind of both values
	 * @param first  the first value
	 * @param second the second value
	 * @return {@code true} if the values are equal
	 */
	private static boolean sameValue(Kind kind, Object first, Object second)
	{
		if (kind == Kind.NUMBER && (rank(first) == NOT_A_NUMBER || rank(second) == NOT_A_NUMBER))
			return false;
		return compare(kind, first, second) == 0;
	}

	/**
	 * Orders two values of one kind the way Python orders them.
	 *
	 * @param kind   the kind of both values
	 * @param first  the first value
	 * @param second the second value
	 * @return a negative number, zero or a positive number if the first value is less than, equal to or greater than
	 *         the second one
	 */
	private static int compare(Kind kind, Object first, Object second)
	{
		return switch (kind)
		{
			case NUMBER -> compareNumbers(first, second);
			case STRING -> compareCodePoints((String) first, (String) second);
			case DATE -> ((LocalDate) first).compareTo((LocalDate) second);
			case TIME -> ((LocalTime) first).compareTo((LocalTime) second);
			case LOCAL_DATETIME -> ((LocalDateTime) first).compareTo((LocalDateTime) second);
			case OFFSET_DATETIME -> ((OffsetDateTime) first).toInstant().compareTo(((OffsetDateTime) second).toInstant());
			case UNSUPPORTED -> throw new IllegalStateException("Unsupported version kind");
		};
	}

	/**
	 * Orders two strings by code point, the way Python does.
	 *
	 * @param first  the first string
	 * @param second the second string
	 * @return a negative number, zero or a positive number if the first string sorts before, together with or after the
	 *         second one
	 */
	private static int compareCodePoints(String first, String second)
	{
		int firstIndex = 0;
		int secondIndex = 0;
		while (firstIndex < first.length() && secondIndex < second.length())
		{
			int left = first.codePointAt(firstIndex);
			int right = second.codePointAt(secondIndex);
			if (left != right)
				return Integer.compare(left, right);
			firstIndex += Character.charCount(left);
			secondIndex += Character.charCount(right);
		}
		return Integer.compare(first.length() - firstIndex, second.length() - secondIndex);
	}

	/**
	 * Orders two Python numbers exactly, placing not-a-number after every other value.
	 *
	 * @param first  the first number, boolean or float
	 * @param second the second number, boolean or float
	 * @return a negative number, zero or a positive number if the first value is less than, equal to or greater than
	 *         the second one
	 */
	private static int compareNumbers(Object first, Object second)
	{
		int firstRank = rank(first);
		int byRank = Integer.compare(firstRank, rank(second));
		if (byRank != 0 || firstRank != 0)
			return byRank;
		return exact(first).compareTo(exact(second));
	}

	/**
	 * Classifies a number as negative infinity, finite, positive infinity or not a number.
	 *
	 * @param value a number or boolean
	 * @return {@code -1}, {@code 0}, {@code 1} or {@link #NOT_A_NUMBER}
	 */
	private static int rank(Object value)
	{
		if (!isFloat(value))
			return 0;
		double number = ((Number) value).doubleValue();
		if (Double.isNaN(number))
			return NOT_A_NUMBER;
		if (number == Double.POSITIVE_INFINITY)
			return 1;
		if (number == Double.NEGATIVE_INFINITY)
			return -1;
		return 0;
	}

	/**
	 * Returns the exact value of a finite number. The binary64 constructor is intended: the decimal expansion of a
	 * double that {@link BigDecimal#valueOf(double)} would produce differs from the value that Python compares.
	 *
	 * @param value a finite number or a boolean
	 * @return the exact value, where a float stands for the binary64 value that Python reads
	 */
	@SuppressWarnings("PMD.AvoidDecimalLiteralsInBigDecimalConstructor")
	private static BigDecimal exact(Object value)
	{
		if (Boolean.TRUE.equals(value))
			return BigDecimal.ONE;
		if (Boolean.FALSE.equals(value))
			return BigDecimal.ZERO;
		if (value instanceof BigInteger integer)
			return new BigDecimal(integer);
		if (isFloat(value))
			return new BigDecimal(((Number) value).doubleValue());
		return BigDecimal.valueOf(((Number) value).longValue());
	}

	/**
	 * Determines whether a value models a Python float.
	 *
	 * @param value a version value
	 * @return {@code true} if the value is a TOML float
	 */
	private static boolean isFloat(Object value)
	{
		return value instanceof Double || value instanceof Float || value instanceof BigDecimal;
	}

	/**
	 * Selects the single V8 crate version that the module pins by download location.
	 *
	 * @param module the module text
	 * @return the version
	 * @throws Rejection if the module pins no version or several versions
	 */
	private static String crateVersion(String module) throws Rejection
	{
		Set<String> matches = new TreeSet<>();
		Matcher urls = CRATE_URL.matcher(module);
		while (urls.find())
			matches.add(urls.group(1));
		if (matches.size() != 1)
		{
			throw new Rejection("expected exactly one pinned v8 crate version in MODULE.bazel, found: " +
				reprList(matches));
		}
		return matches.iterator().next();
	}

	/**
	 * Resolves the checksum manifest of a version.
	 *
	 * @param root    the real path of the repository root
	 * @param version the selected version
	 * @param module  the module location, reported if the version cannot form a path
	 * @return the manifest location
	 * @throws OperationalFailure if the version cannot form a path
	 */
	private static Path manifestPath(Path root, String version, Path module) throws OperationalFailure
	{
		try
		{
			return root.resolve(MANIFEST_DIRECTORY).resolve("rusty_v8_" + version.replace('.', '_') + ".sha256");
		}
		catch (InvalidPathException failure)
		{
			throw new OperationalFailure(module, "invalid checksum manifest path: " + failure.getMessage());
		}
	}

	/**
	 * Parses the checksum manifest.
	 *
	 * @param manifest the manifest location
	 * @return the digest of every artifact file name
	 * @throws OperationalFailure if the manifest exists but cannot be read
	 * @throws Rejection          if the manifest is missing, empty or malformed
	 */
	private static Map<String, String> parseManifest(Path manifest) throws OperationalFailure, Rejection
	{
		String text;
		try
		{
			text = readText(manifest);
		}
		catch (NoSuchFileException _)
		{
			throw new Rejection("missing checksum manifest: " + manifest);
		}
		catch (IOException failure)
		{
			throw new OperationalFailure(manifest, describe(failure));
		}
		Map<String, String> checksums = new LinkedHashMap<>();
		List<String> lines = splitLines(text);
		for (int index = 0; index < lines.size(); ++index)
			parseLine(manifest, index + 1, lines.get(index), checksums);
		if (checksums.isEmpty())
			throw new Rejection("empty checksum manifest: " + manifest);
		return checksums;
	}

	/**
	 * Splits text into lines at every boundary that Python's {@code str.splitlines} recognizes.
	 *
	 * @param text the text to split
	 * @return the lines without their terminators
	 */
	private static List<String> splitLines(String text)
	{
		List<String> lines = new ArrayList<>();
		int start = 0;
		for (int index = 0; index < text.length(); ++index)
		{
			if (isLineBoundary(text.charAt(index)))
			{
				lines.add(text.substring(start, index));
				start = index + 1;
			}
		}
		if (start < text.length())
			lines.add(text.substring(start));
		return lines;
	}

	/**
	 * Determines whether a character ends a line for Python's {@code str.splitlines}.
	 *
	 * @param character the character to test
	 * @return {@code true} if the character ends a line
	 */
	private static boolean isLineBoundary(char character)
	{
		return character == '\n' || character == '\u000b' || character == '\f' ||
			(character >= 0x1c && character <= 0x1e) || character == 0x85 || character == 0x2028 ||
			character == 0x2029;
	}

	/**
	 * Parses one manifest line.
	 *
	 * @param manifest  the manifest location
	 * @param number    the one-based line number
	 * @param line      the line without its terminator
	 * @param checksums the digests collected so far
	 * @throws Rejection if the line is malformed or repeats an artifact
	 */
	private static void parseLine(Path manifest, int number, String line, Map<String, String> checksums)
		throws Rejection
	{
		List<String> parts = splitOnWhitespace(line);
		if (parts.isEmpty())
			return;
		String location = manifest + ":" + number + ": ";
		if (parts.size() != 2)
			throw new Rejection(location + "expected '<sha256>  <filename>'");
		String checksum = parts.get(0);
		String filename = parts.get(1);
		if (!SHA256.matcher(checksum).matches())
			throw new Rejection(location + "invalid SHA-256 digest for " + filename);
		if (filename.equals(".") || filename.equals("..") || filename.indexOf('/') >= 0)
			throw new Rejection(location + "expected a bare artifact filename");
		if (checksums.containsKey(filename))
			throw new Rejection(location + "duplicate checksum for " + filename);
		checksums.put(filename, checksum);
	}

	/**
	 * Splits a line at runs of whitespace, the way Python's {@code str.split} does.
	 *
	 * @param line the line to split
	 * @return the non-empty tokens
	 */
	private static List<String> splitOnWhitespace(String line)
	{
		List<String> parts = new ArrayList<>();
		int start = -1;
		for (int index = 0; index < line.length(); ++index)
		{
			if (isSpace(line.charAt(index)))
			{
				if (start >= 0)
				{
					parts.add(line.substring(start, index));
					start = -1;
				}
			}
			else if (start < 0)
				start = index;
		}
		if (start >= 0)
			parts.add(line.substring(start));
		return parts;
	}

	/**
	 * Determines whether Python's {@code str.isspace} accepts a character.
	 *
	 * @param character the character to test
	 * @return {@code true} if the character is whitespace
	 */
	private static boolean isSpace(char character)
	{
		return (character >= 9 && character <= 13) || (character >= 0x1c && character <= 0x20) ||
			character == 0x85 || character == 0xa0 || character == 0x1680 ||
			(character >= 0x2000 && character <= 0x200a) || character == 0x2028 || character == 0x2029 ||
			character == 0x202f || character == 0x205f || character == 0x3000;
	}

	/**
	 * Compares the module entries of one version with the manifest.
	 *
	 * @param module    the module text
	 * @param checksums the manifest digests by artifact file name
	 * @param version   the selected version
	 * @throws Rejection if an entry is incomplete or the module and manifest differ
	 */
	private static void checkEntries(String module, Map<String, String> checksums, String version) throws Rejection
	{
		List<ModuleEntry> entries = moduleEntries(module, version);
		if (entries.isEmpty())
		{
			throw new Rejection(formatDrift(
				List.of("MODULE.bazel has no rusty_v8 http_file entries for " + version)));
		}
		List<String> errors = entrySetErrors(entries, checksums);
		errors.addAll(checksumErrors(entries, checksums));
		if (!errors.isEmpty())
			throw new Rejection(formatDrift(errors));
	}

	/**
	 * Formats drift errors in the wording of the reference tool.
	 *
	 * @param errors the errors, in report order
	 * @return the diagnostic text
	 */
	private static String formatDrift(List<String> errors)
	{
		return "rusty_v8 MODULE.bazel checksum drift:\n- " + String.join("\n- ", errors);
	}

	/**
	 * Extracts the {@code http_file} entries of one version.
	 *
	 * @param module  the module text
	 * @param version the selected version
	 * @return the entries, in module order
	 * @throws Rejection if an entry has no downloaded file path
	 */
	private static List<ModuleEntry> moduleEntries(String module, String version) throws Rejection
	{
		String prefix = "rusty_v8_" + version.replace('.', '_') + "_";
		List<ModuleEntry> entries = new ArrayList<>();
		Matcher blocks = HTTP_FILE_BLOCK.matcher(module);
		while (blocks.find())
		{
			String block = blocks.group();
			String name = field(NAME_FIELD, block);
			if (name != null && name.startsWith(prefix))
			{
				String path = field(PATH_FIELD, block);
				if (path == null)
					throw new Rejection("MODULE.bazel " + name + " is missing downloaded_file_path");
				entries.add(new ModuleEntry(name, path, field(DIGEST_FIELD, block)));
			}
		}
		return entries;
	}

	/**
	 * Returns the first value of a one-line string field of a block.
	 *
	 * @param pattern the pattern of the field
	 * @param block   the block text
	 * @return the field value, or {@code null} if the block has no such field
	 */
	private static String field(Pattern pattern, String block)
	{
		Matcher matcher = pattern.matcher(block);
		if (matcher.find())
			return matcher.group(1);
		return null;
	}

	/**
	 * Builds the pattern of a one-line string field of an {@code http_file} block.
	 *
	 * @param field the field name
	 * @return the pattern, whose first group is the field value
	 */
	private static Pattern fieldPattern(String field)
	{
		return Pattern.compile("^" + SPACE + "*" + field + SPACE + "*=" + SPACE + "*\"([^\"]+)\"," + SPACE + "*$",
			Pattern.MULTILINE | Pattern.UNIX_LINES);
	}

	/**
	 * Reports duplicate entries and entries that only the module or only the manifest names.
	 *
	 * @param entries   the module entries
	 * @param checksums the manifest digests by artifact file name
	 * @return the errors, in report order
	 */
	private static List<String> entrySetErrors(List<ModuleEntry> entries, Map<String, String> checksums)
	{
		Map<String, ModuleEntry> moduleFiles = new LinkedHashMap<>();
		Set<String> duplicates = new HashSet<>();
		for (ModuleEntry entry : entries)
		{
			if (moduleFiles.put(entry.path(), entry) != null)
				duplicates.add(entry.path());
		}
		List<String> errors = new ArrayList<>();
		for (String filename : sorted(duplicates))
			errors.add("MODULE.bazel has duplicate http_file entries for " + filename);
		Set<String> moduleOnly = new HashSet<>(moduleFiles.keySet());
		moduleOnly.removeAll(checksums.keySet());
		for (String filename : sorted(moduleOnly))
			errors.add("MODULE.bazel " + moduleFiles.get(filename).name() + " has no checksum in the manifest");
		Set<String> manifestOnly = new HashSet<>(checksums.keySet());
		manifestOnly.removeAll(moduleFiles.keySet());
		for (String filename : sorted(manifestOnly))
			errors.add("manifest has " + filename + ", but MODULE.bazel has no http_file");
		return errors;
	}

	/**
	 * Sorts strings by code point.
	 *
	 * @param values the strings to sort
	 * @return a sorted copy
	 */
	private static List<String> sorted(Collection<String> values)
	{
		List<String> result = new ArrayList<>(values);
		result.sort(VerifyV8ModuleChecksums::compareCodePoints);
		return result;
	}

	/**
	 * Reports entries whose digest is missing or differs from the manifest.
	 *
	 * @param entries   the module entries
	 * @param checksums the manifest digests by artifact file name
	 * @return the errors, in module order
	 */
	private static List<String> checksumErrors(List<ModuleEntry> entries, Map<String, String> checksums)
	{
		List<String> errors = new ArrayList<>();
		for (ModuleEntry entry : entries)
		{
			String expected = checksums.get(entry.path());
			if (expected != null)
			{
				if (entry.sha256() == null)
					errors.add("MODULE.bazel " + entry.name() + " is missing sha256");
				else if (!entry.sha256().equals(expected))
				{
					errors.add("MODULE.bazel " + entry.name() + " has sha256 " + entry.sha256() + ", expected " +
						expected);
				}
			}
		}
		return errors;
	}

	/**
	 * Formats values as a Python list.
	 *
	 * @param values strings, booleans, numbers or temporal values
	 * @return the {@code repr} of the list
	 */
	private static String reprList(Collection<?> values)
	{
		List<String> parts = new ArrayList<>();
		for (Object value : values)
			parts.add(reprValue(value));
		return "[" + String.join(", ", parts) + "]";
	}

	/**
	 * Formats a value the way Python's {@code repr} does.
	 *
	 * @param value a string, boolean, number or temporal value
	 * @return the {@code repr} of the value
	 */
	private static String reprValue(Object value)
	{
		if (value instanceof String text)
			return reprString(text);
		if (Boolean.TRUE.equals(value))
			return "True";
		if (Boolean.FALSE.equals(value))
			return "False";
		if (isFloat(value))
			return reprFloat(((Number) value).doubleValue());
		if (value instanceof Number)
			return value.toString();
		if (value instanceof LocalDate date)
			return "datetime.date(" + dateFields(date) + ")";
		if (value instanceof LocalTime time)
			return "datetime.time(" + clockFields(time) + ")";
		if (value instanceof LocalDateTime stamp)
			return "datetime.datetime(" + dateFields(stamp.toLocalDate()) + ", " + clockFields(stamp.toLocalTime()) + ")";
		if (value instanceof OffsetDateTime stamp)
		{
			return "datetime.datetime(" + dateFields(stamp.toLocalDate()) + ", " + clockFields(stamp.toLocalTime()) +
				", tzinfo=" + zoneRepr(stamp.getOffset().getTotalSeconds()) + ")";
		}
		throw new IllegalStateException("Unsupported version value");
	}

	/**
	 * Formats the arguments of a Python {@code datetime.date}.
	 *
	 * @param date the date
	 * @return the year, month and day
	 */
	private static String dateFields(LocalDate date)
	{
		return date.getYear() + ", " + date.getMonthValue() + ", " + date.getDayOfMonth();
	}

	/**
	 * Formats the arguments of a Python {@code datetime.time}, omitting trailing zero seconds and microseconds.
	 *
	 * @param time the time of day
	 * @return the hour and minute, followed by the seconds and microseconds that Python shows
	 */
	private static String clockFields(LocalTime time)
	{
		int microseconds = time.getNano() / 1_000;
		String fields = time.getHour() + ", " + time.getMinute();
		if (microseconds != 0)
			return fields + ", " + time.getSecond() + ", " + microseconds;
		if (time.getSecond() != 0)
			return fields + ", " + time.getSecond();
		return fields;
	}

	/**
	 * Formats a fixed offset as a Python {@code datetime.timezone}.
	 *
	 * @param seconds the offset from UTC, in seconds
	 * @return the {@code repr} of the time zone
	 */
	private static String zoneRepr(int seconds)
	{
		if (seconds == 0)
			return "datetime.timezone.utc";
		int days = Math.floorDiv(seconds, 86_400);
		int remainder = Math.floorMod(seconds, 86_400);
		String delta;
		if (days == 0)
			delta = "seconds=" + remainder;
		else if (remainder == 0)
			delta = "days=" + days;
		else
			delta = "days=" + days + ", seconds=" + remainder;
		return "datetime.timezone(datetime.timedelta(" + delta + "))";
	}

	/**
	 * Formats a double the way Python's {@code repr} does.
	 *
	 * @param number the value
	 * @return the shortest representation that reads back as the same value
	 */
	private static String reprFloat(double number)
	{
		if (Double.isNaN(number))
			return "nan";
		if (number == Double.POSITIVE_INFINITY)
			return "inf";
		if (number == Double.NEGATIVE_INFINITY)
			return "-inf";
		String sign = "";
		if (Double.doubleToRawLongBits(number) < 0)
			sign = "-";
		if (number == 0)
			return sign + "0.0";
		BigDecimal shortest = new BigDecimal(Double.toString(Math.abs(number))).stripTrailingZeros();
		String digits = shortest.unscaledValue().toString();
		int decimalPoint = digits.length() - shortest.scale();
		if (decimalPoint <= -4 || decimalPoint > 16)
			return sign + scientific(digits, decimalPoint - 1);
		return sign + fixed(digits, decimalPoint);
	}

	/**
	 * Formats shortest digits in Python's exponent notation.
	 *
	 * @param digits   the significant digits
	 * @param exponent the decimal exponent of the first digit
	 * @return the digits with an exponent of at least two digits
	 */
	private static String scientific(String digits, int exponent)
	{
		String fraction = "";
		if (digits.length() > 1)
			fraction = "." + digits.substring(1);
		String sign = "+";
		int magnitude = exponent;
		if (exponent < 0)
		{
			sign = "-";
			magnitude = -exponent;
		}
		String padding = "";
		if (magnitude < 10)
			padding = "0";
		return digits.substring(0, 1) + fraction + "e" + sign + padding + magnitude;
	}

	/**
	 * Formats shortest digits in Python's positional notation.
	 *
	 * @param digits       the significant digits
	 * @param decimalPoint the position of the decimal point relative to the first digit
	 * @return the digits with a decimal point and at least one fractional digit
	 */
	private static String fixed(String digits, int decimalPoint)
	{
		if (decimalPoint <= 0)
			return "0." + "0".repeat(-decimalPoint) + digits;
		if (decimalPoint >= digits.length())
			return digits + "0".repeat(decimalPoint - digits.length()) + ".0";
		return digits.substring(0, decimalPoint) + "." + digits.substring(decimalPoint);
	}

	/**
	 * Formats a string the way Python's {@code repr} does.
	 *
	 * @param text the string
	 * @return the quoted and escaped string
	 */
	private static String reprString(String text)
	{
		char quote = '\'';
		if (text.indexOf('\'') >= 0 && text.indexOf('"') < 0)
			quote = '"';
		StringBuilder repr = new StringBuilder().append(quote);
		for (int index = 0; index < text.length();)
		{
			int codePoint = text.codePointAt(index);
			index += Character.charCount(codePoint);
			appendRepr(repr, codePoint, quote);
		}
		return repr.append(quote).toString();
	}

	/**
	 * Appends one code point of a string, escaped as Python's {@code repr} does.
	 *
	 * @param repr      the text under construction
	 * @param codePoint the code point to append
	 * @param quote     the quote character that delimits the string
	 */
	private static void appendRepr(StringBuilder repr, int codePoint, char quote)
	{
		if (codePoint == quote || codePoint == '\\')
			repr.append('\\').appendCodePoint(codePoint);
		else if (codePoint == '\n')
			repr.append("\\n");
		else if (codePoint == '\r')
			repr.append("\\r");
		else if (codePoint == '\t')
			repr.append("\\t");
		else if (isPythonPrintable(codePoint))
			repr.appendCodePoint(codePoint);
		else if (codePoint <= 0xff)
			repr.append(String.format("\\x%02x", codePoint));
		else if (codePoint <= 0xffff)
			repr.append(String.format("\\u%04x", codePoint));
		else
			repr.append(String.format("\\U%08x", codePoint));
	}

	/**
	 * Returns whether Python's {@code str.isprintable()} accepts a code point.
	 *
	 * @param codePoint the Unicode code point to test
	 * @return {@code true} if the code point is printable
	 */
	private static boolean isPythonPrintable(int codePoint)
	{
		int type = Character.getType(codePoint);
		return type != Character.UNASSIGNED && type != Character.CONTROL && type != Character.FORMAT &&
			type != Character.SURROGATE && type != Character.PRIVATE_USE && type != Character.LINE_SEPARATOR &&
			type != Character.PARAGRAPH_SEPARATOR && (type != Character.SPACE_SEPARATOR || codePoint == ' ');
	}

	/**
	 * The Python types that a lock file version can have, grouped by comparability.
	 */
	private enum Kind
	{
		/**
		 * Booleans, integers and floats, which Python compares with each other.
		 */
		NUMBER,
		/**
		 * Strings.
		 */
		STRING,
		/**
		 * Dates.
		 */
		DATE,
		/**
		 * Times of day.
		 */
		TIME,
		/**
		 * Local date-times.
		 */
		LOCAL_DATETIME,
		/**
		 * Offset date-times.
		 */
		OFFSET_DATETIME,
		/**
		 * Arrays, tables and anything else that Python cannot hash.
		 */
		UNSUPPORTED
	}

	/**
	 * One {@code http_file} entry of the module.
	 *
	 * @param name   the repository name
	 * @param path   the downloaded file name
	 * @param sha256 the declared digest, or {@code null} if the entry declares none
	 */
	private record ModuleEntry(String name, String path, String sha256)
	{
	}

	/**
	 * An input that cannot be read or interpreted, as opposed to one that violates the checksum policy.
	 */
	private static final class OperationalFailure extends Exception
	{
		/**
		 * The serialization version.
		 */
		private static final long serialVersionUID = 1L;
		/**
		 * The location of the offending input.
		 */
		private final String location;

		/**
		 * Creates a failure.
		 *
		 * @param path    the offending input
		 * @param message the reason
		 */
		OperationalFailure(Path path, String message)
		{
			super(message);
			this.location = path.toString();
		}

		/**
		 * Returns the location of the offending input.
		 *
		 * @return the location
		 */
		String location()
		{
			return location;
		}
	}

	/**
	 * An input that violates the checksum policy.
	 */
	private static final class Rejection extends Exception
	{
		/**
		 * The serialization version.
		 */
		private static final long serialVersionUID = 1L;

		/**
		 * Creates a rejection.
		 *
		 * @param message the diagnostic text
		 */
		Rejection(String message)
		{
			super(message);
		}
	}
}
