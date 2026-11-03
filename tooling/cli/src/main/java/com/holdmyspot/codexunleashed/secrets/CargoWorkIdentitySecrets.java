package com.holdmyspot.codexunleashed.secrets;

import com.holdmyspot.codexunleashed.scheduler.CargoWorkIdentity;
import java.lang.invoke.MethodHandles;

/**
 * Gives the module's tests typed access to a class that creates identities with a controlled argument resolver.
 * Only the module's test module can read this package.
 */
public final class CargoWorkIdentitySecrets
{
	/** The access that {@link CargoWorkIdentity} registers when it is initialized. */
	private static CargoWorkIdentityAccess cargoWorkIdentityAccess;

	/**
	 * Prevents construction.
	 */
	private CargoWorkIdentitySecrets()
	{
	}

	/**
	 * Registers the access that {@link CargoWorkIdentity} provides to this module's tests.
	 *
	 * @param access the access to register
	 */
	public static void setCargoWorkIdentityAccess(CargoWorkIdentityAccess access)
	{
		cargoWorkIdentityAccess = access;
	}

	/**
	 * Returns the access that {@link CargoWorkIdentity} registered, initializing that class first if necessary.
	 *
	 * @return the registered access
	 * @throws AssertionError if {@link CargoWorkIdentity} did not register its access
	 */
	public static CargoWorkIdentityAccess getCargoWorkIdentityAccess()
	{
		CargoWorkIdentityAccess access = cargoWorkIdentityAccess;
		if (access == null)
		{
			ensureClassInitialized(CargoWorkIdentity.class);
			access = cargoWorkIdentityAccess;
			if (access == null)
				throw new AssertionError(CargoWorkIdentity.class.getName() + " did not register CargoWorkIdentityAccess");
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
