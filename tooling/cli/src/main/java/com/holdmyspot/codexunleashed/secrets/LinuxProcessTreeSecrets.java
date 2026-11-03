package com.holdmyspot.codexunleashed.secrets;

import com.holdmyspot.codexunleashed.scheduler.LinuxProcessTree;
import java.lang.invoke.MethodHandles;

/**
 * Gives the module's tests typed access to a class that dispatches the Linux workload gate with a controlled native exec action.
 * Only the module's test module can read this package.
 */
public final class LinuxProcessTreeSecrets
{
	/** The access that {@link LinuxProcessTree} registers when it is initialized. */
	private static LinuxProcessTreeAccess linuxProcessTreeAccess;

	/**
	 * Prevents construction.
	 */
	private LinuxProcessTreeSecrets()
	{
	}

	/**
	 * Registers the access that {@link LinuxProcessTree} provides to this module's tests.
	 *
	 * @param access the access to register
	 */
	public static void setLinuxProcessTreeAccess(LinuxProcessTreeAccess access)
	{
		linuxProcessTreeAccess = access;
	}

	/**
	 * Returns the access that {@link LinuxProcessTree} registered, initializing that class first if necessary.
	 *
	 * @return the registered access
	 * @throws AssertionError if {@link LinuxProcessTree} did not register its access
	 */
	public static LinuxProcessTreeAccess getLinuxProcessTreeAccess()
	{
		LinuxProcessTreeAccess access = linuxProcessTreeAccess;
		if (access == null)
		{
			ensureClassInitialized(LinuxProcessTree.class);
			access = linuxProcessTreeAccess;
			if (access == null)
				throw new AssertionError(LinuxProcessTree.class.getName() + " did not register LinuxProcessTreeAccess");
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
