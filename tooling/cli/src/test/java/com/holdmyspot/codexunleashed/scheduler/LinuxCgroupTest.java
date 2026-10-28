package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies that cgroup acquisition rejects invalid context before creating owned state.
 */
public final class LinuxCgroupTest
{
	/** A valid close wait for acquisition checks that never launch work. */
	private static final Duration VALID_TIMEOUT = Duration.ofSeconds(1);

	/**
	 * Creates acquisition-contract tests.
	 */
	public LinuxCgroupTest()
	{
	}

	/**
	 * Supplies nonpositive waits and a wait that cannot be represented in signed nanoseconds.
	 *
	 * @return rejected close timeouts
	 */
	@DataProvider
	public Object[][] invalidTimeouts()
	{
		return new Object[][] {
			{Duration.ZERO},
			{Duration.ofNanos(-1)},
			{Duration.ofSeconds(Long.MAX_VALUE)}
		};
	}

	/**
	 * Rejects invalid close waits before resolving or modifying the parent.
	 *
	 * @param timeout the out-of-range close wait
	 */
	@Test(dataProvider = "invalidTimeouts")
	public void rejectsInvalidTimeout(Duration timeout)
	{
		Path parent = Path.of("unresolved-cgroup-parent").toAbsolutePath();

		expectThrows(IllegalArgumentException.class, () -> LinuxCgroup.create(parent, timeout));
	}

	/**
	 * Rejects a relative parent before filesystem resolution.
	 */
	@Test
	public void rejectsRelativeParent()
	{
		expectThrows(IllegalArgumentException.class,
			() -> LinuxCgroup.create(Path.of("relative-cgroup-parent"), VALID_TIMEOUT));
	}

	/**
	 * Rejects an absent parent at the acquisition boundary.
	 */
	@Test
	public void rejectsNullParent()
	{
		expectThrows(NullPointerException.class, () -> LinuxCgroup.create(null, VALID_TIMEOUT));
	}

	/**
	 * Rejects an absent close wait before filesystem resolution.
	 */
	@Test
	public void rejectsNullTimeout()
	{
		expectThrows(NullPointerException.class,
			() -> LinuxCgroup.create(Path.of("unresolved-cgroup-parent").toAbsolutePath(), null));
	}

	/**
	 * Rejects an ordinary filesystem without creating child directories or control files.
	 *
	 * @throws IOException if the owned fixture cannot be created, inspected or removed
	 */
	@Test
	public void rejectsOrdinaryFilesystemBeforeCreatingState() throws IOException
	{
		Path parent = Files.createTempDirectory("cgroup-unsupported-");
		try
		{
			expectThrows(UnsupportedOperationException.class, () -> LinuxCgroup.create(parent, VALID_TIMEOUT));
			try (var entries = Files.list(parent))
			{
				assertTrue(entries.findAny().isEmpty(), "Rejected acquisition created filesystem state");
			}
		}
		finally
		{
			try (var entries = Files.walk(parent))
			{
				for (Path path : entries.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}
}
