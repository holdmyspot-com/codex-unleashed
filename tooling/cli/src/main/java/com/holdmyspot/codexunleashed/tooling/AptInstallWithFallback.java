package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs apt package installation commands and reports their status to the caller.
 */
public final class AptInstallWithFallback
{
	/**
	 * The maintained package installation command name.
	 */
	public static final String NAME = "apt-install-with-fallback";

	/**
	 * Adds the architecture used to select an Ubuntu mirror after the primary apt attempt fails.
	 */
	private static final String ARCHITECTURE_OPTION = "--architecture";

	/**
	 * Adds the apt configuration directory.
	 */
	private static final String APT_DIRECTORY_OPTION = "--apt-dir";

	/**
	 * Adds the apt-get executable.
	 */
	private static final String APT_GET_OPTION = "--apt-get";

	/**
	 * Image apt configuration root referenced by mirror+file source entries.
	 */
	private static final Path IMAGE_APT_DIRECTORY = Path.of("/etc/apt");

	/**
	 * Matches the image's single-URI deb822 source lines.
	 */
	private static final Pattern SOURCE_URIS = Pattern.compile("^URIs:\\s*(\\S+)\\s*$", Pattern.MULTILINE);

	/**
	 * Matches the Ubuntu archive hosts accepted by the existing runner images.
	 */
	private static final Pattern UBUNTU_URI = Pattern.compile(
		"^https?://[a-z0-9.-]*ubuntu\\.com/(ubuntu|ubuntu-ports)/?$");

	/**
	 * Mirrors used when the image's Ubuntu archive hosts fail.
	 */
	private static final String UBUNTU_FALLBACK = "https://mirrors.edge.kernel.org/ubuntu/";

	/**
	 * Mirror used for Ubuntu ports when the image's Ubuntu archive hosts fail.
	 */
	private static final String PORTS_FALLBACK = "https://mirrors.ocf.berkeley.edu/ubuntu-ports/";

	/**
	 * Prevents construction.
	 */
	private AptInstallWithFallback()
	{
	}

	/**
	 * Runs apt with the process streams inherited from the caller.
	 *
	 * @param arguments command options followed by package names
	 * @param err       diagnostics
	 * @return zero when the update and installation succeed, two for invalid command arguments, or the failed apt status
	 * @throws NullPointerException if an argument or diagnostic stream is null
	 * @throws IOException          if a command cannot be started or its result cannot be read
	 */
	public static int run(String[] arguments, PrintStream err) throws IOException
	{
		return run(arguments, err, AptInstallWithFallback::runSystemProcess);
	}

	/**
	 * Runs the primary apt update and package installation, with process execution supplied by the caller.
	 *
	 * @param arguments command options followed by package names
	 * @param err       diagnostics
	 * @param process   executes an apt or architecture command and returns its result
	 * @return zero when the update and installation succeed, two for invalid command arguments, or the failed apt status
	 * @throws NullPointerException if an argument, diagnostic stream, process, or argument element is null
	 * @throws IOException          if a command cannot be started or its result cannot be read
	 */
	public static int run(String[] arguments, PrintStream err, AptProcess process) throws IOException
	{
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(err, "err");
		Objects.requireNonNull(process, "process");

		Options options;
		try
		{
			options = parse(arguments);
		}
		catch (IllegalArgumentException failure)
		{
			err.println("ERROR: " + failure.getMessage());
			usage(err);
			return 2;
		}

		try
		{
			int status = updateAndInstall(options, process);
			if (status == 0)
				return 0;

			String architecture = options.architecture();
			if (architecture == null || architecture.isEmpty())
			{
				AptProcessResult result = process.run(new AptProcessRequest(List.of("dpkg", "--print-architecture"),
					Map.of(), OutputHandling.CAPTURE));
				if (result.status() != 0)
					return 1;
				architecture = result.stdout().strip();
			}

		String fallback;
		if (architecture.equals("amd64") || architecture.equals("i386"))
			fallback = UBUNTU_FALLBACK;
		else
			fallback = PORTS_FALLBACK;
			if (!switchToFallback(options.aptDirectory(), fallback))
			{
				err.println("apt-get failed with exit code " + status + ", and no Ubuntu archive source was found in " +
					options.aptDirectory().resolve("sources.list.d") + " to retry from a fallback mirror");
				return status;
			}

			err.println("apt-get failed with exit code " + status + "; retrying from " + fallback);
			return updateAndInstall(options, process);
		}
		catch (IOException failure)
		{
			err.println("ERROR: " + failure.getMessage());
			return 1;
		}
	}

	/**
	 * Runs an apt update and then install, stopping at the first failure.
	 *
	 * @param options parsed command arguments
	 * @param process apt command executor
	 * @return zero or the first failed apt process status
	 * @throws IOException if a process cannot be started or captured
	 */
	private static int updateAndInstall(Options options, AptProcess process) throws IOException
	{
		Map<String, String> environment = Map.of("DEBIAN_FRONTEND", "noninteractive");
		AptProcessResult update = process.run(new AptProcessRequest(
			List.of(options.aptGet(), "update", "-y"), environment, OutputHandling.INHERIT));
		if (update.status() != 0)
			return update.status();

		List<String> installCommand = new ArrayList<>(List.of(options.aptGet(), "install", "-y",
			"--no-install-recommends"));
		installCommand.addAll(options.packages());
		return process.run(new AptProcessRequest(installCommand, environment, OutputHandling.INHERIT)).status();
	}

	/**
	 * Changes only Ubuntu archive URIs in sorted deb822 source files.
	 *
	 * @param aptDirectory source configuration root
	 * @param fallback replacement archive URL
	 * @return true if at least one source file changed
	 * @throws IOException if a source or mirror list cannot be read or written
	 */
	private static boolean switchToFallback(Path aptDirectory, String fallback) throws IOException
	{
		Path sourcesDirectory = aptDirectory.resolve("sources.list.d");
		if (Files.notExists(sourcesDirectory))
			return false;

		boolean changed = false;
		List<Path> sourceFiles;
		try (Stream<Path> entries = Files.list(sourcesDirectory))
		{
			sourceFiles = entries.filter(path -> path.getFileName().toString().endsWith(".sources")).sorted().toList();
		}
		for (Path sourceFile : sourceFiles)
		{
			String original = Files.readString(sourceFile, StandardCharsets.UTF_8);
			Matcher matcher = SOURCE_URIS.matcher(original);
			StringBuffer replacement = new StringBuffer();
			while (matcher.find())
			{
				String uri = matcher.group(1);
				String line;
				if (isUbuntuSource(uri, aptDirectory))
					line = "URIs: " + fallback;
				else
					line = matcher.group();
				matcher.appendReplacement(replacement, Matcher.quoteReplacement(line));
			}
			matcher.appendTail(replacement);
			String updated = replacement.toString();
			if (!updated.equals(original))
			{
				Files.writeString(sourceFile, updated, StandardCharsets.UTF_8);
				changed = true;
			}
		}
		return changed;
	}

	/**
	 * Checks whether a source points only at Ubuntu archive hosts.
	 *
	 * @param uri source URI
	 * @param aptDirectory supplied apt root used to map image mirror-list paths
	 * @return true for a direct Ubuntu archive URI or an all-Ubuntu mirror list
	 * @throws IOException if a referenced mirror list cannot be read
	 */
	private static boolean isUbuntuSource(String uri, Path aptDirectory) throws IOException
	{
		if (UBUNTU_URI.matcher(uri).matches())
			return true;
		String mirrorPrefix = "mirror+file:";
		if (!uri.startsWith(mirrorPrefix))
			return false;
		Path mirrorList = Path.of(uri.substring(mirrorPrefix.length()));
		if (!mirrorList.startsWith(IMAGE_APT_DIRECTORY))
			return false;
		Path mappedList = aptDirectory.resolve(IMAGE_APT_DIRECTORY.relativize(mirrorList));
		String[] lines = Files.readString(mappedList, StandardCharsets.UTF_8).split("\\R", -1);
		List<String> entries = new ArrayList<>();
		for (String line : lines)
		{
			if (!line.isEmpty())
				entries.add(line.split("\\t", 2)[0]);
		}
		return !entries.isEmpty() && entries.stream().allMatch(entry -> UBUNTU_URI.matcher(entry).matches());
	}

	/**
	 * Executes the actual apt command with inherited streams and the caller's environment.
	 *
	 * @param request command, environment overrides and stream handling
	 * @return the command's status and captured output when requested
	 * @throws IOException if the command cannot be started or captured
	 */
	private static AptProcessResult runSystemProcess(AptProcessRequest request) throws IOException
	{
		Path workingDirectory = Path.of("").toAbsolutePath();
		if (request.outputHandling() == OutputHandling.INHERIT)
		{
			Map<String, String> environment = new HashMap<>(System.getenv());
			environment.putAll(request.environmentOverrides());
			int status = SystemCommands.execute(request.command(), workingDirectory, environment);
			return new AptProcessResult(status, "", "");
		}

		Path temporaryDirectory = Path.of(System.getProperty("java.io.tmpdir"));
		SystemCommands.Result result = SystemCommands.capture(request.command(), workingDirectory, temporaryDirectory,
			request.environmentOverrides());
		return new AptProcessResult(result.status(), result.stdout(), result.stderr());
	}

	/**
	 * Parses the command's options and required package names.
	 *
	 * @param arguments command options followed by package names
	 * @return parsed apt directory, optional architecture, executable and packages
	 * @throws IllegalArgumentException if an option is missing a value or no package is supplied
	 */
	private static Options parse(String[] arguments)
	{
		Path aptDirectory = Path.of("/etc/apt");
		String architecture = null;
		String aptGet = "apt-get";
		List<String> packages = new ArrayList<>();
		int index = 0;
		while (index < arguments.length)
		{
			String argument = arguments[index];
			index = index + 1;
			if (argument.equals(APT_DIRECTORY_OPTION))
			{
				if (index >= arguments.length)
					throw new IllegalArgumentException(APT_DIRECTORY_OPTION + " requires a directory");
				aptDirectory = Path.of(arguments[index]);
				index = index + 1;
			}
			else if (argument.equals(ARCHITECTURE_OPTION))
			{
				if (index >= arguments.length)
					throw new IllegalArgumentException(ARCHITECTURE_OPTION + " requires an architecture");
				architecture = arguments[index];
				index = index + 1;
			}
			else if (argument.equals(APT_GET_OPTION))
			{
				if (index >= arguments.length)
					throw new IllegalArgumentException(APT_GET_OPTION + " requires an executable");
				aptGet = arguments[index];
				index = index + 1;
			}
			else if (argument.startsWith("--"))
				throw new IllegalArgumentException("Unknown option " + argument);
			else
				packages.add(argument);
		}
		if (packages.isEmpty())
			throw new IllegalArgumentException("At least one package is required");
		return new Options(aptDirectory, architecture, aptGet, packages);
	}

	/**
	 * Writes the command's supported syntax.
	 *
	 * @param err diagnostics
	 */
	private static void usage(PrintStream err)
	{
		err.println("Usage: " + NAME + " [--apt-dir DIR=/etc/apt] [--architecture ARCH] " +
			"[--apt-get EXEC=apt-get] PACKAGE...");
	}

	/**
	 * Selects whether a command's output is inherited or captured for a decision.
	 */
	public enum OutputHandling
	{
		/**
		 * Inherits the caller's standard streams.
		 */
		INHERIT,
		/**
		 * Captures process output for a command decision.
		 */
		CAPTURE
	}

	/**
	 * The typed request sent to the apt process collaborator.
	 *
	 * @param command            executable and literal arguments
	 * @param environmentOverrides variables added to the inherited environment
	 * @param outputHandling     whether output is inherited or captured
	 */
	public record AptProcessRequest(List<String> command, Map<String, String> environmentOverrides,
		OutputHandling outputHandling)
	{
		/**
		 * Creates an immutable process request.
		 *
		 * @param command             executable and literal arguments
		 * @param environmentOverrides variables added to the inherited environment
		 * @param outputHandling      whether output is inherited or captured
		 * @throws NullPointerException if any request value is null
		 */
		public AptProcessRequest
		{
			command = List.copyOf(command);
			environmentOverrides = Map.copyOf(environmentOverrides);
			Objects.requireNonNull(outputHandling, "outputHandling");
		}
	}

	/**
	 * A completed apt or architecture command.
	 *
	 * @param status the process exit status
	 * @param stdout captured standard output, or an empty string when output is inherited
	 * @param stderr captured standard error, or an empty string when output is inherited
	 */
	public record AptProcessResult(int status, String stdout, String stderr)
	{
		/**
		 * Creates a completed command result.
		 *
		 * @param status the process exit status
		 * @param stdout captured standard output, or an empty string when output is inherited
		 * @param stderr captured standard error, or an empty string when output is inherited
		 * @throws NullPointerException if either output is null
		 */
		public AptProcessResult
		{
			Objects.requireNonNull(stdout, "stdout");
			Objects.requireNonNull(stderr, "stderr");
		}
	}

	/**
	 * Executes one typed apt or architecture process request.
	 */
	@FunctionalInterface
	public interface AptProcess
	{
		/**
		 * Executes a command and returns its status and any captured output.
		 *
		 * @param request the command to execute
		 * @return the process result
		 * @throws IOException if the command cannot be started or its output cannot be captured
		 */
		AptProcessResult run(AptProcessRequest request) throws IOException;
	}

	/**
	 * The parsed command options and package names.
	 *
	 * @param aptDirectory apt's source configuration directory
	 * @param architecture supplied architecture, or null when it must be read after failure
	 * @param aptGet apt-get executable
	 * @param packages package names in caller order
	 */
	private record Options(Path aptDirectory, String architecture, String aptGet, List<String> packages)
	{
		/**
		 * Copies package names to make parsed command options immutable.
		 *
		 * @param aptDirectory apt's source configuration directory
		 * @param architecture supplied architecture, or null when it must be read after failure
		 * @param aptGet apt-get executable
		 * @param packages package names in caller order
		 */
		private Options
		{
			packages = List.copyOf(packages);
		}
	}
}
