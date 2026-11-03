package com.holdmyspot.codexunleashed.secrets;

import com.holdmyspot.codexunleashed.scheduler.LinuxAuthorityReader;
import java.lang.invoke.MethodHandles;

/**
 * Gives the module's tests typed access to a class that exercises the memory-authority reader decisions without native authority.
 * Only the module's test module can read this package.
 */
public final class LinuxAuthorityReaderSecrets
{
	/** The access that {@link LinuxAuthorityReader} registers when it is initialized. */
	private static LinuxAuthorityReaderAccess linuxAuthorityReaderAccess;

	/**
	 * Prevents construction.
	 */
	private LinuxAuthorityReaderSecrets()
	{
	}

	/**
	 * Registers the access that {@link LinuxAuthorityReader} provides to this module's tests.
	 *
	 * @param access the access to register
	 */
	public static void setLinuxAuthorityReaderAccess(LinuxAuthorityReaderAccess access)
	{
		linuxAuthorityReaderAccess = access;
	}

	/**
	 * Returns the access that {@link LinuxAuthorityReader} registered, initializing that class first if necessary.
	 *
	 * @return the registered access
	 * @throws AssertionError if {@link LinuxAuthorityReader} did not register its access
	 */
	public static LinuxAuthorityReaderAccess getLinuxAuthorityReaderAccess()
	{
		LinuxAuthorityReaderAccess access = linuxAuthorityReaderAccess;
		if (access == null)
		{
			ensureClassInitialized(LinuxAuthorityReader.class);
			access = linuxAuthorityReaderAccess;
			if (access == null)
				throw new AssertionError(LinuxAuthorityReader.class.getName() + " did not register LinuxAuthorityReaderAccess");
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
