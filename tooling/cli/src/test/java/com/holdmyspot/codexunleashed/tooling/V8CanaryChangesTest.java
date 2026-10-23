package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.util.Set;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

/**
 * Verifies the separate general and Windows canary decisions and exact lockfile inputs.
 */
public final class V8CanaryChangesTest
{
	/**
	 * Creates the canary policy tests.
	 */
	public V8CanaryChangesTest()
	{
	}

	/**
	 * Preserves the narrower Windows matrix, forced builds, and version-change reasons.
	 */
	@Test
	public void distinguishesBuildMatrices()
	{
		V8CanaryChanges.Decision cargo = V8CanaryChanges.decide(Set.of("codex-rs/Cargo.toml"), "149.2.0", "149.2.0");
		requireThat(cargo.canaryRequired(), "canaryRequired").isTrue();
		requireThat(cargo.windowsSourceRequired(), "windowsSourceRequired").isFalse();
		requireThat(cargo.canaryReason(), "canaryReason").isEqualTo("codex-rs/Cargo.toml");
		requireThat(cargo.windowsSourceReason(), "windowsSourceReason").isEqualTo("no relevant changes");
		for (String path : new String[]{".github/scripts/rusty_v8_module_bazel.py",
			".github/actions/setup-ci/action.yml", ".github/scripts/setup-dev-drive.ps1"})
		{
			V8CanaryChanges.Decision decision = V8CanaryChanges.decide(Set.of(path), "149.2.0", "149.2.0");
			requireThat(decision.canaryRequired(), "canaryRequired").isTrue();
			requireThat(decision.windowsSourceRequired(), "windowsSourceRequired").isTrue();
			requireThat(decision.canaryReason(), "canaryReason").isEqualTo(path);
			requireThat(decision.windowsSourceReason(), "windowsSourceReason").isEqualTo(path);
		}
		V8CanaryChanges.Decision version = V8CanaryChanges.decide(Set.of(), "149.2.0", "150.0.0");
		requireThat(version.canaryRequired(), "canaryRequired").isTrue();
		requireThat(version.windowsSourceRequired(), "windowsSourceRequired").isTrue();
		requireThat(version.canaryReason(), "canaryReason").isEqualTo("v8 version changed from 149.2.0 to 150.0.0");
		requireThat(version.windowsSourceReason(), "windowsSourceReason").isEqualTo(version.canaryReason());
		requireThat(V8CanaryChanges.forced(), "forced").
			isEqualTo(new V8CanaryChanges.Decision(true, "manual workflow dispatch", true, "manual workflow dispatch"));
	}

	/**
	 * Preserves case-sensitive path matching, wildcard directory traversal, and sorted reason paths.
	 */
	@Test
	public void preservesPathMatching()
	{
		V8CanaryChanges.Decision paths = V8CanaryChanges.decide(Set.of("patches/v8_nested/deep.patch",
			"third_party/v8/nested/source.cc", ".BAZELRC", "unrelated.txt"), "149.2.0", "149.2.0");
		requireThat(paths.canaryRequired(), "canaryRequired").isTrue();
		requireThat(paths.windowsSourceRequired(), "windowsSourceRequired").isFalse();
		requireThat(paths.canaryReason(), "canaryReason").
			isEqualTo("patches/v8_nested/deep.patch, third_party/v8/nested/source.cc");
		V8CanaryChanges.Decision unrelated = V8CanaryChanges.decide(Set.of(".BAZELRC", "unrelated.txt"),
			"149.2.0", "149.2.0");
		requireThat(unrelated.canaryRequired(), "canaryRequired").isFalse();
		requireThat(unrelated.windowsSourceRequired(), "windowsSourceRequired").isFalse();
		requireThat(unrelated.canaryReason(), "canaryReason").isEqualTo("no relevant changes");
		V8CanaryChanges.Decision unicode = V8CanaryChanges.decide(Set.of("third_party/v8/\uE000",
			"third_party/v8/\uD800\uDC00"), "149.2.0", "149.2.0");
		requireThat(unicode.canaryReason(), "canaryReason").
			isEqualTo("third_party/v8/\uE000, third_party/v8/\uD800\uDC00");
	}

	/**
	 * Treats the Java replacement's policy changes like changes to the retained Python path.
	 */
	@Test
	public void recognizesReplacementSources()
	{
		String root = "tooling/cli/src/main/java/com/holdmyspot/codexunleashed/tooling/";
		for (String source : new String[]{"V8CanaryChanges.java", "V8CanaryCommand.java", "V8Versions.java"})
		{
			String path = root + source;
			V8CanaryChanges.Decision decision = V8CanaryChanges.decide(Set.of(path), "149.2.0", "149.2.0");
			requireThat(decision.canaryRequired(), "canaryRequired").isTrue();
			requireThat(decision.windowsSourceRequired(), "windowsSourceRequired").isTrue();
			requireThat(decision.canaryReason(), "canaryReason").isEqualTo(path);
			requireThat(decision.windowsSourceReason(), "windowsSourceReason").isEqualTo(path);
		}
	}

	/**
	 * Resolves only one exact V8 lockfile version, with no module URL fallback.
	 *
	 * @throws IOException if a valid lockfile cannot be parsed
	 */
	@Test
	public void requiresExactLockfileVersion() throws IOException
	{
		String cargoLock = "[[package]]\nname = 'other'\nversion = '1.0.0'\n" +
			"[[package]]\nname = 'v8'\nversion = '149.2.0'\n";
		requireThat(V8Versions.resolveLockfile(cargoLock), "version").isEqualTo("149.2.0");
		String repeated = cargoLock + "[[package]]\nname='v8'\nversion='149.2.0'\n";
		requireThat(V8Versions.resolveLockfile(repeated), "repeatedVersion").isEqualTo("149.2.0");
		expectThrows(IOException.class, () -> V8Versions.resolveLockfile(cargoLock.replace("'v8'", "'other'")));
		expectThrows(IOException.class, () -> V8Versions.resolveLockfile(cargoLock +
			"[[package]]\nname='v8'\nversion='150.0.0'\n"));
	}
}
