package com.holdmyspot.codexunleashed.scheduler;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Checks resource-free launch restrictions; native ownership acceptance belongs to LinuxAttemptProbe. */
public final class LinuxAttemptTest
{
	/** Creates isolated configuration tests. */
	public LinuxAttemptTest() {}

	/** Command validation accesses no paths and permits only explicit attached execution. */
	@Test
	public void validatesWithoutAcquisition()
	{
		var launch = AllocationHooksTest.launch(List.of("/does-not-exist"), Map.of());
		var files = new LinuxProcessTree.StandardFiles(Path.of("/missing-in"), Path.of("/missing-out"), Path.of("/missing-err"));
		var command = new LinuxAttempts.Command(launch, files);
		requireThat(command.launch(), "resourceFreeInput").isEqualTo(launch, "expected");
		var direct = new LinuxProcessTree.Launch(launch.cgroupParent(), launch.scratchParent(), launch.javaExecutable(),
			launch.classPath(), launch.command(), launch.environment(), launch.directory(), launch.uid(), launch.gid());
		requireException(IllegalArgumentException.class, () -> new LinuxAttempts.Command(direct, files));
		requireException(NullPointerException.class, () -> new LinuxAttempts.Command(null, files));
	}

	/** Control bounds retain overflow and nonpositive distinctions before any acquisition. */
	@Test
	public void validatesControlBound()
	{
		requireThat(LinuxAttempts.checkedTimeout(Duration.ofSeconds(30)), "bounded").isEqualTo(Duration.ofSeconds(30), "expected");
		requireException(IllegalArgumentException.class, () -> LinuxAttempts.checkedTimeout(Duration.ZERO));
		requireException(IllegalArgumentException.class, () -> LinuxAttempts.checkedTimeout(Duration.ofSeconds(-1)));
		requireException(IllegalArgumentException.class, () -> LinuxAttempts.checkedTimeout(Duration.ofSeconds(Long.MAX_VALUE)));
	}

	/** Fault collection precedes unregister; an unresolved supervisor prevents dependent unregister.
	 * @throws java.io.IOException if a valid close ordering is rejected
	 */
	@Test
	public void preservesFaultCollectionOrder() throws java.io.IOException
	{
		var events = new java.util.ArrayList<String>();
		LinuxAttempt.closeCollectedOwners(true, () -> events.add("supervisor"), () -> events.add("registration"));
		requireThat(events, "faultOrder").isEqualTo(List.of("supervisor", "registration"), "expected");
		events.clear();
		LinuxAttempt.closeCollectedOwners(false, () -> events.add("supervisor"), () -> events.add("registration"));
		requireThat(events, "normalOrder").isEqualTo(List.of("registration", "supervisor"), "expected");
		events.clear();
		java.io.IOException first = new java.io.IOException("Retained supervisor cleanup");
		requireThat(requireException(java.io.IOException.class, () -> LinuxAttempt.closeCollectedOwners(true,
			() -> { throw first; }, () -> events.add("registration"))), "firstCause").isReferenceEqualTo(first, "expected");
		requireThat(events.isEmpty(), "noUnregisterBeforeFaultCollection").isEqualTo(true, "expected");
	}
}
