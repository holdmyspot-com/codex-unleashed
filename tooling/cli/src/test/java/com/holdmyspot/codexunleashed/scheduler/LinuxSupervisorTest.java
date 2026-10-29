package com.holdmyspot.codexunleashed.scheduler;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

/**
 * Verifies supervisor input rejection before resource acquisition and immutable accepted outcomes.
 */
public final class LinuxSupervisorTest
{
	/**
	 * Creates supervisor contract tests.
	 */
	public LinuxSupervisorTest()
	{
	}

	/**
	 * Supplies waits that must be rejected before the nonexistent cgroup parent is resolved.
	 * @return nonpositive and overflowing waits
	 */
	@DataProvider
	public Object[][] invalidTimeouts()
	{
		return new Object[][] {{Duration.ZERO}, {Duration.ofNanos(-1)}, {Duration.ofSeconds(Long.MAX_VALUE)}};
	}

	/**
	 * Rejects the wait at the caller boundary, rather than failing later during cgroup acquisition.
	 * @param timeout the invalid wait
	 */
	@Test(dataProvider = "invalidTimeouts")
	public void rejectsInvalidTimeoutBeforeAcquisition(Duration timeout)
	{
		expectThrows(IllegalArgumentException.class, () -> LinuxSupervisor.prepare(unresolvedLaunch(), timeout));
	}

	/**
	 * Rejects a missing launch before acquiring resources.
	 */
	@Test
	public void rejectsNullLaunch()
	{
		expectThrows(NullPointerException.class, () -> LinuxSupervisor.prepare(null, Duration.ofSeconds(1)));
	}

	/**
	 * Rejects a missing wait before resolving the cgroup parent.
	 */
	@Test
	public void rejectsNullTimeout()
	{
		expectThrows(NullPointerException.class, () -> LinuxSupervisor.prepare(unresolvedLaunch(), null));
	}

	/**
	 * Keeps accepted child outcomes independent of later caller mutations and returned-map mutations.
	 */
	@Test
	public void copiesAcceptedChildOutcomes()
	{
		Map<Long, Integer> children = new HashMap<>();
		children.put(42L, -9);
		LinuxSupervisor.Result result = new LinuxSupervisor.Result(7, children, true);

		children.clear();
		assertEquals(result.adoptedExits(), Map.of(42L, -9));
		expectThrows(UnsupportedOperationException.class, () -> result.adoptedExits().clear());
	}

	/**
	 * Refuses outcomes that cannot identify an ordinary root exit or a real adopted process.
	 */
	@Test
	public void rejectsInvalidProcessOutcomes()
	{
		expectThrows(IllegalArgumentException.class, () -> new LinuxSupervisor.Result(-1, Map.of(), false));
		expectThrows(IllegalArgumentException.class, () -> new LinuxSupervisor.Result(256, Map.of(), false));
		expectThrows(IllegalArgumentException.class, () -> new LinuxSupervisor.Result(0, Map.of(0L, 0), false));
		expectThrows(NullPointerException.class, () -> new LinuxSupervisor.Result(0, null, false));
	}

	/**
	 * Creates a valid value whose nonexistent filesystem context must never be reached by validation tests.
	 * @return explicit launch inputs without acquiring state
	 */
	private LinuxProcessTree.Launch unresolvedLaunch()
	{
		Path parent = Path.of("unresolved-supervisor-parent").toAbsolutePath();
		return new LinuxProcessTree.Launch(parent, parent, parent.resolve("java"), parent.resolve("classes"),
			List.of(parent.resolve("workload").toString()), Map.of(), parent, 1001, 1001);
	}
}
