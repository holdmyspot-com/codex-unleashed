package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxBuildHookServer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks the parts of {@link LinuxBuildHookServer} that need no native process.
 */
public final class LinuxBuildHookServerTest
{
	/** Creates a resource-free test instance. */
	public LinuxBuildHookServerTest()
	{
	}

	/**
	 * The workload's own preloads are split where {@code ld.so} splits them, at colons and spaces, and empty entries are
	 * dropped.
	 */
	@Test
	public void splitsInheritedPreloads()
	{
		requireThat(LinuxBuildHookServer.inheritedPreloads(Map.of("LD_PRELOAD", "/a.so: /b.so::/c.so ")), "preloads").
			isEqualTo(List.of(Path.of("/a.so"), Path.of("/b.so"), Path.of("/c.so")));
	}

	/**
	 * A workload without {@code LD_PRELOAD}, or with an empty one, has no preloads of its own.
	 */
	@Test
	public void absentPreloadsAreEmpty()
	{
		requireThat(LinuxBuildHookServer.inheritedPreloads(Map.of()), "absent").isEmpty();
		requireThat(LinuxBuildHookServer.inheritedPreloads(Map.of("LD_PRELOAD", "")), "empty").isEmpty();
	}
}
