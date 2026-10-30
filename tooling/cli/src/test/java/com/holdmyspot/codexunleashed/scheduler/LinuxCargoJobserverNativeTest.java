package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
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
	 * Limits these real FIFO operations to the supported Linux platform.
	 */
	@BeforeClass
	public void requireLinuxForNativeFixtures()
	{
		LinuxCargoJobserverTest.requireLinux();
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
		try (var fixture = new LinuxCargoJobserverTest.Fixture();
			var owner = LinuxCargoJobserver.create(fixture.directory, fixture.group, jobs))
		{
			Path fifo;
			Path directory;
			try (var lease = owner.retainCargo(Map.of()))
			{
				fifo = LinuxCargoJobserverTest.fifo(lease.environment());
				directory = fifo.getParent();
				Object identity = Files.readAttributes(fifo, "unix:dev,ino");
				requireThat(Files.getPosixFilePermissions(fifo), "fifoAccess")
					.isEqualTo(PosixFilePermissions.fromString("rw-rw----"), "expected");
				requireThat(Files.getPosixFilePermissions(directory), "directoryAccess")
					.isEqualTo(PosixFilePermissions.fromString("rwx--x---"), "expected");
				requireThat(Files.getAttribute(fifo, "unix:gid"), "workloadGroup").isEqualTo(fixture.group, "expected");
				try (Peer first = new Peer(fifo))
				{
					requireThat(first.drain(), "initialExplicitTokens").isEqualTo(jobs - 1, "expected");
					first.release();
					requireException(IllegalStateException.class, owner::close);
				}
				try (Peer second = new Peer(fifo))
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
	 * Fails oversized setup through nonblocking write without starting a consumer or retaining its partial pool.
	 * @throws Exception if fixture setup or cleanup fails
	 */
	@Test
	public void capacityExhaustionFailsAndCleansPartialPool() throws Exception
	{
		try (var fixture = new LinuxCargoJobserverTest.Fixture())
		{
			IOException failure = requireException(IOException.class,
				() -> LinuxCargoJobserver.create(fixture.directory, fixture.group, Integer.MAX_VALUE));
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
		private final LinuxCargoJobserver.Calls calls = LinuxCargoJobserver.SharedSecrets.nativeCalls();
		/** LP64 native read operation with same-call errno capture. */
		private final MethodHandle read = Linker.nativeLinker().downcallHandle(
			Linker.nativeLinker().defaultLookup().find("read").orElseThrow(),
			FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
			Linker.Option.captureCallState("errno"));
		/** Exact offset of the JDK-captured errno integer. */
		private final long errnoOffset = Linker.Option.captureStateLayout()
			.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
		/** Test-owned endpoint, or -1 after its one close attempt. */
		private int descriptor;

		/**
		 * Opens the actual owner-produced pathname without borrowing its descriptor.
		 * @param fifo owner-produced FIFO path
		 * @throws IOException if open fails
		 */
		private Peer(Path fifo) throws IOException { this.descriptor = calls.open(fifo); }

		/**
		 * Reads every currently available token until Linux reports EAGAIN.
		 * @return actual number of one-byte tokens consumed
		 * @throws IOException if native read reports another failure or unexpected EOF
		 */
		private int drain() throws IOException
		{
			int tokens = 0;
			try (Arena arena = Arena.ofConfined())
			{
				MemorySegment state = arena.allocate(Linker.Option.captureStateLayout());
				MemorySegment buffer = arena.allocate(1);
				while (true)
				{
					long count = (long) read.invokeExact(state, descriptor, buffer, 1L);
					if (count == 1)
					{
						tokens++;
						continue;
					}
					int error = state.get(ValueLayout.JAVA_INT, errnoOffset);
					if (count == -1 && error == 11)
						return tokens;
					if (count == -1 && error == 4)
						continue;
					throw new IOException("Peer read returned " + count + " errno " + error);
				}
			}
			catch (RuntimeException | Error failure) { throw failure; }
			catch (IOException failure) { throw failure; }
			catch (Throwable failure) { throw new IOException("Native peer invocation failed", failure); }
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
