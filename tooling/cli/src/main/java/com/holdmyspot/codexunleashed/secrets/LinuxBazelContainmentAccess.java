package com.holdmyspot.codexunleashed.secrets;

import com.holdmyspot.codexunleashed.scheduler.LinuxBazelContainment;
import com.holdmyspot.codexunleashed.scheduler.LinuxBazelIdentity;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Runs Linux Bazel containment admission with a controlled group allocator.
 * The class that owns the behavior registers the implementation through {@link LinuxBazelContainmentSecrets}.
 */
public interface LinuxBazelContainmentAccess
{
	/**
	 * Runs production admission with a controlled independent group boundary.
	 *
	 * @param authenticator issuing policy
	 * @param owner authenticated native parent
	 * @param child authenticated prepared child
	 * @param preparedExecution complete trusted gate execution
	 * @param cgroupParent absolute normalized delegated parent
	 * @param timeout positive cleanup timeout
	 * @param groups independent exclusive group allocator
	 * @return production containment decision and retained lifecycle owner
	 * @throws IOException if native facts, migration, membership or cleanup fail
	 */
	LinuxBazelContainment admit(LinuxBazelIdentity authenticator, LinuxBazelIdentity.Owner owner,
		LinuxBazelIdentity.Child child, LinuxBazelIdentity.Execution preparedExecution, Path cgroupParent, Duration timeout,
		LinuxBazelContainment.Groups groups) throws IOException;
}
