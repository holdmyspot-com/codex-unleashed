package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.QuietBuildCapture;
import com.holdmyspot.codexunleashed.tooling.QuietBuildResources.WindowsCounters;
import com.holdmyspot.codexunleashed.tooling.WindowsQuietBuildResources;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.testng.SkipException;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Verifies native Windows reader acquisition and counter access on its designated host. */
public final class WindowsQuietBuildResourcesNativeTest
{
	/** Creates an independent Windows native reader test. */
	public WindowsQuietBuildResourcesNativeTest()
	{
	}

	/**
	 * The production reader acquires CPU and memory counters and accepts two consecutive native reads.
	 *
	 * @throws IOException if Windows counter acquisition or reading fails
	 */
	@Test
	public void readsNativeWindowsCounters() throws IOException
	{
		if (!System.getProperty("os.name").startsWith("Windows"))
			throw new SkipException("Windows native counters require a Windows host");

		QuietBuildCapture.Context context = new QuietBuildCapture.Context(Path.of("."), Path.of("."), Map.of());
		try (DefaultToolingScope scope = new DefaultToolingScope();
			WindowsQuietBuildResources reader = (WindowsQuietBuildResources) WindowsQuietBuildResources.factory(scope).
				open(context))
		{
			WindowsCounters first = reader.read();
			WindowsCounters second = reader.read();

			requireThat(first.memoryTotalBytes(), "firstMemoryTotalBytes").isGreaterThan(java.math.BigInteger.ZERO);
			requireThat(second.memoryTotalBytes(), "secondMemoryTotalBytes").isGreaterThan(java.math.BigInteger.ZERO);
			requireThat(first.cpuTicks().idle(), "firstIdleTicks").isGreaterThanOrEqualTo(java.math.BigInteger.ZERO);
			requireThat(second.cpuTicks().system(), "secondSystemTicks").isGreaterThanOrEqualTo(java.math.BigInteger.ZERO);
		}
	}
}
