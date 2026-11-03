package com.holdmyspot.codexunleashed.secrets;

import com.holdmyspot.codexunleashed.scheduler.MacCargoJobserver;

/**
 * Creates a macOS Cargo jobserver over a controlled native provider.
 * The class that owns the behavior registers the implementation through {@link MacCargoJobserverSecrets}.
 */
public interface MacCargoJobserverAccess
{
	/**
	 * Retains a resource-free provider for the same normal filesystem and accounting operations.
	 *
	 * @param provider controlled native boundary
	 * @return new unacquired owner
	 */
	MacCargoJobserver create(MacCargoJobserver.Provider provider);
}
