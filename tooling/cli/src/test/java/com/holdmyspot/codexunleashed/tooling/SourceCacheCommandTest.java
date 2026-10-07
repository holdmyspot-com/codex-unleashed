package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/** Verifies source-cache command dispatch and argument failures before Cargo or cache writes. */
public final class SourceCacheCommandTest
{
	/** Creates command tests. */
	public SourceCacheCommandTest()
	{
	}

	/** Requires confined target validation before resolving a nonexistent workspace. */
	@Test
	public void validatesTargetBeforeWorkspace()
	{
		ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
		try (PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[]{"clean-cached-release-binaries", "missing-workspace", "../outside",
				"codex"}, InputStream.nullInputStream(), err, err);
			assertEquals(status, 2);
			assertTrue(diagnostics.toString(StandardCharsets.UTF_8).contains("Invalid Cargo target directory name"));
		}
	}

	/** Distinguishes operational workspace failures from malformed command arguments. */
	@Test
	public void recordIgnoresBinaryArguments()
	{
		ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
		try (PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[]{"clean-cached-release-binaries", "--record-source-inputs",
				"missing-workspace", "native-target", "ignored-binary"}, InputStream.nullInputStream(), err, err);
			assertEquals(status, 1);
			assertTrue(diagnostics.toString(StandardCharsets.UTF_8).contains("ERROR:"));
		}
	}
}
