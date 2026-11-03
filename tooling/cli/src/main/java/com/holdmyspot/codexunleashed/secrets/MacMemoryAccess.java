package com.holdmyspot.codexunleashed.secrets;

import com.holdmyspot.codexunleashed.scheduler.MacMemory;
import java.io.IOException;

/**
 * Reads a macOS memory sample from controlled native outcomes.
 * The class that owns the behavior registers the implementation through {@link MacMemorySecrets}.
 */
public interface MacMemoryAccess
{
	/**
	 * Runs the same acquisition and cleanup operation used by the public reader.
	 *
	 * @param calls the controlled native outcomes for this acquisition
	 * @return the acquired sample after successful cleanup
	 * @throws IOException if acquisition or cleanup fails
	 */
	MacMemory.Sample read(MacMemory.NativeCalls calls) throws IOException;
}
