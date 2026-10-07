package com.holdmyspot.codexunleashed.tooling;

import com.holdmyspot.codexunleashed.tooling.release.Installers;
import com.holdmyspot.codexunleashed.tooling.cache.CacheRetention;
import com.holdmyspot.codexunleashed.tooling.cache.CachePruner;
import com.holdmyspot.codexunleashed.tooling.cache.ReleaseCacheKeysCommand;
import com.holdmyspot.codexunleashed.tooling.release.ReleaseBuildNumbers;
import com.holdmyspot.codexunleashed.tooling.release.ReleaseTagCommand;
import com.holdmyspot.codexunleashed.tooling.release.ReleaseManifestCommand;
import com.holdmyspot.codexunleashed.tooling.release.LicensePayloadsCommand;
import com.holdmyspot.codexunleashed.tooling.release.PackageCommand;
import com.holdmyspot.codexunleashed.tooling.release.PackageVersions;
import com.holdmyspot.codexunleashed.tooling.release.DotSlashManifest;
import com.holdmyspot.codexunleashed.tooling.release.NpmPublishCommand;
import com.holdmyspot.codexunleashed.tooling.release.UpstreamReleaseDetector;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the project's build, release, and repository policy commands.
 */
public final class Main
{
	/**
	 * Prevents construction.
	 */
	private Main()
	{
	}

	/**
	 * Runs a tooling command and terminates with its status.
	 *
	 * @param args the command and its arguments
	 */
	public static void main(String[] args)
	{
		System.exit(run(args, System.in, System.out, System.err));
	}

	/**
	 * Runs a tooling command.
	 *
	 * @param args the command and its arguments
	 * @param in the caller-owned standard input stream
	 * @param out the standard output destination
	 * @param err the diagnostic destination
	 * @return zero on success, one on operational failure, or two on invalid arguments
	 * @throws NullPointerException if {@code args}, {@code in}, {@code out}, or {@code err} are null
	 */
	public static int run(String[] args, InputStream in, PrintStream out, PrintStream err)
	{
		return run(args, in, out, err, SystemCommands::run);
	}

	/**
	 * Runs a tooling command using the supplied external-command boundary.
	 *
	 * @param args the command and its arguments
	 * @param in the caller-owned standard input stream
	 * @param out the standard output destination
	 * @param err the diagnostic destination
	 * @param runner the external-command boundary
	 * @return zero on success, one on operational failure, or two on invalid arguments
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(String[] args, InputStream in, PrintStream out, PrintStream err, CommandRunner runner)
	{
		Objects.requireNonNull(args, "args");
		Objects.requireNonNull(in, "in");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		Objects.requireNonNull(runner, "runner");
		if (args.length == 0)
			return usage(err);
		try
		{
			return switch (args[0])
			{
				case "stage-release-installers" ->
				{
					if (args.length != 3)
						yield usage(err);
					Installers.stage(Path.of(args[1]), Path.of(args[2]));
					yield 0;
				}
				case "next-release-build-number" ->
				{
					if (args.length != 2)
						yield usage(err);
					// Reject invalid tags before waiting for a potentially live standard-input stream.
					ReleaseBuildNumbers.next(args[1], List.of());
					String inventory;
					try
					{
						inventory = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).
							onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(in.readAllBytes())).toString();
					}
					catch (CharacterCodingException failure)
					{
						throw new IOException("tag inventory is not valid UTF-8; supply UTF-8 text.", failure);
					}
					out.println(ReleaseBuildNumbers.next(args[1], inventory.lines().toList()));
					yield 0;
				}
				case "cache-prefix" ->
				{
					if (args.length != 3 || !"--upstream-ref".equals(args[1]))
						yield usage(err);
					out.println(CacheRetention.prefix(args[2]));
					yield 0;
				}
				case "stable-releases" ->
				{
					if (args.length != 1)
						yield usage(err);
					for (String tag : CachePruner.discoverRetainedReleases(runner))
						out.println(tag);
					yield 0;
				}
				case "prune-actions-caches" ->
				{
					if ((args.length != 3 && args.length != 4) || !"--repository".equals(args[1]) ||
						(args.length == 4 && !"--dry-run".equals(args[3])))
						yield usage(err);
					CachePruner.Result result = CachePruner.prune(args[2], args.length == 4, runner);
					out.println(JsonMapper.builder().build().writeValueAsString(Map.of(
						"retained_releases", result.retainedReleases(), "obsolete_cache_ids", result.obsoleteCacheIds(),
						"dry_run", result.dryRun())));
					yield 0;
				}
				case "check-ci-results" ->
				{
					if (args.length != 2)
						yield usage(err);
					yield CiResults.check(args[1], out);
				}
				case "release-cache-keys" -> ReleaseCacheKeysCommand.run(Arrays.copyOfRange(args, 1, args.length),
					out, err);
				case "resolved-v8-crate-version" ->
				{
					if (args.length != 2)
						yield usage(err);
					out.println(V8Versions.resolve(Path.of(args[1])));
					yield 0;
				}
				case "get-codex-package-version" ->
				{
					if (args.length != 2)
						yield usage(err);
					out.println(PackageVersions.resolve(Path.of(args[1]), System.getenv()));
					yield 0;
				}
				case "write-cargo-cache-manifest" ->
				{
					if (args.length != 2)
						yield usage(err);
					CargoCacheManifest.write(Path.of(args[1]), System.getenv());
					yield 0;
				}
				case "resource-manifest-assets" ->
				{
					if (args.length != 2)
						yield usage(err);
					for (DotSlashManifest.ReleaseAsset asset : DotSlashManifest.releaseAssets(Path.of(args[1])))
						out.println(asset.name() + "\t" + asset.size() + "\t" + asset.digest());
					yield 0;
				}
				case "normalize-source-timestamps" ->
				{
					if (args.length != 3)
						yield usage(err);
					SourceTimestamps.normalize(Path.of(args[1]), Long.parseLong(args[2]));
					yield 0;
				}
				case SourceCacheCommand.NAME -> SourceCacheCommand.run(Arrays.copyOfRange(args, 1, args.length), out, err);
				case "check-cargo-shear" ->
				{
					if ((args.length != 3 && args.length != 5) ||
						(args.length == 5 && !"--cargo-shear".equals(args[3])))
						yield usage(err);
					String executable = "cargo-shear";
					if (args.length == 5)
						executable = args[4];
					yield CargoShear.check(Path.of(args[1]), Path.of(args[2]), List.of(executable), out, runner,
						Path.of(System.getProperty("java.io.tmpdir")));
				}
				case "detect-upstream-release" ->
				{
					if (args.length != 4)
						yield usage(err);
					Path output = Path.of(args[2]);
					Path summary = Path.of(args[3]);
					UpstreamReleaseDetector.Detection detection = UpstreamReleaseDetector.detect(args[1], runner);
					UpstreamReleaseDetector.appendReports(detection, output, summary);
					yield 0;
				}
				case "v8-canary-changes" -> V8CanaryCommand.run(Arrays.copyOfRange(args, 1, args.length), out, err,
					runner);
				case "ensure-release-tag" -> ReleaseTagCommand.run(Arrays.copyOfRange(args, 1, args.length), out, err);
				case "generate-source-provenance" -> SourceProvenanceCommand.run(
					Arrays.copyOfRange(args, 1, args.length), out, err);
				case "generate-release-manifest" -> ReleaseManifestCommand.run(
					Arrays.copyOfRange(args, 1, args.length), out, err);
				case LicensePayloadsCommand.NAME -> LicensePayloadsCommand.run(
					Arrays.copyOfRange(args, 1, args.length), out, err);
				case PackageCommand.NAME -> PackageCommand.run(Arrays.copyOfRange(args, 1, args.length), out, err);
				case NpmPublishCommand.NAME -> NpmPublishCommand.run(Arrays.copyOfRange(args, 1, args.length), out, err);
				case BlobSizeCommand.NAME -> BlobSizeCommand.run(Arrays.copyOfRange(args, 1, args.length), out, err);
				case "verify-cargo-workspace-manifests" ->
				{
					if ((args.length != 2 && args.length != 3) ||
						(args.length == 3 && !args[2].equals("--upstream")))
						yield usage(err);
					if (args.length == 3)
						yield CargoManifestPolicy.checkUpstream(Path.of(args[1]), out, System.getenv());
					yield CargoManifestPolicy.check(Path.of(args[1]), out, System.getenv());
				}
				case BazelClippyCommand.NAME -> BazelClippyCommand.run(Arrays.copyOfRange(args, 1, args.length), out, err);
				case BazelCommand.NAME -> BazelCommand.run(Arrays.copyOfRange(args, 1, args.length), err);
				case V8Command.NAME -> V8Command.run(Arrays.copyOfRange(args, 1, args.length), out, err);
				case "verify-tui-core-boundary" ->
				{
					if (args.length != 2)
						yield usage(err);
					yield TuiBoundaryPolicy.check(Path.of(args[1]), out);
				}
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
	 * Prints command usage for invalid input.
	 *
	 * @param err the diagnostic destination
	 * @return the invalid-arguments status
	 */
	private static int usage(PrintStream err)
	{
		err.println("Usage: codex-tooling <command> <arguments>");
		err.println("  stage-release-installers <upstream-checkout> <release-directory>");
		err.println("  next-release-build-number <upstream-tag> (tag inventory on standard input)");
		err.println("  cache-prefix --upstream-ref <upstream-reference>");
		err.println("  stable-releases");
		err.println("  prune-actions-caches --repository <owner/name> [--dry-run]");
		err.println("  check-ci-results <needs-json>");
			err.println("  resolved-v8-crate-version <checkout>");
			err.println("  get-codex-package-version <checkout>");
			err.println("  write-cargo-cache-manifest <output>");
			err.println("  resource-manifest-assets <manifest>");
		err.println("  normalize-source-timestamps <checkout> <source-date-epoch>");
		err.println("  " + SourceCacheCommand.NAME + " [--record-source-inputs] <workspace> <target> [binary ...]");
		err.println("  check-cargo-shear <checkout> <baseline-json> [--cargo-shear <executable>]");
		err.println("  detect-upstream-release <owner/name> <github-output> <step-summary>");
		err.println("  v8-canary-changes <checkout> [--base <ref> --head <ref>] [--force]");
		err.println("  release-cache-keys --target <target> --compiler-fingerprint <sha256> --v8-version <version>");
		err.println("  ensure-release-tag --repository <owner/name> --tag <vendor-tag> " +
			"--artifact-run-id <id> [--api-base <url>]");
		err.println("  generate-source-provenance --upstream-checkout <checkout> --patch-repo <repository> " +
			"--output <report> [--upstream-ref <ref> --workflow-path <path> --workflow-sha <sha> --workflow-run-id <id>]");
		err.println("  generate-release-manifest --release-dir <release> --patch-repo <repository> " +
			"--output <manifest> --upstream-commit <commit> [metadata options]");
			err.println("  " + LicensePayloadsCommand.NAME + " --manifest <Cargo.toml> --output <licenses> " +
				"[--require-license-evidence]");
				err.println("  " + PackageCommand.NAME + " --repo <repository> [--workspace <checkout>] [package options]");
				err.println("  " + NpmPublishCommand.NAME + " [--tag <release> | --version <version>] [publication options]");
			err.println("  " + BlobSizeCommand.NAME + " --base <ref> --head <ref> --allowlist <file> [--max-bytes <limit>]");
			err.println("  verify-cargo-workspace-manifests <repository> [--upstream]");
			err.println("  verify-tui-core-boundary <repository>");
		err.println("  " + BazelClippyCommand.NAME + " <repository> [--cargo-toml <manifest>] [--bazelrc <configuration>]");
			err.println("  " + BazelCommand.NAME + " [startup options] <Bazel command> [arguments]");
			err.println("  " + V8Command.NAME + " <checkout> <operation> [options]");
		return 2;
	}
}
