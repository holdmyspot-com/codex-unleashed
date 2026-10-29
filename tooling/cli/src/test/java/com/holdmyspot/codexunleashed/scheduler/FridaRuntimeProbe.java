package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Runs explicit native acceptance against a caller-supplied, verified Frida producer in a fresh JVM.
 * This probe acquires real local backends; it does not attach to a workload or establish callback safety.
 * The ordinary test suite does not run this probe or acquire a native library.
 */
public final class FridaRuntimeProbe
{
	/**
	 * Prevents construction of this command entry point.
	 */
	private FridaRuntimeProbe()
	{
	}

	/**
	 * Verifies independent manager closure, guarded global shutdown and refusal to initialize a second native lifetime.
	 * Run in a fresh JVM with native access enabled for the module containing the production runtime.
	 * @param args one path to the verified Frida 17.24.3 native producer
	 * @throws IllegalArgumentException if the producer argument is missing or extra arguments are supplied
	 * @throws IOException if native acquisition or teardown fails
	 * @throws AssertionError if an ownership guard accepts an invalid operation
	 */
	public static void main(String[] args) throws IOException
	{
		if (args.length != 1)
			throw new IllegalArgumentException("Expected one verified Frida producer path");
		Path producer = Path.of(args[0]);

		try (var runtime = new FridaRuntime(producer))
		{
			runtime.start();
			try (var first = runtime.newManager(); var second = runtime.newManager())
			{
				first.start();
				second.start();
				try
				{
					runtime.close();
					throw new AssertionError("Global shutdown accepted two remaining manager owners");
				}
				catch (IOException expected)
				{
					if (expected.getMessage().contains("still owns 2 managers") == false)
						throw expected;
				}
				System.out.println("global_close_refused_with_two_owners=true");

				first.close();
				first.close();
				try (var third = runtime.newManager())
				{
					third.start();
				}
				System.out.println("backend_acquisition_after_peer_close=passed");
			}
			try (var unstarted = runtime.newManager())
			{
				// Closing the reserved owner must release its registration without acquiring a native manager.
			}
		}
		System.out.println("native_runtime_shutdown=returned");

		try (var runtime = new FridaRuntime(producer))
		{
			try
			{
				runtime.start();
				throw new AssertionError("A second native initialization was accepted after shutdown");
			}
			catch (IOException expected)
			{
				if (expected.getMessage().contains("already claimed") == false)
					throw expected;
			}
		}
		System.out.println("second_initialization_rejected=true");
		System.out.println("runtime_manager_ownership=passed");
	}
}
