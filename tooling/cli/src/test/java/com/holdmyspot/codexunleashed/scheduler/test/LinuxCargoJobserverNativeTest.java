package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxCargoJobserver;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import com.holdmyspot.codexunleashed.secrets.LinuxCargoJobserverSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises actual local Linux FIFO creation, exact token capacity, native reads/writes and retained endpoint lifetime.
 * These checks use the current GID; they do not establish supervisor credential-drop access or Cargo integration.
 */
public final class LinuxCargoJobserverNativeTest
{
	/**
	 * Creates local FIFO integration checks.
	 */
	public LinuxCargoJobserverNativeTest() { }

	/**
	 * Borrows one immutable native binding set per scope while keeping other scopes independent.
	 */
	@Test
	public void sharesBindingsWithinScope()
	{
		LinuxCargoJobserverTest.requireLinux();
		try (var first = new DefaultToolingScope(); var second = new DefaultToolingScope())
		{
			var bindings = LinuxCargoJobserverSecrets.getLinuxCargoJobserverAccess().nativeCalls(first);
			requireThat(LinuxCargoJobserverSecrets.getLinuxCargoJobserverAccess().nativeCalls(first), "sharedNativeBindings")
				.isReferenceEqualTo(bindings, "sameScope");
			requireThat(LinuxCargoJobserverSecrets.getLinuxCargoJobserverAccess().nativeCalls(second) == bindings, "independentScopes").isFalse();
			first.close();
			requireException(IllegalStateException.class, () -> LinuxCargoJobserverSecrets.getLinuxCargoJobserverAccess().nativeCalls(first));
		}
	}

	/**
	 * A live logical attempt prevents releasing the Cargo lease and deleting its native FIFO.
	 * This local fixture has no compiler process; actual descendant barriers remain a separate native gate.
	 * @throws Exception if native fixture setup or owned cleanup fails
	 */
	@Test
	public void retainsLeaseUntilAttemptRetires() throws Exception
	{
		LinuxCargoJobserverTest.requireLinux();
		try (var fixture = new LinuxCargoJobserverTest.Fixture();
			var owner = LinuxCargoJobserver.create(fixture.scope, fixture.directory, fixture.group, 1);
			var lease = owner.retainCargo(Map.of()); var attempt = lease.retainAttempt())
		{
			requireException(IllegalStateException.class, lease::close);
			requireException(IllegalStateException.class, owner::close);
			requireThat(lease.environment().containsKey("MAKEFLAGS"), "leaseRemainsUsable").isTrue();
			requireThat(attempt.tryBorrow().isEmpty(), "implicitOnlyCapacity").isTrue();
			attempt.close();
			lease.close();
			requireException(IllegalStateException.class, lease::retainAttempt);
		}
	}

	/**
	 * Supplies capacities on both sides of the zero-explicit-token boundary.
	 * @return positive total capacities including the implicit Cargo slot
	 */
	@DataProvider
	public Object[][] capacities() { return new Object[][] {{1}, {2}, {4}}; }

	/**
	 * Exposes exactly jobs minus one explicit tokens, preserves returned tokens and retains the pool across peer gaps.
	 * @param jobs total configured capacity including Cargo's implicit slot
	 * @throws Exception if native setup, I/O or cleanup fails
	 */
	@Test(dataProvider = "capacities")
	public void seedsExactExplicitCapacityAndRetainsPoolAcrossPeers(int jobs) throws Exception
	{
		LinuxCargoJobserverTest.requireLinux();
		try (var fixture = new LinuxCargoJobserverTest.Fixture();
			var owner = LinuxCargoJobserver.create(fixture.scope, fixture.directory, fixture.group, jobs))
		{
			Path fifo;
			Path directory;
			try (var lease = owner.retainCargo(Map.of()))
			{
				fifo = LinuxCargoJobserverTest.fifo(lease);
				directory = fifo.getParent();
				Object identity = Files.readAttributes(fifo, "unix:dev,ino");
				requireThat(Files.getPosixFilePermissions(fifo), "fifoAccess")
					.isEqualTo(PosixFilePermissions.fromString("rw-rw----"), "expected");
				requireThat(Files.getPosixFilePermissions(directory), "directoryAccess")
					.isEqualTo(PosixFilePermissions.fromString("rwx--x---"), "expected");
				requireThat(Files.getAttribute(fifo, "unix:gid"), "workloadGroup").isEqualTo(fixture.group, "expected");
				try (Peer first = new Peer(fixture.scope, fifo))
				{
					requireThat(first.drain(), "initialExplicitTokens").isEqualTo(jobs - 1, "expected");
					first.release();
					requireException(IllegalStateException.class, owner::close);
				}
				try (Peer second = new Peer(fixture.scope, fifo))
				{
					requireThat(second.drain(), "returnedTokenAcrossPeerGap").isEqualTo(1, "expected");
					requireThat(second.drain(), "noReseeding").isEqualTo(0, "expected");
				}
				requireThat(Files.readAttributes(fifo, "unix:dev,ino"), "fifoIdentity")
					.isEqualTo(identity, "original");
			}
			owner.close();
			requireThat(Files.exists(fifo), "fifoAfterAllConsumersClose").isFalse();
			requireThat(Files.exists(directory), "directoryAfterAllConsumersClose").isFalse();
		}
	}

	/**
	 * Supplies distinct and repeated opaque bytes without changing the FIFO's configured capacity.
	 * @return pairs of unsigned token bytes
	 */
	@DataProvider
	public Object[][] opaqueBytes() { return new Object[][] {{0x51, 0xa5}, {0xa5, 0xa5}}; }

	/**
	 * Returns and reclaims exact opaque bytes through separate logical owners and an independent native endpoint.
	 * Closing one logical attempt cannot reclaim its live peer's reservation or release the invocation lease.
	 * @param first first unsigned token byte
	 * @param second second unsigned token byte, possibly equal to the first
	 * @throws Exception if native fixture setup, observation or cleanup fails
	 */
	@Test(dataProvider = "opaqueBytes")
	public void recoversExactBytesThroughLease(int first, int second) throws Exception
	{
		LinuxCargoJobserverTest.requireLinux();
		try (var fixture = new LinuxCargoJobserverTest.Fixture();
			var owner = LinuxCargoJobserver.create(fixture.scope, fixture.directory, fixture.group, 3);
			var lease = owner.retainCargo(Map.of()); var a = lease.retainAttempt(); var b = lease.retainAttempt())
		{
			var calls = LinuxCargoJobserverSecrets.getLinuxCargoJobserverAccess().nativeCalls(fixture.scope);
			int observer = calls.open(LinuxCargoJobserverTest.fifo(lease));
			try
			{
				requireThat(calls.tryRead(observer).getAsInt(), "firstSeed").isEqualTo((int) '+', "seedByte");
				requireThat(calls.tryRead(observer).getAsInt(), "secondSeed").isEqualTo((int) '+', "seedByte");
				requireThat(calls.tryRead(observer).isEmpty(), "initialCapacity").isTrue();
				requireThat(calls.write(observer, new byte[] {(byte) first, (byte) second}, 2), "opaqueSeed")
					.isEqualTo(2, "unchangedCapacity");
				requireThat(a.tryBorrow().getAsInt(), "firstBorrow").isEqualTo(first, "originalFirst");
				requireThat(b.tryBorrow().getAsInt(), "secondBorrow").isEqualTo(second, "originalSecond");
				requireThat(a.tryBorrow().isEmpty(), "noExtraGrant").isTrue();
				a.returnToken((byte) first);
				a.close();
				requireException(IllegalStateException.class, lease::close);
				requireThat(calls.tryRead(observer).getAsInt(), "returnedFirst").isEqualTo(first, "originalFirst");
				requireThat(calls.tryRead(observer).isEmpty(), "livePeerNotReclaimed").isTrue();
				requireThat(calls.write(observer, new byte[] {(byte) first}, 1), "restoreObservedByte")
					.isEqualTo(1, "oneByte");
				b.close();
				lease.close();
				requireThat(calls.tryRead(observer).getAsInt(), "finalFirst").isEqualTo(first, "originalFirst");
				requireThat(calls.tryRead(observer).getAsInt(), "reclaimedSecond").isEqualTo(second, "originalSecond");
				requireThat(calls.tryRead(observer).isEmpty(), "noDuplicateReturn").isTrue();
			}
			finally { calls.close(observer); }
		}
	}

	/**
	 * Fails oversized setup through nonblocking write without starting a consumer or retaining its partial pool.
	 * @throws Exception if fixture setup or cleanup fails
	 */
	@Test
	public void capacityExhaustionFailsAndCleansPartialPool() throws Exception
	{
		LinuxCargoJobserverTest.requireLinux();
		try (var fixture = new LinuxCargoJobserverTest.Fixture())
		{
			IOException failure = requireException(IOException.class,
				() -> LinuxCargoJobserver.create(fixture.scope, fixture.directory, fixture.group, Integer.MAX_VALUE));
			requireThat(failure.getMessage().contains("2147483646"), "requestedExplicitTokensReported").isTrue();
			requireThat(failure.getCause().getMessage().contains("errno 11"), "capacityFailureReported").isTrue();
			try (var paths = Files.list(fixture.directory))
			{
				requireThat(paths.count(), "retainedPartialPools").isEqualTo(0L, "expected");
			}
		}
	}

	/**
	 * Owns an independent native nonblocking endpoint used to observe this owner's emitted FIFO.
	 * The endpoint models a token consumer, not a Cargo process or jobserver-library import.
	 */
	private static final class Peer implements AutoCloseable
	{
		/** Actual Linux open/write/close access for this independently owned endpoint. */
		private final LinuxCargoJobserver.Calls calls;
		/** Test-owned endpoint, or -1 after its one close attempt. */
		private int descriptor;

		/**
		 * Opens the actual owner-produced pathname without borrowing its descriptor.
		 * @param scope borrowed native services, kept open until the endpoint closes
		 * @param fifo owner-produced FIFO path
		 * @throws IOException if open fails
		 */
		private Peer(ToolingScope scope, Path fifo) throws IOException
		{
			this.calls = LinuxCargoJobserverSecrets.getLinuxCargoJobserverAccess().nativeCalls(scope);
			this.descriptor = calls.open(fifo);
		}

		/**
		 * Reads every currently available token until Linux reports EAGAIN.
		 * @return actual number of one-byte tokens consumed
		 * @throws IOException if native read reports another failure or unexpected EOF
		 */
		private int drain() throws IOException
		{
			int tokens = 0;
			while (calls.tryRead(descriptor).isPresent())
				++tokens;
			return tokens;
		}

		/**
		 * Returns one actual token to the shared FIFO.
		 * @throws IOException if native write fails
		 */
		private void release() throws IOException
		{
			requireThat(calls.write(descriptor, new byte[] {'+'}, 1), "returnedToken").isEqualTo(1, "expected");
		}

		/**
		 * Consumes this independently owned endpoint exactly once.
		 * @throws IOException if native close reports an error
		 */
		@Override
		public void close() throws IOException
		{
			if (descriptor >= 0)
			{
				int owned = descriptor;
				descriptor = -1;
				calls.close(owned);
			}
		}
	}
}
