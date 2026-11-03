package com.holdmyspot.codexunleashed.secrets;

import com.holdmyspot.codexunleashed.scheduler.LinuxAuthority;
import java.lang.invoke.MethodHandles;

/**
 * Gives the module's tests typed access to a class that decides memory-authority replies with controlled retained-owner observations.
 * Only the module's test module can read this package.
 */
public final class LinuxAuthoritySecrets
{
	/** The access that {@link LinuxAuthority} registers when it is initialized. */
	private static LinuxAuthorityAccess linuxAuthorityAccess;

	/**
	 * Prevents construction.
	 */
	private LinuxAuthoritySecrets()
	{
	}

	/**
	 * Registers the access that {@link LinuxAuthority} provides to this module's tests.
	 *
	 * @param access the access to register
	 */
	public static void setLinuxAuthorityAccess(LinuxAuthorityAccess access)
	{
		linuxAuthorityAccess = access;
	}

	/**
	 * Returns the access that {@link LinuxAuthority} registered, initializing that class first if necessary.
	 *
	 * @return the registered access
	 * @throws AssertionError if {@link LinuxAuthority} did not register its access
	 */
	public static LinuxAuthorityAccess getLinuxAuthorityAccess()
	{
		LinuxAuthorityAccess access = linuxAuthorityAccess;
		if (access == null)
		{
			ensureClassInitialized(LinuxAuthority.class);
			access = linuxAuthorityAccess;
			if (access == null)
				throw new AssertionError(LinuxAuthority.class.getName() + " did not register LinuxAuthorityAccess");
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
