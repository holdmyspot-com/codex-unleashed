package com.holdmyspot.codexunleashed.secrets;

import com.holdmyspot.codexunleashed.scheduler.LinuxBazelContainment;
import java.lang.invoke.MethodHandles;

/**
 * Gives the module's tests typed access to a class that runs Linux Bazel containment admission with a controlled group allocator.
 * Only the module's test module can read this package.
 */
public final class LinuxBazelContainmentSecrets
{
	/** The access that {@link LinuxBazelContainment} registers when it is initialized. */
	private static LinuxBazelContainmentAccess linuxBazelContainmentAccess;

	/**
	 * Prevents construction.
	 */
	private LinuxBazelContainmentSecrets()
	{
	}

	/**
	 * Registers the access that {@link LinuxBazelContainment} provides to this module's tests.
	 *
	 * @param access the access to register
	 */
	public static void setLinuxBazelContainmentAccess(LinuxBazelContainmentAccess access)
	{
		linuxBazelContainmentAccess = access;
	}

	/**
	 * Returns the access that {@link LinuxBazelContainment} registered, initializing that class first if necessary.
	 *
	 * @return the registered access
	 * @throws AssertionError if {@link LinuxBazelContainment} did not register its access
	 */
	public static LinuxBazelContainmentAccess getLinuxBazelContainmentAccess()
	{
		LinuxBazelContainmentAccess access = linuxBazelContainmentAccess;
		if (access == null)
		{
			ensureClassInitialized(LinuxBazelContainment.class);
			access = linuxBazelContainmentAccess;
			if (access == null)
				throw new AssertionError(LinuxBazelContainment.class.getName() + " did not register LinuxBazelContainmentAccess");
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
