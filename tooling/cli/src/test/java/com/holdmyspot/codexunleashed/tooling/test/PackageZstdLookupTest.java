package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.PackageZstdLookup;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies the host executable search against Python 3.12 {@code shutil.which} decisions, using explicit
 * environments and temporary directories instead of the test process's own.
 */
public final class PackageZstdLookupTest
{
	/** Whether the host applies Windows search rules. */
	private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
	/**
	 * Executable suffix that the fixture environments' {@code PATHEXT} lists on Windows.
	 */
	private static final String SUFFIX = suffix();

	/**
	 * Creates lookup tests.
	 */
	public PackageZstdLookupTest()
	{
	}

	/**
	 * Keeps the first spelling of each POSIX entry, in order, with empty entries meaning the current directory.
	 */
	@Test
	public void ordersPosixCandidates()
	{
		requireThat(PackageZstdLookup.posixCandidates("zstd", "/a:/b/:/a:"), "candidates").
			isEqualTo(List.of("/a/zstd", "/b/zstd", "zstd"));
		requireThat(PackageZstdLookup.posixCandidates("dotslash", ":"), "separatorOnly").isEqualTo(List.of("dotslash"));
		requireThat(PackageZstdLookup.posixCandidates("zstd", "rel//dir:./x"), "relative").
			isEqualTo(List.of("rel//dir/zstd", "./x/zstd"));
		requireThat(PackageZstdLookup.posixCandidates("zstd", ""), "empty").isEmpty();
	}

	/**
	 * Searches the current directory first and the default extensions in order when Windows variables are absent.
	 */
	@Test
	public void usesWindowsDefaults()
	{
		List<String> candidates = PackageZstdLookup.windowsCandidates("zstd", Map.of(), false);
		requireThat(candidates.size(), "count").isEqualTo(16);
		requireThat(candidates.subList(0, 3), "first").isEqualTo(List.of(".\\zstd.COM", ".\\zstd.EXE", ".\\zstd.BAT"));
		requireThat(candidates.get(8), "second").isEqualTo("C:\\bin\\zstd.COM");
		requireThat(PackageZstdLookup.windowsCandidates("zstd", Map.of("PATHEXT", ""), false), "emptyExtensions").
			isEqualTo(candidates);
	}

	/**
	 * Matches variable names without regard to case and compares directories without regard to case or separator.
	 */
	@Test
	public void foldsWindowsCaseAndSeparators()
	{
		Map<String, String> environment = Map.of("Path", "C:\\a;c:\\A;C:/a;;C:\\b", "pathext", ".EXE;;.CMD..;");
		requireThat(PackageZstdLookup.windowsCandidates("zstd", environment, false), "candidates").
			isEqualTo(List.of("C:\\a\\zstd.EXE", "C:\\a\\zstd.CMD", "zstd.EXE", "zstd.CMD", "C:\\b\\zstd.EXE",
				"C:\\b\\zstd.CMD"));
	}

	/**
	 * Adds the current directory only when asked, searches it once, and finds nothing for an empty path.
	 */
	@Test
	public void placesWindowsCurrentDirectory()
	{
		Map<String, String> environment = Map.of("PATH", "C:\\a", "PATHEXT", ".EXE");
		requireThat(PackageZstdLookup.windowsCandidates("zstd", environment, true), "withCurrent").
			isEqualTo(List.of(".\\zstd.EXE", "C:\\a\\zstd.EXE"));
		requireThat(PackageZstdLookup.windowsCandidates("zstd", environment, false), "withoutCurrent").
			isEqualTo(List.of("C:\\a\\zstd.EXE"));
		requireThat(PackageZstdLookup.windowsCandidates("zstd", Map.of("PATH", ".;C:\\a", "PATHEXT", ".EXE"), true),
			"listedCurrent").isEqualTo(List.of(".\\zstd.EXE", "C:\\a\\zstd.EXE"));
		requireThat(PackageZstdLookup.windowsCandidates("zstd", Map.of("PATH", "", "PATHEXT", ".EXE"), true),
			"emptyPath").isEmpty();
	}

	/**
	 * Joins entries the way Python's {@code ntpath.join} does: no separator after a bare drive or a separator.
	 */
	@Test
	public void joinsWindowsEntries()
	{
		Map<String, String> environment = Map.of("PATH", "C:;D:\\;\\\\server\\share;;E:/x/", "PATHEXT", ".EXE");
		requireThat(PackageZstdLookup.windowsCandidates("zstd", environment, false), "candidates").
			isEqualTo(List.of("C:zstd.EXE", "D:\\zstd.EXE", "\\\\server\\share\\zstd.EXE", "zstd.EXE",
				"E:/x/zstd.EXE"));
	}

	/**
	 * Tries the bare name first only when it already ends with a {@code PATHEXT} suffix, as {@code cmd.exe} does.
	 */
	@Test
	public void ordersWindowsBareName()
	{
		requireThat(PackageZstdLookup.windowsCandidates("zstd", Map.of("PATH", "C:\\a", "PATHEXT", ".EXE;D"), false),
			"bareFirst").isEqualTo(List.of("C:\\a\\zstd", "C:\\a\\zstd.EXE", "C:\\a\\zstdD"));
		requireThat(PackageZstdLookup.windowsCandidates("zstd", Map.of("PATH", "C:\\a", "PATHEXT", ".EXE;.CMD"), false),
			"suffixedOnly").isEqualTo(List.of("C:\\a\\zstd.EXE", "C:\\a\\zstd.CMD"));
	}

	/**
	 * Returns the first executable in search-path order, spelled as the entry and name join.
	 *
	 * @throws IOException if a fixture operation fails
	 */
	@Test
	public void findsFirstExecutable() throws IOException
	{
		Path root = Files.createTempDirectory("package-zstd-lookup-");
		try
		{
			Path first = Files.createDirectory(root.resolve("first dir"));
			Path second = Files.createDirectory(root.resolve("sec\u00f6nd"));
			executable(first.resolve("zstd" + SUFFIX));
			executable(second.resolve("zstd" + SUFFIX));
			executable(second.resolve("dotslash" + SUFFIX));
			Map<String, String> environment = Map.of("PATH", second + File.pathSeparator + first, "PATHEXT", ".EXE");
			requireThat(find(root, environment, "zstd"), "zstd").
				isEqualTo(Optional.of(second + File.separator + "zstd" + SUFFIX));
			requireThat(find(root, environment, "dotslash"), "dotslash").
				isEqualTo(Optional.of(second + File.separator + "dotslash" + SUFFIX));
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Skips a directory, a missing candidate and, where permissions decide, a file that is not executable.
	 *
	 * @throws IOException if a fixture operation fails
	 */
	@Test
	public void skipsUnusableCandidates() throws IOException
	{
		Path root = Files.createTempDirectory("package-zstd-lookup-");
		try
		{
			Path directory = Files.createDirectory(root.resolve("directory"));
			Files.createDirectory(directory.resolve("zstd" + SUFFIX));
			Path plain = Files.createDirectory(root.resolve("plain"));
			if (!WINDOWS)
				Files.createFile(plain.resolve("zstd"));
			Path missing = root.resolve("missing");
			Path good = Files.createDirectory(root.resolve("good"));
			executable(good.resolve("zstd" + SUFFIX));
			String path = String.join(File.pathSeparator, directory.toString(), plain.toString(), missing.toString(),
				good.toString());
			requireThat(find(root, Map.of("PATH", path, "PATHEXT", ".EXE"), "zstd"), "zstd").
				isEqualTo(Optional.of(good + File.separator + "zstd" + SUFFIX));
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Selects a link to an executable by the link's own spelling and skips a link whose target is missing.
	 *
	 * @throws IOException if a fixture operation fails
	 */
	@Test
	public void followsExecutableLinks() throws IOException
	{
		Path root = Files.createTempDirectory("package-zstd-lookup-");
		try
		{
			Path target = Files.createDirectory(root.resolve("target"));
			Path real = executable(target.resolve("real-tool" + SUFFIX));
			Path dangling = Files.createDirectory(root.resolve("dangling"));
			Files.createSymbolicLink(dangling.resolve("zstd" + SUFFIX), root.resolve("nowhere"));
			Path linked = Files.createDirectory(root.resolve("linked"));
			Files.createSymbolicLink(linked.resolve("zstd" + SUFFIX), real);
			String path = dangling + File.pathSeparator + linked;
			requireThat(find(root, Map.of("PATH", path, "PATHEXT", ".EXE"), "zstd"), "zstd").
				isEqualTo(Optional.of(linked + File.separator + "zstd" + SUFFIX));
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Finds nothing for an empty path, searches the working directory for empty entries, and resolves relative
	 * entries against the working directory while keeping their spelling.
	 *
	 * @throws IOException if a fixture operation fails
	 */
	@Test
	public void resolvesRelativeEntries() throws IOException
	{
		Path root = Files.createTempDirectory("package-zstd-lookup-");
		try
		{
			executable(root.resolve("zstd" + SUFFIX));
			Path bin = Files.createDirectory(root.resolve("bin"));
			executable(bin.resolve("dotslash" + SUFFIX));
			requireThat(find(root, Map.of("PATH", "", "PATHEXT", ".EXE"), "zstd"), "emptyPath").isEqualTo(Optional.empty());
			// Windows searches the current directory first, spelled with a leading dot and backslash, unless the process sets
			// NoDefaultCurrentDirectoryInExePath, which is what NeedCurrentDirectoryForExePathW reports.
			String emptyEntryResult = "zstd" + SUFFIX;
			if (WINDOWS && System.getenv("NoDefaultCurrentDirectoryInExePath") == null)
				emptyEntryResult = ".\\" + emptyEntryResult;
			requireThat(find(root, Map.of("PATH", File.pathSeparator, "PATHEXT", ".EXE"), "zstd"), "emptyEntries").
				isEqualTo(Optional.of(emptyEntryResult));
			requireThat(find(root, Map.of("PATH", "bin" + File.separator, "PATHEXT", ".EXE"), "dotslash"), "relative").
				isEqualTo(Optional.of("bin" + File.separator + "dotslash" + SUFFIX));
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Searches the platform default instead of the working directory when the path variable is absent, while an
	 * empty variable searches nothing.
	 *
	 * @throws IOException if a fixture operation fails
	 */
	@Test
	public void distinguishesAbsentPath() throws IOException
	{
		Path root = Files.createTempDirectory("package-zstd-lookup-");
		try
		{
			executable(root.resolve("dotslash" + SUFFIX));
			Optional<String> absent = find(root, Map.of("PATHEXT", ".EXE"), "dotslash");
			if (WINDOWS)
				requireThat(absent, "absent").isEqualTo(Optional.of(".\\dotslash.EXE"));
			else
				requireThat(absent.filter(token -> token.equals("dotslash")), "absent").isEqualTo(Optional.empty());
			requireThat(find(root, Map.of("PATH", "", "PATHEXT", ".EXE"), "dotslash"), "empty").
				isEqualTo(Optional.empty());
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Rejects a command name other than the two that the zstd selection asks for.
	 *
	 * @throws IOException if the search fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = "Unsupported command.*")
	public void rejectsOtherNames() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			new PackageZstdLookup(scope, Path.of(""), Map.of()).find("tar");
		}
	}

	/**
	 * Finds a command through a lookup over an explicit environment.
	 *
	 * @param workingDirectory the directory that relative entries resolve against
	 * @param environment      the process environment
	 * @param name             the command name
	 * @return the selected token, or empty
	 * @throws IOException if the search fails
	 */
	private static Optional<String> find(Path workingDirectory, Map<String, String> environment, String name)
		throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			return new PackageZstdLookup(scope, workingDirectory, environment).find(name);
		}
	}

	/**
	 * Creates an empty executable file.
	 *
	 * @param file the file to create
	 * @return the file
	 * @throws IOException if the file cannot be created or made executable
	 */
	private static Path executable(Path file) throws IOException
	{
		Files.createFile(file);
		requireThat(file.toFile().setExecutable(true), "executableSet").isTrue();
		return file;
	}

	/**
	 * Returns the executable suffix that the fixture environments list in {@code PATHEXT}.
	 *
	 * @return {@code .EXE} on Windows, otherwise the empty string
	 */
	private static String suffix()
	{
		if (System.getProperty("os.name").startsWith("Windows"))
			return ".EXE";
		return "";
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
