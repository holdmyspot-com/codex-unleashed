package com.holdmyspot.codexunleashed.tooling;

import java.util.List;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

/** Checks target filename identity before the cache coordinator can create or remove files. */
public final class SourceCacheTargetsTest
{
	/** Creates target name tests. */
	public SourceCacheTargetsTest()
	{
	}

	/** Preserves native target triples and custom JSON target filenames without changing their case. */
	@Test
	public void retainsTargetFilenameSemantics()
	{
		requireThat(SourceCacheTargets.directoryName("x86_64-unknown-linux-gnu"), "directoryName").isEqualTo(
			"x86_64-unknown-linux-gnu");
		requireThat(SourceCacheTargets.directoryName("custom.target.json"), "directoryName").isEqualTo("custom.target");
		requireThat(SourceCacheTargets.directoryName("custom.JSON"), "directoryName").isEqualTo("custom.JSON");
		requireThat(SourceCacheTargets.directoryName(".json"), "directoryName").isEqualTo(".json");
		requireThat(SourceCacheTargets.directoryName(".custom.json"), "directoryName").isEqualTo(".custom");
	}

	/** Rejects absolute, nested, normalized, and traversal paths using the original target-directory diagnostic. */
	@Test
	public void rejectsEscapingAndAmbiguousTargets()
	{
		for (String target : List.of("", ".", "..", "../outside", "../outside.json", "/outside", "nested/target",
			"./target", "target/", "..json", "...json", "nul\u0000target"))
		{
			IllegalArgumentException failure = expectThrows(IllegalArgumentException.class,
				() -> SourceCacheTargets.directoryName(target));
			requireThat(failure.getMessage(), "message").startsWith("Invalid Cargo target directory name: ");
		}
	}
}
