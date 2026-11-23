package com.holdmyspot.codexunleashed.distribution.test;

import com.holdmyspot.codexunleashed.distribution.DistributionMain;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies that runtime assembly rejects invalid input before it changes anything.
 */
public final class DistributionMainTest
{
	/**
	 * Creates the runtime assembly tests.
	 */
	public DistributionMainTest()
	{
	}

	/**
	 * Rejects an existing output directory without changing its contents.
	 *
	 * @throws IOException if fixture access fails
	 */
	@Test
	public void preservesExistingOutput() throws IOException
	{
		Path root = Files.createTempDirectory("runtime-");
		try
		{
			Path marker = Files.writeString(root.resolve("marker.txt"), "retained");
			try
			{
				DistributionMain.main(new String[]{root.toString(), root.toString()});
				throw new AssertionError("An existing output directory was accepted");
			}
			catch (IllegalArgumentException expected)
			{
				requireThat(expected.getMessage(), "message").contains(root.toString());
			}
			requireThat(Files.readString(marker), "marker").isEqualTo("retained");
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Rejects a missing module directory without creating the image.
	 *
	 * @throws IOException if fixture access fails
	 */
	@Test
	public void rejectsMissingModules() throws IOException
	{
		Path root = Files.createTempDirectory("runtime-");
		try
		{
			Path image = root.resolve("image");
			try
			{
				DistributionMain.main(new String[]{root.resolve("missing-modules").toString(), image.toString()});
				throw new AssertionError("A missing module directory was accepted");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "message").contains("jlink failed");
			}
			requireThat(Files.exists(image), "imageExists").isFalse();
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Rejects a malformed argument list.
	 *
	 * @throws IOException if the arguments are rejected by the linker instead of validation
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsMalformedArguments() throws IOException
	{
		DistributionMain.main(new String[0]);
	}

	/**
	 * Deletes the temporary tree that the calling test owns.
	 *
	 * @param root the tree to delete
	 * @throws IOException if a path cannot be deleted
	 */
	private static void delete(Path root) throws IOException
	{
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.delete(path);
		}
	}
}
