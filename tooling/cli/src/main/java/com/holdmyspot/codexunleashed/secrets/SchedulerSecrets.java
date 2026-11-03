package com.holdmyspot.codexunleashed.secrets;

import com.holdmyspot.codexunleashed.scheduler.Scheduler;
import java.lang.invoke.MethodHandles;

/**
 * Gives the module's tests typed access to a class that dispatches scheduler commands with controlled platform and native boundaries.
 * Only the module's test module can read this package.
 */
public final class SchedulerSecrets
{
	/** The access that {@link Scheduler} registers when it is initialized. */
	private static SchedulerAccess schedulerAccess;

	/**
	 * Prevents construction.
	 */
	private SchedulerSecrets()
	{
	}

	/**
	 * Registers the access that {@link Scheduler} provides to this module's tests.
	 *
	 * @param access the access to register
	 */
	public static void setSchedulerAccess(SchedulerAccess access)
	{
		schedulerAccess = access;
	}

	/**
	 * Returns the access that {@link Scheduler} registered, initializing that class first if necessary.
	 *
	 * @return the registered access
	 * @throws AssertionError if {@link Scheduler} did not register its access
	 */
	public static SchedulerAccess getSchedulerAccess()
	{
		SchedulerAccess access = schedulerAccess;
		if (access == null)
		{
			ensureClassInitialized(Scheduler.class);
			access = schedulerAccess;
			if (access == null)
				throw new AssertionError(Scheduler.class.getName() + " did not register SchedulerAccess");
		}
		return access;
	}

	/**
	 * Runs the static initializer of the class that registers an access.
	 *
	 * @param owner the class whose static initializer registers the access
	 * @throws AssertionError if the class cannot be initialized from this package
	 */
	private static void ensureClassInitialized(Class<?> owner)
	{
		try
		{
			MethodHandles.lookup().ensureInitialized(owner);
		}
		catch (IllegalAccessException e)
		{
			throw new AssertionError("Cannot initialize " + owner.getName(), e);
		}
	}
}
