package com.holdmyspot.codexunleashed.secrets;

import com.holdmyspot.codexunleashed.scheduler.LinuxProcessTree;
import java.io.IOException;

/**
 * Dispatches the Linux workload gate with a controlled native exec action.
 * The class that owns the behavior registers the implementation through {@link LinuxProcessTreeSecrets}.
 */
public interface LinuxProcessTreeAccess
{
	/**
	 * Dispatches using a local action representing the native exec boundary.
	 *
	 * @param mode release execution mode
	 * @param execution action observing whether direct exec is selected
	 * @param replacementDeadline absolute monotonic deadline for external replacement
	 * @throws IOException if external replacement expires
	 * @throws InterruptedException if the replacement wait is interrupted
	 */
	void executeAfterRelease(LinuxProcessTree.ExecutionMode mode, Runnable execution, long replacementDeadline)
		throws IOException, InterruptedException;
}
