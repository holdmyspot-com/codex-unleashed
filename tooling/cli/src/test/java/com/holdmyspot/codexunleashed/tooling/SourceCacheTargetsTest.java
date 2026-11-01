package com.holdmyspot.codexunleashed.tooling;

import java.util.List;
import org.testng.annotations.Test;

import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks target filename identity before the cache coordinator can create or remove files.
 */
public final class SourceCacheTargetsTest
{
	/**
	 * Creates target name tests.
	 */
	public SourceCacheTargetsTest()
	{
	}

	/**
	 * Preserves native target triples and custom JSON target filenames without changing their case.
	 */
	@Test
	public void retainsTargetFilenameSemantics()
	{
		requireThat(SourceCacheTargets.directoryName("x86_64-unknown-linux-gnu"), "SourceCacheTargets")
			.isEqualTo("x86_64-unknown-linux-gnu");
		requireThat(SourceCacheTargets.directoryName("custom.target.json"), "SourceCacheTargets")
			.isEqualTo("custom.target");
		requireThat(SourceCacheTargets.directoryName("custom.JSON"), "SourceCacheTargets").isEqualTo("custom.JSON");
		requireThat(SourceCacheTargets.directoryName(".json"), "SourceCacheTargets").isEqualTo(".json");
		requireThat(SourceCacheTargets.directoryName(".custom.json"), "SourceCacheTargets").isEqualTo(".custom");
	}

	/**
	 * Rejects absolute, nested, normalized, and traversal paths with the target-directory diagnostic.
	 */
	@Test
	public void rejectsEscapingAndAmbiguousTargets()
	{
		for (String target : List.of("", ".", "..", "../outside", "../outside.json", "/outside", "nested/target",
			"./target", "target/", "..json", "...json", "nul\u0000target"))
		{
			IllegalArgumentException failure = requireException(IllegalArgumentException.class, () -> SourceCacheTargets.directoryName(target));
			requireThat(failure.getMessage().startsWith("Invalid Cargo target directory name: "), "failure").isTrue();
		}
	}
}
