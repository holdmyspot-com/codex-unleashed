package com.holdmyspot.codexunleashed.tooling;

import java.util.List;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
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
		assertEquals(SourceCacheTargets.directoryName("x86_64-unknown-linux-gnu"), "x86_64-unknown-linux-gnu");
		assertEquals(SourceCacheTargets.directoryName("custom.target.json"), "custom.target");
		assertEquals(SourceCacheTargets.directoryName("custom.JSON"), "custom.JSON");
		assertEquals(SourceCacheTargets.directoryName(".json"), ".json");
		assertEquals(SourceCacheTargets.directoryName(".custom.json"), ".custom");
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
			assertTrue(failure.getMessage().startsWith("Invalid Cargo target directory name: "));
		}
	}
}
