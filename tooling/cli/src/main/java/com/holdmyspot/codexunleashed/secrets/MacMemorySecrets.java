package com.holdmyspot.codexunleashed.secrets;

import com.holdmyspot.codexunleashed.scheduler.MacMemory;
import java.lang.invoke.MethodHandles;

/**
 * Gives the module's tests typed access to a class that reads a macOS memory sample from controlled native outcomes.
 * Only the module's test module can read this package.
 */
public final class MacMemorySecrets
{
	/** The access that {@link MacMemory} registers when it is initialized. */
	private static MacMemoryAccess macMemoryAccess;

	/**
	 * Prevents construction.
	 */
	private MacMemorySecrets()
	{
	}

	/**
	 * Registers the access that {@link MacMemory} provides to this module's tests.
	 *
	 * @param access the access to register
	 */
	public static void setMacMemoryAccess(MacMemoryAccess access)
	{
		macMemoryAccess = access;
	}

	/**
	 * Returns the access that {@link MacMemory} registered, initializing that class first if necessary.
	 *
	 * @return the registered access
	 * @throws AssertionError if {@link MacMemory} did not register its access
	 */
	public static MacMemoryAccess getMacMemoryAccess()
	{
		MacMemoryAccess access = macMemoryAccess;
		if (access == null)
		{
			ensureClassInitialized(MacMemory.class);
			access = macMemoryAccess;
			if (access == null)
				throw new AssertionError(MacMemory.class.getName() + " did not register MacMemoryAccess");
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
