package com.holdmyspot.codexunleashed.secrets;

import com.holdmyspot.codexunleashed.scheduler.LinuxCargoJobserver;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Acquires the Linux Cargo jobserver with controlled native calls.
 * The class that owns the behavior registers the implementation through {@link LinuxCargoJobserverSecrets}.
 */
public interface LinuxCargoJobserverAccess
{
	/**
	 * Uses actual native operations as the delegate of a focused fault fixture.
	 *
	 * @param scope borrowed native services, retained open by the fixture through all operations
	 * @return the scope's shared resource-free native access
	 */
	LinuxCargoJobserver.Calls nativeCalls(ToolingScope scope);

	/**
	 * Executes normal validated acquisition with controlled external access.
	 *
	 * @param scratchParent existing caller-owned parent
	 * @param workloadGroup workload GID
	 * @param jobs total initial capacity
	 * @param calls native access controlling the boundary under test
	 * @return normally owned FIFO
	 * @throws IOException if acquisition or cleanup fails
	 */
	LinuxCargoJobserver create(Path scratchParent, int workloadGroup, int jobs,
		LinuxCargoJobserver.Calls calls) throws IOException;
}
