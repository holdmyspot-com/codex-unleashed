package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Runs command-line tooling operations.
 */
public final class Main
{
	/**
	 * Quiet-build watcher command whose diagnostics may re-execute the packaged launcher.
	 */
	private static final String WATCH_QUIET_BUILD_COMMAND = "watch-quiet-build";
	/**
	 * Names the upstream V8 action script extraction command.
	 */
	private static final String V8_SCRIPT_COMMAND = "upstream-v8-script";
	/** Environment variable naming the workflow summary file. */
	private static final String GITHUB_STEP_SUMMARY = "GITHUB_STEP_SUMMARY";
	/** Package identity command name. */
	private static final String PACKAGE_IDENTITY_COMMAND = "package-identity";
	/** Package license command name. */
	private static final String PACKAGE_LICENSE_COMMAND = "package-license";
	/** Release manifest command name. */
	private static final String RELEASE_MANIFEST_COMMAND = "release-manifest";
	/** Native release input producer command name. */
	private static final String RELEASE_NATIVE_INPUTS_COMMAND = "release-native-inputs";
	/** Test report comparison command name. */
	private static final String COMPARE_TEST_BASELINE_COMMAND = "compare-test-baseline";
	/** Test skip classification command name. */
	private static final String CHECK_TEST_SKIPS_COMMAND = "check-test-skips";
	/** Upstream release command name. */
	private static final String UPSTREAM_AUTO_RELEASE_COMMAND = "upstream-auto-release";
	/** Direct dependency lint command name. */
	private static final String DIRECT_DEPENDENCY_LINTS_COMMAND = "direct-dependency-lints";

	/**
	 * Prevents construction.
	 */
	private Main()
	{
	}

	/**
	 * Runs a command and terminates with its status.
	 *
	 * @param arguments the command name followed by its arguments
	 */
	public static void main(String[] arguments)
	{
		System.exit(runPackaged(arguments, System.in, System.out, System.err, System.getenv(),
			System.getProperty(ToolingLauncher.PATH_PROPERTY)));
	}

	/**
	 * Runs a tooling command and writes its output and diagnostics to the supplied streams.
	 *
	 * @param arguments the command name followed by its arguments
	 * @param in caller-owned inventory input for retention previews; remains open
	 * @param out progress output
	 * @param err diagnostics
	 * @return zero for success, one for operational failure or rejected input, and two for an unknown command or invalid
	 *         arguments
	 * @throws NullPointerException if an argument is null
	 */
	public static int run(String[] arguments, InputStream in, PrintStream out, PrintStream err)
	{
		return run(arguments, in, out, err, System.getenv());
	}

	/**
	 * Runs a tooling command using the supplied process environment.
	 *
	 * @param arguments the command name followed by its arguments
	 * @param in caller-owned inventory input for retention previews; remains open
	 * @param out progress output
	 * @param err diagnostics
	 * @param environment process environment used by the command
	 * @return zero for success, one for operational failure or rejected input, and two for an unknown command or invalid
	 *         arguments
	 * @throws NullPointerException if an argument or environment is null
	 */
	public static int run(String[] arguments, InputStream in, PrintStream out, PrintStream err,
		Map<String, String> environment)
	{
		return run(arguments, in, out, err, environment, null);
	}

	/**
	 * Runs a packaged command, interpreting launcher identity only for a normal watcher invocation.
	 *
	 * @param arguments command name and arguments
	 * @param in caller-owned inventory input for retention previews
	 * @param out command output
	 * @param err command diagnostics
	 * @param environment complete process environment
	 * @param launcherIdentity packaged launcher path text, or null when the process has no identity
	 * @return zero on success, one on operational failure or rejected input, and two for an unknown command or invalid
	 *         arguments
	 * @throws NullPointerException if any argument except {@code launcherIdentity} is null
	 */
	public static int runPackaged(String[] arguments, InputStream in, PrintStream out, PrintStream err,
		Map<String, String> environment, String launcherIdentity)
	{
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(in, "in");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		Objects.requireNonNull(environment, "environment");
		for (int index = 0; index < arguments.length; index += 1)
			Objects.requireNonNull(arguments[index], "arguments[" + index + "]");

		Path launcher = null;
		if (normalWatchCommand(arguments) && launcherIdentity != null)
		{
			try
			{
				launcher = Path.of(launcherIdentity);
			}
			catch (InvalidPathException _)
			{
				err.println("ERROR: Packaged launcher identity (" + ToolingLauncher.PATH_PROPERTY +
					") must be a valid path; invoke watch-quiet-build through the packaged codex-tooling launcher");
				return 1;
			}
		}
		return run(arguments, in, out, err, environment, launcher);
	}

	/**
	 * Runs a tooling command using the supplied process environment and packaged launcher identity.
	 *
	 * @param arguments   the command name followed by its arguments
	 * @param in          caller-owned inventory input for retention previews; remains open
	 * @param out         progress output
	 * @param err         diagnostics
	 * @param environment process environment used by the command
	 * @param launcher    packaged launcher path, or null when the caller has no packaged launcher identity
	 * @return zero for success, one for operational failure or rejected input, and two for an unknown command or invalid
	 *         arguments
	 * @throws NullPointerException if any argument except {@code launcher} is null
	 */
	public static int run(String[] arguments, InputStream in, PrintStream out, PrintStream err,
		Map<String, String> environment, Path launcher)
	{
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(in, "in");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		Objects.requireNonNull(environment, "environment");
		if (arguments.length == 0)
			return usage(err);
		try (ToolingScope scope = new DefaultToolingScope())
		{
			if (arguments[0].equals(WATCH_QUIET_BUILD_COMMAND))
			{
				int watcherOptionsEnd = watcherOptionsEnd(arguments);
				if (directDumpRequested(arguments))
					return runQuietBuildDump(scope, Arrays.copyOfRange(arguments, 1, watcherOptionsEnd), out, environment);

				if (launcher == null)
				{
					throw new IllegalArgumentException("Missing packaged launcher identity (" + ToolingLauncher.PATH_PROPERTY +
						"); invoke watch-quiet-build through the packaged codex-tooling launcher");
				}
				if (!launcher.isAbsolute())
				{
					throw new IllegalArgumentException("Packaged launcher identity (" + ToolingLauncher.PATH_PROPERTY +
						") must be an absolute path; " +
						"invoke watch-quiet-build through the packaged codex-tooling launcher");
				}
				if (!Files.isRegularFile(launcher))
				{
					throw new IllegalArgumentException("Packaged launcher identity (" + ToolingLauncher.PATH_PROPERTY +
						") must name an existing regular file; " +
						"invoke watch-quiet-build through the packaged codex-tooling launcher");
				}
				QuietBuildDump.Platform platform = quietBuildPlatform();
				QuietBuildCapture.Context context = new QuietBuildCapture.Context(Path.of("").toAbsolutePath(),
					Path.of(System.getProperty("java.io.tmpdir")), environment);
				return QuietBuildCommand.run(scope, Arrays.copyOfRange(arguments, 1, arguments.length), context,
					platform, launcher, err);
			}

			String summaryValue = environment.get(GITHUB_STEP_SUMMARY);
			boolean workflowSummaryIsAbsent = summaryValue == null || summaryValue.isBlank();
			Path summary = null;
			if (!workflowSummaryIsAbsent)
				summary = Path.of(summaryValue);
			return switch (arguments[0])
			{
				case DIRECT_DEPENDENCY_LINTS_COMMAND -> DirectDependencyLints.run(scope,
					Arrays.copyOfRange(arguments, 1, arguments.length), in, environment, out, err);
				case AptInstallWithFallback.NAME -> AptInstallWithFallback.run(
					Arrays.copyOfRange(arguments, 1, arguments.length), err);
				case PACKAGE_IDENTITY_COMMAND -> PackageIdentity.run(scope,
					Arrays.copyOfRange(arguments, 1, arguments.length), out, err);
				case WorkspaceLockAlignment.NAME -> WorkspaceLockAlignment.run(scope,
					Arrays.copyOfRange(arguments, 1, arguments.length), environment, out, err);
				case PACKAGE_LICENSE_COMMAND -> PackageLicense.run(scope,
					Arrays.copyOfRange(arguments, 1, arguments.length), in, environment, out, err);
				case VoiceRuntime.NAME -> VoiceRuntime.run(scope, Arrays.copyOfRange(arguments, 1, arguments.length),
					environment, out, err);
				case ReleaseReproduction.COMMAND -> ReleaseReproduction.run(scope,
					Arrays.copyOfRange(arguments, 1, arguments.length), environment, out, err);
				case RELEASE_MANIFEST_COMMAND -> ReleaseManifest.run(scope,
					Arrays.copyOfRange(arguments, 1, arguments.length), out, err);
				case RELEASE_NATIVE_INPUTS_COMMAND -> ReleaseManifestNativeInputs.run(scope,
					Arrays.copyOfRange(arguments, 1, arguments.length), environment, out, err);
				case PruneWorkspaceOutputs.NAME -> PruneWorkspaceOutputs.run(scope,
					Arrays.copyOfRange(arguments, 1, arguments.length), out, err);
				case COMPARE_TEST_BASELINE_COMMAND -> CompareTestBaseline.run(scope,
					Arrays.copyOfRange(arguments, 1, arguments.length), summary, out, err);
				case CHECK_TEST_SKIPS_COMMAND -> CheckTestSkips.run(
					Arrays.copyOfRange(arguments, 1, arguments.length), summary, out, err);
				case BazelTestEvidence.NAME -> BazelTestEvidence.run(scope,
					Arrays.copyOfRange(arguments, 1, arguments.length), out, err);
				case UPSTREAM_AUTO_RELEASE_COMMAND -> UpstreamAutoRelease.run(scope,
					Arrays.copyOfRange(arguments, 1, arguments.length), out, err);
				case "cargo-cache-keys" -> CargoCacheKeys.run(Arrays.copyOfRange(arguments, 1, arguments.length), out, err);
				case VerifyTuiCoreBoundary.NAME -> VerifyTuiCoreBoundary.run(scope, Arrays.copyOfRange(arguments, 1,
					arguments.length), out, err);
				case VerifyBazelClippyLints.NAME -> VerifyBazelClippyLints.run(scope,
					Arrays.copyOfRange(arguments, 1, arguments.length), out, err);
				case VerifyCargoWorkspaceManifests.NAME -> VerifyCargoWorkspaceManifests.run(scope,
					Arrays.copyOfRange(arguments, 1, arguments.length), out, err);
				case "verify-cargo-lock" -> VerifyCargoLock.run(scope, Arrays.copyOfRange(arguments, 1, arguments.length), out,
					err);
				case "cargo-cache-parts" -> CargoCacheParts.run(scope, Arrays.copyOfRange(arguments, 1, arguments.length),
					environment, out, err);
				case V8_SCRIPT_COMMAND -> writeV8Script(scope, arguments, out, err);
				case GhcrRetentionPlan.NAME -> GhcrRetentionPlan.run(scope, Arrays.copyOfRange(arguments, 1,
					arguments.length), in, out, err);
				case ActionsCacheRetentionPlan.NAME -> ActionsCacheRetentionPlan.run(scope, Arrays.copyOfRange(arguments, 1,
					arguments.length), in, out, err);
				case SourceCacheCommand.NAME -> SourceCacheCommand.run(scope, Arrays.copyOfRange(arguments, 1,
					arguments.length), out, err);
				case BlobSizeCommand.NAME -> BlobSizeCommand.run(Arrays.copyOfRange(arguments, 1, arguments.length),
					Path.of("."), Path.of(System.getProperty("java.io.tmpdir")), out, err);
				case CleanupOwnedCacheFixtures.NAME -> CleanupOwnedCacheFixtures.run(scope,
					Arrays.copyOfRange(arguments, 1, arguments.length), out, err);
				case MacosUnwindReport.NAME -> MacosUnwindReport.run(
					Arrays.copyOfRange(arguments, 1, arguments.length), out, err);
				case ReclaimBaselineMacosXcodes.NAME -> ReclaimBaselineMacosXcodes.run(scope,
					Arrays.copyOfRange(arguments, 1, arguments.length), environment, out, err);
				case VerifyCodexCacheInvalidation.NAME -> VerifyCodexCacheInvalidation.run(scope,
					Arrays.copyOfRange(arguments, 1, arguments.length), environment, out, err);
				case VerifyReadmeCharacters.NAME -> VerifyReadmeCharacters.run(scope,
					Arrays.copyOfRange(arguments, 1, arguments.length), out, err);
				case VerifyReadmeToc.NAME -> VerifyReadmeToc.run(scope,
					Arrays.copyOfRange(arguments, 1, arguments.length), out, err);
				case VerifyV8ModuleChecksums.NAME -> VerifyV8ModuleChecksums.run(scope,
					Arrays.copyOfRange(arguments, 1, arguments.length), out, err);
				case VerifyWorkspaceLockVersions.NAME -> VerifyWorkspaceLockVersions.run(scope,
					Arrays.copyOfRange(arguments, 1, arguments.length), environment, out, err);
				case SelectBazelPackages.NAME -> SelectBazelPackages.run(scope,
					Arrays.copyOfRange(arguments, 1, arguments.length), out, err);
				case SelectWindowsBazelTargets.NAME -> SelectWindowsBazelTargets.run(
					Arrays.copyOfRange(arguments, 1, arguments.length), in, out, err);
				case PackageZstd.NAME -> PackageZstd.run(scope, Arrays.copyOfRange(arguments, 1, arguments.length),
					Path.of("").toAbsolutePath(), environment, out, err);
				default -> usage(err);
			};
		}
		catch (IOException | IllegalArgumentException failure)
		{
			err.println("ERROR: " + failure.getMessage());
			return 1;
		}
	}

	/**
	 * Finds the watcher-argument boundary before the build command separator.
	 *
	 * @param arguments complete command arguments
	 * @return index of the first literal separator, or the argument count
	 */
	private static int watcherOptionsEnd(String[] arguments)
	{
		for (int index = 1; index < arguments.length; index += 1)
			if (arguments[index].equals("--"))
				return index;
		return arguments.length;
	}

	/**
	 * Indicates whether a direct dump option occurs among watcher options before the separator.
	 *
	 * @param arguments complete command arguments
	 * @return true when watcher options select direct-dump mode
	 */
	private static boolean directDumpRequested(String[] arguments)
	{
		for (int index = 1; index < watcherOptionsEnd(arguments); index += 1)
			if (arguments[index].equals("--dump"))
				return true;
		return false;
	}

	/**
	 * Indicates whether arguments select normal watch mode rather than direct dump mode.
	 *
	 * @param arguments complete command arguments
	 * @return true when normal watcher invocation applies
	 */
	private static boolean normalWatchCommand(String[] arguments)
	{
		return arguments.length > 0 && arguments[0].equals(WATCH_QUIET_BUILD_COMMAND) &&
			!directDumpRequested(arguments);
	}

	/**
	 * Writes one direct host report after the dump protocol has been validated.
	 *
	 * @param scope invocation-owned shared services
	 * @param arguments validated by the dump command before this callback runs
	 * @param out caller-owned report output
	 * @param environment complete process environment supplied by the command boundary
	 * @return zero when the report is written
	 * @throws IOException if report generation or output fails
	 */
	private static int runQuietBuildDump(ToolingScope scope, String[] arguments, PrintStream out,
		Map<String, String> environment) throws IOException
	{
		return QuietBuildDumpCommand.run(arguments, out, (jcmd, writer) ->
		{
			QuietBuildCapture.Context context = new QuietBuildCapture.Context(Path.of("").toAbsolutePath(),
				Path.of(System.getProperty("java.io.tmpdir")), environment);
			QuietBuildDump.Platform platform = quietBuildPlatform();
			QuietBuildDump.create(scope, new QuietBuildProcessAccess(), context, platform, Path.of("/proc")).dump(jcmd,
				writer);
		});
	}

	/**
	 * Selects the host platform supported by quiet-build operations.
	 *
	 * @return Linux, Darwin or Windows platform
	 * @throws IllegalArgumentException if the host operating system is unsupported
	 */
	private static QuietBuildDump.Platform quietBuildPlatform()
	{
		String osName = System.getProperty("os.name");
		if (osName == null || osName.isBlank())
			throw new IllegalArgumentException("Cannot determine the host operating system for watch-quiet-build --dump");

		String normalizedOsName = osName.toLowerCase(Locale.ROOT);
		if (normalizedOsName.startsWith("linux"))
			return QuietBuildDump.Platform.LINUX;
		if (normalizedOsName.startsWith("mac os x") || normalizedOsName.equals("darwin"))
			return QuietBuildDump.Platform.DARWIN;
		if (normalizedOsName.startsWith("windows"))
			return QuietBuildDump.Platform.WINDOWS;
		throw new IllegalArgumentException("Unsupported host operating system '" + osName +
			"' for quiet-build operations; supported hosts are Linux, Mac OS X/Darwin and Windows");
	}

	/**
	 * Writes an upstream V8 action's decoded script for execution in a caller-owned child environment.
	 *
	 * @param scope invocation-owned shared values
	 * @param arguments the command name and action document path
	 * @param out the unchanged decoded script
	 * @param err usage diagnostics
	 * @return zero on success or two when the action path argument is missing or extra arguments are supplied
	 * @throws IOException if the action document cannot be read
	 */
	private static int writeV8Script(ToolingScope scope, String[] arguments, PrintStream out, PrintStream err)
		throws IOException
	{
		if (arguments.length != 2)
			return usage(err);
		out.print(UpstreamV8Script.extract(scope, Files.readString(Path.of(arguments[1]))));
		return 0;
	}

	/**
	 * Reports the supported commands.
	 *
	 * @param err diagnostics
	 * @return the invalid-arguments status
	 */
	private static int usage(PrintStream err)
	{
		err.println("       codex-tooling " + WATCH_QUIET_BUILD_COMMAND +
			" --watch PATH --report PATH [--jcmd PATH] [--first-report SECONDS] [--interval SECONDS] " +
			"[--stop-after SECONDS] [--sample-interval SECONDS] [--cpu-interval SECONDS] [--min-cpu SECONDS] -- COMMAND");
		err.println("Usage: codex-tooling " + SourceCacheCommand.NAME +
			" [--record-source-inputs] [--profile release|ci-test] <workspace> <target> [binary ...]");
		err.println("       codex-tooling " + GhcrRetentionPlan.NAME + " < inventory.json");
		err.println("       codex-tooling " + ActionsCacheRetentionPlan.NAME + " < inventory.json");
		err.println("       codex-tooling " + AptInstallWithFallback.NAME + " [options] PACKAGE...");
		err.println("       codex-tooling " + PACKAGE_IDENTITY_COMMAND + " <source|binary> [options]");
		err.println("       codex-tooling " + WorkspaceLockAlignment.NAME + " --source-root ROOT");
		err.println("       codex-tooling " + PACKAGE_LICENSE_COMMAND + " <stage|check|finalize> [options]");
		err.println("       codex-tooling " + VoiceRuntime.NAME + " <stage|check|verify> [options]");
		err.println("       codex-tooling " + RELEASE_MANIFEST_COMMAND +
			" <receipt|assemble|verify|authenticate> [options]");
		err.println("       codex-tooling " + RELEASE_NATIVE_INPUTS_COMMAND + " [options]");
		err.println("       codex-tooling " + PruneWorkspaceOutputs.NAME + " [options]");
		err.println("       codex-tooling " + COMPARE_TEST_BASELINE_COMMAND + " [options] <report.xml>...");
		err.println("       codex-tooling " + CHECK_TEST_SKIPS_COMMAND + " [options] <report.xml>...");
		err.println("       codex-tooling " + BazelTestEvidence.NAME +
			" --bep PATH --expected-patterns PATH --bazel-exit-code INTEGER [--interrupted]");
		err.println("       codex-tooling " + UPSTREAM_AUTO_RELEASE_COMMAND + " <operation> [options]");
		err.println("       codex-tooling cargo-cache-keys <target> <upstream> <wrappers> <helper> <compiler> [<patches>]");
		err.println("       codex-tooling verify-cargo-lock <original> <current> <workspace-metadata>");
		err.println("       codex-tooling " + VerifyTuiCoreBoundary.NAME + " <repository-root>");
		err.println("       codex-tooling " + VerifyBazelClippyLints.NAME +
			" <repository-root> [--cargo-toml PATH] [--bazelrc PATH]");
		err.println("       codex-tooling cargo-cache-parts <push|pull> <repository> <reference> <previous> <target> " +
			"<temporary> [options]");
		err.println("       codex-tooling " + V8_SCRIPT_COMMAND + " <upstream-action.yml>");
		err.println("       codex-tooling " + BlobSizeCommand.NAME + " --base BASE --head HEAD [--repo PATH]");
		err.println("       codex-tooling " + CleanupOwnedCacheFixtures.NAME + " <request.json> <versions.json>");
		err.println("       codex-tooling " + MacosUnwindReport.NAME + " BINARY ARGUMENT...");
		err.println("       codex-tooling " + ReclaimBaselineMacosXcodes.NAME + " --evidence-directory DIRECTORY");
		err.println("       codex-tooling " + VerifyCodexCacheInvalidation.NAME);
		err.println("       codex-tooling " + VerifyReadmeCharacters.NAME + " <file>");
		err.println("       codex-tooling " + VerifyReadmeToc.NAME + " <file>");
		err.println("       codex-tooling " + VerifyV8ModuleChecksums.NAME + " <repository-root>");
		err.println("       codex-tooling " + VerifyWorkspaceLockVersions.NAME + " <repository-root>");
		err.println("       codex-tooling " + SelectBazelPackages.NAME + " --candidates <file> " +
			"--output-directory <directory> [--clippy-targets <file>] --shard <number> --shard-count <number>");
		err.println("       codex-tooling " + SelectWindowsBazelTargets.NAME +
			" --shard <number> --shard-count <number> --durations <file>");
		err.println("       codex-tooling " + PackageZstd.NAME + " --upstream-root <directory>");
		return 2;
	}
}
