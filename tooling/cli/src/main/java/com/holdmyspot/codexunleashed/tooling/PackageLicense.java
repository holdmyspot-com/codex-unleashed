package com.holdmyspot.codexunleashed.tooling;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * Stages supplied legal documents and verifies their bytes in canonical package archives.
 */
public final class PackageLicense
{
	/**
	 * Required downstream legal documents.
	 */
	private static final List<String> DOWNSTREAM_DOCUMENTS = List.of("LICENSE.md", "docs/LICENSE.html", "docs/terms.html",
		"docs/privacy.html", "licenses/openai-codex/LICENSE-APACHE-2.0");
	/**
	 * Required upstream legal notices.
	 */
	private static final List<String> UPSTREAM_NOTICES = List.of("LICENSE", "NOTICE",
		"codex-rs/skills/src/assets/samples/imagegen/LICENSE.txt",
		"codex-rs/skills/src/assets/samples/openai-docs/LICENSE.txt",
		"codex-rs/skills/src/assets/samples/skill-creator/license.txt",
		"codex-rs/skills/src/assets/samples/skill-installer/LICENSE.txt", "codex-rs/vendor/bubblewrap/COPYING",
		"third_party/voice/NOTICE.md", "third_party/wezterm/LICENSE");
	/**
	 * Archive-relative legal material directory.
	 */
	private static final String LEGAL_DIRECTORY = "codex-resources/legal";
	/**
	 * Maximum allowed size of one legal document.
	 */
	private static final int MAX_DOCUMENT_BYTES = 2 * 1024 * 1024;
	/**
	 * Maximum allowed number of legal sources.
	 */
	private static final int MAX_LEGAL_FILES = 64;
	/**
	 * Maximum allowed archive member count.
	 */
	private static final int MAX_ARCHIVE_MEMBERS = 10_000;
	/**
	 * Canonical package variants whose archives are finalized.
	 */
	private static final List<String> PACKAGE_VARIANTS = List.of("codex", "codex-app-server");
	/**
	 * Canonical archive formats created by the upstream writer.
	 */
	private static final List<String> ARCHIVE_SUFFIXES = List.of(".tar.gz", ".tar.zst");
	/**
	 * Upstream module that owns archive writing.
	 */
	private static final String ARCHIVE_MODULE = "codex_package.archive";

	/**
	 * A completed command with separated output, suitable for a typed process boundary.
	 *
	 * @param status process exit status
	 * @param stdout standard output text
	 * @param stderr standard error text
	 */
	public record CommandResult(int status, String stdout, String stderr)
	{
		/**
		 * Creates a command result.
		 *
		 * @param status process exit status
		 * @param stdout standard output text
		 * @param stderr standard error text
		 */
		public CommandResult
		{
			Objects.requireNonNull(stdout, "stdout");
			Objects.requireNonNull(stderr, "stderr");
		}
	}

	/**
	 * Executes upstream archive commands and streams a decompressor's stdout to an owned file.
	 */
	public interface ArchiveProcesses
	{
		/**
		 * Captures the command's UTF-8 output.
		 *
		 * @param command argument vector
		 * @param workingDirectory command working directory
		 * @param temporaryDirectory directory for captured output
		 * @param environment environment additions
		 * @return process status and captured output
		 * @throws IOException if the command cannot be run
		 */
		CommandResult capture(List<String> command, Path workingDirectory, Path temporaryDirectory,
			Map<String, String> environment) throws IOException;

		/**
		 * Runs a command with stdout written to the specified file.
		 *
		 * @param command argument vector
		 * @param workingDirectory command working directory
		 * @param environment environment additions
		 * @param standardOutput destination for standard output
		 * @throws IOException if the command cannot be run successfully
		 */
		void runToFile(List<String> command, Path workingDirectory, Map<String, String> environment,
			Path standardOutput) throws IOException;
	}

	/**
	 * Prevents construction.
	 */
	private PackageLicense()
	{
	}

	/**
	 * Runs stage, check, or finalize with explicitly supplied process environment.
	 *
	 * @param scope borrowed shared parsers; the caller retains ownership
	 * @param arguments command arguments without the command name
	 * @param input stdin for the special check archive value "-"; remains open
	 * @param environment process environment supplied by composition
	 * @param out success output
	 * @param err diagnostics
	 * @return zero on success, one on rejected legal content or operation failure, or two for invalid arguments
	 */
	public static int run(ToolingScope scope, String[] arguments, InputStream input, Map<String, String> environment,
		PrintStream out, PrintStream err)
	{
		return run(scope, arguments, input, environment, new SystemArchiveProcesses(), out, err);
	}

	/**
	 * Runs the command through a supplied process boundary for focused policy verification.
	 *
	 * @param scope borrowed shared parsers; the caller retains ownership
	 * @param arguments command arguments without the command name
	 * @param input stdin for the special check archive value "-"; remains open
	 * @param environment process environment supplied by composition
	 * @param processes ordinary process boundary for upstream commands
	 * @param out success output
	 * @param err diagnostics
	 * @return zero on success, one on rejected legal content or operation failure, or two for invalid arguments
	 */
	public static int run(ToolingScope scope, String[] arguments, InputStream input, Map<String, String> environment,
		ArchiveProcesses processes, PrintStream out, PrintStream err)
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(environment, "environment");
		return run(scope, arguments, input, environment, processes,
			directory -> new PackageZstdLookup(scope, directory, environment), out, err);
	}

	/**
	 * Runs the command through supplied process and executable-search boundaries for focused policy verification.
	 *
	 * @param scope borrowed shared parsers; the caller retains ownership
	 * @param arguments command arguments without the command name
	 * @param input stdin for the special check archive value "-"; remains open
	 * @param environment process environment supplied by composition
	 * @param processes ordinary process boundary for upstream commands
	 * @param executables creates the host executable search for the upstream {@code scripts} directory
	 * @param out success output
	 * @param err diagnostics
	 * @return zero on success, one on rejected legal content or operation failure, or two for invalid arguments
	 */
	public static int run(ToolingScope scope, String[] arguments, InputStream input, Map<String, String> environment,
		ArchiveProcesses processes, Function<Path, PackageZstd.Executables> executables, PrintStream out,
		PrintStream err)
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(input, "input");
		Objects.requireNonNull(environment, "environment");
		Objects.requireNonNull(processes, "processes");
		Objects.requireNonNull(executables, "executables");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		if (Arrays.asList(arguments).contains("--help"))
		{
			out.println("Usage: package-license <stage|check|finalize> --metadata-root PATH " +
			"--upstream-root PATH [mode options]");
			return 0;
		}
		Options options;
		try
		{
			options = parse(arguments);
		}
		catch (IllegalArgumentException failure)
		{
			err.println("ERROR: " + failure.getMessage());
			return 2;
		}

		try
		{
			int documentCount;
			if (options.mode().equals("stage"))
			{
				Map<String, byte[]> documents = suppliedDocuments(options.metadataRoot(), options.upstreamRoot());
				stageDocuments(options.packageDirectory(), documents);
				documentCount = documents.size();
			}
			else if (options.mode().equals("check"))
			{
				Map<String, byte[]> documents = suppliedDocuments(options.metadataRoot(), options.upstreamRoot());
				if (options.archive().equals("-"))
					checkArchive(nonClosing(input), documents);
				else
					try (InputStream archive = Files.newInputStream(Path.of(options.archive())))
					{
						checkArchive(archive, documents);
					}
				documentCount = documents.size();
			}
			else
			{
				List<String> zstdCommand = resolveZstd(options.upstreamRoot(), executables);
				documentCount = finalizePackages(options, environment, processes, zstdCommand);
			}
			out.println("Package legal content " + options.mode() + " passed (" + documentCount + " documents)");
			return 0;
		}
		catch (IOException | IllegalArgumentException failure)
		{
			String prefix = "Package legal content rejected";
			if (options.mode().equals("finalize"))
				prefix = "Package legal finalization failed";
			err.println(prefix + ": " + failure.getMessage());
			return 1;
		}
	}

	/**
	 * Checks the supplied legal members in a streaming tar reader without extracting archive paths.
	 *
	 * @param source gzip-compressed or uncompressed tar bytes; closed by this method
	 * @param documents required archive-relative legal members
	 * @throws IOException if reading fails or a required member is invalid
	 */
	public static void checkArchive(InputStream source, Map<String, byte[]> documents) throws IOException
	{
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(documents, "documents");
		Set<String> seen = new HashSet<>();
		Set<String> remaining = new HashSet<>(documents.keySet());
		try (InputStream decoded = maybeGunzip(source); TarArchiveInputStream archive = new TarArchiveInputStream(decoded))
		{
			TarArchiveEntry entry = archive.getNextEntry();
			while (entry != null)
			{
				if (seen.size() >= MAX_ARCHIVE_MEMBERS)
					throw new IOException("Archive exceeds " + MAX_ARCHIVE_MEMBERS + " members");
				String name = canonicalName(entry.getName());
				if (!seen.add(name))
					throw new IOException("Duplicate archive member: " + name);
				if (entry.isLink() || entry.isSymbolicLink() || (!entry.isFile() && !entry.isDirectory()))
					throw new IOException("Archive member is not a regular file or directory: " + name);
				byte[] expected = documents.get(name);
				if (expected != null)
				{
					if (!entry.isFile() || entry.getSize() != expected.length)
						throw new IOException("Legal document differs: " + name);
					byte[] actual = archive.readNBytes(MAX_DOCUMENT_BYTES + 1);
					if (!Arrays.equals(actual, expected))
						throw new IOException("Legal document differs: " + name);
					remaining.remove(name);
				}
				entry = archive.getNextEntry();
			}
		}
		if (!remaining.isEmpty())
			throw new IOException("Missing legal documents: " + String.join(", ", new java.util.TreeSet<>(remaining)));
	}

	/**
	 * Parses and validates command arguments.
	 *
	 * @param arguments command name and options
	 * @return validated command options
	 * @throws IllegalArgumentException if options are missing, conflicting, or invalid
	 */
	private static Options parse(String[] arguments)
	{
		if (arguments.length == 0 || !List.of("stage", "check", "finalize").contains(arguments[0]))
			throw new IllegalArgumentException("Usage: package-license <stage|check|finalize> options");
		Map<String, String> values = new LinkedHashMap<>();
		Set<String> allowed = Set.of("--metadata-root", "--upstream-root", "--package-dir", "--archive", "--package-root",
			"--archive-root", "--target");
		for (int index = 1; index < arguments.length; index = index + 1)
		{
			String argument = arguments[index];
			int equals = argument.indexOf('=');
			String name = argument;
			if (equals >= 0)
				name = argument.substring(0, equals);
			if (!allowed.contains(name))
				throw new IllegalArgumentException("Unknown option: " + name);
			String value;
			if (equals >= 0)
				value = argument.substring(equals + 1);
			else
			{
				index = index + 1;
				if (index < arguments.length)
					value = arguments[index];
				else
					throw new IllegalArgumentException(name + " requires a value");
			}
			values.put(name, value);
		}
		String mode = arguments[0];
		String metadata = required(values, "--metadata-root");
		String upstream = required(values, "--upstream-root");
		String packageDirectory = values.get("--package-dir");
		String archive = values.get("--archive");
		String packageRoot = values.get("--package-root");
		String archiveRoot = values.get("--archive-root");
		String target = values.get("--target");
		if (mode.equals("stage") && (packageDirectory == null || archive != null || packageRoot != null ||
			archiveRoot != null || target != null))
			throw new IllegalArgumentException("Stage requires --package-dir and excludes archive and finalization options");
		if (mode.equals("check") && (archive == null || packageDirectory != null || packageRoot != null ||
			archiveRoot != null || target != null))
			throw new IllegalArgumentException("Check requires --archive and excludes package and finalization options");
		if (mode.equals("finalize") && (packageDirectory != null || archive != null || packageRoot == null ||
			archiveRoot == null || target == null))
			throw new IllegalArgumentException("Finalize requires --package-root, --archive-root, and --target; " +
				"it excludes --package-dir and --archive");
		return new Options(mode, Path.of(metadata), Path.of(upstream), path(packageDirectory), archive,
			path(packageRoot), path(archiveRoot), target);
	}

	/**
	 * Returns a required option value.
	 *
	 * @param values parsed option values
	 * @param name option name
	 * @return option value
	 * @throws IllegalArgumentException if the option is absent
	 */
	private static String required(Map<String, String> values, String name)
	{
		String value = values.get(name);
		if (value == null)
			throw new IllegalArgumentException(name + " is required");
		return value;
	}

	/**
	 * Converts an optional path argument.
	 *
	 * @param value path text or null
	 * @return the parsed path or null
	 */
	private static Path path(String value)
	{
		if (value == null)
			return null;
		return Path.of(value);
	}

	/**
	 * Reads and validates all supplied legal source files.
	 *
	 * @param metadataRoot downstream metadata source root
	 * @param upstreamRoot upstream notice source root
	 * @return archive-relative legal bytes sorted by path
	 * @throws IOException if a source is missing, linked, empty, oversized, or conflicting
	 */
	private static Map<String, byte[]> suppliedDocuments(Path metadataRoot, Path upstreamRoot) throws IOException
	{
		List<Source> sources = new ArrayList<>();
		for (String relative : DOWNSTREAM_DOCUMENTS)
			sources.add(new Source(relative, metadataRoot.resolve(relative)));
		for (String relative : UPSTREAM_NOTICES)
			sources.add(new Source("licenses/upstream/" + relative, upstreamRoot.resolve(relative)));
		Path licenses = metadataRoot.resolve("licenses");
		if (Files.exists(licenses, LinkOption.NOFOLLOW_LINKS))
		{
			try (Stream<Path> paths = Files.walk(licenses))
			{
				for (Path path : paths.filter(candidate -> Files.isRegularFile(candidate) || Files.isSymbolicLink(candidate)).
					sorted().toList())
				{
					String relative = metadataRoot.relativize(path).toString().
						replace(path.getFileSystem().getSeparator(), "/");
					sources.add(new Source(relative, path));
				}
			}
		}
		if (sources.size() > MAX_LEGAL_FILES)
			throw new IOException("Legal sources exceed " + MAX_LEGAL_FILES + " documents");
		Map<String, byte[]> documents = new TreeMap<>();
		for (Source source : sources)
		{
			if (Files.isSymbolicLink(source.path()) || !Files.isRegularFile(source.path(), LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Missing regular legal source: " + source.path());
			long size = Files.size(source.path());
			if (size == 0 || size > MAX_DOCUMENT_BYTES)
				throw new IOException("Legal source must contain 1.." + MAX_DOCUMENT_BYTES + " bytes: " + source.path());
			byte[] content = Files.readAllBytes(source.path());
			if (content.length == 0 || content.length > MAX_DOCUMENT_BYTES)
				throw new IOException("Legal source must contain 1.." + MAX_DOCUMENT_BYTES + " bytes: " + source.path());
			String name = LEGAL_DIRECTORY + "/" + source.relative();
			byte[] existing = documents.putIfAbsent(name, content);
			if (existing != null && !Arrays.equals(existing, content))
				throw new IOException("Conflicting legal sources for: " + name);
		}
		return documents;
	}

	/**
	 * Preflights every destination and stages legal bytes into a canonical package directory.
	 *
	 * @param packageDirectory package root
	 * @param documents archive-relative legal bytes
	 * @throws IOException if metadata is absent or a destination conflicts or is linked
	 */
	private static void stageDocuments(Path packageDirectory, Map<String, byte[]> documents) throws IOException
	{
		Path metadata = packageDirectory.resolve("codex-package.json");
		if (!Files.isRegularFile(metadata))
			throw new IOException("Missing canonical package metadata: " + metadata);
		Path root = packageDirectory.toAbsolutePath();
		for (String relative : documents.keySet())
		{
			Path destination = root.resolve(relative);
			for (Path ancestor = destination; ancestor != null && !ancestor.equals(root.getParent());
			ancestor = ancestor.getParent())
			{
				if (Files.isSymbolicLink(ancestor))
					throw new IOException("Linked legal destination: " + ancestor);
			}
			byte[] content = documents.get(relative);
			if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) &&
				(!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) || Files.size(destination) != content.length ||
					!Arrays.equals(Files.readAllBytes(destination), content)))
				throw new IOException("Existing legal document differs: " + destination);
		}
		for (String relative : documents.keySet())
		{
			Path destination = root.resolve(relative);
			Files.createDirectories(destination.getParent());
			Files.write(destination, documents.get(relative));
		}
	}

	/**
	 * Stages both package variants, invokes the upstream writer, and checks each resulting archive.
	 *
	 * @param options parsed finalization options
	 * @param environment process environment additions
	 * @param processes upstream process boundary
	 * @param zstdCommand upstream-selected zstd command
	 * @return number of staged legal documents
	 * @throws IOException if staging, writing, decompression, or archive validation fails
	 */
	private static int finalizePackages(Options options, Map<String, String> environment,
		ArchiveProcesses processes, List<String> zstdCommand) throws IOException
	{
		Map<String, byte[]> documents = suppliedDocuments(options.metadataRoot(), options.upstreamRoot());
		Map<String, Path> packageDirectories = new LinkedHashMap<>();
		for (String variant : PACKAGE_VARIANTS)
			packageDirectories.put(variant, options.packageRoot().resolve(variant + "-package-" + options.target()));
		for (Path directory : packageDirectories.values())
			stageDocuments(directory, documents);

		for (Map.Entry<String, Path> packageDirectory : packageDirectories.entrySet())
		{
			for (String suffix : ARCHIVE_SUFFIXES)
			{
				String archiveName = packageDirectory.getKey() + "-package-" + options.target() + suffix;
				Path archive = options.archiveRoot().resolve(archiveName);
				writeArchive(options.upstreamRoot(), packageDirectory.getValue(), archive, environment, processes);
				if (suffix.equals(".tar.gz"))
					try (InputStream source = Files.newInputStream(archive))
					{
						checkArchive(source, documents);
					}
				else
					checkZstdArchive(archive, zstdCommand, options.upstreamRoot(), environment, processes, documents);
			}
		}
		return documents.size();
	}

	/**
	 * Selects the zstd command that the archive checks run from the upstream {@code scripts} directory.
	 *
	 * @param upstreamRoot upstream repository root
	 * @param executables creates the host executable search for the checks' working directory
	 * @return selected zstd command vector
	 * @throws IOException if the scripts directory is missing or neither zstd nor DotSlash is available
	 */
	private static List<String> resolveZstd(Path upstreamRoot, Function<Path, PackageZstd.Executables> executables)
		throws IOException
	{
		Path scripts = upstreamRoot.resolve("scripts");
		if (!Files.isDirectory(scripts))
			throw new IOException("Missing upstream scripts directory: " + scripts);
		return PackageZstd.resolve(upstreamRoot.toAbsolutePath().normalize(), executables.apply(scripts));
	}

	/**
	 * Builds an upstream archive module command.
	 *
	 * @param arguments archive module arguments
	 * @return complete command argument vector
	 */
	private static List<String> command(String... arguments)
	{
		List<String> command = new ArrayList<>(List.of("python", "-m", ARCHIVE_MODULE));
		command.addAll(List.of(arguments));
		return List.copyOf(command);
	}

	/**
	 * Invokes the upstream canonical archive writer.
	 *
	 * @param upstreamRoot upstream repository root
	 * @param packageDirectory staged package directory
	 * @param archive output archive path
	 * @param environment process environment additions
	 * @param processes upstream process boundary
	 * @throws IOException if the writer fails
	 */
	private static void writeArchive(Path upstreamRoot, Path packageDirectory, Path archive,
		Map<String, String> environment, ArchiveProcesses processes) throws IOException
	{
		Path scripts = upstreamRoot.resolve("scripts");
		List<String> command = command("write", "--package-dir", packageDirectory.toAbsolutePath().toString(),
			"--archive-output", archive.toAbsolutePath().toString(), "--force");
		CommandResult result = processes.capture(command, scripts, Path.of(System.getProperty("java.io.tmpdir")),
			processEnvironment(environment, upstreamRoot));
		if (result.status() != 0)
			throw new IOException("Upstream archive writer failed: " + result.stderr());
	}

	/**
	 * Adds the upstream root required by the archive module.
	 *
	 * @param environment process environment additions
	 * @param upstreamRoot upstream repository root
	 * @return immutable process environment
	 */
	private static Map<String, String> processEnvironment(Map<String, String> environment, Path upstreamRoot)
	{
		Map<String, String> result = new LinkedHashMap<>(environment);
		result.put("CODEX_REPO_ROOT", upstreamRoot.toAbsolutePath().normalize().toString());
		return Map.copyOf(result);
	}

	/**
	 * Decompresses a zstd archive with the upstream-selected command and checks its tar members.
	 *
	 * @param archive zstd archive path
	 * @param zstdCommand upstream-selected decompressor command
	 * @param upstreamRoot upstream repository root
	 * @param environment process environment additions
	 * @param processes upstream process boundary
	 * @param documents required legal bytes
	 * @throws IOException if decompression or archive validation fails
	 */
	private static void checkZstdArchive(Path archive, List<String> zstdCommand,
		Path upstreamRoot, Map<String, String> environment, ArchiveProcesses processes,
		Map<String, byte[]> documents) throws IOException
	{
		Path scripts = upstreamRoot.resolve("scripts");
		Path tarFile = Files.createTempFile("package-license-", ".tar");
		try
		{
			List<String> command = new ArrayList<>(zstdCommand);
			command.addAll(List.of("-dc", archive.toAbsolutePath().toString()));
			processes.runToFile(command, scripts, processEnvironment(environment, upstreamRoot), tarFile);
			try (InputStream source = Files.newInputStream(tarFile))
			{
				checkArchive(source, documents);
			}
		}
		finally
		{
			Files.deleteIfExists(tarFile);
		}
	}

	/**
	 * Detects gzip magic bytes and wraps the stream when needed.
	 *
	 * @param source archive stream
	 * @return gzip-decoded or unchanged buffered stream
	 * @throws IOException if the stream cannot be inspected
	 */
	private static InputStream maybeGunzip(InputStream source) throws IOException
	{
		BufferedInputStream buffered;
		if (source instanceof BufferedInputStream existing)
			buffered = existing;
		else
			buffered = new BufferedInputStream(source);
		buffered.mark(2);
		int first = buffered.read();
		int second = buffered.read();
		buffered.reset();
		if (first == 0x1f && second == 0x8b)
			return new GZIPInputStream(buffered);
		return buffered;
	}

	/**
	 * Validates an archive member path and removes only its directory suffix.
	 *
	 * @param rawName raw tar member name
	 * @return canonical relative member name
	 * @throws IOException if the name is unsafe
	 */
	private static String canonicalName(String rawName) throws IOException
	{
		int length = rawName.length();
		while (length > 0 && rawName.charAt(length - 1) == '/')
			length = length - 1;
		String name = rawName.substring(0, length);
		String[] parts = name.split("/", -1);
		if (name.isEmpty() || rawName.indexOf('\\') >= 0 || rawName.indexOf(':') >= 0 ||
			Arrays.stream(parts).anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..")))
			throw new IOException("Unsafe archive member path: '" + rawName + "'");
		return name;
	}

	/**
	 * Wraps caller-owned input so closing the archive reader leaves it open.
	 *
	 * @param input caller-owned stream
	 * @return non-closing wrapper
	 */
	private static InputStream nonClosing(InputStream input)
	{
		return new FilterInputStream(input)
		{
			@Override
			public void close()
			{
			}
		};
	}

	/**
	 * Runs upstream commands through the shared system command boundary.
	 */
	private static final class SystemArchiveProcesses implements ArchiveProcesses
	{
		/** Creates the system process adapter. */
		private SystemArchiveProcesses()
		{
		}
		@Override
		public CommandResult capture(List<String> command, Path workingDirectory, Path temporaryDirectory,
			Map<String, String> environment) throws IOException
		{
			SystemCommands.Result result = SystemCommands.capture(command, workingDirectory, temporaryDirectory, environment);
			return new CommandResult(result.status(), result.stdout(), result.stderr());
		}

		@Override
		public void runToFile(List<String> command, Path workingDirectory, Map<String, String> environment,
			Path standardOutput) throws IOException
		{
			Path standardError = Files.createTempFile("package-license-", ".stderr");
			try
			{
				ProcessBuilder builder = SystemCommands.createBuilder(command).
					directory(workingDirectory.toFile()).redirectOutput(standardOutput.toFile()).
					redirectError(standardError.toFile());
				builder.environment().putAll(environment);
				try (Process process = builder.start())
				{
					int status;
					try
					{
						status = process.waitFor();
					}
					catch (InterruptedException failure)
					{
						process.destroyForcibly();
						Thread.currentThread().interrupt();
						throw new IOException("Interrupted while waiting for zstd decompressor", failure);
					}
					if (status != 0)
						throw new IOException("zstd decompression failed: " + Files.readString(standardError));
				}
			}
			finally
			{
				Files.deleteIfExists(standardError);
			}
		}
	}

	/**
	 * Validated options for one package-license operation.
	 */
	private static final class Options
	{
		/**
		 * Selected operation.
		 */
		private final String mode;
		/**
		 * Downstream legal source root.
		 */
		private final Path metadataRoot;
		/**
		 * Upstream legal source root.
		 */
		private final Path upstreamRoot;
		/**
		 * Stage package path.
		 */
		private final Path packageDirectory;
		/**
		 * Archive path for check mode.
		 */
		private final String archive;
		/**
		 * Package root for finalization.
		 */
		private final Path packageRoot;
		/**
		 * Output root for finalization archives.
		 */
		private final Path archiveRoot;
		/**
		 * Final package target identifier.
		 */
		private final String target;

		/**
		 * Creates parsed options.
		 *
		 * @param mode selected operation
		 * @param metadataRoot downstream source root
		 * @param upstreamRoot upstream source root
		 * @param packageDirectory stage package path
		 * @param archive check archive path
		 * @param packageRoot finalization package root
		 * @param archiveRoot finalization output root
		 * @param target target identifier
		 */
		private Options(String mode, Path metadataRoot, Path upstreamRoot, Path packageDirectory, String archive,
			Path packageRoot, Path archiveRoot, String target)
		{
			this.mode = mode;
			this.metadataRoot = metadataRoot;
			this.upstreamRoot = upstreamRoot;
			this.packageDirectory = packageDirectory;
			this.archive = archive;
			this.packageRoot = packageRoot;
			this.archiveRoot = archiveRoot;
			this.target = target;
		}

		/**
		 * Returns the selected operation.
		 *
		 * @return selected operation
		 */
		private String mode()
		{
			return mode;
		}

		/**
		 * Returns the downstream source root.
		 *
		 * @return downstream source root
		 */
		private Path metadataRoot()
		{
			return metadataRoot;
		}

		/**
		 * Returns the upstream source root.
		 *
		 * @return upstream source root
		 */
		private Path upstreamRoot()
		{
			return upstreamRoot;
		}

		/**
		 * Returns the stage package path.
		 *
		 * @return stage package path
		 */
		private Path packageDirectory()
		{
			return packageDirectory;
		}

		/**
		 * Returns the archive check path.
		 *
		 * @return archive check path
		 */
		private String archive()
		{
			return archive;
		}

		/**
		 * Returns the finalization package root.
		 *
		 * @return finalization package root
		 */
		private Path packageRoot()
		{
			return packageRoot;
		}

		/**
		 * Returns the finalization archive root.
		 *
		 * @return finalization archive root
		 */
		private Path archiveRoot()
		{
			return archiveRoot;
		}

		/**
		 * Returns the target identifier.
		 *
		 * @return target identifier
		 */
		private String target()
		{
			return target;
		}
	}

	/**
	 * A legal source and its archive-relative destination.
	 *
	 * @param relative archive-relative path
	 * @param path source path
	 */
	private record Source(String relative, Path path)
	{
	}
}
