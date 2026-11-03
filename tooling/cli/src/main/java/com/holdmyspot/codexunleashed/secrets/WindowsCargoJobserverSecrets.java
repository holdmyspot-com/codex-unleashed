package com.holdmyspot.codexunleashed.secrets;

import com.holdmyspot.codexunleashed.scheduler.WindowsCargoJobserver;
import java.lang.invoke.MethodHandles;

/**
 * Gives the module's tests typed access to a class that acquires the Windows Cargo jobserver with controlled native outcomes.
 * Only the module's test module can read this package.
 */
public final class WindowsCargoJobserverSecrets
{
	/** The access that {@link WindowsCargoJobserver} registers when it is initialized. */
	private static WindowsCargoJobserverAccess windowsCargoJobserverAccess;

	/**
	 * Prevents construction.
	 */
	private WindowsCargoJobserverSecrets()
	{
	}

	/**
	 * Registers the access that {@link WindowsCargoJobserver} provides to this module's tests.
	 *
	 * @param access the access to register
	 */
	public static void setWindowsCargoJobserverAccess(WindowsCargoJobserverAccess access)
	{
		windowsCargoJobserverAccess = access;
	}

	/**
	 * Returns the access that {@link WindowsCargoJobserver} registered, initializing that class first if necessary.
	 *
	 * @return the registered access
	 * @throws AssertionError if {@link WindowsCargoJobserver} did not register its access
	 */
	public static WindowsCargoJobserverAccess getWindowsCargoJobserverAccess()
	{
		WindowsCargoJobserverAccess access = windowsCargoJobserverAccess;
		if (access == null)
		{
			ensureClassInitialized(WindowsCargoJobserver.class);
			access = windowsCargoJobserverAccess;
			if (access == null)
				throw new AssertionError(WindowsCargoJobserver.class.getName() + " did not register WindowsCargoJobserverAccess");
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
