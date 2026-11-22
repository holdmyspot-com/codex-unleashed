package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;

/**
 * Runs upstream stable-release selection, identity validation, and publication reconciliation.
 */
public final class UpstreamAutoRelease
{
	/**
	 * Implements the corresponding release workflow operation.
	 */
	private static final Pattern UPSTREAM_TAG = Pattern.compile("rust-v(\\d+)\\.(\\d+)\\.(\\d+)");
	/**
	 * Implements the corresponding release workflow operation.
	 */
	private static final Pattern FORK_TAG = Pattern.compile("rust-v(\\d+)\\.(\\d+)\\.(\\d+)\\+(\\d+)");
	/**
	 * Implements the corresponding release workflow operation.
	 */
	private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
	/**
	 * Implements the corresponding release workflow operation.
	 */
	private static final Pattern PATCH = Pattern.compile("^(\\d+)\\. \\[[^\\]]*]\\(([^)]+\\.patch)\\)\\s*$");
	/**
	 * Implements the corresponding release workflow operation.
	 */
	private static final String MARKER_PREFIX = "<!-- codex-unleashed-publication: ";
	/**
	 * Orders asset names according to Unicode code points.
	 */
	private static final Comparator<String> CODE_POINT_ORDER = (left, right) ->
		Arrays.compare(left.codePoints().toArray(), right.codePoints().toArray());

	/**
	 * Prevents construction.
	 */
	private UpstreamAutoRelease()
	{
	}

	/**
	 * Runs one release operation with shared JSON services and caller-owned output streams.
	 *
	 * @param scope operation input
	 * @param arguments operation input
	 * @param out operation input
	 * @param err operation input
	 * @return operation result
	 * @throws IOException if the operation fails
	 */
	public static int run(ToolingScope scope, String[] arguments, PrintStream out, PrintStream err) throws IOException
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		if (arguments.length == 0)
			return syntax(err, "Expected an upstream auto-release operation");
		try
		{
			Options options = new Options(arguments);
			switch (arguments[0])
			{
				case "detect" -> detect(scope, options, out);
				case "save-identity" -> saveIdentity(scope, options);
				case "restore-identity" -> restoreIdentity(scope, options, out);
				case "publication" -> publication(scope, options, out);
				case "patches" -> out.println(String.join(" ", patchQueue(Files.readString(options.path("--readme")))));
				case "notes" -> notes(options, out);
				case "summary" -> out.print(failureSummary(options.required("--upstream-tag"),
					options.required("--failing"), options.optional("--log-url")));
				default ->
				{
					return syntax(err, "Unsupported upstream auto-release operation: " + arguments[0]);
				}
			}
			return 0;
		}
		catch (ReleaseFailure failure)
		{
			err.println("upstream-auto-release: " + failure.getMessage());
			return 1;
		}
		catch (IllegalArgumentException failure)
		{
			err.println("upstream-auto-release: " + failure.getMessage());
			return 2;
		}
		catch (IOException failure)
		{
			err.println("upstream-auto-release: " + safeMessage(failure));
			return 1;
		}
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param scope operation input
	 * @param args operation input
	 * @param out operation input
	 * @throws IOException if the operation fails
	 */
	private static void detect(ToolingScope scope, Options args, PrintStream out) throws IOException
	{
		JsonNode upstream = readJsonArray(scope, args.path("--upstream-releases"));
		JsonNode fork = readJsonArray(scope, args.path("--fork-releases"));
		String requested = args.optional("--requested-tag");
		boolean draftRehearsal = "true".equals(args.optional("--draft-rehearsal"));
		List<Version> stable = new ArrayList<>();
		for (JsonNode release : upstream)
		{
			String tag = string(release, "tag_name");
			Version version = stableVersion(release, tag);
			if (version != null)
				stable.add(version);
		}
		Version candidate;
		if (requested != null && !requested.isEmpty())
		{
			String tag = requested;
			if (!requested.startsWith("rust-v"))
				tag = "rust-v" + requested;
			String selectedTag = tag;
			candidate = stable.stream().filter(value -> value.tag.equals(selectedTag)).findFirst().orElseThrow(() ->
				new InvocationException(selectedTag + " is not a stable (non-draft, non-prerelease) upstream release"));
		}
		else
			candidate = stable.stream().max((left, right) -> compareParts(left.parts, right.parts)).orElseThrow(() ->
				new InvocationException("The upstream release list contains no stable rust-vX.Y.Z release"));
		BigInteger build = BigInteger.ONE;
		List<List<Integer>> published = new ArrayList<>();
		for (JsonNode release : fork)
		{
			String tag = string(release, "tag_name");
			Version releaseVersion = forkVersion(tag);
			if (releaseVersion == null)
				continue;
			if (releaseVersion.parts.equals(candidate.parts))
				build = build.max(releaseVersion.build.add(BigInteger.ONE));
			if (!bool(release, "draft", false))
				published.add(releaseVersion.parts);
		}
		String version = dotted(candidate.parts);
		String forkTags = args.optional("--fork-tags");
		if (forkTags != null)
		{
			String prefix = "rust-v" + version + "+";
			for (JsonNode entry : readJsonArray(scope, Path.of(forkTags)))
			{
				if (!entry.isString())
					throw new InvocationException("Expected a string in the fork tag list");
				String name = entry.stringValue();
				if (name.startsWith(prefix))
				{
					Version tagVersion = forkVersion(name);
					if (tagVersion != null)
						build = build.max(tagVersion.build.add(BigInteger.ONE));
				}
			}
		}
		String tag = "rust-v" + version + "+" + build;
		String action = "release";
		String reason = "Upstream " + candidate.tag + " is not released by the fork";
		if (draftRehearsal)
			reason = "Draft rehearsal for upstream " + candidate.tag;
		else if (published.stream().anyMatch(candidate.parts::equals))
		{
			action = "noop";
			reason = "Upstream " + candidate.tag + " is already released by the fork";
		}
		else if ((requested == null || requested.isEmpty()) && published.stream().
			max(UpstreamAutoRelease::compareParts).
			filter(value -> compareParts(value, candidate.parts) > 0).isPresent())
		{
			List<Integer> latest = published.stream().max(UpstreamAutoRelease::compareParts).orElseThrow();
			action = "noop";
			reason = "The fork's latest release " + dotted(latest) + " is newer than the newest stable upstream release " +
				candidate.tag;
		}
		writeFields(out, Map.of("action", action, "reason", reason, "version", version, "build", build.toString(),
			"tag", tag, "release_name", version + "+" + build, "upstream_tag", candidate.tag),
			List.of("action", "reason", "version", "build", "tag", "release_name", "upstream_tag"));
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param release operation input
	 * @param tag operation input
	 * @return operation result
	 */
	private static Version stableVersion(JsonNode release, String tag)
	{
		if (bool(release, "draft", false) || bool(release, "prerelease", false))
			return null;
		Matcher matcher = UPSTREAM_TAG.matcher(tag);
		if (matcher.matches())
			return new Version(tag, parts(matcher, 1, 3), BigInteger.ZERO);
		return null;
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param tag operation input
	 * @return operation result
	 */
	private static Version forkVersion(String tag)
	{
		Matcher matcher = FORK_TAG.matcher(tag);
		if (matcher.matches() && matcher.group(4).matches("[1-9][0-9]*"))
			return new Version(tag, parts(matcher, 1, 3), new BigInteger(matcher.group(4)));
		return null;
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param matcher operation input
	 * @param first operation input
	 * @param last operation input
	 * @return operation result
	 */
	private static List<Integer> parts(Matcher matcher, int first, int last)
	{
		return List.of(Integer.parseInt(matcher.group(first)), Integer.parseInt(matcher.group(first + 1)),
			Integer.parseInt(matcher.group(last)));
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param left operation input
	 * @param right operation input
	 * @return operation result
	 */
	private static int compareParts(List<Integer> left, List<Integer> right)
	{
		for (int i = 0; i < left.size(); i += 1)
		{
			int result = Integer.compare(left.get(i), right.get(i));
			if (result != 0)
				return result;
		}
		return 0;
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param scope operation input
	 * @param args operation input
	 * @throws IOException if the operation fails
	 */
	private static void saveIdentity(ToolingScope scope, Options args) throws IOException
	{
		Path decisionFile = args.path("--decision-output");
		Map<String, String> decision = new LinkedHashMap<>();
		for (String line : Files.readAllLines(decisionFile))
		{
			int equals = line.indexOf('=');
			if (equals <= 0 || decision.put(line.substring(0, equals), line.substring(equals + 1)) != null)
				throw new InvocationException("Release decision output is malformed");
		}
		Set<String> expectedFields = Set.of("action", "reason", "version", "build", "tag", "release_name", "upstream_tag");
		if (!decision.keySet().equals(expectedFields))
			throw new InvocationException("Release decision output has invalid fields");
		BigInteger build = parsePositiveBuild(decision.get("build"), "build");
		Map<String, Object> document = new LinkedHashMap<>();
		document.put("format_version", 1);
		document.put("run_id", args.required("--run-id"));
		document.put("fork_commit", args.required("--fork-commit"));
		document.put("upstream_commit", args.optionalOrEmpty("--upstream-commit"));
		Map<String, Object> selected = new LinkedHashMap<>(decision);
		selected.put("build", build);
		document.put("decision", selected);
		validateIdentity(scope.getJsonMapper().valueToTree(document), document.get("run_id").toString(),
			document.get("fork_commit").toString());
		Files.writeString(args.path("--identity"), scope.getJsonMapper().writeValueAsString(document) + "\n",
			StandardCharsets.UTF_8);
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param scope operation input
	 * @param args operation input
	 * @param out operation input
	 * @throws IOException if the operation fails
	 */
	private static void restoreIdentity(ToolingScope scope, Options args, PrintStream out) throws IOException
	{
		JsonNode identity = readJsonObject(scope, args.path("--identity"));
		Map<String, String> decision = validateIdentity(identity, args.required("--run-id"),
			args.required("--fork-commit"));
		writeFields(out, decision, List.of("action", "reason", "version", "build", "tag", "release_name",
			"upstream_tag", "upstream_commit"));
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param identity operation input
	 * @param runId operation input
	 * @param forkCommit operation input
	 * @return operation result
	 */
	private static Map<String, String> validateIdentity(JsonNode identity, String runId, String forkCommit)
	{
		requireFields(identity, Set.of("format_version", "run_id", "fork_commit", "upstream_commit", "decision"),
			"Release identity has invalid fields; rerun failed jobs from the original attempt");
		if (!identity.path("format_version").isIntegralNumber() || identity.path("format_version").intValue() != 1 ||
			!runId.equals(text(identity, "run_id")) || !forkCommit.equals(text(identity, "fork_commit")))
			throw new ReleaseFailure("Release identity does not belong to this run and fork commit");
		JsonNode decision = identity.get("decision");
		requireFields(decision, Set.of("action", "reason", "version", "build", "tag", "release_name", "upstream_tag"),
			"Release identity has invalid decision fields");
		String action = text(decision, "action");
		String reason = text(decision, "reason");
		String version = text(decision, "version");
		if (!(action.equals("release") || action.equals("noop")) || reason == null ||
			version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))
			throw new InvocationException("Release identity has an invalid action or version");
		JsonNode buildNode = decision.get("build");
		if (buildNode == null || !buildNode.isIntegralNumber() || buildNode.bigIntegerValue().signum() < 1)
			throw new InvocationException("Release identity has an invalid version or build");
		String build = buildNode.bigIntegerValue().toString();
		String upstreamTag = "rust-v" + version;
		if (!text(decision, "tag").equals(upstreamTag + "+" + build) ||
			!text(decision, "release_name").equals(version + "+" + build) ||
			!text(decision, "upstream_tag").equals(upstreamTag))
			throw new InvocationException("Release identity has inconsistent version, build and tag");
		String upstreamCommit = text(identity, "upstream_commit");
		if (upstreamCommit == null || (action.equals("release") && !COMMIT.matcher(upstreamCommit).matches()))
			throw new InvocationException("Release identity has an invalid upstream commit");
		return Map.of("action", action, "reason", reason, "version", version,
			"build", build,
			"tag", upstreamTag + "+" + build, "release_name", version + "+" + build,
			"upstream_tag", upstreamTag, "upstream_commit", upstreamCommit);
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param scope operation input
	 * @param args operation input
	 * @param out operation input
	 * @throws IOException if the operation fails
	 */
	private static void publication(ToolingScope scope, Options args, PrintStream out) throws IOException
	{
		Path assetDirectory = args.path("--assets-directory");
		Map<String, String> digests = new TreeMap<>(CODE_POINT_ORDER);
		try (java.util.stream.Stream<Path> children = Files.list(assetDirectory))
		{
			for (Path path : children.sorted().toList())
				if (Files.isRegularFile(path))
					digests.put(path.getFileName().toString(), Sha256.digest(path));
		}
		if (digests.isEmpty())
			throw new InvocationException("No release assets in " + assetDirectory);
		String tag = args.required("--tag");
		String forkCommit = args.required("--fork-commit");
		String upstreamTag = args.required("--upstream-tag");
		String upstreamCommit = args.required("--upstream-commit");
		String tagCommit = args.optionalOrEmpty("--tag-commit");
		Version version = forkVersion(tag);
		if (version == null)
			throw new InvocationException("Invalid fork release tag: " + tag);
		if (!upstreamTag.equals(tag.substring(0, tag.indexOf('+'))) || !COMMIT.matcher(upstreamCommit).matches())
			throw new InvocationException("The publication upstream tag or commit is inconsistent with the built identity");
		if (!tagCommit.isEmpty() && !tagCommit.equals(forkCommit))
			throw new InvocationException(tag + " targets " + tagCommit + ", not the original fork commit " + forkCommit);
		JsonNode releases = readJsonArray(scope, args.path("--fork-releases"));
		boolean draftRehearsal = "true".equals(args.optional("--draft-rehearsal"));
		boolean otherPublished = false;
		List<JsonNode> matches = new ArrayList<>();
		for (JsonNode release : releases)
		{
			String releaseTag = string(release, "tag_name");
			Version releaseVersion = forkVersion(releaseTag);
			if (releaseTag.equals(tag))
				matches.add(release);
			else if (!draftRehearsal && !bool(release, "draft", false) && releaseVersion != null &&
				releaseVersion.parts.equals(version.parts))
				otherPublished = true;
		}
		if (matches.size() > 1)
			throw new InvocationException("Multiple release records for " + tag);
		String phase;
		String action;
		if (!matches.isEmpty())
		{
			JsonNode release = matches.getFirst();
			if (draftRehearsal && !bool(release, "draft", false))
				throw new ReleaseFailure(tag + " is already public; refusing draft rehearsal");
			String marker = publicationMarker(scope, tag, forkCommit, upstreamTag, upstreamCommit, digests,
				draftRehearsal);
			String body = text(release, "body");
			if (!forkCommit.equals(text(release, "target_commitish")) || body == null || !body.contains(marker))
				throw new ReleaseFailure(tag + " belongs to a different publication request; retry its original workflow run");
			Map<String, String> recorded = new TreeMap<>();
			JsonNode assets = release.get("assets");
			if (assets == null || !assets.isArray())
				throw new InvocationException(tag + " has invalid asset records");
			for (JsonNode asset : assets)
			{
				String name = text(asset, "name");
				String digest = text(asset, "digest");
				if (name == null || recorded.put(name, digest) != null)
					throw new InvocationException(tag + " has invalid asset records");
			}
			Map<String, String> expected = new TreeMap<>();
			digests.forEach((name, digest) -> expected.put(name, "sha256:" + digest));
			for (Map.Entry<String, String> entry : recorded.entrySet())
				if (!Objects.equals(expected.get(entry.getKey()), entry.getValue()))
					throw new InvocationException(tag + " assets differ from the original request: " + entry.getKey());
			if (bool(release, "draft", false))
			{
				if (otherPublished)
				{
					phase = "complete";
					action = "noop";
				}
				else
				{
					phase = "upload";
					action = "release";
				}
			}
			else
			{
				if (tagCommit.isEmpty())
					throw new ReleaseFailure(tag + " is published but its Git tag is missing");
				if (!recorded.equals(expected))
					throw new ReleaseFailure(tag + " published assets are incomplete; refusing to modify a published release");
				if (bool(release, "prerelease", false))
					phase = "verify";
				else
					phase = "complete";
				if (phase.equals("complete"))
					action = "noop";
				else
					action = "release";
			}
		}
		else if (otherPublished)
		{
			phase = "complete";
			action = "noop";
		}
		else
		{
			phase = "create";
			action = "release";
		}
		Path notes = args.path("--notes");
		String marker = publicationMarker(scope, tag, forkCommit, upstreamTag, upstreamCommit, digests,
			draftRehearsal);
		Files.writeString(notes, "\n" + marker + "\n", StandardCharsets.UTF_8,
			java.nio.file.StandardOpenOption.APPEND);
		writeFields(out, Map.of("action", action, "phase", phase, "tag", tag), List.of("action", "phase", "tag"));
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param scope operation input
	 * @param tag operation input
	 * @param forkCommit operation input
	 * @param upstreamTag operation input
	 * @param upstreamCommit operation input
	 * @param digests operation input
	 * @param draftRehearsal whether the request must remain a draft
	 * @return operation result
	 */
	private static String publicationMarker(ToolingScope scope, String tag, String forkCommit, String upstreamTag,
		String upstreamCommit, Map<String, String> digests, boolean draftRehearsal)
	{
		Map<String, String> sortedDigests = new TreeMap<>(CODE_POINT_ORDER);
		sortedDigests.putAll(digests);
		Map<String, Object> request = new TreeMap<>(CODE_POINT_ORDER);
		request.put("tag", tag);
		request.put("fork_commit", forkCommit);
		request.put("upstream_tag", upstreamTag);
		request.put("upstream_commit", upstreamCommit);
		request.put("asset_digests", sortedDigests);
		if (draftRehearsal)
			request.put("draft_rehearsal", true);
		DefaultPrettyPrinter printer = new DefaultPrettyPrinter(Separators.createDefaultInstance().
			withObjectNameValueSpacing(Separators.Spacing.AFTER).
			withObjectEntrySpacing(Separators.Spacing.AFTER).
			withArrayElementSpacing(Separators.Spacing.AFTER).
			withObjectEmptySeparator("").withArrayEmptySeparator(""));
		printer = printer.withObjectIndenter(DefaultPrettyPrinter.NopIndenter.instance()).
			withArrayIndenter(DefaultPrettyPrinter.NopIndenter.instance());
		String json = scope.getArtifactJsonMapper().writer().with(printer).
			with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(request);
		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.US_ASCII));
			return MARKER_PREFIX + java.util.HexFormat.of().formatHex(digest) + " -->";
		}
		catch (NoSuchAlgorithmException impossible)
		{
			throw new IllegalStateException("Java platform does not supply SHA-256", impossible);
		}
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param readme operation input
	 * @return operation result
	 */
	private static List<String> patchQueue(String readme)
	{
		List<String> paths = new ArrayList<>();
		for (String line : readme.split("\\R"))
		{
			Matcher matcher = PATCH.matcher(line);
			if (matcher.matches())
			{
				int number = parsePositive(matcher.group(1), "patch entry");
				if (number != paths.size() + 1)
					throw new InvocationException("Patch entry " + number + " where " + (paths.size() + 1) + " was expected");
				paths.add("patches/" + matcher.group(2));
			}
		}
		if (paths.isEmpty())
			throw new InvocationException("The patch README lists no numbered patch");
		return paths;
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param args operation input
	 * @param out operation input
	 * @throws IOException if the operation fails
	 */
	private static void notes(Options args, PrintStream out) throws IOException
	{
		String version = args.required("--version");
		BigInteger build = parsePositiveBuild(args.required("--build"), "build");
		String upstreamTag = args.required("--upstream-tag");
		String upstreamCommit = args.required("--upstream-commit");
		String forkCommit = args.required("--fork-commit");
		List<String> patches = patchQueue(Files.readString(args.path("--readme")));
		String changes = null;
		String changelog = args.optional("--changelog");
		if (changelog != null)
			changes = changelogSection(Files.readString(Path.of(changelog)), upstreamTag);
		String tag = "rust-v" + version + "%2B" + build;
		String base = "https://github.com/holdmyspot-com/codex-unleashed/releases";
		String patchLines = java.util.stream.IntStream.range(0, patches.size()).mapToObj(index ->
		{
			String path = patches.get(index);
			return (index + 1) + ". [`" + path.substring("patches/".length()) + "`](https://github.com/holdmyspot-com/" +
				"codex-unleashed/blob/" + forkCommit + "/" + path + ")";
		}).collect(java.util.stream.Collectors.joining("\n"));
		String changesSection = "";
		if (changes != null && !changes.isEmpty())
			changesSection = "## Changes\n\n" + changes + "\n\n";
		out.printf("# Codex Unleashed %s+%d%n%n> **Unofficial:** This distribution is not affiliated with " +
			"or endorsed by OpenAI.%n%n" +
			"Built from upstream [`%s`](https://github.com/openai/codex/releases/tag/%s) at commit " +
			"[`%s`](https://github.com/openai/codex/commit/%s) with this repository's patch queue applied in order.%n%n" +
			"## Install%n%nLinux and macOS:%n%n```sh%ncurl -fsSL %s/latest/download/install.sh | bash%n```%n%n" +
			"Windows:%n%n```powershell%npowershell -ExecutionPolicy ByPass -c " +
			"\"irm %s/latest/download/install.ps1 | iex\"%n```%n%n" +
			"To install exactly this release, replace `latest/download` with `download/%s` in either URL, " +
			"for example `%s/download/%s/install.sh`.%n%n" +
			"## Verify%n%nEach `codex-package-<target>.tar.gz` asset is listed in `SHA256SUMS`, " +
			"and GitHub shows the same SHA-256 digest on the asset. " +
			"The installers verify the digest before installing.%n%n" +
			"`codex-symbols-<target>.tar.gz` holds the debug symbols stripped from that target's binaries, " +
			"for symbolicating crash reports. It is listed in `SHA256SUMS` too.%n%n" +
			"%s## Patches applied%n%nThe patch queue at [`%s`](https://github.com/holdmyspot-com/" +
			"codex-unleashed/tree/%s/patches), in order:%n%n%s%n",
			version, build, upstreamTag, upstreamTag, upstreamCommit, upstreamCommit, base, base, tag, base, tag,
			changesSection,
			forkCommit.substring(0, Math.min(12, forkCommit.length())), forkCommit, patchLines);
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param changelog operation input
	 * @param upstreamTag operation input
	 * @return operation result
	 */
	private static String changelogSection(String changelog, String upstreamTag)
	{
		List<String> body = null;
		for (String line : changelog.split("\\R", -1))
		{
			if (line.startsWith("## "))
			{
				if (body != null)
					break;
				if (line.stripTrailing().equals("## Upstream " + upstreamTag))
						body = new ArrayList<>();
			}
			else if (body != null) body.add(line);
		}
		if (body == null)
			return null;
		int first = 0;
		int last = body.size();
		while (first < last && body.get(first).isBlank()) first += 1;
		while (last > first && body.get(last - 1).isBlank()) last -= 1;
		return String.join("\n", body.subList(first, last));
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param tag operation input
	 * @param failing operation input
	 * @param logUrl operation input
	 * @return operation result
	 */
	private static String failureSummary(String tag, String failing, String logUrl)
	{
		String log = "";
		if (logUrl != null)
			log = "\n\nLog: " + logUrl;
		return "## Automatic release of upstream " + tag + " failed\n\nFailing: **" + failing + "**" + log +
			"\n\nNothing was published: no tag, release or asset was created.\n";
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param scope operation input
	 * @param path operation input
	 * @return operation result
	 * @throws IOException if the operation fails
	 */
	private static JsonNode readJsonArray(ToolingScope scope, Path path) throws IOException
	{
		JsonNode value = parseJson(scope, Files.readString(path));
		if (value == null || !value.isArray())
			throw new InvocationException("Expected a JSON release list");
		return value;
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param scope operation input
	 * @param path operation input
	 * @return operation result
	 * @throws IOException if the operation fails
	 */
	private static JsonNode readJsonObject(ToolingScope scope, Path path) throws IOException
	{
		JsonNode value = parseJson(scope, Files.readString(path));
		if (value == null || !value.isObject())
			throw new InvocationException("Expected a JSON object");
		return value;
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param scope operation input
	 * @param text operation input
	 * @return operation result
	 */
	private static JsonNode parseJson(ToolingScope scope, String text)
	{
		try
		{
			return scope.getJsonMapper().readTree(text);
		}
		catch (RuntimeException failure)
		{
			throw new InvocationException("Malformed JSON input: " + safeMessage(failure));
		}
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param value operation input
	 * @param expected operation input
	 * @param message operation input
	 */
	private static void requireFields(JsonNode value, Set<String> expected, String message)
	{
		if (value == null || !value.isObject())
			throw new InvocationException(message);
		Set<String> actual = new HashSet<>();
		value.properties().forEach(entry -> actual.add(entry.getKey()));
		if (!actual.equals(expected))
			throw new InvocationException(message);
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param value operation input
	 * @param key operation input
	 * @return operation result
	 */
	private static String string(JsonNode value, String key)
	{
		String text = text(value, key);
		if (text == null)
			throw new InvocationException("Release record has invalid " + key);
		return text;
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param value operation input
	 * @param key operation input
	 * @return operation result
	 */
	private static String text(JsonNode value, String key)
	{
		JsonNode field = null;
		if (value != null)
			field = value.get(key);
		if (field != null && field.isString())
			return field.stringValue();
		return null;
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param value operation input
	 * @param key operation input
	 * @param defaultValue operation input
	 * @return operation result
	 */
	private static boolean bool(JsonNode value, String key, boolean defaultValue)
	{
		JsonNode field = null;
		if (value != null)
			field = value.get(key);
		if (field == null || field.isNull())
			return defaultValue;
		return field.isBoolean() && field.booleanValue();
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param value operation input
	 * @param name operation input
	 * @return operation result
	 */
	private static BigInteger parsePositiveBuild(String value, String name)
	{
		try
		{
			BigInteger result = new BigInteger(value);
			if (result.signum() > 0)
				return result;
		}
		catch (NumberFormatException _)
		{
			// The shared error below includes the original invalid value.
		}
		throw new InvocationException("Invalid " + name + ": " + value);
	}

	/**
	 * Parses a positive bounded patch sequence number.
	 *
	 * @param value operation input
	 * @param name operation input
	 * @return positive bounded number
	 */
	private static int parsePositive(String value, String name)
	{
		try
		{
			int result = Integer.parseInt(value);
			if (result > 0)
				return result;
		}
		catch (NumberFormatException _)
		{
			// The shared error below includes the original invalid value.
		}
		throw new InvocationException("Invalid " + name + ": " + value);
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param out operation input
	 * @param fields operation input
	 * @param order operation input
	 */
	private static void writeFields(PrintStream out, Map<String, String> fields, List<String> order)
	{
		for (String key : order)
		{
			if (fields.containsKey(key))
				out.println(key + "=" + fields.get(key));
		}
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param err operation input
	 * @param message operation input
	 * @return operation result
	 */
	private static int syntax(PrintStream err, String message)
	{
		err.println("upstream-auto-release: " + message);
		return 2;
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param failure operation input
	 * @return operation result
	 */
	private static String safeMessage(Exception failure)
	{
		String message = failure.getMessage();
		if (message == null)
			return failure.getClass().getSimpleName();
		return message;
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param parts operation input
	 * @return operation result
	 */
	private static String dotted(List<Integer> parts)
	{
		return String.join(".", parts.stream().map(String::valueOf).toList());
	}

	/**
	 * Implements the corresponding release workflow operation.
	 */
	private static final class Options
	{
		/**
		 * Parsed option values, indexed by name.
		 */
		private final Map<String, String> values = new LinkedHashMap<>();
		/**
		 * Parses arguments according to the selected command's option schema.
		 *
		 * @param arguments operation name and options
		 */
		Options(String[] arguments)
		{
			Set<String> allowed = switch (arguments[0])
			{
				case "detect" -> Set.of("--upstream-releases", "--fork-releases", "--fork-tags", "--requested-tag",
					"--draft-rehearsal");
				case "save-identity" -> Set.of("--identity", "--run-id", "--fork-commit", "--decision-output",
					"--upstream-commit");
				case "restore-identity" -> Set.of("--identity", "--run-id", "--fork-commit");
				case "publication" -> Set.of("--fork-releases", "--tag", "--fork-commit", "--upstream-tag",
					"--upstream-commit", "--tag-commit", "--assets-directory", "--notes", "--draft-rehearsal");
				case "patches" -> Set.of("--readme");
				case "notes" -> Set.of("--version", "--build", "--upstream-tag", "--upstream-commit",
					"--fork-commit", "--readme", "--changelog");
				case "summary" -> Set.of("--upstream-tag", "--failing", "--log-url");
				default -> Set.of();
			};
			for (int i = 1; i < arguments.length; i += 1)
			{
				String key = arguments[i];
				if (!allowed.contains(key) || i + 1 == arguments.length || !key.startsWith("--"))
					throw new InvocationException("Invalid or duplicate option: " + key);
				i += 1;
				if (values.put(key, arguments[i]) != null)
					throw new InvocationException("Invalid or duplicate option: " + key);
			}
			Set<String> required = new HashSet<>(allowed);
			required.remove("--draft-rehearsal");
			if (values.containsKey("--draft-rehearsal") && !"true".equals(values.get("--draft-rehearsal")))
				throw new InvocationException("--draft-rehearsal requires true");
			if (arguments[0].equals("detect"))
			{
				required.remove("--fork-tags");
				required.remove("--requested-tag");
			}
			if (arguments[0].equals("save-identity"))
				required.remove("--upstream-commit");
			if (arguments[0].equals("notes"))
				required.remove("--changelog");
			if (arguments[0].equals("summary"))
				required.remove("--log-url");
		if (!values.keySet().containsAll(required))
			throw new InvocationException("Missing required option: " +
				required.stream().filter(key -> !values.containsKey(key)).sorted().findFirst().orElseThrow());
		}

		/**
		 * Returns a required option value.
		 *
		 * @param key option name
		 * @return option value
		 */
		String required(String key)
		{
			String value = values.get(key);
			if (value == null)
				throw new InvocationException("Missing required option: " + key);
			return value;
		}

		/**
		 * Returns an optional option value.
		 *
		 * @param key option name
		 * @return option value, or null when absent
		 */
		String optional(String key)
		{
			return values.get(key);
		}

		/**
		 * Returns an optional value, defaulting to an empty string.
		 *
		 * @param key option name
		 * @return option value, or an empty string when absent
		 */
		String optionalOrEmpty(String key)
		{
			return values.getOrDefault(key, "");
		}

		/**
		 * Returns the required option as a filesystem path.
		 *
		 * @param key option name
		 * @return option path
		 */
		Path path(String key)
		{
			return Path.of(required(key));
		}
	}

	/**
	 * Implements the corresponding release workflow operation.
	 *
	 * @param tag record component
	 * @param parts record component
	 * @param build record component
	 */
	private record Version(String tag, List<Integer> parts, BigInteger build)
	{
	}

	/**
	 * Implements the corresponding release workflow operation.
	 */
	private static final class InvocationException extends IllegalArgumentException
	{
		/** Serialization identifier required by the exception superclass. */
		private static final long serialVersionUID = 1L;

		/**
		 * Creates an invalid invocation failure.
		 *
		 * @param message operation input
		 */
		InvocationException(String message)
		{
			super(message);
		}
	}

	/**
	 * Implements the corresponding release workflow operation.
	 */
	private static final class ReleaseFailure extends IllegalArgumentException
	{
		/** Serialization identifier required by the exception superclass. */
		private static final long serialVersionUID = 1L;

		/**
		 * Creates an unsafe publication conflict failure.
		 *
		 * @param message operation input
		 */
		ReleaseFailure(String message)
		{
			super(message);
		}
	}
}
