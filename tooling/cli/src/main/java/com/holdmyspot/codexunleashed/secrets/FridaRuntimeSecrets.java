package com.holdmyspot.codexunleashed.secrets;

import com.holdmyspot.codexunleashed.scheduler.FridaRuntime;
import java.lang.invoke.MethodHandles;

/**
 * Gives the module's tests typed access to a class that builds Frida runtime owners in controlled states.
 * Only the module's test module can read this package.
 */
public final class FridaRuntimeSecrets
{
	/** The access that {@link FridaRuntime} registers when it is initialized. */
	private static FridaRuntimeAccess fridaRuntimeAccess;

	/**
	 * Prevents construction.
	 */
	private FridaRuntimeSecrets()
	{
	}

	/**
	 * Registers the access that {@link FridaRuntime} provides to this module's tests.
	 *
	 * @param access the access to register
	 */
	public static void setFridaRuntimeAccess(FridaRuntimeAccess access)
	{
		fridaRuntimeAccess = access;
	}

	/**
	 * Returns the access that {@link FridaRuntime} registered, initializing that class first if necessary.
	 *
	 * @return the registered access
	 * @throws AssertionError if {@link FridaRuntime} did not register its access
	 */
	public static FridaRuntimeAccess getFridaRuntimeAccess()
	{
		FridaRuntimeAccess access = fridaRuntimeAccess;
		if (access == null)
		{
			ensureClassInitialized(FridaRuntime.class);
			access = fridaRuntimeAccess;
			if (access == null)
				throw new AssertionError(FridaRuntime.class.getName() + " did not register FridaRuntimeAccess");
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
