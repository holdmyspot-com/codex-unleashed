package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.QuietBuildCommand;
import com.holdmyspot.codexunleashed.tooling.QuietBuildCoordinator;
import com.holdmyspot.codexunleashed.tooling.QuietBuildProcess;
import java.util.List;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks quiet-build process exit status mapping.
 */
public final class QuietBuildCommandTest
{
	/**
	 * Creates an exit-status contract test.
	 */
	public QuietBuildCommandTest()
	{
	}

	/**
	 * Maps every process completion and coordinator outcome to its caller-visible status.
	 */
	@Test
	public void mapsCoordinatorResultsToProcessStatus()
	{
		List<QuietBuildCoordinator.Result> results = List.of(
			new QuietBuildCoordinator.Completed(new QuietBuildProcess.UnixExit(0)),
			new QuietBuildCoordinator.Completed(new QuietBuildProcess.UnixExit(7)),
			new QuietBuildCoordinator.Completed(new QuietBuildProcess.UnixExit(255)),
			new QuietBuildCoordinator.Completed(new QuietBuildProcess.UnixSignal(15, false)),
			new QuietBuildCoordinator.Completed(new QuietBuildProcess.WindowsExit(0xffff_ffffL)),
			new QuietBuildCoordinator.MonitoringFailed(),
			new QuietBuildCoordinator.QuietTimeout());
		List<Integer> expectedStatuses = List.of(0, 7, 255, -15, -1, 1, 124);

		for (int index = 0; index < results.size(); index += 1)
			requireThat(QuietBuildCommand.exitStatus(results.get(index)), "status").isEqualTo(expectedStatuses.get(index));
	}
}
