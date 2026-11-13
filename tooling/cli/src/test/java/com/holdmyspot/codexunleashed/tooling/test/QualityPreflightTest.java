package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.build.QualityPreflight;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
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
