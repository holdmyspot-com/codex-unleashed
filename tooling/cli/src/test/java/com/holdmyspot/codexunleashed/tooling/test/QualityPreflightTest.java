package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.build.QualityPreflight;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.spi.ToolProvider;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies exact source selection before {@link QualityPreflight} runs the analyzers.
 */
public final class QualityPreflightTest
{
	/**
	 * Creates independently isolated manifest checks.
	 */
	public QualityPreflightTest()
	{
	}

	/**
	 * Resolves selected main, test, and build sources while leaving unselected sources outside the selection.
	 *
	 * @throws IOException if fixture creation, reading, or cleanup fails
	 */
	@Test
	public void selectsAllThreeSourceRoots() throws IOException
	{
		try (Directory directory = new Directory())
		{
			List<String> selected = List.of("tooling/cli/src/main/java/sample/Main.java",
				"tooling/cli/src/test/java/sample/Test.java", "tooling/cli/src/build/java/sample/Build.java");
			for (String entry : selected)
				createSource(directory.path.resolve(entry));
			createSource(directory.path.resolve("tooling/cli/src/main/java/sample/Unselected.java"));
			Path manifest = directory.path.resolve("paths.txt");
			Files.write(manifest, selected);

			requireThat(QualityPreflight.readPaths(directory.path, manifest), "selectedSources").
				isEqualTo(selected.stream().map(directory.path::resolve).toList());
		}
	}

	/**
	 * Verifies Javadoc runs separately for each source root and retains every group's diagnostics.
	 *
	 * @throws IOException if fixture creation, Javadoc reports, or cleanup fails
	 */
	@Test
	public void verifiesJavadocGrouping() throws IOException
	{
		try (Directory directory = new Directory())
		{
			List<String> selected = List.of("tooling/cli/src/main/java/module-info.java",
				"tooling/cli/src/main/java/sample/Main.java", "tooling/cli/src/test/java/module-info.java",
				"tooling/cli/src/test/java/sample/Test.java", "tooling/cli/src/build/java/module-info.java",
				"tooling/cli/src/build/java/sample/Build.java");
			createModuleSource(directory.path.resolve(selected.get(0)), true);
			createModuleSource(directory.path.resolve(selected.get(2)), false);
			createModuleSource(directory.path.resolve(selected.get(4)), false);
			Path manifest = directory.path.resolve("paths.txt");
			Path reports = directory.path.resolve("reports");
			Files.write(manifest, selected);
			QualityPreflight.main(new String[] {"prepare", directory.path.toString(), "paths.txt", reports.toString()});

			Properties current = new Properties();
			try (BufferedReader reader = Files.newBufferedReader(reports.resolve("current.properties")))
			{
				current.load(reader);
			}
			Path invocation = reports.resolve(current.getProperty("quality.run.id"));
			Path classes = Files.createDirectory(directory.path.resolve("classes"));
			Files.writeString(invocation.resolve("classpath.txt"), classes.toString());
			for (String root : List.of("main", "test", "build"))
				Files.writeString(invocation.resolve("javadoc-" + root + "-classes.txt"), classes.toString());
			Files.writeString(invocation.resolve("checkstyle.result"), "passed\n");
			Files.writeString(invocation.resolve("pmd.result"), "passed\n");

			IOException failure = null;
			try
			{
				QualityPreflight.main(new String[] {"verify", directory.path.toString(), "unused", reports.toString()});
			}
			catch (IOException exception)
			{
				failure = exception;
			}
			if (failure == null)
				throw new AssertionError("Javadoc findings in the main source root must fail verification");

			Path legacyLog = invocation.resolve("javadoc.log");
			if (Files.isRegularFile(legacyLog))
			{
				String diagnostic = Files.readString(legacyLog);
				if (diagnostic.contains("too many module declarations") ||
					diagnostic.contains("duplicate module declaration") || diagnostic.contains("duplicate module:"))
					throw new AssertionError("Javadoc must not receive module descriptors from multiple source roots: " +
						diagnostic);
				if (!Files.isRegularFile(invocation.resolve("javadoc-main.log")))
					throw new AssertionError("Expected the pre-grouping failure to identify duplicate module declarations, " +
						"but got: " + diagnostic);
			}

			for (String root : List.of("main", "test", "build"))
			{
				Path log = invocation.resolve("javadoc-" + root + ".log");
				if (!Files.isRegularFile(log))
					throw new AssertionError("Javadoc diagnostics were not retained for the " + root + " source group");
				if (!Files.isDirectory(invocation.resolve("javadoc-" + root)))
					throw new AssertionError("Javadoc output was not isolated for the " + root + " source group");
			}
			String mainDiagnostics = Files.readString(invocation.resolve("javadoc-main.log"));
			if (!mainDiagnostics.contains("warning"))
				throw new AssertionError("The main source group's doclint finding was not retained: " + mainDiagnostics);
			String outcomes = Files.readString(invocation.resolve("outcomes.properties"));
			if (!outcomes.contains("javadoc=findings"))
				throw new AssertionError("A later successful source group erased the main group's finding: " + outcomes);
			List<String> groups = List.of("main", "test", "build");
			List<String> expectedOutcomes = List.of("findings", "passed", "passed");
			for (int index = 0; index < groups.size(); index += 1)
			{
				String group = groups.get(index);
				String expected = expectedOutcomes.get(index);
				String actual = Files.readString(invocation.resolve("javadoc-" + group + ".result")).strip();
				if (!actual.equals(expected))
					throw new AssertionError("Unexpected Javadoc result for " + group + ": " + actual + "\n" +
						Files.readString(invocation.resolve("javadoc-" + group + ".log")));
			}
		}
	}

	/**
	 * Supplies each independently admitted source root for compiled sibling checks.
	 *
	 * @return main, test, and build source roots
	 */
	@DataProvider
	public Object[][] moduleSourceGroups()
	{
		return new Object[][] {{"main"}, {"test"}, {"build"}};
	}

	/**
	 * Resolves compiled unselected siblings while documenting only selected modular sources.
	 *
	 * @param group selected source-root name
	 * @throws IOException if fixture creation or quality reports fail
	 */
	@Test(dataProvider = "moduleSourceGroups")
	public void resolvesCompiledModuleSiblings(String group) throws IOException
	{
		try (Directory directory = new Directory())
		{
			Path reports = directory.path.resolve("reports");
			Path invocation = null;
			try
			{
				Path classes = compileModularFixture(directory.path, group);
				Path helperScope = sourcePath(directory.path, group, "sample/HelperScope.java");
				Path helper = sourcePath(directory.path, group, "sample/Helper.java");
				Files.writeString(helperScope, "package sample;\n/** UNSELECTED_DOC_SENTINEL <unknown-scope-tag> */\n" +
					"public class HelperScope { /** Returns a value. @return a value */ public int value() { return 0; } }\n");
				Files.writeString(helper, "package sample;\n/** UNSELECTED_DOC_SENTINEL <unknown-helper-tag> */\n" +
					"public final class Helper { /** Creates a helper. */ public Helper() {} " +
					"/** A kind. */ public enum Kind { /** A value. */ VALUE } }\n");
				invocation = prepareQualityRun(directory.path, group, classes, true);
				QualityPreflight.main(new String[] {"verify", directory.path.toString(), "unused", reports.toString()});
				assertQualityOutcome(invocation, group, "passed");
				String diagnostics = Files.readString(invocation.resolve("javadoc-" + group + ".log"));
				if (diagnostics.contains("UNSELECTED_DOC_SENTINEL") || diagnostics.contains("unknown-scope-tag") ||
					diagnostics.contains("unknown-helper-tag"))
					throw new AssertionError("Unselected helper documentation entered Javadoc diagnostics: " + diagnostics);
				Path output = invocation.resolve("javadoc-" + group);
				if (Files.exists(output.resolve("sample/HelperScope.html")) ||
					Files.exists(output.resolve("sample/Helper.html")))
					throw new AssertionError("Unselected helper pages were generated");
			}
			finally
			{
				captureFixtureEvidence(directory.path, invocation, "resolved-siblings", group);
			}
		}
	}

	/**
	 * Retains doclint findings from selected source despite compiled stale classes.
	 *
	 * @param group selected source-root name
	 * @throws IOException if fixture creation or quality reports fail
	 */
	@Test(dataProvider = "moduleSourceGroups")
	public void preservesSelectedModuleDoclint(String group) throws IOException
	{
		try (Directory directory = new Directory())
		{
			Path reports = directory.path.resolve("reports");
			Path invocation = null;
			try
			{
				Path classes = compileModularFixture(directory.path, group);
				Path selected = sourcePath(directory.path, group, "sample/Selected.java");
				String source = Files.readString(selected);
				Files.writeString(selected, source.replace("@return the kind.",
					"@return <bad-selected-tag>SELECTED_DOC_SENTINEL"));
				invocation = prepareQualityRun(directory.path, group, classes, true);
				IOException failure = null;
				try
				{
					QualityPreflight.main(new String[] {"verify", directory.path.toString(), "unused", reports.toString()});
				}
				catch (IOException exception)
				{
					failure = exception;
				}
				if (failure == null)
					throw new AssertionError("Malformed selected Javadoc must fail verification");
				assertQualityOutcome(invocation, group, "findings");
				String diagnostics = Files.readString(invocation.resolve("javadoc-" + group + ".log"));
				if (!diagnostics.contains("SELECTED_DOC_SENTINEL"))
					throw new AssertionError("Selected-source doclint diagnostic was not retained: " + diagnostics);
			}
			finally
			{
				captureFixtureEvidence(directory.path, invocation, "selected-doclint", group);
			}
		}
	}

	/**
	 * Rejects a modular Javadoc group when Maven has not supplied its compiled output receipt.
	 *
	 * @param group selected source-root name
	 * @throws IOException if fixture creation or quality reports fail
	 */
	@Test(dataProvider = "moduleSourceGroups")
	public void rejectsMissingModuleClasses(String group) throws IOException
	{
		try (Directory directory = new Directory())
		{
			Path reports = directory.path.resolve("reports");
			Path invocation = null;
			try
			{
				Path classes = compileModularFixture(directory.path, group);
				invocation = prepareQualityRun(directory.path, group, classes, false);
				IOException failure = null;
				try
				{
					QualityPreflight.main(new String[] {"verify", directory.path.toString(), "unused", reports.toString()});
				}
				catch (IOException exception)
				{
					failure = exception;
				}
				if (failure == null)
					throw new AssertionError("A modular group without compiled classes must fail verification");
				assertQualityOutcome(invocation, group, "tool-error");
				String expectedPath = invocation.resolve("javadoc-" + group + "-classes.txt").toString();
				String diagnostics = Files.readString(invocation.resolve("javadoc-" + group + ".log"));
				if (!diagnostics.contains("Compiled module classes unavailable for " + group + ": " + expectedPath) ||
					!diagnostics.contains("receipt is missing"))
					throw new AssertionError("Missing compiled-module receipt diagnostic was not retained: " + diagnostics);
			}
			finally
			{
				captureFixtureEvidence(directory.path, invocation, "missing-module-classes", group);
			}
		}
	}

	/**
	 * Keeps the nonmodular classpath behavior independent of modular class receipts.
	 *
	 * @throws IOException if fixture creation or quality reports fail
	 */
	@Test
	public void keepsNonmodularSelectionIndependent() throws IOException
	{
		try (Directory directory = new Directory())
		{
			Path reports = directory.path.resolve("reports");
			Path invocation = null;
			try
			{
				Path sourceRoot = directory.path.resolve("tooling/cli/src/main/java");
				Path selected = sourceRoot.resolve("sample/PlainSelected.java");
				Path helper = sourceRoot.resolve("sample/PlainHelper.java");
				Files.createDirectories(selected.getParent());
				Files.writeString(selected, "package sample;\n/** A selected class. */\npublic final class PlainSelected { " +
					"/** Creates the class. */ public PlainSelected() {} " +
					"/** Returns the helper.\n * @return a helper.\n */ public PlainHelper helper() { return null; } }\n");
				Files.writeString(helper, "package sample;\n/** A compiled helper. */\npublic final class PlainHelper { " +
					"/** Creates the helper. */ public PlainHelper() {} }\n");
				Path classes = compileSources(directory.path.resolve("fixture-classes"), List.of(selected, helper),
					directory.path.resolve("fixture-compile.stdout"), directory.path.resolve("fixture-compile.stderr"),
					directory.path.resolve("fixture-compile.status"));
				invocation = prepareQualityRun(directory.path, "main",
					List.of("tooling/cli/src/main/java/sample/PlainSelected.java"), classes, false);
				QualityPreflight.main(new String[] {"verify", directory.path.toString(), "unused", reports.toString()});
				assertQualityOutcome(invocation, "main", "passed");
			}
			finally
			{
				captureFixtureEvidence(directory.path, invocation, "nonmodular-selection", "main");
			}
		}
	}

	/**
	 * Creates, compiles, and retains the classes for a documented modular fixture.
	 *
	 * @param repository fixture repository root
	 * @param group source-root name
	 * @return compiled class output directory
	 * @throws IOException if fixture sources or compiler receipts cannot be written
	 */
	private static Path compileModularFixture(Path repository, String group) throws IOException
	{
		Path moduleInfo = sourcePath(repository, group, "module-info.java");
		Path selected = sourcePath(repository, group, "sample/Selected.java");
		Path helperScope = sourcePath(repository, group, "sample/HelperScope.java");
		Path helper = sourcePath(repository, group, "sample/Helper.java");
		Files.createDirectories(moduleInfo.getParent());
		Files.createDirectories(selected.getParent());
		Files.writeString(moduleInfo, "/** A documented fixture module. */\nmodule sample.fixture { exports sample; }\n");
		String selectedReturn = "@return the kind.";
		Files.writeString(selected, "package sample;\n/** A documented selected class. */\n" +
			"public final class Selected {\n" +
			"\t/** Creates a selected class. */\n\tpublic Selected() {}\n" +
			"\t/** Returns the helper scope.\n\t * @return the helper scope.\n\t */\n" +
			"\tpublic HelperScope scope() { return null; }\n" +
			"\t/** Returns a helper kind.\n\t * " + selectedReturn + "\n\t */\n" +
			"\tpublic Helper.Kind kind() { return Helper.Kind.VALUE; }\n}\n");
		Files.writeString(helperScope, "package sample;\n/** A documented helper scope. */\n" +
			"public final class HelperScope {\n" +
			"\t/** Creates a helper scope. */\n\tpublic HelperScope() {}\n" +
			"\t/** Returns a value.\n\t * @return a value.\n\t */\n\tpublic int value() { return 1; }\n}\n");
		Files.writeString(helper, "package sample;\n/** A documented helper. */\npublic final class Helper {\n" +
			"\t/** Creates a helper. */\n\tpublic Helper() {}\n" +
			"\t/** A documented kind. */\n\tpublic enum Kind { /** A documented value. */ VALUE }\n}\n");
		return compileSources(repository.resolve("fixture-classes"), List.of(moduleInfo, selected, helperScope, helper),
			repository.resolve("fixture-compile.stdout"), repository.resolve("fixture-compile.stderr"),
			repository.resolve("fixture-compile.status"));
	}

	/**
	 * Compiles an isolated fixture using the running JDK's javac tool provider.
	 *
	 * @param classes output directory for compiled fixtures
	 * @param sources exact fixture sources to compile
	 * @param stdout retained compiler standard output
	 * @param stderr retained compiler standard error
	 * @param statusPath retained compiler status
	 * @return compiled classes directory
	 * @throws IOException if the output directory or compiler receipts cannot be written
	 */
	private static Path compileSources(Path classes, List<Path> sources, Path stdout, Path stderr, Path statusPath)
		throws IOException
	{
		Files.createDirectories(classes);
		ToolProvider javac = ToolProvider.findFirst("javac").orElseThrow(
			() -> new IllegalStateException("JDK javac tool provider is unavailable"));
		List<String> arguments = new java.util.ArrayList<>(List.of("-d", classes.toString()));
		arguments.addAll(sources.stream().map(Path::toString).toList());
		int status;
		try (PrintWriter output = new PrintWriter(Files.newBufferedWriter(stdout));
			PrintWriter error = new PrintWriter(Files.newBufferedWriter(stderr)))
		{
			status = javac.run(output, error, arguments.toArray(String[]::new));
		}
		Files.writeString(statusPath, status + "\n");
		if (status != 0)
			throw new AssertionError("Fixture javac returned " + status + ": " + Files.readString(stderr));
		return classes;
	}

	/**
	 * Prepares a public driver invocation with its exact manifest and analyzer receipts.
	 *
	 * @param repository fixture repository
	 * @param group selected source-root name
	 * @param classes precompiled fixture classes
	 * @param includeModuleClasses whether to publish the modular output receipt
	 * @return current invocation report directory
	 * @throws IOException if preparation fails
	 */
	private static Path prepareQualityRun(Path repository, String group, Path classes, boolean includeModuleClasses)
		throws IOException
	{
		return prepareQualityRun(repository, group, List.of(
			"tooling/cli/src/" + group + "/java/module-info.java",
			"tooling/cli/src/" + group + "/java/sample/Selected.java"), classes, includeModuleClasses);
	}

	/**
	 * Prepares a public driver invocation that selects the given sources.
	 *
	 * @param repository           fixture repository
	 * @param group                selected source-root name
	 * @param selected             repository-relative paths of the selected sources
	 * @param classes              precompiled fixture classes
	 * @param includeModuleClasses whether to publish the modular output receipt
	 * @return current invocation report directory
	 * @throws IOException if preparation fails
	 */
	private static Path prepareQualityRun(Path repository, String group, List<String> selected, Path classes,
		boolean includeModuleClasses) throws IOException
	{
		Path manifest = repository.resolve("paths.txt");
		Path reports = repository.resolve("reports");
		Files.write(manifest, selected);
		QualityPreflight.main(new String[] {"prepare", repository.toString(), "paths.txt", reports.toString()});
		Properties current = new Properties();
		try (BufferedReader reader = Files.newBufferedReader(reports.resolve("current.properties")))
		{
			current.load(reader);
		}
		Path invocation = reports.resolve(current.getProperty("quality.run.id"));
		Files.writeString(invocation.resolve("classpath.txt"), classes.toString());
		if (includeModuleClasses)
			Files.writeString(invocation.resolve("javadoc-" + group + "-classes.txt"), classes.toString());
		Files.writeString(invocation.resolve("checkstyle.result"), "passed\n");
		Files.writeString(invocation.resolve("pmd.result"), "passed\n");
		return invocation;
	}

	/**
	 * Requires the exact group and aggregate Javadoc result.
	 *
	 * @param invocation retained invocation reports
	 * @param group source-root name
	 * @param expected expected Javadoc outcome
	 * @throws IOException if retained results cannot be read
	 */
	private static void assertQualityOutcome(Path invocation, String group, String expected) throws IOException
	{
		String groupResult = Files.readString(invocation.resolve("javadoc-" + group + ".result")).strip();
		if (!groupResult.equals(expected))
			throw new AssertionError("Unexpected Javadoc result for " + group + ": " + groupResult + "\n" +
				Files.readString(invocation.resolve("javadoc-" + group + ".log")));
		String outcomes = Files.readString(invocation.resolve("outcomes.properties"));
		if (!outcomes.contains("javadoc=" + expected))
			throw new AssertionError("Unexpected aggregate Javadoc result: " + outcomes);
	}

	/**
	 * Copies fixture compilation and Javadoc diagnostics before the test fixture is removed.
	 *
	 * @param repository fixture repository root
	 * @param invocation current invocation or {@code null} if preparation failed
	 * @param scenario fixture scenario name
	 * @param group source-root name
	 * @throws IOException if retained fixture diagnostics cannot be copied
	 */
	private static void captureFixtureEvidence(Path repository, Path invocation, String scenario,
		String group) throws IOException
	{
		Path root = Path.of(System.getProperty("java.io.tmpdir")).resolve("quality-preflight-fixture-evidence").
			resolve(scenario).resolve(group);
		Files.createDirectories(root);
		for (String name : List.of("fixture-compile.stdout", "fixture-compile.stderr", "fixture-compile.status"))
		{
			Path source = repository.resolve(name);
			if (Files.isRegularFile(source))
				Files.copy(source, root.resolve(name), StandardCopyOption.REPLACE_EXISTING);
		}
		if (invocation == null)
			return;
		for (String name : List.of("classpath.txt", "javadoc-" + group + "-classes.txt", "checkstyle.result",
			"pmd.result", "outcomes.properties", "javadoc-" + group + ".log", "javadoc-" + group + ".result"))
		{
			Path source = invocation.resolve(name);
			if (Files.isRegularFile(source))
				Files.copy(source, root.resolve(name), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * Resolves a fixture source under its selected Javadoc source root.
	 *
	 * @param repository fixture repository root
	 * @param group source-root name
	 * @param relative path relative to the Java source root
	 * @return fixture source path
	 */
	private static Path sourcePath(Path repository, String group, String relative)
	{
		return repository.resolve("tooling/cli/src/" + group + "/java").resolve(relative);
	}

	/**
	 * Supplies malformed source selections, including aliases that repeat the same file.
	 *
	 * @return UTF-8 manifest contents that must be rejected
	 */
	@DataProvider
	public Object[][] malformedManifests()
	{
		String selected = "tooling/cli/src/main/java/sample/Selected.java";
		return new Object[][]
		{
			{""}, {"\n"}, {" \n"}, {selected + "\n\n"}, {selected + "\n" + selected},
			{selected + "\ntooling/cli/src/main/java/sample/./Selected.java"},
			{"/absolute.java"}, {"../outside.java"}, {"tooling/cli/src/main/java/../Selected.java"},
			{"tooling/cli/src/main/resources/Selected.java"}, {"tooling/cli/src/main/java/sample/Missing.java"},
			{"tooling/cli/src/main/java/sample/Directory.java"}, {"tooling/cli/src/main/java/sample/Selected.txt"}
		};
	}

	/**
	 * Rejects malformed selections before publishing any analyzer input directory.
	 *
	 * @param contents invalid UTF-8 manifest contents
	 * @throws IOException if fixture creation, reading, or cleanup fails
	 */
	@Test(dataProvider = "malformedManifests", expectedExceptions = IllegalArgumentException.class)
	public void rejectsMalformedManifest(String contents) throws IOException
	{
		try (Directory directory = new Directory())
		{
			createSource(directory.path.resolve("tooling/cli/src/main/java/sample/Selected.java"));
			Files.createDirectory(directory.path.resolve("tooling/cli/src/main/java/sample/Directory.java"));
			Path manifest = directory.path.resolve("paths.txt");
			Path reports = directory.path.resolve("reports");
			Files.writeString(manifest, contents);
			try
			{
				QualityPreflight.prepare(directory.path, manifest, reports);
			}
			finally
			{
				requireThat(Files.exists(reports), "analyzerInputsPublished").isFalse();
			}
		}
	}

	/**
	 * Rejects a symbolic link before source selection can follow it to a different file.
	 *
	 * @throws IOException if fixture creation, reading, or cleanup fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsSymbolicLink() throws IOException
	{
		try (Directory directory = new Directory())
		{
			Path actual = directory.path.resolve("Selected.java");
			createSource(actual);
			Path link = directory.path.resolve("tooling/cli/src/main/java/sample/Selected.java");
			Files.createDirectories(link.getParent());
			Files.createSymbolicLink(link, actual);
			Path manifest = directory.path.resolve("paths.txt");
			Files.writeString(manifest, "tooling/cli/src/main/java/sample/Selected.java\n");
			QualityPreflight.readPaths(directory.path, manifest);
		}
	}

	/**
	 * Creates a regular Java input whose content is irrelevant to manifest validation.
	 *
	 * @param source exact source path to create
	 * @throws IOException if the input cannot be created
	 */
	private static void createSource(Path source) throws IOException
	{
		Files.createDirectories(source.getParent());
		Files.writeString(source, "package sample;\n");
	}

	/**
	 * Creates a module descriptor and type, optionally including missing documentation findings.
	 *
	 * @param source module descriptor path
	 * @param invalidDocumentation whether this descriptor deliberately omits documentation
	 * @throws IOException if the fixture source cannot be created
	 */
	private static void createModuleSource(Path source, boolean invalidDocumentation) throws IOException
	{
		Files.createDirectories(source.getParent());
		String moduleComment = "";
		if (!invalidDocumentation)
			moduleComment = "/** A documented module. */\n";
		Files.writeString(source, moduleComment + "module sample.module { }\n");
		String name = className(source);
		Path type = source.resolveSibling("sample").resolve(name + ".java");
		Files.createDirectories(type.getParent());
		String comment = "";
		String constructor = "";
		if (!invalidDocumentation)
		{
			comment = "/** A documented type. */\n";
			constructor = "\t/** Creates the type. */\n\tpublic " + name + "() {}\n";
		}
		Files.writeString(type, "package sample;\n" + comment + "public class " + name + " {\n" + constructor + "}\n");
	}

	/**
	 * Resolves the fixture type name from its source root.
	 *
	 * @param moduleInfo module descriptor fixture path
	 * @return type name for that source root
	 */
	private static String className(Path moduleInfo)
	{
		Path root = moduleInfo.getParent().getParent();
		return switch (root.getFileName().toString())
		{
			case "main" -> "Main";
			case "test" -> "Test";
			case "build" -> "Build";
			default -> throw new IllegalStateException("Unexpected source root: " + root);
		};
	}

	/**
	 * Owns one manifest fixture and removes its tree on every exit path.
	 */
	private static final class Directory implements AutoCloseable
	{
		/**
		 * The independently owned fixture root removed by {@link #close()}.
		 */
		private final Path path = Files.createTempDirectory("quality-preflight-test-");

		/**
		 * Creates the fixture root under the configured Java temporary directory.
		 *
		 * @throws IOException if the root cannot be created
		 */
		private Directory() throws IOException
		{
		}

		@Override
		public void close() throws IOException
		{
			try (Stream<Path> entries = Files.walk(path))
			{
				for (Path entry : entries.sorted(Comparator.reverseOrder()).toList())
					Files.delete(entry);
			}
		}
	}
}
