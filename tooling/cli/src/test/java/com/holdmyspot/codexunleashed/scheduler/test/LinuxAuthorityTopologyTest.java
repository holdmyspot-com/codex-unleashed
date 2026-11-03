package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxAuthorityReader;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.io.FileUtils;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises real native path acquisition and topology enumeration without acquiring cgroup memory authority.
 */
public final class LinuxAuthorityTopologyTest
{
	/**
	 * Creates isolated topology contract tests.
	 */
	public LinuxAuthorityTopologyTest()
	{
	}

	/**
	 * Requires the platform whose native path-acquisition contract these fixtures exercise.
	 * @throws SkipException if this JVM does not run on Linux
	 */
	private static void requireLinux()
	{
		if (!System.getProperty("os.name").equals("Linux"))
			throw new SkipException("Linux authority topology requires native Linux path acquisition");
	}

	/**
	 * Supplies intact topology, verified leaf removal and independent failures that must remain unavailable.
	 * @return fixture kind and expected candidate decision, or null for an ordinary I/O failure
	 */
	@DataProvider
	public Object[][] topologies()
	{
		return new Object[][] {
			{"present", false},
			{"leaf-removed", true},
			{"foreign-child", null},
			{"ancestor-removed", null},
			{"leaf-symlink", null},
			{"leaf-file", null}
		};
	}

	/**
	 * Nominates only the absent registered leaf; extra groups, missing ancestors and invalid native acquisitions fail.
	 * @param kind isolated directory arrangement
	 * @param expected whether the leaf needs ownership rechecking, or null for an ordinary I/O failure
	 * @throws IOException if fixture setup, an accepted observation or cleanup fails
	 */
	@Test(dataProvider = "topologies")
	public void rejectsUnverifiedTopology(String kind, Boolean expected) throws IOException
	{
		requireLinux();
		Path fixture = Files.createTempDirectory("linux-authority-topology-").toRealPath();
		try (var scope = new DefaultToolingScope())
		{
			Path directory = Files.createDirectory(fixture.resolve("owned"));
			Path leaf = directory.resolve("leaf");
			switch (kind)
			{
				case "present" -> Files.createDirectory(leaf);
				case "leaf-removed" -> { }
				case "foreign-child" ->
				{
					Files.createDirectory(leaf);
					Files.createDirectory(directory.resolve("foreign"));
				}
				case "ancestor-removed" -> leaf = directory.resolve("ancestor/leaf");
				case "leaf-symlink" -> Files.createSymbolicLink(leaf, Files.createDirectory(fixture.resolve("other")));
				case "leaf-file" -> Files.writeString(leaf, "unavailable\n");
				default -> throw new IllegalArgumentException("Unknown topology fixture: " + kind);
			}
			Path selectedLeaf = leaf;
			if (expected == null)
				requireException(IOException.class,
					() -> LinuxAuthorityReader.SharedSecrets.requiresLeafRecheck(scope, fixture, directory, selectedLeaf));
			else
				requireThat(LinuxAuthorityReader.SharedSecrets.requiresLeafRecheck(scope, fixture, directory, selectedLeaf),
					"requiresLeafRecheck").isEqualTo(expected);
		}
		finally
		{
			FileUtils.deleteDirectory(fixture.toFile());
		}
	}
}
