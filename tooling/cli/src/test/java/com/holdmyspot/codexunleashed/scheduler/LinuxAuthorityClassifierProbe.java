package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises the real Linux FFM classifier against ordinary files; it makes no positive native-authority claim.
 */
public final class LinuxAuthorityClassifierProbe
{
	/**
	 * Prevents construction of this standalone native check.
	 */
	private LinuxAuthorityClassifierProbe()
	{
	}

	/**
	 * Requires an ordinary directory with plausible core-file names to remain unavailable.
	 * @param arguments one owned scratch directory
	 * @throws IOException if owned fixture creation or cleanup fails
	 */
	public static void main(String[] arguments) throws IOException
	{
		requireThat(LinuxAuthorityReader.SharedSecrets.openFlags("amd64", true), "x64DirectoryFlags")
			.isEqualTo(02600000, "expected");
		requireThat(LinuxAuthorityReader.SharedSecrets.openFlags("x86_64", false), "x64FileFlags")
			.isEqualTo(02400000, "expected");
		requireThat(LinuxAuthorityReader.SharedSecrets.openFlags("aarch64", true), "arm64DirectoryFlags")
			.isEqualTo(02140000, "expected");
		requireThat(LinuxAuthorityReader.SharedSecrets.openFlags("aarch64", false), "arm64FileFlags")
			.isEqualTo(02100000, "expected");
		requireException(UnsupportedOperationException.class,
			() -> LinuxAuthorityReader.SharedSecrets.openFlags("unsupported", true));
		System.out.println("open_flags=x64_and_arm64_verified; unsupported_architecture=rejected");
		Path directory = Files.createTempDirectory(Path.of(arguments[0]), "classifier-");
		try
		{
			Files.writeString(directory.resolve("cgroup.procs"), "");
			Files.writeString(directory.resolve("cgroup.controllers"), "memory\n");
			requireThat(openFixtureDescriptors(directory), "fixtureDescriptorsBeforeRead").isEqualTo(0L, "expected");
			IOException failure = requireException(IOException.class,
				() -> LinuxAuthorityReader.SharedSecrets.requireActualRoot(directory));
			requireThat(failure.getMessage().contains("filesystem magic"), "ordinaryFilesystemRejected").isTrue();
			requireThat(openFixtureDescriptors(directory), "fixtureDescriptorsAfterRejectedRead").isEqualTo(0L, "expected");
			System.out.println("ordinary_filesystem=UNAVAILABLE; native_classifier_invoked=true; owned_descriptors_after=0");
		}
		finally
		{
			Files.deleteIfExists(directory.resolve("cgroup.controllers"));
			Files.deleteIfExists(directory.resolve("cgroup.procs"));
			Files.delete(directory);
		}
	}

	/**
	 * Counts only retained descriptors pointing at this live owned fixture, ignoring unrelated JVM descriptors.
	 * @param directory live owned fixture whose root descriptor must be released after rejection
	 * @return number of open fixture-directory descriptors
	 * @throws IOException if proc descriptor inspection fails
	 */
	private static long openFixtureDescriptors(Path directory) throws IOException
	{
		long count = 0;
		try (var descriptors = Files.newDirectoryStream(Path.of("/proc/self/fd")))
		{
			for (Path descriptor : descriptors)
			{
				try
				{
					if (Files.readSymbolicLink(descriptor).equals(directory))
						count++;
				}
				catch (NoSuchFileException disappeared)
				{
					// Unrelated JVM-owned descriptors may close during enumeration; this fixture stays live throughout.
				}
			}
		}
		return count;
	}
}
