package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.FridaRuntime;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.Test;

import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

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
	 * Reads the actual supported session observation and rejects inactive or uncertain ownership.
	 * @throws Exception if controlled ownership setup fails
	 */
	@Test public void detachedObservationRequiresAcquiredCertainSession() throws Exception
	{
		try (var fixture = new ScriptCleanup())
		{
			var unattached = FridaRuntime.SharedSecrets.session(fixture.manager, 124L,
				java.lang.foreign.MemorySegment.NULL);
			requireException(IllegalStateException.class, unattached::detached);
			requireThat(fixture.session.detached(), "attachedNativeObservation").isFalse();
			fixture.detached = true;
			requireThat(fixture.session.detached(), "detachedNativeObservation").isTrue();
			FridaRuntime.SharedSecrets.failCleanup(fixture.session, new IOException("Uncertain reference release"));
			requireException(IllegalStateException.class, fixture.session::detached);
		}
	}

	/**
	 * Closing an unstarted owner ends its lifetime without opening its producer path.
	 * @throws IOException if resource-free cleanup fails
	 */
	@Test
	public void closesUnstartedOwnerWithoutAcquiringNativeResources() throws IOException
	{
		try (var scope = new DefaultToolingScope())
		{
		var runtime = new FridaRuntime(scope, Path.of("unused-native-producer"));

		requireException(IllegalStateException.class, runtime::newManager);
		runtime.close();
		runtime.close();

		requireException(IllegalStateException.class, runtime::start);
		requireException(IllegalStateException.class, runtime::newManager);
		}
	}

	/**
	 * Closing a runtime whose producer was loaded keeps that producer mapped in the JVM. Loading Frida installs process-wide
	 * signal handlers that its teardown does not remove, so unloading it would leave the JVM's next SIGSEGV jumping into
	 * unmapped code. A uniquely named copy of a JDK library stands in for the producer: it loads, lacks the version export,
	 * and so fails start after loading, which is the earliest point at which the producer must stay resident.
	 * @throws IOException if the stand-in cannot be copied or cleanup fails
	 */
	@Test
	public void closeKeepsLoadedProducerMapped() throws IOException
	{
		if (System.getProperty("os.name", "").startsWith("Linux") == false)
			throw new org.testng.SkipException("Process mappings are observed through Linux /proc/self/maps");
		Path temporary = java.nio.file.Files.createTempDirectory("frida-runtime-resident-");
		Path producer = temporary.resolve("stand-in-producer-" + System.nanoTime() + ".so");
		java.nio.file.Files.copy(Path.of(System.getProperty("java.home"), "lib", "libsyslookup.so"), producer);
		try (var scope = new DefaultToolingScope())
		{
			var runtime = new FridaRuntime(scope, producer);
			requireException(IOException.class, runtime::start);
			requireThat(mapped(producer), "mappedAfterFailedStart").isTrue();
			runtime.close();
			requireThat(mapped(producer), "mappedAfterClose").isTrue();
		}
		finally
		{
			java.nio.file.Files.delete(producer);
			java.nio.file.Files.delete(temporary);
		}
	}

	/**
	 * Indicates whether this JVM currently maps the named file.
	 * @param file absolute file path compared with the mapping's backing path
	 * @return {@code true} if any mapping of this process is backed by {@code file}
	 * @throws IOException if the process mappings cannot be read
	 */
	private static boolean mapped(Path file) throws IOException
	{
		String target = file.toRealPath().toString();
		return java.nio.file.Files.readAllLines(Path.of("/proc/self/maps")).stream().
			anyMatch(line -> line.endsWith(" " + target));
	}

	/**
	 * A different thread cannot acquire or close an owner's resources; the constructing thread can still close it.
	 * @throws IOException if owner-thread cleanup fails
	 * @throws InterruptedException if waiting for the rejected caller is interrupted
	 */
	@Test
	public void rejectsOtherThreadWithoutChangingOwnership() throws IOException, InterruptedException
	{
		try (var scope = new DefaultToolingScope();
			var runtime = new FridaRuntime(scope, Path.of("unused-native-producer")))
		{
			var result = new AtomicReference<Throwable>();
			Thread caller = Thread.ofPlatform().start(() ->
			{
				try
				{
					requireException(IllegalStateException.class, runtime::start);
					requireException(IllegalStateException.class, runtime::newManager);
					requireException(IllegalStateException.class, runtime::close);
				}
				catch (Throwable failure)
				{
					result.set(failure);
				}
			});
			caller.join();

			requireThat(result.get() == null, "result")
				.withContext("Other-thread operation failed its rejection assertion: " + result.get(), "diagnostic")
				.isTrue();
		}
	}
	/**
	 * Releases owned references after a target exits between the destroyed check and unload.
	 * @throws Exception if controlled native operation setup or cleanup fails
	 */
	@Test
	public void reconcilesDestroyedScriptDuringUnload() throws Exception
	{
		try (var fixture = new ScriptCleanup())
		{
			fixture.script.close();
			fixture.script.close();
			requireThat(fixture.unloads, "unloadAttempts").isEqualTo(1);
			requireThat(fixture.unrefs, "releasedReferences").isEqualTo(2);
			requireThat(fixture.freedErrors, "freedNativeErrors").isEqualTo(1);
			requireThat(FridaRuntime.SharedSecrets.scripts(fixture.session), "scriptRegistrationRetired").isEqualTo(0);
		}
	}

	/**
	 * Permits retirement only for the exact pinned error and confirmed destroyed state.
	 * @param domain native error domain
	 * @param code native error code
	 * @param destroyed independently observed post-failure destroyed state
	 * @param observationFails whether the post-state is unavailable
	 * @param unrefFails whether native reference release is uncertain
	 * @throws Exception if controlled native operation setup fails
	 */
	@Test(dataProvider = "unreconciledScriptFailures")
	public void retainsUnreconciledScriptFailure(String domain, int code, boolean destroyed,
		boolean observationFails, boolean unrefFails) throws Exception
	{
		try (var fixture = new ScriptCleanup())
		{
			fixture.domain = domain;
			fixture.code = code;
			fixture.destroyedAfterFailure = destroyed;
			fixture.observationFails = observationFails;
			fixture.unrefFails = unrefFails;
			IOException first = closeFailure(fixture.script);
			int observations = fixture.observations;
			int unrefs = fixture.unrefs;
			IOException repeated = closeFailure(fixture.script);
			requireThat(repeated.getCause() == first, "firstCleanupFailureRetained").isTrue();
			requireThat(fixture.unloads, "uncertainUnloadNotRepeated").isEqualTo(1);
			requireThat(fixture.unrefs, "uncertainUnrefNotRepeated").isEqualTo(unrefs);
			requireThat(fixture.observations, "uncertainObservationNotRepeated").isEqualTo(observations);
			requireThat(FridaRuntime.SharedSecrets.scripts(fixture.session), "uncertainScriptRemainsRegistered").isEqualTo(1);
			requireThat(fixture.freedErrors, "nativeErrorFreedOnce").isEqualTo(1);
		}
	}

	/**
	 * Supplies distinct error-domain, code, post-state and uncertain cleanup boundaries.
	 * @return independent failure cases
	 */
	@org.testng.annotations.DataProvider
	public Object[][] unreconciledScriptFailures()
	{
		return new Object[][] {
			{"other-error-quark", 6, true, false, false},
			{"frida-error-quark", 1, true, false, false},
			{"frida-error-quark", 6, false, false, false},
			{"frida-error-quark", 6, true, true, false},
			{"frida-error-quark", 6, true, false, true}
		};
	}

	/**
	 * Captures checked cleanup failure without accepting successful close.
	 * @param owner retained resource being closed
	 * @return actual cleanup failure
	 * @throws Exception if a different checked failure occurs
	 */
	private static IOException closeFailure(AutoCloseable owner) throws Exception
	{
		try { owner.close(); }
		catch (IOException failure) { return failure; }
		throw new AssertionError("Expected retained cleanup failure");
	}

	/** Models native operation results; this is ownership evidence, not a native ABI gate. */
	private static final class ScriptCleanup implements AutoCloseable, FridaRuntime.Lifecycle
	{
		/** Borrowed immutable services owned by this fixture. */
		final ToolingScope scope = new DefaultToolingScope();
		/** Owned memory for controlled native records. */
		final java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined();
		/** Actual Java lifecycle owner without native acquisition. */
		final FridaRuntime runtime = new FridaRuntime(scope, Path.of("unused-native-producer"));
		/** Controlled native manager retained for observation guards. */
		final FridaRuntime.Manager manager;
		/** Controlled exact session whose supported native observation is tested. */
		final FridaRuntime.Manager.Session session;
		/** Independently supplied native detached result. */
		boolean detached;
		/** Exact retained script owner under test. */
		final FridaRuntime.Manager.Session.Script script;
		/** Counts independently observed lifecycle operations. */
		int observations, unloads, unrefs, freedErrors;
		/** Controlled native error domain. */
		String domain = "frida-error-quark";
		/** Controlled native error code. */
		int code = 6;
		/** Controlled exact post-failure script state. */
		boolean destroyedAfterFailure = true;
		/** Independent observation and destruction failures. */
		boolean observationFails, unrefFails;

		/**
		 * Creates exact Java owners bound to these controlled native operations.
		 */
		ScriptCleanup()
		{
			FridaRuntime.SharedSecrets.initialize(runtime, this);
			manager = FridaRuntime.SharedSecrets.manager(runtime, true);
			session = FridaRuntime.SharedSecrets.session(manager, 123L, arena.allocate(8));
			script = FridaRuntime.SharedSecrets.loadedScript(session, "send('unused')", arena.allocate(8),
				arena.allocate(8));
		}

		/**
		 * Supplies only the supported native session observation, without claiming process completion.
		 * @param ignored retained session description
		 * @return independently controlled detached flag
		 */
		@Override public int isDetached(java.lang.foreign.MemorySegment ignored) { return detached ? 1 : 0; }

		/**
		 * Supplies independently controlled destruction observations.
		 * @param ignored retained script
		 * @return exact controlled destroyed flag
		 */
		@Override public int isDestroyed(java.lang.foreign.MemorySegment ignored)
		{
			if (observations++ == 0) return 0;
			if (observationFails) throw new IllegalStateException("post-failure observation unavailable");
			return destroyedAfterFailure ? 1 : 0;
		}

		/**
		 * Supplies the exact pinned unload failure.
		 * @param owner retained script
		 * @param cancellable unused cancellation description
		 * @param error output native error record
		 */
		@Override public void unloadScript(java.lang.foreign.MemorySegment owner, java.lang.foreign.MemorySegment cancellable,
			java.lang.foreign.MemorySegment error)
		{
			unloads++;
			var fields = arena.allocate(16, 8);
			fields.set(java.lang.foreign.ValueLayout.JAVA_INT, 0, 1);
			fields.set(java.lang.foreign.ValueLayout.JAVA_INT, 4, code);
			fields.set(java.lang.foreign.ValueLayout.ADDRESS, 8, arena.allocateFrom("Script is destroyed"));
			error.set(java.lang.foreign.ValueLayout.ADDRESS, 0, fields);
		}

		/**
		 * Supplies the native error domain.
		 * @param ignored controlled native quark
		 * @return allocated error domain
		 */
		@Override public java.lang.foreign.MemorySegment quarkToString(int ignored) { return arena.allocateFrom(domain); }
		/**
		 * Records independent reference release.
		 * @param ignored retained reference
		 */
		@Override public void unref(java.lang.foreign.MemorySegment ignored) { unrefs++; if (unrefFails) throw new IllegalStateException("native reference release uncertain"); }
		/**
		 * Records native error retirement.
		 * @param ignored retained error
		 */
		@Override public void errorFree(java.lang.foreign.MemorySegment ignored) { freedErrors++; }
		@Override public void close() { try { arena.close(); } finally { scope.close(); } }
	}

}
