package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.PackageZstd;
import com.holdmyspot.codexunleashed.tooling.PackageZstd.Executables;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies the zstd selection policy against recorded tool tokens, without launching any executable.
 */
public final class PackageZstdTest
{
	/** Diagnostic that precedes the wrapper path when no route is available. */
	private static final String REQUIRED = "zstd is required to write .tar.zst archives. Install zstd, or install " +
		"DotSlash so the repository wrapper can run: ";

	/**
	 * Creates policy tests.
	 */
	public PackageZstdTest()
	{
	}

	/**
	 * Selects the zstd executable alone, without consulting DotSlash or the wrapper.
	 *
	 * @throws IOException if a fixture operation fails
	 */
	@Test
	public void prefersPathZstd() throws IOException
	{
		Path root = Files.createTempDirectory("package-zstd-policy-");
		try
		{
			List<String> requested = new ArrayList<>();
			Executables executables = name ->
			{
				requested.add(name);
				return Optional.of("/tools with spaces/" + name);
			};
			requireThat(PackageZstd.resolve(root, executables), "argv").isEqualTo(List.of("/tools with spaces/zstd"));
			requireThat(requested, "requested").isEqualTo(List.of("zstd"));
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Selects DotSlash and the wrapper path when zstd is absent, keeping spaces and non-ASCII text.
	 *
	 * @throws IOException if a fixture operation fails
	 */
	@Test
	public void usesDotslashManifest() throws IOException
	{
		Path base = Files.createTempDirectory("package-zstd-policy-");
		try
		{
			Path root = Files.createDirectory(base.resolve("röot with spaces"));
			Path wrapper = wrapper(root, "{}\n");
			List<String> requested = new ArrayList<>();
			Executables executables = name ->
			{
				requested.add(name);
				return Optional.of("/tools with spaces/dotslash").filter(_ -> name.equals("dotslash"));
			};
			requireThat(PackageZstd.resolve(root, executables), "argv").
				isEqualTo(List.of("/tools with spaces/dotslash", wrapper.toString()));
			requireThat(requested, "requested").isEqualTo(List.of("zstd", "dotslash"));
		}
		finally
		{
			deleteTree(base);
		}
	}

	/**
	 * Rejects a repository when neither tool is available, naming the wrapper that DotSlash would have run.
	 *
	 * @throws IOException if a fixture operation fails
	 */
	@Test
	public void rejectsUnavailableTools() throws IOException
	{
		Path root = Files.createTempDirectory("package-zstd-policy-");
		try
		{
			wrapper(root, "{}\n");
			requireThat(rejection(root, _ -> Optional.empty()), "diagnostics").
				isEqualTo(REQUIRED + root.resolve(".github/workflows/zstd"));
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Rejects DotSlash alone when the wrapper does not exist.
	 *
	 * @throws IOException if a fixture operation fails
	 */
	@Test
	public void rejectsMissingManifest() throws IOException
	{
		Path root = Files.createTempDirectory("package-zstd-policy-");
		try
		{
			requireThat(rejection(root), "diagnostics").isEqualTo(REQUIRED + root.resolve(".github/workflows/zstd"));
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Rejects DotSlash alone when the wrapper path is a directory.
	 *
	 * @throws IOException if a fixture operation fails
	 */
	@Test
	public void rejectsDirectoryManifest() throws IOException
	{
		Path root = Files.createTempDirectory("package-zstd-policy-");
		try
		{
			Files.createDirectories(root.resolve(".github/workflows/zstd"));
			requireThat(rejection(root), "diagnostics").isEqualTo(REQUIRED + root.resolve(".github/workflows/zstd"));
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Rejects DotSlash alone when the wrapper is a link to a file that does not exist.
	 *
	 * @throws IOException if a fixture operation fails
	 */
	@Test
	public void rejectsDanglingManifest() throws IOException
	{
		Path root = Files.createTempDirectory("package-zstd-policy-");
		try
		{
			Path link = root.resolve(".github/workflows/zstd");
			Files.createDirectories(link.getParent());
			Files.createSymbolicLink(link, root.resolve("missing-target"));
			requireThat(rejection(root), "diagnostics").isEqualTo(REQUIRED + link);
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Accepts a wrapper that is a link to a regular file.
	 *
	 * @throws IOException if a fixture operation fails
	 */
	@Test
	public void acceptsManifestSymlink() throws IOException
	{
		Path root = Files.createTempDirectory("package-zstd-policy-");
		try
		{
			Path target = root.resolve("real-wrapper");
			Files.writeString(target, "{}\n");
			Path link = root.resolve(".github/workflows/zstd");
			Files.createDirectories(link.getParent());
			Files.createSymbolicLink(link, target);
			requireThat(PackageZstd.resolve(root, dotslashOnly()), "argv").isEqualTo(List.of("dotslash", link.toString()));
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Accepts a regular wrapper whatever it contains, because DotSlash parses it later.
	 *
	 * @throws IOException if a fixture operation fails
	 */
	@Test
	public void ignoresManifestContents() throws IOException
	{
		Path root = Files.createTempDirectory("package-zstd-policy-");
		try
		{
			Path wrapper = wrapper(root, "");
			requireThat(PackageZstd.resolve(root, dotslashOnly()), "empty").
				isEqualTo(List.of("dotslash", wrapper.toString()));
			Files.write(wrapper, new byte[]{(byte) 0xff, 0, '{', (byte) 0x80});
			requireThat(PackageZstd.resolve(root, dotslashOnly()), "binary").
				isEqualTo(List.of("dotslash", wrapper.toString()));
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Returns each lookup token as spelled, without normalizing it into a path or splitting at spaces.
	 *
	 * @throws IOException if a fixture operation fails
	 */
	@Test
	public void preservesArgvBoundaries() throws IOException
	{
		Path root = Files.createTempDirectory("package-zstd-policy-");
		try
		{
			List<String> tokens = List.of("./bin//zstd", "..\\x/zstd ", "zsté \"quoted\" 'x'\n", "zstd");
			for (String token : tokens)
				requireThat(PackageZstd.resolve(root, name -> Optional.of(token).filter(_ -> name.equals("zstd"))),
					"argv").isEqualTo(List.of(token));
			wrapper(root, "{}\n");
			for (String token : tokens)
			{
				List<String> argv = PackageZstd.resolve(root, name -> Optional.of(token).filter(_ -> name.equals("dotslash")));
				requireThat(argv.getFirst(), "dotslash").isEqualTo(token);
				requireThat(argv.size(), "argvSize").isEqualTo(2);
			}
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Spells the wrapper without {@code .} components, as the retired Python resolver did.
	 *
	 * @throws IOException if a fixture operation fails
	 */
	@Test
	public void dropsDotComponents() throws IOException
	{
		Path root = Files.createTempDirectory("package-zstd-policy-");
		try
		{
			Path wrapper = wrapper(root, "{}\n");
			Path dotted = root.resolve(".").resolve(".");
			requireThat(PackageZstd.resolve(dotted, dotslashOnly()), "argv").
				isEqualTo(List.of("dotslash", wrapper.toString()));
			requireThat(rejection(dotted, _ -> Optional.empty()), "diagnostics").
				isEqualTo(REQUIRED + wrapper);
			Path relative = Path.of(".");
			requireThat(rejection(relative, _ -> Optional.empty()), "relative").
				isEqualTo(REQUIRED + Path.of(".github/workflows/zstd"));
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Propagates a failure of the host search instead of treating the tool as absent.
	 *
	 * @throws IOException the failure that the search raises
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = "search failed")
	public void propagatesSearchFailure() throws IOException
	{
		PackageZstd.resolve(Path.of("."), _ ->
		{
			throw new IOException("search failed");
		});
	}

	/**
	 * Creates the DotSlash wrapper of a repository root.
	 *
	 * @param root     the repository root
	 * @param contents the wrapper text
	 * @return the wrapper path
	 * @throws IOException if the file cannot be written
	 */
	private static Path wrapper(Path root, String contents) throws IOException
	{
		Path wrapper = root.resolve(".github/workflows/zstd");
		Files.createDirectories(wrapper.getParent());
		Files.writeString(wrapper, contents);
		return wrapper;
	}

	/**
	 * Creates a search that finds only DotSlash.
	 *
	 * @return the executable search
	 */
	private static Executables dotslashOnly()
	{
		return name -> Optional.of("dotslash").filter(_ -> name.equals("dotslash"));
	}

	/**
	 * Resolves a repository whose only available tool is DotSlash and returns the rejection text.
	 *
	 * @param root the repository root
	 * @return the diagnostic
	 */
	private static String rejection(Path root)
	{
		return rejection(root, dotslashOnly());
	}

	/**
	 * Resolves a repository that must be rejected and returns the rejection text.
	 *
	 * @param root        the repository root
	 * @param executables the host search
	 * @return the diagnostic
	 */
	private static String rejection(Path root, Executables executables)
	{
		try
		{
			PackageZstd.resolve(root, executables);
		}
		catch (IOException failure)
		{
			return failure.getMessage();
		}
		throw new AssertionError("Expected a rejection for " + root);
	}

	/**
	 * Deletes a fixture directory tree.
	 *
	 * @param root the tree to delete
	 * @throws IOException if a file cannot be deleted
	 */
	private static void deleteTree(Path root) throws IOException
	{
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.deleteIfExists(path);
		}
	}
}
