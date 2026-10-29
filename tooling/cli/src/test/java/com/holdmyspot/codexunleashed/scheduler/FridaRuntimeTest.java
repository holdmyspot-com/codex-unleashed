package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.Test;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Exercises Java ownership guards without loading or simulating the native Frida runtime.
 * Native acquisition and teardown require the separate native acceptance probe.
 */
public final class FridaRuntimeTest
{
	/**
	 * Creates ownership tests.
	 */
	public FridaRuntimeTest()
	{
	}

	/**
	 * Closing an unstarted owner ends its lifetime without opening its producer path.
	 * @throws IOException if resource-free cleanup fails
	 */
	@Test
	public void closesUnstartedOwnerWithoutAcquiringNativeResources() throws IOException
	{
		var runtime = new FridaRuntime(Path.of("unused-native-producer"));

		expectThrows(IllegalStateException.class, runtime::newManager);
		runtime.close();
		runtime.close();

		expectThrows(IllegalStateException.class, runtime::start);
		expectThrows(IllegalStateException.class, runtime::newManager);
	}

	/**
	 * A different thread cannot acquire or close an owner's resources; the constructing thread can still close it.
	 * @throws IOException if owner-thread cleanup fails
	 * @throws InterruptedException if waiting for the rejected caller is interrupted
	 */
	@Test
	public void rejectsOtherThreadWithoutChangingOwnership() throws IOException, InterruptedException
	{
		try (var runtime = new FridaRuntime(Path.of("unused-native-producer")))
		{
			var result = new AtomicReference<Throwable>();
			Thread caller = Thread.ofPlatform().start(() ->
			{
				try
				{
					expectThrows(IllegalStateException.class, runtime::start);
					expectThrows(IllegalStateException.class, runtime::newManager);
					expectThrows(IllegalStateException.class, runtime::close);
				}
				catch (Throwable failure)
				{
					result.set(failure);
				}
			});
			caller.join();

			assertTrue(result.get() == null, "Other-thread operation failed its rejection assertion: " + result.get());
		}
	}
}
