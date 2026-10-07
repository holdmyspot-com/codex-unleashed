package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Selects the general V8 canary and its narrower Windows source-build matrix.
 */
public final class V8CanaryChanges
{
	private static final String JAVA_POLICY_ROOT =
		"tooling/cli/src/main/java/com/holdmyspot/codexunleashed/tooling/";
	private static final Set<String> SHARED_PATHS = Set.of(
		".github/scripts/rusty_v8_bazel.py", ".github/scripts/rusty_v8_module_bazel.py",
		".github/scripts/setup-dev-drive.ps1", ".github/scripts/v8_canary_changes.py",
		".github/workflows/rusty-v8-release.yml", ".github/workflows/v8-canary.yml",
		JAVA_POLICY_ROOT + "V8CanaryChanges.java", JAVA_POLICY_ROOT + "V8CanaryCommand.java",
		JAVA_POLICY_ROOT + "V8Versions.java");
	private static final Set<String> CANARY_PATHS = Set.of(".bazelrc",
		".github/scripts/run_bazel_with_buildbuddy.py", ".github/workflows/postmerge-ci.yml",
		"MODULE.bazel", "MODULE.bazel.lock", "codex-rs/Cargo.toml", "patches/BUILD.bazel");
	private static final List<String> CANARY_PREFIXES = List.of(".github/actions/setup-bazel-ci/", "third_party/v8/");
	private static final List<String> PATCH_PREFIXES = List.of("patches/llvm_", "patches/rules_cc_", "patches/v8_");
	private static final String SHARED_PREFIX = ".github/actions/setup-ci/";

	/**
	 * Prevents construction.
	 */
	private V8CanaryChanges()
	{
	}

	/**
	 * Describes both build decisions and their workflow reasons.
	 *
	 * @param canaryRequired indicates whether the general matrix runs
	 * @param canaryReason the general matrix reason
	 * @param windowsSourceRequired indicates whether Windows rebuilds V8 from source
	 * @param windowsSourceReason the Windows source-build reason
	 */
	public record Decision(boolean canaryRequired, String canaryReason,
		boolean windowsSourceRequired, String windowsSourceReason)
	{
		/**
		 * Creates build decisions with explicit reason strings.
		 *
		 * @param canaryRequired indicates whether the general matrix runs
		 * @param canaryReason the general matrix reason
		 * @param windowsSourceRequired indicates whether Windows rebuilds V8 from source
		 * @param windowsSourceReason the Windows source-build reason
		 * @throws NullPointerException if either reason is null
		 */
		public Decision
		{
			Objects.requireNonNull(canaryReason, "canaryReason");
			Objects.requireNonNull(windowsSourceReason, "windowsSourceReason");
		}
	}

	/**
	 * Enables both matrices for manual workflow dispatch.
	 *
	 * @return the forced build decisions
	 */
	public static Decision forced()
	{
		String reason = "manual workflow dispatch";
		return new Decision(true, reason, true, reason);
	}

	/**
	 * Compares a Git range using three-dot paths and the merge-base V8 version.
	 *
	 * @param checkout the upstream checkout
	 * @param base the base revision
	 * @param head the head revision
	 * @param runner the Git command boundary
	 * @return the build decisions
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if either revision is empty
	 * @throws IOException if Git fails or a revision's lockfile is malformed or ambiguous
	 */
	public static Decision compare(Path checkout, String base, String head, CommandRunner runner) throws IOException
	{
		Objects.requireNonNull(checkout, "checkout");
		Objects.requireNonNull(base, "base");
		Objects.requireNonNull(head, "head");
		Objects.requireNonNull(runner, "runner");
		if (base.isEmpty() || head.isEmpty())
			throw new IllegalArgumentException("--base and --head are required unless --force is set");
		String root = checkout.toAbsolutePath().normalize().toString();
		Set<String> paths = Set.copyOf(runner.run(List.of("git", "-C", root, "diff", "--name-only", "--no-renames",
			base + "..." + head)).lines().toList());
		String mergeBase = runner.run(List.of("git", "-C", root, "merge-base", base, head)).strip();
		String baseVersion = V8Versions.resolveLockfile(runner.run(List.of("git", "-C", root, "show",
			mergeBase + ":codex-rs/Cargo.lock")));
		String headVersion = V8Versions.resolveLockfile(runner.run(List.of("git", "-C", root, "show",
			head + ":codex-rs/Cargo.lock")));
		return decide(paths, baseVersion, headVersion);
	}

	/**
	 * Selects matrices from raw repository paths and exact resolved V8 versions.
	 * Wildcards in the retained path policy span directory separators and matching remains case-sensitive.
	 *
	 * @param changedFiles the changed repository-relative paths
	 * @param baseVersion the merge-base V8 version
	 * @param headVersion the head V8 version
	 * @return the build decisions and sorted path or version reasons
	 * @throws NullPointerException if any argument or path is null
	 */
	public static Decision decide(Set<String> changedFiles, String baseVersion, String headVersion)
	{
		Objects.requireNonNull(changedFiles, "changedFiles");
		Objects.requireNonNull(baseVersion, "baseVersion");
		Objects.requireNonNull(headVersion, "headVersion");
		for (String path : changedFiles)
			Objects.requireNonNull(path, "changedFiles element");
		if (!baseVersion.equals(headVersion))
		{
			String reason = "v8 version changed from " + baseVersion + " to " + headVersion;
			return new Decision(true, reason, true, reason);
		}

		SortedSet<String> canary = sortedPaths();
		SortedSet<String> windows = sortedPaths();
		for (String path : changedFiles)
		{
			if (SHARED_PATHS.contains(path) || path.startsWith(SHARED_PREFIX))
			{
				canary.add(path);
				windows.add(path);
			}
			else if (CANARY_PATHS.contains(path) ||
				CANARY_PREFIXES.stream().anyMatch(path::startsWith) ||
				(path.endsWith(".patch") && PATCH_PREFIXES.stream().anyMatch(path::startsWith)))
				canary.add(path);
		}
		return new Decision(!canary.isEmpty(), reason(canary), !windows.isEmpty(), reason(windows));
	}

	/**
	 * Creates path ordering by Unicode code point, including supplementary characters.
	 *
	 * @return the empty sorted path set
	 */
	private static SortedSet<String> sortedPaths()
	{
		return new TreeSet<>((first, second) ->
			Arrays.compare(first.codePoints().toArray(), second.codePoints().toArray()));
	}

	/**
	 * Formats the path reason or its explicit absence.
	 *
	 * @param paths the sorted matching paths
	 * @return the workflow reason
	 */
	private static String reason(SortedSet<String> paths)
	{
		if (paths.isEmpty())
			return "no relevant changes";
		return String.join(", ", paths);
	}
}
