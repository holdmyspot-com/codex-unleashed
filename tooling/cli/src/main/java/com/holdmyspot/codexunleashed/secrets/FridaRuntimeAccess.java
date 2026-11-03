package com.holdmyspot.codexunleashed.secrets;

import com.holdmyspot.codexunleashed.scheduler.FridaRuntime;
import java.io.IOException;
import java.lang.foreign.MemorySegment;

/**
 * Builds Frida runtime owners in controlled states for ownership tests, without loading a producer.
 * The class that owns the behavior registers the implementation through {@link FridaRuntimeSecrets}.
 */
public interface FridaRuntimeAccess
{
	/**
	 * Marks an unstarted runtime initialized and binds its lifecycle operations.
	 *
	 * @param runtime unstarted runtime
	 * @param lifecycle controlled lifecycle operations
	 */
	void initialize(FridaRuntime runtime, FridaRuntime.Lifecycle lifecycle);

	/**
	 * Creates an unregistered manager owner.
	 *
	 * @param runtime enclosing runtime
	 * @param started whether the manager reports full startup
	 * @return the manager
	 */
	FridaRuntime.Manager manager(FridaRuntime runtime, boolean started);

	/**
	 * Creates an unregistered session owner.
	 *
	 * @param manager enclosing manager
	 * @param pid target identity
	 * @param handle attached native session, or NULL for an unattached owner
	 * @return the session
	 */
	FridaRuntime.Manager.Session session(FridaRuntime.Manager manager, long pid, MemorySegment handle);

	/**
	 * Records an uncertain cleanup failure on a session.
	 *
	 * @param session session owner
	 * @param failure retained failure
	 */
	void failCleanup(FridaRuntime.Manager.Session session, IOException failure);

	/**
	 * Creates a script whose load was attempted, registered with its session.
	 *
	 * @param session enclosing session
	 * @param source script source
	 * @param handle native script
	 * @param options native script options
	 * @return the script
	 */
	FridaRuntime.Manager.Session.Script loadedScript(FridaRuntime.Manager.Session session, String source,
		MemorySegment handle, MemorySegment options);

	/**
	 * Returns the number of scripts a session still owns.
	 *
	 * @param session session owner
	 * @return the registered script count
	 */
	int scripts(FridaRuntime.Manager.Session session);
}
