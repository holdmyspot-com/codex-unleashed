package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks invocation leases and cleanup decisions with actual owned FIFOs and narrow native-failure fixtures.
 * These checks do not launch Cargo or establish compiler-tree completion.
 */
public final class LinuxCargoJobserverTest
{
	/**
	 * Creates owner contract checks.
	 */
	public LinuxCargoJobserverTest() { }

	/**
	 * Limits actual FIFO operations to the Linux platform this owner supports.
	 */
	@BeforeClass
	public void requireLinuxForNativeFixtures()
	{
		requireLinux();
	}

	/**
	 * Rejects native fixture execution on another operating system without weakening production validation.
	 */
	static void requireLinux()
	{
		if (!System.getProperty("os.name").equals("Linux"))
			throw new SkipException("These native owner checks require Linux");
	}

	/**
	 * Keeps the root environment immutable and refuses cleanup while its single invocation is retained.
	 * @throws Exception if native setup or cleanup fails
	 */
	@Test
	public void retainsOneInvocationAndAnImmutableEnvironment() throws Exception
	{
		try (Fixture fixture = new Fixture();
			LinuxCargoJobserver owner = LinuxCargoJobserver.create(fixture.directory, fixture.group, 2))
		{
			Map<String, String> original = new HashMap<>(Map.of("marker", "original"));
			LinuxCargoJobserver.CargoLease lease = owner.retainCargo(original);
			try (lease)
			{
				Map<String, String> environment = lease.environment();
				original.put("marker", "changed");
				requireThat(environment.get("marker"), "marker").isEqualTo("original", "expected");
				requireException(UnsupportedOperationException.class, () -> environment.put("other", "changed"));
				String flags = environment.get("CARGO_MAKEFLAGS");
				requireThat(environment.get("MAKEFLAGS"), "makeFlags").isEqualTo(flags, "cargoFlags");
				requireThat(environment.get("MFLAGS"), "legacyFlags").isEqualTo(flags, "cargoFlags");
				Path fifo = fifo(environment);
				requireThat(Files.exists(fifo), "liveFifo").isTrue();
				requireException(IllegalStateException.class, owner::close);
				requireThat(Files.exists(fifo), "retainedFifo").isTrue();
				requireException(IllegalStateException.class, () -> owner.retainCargo(Map.of()));
			}
			requireException(IllegalStateException.class, lease::environment);
			lease.close();
			requireException(IllegalStateException.class, () -> owner.retainCargo(Map.of()));
		}
	}

	/**
	 * Rejects inherited authority before consuming the invocation, including explicitly empty make flags.
	 * @throws Exception if native setup or cleanup fails
	 */
	@Test
	public void rejectsConflictingOrNullEnvironmentBeforeRetention() throws Exception
	{
		try (Fixture fixture = new Fixture();
			LinuxCargoJobserver owner = LinuxCargoJobserver.create(fixture.directory, fixture.group, 1))
		{
			for (String variable : List.of("CARGO_MAKEFLAGS", "MAKEFLAGS", "MFLAGS"))
				for (String value : List.of("", "-j8"))
					requireException(IllegalArgumentException.class, () -> owner.retainCargo(Map.of(variable, value)));
			requireException(NullPointerException.class, () -> owner.retainCargo(null));
			Map<String, String> malformed = new HashMap<>();
			malformed.put("key", null);
			requireException(NullPointerException.class, () -> owner.retainCargo(malformed));
			try (var lease = owner.retainCargo(Map.of("valid", "retained")))
			{
				requireThat(lease.environment().get("valid"), "valid").isEqualTo("retained", "expected");
			}
		}
	}

	/**
	 * Rejects invalid contexts before creating an owned child directory.
	 * @throws Exception if test-owned directory cleanup fails
	 */
	@Test
	public void rejectsInvalidInputsBeforeAcquisition() throws Exception
	{
		try (Fixture fixture = new Fixture())
		{
			requireException(NullPointerException.class, () -> LinuxCargoJobserver.create(null, fixture.group, 1));
			requireException(IllegalArgumentException.class,
				() -> LinuxCargoJobserver.create(Path.of("relative"), fixture.group, 1));
			for (int value : List.of(0, -1))
			{
				requireException(IllegalArgumentException.class,
					() -> LinuxCargoJobserver.create(fixture.directory, value, 1));
				requireException(IllegalArgumentException.class,
					() -> LinuxCargoJobserver.create(fixture.directory, fixture.group, value));
			}
			Path spaced = Files.createDirectory(fixture.directory.resolve("with space"));
			requireException(IllegalArgumentException.class, () -> LinuxCargoJobserver.create(spaced, fixture.group, 1));
			Files.delete(spaced);
			try (var children = Files.list(fixture.directory))
			{
				requireThat(children.count(), "createdEntries").isEqualTo(0L, "expected");
			}
		}
	}

	/**
	 * Rejects a filename containing the auth-option marker that jobserver 0.1.34 selects using rsplit_once.
	 * @throws Exception if fixture setup or cleanup fails
	 */
	@Test
	public void rejectsAnAuthMarkerInsideTheParentPath() throws Exception
	{
		try (Fixture fixture = new Fixture())
		{
			Path ambiguous = Files.createDirectory(fixture.directory.resolve("--jobserver-auth=fifo:other"));
			requireException(IllegalArgumentException.class, () ->
			{
				try (var owner = LinuxCargoJobserver.create(ambiguous, fixture.group, 1))
				{
					// A returned owner is closed even when the rejection under test is absent.
				}
			});
		}
	}

	/**
	 * Retains the original setup error and its partial path owner when cleanup cannot remove a foreign fixture entry.
	 * The fixture models an independently failed path cleanup, not a real native write failure.
	 * @throws Exception if fixture cleanup fails
	 */
	@Test
	public void retainsPartialOwnerAndOriginalSetupFailure() throws Exception
	{
		try (Fixture fixture = new Fixture())
		{
			Faults calls = new Faults();
			calls.failWrite = true;
			calls.blockCleanup = true;
			LinuxCargoJobserver.PreparationFailure failure = requireException(LinuxCargoJobserver.PreparationFailure.class,
				() -> LinuxCargoJobserver.SharedSecrets.create(fixture.directory, fixture.group, 2, calls));
			requireThat(failure.getCause().getCause() == calls.writeFailure, "originalNativeFailure").isTrue();
			requireThat(failure.getCause().getSuppressed().length, "suppressedCleanupErrors").isEqualTo(1, "expected");
			requireThat(Files.exists(calls.blocker), "retainedCleanupPath").isTrue();
			requireThat(calls.closes, "descriptorCloseAttempts").isEqualTo(1, "expected");
			Files.delete(calls.blocker);
			failure.close();
			failure.close();
			requireThat(Files.exists(calls.fifo.getParent()), "ownerDirectoryAfterRetry").isFalse();
			requireThat(calls.closes, "descriptorCloseAttemptsAfterRetry").isEqualTo(1, "expected");
		}
	}

	/**
	 * Does not retry a consumed descriptor after a simulated Linux close error, even while path deletion needs retry.
	 * The fixture closes the real descriptor before reporting the documented released-FD failure outcome.
	 * @throws Exception if fixture setup or cleanup fails
	 */
	@Test
	public void retriesPathCleanupWithoutClosingDescriptorAgain() throws Exception
	{
		try (Fixture fixture = new Fixture())
		{
			Faults calls = new Faults();
			LinuxCargoJobserver owner = LinuxCargoJobserver.SharedSecrets.create(fixture.directory, fixture.group, 1, calls);
			calls.failClose = true;
			calls.blocker = Files.writeString(calls.fifo.getParent().resolve("fixture-blocker"), "owned by test");
			IOException failure = requireException(IOException.class, owner::close);
			requireThat(failure == calls.closeFailure, "originalCloseFailure").isTrue();
			requireThat(failure.getSuppressed().length, "pathCleanupErrors").isEqualTo(1, "expected");
			Files.delete(calls.blocker);
			owner.close();
			owner.close();
			requireThat(calls.closes, "nativeCloseAttempts").isEqualTo(1, "expected");
			requireException(IllegalStateException.class, () -> owner.retainCargo(Map.of()));
		}
	}

	/**
	 * Cleans a partial seed after an invalid zero-progress boundary result.
	 * @throws Exception if fixture setup or cleanup fails
	 */
	@Test
	public void rejectsZeroProgressAndCleansOwnedResources() throws Exception
	{
		try (Fixture fixture = new Fixture())
		{
			Faults calls = new Faults();
			calls.zeroWrite = true;
			requireException(IOException.class,
				() -> LinuxCargoJobserver.SharedSecrets.create(fixture.directory, fixture.group, 2, calls));
			requireThat(calls.closes, "closedOwnerEndpoint").isEqualTo(1, "expected");
			requireThat(Files.exists(calls.fifo.getParent()), "setupDirectory").isFalse();
		}
	}

	/**
	 * Restores owner setup access when the native creation boundary applies a restrictive umask.
	 * The fixture creates the actual FIFO with zero permission bits; it does not change process-global umask.
	 * @throws Exception if native setup or cleanup fails
	 */
	@Test
	public void establishesOwnerAccessAfterRestrictiveCreation() throws Exception
	{
		try (Fixture fixture = new Fixture())
		{
			Faults calls = new Faults();
			calls.restrictCreation = true;
			try (var owner = LinuxCargoJobserver.SharedSecrets.create(fixture.directory, fixture.group, 2, calls);
				var lease = owner.retainCargo(Map.of()))
			{
				requireThat(Files.exists(fifo(lease.environment())), "accessibleOwnerPool").isTrue();
			}
		}
	}

	/**
	 * Extracts the exact path from this owner's emitted auth option for behavioral consumer checks.
	 * @param environment owner-produced complete launch environment
	 * @return exact emitted FIFO path
	 */
	static Path fifo(Map<String, String> environment)
	{
		String flags = environment.get("CARGO_MAKEFLAGS");
		return Path.of(flags.substring(flags.lastIndexOf("fifo:") + "fifo:".length()));
	}

	/**
	 * Owns test-local storage; it models no CAT runner or remote workflow.
	 */
	static final class Fixture implements AutoCloseable
	{
		/** Exclusive local parent, removed after every owner created by the test is closed. */
		final Path directory;
		/** Current numeric group, allowing a local test without privileged credential changes. */
		final int group;

		/**
		 * Creates a local owner context.
		 * @throws IOException if storage or group lookup fails
		 */
		Fixture() throws IOException
		{
			this.directory = Files.createTempDirectory("cargo-fifo-test-");
			try { this.group = (Integer) Files.getAttribute(directory, "unix:gid"); }
			catch (IOException | RuntimeException | Error failure)
			{
				try { Files.delete(directory); }
				catch (IOException cleanup) { failure.addSuppressed(cleanup); }
				throw failure;
			}
		}

		/**
		 * Removes only this fixture's paths after test-owned native owners have closed.
		 * @throws IOException if fixture removal fails
		 */
		@Override
		public void close() throws IOException
		{
			try (var paths = Files.walk(directory))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}

	/**
	 * Controls the native boundary's reported outcomes while delegating acquired resources to real Linux operations.
	 */
	private static final class Faults implements LinuxCargoJobserver.Calls
	{
		/** Actual native implementation used for all unaffected operations and resource release. */
		private final LinuxCargoJobserver.Calls actual = LinuxCargoJobserver.SharedSecrets.nativeCalls();
		/** Test-owned error returned by the controlled write boundary. */
		private final IOException writeFailure = new IOException("Controlled native write failure");
		/** Test-owned error reported after the real descriptor has been closed. */
		private final IOException closeFailure = new IOException("Controlled released-descriptor close error");
		/** Indicates whether write reports the controlled error. */
		private boolean failWrite = false;
		/** Indicates whether write reports invalid zero progress. */
		private boolean zeroWrite = false;
		/** Indicates whether close reports an error after consuming the actual descriptor. */
		private boolean failClose = false;
		/** Indicates whether the write failure creates a test-owned entry preventing directory removal. */
		private boolean blockCleanup = false;
		/** Indicates whether the fixture applies a zero-permission creation result, modeling restrictive umask. */
		private boolean restrictCreation = false;
		/** Actual created FIFO whose parent contains the controlled path-cleanup fixture. */
		private Path fifo = null;
		/** Test-owned foreign entry removed before retrying owner cleanup. */
		private Path blocker = null;
		/** Number of actual native close attempts made through this boundary. */
		private int closes = 0;

		/**
		 * Creates controlled external access without acquiring native resources.
		 */
		private Faults() { }

		@Override
		public void createFifo(Path path, int mode) throws IOException
		{
			int creationMode = restrictCreation ? 0 : mode;
			actual.createFifo(path, creationMode);
			fifo = path;
		}

		@Override
		public int open(Path path) throws IOException { return actual.open(path); }

		@Override
		public int write(int descriptor, byte[] tokens, int count) throws IOException
		{
			if (failWrite)
			{
				if (blockCleanup)
					blocker = Files.writeString(fifo.getParent().resolve("fixture-blocker"), "owned by test");
				throw writeFailure;
			}
			if (zeroWrite)
				return 0;
			return actual.write(descriptor, tokens, count);
		}

		@Override
		public void close(int descriptor) throws IOException
		{
			closes++;
			actual.close(descriptor);
			if (failClose)
				throw closeFailure;
		}
	}
}
