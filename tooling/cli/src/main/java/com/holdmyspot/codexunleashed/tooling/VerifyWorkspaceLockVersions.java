package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.CharacterCodingException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.dataformat.toml.TomlMapper;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Verifies that the lockfile of a checkout differs from the lockfile of its HEAD commit only in the versions of the
 * workspace packages, without modifying the checkout or its repository.
 * <p>
 * The baseline is {@code codex-rs/Cargo.lock} as Git records it at HEAD and the candidate is the working-tree file of
 * the same name. The candidate is accepted when its non-package entries equal the baseline's, its packages that name a
 * {@code source} equal the baseline's in order, and its remaining packages equal the baseline's with only their
 * {@code version} set to the {@code workspace.package.version} of {@code codex-rs/Cargo.toml}. Values are compared
 * with the equality of the Python {@code tomllib} comparison that this command replaces: integers, floats and booleans
 * compare by exact numeric value, a NaN equals nothing, a date, time or date-time equals only the same kind of value
 * truncated to microseconds, and an offset date-time compares by instant. The package list is read with the same
 * iteration, membership and {@code dict} conversion rules as that comparison, so that input it accepted or failed on
 * is decided the same way.
 */
public final class VerifyWorkspaceLockVersions
{
	/**
	 * The command name.
	 */
	public static final String NAME = "verify-workspace-lock-versions";

	/**
	 * Names the arguments that the command accepts.
	 */
	private static final String USAGE = "Usage: codex-tooling " + NAME + " <repository-root>";
	/**
	 * Names the lockfile relative to the checkout, as Git addresses it.
	 */
	private static final String LOCK_PATH = "codex-rs/Cargo.lock";
	/**
	 * Names the workspace manifest relative to the checkout.
	 */
	private static final String MANIFEST_PATH = "codex-rs/Cargo.toml";
	/**
	 * Names the baseline lockfile in diagnostics, as Git addresses it at HEAD.
	 */
	private static final String BASELINE_LABEL = "HEAD:" + LOCK_PATH;
	/**
	 * Names the lockfile entry that lists the packages.
	 */
	private static final String PACKAGE_KEY = "package";
	/**
	 * Names the package entry that marks a package as coming from a registry or Git origin.
	 */
	private static final String SOURCE_KEY = "source";
	/**
	 * Names the package entry that holds the package version.
	 */
	private static final String VERSION_KEY = "version";

	/**
	 * Prevents construction.
	 */
	private VerifyWorkspaceLockVersions()
	{
	}

	/**
	 * Verifies the lockfile of one checkout without closing the caller's streams.
	 *
	 * @param scope       shared tooling values borrowed until the command finishes
	 * @param arguments   the repository root, the only argument after the command name
	 * @param environment child environment overrides for Git
	 * @param out         receives the success line
	 * @param err         receives the usage text or the one diagnostic
	 * @return zero when only workspace versions differ, one when the lockfile differs in another way or an input
	 *         cannot be used, or two for invalid command arguments
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(ToolingScope scope, String[] arguments, Map<String, String> environment, PrintStream out,
		PrintStream err)
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(environment, "environment");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");

		if (arguments.length != 1 || arguments[0].equals("-h") || arguments[0].equals("--help"))
		{
			err.println(USAGE);
			return 2;
		}

		try
		{
			int workspaceCount = verifyRepository(scope, SystemCommands::capture,
				Path.of(System.getProperty("java.io.tmpdir")), environment, Path.of(arguments[0]));
			out.println("Verified " + workspaceCount + " workspace versions; external dependencies unchanged");
			return 0;
		}
		catch (DifferenceException difference)
		{
			err.println(difference.getDifference().getDiagnostic());
			return 1;
		}
		catch (IOException | InvalidPathException failure)
		{
			err.println(NAME + ": " + failure.getMessage());
			return 1;
		}
	}

	/**
	 * Reads the baseline lockfile from Git, the candidate lockfile and the workspace manifest, and compares them.
	 * Every input is read before any comparison, and none is written.
	 *
	 * @param scope              shared tooling values borrowed for the duration of the call
	 * @param capture            runs Git
	 * @param temporaryDirectory existing parent directory for the output captured from Git
	 * @param environment        child environment overrides for Git
	 * @param repository         checkout whose HEAD, {@code codex-rs/Cargo.lock} and {@code codex-rs/Cargo.toml} are
	 *                           read
	 * @return the number of workspace packages in the candidate lockfile
	 * @throws NullPointerException if any argument is null
	 * @throws DifferenceException  if the candidate differs from the baseline by more than workspace versions
	 * @throws IOException          if Git cannot read the baseline, a file cannot be read, a document cannot be
	 *                              parsed, or the packages cannot be traversed as the replaced comparison required
	 */
	public static int verifyRepository(ToolingScope scope, SystemCommands.Capture capture, Path temporaryDirectory,
		Map<String, String> environment, Path repository) throws DifferenceException, IOException
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(capture, "capture");
		Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
		Objects.requireNonNull(environment, "environment");
		Objects.requireNonNull(repository, "repository");

		String beforeLock = readBaseline(capture, temporaryDirectory, environment, repository);
		String afterLock = readText(repository.resolve(LOCK_PATH));
		String workspaceManifest = readText(repository.resolve(MANIFEST_PATH));
		return verifyDocuments(scope, beforeLock, afterLock, workspaceManifest);
	}

	/**
	 * Compares the baseline lockfile, the candidate lockfile and the workspace manifest. The differences are checked
	 * in a fixed order, and the manifest is parsed only when the comparison reaches it, so that the first applicable
	 * outcome is the one the replaced comparison reported.
	 *
	 * @param scope             shared tooling values borrowed for the duration of the call
	 * @param beforeLock        text of the baseline lockfile
	 * @param afterLock         text of the candidate lockfile
	 * @param workspaceManifest text of the workspace manifest
	 * @return the number of workspace packages in the candidate lockfile
	 * @throws NullPointerException if any argument is null
	 * @throws DifferenceException  if the candidate differs from the baseline by more than workspace versions
	 * @throws IOException          if a document cannot be parsed, or the packages or the manifest cannot be traversed
	 *                              as the replaced comparison required
	 */
	public static int verifyDocuments(ToolingScope scope, String beforeLock, String afterLock,
		String workspaceManifest) throws DifferenceException, IOException
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(beforeLock, "beforeLock");
		Objects.requireNonNull(afterLock, "afterLock");
		Objects.requireNonNull(workspaceManifest, "workspaceManifest");

		TomlMapper mapper = scope.getTomlMapper();
		Map<Object, Object> before = parseDocument(mapper, beforeLock, BASELINE_LABEL);
		Map<Object, Object> after = parseDocument(mapper, afterLock, LOCK_PATH);

		if (!equalValues(withoutPackages(before), withoutPackages(after)))
			throw new DifferenceException(Difference.METADATA);

		Partition beforePartition = partition(before, BASELINE_LABEL);
		Partition afterPartition = partition(after, LOCK_PATH);
		if (!equalValues(beforePartition.external(), afterPartition.external()))
			throw new DifferenceException(Difference.EXTERNAL);

		Object version = findWorkspaceVersion(parseDocument(mapper, workspaceManifest, MANIFEST_PATH));
		List<Object> expected = new ArrayList<>();
		for (Object record : beforePartition.workspace())
			expected.add(withVersion(record, version));
		if (!equalValues(expected, afterPartition.workspace()))
			throw new DifferenceException(Difference.WORKSPACE);
		return afterPartition.workspace().size();
	}

	/**
	 * Reads the baseline lockfile from the HEAD commit of a repository.
	 *
	 * @param capture            runs Git
	 * @param temporaryDirectory existing parent directory for the output captured from Git
	 * @param environment        child environment overrides for Git
	 * @param repository         the working directory of Git
	 * @return the text of the lockfile at HEAD
	 * @throws IOException if Git cannot be run, cannot clean up its captured output, or exits with a nonzero status
	 */
	private static String readBaseline(SystemCommands.Capture capture, Path temporaryDirectory,
		Map<String, String> environment, Path repository) throws IOException
	{
		List<String> command = List.of("git", "show", BASELINE_LABEL);
		SystemCommands.Result result;
		try
		{
			result = capture.capture(command, repository, temporaryDirectory, environment);
		}
		catch (IOException failure)
		{
			throw new IOException("git show " + BASELINE_LABEL + " failed: " + describe(failure), failure);
		}
		if (result.status() != 0)
		{
			throw new IOException("git show " + BASELINE_LABEL + " exited with status " + result.status() + ": " +
				result.stderr().strip());
		}
		return result.stdout();
	}

	/**
	 * Reads one input file as UTF-8.
	 *
	 * @param path the file to read
	 * @return its text
	 * @throws IOException if the file is missing, unreadable or not valid UTF-8
	 */
	private static String readText(Path path) throws IOException
	{
		try
		{
			return Files.readString(path, UTF_8);
		}
		catch (IOException failure)
		{
			throw new IOException("Cannot read " + path + ": " + describe(failure), failure);
		}
	}

	/**
	 * Describes a failure without a stack trace or a class name unless nothing else is known.
	 *
	 * @param failure the failure to describe
	 * @return the reason of the failure
	 */
	private static String describe(IOException failure)
	{
		if (failure instanceof NoSuchFileException)
			return "No such file or directory";
		if (failure instanceof FileSystemException fileFailure && fileFailure.getReason() != null)
			return fileFailure.getReason();
		String message = failure.getMessage();
		if (message == null)
			return failure.getClass().getSimpleName();
		if (failure instanceof CharacterCodingException)
			return "Not valid UTF-8: " + message;
		return message;
	}

	/**
	 * Parses a TOML document into the values that the comparison is defined over. The parser's temporal values keep
	 * their kind, and its numbers become exact integers or binary64 values.
	 *
	 * @param mapper the TOML parser
	 * @param text   the document
	 * @param label  names the document in diagnostics
	 * @return the root table, which is empty for a document without entries
	 * @throws IOException if the document is not valid TOML, or holds a value that the comparison does not define
	 */
	private static Map<Object, Object> parseDocument(TomlMapper mapper, String text, String label)
		throws IOException
	{
		Object parsed;
		try
		{
			parsed = mapper.readValue(text, Object.class);
		}
		catch (JacksonException failure)
		{
			throw new IOException("Cannot parse " + label + ": " + failure.getOriginalMessage(), failure);
		}
		if (parsed == null)
			return new LinkedHashMap<>();
		if (parsed instanceof Map<?, ?> table)
			return normalizeTable(table, label);
		throw new IOException("Cannot parse " + label + ": the root value is " + parsed.getClass().getSimpleName() +
			" instead of a table");
	}

	/**
	 * Copies a parsed table, preserving its entry order.
	 *
	 * @param table the parsed table
	 * @param label names the document in diagnostics
	 * @return the table with normalized values
	 * @throws IOException if a key is not a string or a value is of a type that the comparison does not define
	 */
	private static Map<Object, Object> normalizeTable(Map<?, ?> table, String label) throws IOException
	{
		Map<Object, Object> result = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : table.entrySet())
		{
			if (!(entry.getKey() instanceof String key))
				throw new IOException("Cannot parse " + label + ": a table key is not a string");
			result.put(key, normalizeValue(entry.getValue(), label));
		}
		return result;
	}

	/**
	 * Converts a parsed value into the value that the comparison uses.
	 *
	 * @param value the parsed value
	 * @param label names the document in diagnostics
	 * @return a table or list copy, a {@code BigInteger}, a {@code Double}, a string, a boolean, or a date, time or
	 *         date-time truncated to microseconds
	 * @throws IOException if the value is of a type that the comparison does not define
	 */
	private static Object normalizeValue(Object value, String label) throws IOException
	{
		if (value instanceof Map<?, ?> table)
			return normalizeTable(table, label);
		if (value instanceof List<?> list)
		{
			List<Object> result = new ArrayList<>();
			for (Object member : list)
				result.add(normalizeValue(member, label));
			return result;
		}
		if (value instanceof BigInteger || value instanceof Double || value instanceof String ||
			value instanceof Boolean || value instanceof LocalDate)
		{
			return value;
		}
		if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte)
			return BigInteger.valueOf(((Number) value).longValue());
		if (value instanceof BigDecimal decimal)
			return decimal.doubleValue();
		if (value instanceof Float real)
			return real.doubleValue();
		if (value instanceof LocalTime time)
			return time.truncatedTo(ChronoUnit.MICROS);
		if (value instanceof LocalDateTime dateTime)
			return dateTime.truncatedTo(ChronoUnit.MICROS);
		if (value instanceof OffsetDateTime dateTime)
			return dateTime.truncatedTo(ChronoUnit.MICROS);
		String type = "null";
		if (value != null)
			type = value.getClass().getName();
		throw new IOException("Cannot parse " + label + ": the parser returned an unsupported value of type " + type);
	}

	/**
	 * Returns the entries of a lockfile other than its package list.
	 *
	 * @param lock the root table of a lockfile
	 * @return a copy of the table without its {@code package} entry
	 */
	private static Map<Object, Object> withoutPackages(Map<Object, Object> lock)
	{
		Map<Object, Object> result = new LinkedHashMap<>(lock);
		result.remove(PACKAGE_KEY);
		return result;
	}

	/**
	 * Splits the packages of a lockfile by whether they name a source.
	 *
	 * @param lock  the root table of a lockfile
	 * @param label names the lockfile in diagnostics
	 * @return the packages with a source and the packages without one, each in the order the package list iterates
	 * @throws IOException if the lockfile has no package entry, or its package entry cannot be iterated or tested for a
	 *                     source
	 */
	private static Partition partition(Map<Object, Object> lock, String label) throws IOException
	{
		if (!lock.containsKey(PACKAGE_KEY))
			throw new IOException(label + " has no " + PACKAGE_KEY + " entry");
		List<Object> external = new ArrayList<>();
		List<Object> workspace = new ArrayList<>();
		for (Object item : iterate(lock.get(PACKAGE_KEY), label))
		{
			if (hasSource(item, label))
				external.add(item);
			else
				workspace.add(item);
		}
		return new Partition(external, workspace);
	}

	/**
	 * Lists what a Python {@code for} statement visits in a value: the members of a list, the keys of a table, or the
	 * code points of a string.
	 *
	 * @param value the package entry of a lockfile
	 * @param label names the lockfile in diagnostics
	 * @return the visited values in order
	 * @throws IOException if the value cannot be iterated
	 */
	private static List<Object> iterate(Object value, String label) throws IOException
	{
		List<Object> result = new ArrayList<>();
		if (value instanceof List<?> list)
			result.addAll(list);
		else if (value instanceof Map<?, ?> table)
			result.addAll(table.keySet());
		else if (value instanceof String text)
		{
			for (int codePoint : text.codePoints().toArray())
				result.add(Character.toString(codePoint));
		}
		else
			throw new IOException(label + " has a " + PACKAGE_KEY + " entry that cannot be iterated: " + kind(value));
		return result;
	}

	/**
	 * Evaluates the Python membership test {@code "source" in item}.
	 *
	 * @param item  a visited package entry
	 * @param label names the lockfile in diagnostics
	 * @return {@code true} if a table has the key, a string contains the text, or a list has a member equal to the
	 *         text
	 * @throws IOException if the value does not support membership tests
	 */
	private static boolean hasSource(Object item, String label) throws IOException
	{
		if (item instanceof Map<?, ?> table)
			return table.containsKey(SOURCE_KEY);
		if (item instanceof String text)
			return text.contains(SOURCE_KEY);
		if (item instanceof List<?> list)
		{
			for (Object member : list)
			{
				if (equalValues(SOURCE_KEY, member))
					return true;
			}
			return false;
		}
		throw new IOException(label + " has a " + PACKAGE_KEY + " item that cannot be tested for a " + SOURCE_KEY + ": " +
			kind(item));
	}

	/**
	 * Finds the workspace package version of the manifest.
	 *
	 * @param manifest the root table of the manifest
	 * @return the value of {@code workspace.package.version}, which may be of any type
	 * @throws IOException if {@code workspace}, {@code package} or {@code version} is missing, or an enclosing value
	 *                     is not a table
	 */
	private static Object findWorkspaceVersion(Map<Object, Object> manifest) throws IOException
	{
		Object current = manifest;
		for (String key : List.of("workspace", PACKAGE_KEY, VERSION_KEY))
		{
			if (!(current instanceof Map<?, ?> table) || !table.containsKey(key))
				throw new IOException(MANIFEST_PATH + " does not define workspace.package.version");
			current = table.get(key);
		}
		return current;
	}

	/**
	 * Evaluates the Python expression {@code dict(item, version=version)}.
	 *
	 * @param item    a baseline package without a source
	 * @param version the workspace version
	 * @return a table that copies a table, or is built from the two-element entries of a list, and then has its
	 *         {@code version} set. A table with a key that is not a string equals no parsed package.
	 * @throws IOException if the value cannot be converted to a table, or a key is not hashable
	 */
	private static Map<Object, Object> withVersion(Object item, Object version) throws IOException
	{
		Map<Object, Object> result = new LinkedHashMap<>();
		if (item instanceof Map<?, ?> table)
			result.putAll(table);
		else if (item instanceof List<?> list)
		{
			for (Object member : list)
				putEntry(result, member);
		}
		else if (!(item instanceof String text) || !text.isEmpty())
			throw new IOException("A baseline package without a source cannot be converted to a table: " + kind(item));
		result.put(VERSION_KEY, version);
		return result;
	}

	/**
	 * Adds one element of a Python {@code dict} constructor argument, which must supply exactly two values.
	 *
	 * @param result the table under construction
	 * @param member a list member that holds a key and a value
	 * @throws IOException if the member does not supply exactly two values, or its key is a list or a table
	 */
	private static void putEntry(Map<Object, Object> result, Object member) throws IOException
	{
		List<Object> pair = new ArrayList<>();
		if (member instanceof List<?> list)
			pair.addAll(list);
		else if (member instanceof Map<?, ?> table)
			pair.addAll(table.keySet());
		else if (member instanceof String text)
		{
			for (int codePoint : text.codePoints().toArray())
				pair.add(Character.toString(codePoint));
		}
		else
			throw new IOException("A baseline package list member is not a key-value pair: " + kind(member));
		if (pair.size() != 2)
		{
			throw new IOException("A baseline package list member is not a key-value pair: it supplies " + pair.size() +
				" values instead of 2");
		}
		Object key = pair.get(0);
		if (key instanceof List || key instanceof Map)
			throw new IOException("A baseline package list member has an unhashable key: " + kind(key));
		result.put(key, pair.get(1));
	}

	/**
	 * Names the type of a parsed value.
	 *
	 * @param value a normalized value
	 * @return a short type name
	 */
	private static String kind(Object value)
	{
		return value.getClass().getSimpleName();
	}

	/**
	 * Compares two values as Python {@code ==} compares the values that {@code tomllib} produces.
	 *
	 * @param left  the first normalized value
	 * @param right the second normalized value
	 * @return {@code true} if the values are equal
	 */
	private static boolean equalValues(Object left, Object right)
	{
		if (left instanceof Map<?, ?> leftTable)
			return right instanceof Map<?, ?> rightTable && equalTables(leftTable, rightTable);
		if (left instanceof List<?> leftList)
			return right instanceof List<?> rightList && equalLists(leftList, rightList);
		if (isNumber(left))
			return isNumber(right) && equalNumbers(left, right);
		if (left instanceof OffsetDateTime leftInstant)
			return right instanceof OffsetDateTime rightInstant && leftInstant.isEqual(rightInstant);
		return left.getClass() == right.getClass() && left.equals(right);
	}

	/**
	 * Compares two tables by key set and value, ignoring entry order.
	 *
	 * @param left  the first table
	 * @param right the second table
	 * @return {@code true} if both hold the same keys with equal values
	 */
	private static boolean equalTables(Map<?, ?> left, Map<?, ?> right)
	{
		if (left.size() != right.size())
			return false;
		for (Map.Entry<?, ?> entry : left.entrySet())
		{
			if (!right.containsKey(entry.getKey()) || !equalValues(entry.getValue(), right.get(entry.getKey())))
				return false;
		}
		return true;
	}

	/**
	 * Compares two lists by length and by member in order.
	 *
	 * @param left  the first list
	 * @param right the second list
	 * @return {@code true} if both hold equal members in the same order
	 */
	private static boolean equalLists(List<?> left, List<?> right)
	{
		if (left.size() != right.size())
			return false;
		for (int index = 0; index < left.size(); ++index)
		{
			if (!equalValues(left.get(index), right.get(index)))
				return false;
		}
		return true;
	}

	/**
	 * Determines whether Python treats a value as a number when comparing it.
	 *
	 * @param value a normalized value
	 * @return {@code true} for a boolean, an integer or a float
	 */
	private static boolean isNumber(Object value)
	{
		return value instanceof Boolean || value instanceof BigInteger || value instanceof Double;
	}

	/**
	 * Compares two numbers by exact value, without rounding an integer to a float.
	 *
	 * @param left  a boolean, integer or float
	 * @param right a boolean, integer or float
	 * @return {@code true} if both are finite and numerically equal, or both are the same infinity
	 */
	private static boolean equalNumbers(Object left, Object right)
	{
		if (isFinite(left) && isFinite(right))
			return exactValue(left).compareTo(exactValue(right)) == 0;
		return left instanceof Double leftReal && Double.isInfinite(leftReal) && leftReal.equals(right);
	}

	/**
	 * Determines whether a number has an exact decimal value.
	 *
	 * @param number a boolean, integer or float
	 * @return {@code false} for a NaN or an infinity
	 */
	private static boolean isFinite(Object number)
	{
		return !(number instanceof Double real) || Double.isFinite(real);
	}

	/**
	 * Returns the exact value of a finite number.
	 *
	 * @param number a boolean, integer or finite float
	 * @return a boolean as zero or one, an integer as itself, and a float as the exact value of its bits
	 */
	@SuppressWarnings("PMD.AvoidDecimalLiteralsInBigDecimalConstructor")
	private static BigDecimal exactValue(Object number)
	{
		if (number instanceof Boolean flag)
		{
			if (flag)
				return BigDecimal.ONE;
			return BigDecimal.ZERO;
		}
		if (number instanceof BigInteger integer)
			return new BigDecimal(integer);
		return new BigDecimal(((Double) number).doubleValue());
	}

	/**
	 * The packages of one lockfile, split by whether they name a source.
	 *
	 * @param external  the packages that name a source
	 * @param workspace the packages that do not
	 */
	private record Partition(List<Object> external, List<Object> workspace)
	{
	}

	/**
	 * The ways in which the candidate lockfile can differ from the baseline by more than workspace versions.
	 */
	public enum Difference
	{
		/**
		 * An entry other than the package list differs.
		 */
		METADATA("Cargo changed lockfile metadata beyond workspace versions"),
		/**
		 * The packages that name a source differ.
		 */
		EXTERNAL("Cargo changed external dependency records"),
		/**
		 * A package without a source differs in more than its version.
		 */
		WORKSPACE("Cargo changed workspace records beyond their release version");

		/**
		 * The diagnostic that the command prints for the difference.
		 */
		private final String diagnostic;

		/**
		 * Creates a difference.
		 *
		 * @param diagnostic the diagnostic that the command prints
		 */
		Difference(String diagnostic)
		{
			this.diagnostic = diagnostic;
		}

		/**
		 * Returns the diagnostic that the command prints for the difference.
		 *
		 * @return the diagnostic, without a line terminator
		 */
		public String getDiagnostic()
		{
			return diagnostic;
		}
	}

	/**
	 * Signals that the candidate lockfile differs from the baseline by more than workspace versions, as opposed to an
	 * input that cannot be read or interpreted.
	 */
	public static final class DifferenceException extends Exception
	{
		/**
		 * The serialization version.
		 */
		private static final long serialVersionUID = 1L;
		/**
		 * The kind of difference.
		 */
		private final Difference difference;

		/**
		 * Creates an exception.
		 *
		 * @param difference the kind of difference
		 */
		private DifferenceException(Difference difference)
		{
			super(difference.getDiagnostic());
			this.difference = difference;
		}

		/**
		 * Returns the kind of difference.
		 *
		 * @return the kind
		 */
		public Difference getDifference()
		{
			return difference;
		}
	}
}
