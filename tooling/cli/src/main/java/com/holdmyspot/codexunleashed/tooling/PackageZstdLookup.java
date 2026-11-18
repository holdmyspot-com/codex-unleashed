package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandleProxies;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Searches the host's executable search path for the commands that {@link PackageZstd} selects.
 * <p>
 * The search reproduces the Python 3.12 {@code shutil.which} decisions that the retired resolver relied on, for the
 * fixed names {@code zstd} and {@code dotslash}:
 * <ul>
 * <li>An absent {@code PATH} searches the platform default, while an empty {@code PATH} finds nothing.</li>
 * <li>Entries keep their spelling, including empty and relative ones, and the returned token is that spelling joined
 * to the name, not a normalized path. Relative entries resolve against the supplied working directory.</li>
 * <li>On Windows the {@code PATHEXT} suffixes are appended to the name, the current directory is searched first when
 * {@code NeedCurrentDirectoryForExePathW} says so, and directories are compared without regard to case.</li>
 * <li>A candidate qualifies when it exists, is not a directory and is executable, each decided after following
 * links.</li>
 * </ul>
 * The environment and working directory are fixed at construction, so a lookup is thread-safe.
 */
public final class PackageZstdLookup implements PackageZstd.Executables
{
	/**
	 * Platform search path that Windows uses when {@code PATH} is absent.
	 */
	private static final String WINDOWS_DEFAULT_PATH = ".;C:\\bin";
	/**
	 * Executable suffixes that Windows uses when {@code PATHEXT} is absent or empty.
	 */
	private static final String WINDOWS_DEFAULT_SUFFIXES = ".COM;.EXE;.BAT;.CMD;.VBS;.JS;.WS;.MSC";
	/**
	 * The {@code _CS_PATH} selector of {@code confstr} on Linux.
	 */
	private static final int LINUX_CS_PATH = 0;
	/**
	 * The {@code _CS_PATH} selector of {@code confstr} on Darwin.
	 */
	private static final int DARWIN_CS_PATH = 1;
	/** The command names that this lookup answers for. */
	private static final Set<String> NAMES = Set.of("zstd", "dotslash");
	/** Platform whose conventions this lookup applies, fixed at construction. */
	private final boolean windows = System.getProperty("os.name").startsWith("Windows");
	/** Provides the platform linker that native queries borrow. */
	private final ToolingScope scope;
	/** Directory that relative candidates resolve against. */
	private final Path workingDirectory;
	/** Process environment whose search variables decide the search. */
	private final Map<String, String> environment;

	/**
	 * Creates a lookup for one process environment.
	 *
	 * @param scope            borrowed shared values, kept open while the lookup is used
	 * @param workingDirectory the directory that relative search-path entries resolve against
	 * @param environment      the complete process environment; its search variables are read when a name is found
	 * @throws NullPointerException if an argument is null
	 */
	public PackageZstdLookup(ToolingScope scope, Path workingDirectory, Map<String, String> environment)
	{
		this.scope = Objects.requireNonNull(scope, "scope");
		this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
		this.environment = Map.copyOf(environment);
	}

	/**
	 * Calls the platform's {@code confstr}, which returns a configuration string.
	 */
	@FunctionalInterface
	public interface ConfigurationString
	{
		/**
		 * Copies a configuration string into a buffer.
		 *
		 * @param name   the configuration selector
		 * @param buffer the destination, or NULL to ask for the required size
		 * @param size   the destination size in bytes
		 * @return the size that the complete string needs, including its terminator, or zero when it is unset
		 */
		long invoke(int name, MemorySegment buffer, long size);
	}

	/**
	 * Calls the Windows {@code NeedCurrentDirectoryForExePathW}, which decides whether the current directory is
	 * searched for an executable name.
	 */
	@FunctionalInterface
	public interface CurrentDirectoryPolicy
	{
		/**
		 * Asks whether the current directory is searched for the executable.
		 *
		 * @param name the null-terminated UTF-16 executable name
		 * @return nonzero when the current directory is searched
		 */
		int invoke(MemorySegment name);
	}

	/**
	 * Finds the first qualifying candidate on the search path.
	 * <p>
	 * A qualifying executable is selected even if it later fails to run. A native query that cannot be bound or run
	 * fails the search instead of reporting the command absent.
	 *
	 * @param name the command name, either {@code zstd} or {@code dotslash}
	 * @return the token that names the first qualifying candidate, or empty when the search path holds none
	 * @throws NullPointerException     if {@code name} is null
	 * @throws IllegalArgumentException if {@code name} is not a supported command name
	 * @throws IOException              if the platform search path cannot be read
	 */
	@Override
	public Optional<String> find(String name) throws IOException
	{
		Objects.requireNonNull(name, "name");
		if (!NAMES.contains(name))
			throw new IllegalArgumentException("Unsupported command name " + name + "; expected one of " + NAMES);

		List<String> candidates;
		if (windows)
			candidates = windowsCandidates(name, environment, needsCurrentDirectory(name));
		else
		{
			String path = environment.get("PATH");
			if (path == null)
				path = defaultPosixPath();
			candidates = posixCandidates(name, path);
		}
		for (String candidate : candidates)
			if (qualifies(candidate))
				return Optional.of(candidate);
		return Optional.empty();
	}

	/**
	 * Lists the POSIX candidates for a search path in search order.
	 *
	 * @param name the command name
	 * @param path the search path, with entries separated by colons; empty entries mean the current directory
	 * @return the tokens to check, each entry's first spelling only, and none for an empty {@code path}
	 */
	public static List<String> posixCandidates(String name, String path)
	{
		Set<String> directories = new LinkedHashSet<>();
		if (!path.isEmpty())
			directories.addAll(List.of(path.split(":", -1)));
		List<String> candidates = new ArrayList<>();
		for (String directory : directories)
		{
			if (directory.isEmpty())
				candidates.add(name);
			else if (directory.endsWith("/"))
				candidates.add(directory + name);
			else
				candidates.add(directory + "/" + name);
		}
		return candidates;
	}

	/**
	 * Lists the Windows candidates for an environment in search order.
	 *
	 * @param name             the command name
	 * @param environment      the process environment, whose variable names are matched without regard to case
	 * @param currentDirectory whether the current directory is searched before the entries of {@code PATH}
	 * @return the tokens to check; a directory is checked once, however its spelling differs in case or separators,
	 *         and none is returned for an empty {@code PATH}
	 */
	public static List<String> windowsCandidates(String name, Map<String, String> environment,
		boolean currentDirectory)
	{
		String path = windowsVariable(environment, "PATH");
		if (path == null)
			path = WINDOWS_DEFAULT_PATH;
		if (path.isEmpty())
			return List.of();

		List<String> directories = new ArrayList<>();
		if (currentDirectory)
			directories.add(".");
		directories.addAll(List.of(path.split(";", -1)));

		String suffixText = windowsVariable(environment, "PATHEXT");
		if (suffixText == null || suffixText.isEmpty())
			suffixText = WINDOWS_DEFAULT_SUFFIXES;
		List<String> suffixes = new ArrayList<>();
		for (String suffix : suffixText.split(";", -1))
			if (!suffix.isEmpty())
				suffixes.add(suffix.replaceAll("[.]+$", ""));
		List<String> names = new ArrayList<>();
		for (String suffix : suffixes)
			names.add(name + suffix);
		// cmd.exe runs a bare name only when it already ends in a PATHEXT suffix, and then before the suffixed names.
		String upperName = name.toUpperCase(Locale.ROOT);
		for (String suffix : suffixes)
		{
			if (upperName.endsWith(suffix.toUpperCase(Locale.ROOT)))
			{
				names.addFirst(name);
				break;
			}
		}

		Set<String> seen = new LinkedHashSet<>();
		List<String> candidates = new ArrayList<>();
		for (String directory : directories)
		{
			if (!seen.add(directory.replace('/', '\\').toLowerCase(Locale.ROOT)))
				continue;
			for (String candidate : names)
				candidates.add(joinWindows(directory, candidate));
		}
		return candidates;
	}

	/**
	 * Reads a Windows environment variable without regard to the case of its name.
	 *
	 * @param environment the process environment
	 * @param name        the variable name
	 * @return the value, or null when the variable is absent, which differs from an empty value
	 */
	private static String windowsVariable(Map<String, String> environment, String name)
	{
		for (Map.Entry<String, String> entry : environment.entrySet())
			if (entry.getKey().equalsIgnoreCase(name))
				return entry.getValue();
		return null;
	}

	/**
	 * Joins a directory and a file name the way Python's {@code ntpath.join} does for these inputs.
	 *
	 * @param directory a {@code PATH} entry
	 * @param name      the file name
	 * @return the name alone for an empty entry, the entry and name when it already ends in a separator or is a bare
	 *         drive such as {@code C:}, and otherwise the entry, a backslash and the name
	 */
	private static String joinWindows(String directory, String name)
	{
		if (directory.isEmpty())
			return name;
		char last = directory.charAt(directory.length() - 1);
		boolean bareDrive = directory.length() == 2 && last == ':';
		if (last == '\\' || last == '/' || bareDrive)
			return directory + name;
		return directory + "\\" + name;
	}

	/**
	 * Decides whether a candidate exists, is not a directory and is executable, after following links.
	 *
	 * @param candidate the token to check
	 * @return true if the candidate qualifies; a token that is not a valid path never qualifies
	 */
	private boolean qualifies(String candidate)
	{
		try
		{
			Path file = workingDirectory.resolve(Path.of(candidate));
			return Files.exists(file) && Files.isExecutable(file) && !Files.isDirectory(file);
		}
		catch (InvalidPathException _)
		{
			return false;
		}
	}

	/**
	 * Reads the search path that POSIX hosts use when {@code PATH} is absent.
	 *
	 * @return the platform's {@code _CS_PATH}
	 * @throws IOException if the platform is not Linux or macOS, or the platform has no such path
	 */
	// The launcher grants this module restricted FFM access; the fixed confstr signature uses a confined buffer.
	@SuppressWarnings("restricted")
	private String defaultPosixPath() throws IOException
	{
		String operatingSystem = System.getProperty("os.name");
		int selector;
		if (operatingSystem.equals("Linux"))
			selector = LINUX_CS_PATH;
		else if (operatingSystem.equals("Mac OS X"))
			selector = DARWIN_CS_PATH;
		else
			throw new IOException("No default search path is known for " + operatingSystem);
		Linker linker = scope.getNativeLinker();
		ConfigurationString confstr = MethodHandleProxies.asInterfaceInstance(ConfigurationString.class,
			linker.downcallHandle(linker.defaultLookup().find("confstr").
				orElseThrow(() -> new UnsupportedOperationException("The POSIX runtime does not provide confstr")),
				FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
					ValueLayout.JAVA_LONG)));
		long size = confstr.invoke(selector, MemorySegment.NULL, 0L);
		if (size <= 0)
			throw new IOException("The platform does not define a default executable search path");
		try (Arena arena = Arena.ofConfined())
		{
			MemorySegment buffer = arena.allocate(size);
			long written = confstr.invoke(selector, buffer, size);
			if (written != size)
				throw new IOException("The platform default executable search path changed while it was read");
			return buffer.getString(0, UTF_8);
		}
	}

	/**
	 * Asks Windows whether the current directory is searched for an executable name.
	 * <p>
	 * Only the platform can answer: it reads the process's {@code NoDefaultCurrentDirectoryInExePath} setting, which
	 * is not the supplied environment.
	 *
	 * @param name the command name
	 * @return true if the current directory is searched first
	 */
	// Restricted FFM access is granted to this module by the launcher; the fixed signature uses a confined argument.
	@SuppressWarnings("restricted")
	private boolean needsCurrentDirectory(String name)
	{
		if (ValueLayout.ADDRESS.byteSize() != Long.BYTES)
			throw new UnsupportedOperationException("The Windows executable search requires a 64-bit runtime");
		Linker linker = scope.getNativeLinker();
		SymbolLookup symbols = SymbolLookup.libraryLookup("Kernel32", Arena.global());
		CurrentDirectoryPolicy policy = MethodHandleProxies.asInterfaceInstance(CurrentDirectoryPolicy.class,
			linker.downcallHandle(symbols.find("NeedCurrentDirectoryForExePathW").orElseThrow(() ->
				new UnsupportedOperationException("The Windows runtime does not provide NeedCurrentDirectoryForExePathW")),
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)));
		try (Arena arena = Arena.ofConfined())
		{
			MemorySegment argument = arena.allocateFrom(ValueLayout.JAVA_CHAR, (name + '\0').toCharArray());
			return policy.invoke(argument) != 0;
		}
	}
}
