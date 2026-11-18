package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.BuildOutput;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.SourceInputTimes;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.testng.SkipException;
import org.testng.annotations.Test;

import static com.holdmyspot.codexunleashed.tooling.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies the latest readable build-output timestamp using independent file trees.
 */
public final class BuildOutputTest
{
	/**
	 * Creates build-output tests.
	 */
	public BuildOutputTest()
	{
	}

	/**
	 * Includes hidden and nested files and observes timestamp updates made after construction.
	 *
	 * @throws IOException if fixture creation, timestamp updates or cleanup fail
	 */
	@Test
	public void observesHiddenNestedOutputUpdates() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path nested = Files.createDirectories(fixture.root.resolve("nested/deeper"));
			Path hidden = Files.writeString(fixture.root.resolve(".hidden"), "hidden output");
			Path output = Files.writeString(nested.resolve("output"), "nested output");
			Instant first = Instant.parse("2020-01-01T00:00:10Z");
			Instant update = Instant.parse("2020-01-01T00:00:20Z");
			setTime(fixture.scope, hidden, Instant.parse("2020-01-01T00:00:05Z"));
			setTime(fixture.scope, output, first);
			BuildOutput buildOutput = new BuildOutput(fixture.scope, fixture.root,
				fixture.root.resolve("report"), fixture.root.resolve("samples"));

			requireThat(buildOutput.latest(), "initialLatestOutput").isEqualTo(first);
			setTime(fixture.scope, output, update);
			requireThat(buildOutput.latest(), "updatedLatestOutput").isEqualTo(update);
		}
	}

	/**
	 * Excludes both configured files after normalizing their lexical path spellings.
	 *
	 * @throws IOException if fixture creation, timestamp updates or cleanup fail
	 */
	@Test
	public void excludesNormalizedReportAndSamples() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path nested = Files.createDirectory(fixture.root.resolve("nested"));
			Path output = Files.writeString(nested.resolve("output"), "build output");
			Path report = Files.writeString(fixture.root.resolve("report"), "report");
			Path samples = Files.writeString(fixture.root.resolve("samples"), "samples");
			Instant expected = Instant.parse("2020-01-01T00:00:10Z");
			setTime(fixture.scope, output, expected);
			setTime(fixture.scope, report, Instant.parse("2020-01-01T00:00:20Z"));
			setTime(fixture.scope, samples, Instant.parse("2020-01-01T00:00:30Z"));
			BuildOutput buildOutput = new BuildOutput(fixture.scope, fixture.root,
				fixture.root.resolve("nested/../report"), fixture.root.resolve("nested/../samples"));

			requireThat(buildOutput.latest(), "latestWithoutExcludedFiles").isEqualTo(expected);
		}
	}

	/**
	 * Returns the epoch for empty and missing roots.
	 *
	 * @throws IOException if fixture creation or cleanup fails
	 */
	@Test
	public void returnsEpochForEmptyAndMissingRoots() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			BuildOutput empty = new BuildOutput(fixture.scope, fixture.root,
				fixture.root.resolve("report"), fixture.root.resolve("samples"));
			BuildOutput missing = new BuildOutput(fixture.scope, fixture.root.resolve("missing"),
				fixture.root.resolve("report"), fixture.root.resolve("samples"));

			requireThat(empty.latest(), "emptyLatest").isEqualTo(Instant.EPOCH);
			requireThat(missing.latest(), "missingLatest").isEqualTo(Instant.EPOCH);
		}
	}

	/**
	 * Ignores a directory timestamp later than every file timestamp.
	 *
	 * @throws IOException if fixture timestamp updates or cleanup fail
	 */
	@Test
	public void ignoresDirectoryModificationTimes() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			SourceInputTimes.setTimes(fixture.scope, fixture.root,
				FileTime.from(Instant.parse("2040-01-01T00:00:00Z")));
			BuildOutput buildOutput = new BuildOutput(fixture.scope, fixture.root,
				fixture.root.resolve("report"), fixture.root.resolve("samples"));

			requireThat(buildOutput.latest(), "latestWithoutFiles").isEqualTo(Instant.EPOCH);
		}
	}

	/**
	 * Preserves future file times and does not return a negative time in place of the epoch.
	 *
	 * @throws IOException if fixture creation, timestamp updates or cleanup fail
	 */
	@Test
	public void preservesFutureAndNegativeTimes() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path futureDirectory = Files.createDirectory(fixture.root.resolve("future"));
			Path futureFile = Files.writeString(futureDirectory.resolve("output"), "future");
			Instant future = Instant.parse("2040-01-01T00:00:00Z");
			setTime(fixture.scope, futureFile, future);
			BuildOutput futureOutput = new BuildOutput(fixture.scope, futureDirectory,
				futureDirectory.resolve("report"), futureDirectory.resolve("samples"));
			requireThat(futureOutput.latest(), "futureOutputTime").isEqualTo(future);

			Path negativeDirectory = Files.createDirectory(fixture.root.resolve("negative"));
			Path negativeFile = Files.writeString(negativeDirectory.resolve("output"), "negative");
			setTime(fixture.scope, negativeFile, Instant.parse("1969-12-31T23:59:50Z"));
			BuildOutput negativeOutput = new BuildOutput(fixture.scope, negativeDirectory,
				negativeDirectory.resolve("report"), negativeDirectory.resolve("samples"));
			requireThat(negativeOutput.latest(), "negativeOutputTime").isEqualTo(Instant.EPOCH);
		}
	}

	/**
	 * Includes regular-file links while skipping broken links and linked subdirectories.
	 *
	 * @throws IOException if fixture creation, timestamp updates or cleanup fail
	 */
	@Test
	public void includesFileLinksAndSkipsOtherLinks() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path watched = Files.createDirectory(fixture.root.resolve("watched"));
			Path target = Files.writeString(fixture.root.resolve("file-target"), "linked output");
			Instant linkedTime = Instant.parse("2020-01-01T00:00:20Z");
			setTime(fixture.scope, target, linkedTime);
			createSymbolicLink(watched.resolve("file-link"), target);
			createSymbolicLink(watched.resolve("broken-link"), fixture.root.resolve("missing-target"));

			Path linkedDirectory = Files.createDirectories(fixture.root.resolve("directory-target/nested"));
			Path ignoredOutput = Files.writeString(linkedDirectory.resolve("output"), "directory target");
			setTime(fixture.scope, ignoredOutput, Instant.parse("2020-01-01T00:00:30Z"));
			createSymbolicLink(watched.resolve("directory-link"), linkedDirectory.getParent());

			Path ownOutput = Files.writeString(watched.resolve("own-output"), "watched output");
			setTime(fixture.scope, ownOutput, Instant.parse("2020-01-01T00:00:10Z"));
			BuildOutput buildOutput = new BuildOutput(fixture.scope, watched,
				watched.resolve("report"), watched.resolve("samples"));

			requireThat(buildOutput.latest(), "latestLinkedFileOutput").isEqualTo(linkedTime);
		}
	}

	/**
	 * Scans files below a root that is itself a directory symbolic link.
	 *
	 * @throws IOException if fixture creation, timestamp updates or cleanup fail
	 */
	@Test
	public void scansRootDirectoryLink() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path target = Files.createDirectory(fixture.root.resolve("root-target"));
			Path output = Files.writeString(target.resolve("output"), "root target output");
			Instant expected = Instant.parse("2020-01-01T00:00:40Z");
			setTime(fixture.scope, output, expected);
			Path rootLink = fixture.root.resolve("root-link");
			createSymbolicLink(rootLink, target);
			BuildOutput buildOutput = new BuildOutput(fixture.scope, rootLink,
				rootLink.resolve("report"), rootLink.resolve("samples"));

			requireThat(buildOutput.latest(), "latestBelowRootLink").isEqualTo(expected);
		}
	}

	/**
	 * Ignores a removed file and retains timestamps from the remaining files.
	 *
	 * @throws IOException if fixture creation, removal or cleanup fail
	 */
	@Test
	public void skipsRemovedFilesAndKeepsRemainingOutput() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path removed = Files.writeString(fixture.root.resolve("removed"), "removed output");
			Path remaining = Files.writeString(fixture.root.resolve("remaining"), "remaining output");
			setTime(fixture.scope, removed, Instant.parse("2020-01-01T00:00:30Z"));
			Instant expected = Instant.parse("2020-01-01T00:00:10Z");
			setTime(fixture.scope, remaining, expected);
			Files.delete(removed);
			BuildOutput buildOutput = new BuildOutput(fixture.scope, fixture.root,
				fixture.root.resolve("report"), fixture.root.resolve("samples"));

			requireThat(buildOutput.latest(), "latestAfterRemoval").isEqualTo(expected);
		}
	}

	/**
	 * Rejects null scope, directory, report and samples inputs.
	 *
	 * @throws IOException if fixture creation or cleanup fail
	 */
	@Test
	public void rejectsNullRequiredInputs() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path report = fixture.root.resolve("report");
			Path samples = fixture.root.resolve("samples");
			requireException(NullPointerException.class,
				() -> new BuildOutput(null, fixture.root, report, samples));
			requireException(NullPointerException.class,
				() -> new BuildOutput(fixture.scope, null, report, samples));
			requireException(NullPointerException.class,
				() -> new BuildOutput(fixture.scope, fixture.root, null, samples));
			requireException(NullPointerException.class,
				() -> new BuildOutput(fixture.scope, fixture.root, report, null));
		}
	}

	/**
	 * Creates a real link fixture or skips when the host does not permit symbolic links.
	 *
	 * @param link path at which to create the symbolic link
	 * @param target path the symbolic link references
	 * @return the created symbolic link path
	 * @throws IOException if another filesystem operation prevents link creation
	 */
	private static Path createSymbolicLink(Path link, Path target) throws IOException
	{
		try
		{
			return Files.createSymbolicLink(link, target);
		}
		catch (UnsupportedOperationException | SecurityException | FileSystemException failure)
		{
			throw new SkipException("The host does not permit symbolic-link fixtures: " + failure);
		}
	}

	/**
	 * Sets a fixture's exact modification time through the project's shared timestamp writer.
	 *
	 * @param scope borrowed timestamp bindings
	 * @param path fixture file to update
	 * @param time deterministic modification time
	 * @throws IOException if the filesystem cannot store the requested time
	 */
	private static void setTime(ToolingScope scope, Path path, Instant time) throws IOException
	{
		SourceInputTimes.setTimes(scope, path, FileTime.from(time));
	}

	/**
	 * Removes only the temporary tree owned by this test fixture.
	 *
	 * @param root test-owned temporary directory
	 * @throws IOException if a fixture entry cannot be removed
	 */
	private static void deleteTree(Path root) throws IOException
	{
		Files.walkFileTree(root, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException
			{
				if (failure != null)
					throw failure;
				Files.delete(directory);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/** Owns one test's scope and temporary file tree. */
	private static final class Fixture implements AutoCloseable
	{
		/** Native timestamp reader borrowed by each observer created in this fixture. */
		private final ToolingScope scope;
		/** Temporary file tree owned by this fixture. */
		private final Path root;

		/**
		 * Creates an independent temporary root and native timestamp scope.
		 *
		 * @throws IOException if the temporary root cannot be created
		 */
		private Fixture() throws IOException
		{
			scope = new DefaultToolingScope();
			try
			{
				root = Files.createTempDirectory("build-output-");
			}
			catch (IOException | RuntimeException | Error failure)
			{
				try
				{
					scope.close();
				}
				catch (RuntimeException | Error cleanupFailure)
				{
					failure.addSuppressed(cleanupFailure);
				}
				throw failure;
			}
		}

		/**
		 * Closes the timestamp scope and removes its temporary file tree.
		 *
		 * @throws IOException if the temporary tree cannot be removed
		 */
		@Override
		public void close() throws IOException
		{
			try (scope)
			{
				deleteTree(root);
			}
		}
	}
}
