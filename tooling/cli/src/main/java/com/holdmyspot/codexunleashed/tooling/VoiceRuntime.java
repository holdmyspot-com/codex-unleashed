package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/** Stages, checks, and natively accepts the sealed voice runtime in a primary package. */
public final class VoiceRuntime
{
	/** Command name registered by the launcher. */
	public static final String NAME = "voice-runtime";
	/** Usage text for the command. */
	private static final String HELP = "Usage: voice-runtime <stage|check|verify> --package-dir PATH " +
		"--upstream-root PATH " +
		"--target TARGET --build-commit SHA --package-version VERSION [stage: --release-dir PATH] " +
		"[verify: --test-binary PATH --installed-test-binary PATH --evidence-dir PATH]\n" +
		"Environment: CODEX_VOICE_DIAGNOSTICS_DIR=PATH keeps the output files and a dependency listing of a failed " +
		"helper identity query in a new subdirectory of PATH.";
	/**
	 * Environment variable naming a directory that receives the complete output files and a dependency listing of a
	 * failed helper identity query.
	 */
	private static final String DIAGNOSTICS_DIRECTORY_VARIABLE = "CODEX_VOICE_DIAGNOSTICS_DIR";
	/** Upper-case names of the environment variables that steer a helper's shared-library search. */
	private static final Set<String> LOADER_VARIABLES = Set.of("PATH", "LD_LIBRARY_PATH", "DYLD_LIBRARY_PATH");
	/** Most recent bytes of each helper output stream quoted in a failure message. */
	private static final int DIAGNOSTIC_EXCERPT_BYTES = 4096;
	/** Accepted upstream commit format. */
	private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
	/** Accepted package version format. */
	private static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(?:\\+[0-9]+)?");
	/** License and source files copied into staged voice resources. */
	private static final List<String> LICENSE_FILES = List.of("NOTICE.md", "sources.json", "licenses/LGPL-2.1.txt",
		"licenses/Opus.txt", "licenses/PCRE2.md", "licenses/libffi.txt", "licenses/proxy-libintl.txt",
		"licenses/sljit.txt", "licenses/zlib.txt");

	/** Child process boundary with explicit inherited-environment removal and timeout. */
	@FunctionalInterface
	public interface Processes
	{
		/**
		 * Performs run for the voice runtime.
		 *
		 * @param command supplied command value
		 * @param workingDirectory supplied workingDirectory value
		 * @param environment child environment values
			 * @param removedEnvironment supplied removedEnvironment value
		 * @param timeout supplied timeout value
		 * @param stdout supplied stdout value
		 * @param stderr supplied stderr value
		 * @return the run result
		 * @throws IOException if the operation fails
		 */
		int run(List<String> command, Path workingDirectory, Map<String, String> environment,
			Set<String> removedEnvironment,
			Duration timeout, Path stdout, Path stderr) throws IOException;
	}

	/** Prevents construction of the command utility. */
	private VoiceRuntime()
	{
	}

	/**
	 * Runs the command with the system process implementation.
	 *
	 * @param scope shared tooling scope
	 * @param arguments command mode and named arguments
	 * @param environment child environment values
	 * @param out caller-owned success output
	 * @param err caller-owned error output
	 * @return command status
	 */
	public static int run(ToolingScope scope, String[] arguments, Map<String, String> environment, PrintStream out,
		PrintStream err)
	{
		return run(scope, arguments, environment, VoiceRuntime::runProcess, out, err);
	}

	/**
	 * Runs the command using the supplied process boundary for native acceptance.
	 *
	 * @param scope shared tooling scope
	 * @param arguments command mode and named arguments
	 * @param environment child environment values
	 * @param processes child process boundary
	 * @param out caller-owned success output
	 * @param err caller-owned error output
	 * @return command status
	 */
	public static int run(ToolingScope scope, String[] arguments, Map<String, String> environment, Processes processes,
		PrintStream out, PrintStream err)
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(environment, "environment");
		Objects.requireNonNull(processes, "processes");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		Options options;
		try
		{
			options = Options.parse(arguments);
		}
		catch (IllegalArgumentException failure)
		{
			err.println("voice-runtime: " + failure.getMessage());
			err.println(HELP);
			return 2;
		}
		if (options.help())
		{
			out.println(HELP);
			return 0;
		}
		try
		{
			switch (options.mode())
			{
				case "stage" -> stage(scope, options, environment, processes);
				case "check" -> check(scope, options, environment, processes);
				case "verify" -> verify(scope, options, environment, processes);
				default -> throw new IllegalArgumentException("unsupported mode " + options.mode());
			}
			if (!options.mode().equals("verify"))
				out.println("voice-runtime " + options.mode() + " passed for " + options.target());
			return 0;
		}
		catch (IOException | IllegalArgumentException failure)
		{
			err.println("voice-runtime: " + failure.getMessage());
			return 1;
		}
	}

	/**
	 * Performs stage for the voice runtime.
	 *
	 * @param scope supplied scope value
	 * @param options selected command options
	 * @param environment child environment values
	 * @param processes supplied processes value
	 * @throws IOException if the operation fails
	 */
	private static void stage(ToolingScope scope, Options options, Map<String, String> environment, Processes processes)
		throws IOException
	{
		Path source = VoiceRuntimeAcceptance.physicalDirectory(options.path("--upstream-root"), "upstream root");
		Path release = VoiceRuntimeAcceptance.physicalDirectory(options.path("--release-dir"), "release directory");
		Path packageRoot = VoiceRuntimeAcceptance.physicalDirectory(options.path("--package-dir"), "package directory");
		checkDistinctRoots(source, release, packageRoot);
		Path entrypoint = VoiceRuntimeAcceptance.packageEntrypoint(scope, packageRoot, options.target(), options.version());
		Path voiceRoot = packageRoot.resolve("codex-resources/voice");
		if (Files.exists(voiceRoot, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("voice destination already exists: " + voiceRoot);
		Path releaseRuntime = VoiceRuntimeAcceptance.physicalDirectory(release.resolve("runtime"), "release runtime");
		Path receiptPath = VoiceRuntimeAcceptance.physicalRegularFile(releaseRuntime, Path.of("runtime.json"),
			"runtime receipt");
		VoiceRuntimeReceipt receipt = VoiceRuntimeReceipt.read(scope, receiptPath);
		if (!receipt.target().equals(VoiceRuntimeAcceptance.voiceTarget(options.target())) ||
			!receipt.sourceCommit().equals(options.commit()))
					throw new IOException("runtime receipt target or source commit does not match the requested identity");
		Path sourceManifest = VoiceRuntimeAcceptance.physicalRegularFile(source, Path.of("third_party/voice/sources.json"),
			"voice source manifest");
		if (!sha256(sourceManifest).equals(receipt.sourceManifestSha256()))
			throw new IOException("runtime receipt sourceManifestSha256 does not match upstream sources.json");
		validateLibraries(releaseRuntime, receipt, options.target());
		Path helper = VoiceRuntimeAcceptance.physicalRegularFile(release,
			Path.of("codex-voice-host" + executableSuffix(options.target())), "voice helper");
		Path loaderDirectory = stagedLoaderDirectory(releaseRuntime, options.target());
		Map<String, String> helperEnvironment = stagedLoaderEnvironment(environment, options.target(), loaderDirectory);
		String helperCommit = runIdentity(helper, release, helperEnvironment, List.of(release, loaderDirectory), processes);
		if (!helperCommit.equals(options.commit()))
			throw new IOException("voice helper build commit does not match requested source commit");
		Path resources = packageRoot.resolve("codex-resources");
		Files.createDirectories(resources);
		try
		{
			Files.createDirectory(voiceRoot);
			Path bin = Files.createDirectory(voiceRoot.resolve("bin"));
			copyHashed(helper, bin.resolve(helper.getFileName()));
			copyHashed(receiptPath, voiceRoot.resolve("runtime.json"));
			for (VoiceRuntimeReceipt.Library library : receipt.libraries())
				copyHashed(VoiceRuntimeAcceptance.physicalRegularFile(releaseRuntime, Path.of(library.path()),
					"runtime library"), voiceRoot.resolve(library.path()));
			for (String legal : LICENSE_FILES)
				copyLegal(source.resolve("third_party/voice").resolve(legal), voiceRoot.resolve(legal));
			if (options.target().contains("windows"))
				copyLegal(source.resolve("third_party/voice/windows-crt.json"), voiceRoot.resolve("windows-crt.json"));
			Map<String, String> inventory = inventory(packageRoot, voiceRoot, entrypoint);
			Map<String, Object> manifest = new LinkedHashMap<>();
			manifest.put("schemaVersion", 1);
			manifest.put("buildCommit", options.commit());
			manifest.put("appTarget", options.target());
			manifest.put("voiceTarget", receipt.target());
			manifest.put("appVersion", options.version());
			manifest.put("sha256", new TreeMap<>(inventory));
			writeJsonExclusive(scope, voiceRoot.resolve("manifest.json"), manifest);
			validateManifest(scope, packageRoot, voiceRoot, options, receipt);
		}
		catch (IOException | RuntimeException failure)
		{
			rollbackStage(voiceRoot, resources);
			throw failure;
		}
	}

	/**
	 * Performs check for the voice runtime.
	 *
	 * @param scope supplied scope value
	 * @param options selected command options
	 * @param environment child environment values
	 * @param processes supplied processes value
	 * @throws IOException if the operation fails
	 */
	private static void check(ToolingScope scope, Options options, Map<String, String> environment, Processes processes)
		throws IOException
	{
		Path source = VoiceRuntimeAcceptance.physicalDirectory(options.path("--upstream-root"), "upstream root");
		Path packageRoot = VoiceRuntimeAcceptance.physicalDirectory(options.path("--package-dir"), "package directory");
		VoiceRuntimeAcceptance.packageEntrypoint(scope, packageRoot, options.target(), options.version());
		Path voiceRoot = VoiceRuntimeAcceptance.physicalDirectory(
			packageRoot.resolve("codex-resources/voice"), "voice runtime");
		Path receiptPath = VoiceRuntimeAcceptance.physicalRegularFile(voiceRoot, Path.of("runtime.json"),
			"runtime receipt");
		VoiceRuntimeReceipt receipt = VoiceRuntimeReceipt.read(scope, receiptPath);
		if (!receipt.sourceCommit().equals(options.commit()) || !receipt.target().equals(
			VoiceRuntimeAcceptance.voiceTarget(options.target())))
			throw new IOException("installed voice runtime receipt identity does not match requested identity");
		Path sourceManifest = VoiceRuntimeAcceptance.physicalRegularFile(source, Path.of("third_party/voice/sources.json"),
			"voice source manifest");
		if (!sha256(sourceManifest).equals(receipt.sourceManifestSha256()))
			throw new IOException("installed voice runtime source manifest differs from upstream source");
		validateManifest(scope, packageRoot, voiceRoot, options, receipt);
		validateInstalledRuntime(voiceRoot, source, receipt, options.target());
		Path helper = VoiceRuntimeAcceptance.physicalRegularFile(voiceRoot,
			Path.of("bin/codex-voice-host" + executableSuffix(options.target())), "installed voice helper");
		if (!runIdentity(helper, packageRoot, environment, List.of(helper.getParent()), processes).equals(options.commit()))
			throw new IOException("installed voice helper build commit does not match requested source commit");
	}

	/**
	 * Performs verify for the voice runtime.
	 *
	 * @param scope supplied scope value
	 * @param options selected command options
	 * @param environment child environment values
	 * @param processes supplied processes value
	 * @throws IOException if the operation fails
	 */
	private static void verify(ToolingScope scope, Options options, Map<String, String> environment, Processes processes)
		throws IOException
	{
		check(scope, options, environment, processes);
		Path packageRoot = options.path("--package-dir").toRealPath();
		if (!packageRoot.toString().contains(" "))
			throw new IOException("verify requires the physical package path to contain a space");
		Path evidence = options.path("--evidence-dir");
		Files.createDirectory(evidence);
		Path voiceRoot = packageRoot.resolve("codex-resources/voice");
		String helperBefore = sha256(voiceRoot.resolve("bin/codex-voice-host" + executableSuffix(options.target())));
		String runtimeBefore = sha256(voiceRoot.resolve("runtime.json"));
		String manifestBefore = sha256(voiceRoot.resolve("manifest.json"));
		List<Map<String, Object>> tests = new ArrayList<>();
		String[] names = {"relocated_runtime_initializes_closes_and_rejects_duplicate_initialization",
			"installed_package_initializes_closes_and_rejects_duplicate_initialization"};
		String[] optionsNames = {"--test-binary", "--installed-test-binary"};
		for (int index = 0; index < names.length; index += 1)
		{
			Path binary = VoiceRuntimeAcceptance.physicalRegularFile(options.path(optionsNames[index]).getParent(),
				options.path(optionsNames[index]).getFileName(), "verification test binary");
			if (index == 0)
				tests.add(runNativeTest(binary, names[index], index, evidence, packageRoot, voiceRoot, options,
					environment, processes, null));
			else
			{
				Path installedInput = Files.createDirectory(evidence.resolve("installed_package_test_input"));
				try
				{
					writeBoundedInput(installedInput.resolve("installed_package_path.txt"), packageRoot.toString());
					writeBoundedInput(installedInput.resolve("expected_build_commit.txt"), options.commit());
					tests.add(runNativeTest(binary, names[index], index, evidence, packageRoot, voiceRoot,
						options, environment, processes, installedInput));
				}
				finally
				{
					deleteTree(installedInput);
				}
			}
		}
		if (!helperBefore.equals(sha256(voiceRoot.resolve("bin/codex-voice-host" + executableSuffix(options.target())))) ||
			!runtimeBefore.equals(sha256(voiceRoot.resolve("runtime.json"))) ||
			!manifestBefore.equals(sha256(voiceRoot.resolve("manifest.json"))))
			throw new IOException("voice package identity changed during native verification");
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("format_version", 1);
		result.put("result", "passed");
		result.put("target", options.target());
		result.put("voice_target", VoiceRuntimeAcceptance.voiceTarget(options.target()));
		result.put("source_commit", options.commit());
		result.put("package_version", options.version());
		result.put("helper_sha256", helperBefore);
		result.put("runtime_manifest_sha256", runtimeBefore);
		result.put("voice_manifest_sha256", manifestBefore);
		result.put("tests", tests);
		writeJsonAtomic(scope, evidence.resolve("result.json"), result);
	}

	/**
	 * Performs runNativeTest for the voice runtime.
	 *
	 * @param binary supplied binary value
	 * @param name supplied name value
	 * @param index supplied index value
	 * @param evidence supplied evidence value
	 * @param packageRoot supplied packageRoot value
	 * @param voiceRoot supplied voiceRoot value
	 * @param options selected command options
	 * @param parentEnvironment supplied parentEnvironment value; its loader search path is replaced by the package's
	 *                          runtime library directory
	 * @param processes supplied processes value
	 * @param workingDirectory supplied workingDirectory value
	 * @return the runNativeTest result
	 * @throws IOException if the operation fails
	 */
	private static Map<String, Object> runNativeTest(Path binary, String name, int index, Path evidence, Path packageRoot,
		Path voiceRoot, Options options, Map<String, String> parentEnvironment, Processes processes, Path workingDirectory)
		throws IOException
	{
		String prefix = "installed";
		if (index == 0)
			prefix = "packaged";
		Path listOut = evidence.resolve(prefix + "-list.stdout");
		Path listErr = evidence.resolve(prefix + "-list.stderr");
		Path out = evidence.resolve(prefix + ".stdout");
		Path err = evidence.resolve(prefix + ".stderr");
		// The test binary runs from its verification directory, where its runpath (@loader_path/../lib on macOS,
		// $ORIGIN/../lib on Linux) and its application directory (Windows) reach none of the package's libraries.
		// Point the loader at the package's own libraries, as the helper identity query does.
		Map<String, String> additions = new HashMap<>(stagedLoaderEnvironment(parentEnvironment, options.target(),
			stagedLoaderDirectory(voiceRoot, options.target())));
		Path childWorkingDirectory = packageRoot;
		if (index == 0)
		{
			additions.put("CARGO_BIN_EXE_codex-voice-host", voiceRoot.resolve("bin").
				resolve("codex-voice-host" + executableSuffix(options.target())).toString());
			additions.put("CODEX_TEST_VOICE_RUNTIME", voiceRoot.toString());
		}
		else
		{
			additions.keySet().removeIf(key -> key.startsWith("CODEX_TEST_"));
			childWorkingDirectory = Objects.requireNonNull(workingDirectory, "installed package test input directory");
		}
		Set<String> removed = Set.of("RUNFILES_DIR", "RUNFILES_MANIFEST_FILE", "TEST_SRCDIR", "JAVA_RUNFILES");
		int listed = processes.run(List.of(binary.toString(), "--ignored", "--exact", name, "--list"),
			childWorkingDirectory, Map.copyOf(additions), removed, Duration.ofSeconds(60), listOut, listErr);
		if (listed != 0 || !isSingleListedTest(Files.readString(listOut), name))
			throw new IOException("native test listing failed or did not list exactly " + name + " (status " + listed +
				", logs " + listOut + " and " + listErr + ")");
		int status = processes.run(List.of(binary.toString(), "--ignored", "--exact", name, "--nocapture"),
			childWorkingDirectory, Map.copyOf(additions), removed, Duration.ofSeconds(60), out, err);
		if (status != 0 || !hasSinglePassedTest(Files.readString(out)))
			throw new IOException("native test failed for " + name + " (status " + status + ", logs " + out +
				" and " + err + ")");
		Map<String, Object> test = new LinkedHashMap<>();
		test.put("name", name);
		test.put("result", "passed");
		test.put("list_stdout", listOut.getFileName().toString());
		test.put("list_stderr", listErr.getFileName().toString());
		test.put("stdout", out.getFileName().toString());
		test.put("stderr", err.getFileName().toString());
		return test;
	}

	/**
	 * Writes a bounded raw UTF-8 installed package test input without a newline.
	 *
	 * @param path exclusive input file path
	 * @param value raw UTF-8 value
	 * @throws IOException if input is too large or writing fails
	 */
	private static void writeBoundedInput(Path path, String value) throws IOException
	{
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		if (bytes.length == 0 || bytes.length > 64 * 1024)
			throw new IOException("installed package test input must contain 1 through 65536 UTF-8 bytes");
		Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
	}

	/**
	 * Rolls back an incomplete staging attempt.
	 *
	 * @param voiceRoot owned staging destination
	 * @param resources package resources path
	 * @throws IOException if rollback fails
	 */
	private static void rollbackStage(Path voiceRoot, Path resources) throws IOException
	{
		deleteTree(voiceRoot);
		if (!Files.exists(resources.resolve("voice"), LinkOption.NOFOLLOW_LINKS))
			Files.deleteIfExists(resources);
	}

	/**
	 * Performs isSingleListedTest for the voice runtime.
	 *
	 * @param output supplied output value
	 * @param expected supplied expected value
	 * @return the isSingleListedTest result
	 */
	private static boolean isSingleListedTest(String output, String expected)
	{
		List<String> listed = output.lines().map(String::strip).filter(line -> line.matches(".+: test")).toList();
		return listed.equals(List.of(expected + ": test"));
	}

	/**
	 * Performs hasSinglePassedTest for the voice runtime.
	 *
	 * @param output supplied output value
	 * @return the hasSinglePassedTest result
	 */
	private static boolean hasSinglePassedTest(String output)
	{
		return output.lines().map(String::strip).anyMatch(line ->
			line.matches("test result: ok\\. 1 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in .+"));
	}

	/**
	 * Performs validateInstalledRuntime for the voice runtime.
	 *
	 * @param voiceRoot supplied voiceRoot value
	 * @param source supplied source value
	 * @param receipt supplied receipt value
	 * @param target supplied target value
	 * @throws IOException if the operation fails
	 */
	private static void validateInstalledRuntime(Path voiceRoot, Path source, VoiceRuntimeReceipt receipt, String target)
		throws IOException
	{
		Path runtime = voiceRoot;
		for (VoiceRuntimeReceipt.Library library : receipt.libraries())
		{
			Path file = VoiceRuntimeAcceptance.physicalRegularFile(runtime, Path.of(library.path()),
				"installed runtime library");
			if (!sha256(file).equals(library.sha256()))
				throw new IOException("installed runtime library hash mismatch: " + library.path());
		}
		List<String> legal = new ArrayList<>(LICENSE_FILES);
		if (target.contains("windows"))
			legal.add("windows-crt.json");
		for (String relative : legal)
		{
			Path installed = VoiceRuntimeAcceptance.physicalRegularFile(voiceRoot, Path.of(relative),
				"installed legal input");
			Path original = VoiceRuntimeAcceptance.physicalRegularFile(source, Path.of("third_party/voice").resolve(relative),
				"upstream legal input");
			if (!sha256(installed).equals(sha256(original)))
				throw new IOException("installed voice legal input differs from upstream source: " + relative);
		}
	}

	/**
	 * Performs validateManifest for the voice runtime.
	 *
	 * @param scope supplied scope value
	 * @param packageRoot supplied packageRoot value
	 * @param voiceRoot supplied voiceRoot value
	 * @param options selected command options
	 * @param receipt supplied receipt value
	 * @throws IOException if the operation fails
	 */
	private static void validateManifest(ToolingScope scope, Path packageRoot, Path voiceRoot, Options options,
		VoiceRuntimeReceipt receipt) throws IOException
	{
		Path manifestPath = VoiceRuntimeAcceptance.physicalRegularFile(voiceRoot, Path.of("manifest.json"),
			"voice manifest");
		if (Files.size(manifestPath) > 1024 * 1024)
			throw new IOException("voice manifest exceeds 1 MiB");
		JsonNode manifest = VoiceRuntimeReceipt.parse(scope, Files.readAllBytes(manifestPath), manifestPath.toString());
		VoiceRuntimeReceipt.objectFields(manifest,
			Set.of("schemaVersion", "buildCommit", "appTarget", "voiceTarget", "appVersion", "sha256"),
			manifestPath.toString());
		if (VoiceRuntimeReceipt.integer(manifest, "schemaVersion", "voice manifest") != 1 ||
			!VoiceRuntimeReceipt.string(manifest, "buildCommit", "voice manifest").equals(options.commit()) ||
			!VoiceRuntimeReceipt.string(manifest, "appTarget", "voice manifest").equals(options.target()) ||
			!VoiceRuntimeReceipt.string(manifest, "voiceTarget", "voice manifest").equals(receipt.target()) ||
			!VoiceRuntimeReceipt.string(manifest, "appVersion", "voice manifest").equals(options.version()))
			throw new IOException("voice manifest identity does not match package request");
		JsonNode hashes = manifest.get("sha256");
		if (hashes == null || !hashes.isObject())
			throw new IOException("voice manifest sha256 must be an object");
		Map<String, String> actual = inventory(packageRoot, voiceRoot,
			VoiceRuntimeAcceptance.physicalRegularFile(packageRoot,
				Path.of("bin/codex" + executableSuffix(options.target())), "package entrypoint"));
		if (hashes.size() != actual.size())
			throw new IOException("voice manifest inventory does not match package files");
		for (Map.Entry<String, String> file : actual.entrySet())
		{
			JsonNode expected = hashes.get(file.getKey());
			if (expected == null || !expected.isString() || !expected.asString().equals(file.getValue()))
				throw new IOException("voice manifest digest mismatch for " + file.getKey());
		}
	}

	/**
	 * Performs inventory for the voice runtime.
	 *
	 * @param packageRoot supplied packageRoot value
	 * @param voiceRoot supplied voiceRoot value
	 * @param entrypoint supplied entrypoint value
	 * @return the inventory result
	 * @throws IOException if the operation fails
	 */
	private static Map<String, String> inventory(Path packageRoot, Path voiceRoot, Path entrypoint) throws IOException
	{
		Map<String, String> result = new LinkedHashMap<>();
		result.put(packageRoot.relativize(entrypoint).toString().replace('\\', '/'), sha256(entrypoint));
		try (Stream<Path> paths = Files.walk(voiceRoot))
		{
			for (Path path : paths.toList())
			{
				if (Files.isSymbolicLink(path))
					throw new IOException("voice tree contains a symbolic link: " + path);
				if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
					continue;
				if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
					throw new IOException("voice tree contains a special file: " + path);
				String relative = packageRoot.relativize(path).toString().replace('\\', '/');
				if (relative.endsWith("/manifest.json"))
					continue;
				result.put(relative, sha256(path));
			}
		}
		return Map.copyOf(result);
	}

	/**
	 * Performs validateLibraries for the voice runtime.
	 *
	 * @param runtime supplied runtime value
	 * @param receipt supplied receipt value
	 * @param appTarget supplied appTarget value
	 * @throws IOException if the operation fails
	 */
	private static void validateLibraries(Path runtime, VoiceRuntimeReceipt receipt, String appTarget) throws IOException
	{
		boolean windows = appTarget.contains("windows");
		boolean darwin = appTarget.contains("apple-darwin");
		Set<String> libraryPaths = new HashSet<>();
		for (VoiceRuntimeReceipt.Library library : receipt.libraries())
		{
			String path = library.path();
			boolean allowed;
			if (windows)
				allowed = path.matches("bin/[A-Za-z0-9_+.-]+\\.[dD][lL][lL]");
			else if (darwin)
				allowed = path.matches("(?:lib|plugins)/[A-Za-z0-9_+.-]+\\.dylib");
			else
				allowed = path.matches("lib/(?:gstreamer-1\\.0/)?[A-Za-z0-9_+.-]+\\.so(?:\\.[0-9]+)*");
			if (!allowed)
				throw new IOException("runtime library path is not allowed for target: " + path);
			Path file = VoiceRuntimeAcceptance.physicalRegularFile(runtime, Path.of(path), "runtime library");
			if (!sha256(file).equals(library.sha256()))
				throw new IOException("runtime library hash mismatch: " + path);
			libraryPaths.add(path);
		}
		for (String plugin : receipt.plugins())
			if (!libraryPaths.contains(plugin))
				throw new IOException("runtime receipt omits plugin path " + plugin);
		String gio = "lib/libgio-2.0.so.0";
		if (darwin)
			gio = "lib/libgio-2.0.0.dylib";
		if (windows)
			gio = "bin/gio-2.0-0.dll";
		if (!libraryPaths.contains(gio))
			throw new IOException("runtime receipt omits required GIO library " + gio);
		if (windows && !libraryPaths.contains("bin/vcruntime140.dll"))
			throw new IOException("runtime receipt omits bin/vcruntime140.dll");
	}

	/**
	 * Selects the directory that holds the shared libraries of the release runtime for a target.
	 *
	 * @param releaseRuntime the sealed release's runtime directory
	 * @param appTarget the application target being staged
	 * @return {@code bin} for Windows targets, which keep their DLLs there, and {@code lib} for all other targets
	 */
	private static Path stagedLoaderDirectory(Path releaseRuntime, String appTarget)
	{
		String directory = "lib";
		if (appTarget.contains("windows"))
			directory = "bin";
		return releaseRuntime.resolve(directory).toAbsolutePath().normalize();
	}

	/**
	 * Builds the environment for the identity query of the helper in the release directory.
	 *
	 * <p>The helper sits in the release directory while the runtime libraries sit in {@code runtime/lib} or
	 * {@code runtime/bin}. Neither the Linux {@code $ORIGIN/../lib} runpath, the macOS equivalent, nor the Windows
	 * application-directory search reaches that directory, so the query would fail to load the helper (Windows reports
	 * status 0xC0000135, STATUS_DLL_NOT_FOUND). This replaces the inherited loader path with the runtime directory, as
	 * the producer's own identity query does, so a hostile inherited path cannot supply the libraries either.</p>
	 *
	 * @param environment the caller's environment values, which are not modified
	 * @param appTarget the application target being staged
	 * @param loaderDirectory the runtime directory that holds the shared libraries
	 * @return a copy of {@code environment} with the target's loader search path set to {@code loaderDirectory}
	 */
	private static Map<String, String> stagedLoaderEnvironment(Map<String, String> environment, String appTarget,
		Path loaderDirectory)
	{
		Map<String, String> helperEnvironment = new HashMap<>(environment);
		if (appTarget.contains("windows"))
		{
			// The inherited name may be spelled Path; both spellings name one Windows variable.
			helperEnvironment.keySet().removeIf(name -> name.equalsIgnoreCase("PATH"));
			helperEnvironment.put("PATH", loaderDirectory.toString());
		}
		else if (appTarget.contains("apple-darwin"))
			helperEnvironment.put("DYLD_LIBRARY_PATH", loaderDirectory.toString());
		else if (appTarget.contains("linux"))
			helperEnvironment.put("LD_LIBRARY_PATH", loaderDirectory.toString());
		return helperEnvironment;
	}

	/**
	 * Runs the helper's identity query and returns the build commit it reports.
	 *
	 * <p>When the query fails, the message quotes the tail of the helper's output. If the environment names a
	 * diagnostics directory ({@value #DIAGNOSTICS_DIRECTORY_VARIABLE}), the complete output files and a listing of the
	 * directories that should hold the helper's libraries are also kept in a new subdirectory of it; otherwise they are
	 * deleted with the query's temporary directory.</p>
	 *
	 * @param helper helper executable to query
	 * @param workingDirectory directory the helper runs in
	 * @param environment child environment values; also names the optional diagnostics directory
	 * @param dependencyDirectories directories whose files are listed in the retained diagnostics of a failed query
	 * @param processes child process boundary
	 * @return the build commit the helper reported
	 * @throws IOException if the helper exits unsuccessfully or does not report a build commit
	 */
	private static String runIdentity(Path helper, Path workingDirectory, Map<String, String> environment,
		List<Path> dependencyDirectories, Processes processes) throws IOException
	{
		Path temp = Files.createTempDirectory("voice-identity-");
		try
		{
			Path stdout = temp.resolve("stdout");
			Path stderr = temp.resolve("stderr");
			int status = processes.run(List.of(helper.toString(), "--build-commit"), workingDirectory, environment, Set.of(),
				Duration.ofSeconds(10), stdout, stderr);
			String result = "";
			if (Files.exists(stdout))
				result = Files.readString(stdout).strip();
			if (status != 0 || !COMMIT.matcher(result).matches())
			{
				String retained = retainedLogs(environment, stdout, stderr,
					dependencyListing(helper, workingDirectory, environment, dependencyDirectories));
				throw new IOException("voice helper identity failed with status " + status + retained +
					"\nhelper stdout (last " + DIAGNOSTIC_EXCERPT_BYTES + " bytes):\n" + tail(stdout) +
					"\nhelper stderr (last " + DIAGNOSTIC_EXCERPT_BYTES + " bytes):\n" + tail(stderr));
			}
			return result;
		}
		finally
		{
			deleteTree(temp);
		}
	}

	/**
	 * Describes what the helper could load: its location, the loader search paths it ran with, and the files in the
	 * directories that should hold its libraries.
	 *
	 * @param helper helper executable that was queried
	 * @param workingDirectory directory the helper ran in
	 * @param environment child environment values of the query
	 * @param directories directories to list, in order
	 * @return the listing text; an unreadable directory is reported in the text, not thrown
	 */
	private static String dependencyListing(Path helper, Path workingDirectory, Map<String, String> environment,
		List<Path> directories)
	{
		StringBuilder listing = new StringBuilder(1024);
		listing.append("helper: ").append(helper).append("\nworking directory: ").append(workingDirectory).append('\n');
		for (Map.Entry<String, String> variable : new TreeMap<>(environment).entrySet())
		{
			String name = variable.getKey().toUpperCase(Locale.ROOT);
			if (LOADER_VARIABLES.contains(name))
				listing.append(variable.getKey()).append('=').append(variable.getValue()).append('\n');
		}
		for (Path directory : directories)
		{
			listing.append("directory ").append(directory).append('\n');
			try (Stream<Path> entries = Files.list(directory))
			{
				for (Path entry : entries.sorted().toList())
				{
					String kind = "file";
					String size = "";
					if (Files.isDirectory(entry))
						kind = "dir ";
					else
						size = " " + Files.size(entry) + " bytes";
					listing.append("  ").append(kind).append(' ').append(entry.getFileName()).append(size).append('\n');
				}
			}
			catch (IOException | RuntimeException failure)
			{
				listing.append("  unreadable: ").append(failure).append('\n');
			}
		}
		return listing.toString();
	}

	/**
	 * Copies the helper's output files and dependency listing into the configured diagnostics directory.
	 *
	 * @param environment child environment values naming the optional diagnostics directory
	 * @param stdout helper standard output file
	 * @param stderr helper standard error file
	 * @param dependencies text of the dependency listing
	 * @return a message suffix naming where the files were kept, or an empty string when none is configured
	 */
	private static String retainedLogs(Map<String, String> environment, Path stdout, Path stderr, String dependencies)
	{
		String configured = environment.get(DIAGNOSTICS_DIRECTORY_VARIABLE);
		if (configured == null || configured.isBlank())
			return "";
		try
		{
			Path root = Path.of(configured);
			Files.createDirectories(root);
			Path retained = Files.createTempDirectory(root, "voice-helper-identity-");
			for (Path log : List.of(stdout, stderr))
				if (Files.exists(log))
					Files.copy(log, retained.resolve(log.getFileName()));
			Files.writeString(retained.resolve("dependencies.txt"), dependencies, StandardCharsets.UTF_8);
			return " (logs and dependency listing kept in " + retained + ")";
		}
		catch (IOException | RuntimeException failure)
		{
			return " (logs could not be kept in " + configured + ": " + failure + ")";
		}
	}

	/**
	 * Reads the last bytes of a helper output file.
	 *
	 * @param file helper output file, which may be absent
	 * @return the stripped tail text, or {@code <none>} if the file is absent or empty
	 * @throws IOException if the file cannot be read
	 */
	private static String tail(Path file) throws IOException
	{
		if (!Files.exists(file))
			return "<none>";
		try (SeekableByteChannel channel = Files.newByteChannel(file))
		{
			long size = channel.size();
			long start = Math.max(0, size - DIAGNOSTIC_EXCERPT_BYTES);
			ByteBuffer buffer = ByteBuffer.allocate((int) (size - start));
			channel.position(start);
			while (buffer.hasRemaining() && channel.read(buffer) >= 0)
			{
			}
			String text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8).strip();
			if (text.isEmpty())
				return "<none>";
			return text;
		}
	}

	/**
	 * Performs runProcess for the voice runtime.
	 *
	 * @param command supplied command value
	 * @param workingDirectory supplied workingDirectory value
	 * @param environment child environment values
	 * @param removedEnvironment supplied removedEnvironment value
	 * @param timeout supplied timeout value
	 * @param stdout supplied stdout value
	 * @param stderr supplied stderr value
	 * @return the runProcess result
	 * @throws IOException if the operation fails
	 */
	private static int runProcess(List<String> command, Path workingDirectory, Map<String, String> environment,
		Set<String> removedEnvironment, Duration timeout, Path stdout, Path stderr) throws IOException
	{
		ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
		Map<String, String> childEnvironment = builder.environment();
		removedEnvironment.forEach(childEnvironment::remove);
		childEnvironment.putAll(environment);
		try (Process process = builder.start())
		{
			try (OutputStream stdoutStream = Files.newOutputStream(stdout, StandardOpenOption.CREATE_NEW);
				OutputStream stderrStream = Files.newOutputStream(stderr, StandardOpenOption.CREATE_NEW))
			{
			Thread out = Thread.ofVirtual().start(() -> transfer(process.getInputStream(), stdoutStream));
			Thread err = Thread.ofVirtual().start(() -> transfer(process.getErrorStream(), stderrStream));
			try
			{
				if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS))
				{
					process.descendants().forEach(ProcessHandle::destroyForcibly);
					process.destroyForcibly();
					process.waitFor();
					throw new IOException("process timed out after " + timeout);
				}
				out.join();
				err.join();
				return process.exitValue();
			}
			catch (InterruptedException failure)
			{
				process.descendants().forEach(ProcessHandle::destroyForcibly);
				process.destroyForcibly();
				try
				{
					process.waitFor();
				}
				catch (InterruptedException interrupted)
				{
					failure.addSuppressed(interrupted);
				}
				Thread.currentThread().interrupt();
				throw new IOException("interrupted while waiting for child process", failure);
			}
		}
		}
	}

	/**
	 * Performs transfer for the voice runtime.
	 *
	 * @param input supplied input value
	 * @param output supplied output value
	 */
	private static void transfer(InputStream input, OutputStream output)
	{
		try (input; output)
		{
			input.transferTo(output);
		}
		catch (IOException _)
		{
			// The process result remains authoritative; a missing/short log causes the command to fail validation.
		}
	}

	/**
	 * Performs copyHashed for the voice runtime.
	 *
	 * @param input supplied input value
	 * @param output supplied output value
	 * @throws IOException if the operation fails
	 */
	private static void copyHashed(Path input, Path output) throws IOException
	{
		Files.createDirectories(output.getParent());
		String before = sha256(input);
		Files.copy(input, output);
		if (!before.equals(sha256(output)) || !before.equals(sha256(input)))
			throw new IOException("source changed while copying: " + input);
	}

	/**
	 * Performs copyLegal for the voice runtime.
	 *
	 * @param input supplied input value
	 * @param output supplied output value
	 * @throws IOException if the operation fails
	 */
	private static void copyLegal(Path input, Path output) throws IOException
	{
		if (Files.isSymbolicLink(input) || !Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("required legal input is missing or nonphysical: " + input);
		long size = Files.size(input);
		if (size == 0 || size > 2L * 1024 * 1024)
			throw new IOException("required legal input must be nonempty and at most 2 MiB: " + input);
		copyHashed(input, output);
	}

	/**
	 * Performs writeJsonExclusive for the voice runtime.
	 *
	 * @param scope supplied scope value
	 * @param target supplied target value
	 * @param value supplied value value
	 * @throws IOException if the operation fails
	 */
	private static void writeJsonExclusive(ToolingScope scope, Path target, Object value) throws IOException
	{
		byte[] bytes = scope.getArtifactJsonMapper().writeValueAsBytes(value);
		if (bytes.length > 1024 * 1024)
			throw new IOException("voice manifest exceeds 1 MiB");
		Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
	}

	/**
	 * Performs writeJsonAtomic for the voice runtime.
	 *
	 * @param scope supplied scope value
	 * @param target supplied target value
	 * @param value supplied value value
	 * @throws IOException if the operation fails
	 */
	private static void writeJsonAtomic(ToolingScope scope, Path target, Object value) throws IOException
	{
		Path temporary = Files.createTempFile(target.getParent(), "voice-result-", ".tmp");
		try
		{
			Files.write(temporary, scope.getArtifactJsonMapper().writeValueAsBytes(value),
				StandardOpenOption.TRUNCATE_EXISTING);
			try
			{
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException failure)
			{
				throw new IOException("atomic result replacement is unsupported", failure);
			}
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Performs sha256 for the voice runtime.
	 *
	 * @param path supplied path value
	 * @return the sha256 result
	 * @throws IOException if the operation fails
	 */
	private static String sha256(Path path) throws IOException
	{
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = Files.newInputStream(path))
			{
				byte[] buffer = new byte[64 * 1024];
				int read = input.read(buffer);
				while (read >= 0)
				{
					if (read > 0)
						digest.update(buffer, 0, read);
					read = input.read(buffer);
				}
			}
				return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException impossible)
		{
			throw new AssertionError(impossible);
		}
	}

	/**
	 * Performs checkDistinctRoots for the voice runtime.
	 *
	 * @param source supplied source value
	 * @param release supplied release value
	 * @param packageRoot supplied packageRoot value
	 * @throws IOException if the operation fails
	 */
	private static void checkDistinctRoots(Path source, Path release, Path packageRoot) throws IOException
	{
		if (source.equals(release) || source.equals(packageRoot) || release.equals(packageRoot) ||
			source.startsWith(release) || source.startsWith(packageRoot) || release.startsWith(source) ||
			release.startsWith(packageRoot) || packageRoot.startsWith(source) || packageRoot.startsWith(release))
			throw new IOException("source, release, and package roots must be distinct and nonnested");
	}

	/**
	 * Performs deleteTree for the voice runtime.
	 *
	 * @param root supplied root value
	 * @throws IOException if the operation fails
	 */
	private static void deleteTree(Path root) throws IOException
	{
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS))
			return;
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
				Files.deleteIfExists(path);
		}
	}

	/**
	 * Returns the native executable suffix for a target.
	 *
	 * @param target application target
	 * @return executable suffix
	 */
	private static String executableSuffix(String target)
	{
		if (target.contains("windows"))
			return ".exe";
		return "";
	}

	/**
	 * Parsed and validated command options.
	 *
	 * @param mode selected command mode
	 * @param values validated option values
	 * @param help whether help output was requested
	 */
	private record Options(String mode, Map<String, String> values, boolean help)
	{
		/**
		 * Performs parse for the voice runtime.
		 *
		 * @param args supplied args value
		 * @return the parse result
		 */
		private static Options parse(String[] args)
		{
		if (args.length == 0)
				throw new IllegalArgumentException("expected stage, check, or verify");
			if (args.length == 1 && args[0].equals("--help"))
				return new Options("", Map.of(), true);
			String mode = args[0];
			Set<String> required = switch (mode)
			{
				case "stage" -> Set.of("--release-dir", "--package-dir", "--upstream-root", "--target",
					"--build-commit", "--package-version");
				case "check" -> Set.of("--package-dir", "--upstream-root", "--target", "--build-commit", "--package-version");
				case "verify" -> Set.of("--package-dir", "--upstream-root", "--target", "--build-commit",
					"--package-version", "--test-binary", "--installed-test-binary", "--evidence-dir");
				default -> throw new IllegalArgumentException("unsupported mode " + mode);
			};
			if (args.length == 2 && args[1].equals("--help"))
				return new Options(mode, Map.of(), true);
			if (args.length != 1 + 2 * required.size())
				throw new IllegalArgumentException("each accepted option must appear exactly once with a value");
			Map<String, String> parsed = new HashMap<>();
			for (int index = 1; index < args.length; index += 2)
			{
				String name = args[index];
				String value = args[index + 1];
				if (name == null || value == null || value.isEmpty() || !required.contains(name) ||
					parsed.put(name, value) != null)
					throw new IllegalArgumentException("unknown, duplicate, or empty option value: " + name);
			}
			if (!parsed.keySet().equals(required))
				throw new IllegalArgumentException("all mode options must be supplied");
			String target = parsed.get("--target");
			try
			{
				VoiceRuntimeAcceptance.voiceTarget(target);
			}
			catch (IOException failure)
			{
				throw new IllegalArgumentException(failure.getMessage(), failure);
			}
			if (!COMMIT.matcher(parsed.get("--build-commit")).matches())
				throw new IllegalArgumentException("--build-commit must be 40 lowercase hexadecimal characters");
			if (!VERSION.matcher(parsed.get("--package-version")).matches())
				throw new IllegalArgumentException("--package-version must be a canonical package version");
			return new Options(mode, Map.copyOf(parsed), false);
		}

		/**
		 * Performs target for the voice runtime.
		 *
		 * @return the target result
		 */
		private String target()
		{
			return values.get("--target");
		}
		/**
		 * Performs commit for the voice runtime.
		 *
		 * @return the commit result
		 */
		private String commit()
		{
			return values.get("--build-commit");
		}
		/**
		 * Performs version for the voice runtime.
		 *
		 * @return the version result
		 */
		private String version()
		{
			return values.get("--package-version");
		}
		/**
		 * Performs path for the voice runtime.
		 *
		 * @param option supplied option value
		 * @return the path result
		 */
		private Path path(String option)
		{
			return Path.of(values.get(option));
		}
	}
}
