package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DarwinQuietBuildResources;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.QuietBuildProcessAccess;
import com.holdmyspot.codexunleashed.tooling.QuietBuildResources;
import java.io.IOException;
import java.util.Locale;
import org.testng.SkipException;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Exercises native Darwin counter acquisition on the designated operating system. */
public final class DarwinQuietBuildResourcesNativeTest
{
	/** Creates Darwin native reader tests. */
	public DarwinQuietBuildResourcesNativeTest()
	{
	}

	/**
	 * Acquires all four Mach CPU tick fields through the production reader factory on Darwin.
	 *
	 * @throws IOException if process context or native capture fails
	 */
	@Test
	public void readsNativeDarwinTicks() throws IOException
	{
		String operatingSystem = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (!operatingSystem.contains("mac") && !operatingSystem.contains("darwin"))
			throw new SkipException("Native Darwin reader test requires Darwin");

		try (DefaultToolingScope scope = new DefaultToolingScope();
			DarwinQuietBuildResourcesTest.TestContext fixture = DarwinQuietBuildResourcesTest.openContext(scope);
			QuietBuildResources.Reader reader = DarwinQuietBuildResources.factory(scope, new QuietBuildProcessAccess()).
				open(fixture.context()))
		{
			QuietBuildResources.DarwinCounters counters = (QuietBuildResources.DarwinCounters) reader.read();
			requireThat(counters.cpuTicks().user(), "userTicks").isNotNull();
			requireThat(counters.cpuTicks().system(), "systemTicks").isNotNull();
			requireThat(counters.cpuTicks().idle(), "idleTicks").isNotNull();
			requireThat(counters.cpuTicks().nice(), "niceTicks").isNotNull();
		}
	}
}
