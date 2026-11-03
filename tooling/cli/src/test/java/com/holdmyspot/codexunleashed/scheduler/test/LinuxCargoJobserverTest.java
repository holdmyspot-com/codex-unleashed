package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxCargoJobserver;
import com.holdmyspot.codexunleashed.scheduler.LinuxFifoBindings;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
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
	 * Rejects native fixture execution on another operating system without weakening production validation.
	 */
	static void requireLinux()
	{
		if (!System.getProperty("os.name").equals("Linux"))
			throw new SkipException("These native owner checks require Linux");
	}

	/**
	 * Supplies a controlled zero-progress return and a controlled exception before the actual native write.
	 * @return one flag per case: true for zero progress, false for the controlled exception
	 */
	@DataProvider
	public Object[][] returnFaults() { return new Object[][] {{true}, {false}}; }

	/**
	 * A transfer fault leaves the lease retained until its logical attempt retires, without replaying uncertain I/O.
	 * The controlled native boundary models zero progress or a pre-write exception; no operating-system fault is claimed.
	 * @param zeroProgress indicates whether the controlled write returns zero instead of throwing its retained exception
	 * @throws Exception if actual fixture setup or owned native cleanup fails
	 */
	@Test(dataProvider = "returnFaults")
	public void retiresFailedTransferBeforeLeaseRelease(boolean zeroProgress) throws Exception
	{
		requireLinux();
		try (var fixture = new Fixture())
		{
			var faults = new Faults(fixture.scope);
			try (var owner = LinuxCargoJobserver.SharedSecrets.create(fixture.directory, fixture.group, 2, faults);
				var lease = owner.retainCargo(Map.of()); var attempt = lease.retainAttempt())
			{
				requireThat(attempt.tryBorrow().getAsInt(), "borrowedByte").isEqualTo((int) '+', "seedByte");
				faults.zeroWrite = zeroProgress;
				faults.failWrite = !zeroProgress;
				IOException failure = requireException(IOException.class, () -> attempt.returnToken((byte) '+'));
				if (!zeroProgress)
					requireThat(failure, "originalTransferFault").isReferenceEqualTo(faults.writeFailure, "controlledFault");
				requireException(IllegalStateException.class, lease::close);
				int writesAtFault = faults.writes;
				requireException(IOException.class, attempt::close);
				requireThat(attempt.isClosed(), "retiredDespiteFault").isTrue();
				lease.close();
				attempt.close();
				lease.close();
				requireThat(faults.writes, "noUncertainWriteReplay").isEqualTo(writesAtFault, "atFault");
			}
			requireThat(faults.closes, "nativeDescriptorReleasedOnce").isEqualTo(1, "oneClose");
		}
	}

	/**
	 * Keeps the root environment immutable and refuses cleanup while its single invocation is retained.
	 * @throws Exception if native setup or cleanup fails
	 */
	@Test
	public void retainsOneInvocationAndAnImmutableEnvironment() throws Exception
	{
		requireLinux();
		try (Fixture fixture = new Fixture();
			LinuxCargoJobserver owner = LinuxCargoJobserver.create(fixture.scope, fixture.directory, fixture.group, 2))
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
				Path fifo = fifo(lease);
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
	 * Names the FIFO by path, so the jobserver crate configures Cargo's children without a pre-exec hook. That hook
	 * forces fork, and concurrent forks under Frida child gating abort the forking process.
	 * @throws Exception if native setup or cleanup fails
	 */
	@Test
	public void cargoFlagsNameTheFifoPath() throws Exception
	{
		requireLinux();
		try (Fixture fixture = new Fixture();
			LinuxCargoJobserver owner = LinuxCargoJobserver.create(fixture.scope, fixture.directory, fixture.group, 2);
			var lease = owner.retainCargo(Map.of()))
		{
			String flags = lease.environment().get("CARGO_MAKEFLAGS");
			requireThat(flags, "cargoFlags").contains("--jobserver-auth=fifo:" + fifo(lease));
			requireThat(flags, "cargoFlags").doesNotContain(LinuxCargoJobserver.READ_DESCRIPTOR + ",");
			requireThat(lease.environment().containsKey("PATH"), "pathWithoutMake").isFalse();
		}
	}

	/**
	 * GNU Make, found on the lease's PATH as a build script finds it, joins the lease's jobserver when the FIFO is
	 * open on the lease's descriptors. Bash stands in for the trusted leader that opens those descriptors before exec.
	 * Make 4.3 rejects a FIFO path in its auth option, so a clean exit with no jobserver diagnostic shows that the
	 * lease's make front end translated the path to the inherited descriptors.
	 * @throws Exception if fixture setup, process execution or cleanup fails
	 */
	@Test
	public void gnuMakeJoinsLeaseJobserverThroughInheritedDescriptors() throws Exception
	{
		requireLinux();
		Path make = Path.of("/usr/bin/make");
		Path bash = Path.of("/bin/bash");
		if (!Files.isExecutable(make) || !Files.isExecutable(bash))
			throw new SkipException("GNU Make and Bash are required to observe make's jobserver import");
		Path frontEnd;
		try (Fixture fixture = new Fixture())
		{
			try (LinuxCargoJobserver owner = LinuxCargoJobserver.create(fixture.scope, fixture.directory, fixture.group, 2))
			{
				try (var lease = owner.retainCargo(Map.of("PATH", "/usr/bin:/bin")))
				{
					var descriptors = lease.descriptors();
					String path = lease.environment().get("PATH");
					requireThat(path, "leasePath").endsWith(":/usr/bin:/bin");
					frontEnd = Path.of(path.substring(0, path.indexOf(':')), "make");
					requireThat(Files.isExecutable(frontEnd), "frontEnd").isTrue();
					Path makefile = Files.writeString(fixture.directory.resolve("Makefile"), "all: a b\na b:\n\t@true\n");
					var builder = new ProcessBuilder(bash.toString(), "-c", "exec make -f \"$0\" " + descriptors.read() +
						"<>\"$1\" " + descriptors.write() + "<>\"$1\"", makefile.toString(), descriptors.fifo().toString());
					builder.environment().keySet().removeAll(List.of("CARGO_MAKEFLAGS", "MAKEFLAGS", "MFLAGS"));
					builder.environment().put("MAKEFLAGS", lease.environment().get("MAKEFLAGS"));
					builder.environment().put("PATH", path);
					builder.directory(fixture.directory.toFile()).redirectErrorStream(true);
					Process process = builder.start();
					String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
					requireThat(process.waitFor(), "makeExit").isEqualTo(0, "expected");
					requireThat(output, "makeOutput").doesNotContain("jobserver");
					Files.delete(makefile);
				}
			}
			requireThat(Files.exists(frontEnd), "frontEndAfterClose").isFalse();
		}
	}

	/**
	 * Rejects inherited authority before consuming the invocation, including explicitly empty make flags.
	 * @throws Exception if native setup or cleanup fails
	 */
	@Test
	public void rejectsConflictingOrNullEnvironmentBeforeRetention() throws Exception
	{
		requireLinux();
		try (Fixture fixture = new Fixture();
			LinuxCargoJobserver owner = LinuxCargoJobserver.create(fixture.scope, fixture.directory, fixture.group, 1))
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
		requireLinux();
		try (Fixture fixture = new Fixture())
		{
			requireException(NullPointerException.class, () -> LinuxCargoJobserver.create(fixture.scope, null, fixture.group, 1));
			requireException(IllegalArgumentException.class,
				() -> LinuxCargoJobserver.create(fixture.scope, Path.of("relative"), fixture.group, 1));
			for (int value : List.of(0, -1))
			{
				requireException(IllegalArgumentException.class,
					() -> LinuxCargoJobserver.create(fixture.scope, fixture.directory, value, 1));
				requireException(IllegalArgumentException.class,
					() -> LinuxCargoJobserver.create(fixture.scope, fixture.directory, fixture.group, value));
			}
			Path spaced = Files.createDirectory(fixture.directory.resolve("with space"));
			requireException(IllegalArgumentException.class, () -> LinuxCargoJobserver.create(fixture.scope, spaced, fixture.group, 1));
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
		requireLinux();
		try (Fixture fixture = new Fixture())
		{
			Path ambiguous = Files.createDirectory(fixture.directory.resolve("--jobserver-auth=fifo:other"));
			requireException(IllegalArgumentException.class, () ->
			{
				try (var owner = LinuxCargoJobserver.create(fixture.scope, ambiguous, fixture.group, 1))
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
		requireLinux();
		try (Fixture fixture = new Fixture())
		{
			Faults calls = new Faults(fixture.scope);
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
		requireLinux();
		try (Fixture fixture = new Fixture())
		{
			Faults calls = new Faults(fixture.scope);
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
		requireLinux();
		try (Fixture fixture = new Fixture())
		{
			Faults calls = new Faults(fixture.scope);
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
		requireLinux();
		try (Fixture fixture = new Fixture())
		{
			Faults calls = new Faults(fixture.scope);
			calls.restrictCreation = true;
			try (var owner = LinuxCargoJobserver.SharedSecrets.create(fixture.directory, fixture.group, 2, calls);
				var lease = owner.retainCargo(Map.of()))
			{
				requireThat(Files.exists(fifo(lease)), "accessibleOwnerPool").isTrue();
			}
		}
	}

	/**
	 * Returns the FIFO that the lease's consumers open, for behavioral checks of that endpoint.
	 * @param lease live owner-produced invocation lease
	 * @return exact FIFO path
	 */
	static Path fifo(LinuxCargoJobserver.CargoLease lease)
	{
		return lease.descriptors().fifo();
	}

	/**
	 * Owns test-local storage; it models no CAT runner or remote workflow.
	 */
	static final class Fixture implements AutoCloseable
	{
		/** Shared native services closed after test-owned endpoints and consumers. */
		final ToolingScope scope = new DefaultToolingScope();
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
			scope.close();
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
		private final LinuxCargoJobserver.Calls actual;
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
		/** Number of requested writes, including initial seeding and controlled failed returns. */
		private int writes = 0;

		/**
		 * Creates controlled external access without acquiring native resources.
		 * @param scope borrowed native linker, retained open by the enclosing fixture
		 */
		private Faults(ToolingScope scope) { actual = LinuxCargoJobserver.SharedSecrets.nativeCalls(scope); }

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
		public LinuxFifoBindings.Identity identity(int descriptor) throws IOException { return actual.identity(descriptor); }

		@Override
		public java.util.OptionalInt tryRead(int descriptor) throws IOException { return actual.tryRead(descriptor); }

		@Override
		public int write(int descriptor, byte[] tokens, int count) throws IOException
		{
			++writes;
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
