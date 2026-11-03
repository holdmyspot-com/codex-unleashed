package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.Main;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies source-cache command dispatch and argument failures before Cargo or cache writes.
 */
public final class SourceCacheCommandTest
{
	/**
	 * Creates command tests.
	 */
	public SourceCacheCommandTest()
	{
	}

	/**
	 * Requires confined target validation before resolving a nonexistent workspace.
	 */
	@Test
	public void validatesTargetBeforeWorkspace()
	{
		ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
		try (PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[]{"clean-cached-release-binaries", "missing-workspace", "../outside",
				"codex"}, InputStream.nullInputStream(), err, err);
			requireThat(status, "status").isEqualTo(2);
			requireThat(diagnostics.toString(StandardCharsets.UTF_8).contains("Invalid Cargo target directory name"), "actual")
				.isTrue();
		}
	}

	/**
	 * Applies explicit ci-test selection before validating the target or touching the workspace.
	 */
	@Test
	public void selectsTestProfileBeforeWorkspace()
	{
		ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
		try (PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[]{"clean-cached-release-binaries", "--profile", "ci-test",
				"missing-workspace", "../outside", "codex"}, InputStream.nullInputStream(), err, err);
			requireThat(status, "status")
				.withContext(diagnostics.toString(StandardCharsets.UTF_8), "diagnostic")
				.isEqualTo(2);
			requireThat(diagnostics.toString(StandardCharsets.UTF_8).contains("Invalid Cargo target directory name"), "actual")
				.isTrue();
		}
	}

	/**
	 * Refuses unsupported profile names before opening a workspace or starting Cargo.
	 */
	@Test
	public void refusesUnsupportedProfile()
	{
		ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
		try (PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[]{"clean-cached-release-binaries", "--profile", "../outside",
				"missing-workspace", "native-target", "codex"}, InputStream.nullInputStream(), err, err);
			requireThat(status, "status")
				.withContext(diagnostics.toString(StandardCharsets.UTF_8), "diagnostic")
				.isEqualTo(2);
			requireThat(diagnostics.toString(StandardCharsets.UTF_8).contains("Unsupported Cargo cache profile"), "diagnostics")
				.isTrue();
		}
	}

	/**
	 * Distinguishes operational workspace failures from malformed command arguments.
	 */
	@Test
	public void recordReportsWorkspaceFailure()
	{
		ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
		try (PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[]{"clean-cached-release-binaries", "--record-source-inputs",
				"missing-workspace", "native-target", "ignored-binary"}, InputStream.nullInputStream(), err, err);
			requireThat(status, "status").isEqualTo(1);
			requireThat(diagnostics.toString(StandardCharsets.UTF_8).contains("ERROR:"), "diagnostics").isTrue();
		}
	}
}
