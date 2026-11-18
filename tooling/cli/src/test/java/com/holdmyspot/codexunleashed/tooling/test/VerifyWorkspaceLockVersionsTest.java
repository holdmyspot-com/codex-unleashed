package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import com.holdmyspot.codexunleashed.tooling.VerifyWorkspaceLockVersions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies the comparison of {@link VerifyWorkspaceLockVersions} on documents and on repositories whose Git baseline
 * is scripted, so that each decision is observed on the production operation without a real Git process.
 */
public final class VerifyWorkspaceLockVersionsTest
{
	/** Workspace manifest whose release version is 1.2.3. */
	private static final String MANIFEST = """
		[workspace]
		members = ["alpha"]

		[workspace.package]
		version = "1.2.3"
		""";
	/** The lockfile of the repository fixtures, at the development version. */
	private static final String BEFORE_LOCK = lock(workspacePackage("alpha", "0.0.0"), externalPackage("00"));
	/** The lockfile of the repository fixtures, at the release version. */
	private static final String AFTER_LOCK = lock(workspacePackage("alpha", "1.2.3"), externalPackage("00"));

	/** Constructs independent fixtures for each check. */
	public VerifyWorkspaceLockVersionsTest()
	{
	}

	/**
	 * Accepts a candidate whose workspace packages differ only in version, counting those packages, even when an
	 * external package lies between them.
	 *
	 * @throws IOException if a document cannot be interpreted
	 */
	@Test
	public void acceptsWorkspaceVersionChanges() throws IOException
	{
		String before = lock(workspacePackage("alpha", "0.0.0"), externalPackage("00"),
			workspacePackage("beta", "0.0.0"));
		String after = lock(workspacePackage("alpha", "1.2.3"), externalPackage("00"),
			workspacePackage("beta", "1.2.3"));

		requireThat(decide(before, after, MANIFEST), "actualDecision").isEqualTo("verified 2");
		requireThat(decide(before, before.replace("0.0.0", "1.2.3"), MANIFEST), "actualDecision").
			isEqualTo("verified 2");
		requireThat(decide("package = []\n", "package = []\n", MANIFEST), "actualDecision").isEqualTo("verified 0");
	}

	/**
	 * Reports a changed lockfile entry before a changed package, and a changed external package before a changed
	 * workspace package.
	 *
	 * @throws IOException if a document cannot be interpreted
	 */
	@Test
	public void rejectsHeaderDifferenceFirst() throws IOException
	{
		String before = lock(workspacePackage("alpha", "0.0.0"), externalPackage("00"));
		String changedExternal = lock(workspacePackage("alpha", "9.9.9"), externalPackage("11"));

		requireThat(decide(before, changedExternal, MANIFEST), "actualDecision").isEqualTo("EXTERNAL");
		requireThat(decide(before, changedExternal.replace("version = 4", "version = 3"), MANIFEST),
			"actualDecision").isEqualTo("METADATA");
		requireThat(decide(before, AFTER_LOCK.replace("1.2.3", "9.9.9"), MANIFEST), "actualDecision").
			isEqualTo("WORKSPACE");
	}

	/**
	 * Rejects each way in which a package without a source can differ from its baseline by more than its version.
	 *
	 * @param name  the case
	 * @param after the candidate lockfile
	 * @throws IOException if a document cannot be interpreted
	 */
	@Test(dataProvider = "workspaceChanges")
	public void rejectsWorkspaceChanges(String name, String after) throws IOException
	{
		String before = lock(workspacePackage("alpha", "0.0.0"), externalPackage("00"),
			workspacePackage("beta", "0.0.0"));

		requireThat(decide(before, after, MANIFEST), name).isEqualTo("WORKSPACE");
	}

	/**
	 * Supplies candidate lockfiles that change a workspace package beyond its version.
	 *
	 * @return the case names and candidate lockfiles
	 */
	@DataProvider(name = "workspaceChanges")
	public Object[][] workspaceChanges()
	{
		return new Object[][]
		{
			{"wrongVersion", lock(workspacePackage("alpha", "1.2.3"), externalPackage("00"),
				workspacePackage("beta", "1.2.4"))},
			{"unchangedVersion", lock(workspacePackage("alpha", "1.2.3"), externalPackage("00"),
				workspacePackage("beta", "0.0.0"))},
			{"renamedPackage", lock(workspacePackage("alpha", "1.2.3"), externalPackage("00"),
				workspacePackage("gamma", "1.2.3"))},
			{"removedPackage", lock(workspacePackage("alpha", "1.2.3"), externalPackage("00"))},
			{"addedPackage", lock(workspacePackage("alpha", "1.2.3"), externalPackage("00"),
				workspacePackage("beta", "1.2.3"), workspacePackage("gamma", "1.2.3"))},
			{"reorderedPackages", lock(workspacePackage("beta", "1.2.3"), externalPackage("00"),
				workspacePackage("alpha", "1.2.3"))},
			{"addedField", lock(workspacePackage("alpha", "1.2.3"), externalPackage("00"),
				workspacePackage("beta", "1.2.3") + "dependencies = [\"alpha\"]\n")}
		};
	}

	/**
	 * Treats a package as external whenever it has a {@code source} entry, whatever the entry's value, and as a
	 * workspace package otherwise.
	 *
	 * @param name           the case
	 * @param sourceValue    the TOML value of the {@code source} entry, or an empty string for none
	 * @param expectedChange the decision when only the package version changes
	 * @throws IOException if a document cannot be interpreted
	 */
	@Test(dataProvider = "sourceValues")
	public void preservesSourcePresence(String name, String sourceValue, String expectedChange) throws IOException
	{
		String sourceLine = "";
		if (!sourceValue.isEmpty())
			sourceLine = "source = " + sourceValue + "\n";
		String before = lock("[[package]]\nname = \"source\"\nversion = \"0.0.0\"\n" + sourceLine);
		String after = lock("[[package]]\nname = \"source\"\nversion = \"1.2.3\"\n" + sourceLine);

		requireThat(decide(before, after, MANIFEST), name).isEqualTo(expectedChange);
		requireThat(decide(after, after, MANIFEST), name).isEqualTo("verified " + countWorkspace(sourceValue));
	}

	/**
	 * Counts the packages without a source in a lockfile that has one package.
	 *
	 * @param sourceValue the TOML value of the {@code source} entry, or an empty string for none
	 * @return one if the entry is absent, otherwise zero
	 */
	private static int countWorkspace(String sourceValue)
	{
		if (sourceValue.isEmpty())
			return 1;
		return 0;
	}

	/**
	 * Supplies {@code source} entries of each kind of value.
	 *
	 * @return the case names, entry values and decisions for a version-only change
	 */
	@DataProvider(name = "sourceValues")
	public Object[][] sourceValues()
	{
		return new Object[][]
		{
			{"absent", "", "verified 1"},
			{"emptyString", "\"\"", "EXTERNAL"},
			{"falseValue", "false", "EXTERNAL"},
			{"zero", "0", "EXTERNAL"},
			{"emptyList", "[]", "EXTERNAL"},
			{"emptyTable", "{}", "EXTERNAL"},
			{"registry", "\"registry+https://example.invalid/index\"", "EXTERNAL"}
		};
	}

	/**
	 * Compares integers, floats and booleans by exact numeric value, with a NaN equal to nothing.
	 *
	 * @param before the TOML value in the baseline
	 * @param after  the TOML value in the candidate
	 * @param equal  whether the values are equal
	 * @throws IOException if a document cannot be interpreted
	 */
	@Test(dataProvider = "numericPairs")
	public void comparesNumericValuesExactly(String before, String after, boolean equal) throws IOException
	{
		String expected = "METADATA";
		if (equal)
			expected = "verified 0";

		requireThat(decide(valueLock(before), valueLock(after), MANIFEST), "actualDecision").
			isEqualTo(expected);
	}

	/**
	 * Supplies pairs of numeric TOML values with the equality that Python gives them.
	 *
	 * @return the values and their equality
	 */
	@DataProvider(name = "numericPairs")
	public Object[][] numericPairs()
	{
		return new Object[][]
		{
			{"1", "1.0", true},
			{"1.0", "1", true},
			{"true", "1", true},
			{"false", "0", true},
			{"true", "1.0", true},
			{"false", "-0.0", true},
			{"0.0", "-0.0", true},
			{"true", "2", false},
			{"1", "1.5", false},
			{"1", "\"1\"", false},
			{"1", "[1]", false},
			{"nan", "nan", false},
			{"nan", "-nan", false},
			{"inf", "inf", true},
			{"inf", "+inf", true},
			{"inf", "-inf", false},
			{"inf", "1", false},
			{"9007199254740993", "9007199254740992.0", false},
			{"9007199254740992", "9007199254740992.0", true},
			{"9223372036854775808", "9223372036854775808", true},
			{"9223372036854775808", "9223372036854775809", false},
			{"18446744073709551616", "1.8446744073709552e19", true},
			{"-9223372036854775809", "-9223372036854775808", false}
		};
	}

	/**
	 * Keeps each kind of date and time distinct, truncates their fractions to microseconds, and compares offset
	 * date-times by instant.
	 *
	 * @param before the TOML value in the baseline
	 * @param after  the TOML value in the candidate
	 * @param equal  whether the values are equal
	 * @throws IOException if a document cannot be interpreted
	 */
	@Test(dataProvider = "temporalPairs")
	public void preservesTemporalKinds(String before, String after, boolean equal) throws IOException
	{
		String expected = "METADATA";
		if (equal)
			expected = "verified 0";

		requireThat(decide(valueLock(before), valueLock(after), MANIFEST), "actualDecision").
			isEqualTo(expected);
	}

	/**
	 * Supplies pairs of temporal TOML values with the equality that Python gives them.
	 *
	 * @return the values and their equality
	 */
	@DataProvider(name = "temporalPairs")
	public Object[][] temporalPairs()
	{
		return new Object[][]
		{
			{"1979-05-27", "1979-05-27", true},
			{"1979-05-27", "1979-05-28", false},
			{"1979-05-27", "\"1979-05-27\"", false},
			{"1979-05-27", "1979-05-27T00:00:00", false},
			{"07:32:00", "\"07:32:00\"", false},
			{"07:32:00", "1979-05-27T07:32:00", false},
			{"07:32:00.1234567", "07:32:00.1234561", true},
			{"07:32:00.123456", "07:32:00.123457", false},
			{"1979-05-27T07:32:00.1234567", "1979-05-27T07:32:00.1234561", true},
			{"1979-05-27T07:32:00.123456", "1979-05-27T07:32:00.123457", false},
			{"1979-05-27T07:32:00Z", "1979-05-27T08:32:00+01:00", true},
			{"1979-05-27T07:32:00Z", "1979-05-27T07:32:01Z", false},
			{"1979-05-27T07:32:00.1234567Z", "1979-05-27T07:32:00.1234561Z", true},
			{"1979-05-27T07:32:00", "1979-05-27T07:32:00Z", false},
			{"1979-05-27T07:32:00Z", "\"1979-05-27T07:32:00Z\"", false}
		};
	}

	/**
	 * Compares tables regardless of entry order and lists in order, with the scalar rules applied at every depth.
	 *
	 * @param before the TOML value in the baseline
	 * @param after  the TOML value in the candidate
	 * @param equal  whether the values are equal
	 * @throws IOException if a document cannot be interpreted
	 */
	@Test(dataProvider = "containerPairs")
	public void comparesContainersRecursively(String before, String after, boolean equal) throws IOException
	{
		String expected = "METADATA";
		if (equal)
			expected = "verified 0";

		requireThat(decide(valueLock(before), valueLock(after), MANIFEST), "actualDecision").
			isEqualTo(expected);
	}

	/**
	 * Supplies pairs of container TOML values with the equality that Python gives them.
	 *
	 * @return the values and their equality
	 */
	@DataProvider(name = "containerPairs")
	public Object[][] containerPairs()
	{
		return new Object[][]
		{
			{"{a = 1, b = 2}", "{b = 2, a = 1}", true},
			{"{a = 1}", "{a = 1, b = 2}", false},
			{"{a = 1}", "{b = 1}", false},
			{"[1, 2]", "[2, 1]", false},
			{"[1, 2]", "[1.0, 2.0]", true},
			{"[1]", "[1, 1]", false},
			{"[]", "{}", false},
			{"{a = [true]}", "{a = [1]}", true},
			{"[{a = 1}, {a = 2}]", "[{a = 1.0}, {a = 2}]", true},
			{"[nan]", "[nan]", false}
		};
	}

	/**
	 * Reads the package list with the iteration, membership and {@code dict} rules of the comparison it replaces,
	 * so that a package list that is not a list of tables is decided as that comparison decided it.
	 *
	 * @param name     the case
	 * @param before   the baseline lockfile
	 * @param after    the candidate lockfile
	 * @param expected the decision
	 * @throws IOException if a document cannot be interpreted
	 */
	@Test(dataProvider = "packageForms")
	public void matchesPythonPackageIteration(String name, String before, String after, String expected)
		throws IOException
	{
		requireThat(decide(before, after, MANIFEST), name).isEqualTo(expected);
	}

	/**
	 * Supplies package lists that are not lists of tables.
	 *
	 * @return the case names, baseline and candidate lockfiles, and decisions
	 */
	@DataProvider(name = "packageForms")
	public Object[][] packageForms()
	{
		return new Object[][]
		{
			{"emptyTable", "package = {}\n", "package = {}\n", "verified 0"},
			{"emptyString", "package = \"\"\n", "package = \"\"\n", "verified 0"},
			{"emptyListAndTable", "package = []\n", "package = {}\n", "verified 0"},
			{"tableKeysContainSource", "package = {source_a = 1}\n", "package = {source_a = 2}\n", "verified 0"},
			{"reversedTableKeys", "package = {source_a = 1, source_b = 2}\n", "package = {source_b = 2, source_a = 1}\n",
				"EXTERNAL"},
			{"listHoldsSourceString", "package = [\"source\"]\n", "package = [\"source\"]\n", "verified 0"},
			{"listOfPairs", "package = [[[\"name\", \"a\"], [\"version\", \"0.0.0\"]]]\n",
				"package = [{name = \"a\", version = \"1.2.3\"}]\n", "verified 1"},
			{"listOfPairsMismatch", "package = [[[\"name\", \"a\"], [\"version\", \"0.0.0\"]]]\n",
				"package = [{name = \"b\", version = \"1.2.3\"}]\n", "WORKSPACE"},
			{"pairsOfTwoCharacters", "package = [[\"ab\", \"cd\"]]\n",
				"package = [{a = \"b\", c = \"d\", version = \"1.2.3\"}]\n", "verified 1"},
			{"pairsOfTableKeys", "package = [[{a = 1, b = 2}]]\n", "package = [{a = \"b\", version = \"1.2.3\"}]\n",
				"verified 1"},
			{"emptyStringRecord", "package = [\"\"]\n", "package = [{version = \"1.2.3\"}]\n", "verified 1"},
			{"nonStringKey", "package = [[[1, \"a\"]]]\n", "package = [{version = \"1.2.3\"}]\n", "WORKSPACE"},
			{"stringAndTable", "package = \"\"\n", "package = {}\n", "verified 0"}
		};
	}

	/**
	 * Fails operationally, not with a policy difference, when a document cannot be parsed or traversed.
	 *
	 * @param name     the case
	 * @param before   the baseline lockfile
	 * @param after    the candidate lockfile
	 * @param manifest the workspace manifest
	 * @param message  text that the failure message must contain
	 */
	@Test(dataProvider = "unusableDocuments")
	public void rejectsUnusableDocuments(String name, String before, String after, String manifest, String message)
	{
		IOException failure = TestRequirements.requireException(IOException.class,
			() -> decide(before, after, manifest));

		requireThat(failure.getMessage(), name).contains(message);
	}

	/**
	 * Supplies documents that cannot be used.
	 *
	 * @return the case names, documents and message text
	 */
	@DataProvider(name = "unusableDocuments")
	public Object[][] unusableDocuments()
	{
		String valid = lock(workspacePackage("alpha", "0.0.0"));
		return new Object[][]
		{
			{"nonIterablePackage", "package = 1\n", "package = 1\n", MANIFEST, "cannot be iterated"},
			{"noPackage", "version = 4\n", "version = 4\n", MANIFEST, "HEAD:codex-rs/Cargo.lock has no package"},
			{"emptyDocuments", "", "", MANIFEST, "HEAD:codex-rs/Cargo.lock has no package"},
			{"noCandidatePackage", "package = []\n", "", MANIFEST, "codex-rs/Cargo.lock has no package"},
			{"invalidBaseline", "package = \n", "package = []\n", MANIFEST, "Cannot parse HEAD:codex-rs/Cargo.lock"},
			{"invalidCandidate", valid, "package = \n", MANIFEST, "Cannot parse codex-rs/Cargo.lock"},
			{"invalidManifest", valid, valid, "[workspace\n", "Cannot parse codex-rs/Cargo.toml"},
			{"noWorkspaceVersion", valid, valid, "[workspace.package]\nname = \"x\"\n", "workspace.package.version"},
			{"workspaceNotTable", valid, valid, "workspace = \"x\"\n", "workspace.package.version"},
			{"noWorkspace", valid, valid, "[package]\nname = \"x\"\n", "workspace.package.version"},
			{"scalarPackageItem", "package = [1]\n", "package = [1]\n", MANIFEST, "cannot be tested for a source"},
			{"scalarPackageKeyList", "package = [[[1]]]\n", "package = []\n", MANIFEST, "not a key-value pair"},
			{"triplePair", "package = [[[1, 2, 3]]]\n", "package = []\n", MANIFEST, "supplies 3 values"},
			{"unhashableKey", "package = [[[[1], \"a\"]]]\n", "package = []\n", MANIFEST, "unhashable key"},
			{"nonEmptyStringRecord", "package = [\"a\"]\n", "package = []\n", MANIFEST, "cannot be converted"},
			{"integerRecord", "package = [{name = \"a\"}, 5]\n", "package = []\n", MANIFEST, "cannot be tested for a source"}
		};
	}

	/**
	 * Uses the workspace version whatever its TOML type, adding or replacing the baseline's version entry.
	 *
	 * @throws IOException if a document cannot be interpreted
	 */
	@Test
	public void usesWorkspaceVersionOfAnyType() throws IOException
	{
		String before = "package = [{name = \"a\", version = \"0.0.0\"}, {name = \"b\"}]\n";
		String after = "package = [{name = \"a\", version = 7}, {name = \"b\", version = 7}]\n";
		String manifest = "[workspace.package]\nversion = 7\n";

		requireThat(decide(before, after, manifest), "actualDecision").isEqualTo("verified 2");
		requireThat(decide(before, after, MANIFEST), "actualDecision").isEqualTo("WORKSPACE");
	}

	/**
	 * Reads the baseline from Git at HEAD through the supplied capture, uses the checkout's two files, counts the
	 * workspace packages, and writes nothing.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void leavesInputsUnchanged() throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("lock-versions-");
			ToolingScope scope = new DefaultToolingScope())
		{
			Path repository = writeRepository(fixture, AFTER_LOCK, MANIFEST);
			Path temporary = fixture.createDirectories("temporary");
			Map<String, String> environment = Map.of("GIT_DIR", "unused");
			RecordingCapture capture = new RecordingCapture(new SystemCommands.Result(0, BEFORE_LOCK, ""));
			List<String> filesBefore = listFiles(fixture.getPath());

			int actualCount;
			try
			{
				actualCount = VerifyWorkspaceLockVersions.verifyRepository(scope, capture, temporary, environment,
					repository);
			}
			catch (VerifyWorkspaceLockVersions.DifferenceException unexpected)
			{
				throw new IOException(unexpected);
			}

			requireThat(actualCount, "actualCount").isEqualTo(1);
			requireThat(capture.commands, "commands").
				isEqualTo(List.of(List.of("git", "show", "HEAD:codex-rs/Cargo.lock")));
			requireThat(capture.workingDirectories, "workingDirectories").isEqualTo(List.of(repository));
			requireThat(capture.temporaryDirectories, "temporaryDirectories").isEqualTo(List.of(temporary));
			requireThat(capture.environments, "environments").isEqualTo(List.of(environment));
			requireThat(listFiles(fixture.getPath()), "filesAfter").isEqualTo(filesBefore);
			requireThat(Files.readString(repository.resolve("codex-rs/Cargo.lock"), StandardCharsets.UTF_8),
				"lockAfter").isEqualTo(AFTER_LOCK);
			requireThat(Files.readString(repository.resolve("codex-rs/Cargo.toml"), StandardCharsets.UTF_8),
				"manifestAfter").isEqualTo(MANIFEST);
		}
	}

	/**
	 * Reports a nonzero Git status with its number and standard error, and never parses the standard output of a
	 * failed Git.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void reportsGitFailure() throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("lock-versions-");
			ToolingScope scope = new DefaultToolingScope())
		{
			Path repository = writeRepository(fixture, AFTER_LOCK, MANIFEST);
			Path temporary = fixture.createDirectories("temporary");
			RecordingCapture capture = new RecordingCapture(
				new SystemCommands.Result(128, BEFORE_LOCK, "fatal: invalid object name 'HEAD'.\n"));

			IOException failure = TestRequirements.requireException(IOException.class,
				() -> VerifyWorkspaceLockVersions.verifyRepository(scope, capture, temporary, Map.of(), repository));

			requireThat(failure.getMessage(), "actualMessage").contains("git show HEAD:codex-rs/Cargo.lock").
				contains("status 128").contains("fatal: invalid object name 'HEAD'.");
		}
	}

	/**
	 * Preserves a failure to run Git or to clean up its captured output as the cause of an operational failure.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void reportsGitStartFailure() throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("lock-versions-");
			ToolingScope scope = new DefaultToolingScope())
		{
			Path repository = writeRepository(fixture, AFTER_LOCK, MANIFEST);
			Path temporary = fixture.createDirectories("temporary");
			IOException cause = new IOException("cannot remove capture directory");
			SystemCommands.Capture failing = (command, workingDirectory, temporaryDirectory, environment) ->
			{
				throw cause;
			};

			IOException failure = TestRequirements.requireException(IOException.class,
				() -> VerifyWorkspaceLockVersions.verifyRepository(scope, failing, temporary, Map.of(), repository));

			requireThat(failure.getMessage(), "actualMessage").contains("git show HEAD:codex-rs/Cargo.lock failed").
				contains("cannot remove capture directory");
			requireThat(failure.getCause(), "actualCause").isReferenceEqualTo(cause, "cause");
		}
	}

	/**
	 * Reads every input before comparing, so that an unreadable manifest is an operational failure even when the
	 * lockfiles already differ in a way that a comparison would report.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void readsAllInputsBeforeComparing() throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("lock-versions-");
			ToolingScope scope = new DefaultToolingScope())
		{
			Path repository = writeRepository(fixture, AFTER_LOCK.replace("version = 4", "version = 3"), MANIFEST);
			Files.delete(repository.resolve("codex-rs/Cargo.toml"));
			Path temporary = fixture.createDirectories("temporary");
			RecordingCapture capture = new RecordingCapture(new SystemCommands.Result(0, BEFORE_LOCK, ""));

			IOException failure = TestRequirements.requireException(IOException.class,
				() -> VerifyWorkspaceLockVersions.verifyRepository(scope, capture, temporary, Map.of(), repository));

			requireThat(failure.getMessage(), "actualMessage").
				contains(repository.resolve("codex-rs/Cargo.toml").toString());
		}
	}

	/**
	 * Rejects a lockfile that is not valid UTF-8, naming the file.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void rejectsMalformedUtf8() throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("lock-versions-");
			ToolingScope scope = new DefaultToolingScope())
		{
			Path repository = writeRepository(fixture, AFTER_LOCK, MANIFEST);
			Files.write(repository.resolve("codex-rs/Cargo.lock"), new byte[]{(byte) 0xff, (byte) 0xfe});
			Path temporary = fixture.createDirectories("temporary");
			RecordingCapture capture = new RecordingCapture(new SystemCommands.Result(0, BEFORE_LOCK, ""));

			IOException failure = TestRequirements.requireException(IOException.class,
				() -> VerifyWorkspaceLockVersions.verifyRepository(scope, capture, temporary, Map.of(), repository));

			requireThat(failure.getMessage(), "actualMessage").contains("Cargo.lock").contains("UTF-8");
		}
	}

	/**
	 * Compares two lockfiles and a manifest.
	 *
	 * @param before   the baseline lockfile
	 * @param after    the candidate lockfile
	 * @param manifest the workspace manifest
	 * @return {@code verified} and the workspace count, or the name of the difference
	 * @throws IOException if a document cannot be interpreted
	 */
	private static String decide(String before, String after, String manifest) throws IOException
	{
		try (ToolingScope scope = new DefaultToolingScope())
		{
			return "verified " + VerifyWorkspaceLockVersions.verifyDocuments(scope, before, after, manifest);
		}
		catch (VerifyWorkspaceLockVersions.DifferenceException difference)
		{
			return difference.getDifference().name();
		}
	}

	/**
	 * Builds a lockfile.
	 *
	 * @param packages the {@code [[package]]} tables
	 * @return a lockfile with a header and the packages
	 */
	private static String lock(String... packages)
	{
		return "version = 4\n\n" + String.join("\n", packages);
	}

	/**
	 * Builds a lockfile with one value in its header and no packages.
	 *
	 * @param value the TOML value
	 * @return the lockfile
	 */
	private static String valueLock(String value)
	{
		return "value = " + value + "\npackage = []\n";
	}

	/**
	 * Builds a package without a source.
	 *
	 * @param name    the package name
	 * @param version the package version
	 * @return the {@code [[package]]} table
	 */
	private static String workspacePackage(String name, String version)
	{
		return "[[package]]\nname = \"" + name + "\"\nversion = \"" + version + "\"\n";
	}

	/**
	 * Builds a package with a registry source.
	 *
	 * @param checksum the package checksum
	 * @return the {@code [[package]]} table
	 */
	private static String externalPackage(String checksum)
	{
		return "[[package]]\nname = \"serde\"\nversion = \"1.0.0\"\n" +
			"source = \"registry+https://github.com/rust-lang/crates.io-index\"\nchecksum = \"" + checksum + "\"\n";
	}

	/**
	 * Writes the two checkout files under a repository directory.
	 *
	 * @param fixture  the fixture root
	 * @param lock     the candidate lockfile
	 * @param manifest the workspace manifest
	 * @return the repository directory
	 * @throws IOException if a file cannot be written
	 */
	private static Path writeRepository(TestDirectory fixture, String lock, String manifest) throws IOException
	{
		fixture.write("repository/codex-rs/Cargo.lock", lock);
		fixture.write("repository/codex-rs/Cargo.toml", manifest);
		return fixture.getPath().resolve("repository");
	}

	/**
	 * Lists every file and directory beneath a directory, with the content of each file.
	 *
	 * @param root the directory
	 * @return the relative paths, each followed by the file's text, in sorted order
	 * @throws IOException if the directory cannot be read
	 */
	private static List<String> listFiles(Path root) throws IOException
	{
		List<String> result = new ArrayList<>();
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.sorted().toList())
			{
				String content = "";
				if (Files.isRegularFile(path))
					content = Files.readString(path, StandardCharsets.UTF_8);
				result.add(root.relativize(path) + "=" + content);
			}
		}
		return result;
	}

	/**
	 * A capture that records each request and returns one scripted result.
	 */
	private static final class RecordingCapture implements SystemCommands.Capture
	{
		/** The commands requested. */
		private final List<List<String>> commands = new ArrayList<>();
		/** The working directories requested. */
		private final List<Path> workingDirectories = new ArrayList<>();
		/** The temporary directories requested. */
		private final List<Path> temporaryDirectories = new ArrayList<>();
		/** The environments requested. */
		private final List<Map<String, String>> environments = new ArrayList<>();
		/** The result returned for every request. */
		private final SystemCommands.Result result;

		/**
		 * Creates a capture.
		 *
		 * @param result the result returned for every request
		 */
		private RecordingCapture(SystemCommands.Result result)
		{
			this.result = result;
		}

		@Override
		public SystemCommands.Result capture(List<String> command, Path workingDirectory, Path temporaryDirectory,
			Map<String, String> environment)
		{
			commands.add(command);
			workingDirectories.add(workingDirectory);
			temporaryDirectories.add(temporaryDirectory);
			environments.add(environment);
			return result;
		}
	}
}
