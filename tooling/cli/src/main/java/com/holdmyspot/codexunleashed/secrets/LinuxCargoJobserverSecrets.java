package com.holdmyspot.codexunleashed.secrets;

import com.holdmyspot.codexunleashed.scheduler.LinuxCargoJobserver;
import java.lang.invoke.MethodHandles;

/**
 * Gives the module's tests typed access to a class that acquires the Linux Cargo jobserver with controlled native calls.
 * Only the module's test module can read this package.
 */
public final class LinuxCargoJobserverSecrets
{
	/** The access that {@link LinuxCargoJobserver} registers when it is initialized. */
	private static LinuxCargoJobserverAccess linuxCargoJobserverAccess;

	/**
	 * Prevents construction.
	 */
	private LinuxCargoJobserverSecrets()
	{
	}

	/**
	 * Registers the access that {@link LinuxCargoJobserver} provides to this module's tests.
	 *
	 * @param access the access to register
	 */
	public static void setLinuxCargoJobserverAccess(LinuxCargoJobserverAccess access)
	{
		linuxCargoJobserverAccess = access;
	}

	/**
	 * Returns the access that {@link LinuxCargoJobserver} registered, initializing that class first if necessary.
	 *
	 * @return the registered access
	 * @throws AssertionError if {@link LinuxCargoJobserver} did not register its access
	 */
	public static LinuxCargoJobserverAccess getLinuxCargoJobserverAccess()
	{
		LinuxCargoJobserverAccess access = linuxCargoJobserverAccess;
		if (access == null)
		{
			ensureClassInitialized(LinuxCargoJobserver.class);
			access = linuxCargoJobserverAccess;
			if (access == null)
				throw new AssertionError(LinuxCargoJobserver.class.getName() + " did not register LinuxCargoJobserverAccess");
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
