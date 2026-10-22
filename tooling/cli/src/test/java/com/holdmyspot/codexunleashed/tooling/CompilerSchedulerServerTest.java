package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies ownership of the Bazel scheduler's Unix-domain socket during initialization and shutdown.
 */
public final class CompilerSchedulerServerTest
{
	/**
	 * Creates the socket lifecycle tests.
	 */
	public CompilerSchedulerServerTest()
	{
	}

	/**
	 * Removes the service's socket after the initial memory observation fails, preserving the original failure.
	 *
	 * @throws IOException if the fixture cannot be created or removed
	 */
	@Test
	public void removesSocketAfterInitializationFailure() throws IOException
	{
		Path root = Files.createTempDirectory(Path.of("../../.cat/work"), "bazel-scheduler-");
		Path endpoint = root.resolve("s");
		UncheckedIOException failure = new UncheckedIOException(new IOException("memory unavailable"));
		try
		{
			UncheckedIOException actual = expectThrows(UncheckedIOException.class, () ->
			{
				try (CompilerSchedulerServer service = new CompilerSchedulerServer(endpoint, 1000,
					() ->
					{
						throw failure;
					}, Duration.ofMillis(10), System.err))
				{
					service.checkHealthy();
				}
			});
			assertSame(actual, failure);
			assertFalse(Files.exists(endpoint));
		}
		finally
		{
			Files.deleteIfExists(endpoint);
			Files.delete(root);
		}
	}

	/**
	 * Preserves a caller-owned file when binding the socket fails.
	 *
	 * @throws IOException if the fixture cannot be created or removed
	 */
	@Test
	public void preservesExistingEndpoint() throws IOException
	{
		Path root = Files.createTempDirectory(Path.of("../../.cat/work"), "bazel-scheduler-");
		Path endpoint = root.resolve("s");
		Files.writeString(endpoint, "caller owned");
		try
		{
			expectThrows(IOException.class, () ->
			{
				try (CompilerSchedulerServer service = new CompilerSchedulerServer(endpoint, 1000,
					() -> 2000, Duration.ofMillis(10), System.err))
				{
					service.checkHealthy();
				}
			});
			assertEquals(Files.readString(endpoint), "caller owned");
		}
		finally
		{
			Files.deleteIfExists(endpoint);
			Files.delete(root);
		}
	}

	/**
	 * Removes the service-owned endpoint after normal shutdown.
	 *
	 * @throws IOException if the fixture or service cannot be created or removed
	 */
	@Test
	public void removesSocketAfterShutdown() throws IOException
	{
		Path root = Files.createTempDirectory(Path.of("../../.cat/work"), "bazel-scheduler-");
		Path endpoint = root.resolve("s");
		try
		{
			try (CompilerSchedulerServer service = new CompilerSchedulerServer(endpoint, 1000,
				() -> 2000, Duration.ofMillis(10), System.err))
			{
				assertTrue(Files.exists(endpoint));
				service.checkHealthy();
			}
			assertFalse(Files.exists(endpoint));
		}
		finally
		{
			Files.deleteIfExists(endpoint);
			Files.delete(root);
		}
	}

	/**
	 * Waits for an in-progress memory observation before releasing service resources when the closing caller is
	 * interrupted, and preserves the caller's interrupt status.
	 *
	 * @throws Exception if the fixture cannot be created or a lifecycle transition cannot be observed
	 */
	@Test
	public void shutdownWaitsWhenInterrupted() throws Exception
	{
		Path root = Files.createTempDirectory(Path.of("../../.cat/work"), "bazel-scheduler-");
		Path endpoint = root.resolve("s");
		CountDownLatch observing = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger observations = new AtomicInteger();
		try (ExecutorService executor = Executors.newSingleThreadExecutor())
		{
			try (CompilerSchedulerServer service = new CompilerSchedulerServer(endpoint, 1000, () ->
			{
				if (observations.getAndIncrement() > 0)
				{
					observing.countDown();
					try
					{
						release.await();
					}
					catch (InterruptedException failure)
					{
						Thread.currentThread().interrupt();
						throw new UncheckedIOException(new IOException("Memory observation interrupted", failure));
					}
				}
				return 2000;
			}, Duration.ofMillis(10), System.err))
			{
				try
				{
					assertTrue(observing.await(10, TimeUnit.SECONDS), "The service enters its memory observation");
					Future<IOException> closing = executor.submit(() ->
					{
						Thread.currentThread().interrupt();
						IOException failure = expectThrows(IOException.class, service::close);
						assertTrue(Thread.currentThread().isInterrupted());
						return failure;
					});
					expectThrows(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
					assertTrue(Files.exists(endpoint), "The active service retains its endpoint until its thread exits");
					release.countDown();
					assertEquals(closing.get(10, TimeUnit.SECONDS).getMessage(),
						"Interrupted while closing the Bazel compiler scheduler");
					assertFalse(Files.exists(endpoint));
				}
				finally
				{
					release.countDown();
				}
			}
		}
		finally
		{
			release.countDown();
			Files.deleteIfExists(endpoint);
			Files.delete(root);
		}
	}
}
