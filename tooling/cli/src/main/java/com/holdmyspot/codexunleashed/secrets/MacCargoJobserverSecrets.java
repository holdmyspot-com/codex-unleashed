package com.holdmyspot.codexunleashed.secrets;

import com.holdmyspot.codexunleashed.scheduler.MacCargoJobserver;
import java.lang.invoke.MethodHandles;

/**
 * Gives the module's tests typed access to a class that creates a macOS Cargo jobserver over a controlled native provider.
 * Only the module's test module can read this package.
 */
public final class MacCargoJobserverSecrets
{
	/** The access that {@link MacCargoJobserver} registers when it is initialized. */
	private static MacCargoJobserverAccess macCargoJobserverAccess;

	/**
	 * Prevents construction.
	 */
	private MacCargoJobserverSecrets()
	{
	}

	/**
	 * Registers the access that {@link MacCargoJobserver} provides to this module's tests.
	 *
	 * @param access the access to register
	 */
	public static void setMacCargoJobserverAccess(MacCargoJobserverAccess access)
	{
		macCargoJobserverAccess = access;
	}

	/**
	 * Returns the access that {@link MacCargoJobserver} registered, initializing that class first if necessary.
	 *
	 * @return the registered access
	 * @throws AssertionError if {@link MacCargoJobserver} did not register its access
	 */
	public static MacCargoJobserverAccess getMacCargoJobserverAccess()
	{
		MacCargoJobserverAccess access = macCargoJobserverAccess;
		if (access == null)
		{
			ensureClassInitialized(MacCargoJobserver.class);
			access = macCargoJobserverAccess;
			if (access == null)
				throw new AssertionError(MacCargoJobserver.class.getName() + " did not register MacCargoJobserverAccess");
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
