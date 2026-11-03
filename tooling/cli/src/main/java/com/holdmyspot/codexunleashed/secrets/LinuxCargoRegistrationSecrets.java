package com.holdmyspot.codexunleashed.secrets;

import com.holdmyspot.codexunleashed.scheduler.LinuxCargoRegistration;
import java.lang.invoke.MethodHandles;

/**
 * Gives the module's tests typed access to a class that authenticates Linux Cargo registrations with controlled native observations.
 * Only the module's test module can read this package.
 */
public final class LinuxCargoRegistrationSecrets
{
	/** The access that {@link LinuxCargoRegistration} registers when it is initialized. */
	private static LinuxCargoRegistrationAccess linuxCargoRegistrationAccess;

	/**
	 * Prevents construction.
	 */
	private LinuxCargoRegistrationSecrets()
	{
	}

	/**
	 * Registers the access that {@link LinuxCargoRegistration} provides to this module's tests.
	 *
	 * @param access the access to register
	 */
	public static void setLinuxCargoRegistrationAccess(LinuxCargoRegistrationAccess access)
	{
		linuxCargoRegistrationAccess = access;
	}

	/**
	 * Returns the access that {@link LinuxCargoRegistration} registered, initializing that class first if necessary.
	 *
	 * @return the registered access
	 * @throws AssertionError if {@link LinuxCargoRegistration} did not register its access
	 */
	public static LinuxCargoRegistrationAccess getLinuxCargoRegistrationAccess()
	{
		LinuxCargoRegistrationAccess access = linuxCargoRegistrationAccess;
		if (access == null)
		{
			ensureClassInitialized(LinuxCargoRegistration.class);
			access = linuxCargoRegistrationAccess;
			if (access == null)
				throw new AssertionError(LinuxCargoRegistration.class.getName() + " did not register LinuxCargoRegistrationAccess");
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
