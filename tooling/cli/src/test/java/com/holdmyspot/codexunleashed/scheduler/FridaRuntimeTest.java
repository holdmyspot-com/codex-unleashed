package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.Test;
import static org.testng.Assert.assertTrue;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
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
	/** An asynchronous target exit between the destroyed check and unload still releases owned references. */
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
			requireThat(fixture.scriptSet.isEmpty(), "scriptRegistrationRetired").isTrue();
		}
	}

	/** Only the exact pinned error with a confirmed destroyed state permits reference retirement. */
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
			requireThat(fixture.scriptSet.size(), "uncertainScriptRemainsRegistered").isEqualTo(1);
			requireThat(fixture.freedErrors, "nativeErrorFreedOnce").isEqualTo(1);
		}
	}

	/** Supplies distinct error-domain, code, post-state and uncertain cleanup boundaries. */
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

	/** Captures checked cleanup failure without accepting a successful close. */
	private static IOException closeFailure(AutoCloseable owner) throws Exception
	{
		try { owner.close(); }
		catch (IOException failure) { return failure; }
		throw new AssertionError("Expected retained cleanup failure");
	}

	/** Models native operation results; this is ownership evidence, not a native ABI gate. */
	private static final class ScriptCleanup implements AutoCloseable
	{
		final java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined();
		final FridaRuntime runtime = new FridaRuntime(Path.of("unused-native-producer"));
		final FridaRuntime.Manager.Session.Script script;
		final java.util.Set<?> scriptSet;
		int observations, unloads, unrefs, freedErrors;
		String domain = "frida-error-quark";
		int code = 6;
		boolean destroyedAfterFailure = true;
		boolean observationFails, unrefFails;

		ScriptCleanup() throws Exception
		{
			var managerType = FridaRuntime.Manager.class;
			var managerConstructor = managerType.getDeclaredConstructor(FridaRuntime.class);
			managerConstructor.setAccessible(true);
			var manager = managerConstructor.newInstance(runtime);
			var sessionType = FridaRuntime.Manager.Session.class;
			var sessionConstructor = sessionType.getDeclaredConstructor(managerType, long.class);
			sessionConstructor.setAccessible(true);
			var session = sessionConstructor.newInstance(manager, 123L);
			var constructor = FridaRuntime.Manager.Session.Script.class.getDeclaredConstructor(sessionType, String.class);
			constructor.setAccessible(true);
			script = constructor.newInstance(session, "send('unused')");
			set(script, "handle", arena.allocate(8));
			set(script, "options", arena.allocate(8));
			set(script, "loadAttempted", true);
			var scripts = sessionType.getDeclaredField("scripts");
			scripts.setAccessible(true);
			@SuppressWarnings("unchecked") var registered = (java.util.Set<FridaRuntime.Manager.Session.Script>) scripts.get(session);
			registered.add(script);
			scriptSet = registered;
			bind("isDestroyed", "destroyed", int.class, java.lang.foreign.MemorySegment.class);
			bind("unloadScript", "unload", void.class, java.lang.foreign.MemorySegment.class,
				java.lang.foreign.MemorySegment.class, java.lang.foreign.MemorySegment.class);
			bind("unref", "unref", void.class, java.lang.foreign.MemorySegment.class);
			bind("errorFree", "freeError", void.class, java.lang.foreign.MemorySegment.class);
			bind("quarkToString", "quark", java.lang.foreign.MemorySegment.class, int.class);
		}

		private static void set(Object target, String name, Object value) throws Exception
		{
			var field = target.getClass().getDeclaredField(name);
			field.setAccessible(true);
			field.set(target, value);
		}

		private void bind(String field, String method, Class<?> result, Class<?>... arguments) throws Exception
		{
			set(runtime, field, java.lang.invoke.MethodHandles.lookup().findVirtual(ScriptCleanup.class, method,
				java.lang.invoke.MethodType.methodType(result, arguments)).bindTo(this));
		}

		private int destroyed(java.lang.foreign.MemorySegment ignored)
		{
			if (observations++ == 0) return 0;
			if (observationFails) throw new IllegalStateException("post-failure observation unavailable");
			return destroyedAfterFailure ? 1 : 0;
		}

		private void unload(java.lang.foreign.MemorySegment owner, java.lang.foreign.MemorySegment cancellable,
			java.lang.foreign.MemorySegment error)
		{
			unloads++;
			var fields = arena.allocate(16, 8);
			fields.set(java.lang.foreign.ValueLayout.JAVA_INT, 0, 1);
			fields.set(java.lang.foreign.ValueLayout.JAVA_INT, 4, code);
			fields.set(java.lang.foreign.ValueLayout.ADDRESS, 8, arena.allocateFrom("Script is destroyed"));
			error.set(java.lang.foreign.ValueLayout.ADDRESS, 0, fields);
		}

		private java.lang.foreign.MemorySegment quark(int ignored) { return arena.allocateFrom(domain); }
		private void unref(java.lang.foreign.MemorySegment ignored) { unrefs++; if (unrefFails) throw new IllegalStateException("native reference release uncertain"); }
		private void freeError(java.lang.foreign.MemorySegment ignored) { freedErrors++; }
		@Override public void close() { arena.close(); }
	}

}
