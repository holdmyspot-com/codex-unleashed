package com.holdmyspot.codexunleashed.tooling.cache;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies GitHub cache cleanup at its external-command boundary.
 */
public final class CachePrunerTest
{
	/**
	 * Creates the cache cleanup tests.
	 */
	public CachePrunerTest()
	{
	}

	/**
	 * Deletes obsolete managed caches in inventory order and preserves unrelated caches.
	 *
	 * @throws IOException if the controlled command fails unexpectedly
	 */
	@Test
	public void deletesOnlyObsoleteManagedCaches() throws IOException
	{
		List<List<String>> calls = new ArrayList<>();
		AtomicInteger index = new AtomicInteger();
		CachePruner.Result result = CachePruner.prune("owner/repo", false, command ->
		{
			calls.add(command);
			return switch (index.getAndIncrement())
			{
				case 0 -> "rust-v0.160.0\nrust-v0.159.3\nrust-v0.159.2\n";
				case 1 -> "{\"id\":1,\"key\":\"pnpm-upstream-rust-v0.160.0-linux\"}\n" +
					"{\"id\":9007199254740993,\"key\":\"pnpm-legacy\"}\n" +
					"{\"id\":3,\"key\":\"unrelated\"}\n";
				default -> "";
			};
		});
		assertEquals(result.retainedReleases(), List.of("rust-v0.160.0", "rust-v0.159.3"));
		assertEquals(result.obsoleteCacheIds(), List.of(new BigInteger("9007199254740993")));
		assertEquals(calls.size(), 3);
		assertEquals(calls.getLast(), List.of("gh", "api", "--method", "DELETE",
			"repos/owner/repo/actions/caches/9007199254740993"));
	}

	/**
	 * Reports obsolete cache identifiers without sending a deletion in dry-run mode.
	 *
	 * @throws IOException if the controlled command fails unexpectedly
	 */
	@Test
	public void dryRunDoesNotDelete() throws IOException
	{
		AtomicInteger index = new AtomicInteger();
		CachePruner.Result result = CachePruner.prune("owner/repo", true, _ -> switch (index.getAndIncrement())
		{
			case 0 -> "rust-v0.160.0\n";
			case 1 -> "{\"id\":8,\"key\":\"setup-uv-legacy\"}\n";
			default -> throw new AssertionError("Unexpected deletion");
		});
		assertEquals(result.obsoleteCacheIds(), List.of(BigInteger.valueOf(8)));
		assertTrue(result.dryRun());
		assertEquals(index.get(), 2);
	}

	/**
	 * Rejects invalid repositories before contacting GitHub.
	 */
	@Test
	public void validatesRepositoryBeforeCommands()
	{
		expectThrows(IllegalArgumentException.class, () -> CachePruner.prune("../other/repo", false,
			_ ->
			{
				throw new AssertionError("Unexpected GitHub request");
			}));
	}

	/**
	 * Parses the complete inventory before allowing any deletion.
	 */
	@Test
	public void malformedInventoryPreventsDeletion()
	{
		AtomicInteger index = new AtomicInteger();
		expectThrows(IOException.class, () -> CachePruner.prune("owner/repo", false,
			_ -> switch (index.getAndIncrement())
			{
				case 0 -> "rust-v0.160.0\n";
				case 1 -> "{\"id\":1,\"key\":\"pnpm-legacy\"}\n{invalid\n";
				default -> throw new AssertionError("Deletion before inventory validation");
			}));
		assertEquals(index.get(), 2);
	}

	/**
	 * Stops immediately when a deletion fails, leaving later caches untouched.
	 */
	@Test
	public void deletionFailureStopsLaterDeletes()
	{
		AtomicInteger index = new AtomicInteger();
		IOException failure = new IOException("GitHub deletion rejected");
		IOException actual = expectThrows(IOException.class, () -> CachePruner.prune("owner/repo", false,
			_ -> switch (index.getAndIncrement())
			{
				case 0 -> "rust-v0.160.0\n";
				case 1 -> "{\"id\":1,\"key\":\"pnpm-legacy\"}\n{\"id\":2,\"key\":\"apt-legacy\"}\n";
				case 2 -> throw failure;
				default -> throw new AssertionError("Deletion continued after failure");
			}));
		assertEquals(actual, failure);
		assertEquals(index.get(), 3);
	}

	/**
	 * Refuses cache lookup when no stable release establishes a safe retention boundary.
	 */
	@Test
	public void emptyReleaseInventoryPreventsCacheLookup()
	{
		AtomicInteger index = new AtomicInteger();
		expectThrows(IllegalArgumentException.class, () -> CachePruner.prune("owner/repo", false, _ ->
		{
			if (index.getAndIncrement() != 0)
				throw new AssertionError("Unexpected cache lookup");
			return "";
		}));
		assertEquals(index.get(), 1);
	}
}
