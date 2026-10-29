package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies exit results and native launch requests without launching Windows work.
 */
public final class WindowsProcessTest
{
	/**
	 * Creates process-owner contract tests.
	 */
	public WindowsProcessTest()
	{
	}

	/**
	 * Supplies successful, aborted and unsigned native exit statuses.
	 *
	 * @return root status and independent abortion state
	 */
	@DataProvider
	public Object[][] nativeOutcomes()
	{
		return new Object[][] {
			{0L, false},
			{0L, true},
			{125L, true},
			{0x8000_0000L, false},
			{0xffff_ffffL, false}
		};
	}

	/**
	 * Retains the full unsigned status and distinguishes abortion after root success.
	 *
	 * @param rootExit the original native root exit status
	 * @param aborted indicates whether the owner requested abortion
	 */
	@Test(dataProvider = "nativeOutcomes")
	public void preservesNativeOutcomes(long rootExit, boolean aborted)
	{
		var result = new WindowsProcess.Result(rootExit, aborted);

		assertEquals(result.rootExit(), rootExit);
		assertEquals(result.aborted(), aborted);
	}

	/**
	 * Supplies values outside the native unsigned DWORD range.
	 *
	 * @return rejected root statuses
	 */
	@DataProvider
	public Object[][] invalidStatuses()
	{
		return new Object[][] {
			{-1L},
			{0x1_0000_0000L}
		};
	}

	/**
	 * Rejects statuses that could otherwise be truncated into a successful native value.
	 *
	 * @param rootExit the out-of-range root status
	 */
	@Test(dataProvider = "invalidStatuses")
	public void rejectsOutOfRangeStatus(long rootExit)
	{
		expectThrows(IllegalArgumentException.class, () -> new WindowsProcess.Result(rootExit, false));
	}

	/**
	 * Verifies stream routing, restricted inheritance and borrowed-handle ownership.
	 * @throws IOException if launch or cleanup unexpectedly fails
	 */
	@Test
	public void duplicatesSeparateStandardHandles() throws IOException
	{
		var kernel = new RecordingKernel();
		try (var process = launchSeparate(kernel))
		{
			assertEquals(kernel.originals, List.of(31L, 32L, 33L));
			assertEquals(kernel.inherited, List.of(101L, 102L, 103L));
			assertEquals(kernel.standardHandles, List.of(101L, 102L, 103L));
			assertEquals(kernel.closedHandles, List.of(103L, 102L, 101L));
			assertFalse(kernel.calls.contains("CreateFileW"));
			assertEquals(kernel.jobList, List.of(10L));
			assertEquals(kernel.creationFlags, 0x80400);
			assertEquals(process.pid(), 42L);
		}
		assertEquals(kernel.closedHandles, List.of(103L, 102L, 101L, 21L, 20L, 10L));
		assertFalse(kernel.arena.scope().isAlive());
	}

	/**
	 * Verifies that the existing overload still supplies NUL input and merged output.
	 * @throws IOException if launch or cleanup unexpectedly fails
	 */
	@Test
	public void retainsCombinedOutputLaunch() throws IOException
	{
		var kernel = new RecordingKernel();
		try (var process = WindowsProcess.launch(List.of("compiler"), Path.of("."), Map.of(),
			MemorySegment.ofAddress(32), kernel))
		{
			assertEquals(kernel.originals, List.of(11L, 32L));
			assertEquals(kernel.inherited, List.of(101L, 102L));
			assertEquals(kernel.standardHandles, List.of(101L, 102L, 102L));
			assertEquals(kernel.closedHandles, List.of(102L, 101L, 11L));
		}
		assertFalse(kernel.closedHandles.contains(32L));
	}

	/**
	 * Supplies acquisition failures after zero, one, two or three standard-handle copies.
	 * @return failed duplication ordinal, process-creation failure and expected release order
	 */
	@DataProvider
	public Object[][] launchFailures()
	{
		return new Object[][] {
			{1, false, List.of(10L)},
			{2, false, List.of(101L, 10L)},
			{3, false, List.of(102L, 101L, 10L)},
			{0, true, List.of(103L, 102L, 101L, 10L)}
		};
	}

	/**
	 * Releases only successfully acquired resources when duplication or creation fails.
	 * @param duplicateFailure duplication ordinal to fail, or zero
	 * @param createFailure indicates whether process creation fails
	 * @param closedHandles expected reverse acquisition order
	 */
	@Test(dataProvider = "launchFailures")
	public void releasesOwnedHandlesOnLaunchFailure(int duplicateFailure, boolean createFailure,
		List<Long> closedHandles)
	{
		var kernel = new RecordingKernel();
		kernel.duplicateFailure = duplicateFailure;
		kernel.createFailure = createFailure;
		expectThrows(IOException.class, () -> {
			try (var process = launchSeparate(kernel)) { }
		});
		assertEquals(kernel.closedHandles, closedHandles);
		assertFalse(kernel.arena.scope().isAlive());
	}

	/**
	 * Supplies invalid handles independently for each standard stream.
	 * @return standard-stream position and invalid handle
	 */
	@DataProvider
	public Object[][] invalidStandardHandles()
	{
		var closedArena = Arena.ofConfined();
		var closedHandle = closedArena.allocate(1);
		closedArena.close();
		var cases = new ArrayList<Object[]>();
		for (int stream = 0; stream < 3; stream++)
			for (var handle : new MemorySegment[] {null, MemorySegment.NULL,
				MemorySegment.ofAddress(-1), MemorySegment.ofArray(new byte[1]), closedHandle})
				cases.add(new Object[] {stream, handle});
		return cases.toArray(Object[][]::new);
	}

	/**
	 * Rejects invalid standard handles before native access, through both entry points.
	 * @param stream position of the invalid standard handle
	 * @param handle invalid standard handle
	 */
	@Test(dataProvider = "invalidStandardHandles")
	public void rejectsInvalidStandardHandlesBeforeAcquisition(int stream, MemorySegment handle)
	{
		var handles = new MemorySegment[] {MemorySegment.ofAddress(31), MemorySegment.ofAddress(32),
			MemorySegment.ofAddress(33)};
		handles[stream] = handle;
		Class<? extends RuntimeException> failure = handle == null ? NullPointerException.class :
			IllegalArgumentException.class;
		var kernel = new RecordingKernel();
		expectThrows(failure, () -> {
			try (var process = WindowsProcess.launch(List.of("compiler"), Path.of("."), Map.of(),
				handles[0], handles[1], handles[2], kernel)) { }
		});
		assertTrue(kernel.calls.isEmpty(), "Validation must precede native acquisition");
		assertFalse(kernel.arena.scope().isAlive());
		expectThrows(failure, () -> WindowsProcess.start(
			List.of(Path.of(System.getProperty("java.home"), "release").toString()),
			Path.of(System.getProperty("java.home")), Map.of(), handles[0], handles[1], handles[2]));
	}

	/**
	 * Requests a separate-stream launch with three distinguishable borrowed handles.
	 * @param kernel native request recorder owned by the returned process or failed launch
	 * @return the process owner
	 * @throws IOException if the scripted native operation fails
	 */
	private WindowsProcess launchSeparate(RecordingKernel kernel) throws IOException
	{
		return WindowsProcess.launch(List.of("compiler"), Path.of("."), Map.of(),
			MemorySegment.ofAddress(31), MemorySegment.ofAddress(32), MemorySegment.ofAddress(33), kernel);
	}

	/**
	 * Records the requests made through Kernel's existing Win32 ABI layouts. The scripted BOOL,
	 * size-query error 122 and empty-job replies exercise Java decisions only; they establish
	 * neither Windows inheritance nor Job Object containment effects.
	 */
	private static final class RecordingKernel extends WindowsProcess.Kernel
	{
		/** Native operation names in invocation order. */
		private final List<String> calls = new ArrayList<>();
		/** Borrowed handle addresses passed to DuplicateHandle, in order. */
		private final List<Long> originals = new ArrayList<>();
		/** Handle addresses passed to CloseHandle, in order. */
		private final List<Long> closedHandles = new ArrayList<>();
		/** Handles supplied in the process-inheritance attribute. */
		private final List<Long> inherited = new ArrayList<>();
		/** Input, output and error handles supplied in STARTUPINFOEXW. */
		private final List<Long> standardHandles = new ArrayList<>();
		/** Job handles supplied in the process-membership attribute. */
		private final List<Long> jobList = new ArrayList<>();
		/** Flags last supplied to CreateProcessW. */
		private int creationFlags;
		/** One-based duplication call to reject; zero allows all copies. */
		private int duplicateFailure;
		/** Indicates whether CreateProcessW returns a failed BOOL. */
		private boolean createFailure;

		/**
		 * Creates confined storage without loading Windows libraries.
		 */
		private RecordingKernel() { super(Arena.ofConfined()); }

		/**
		 * Records native arguments and supplies only the replies needed by the launch and drain.
		 * @param name Win32 operation
		 * @param arguments native arguments in ABI order
		 * @return scripted native result
		 */
		@Override
		Object call(String name, Object... arguments)
		{
			calls.add(name);
			return switch (name)
			{
				case "CreateJobObjectW" -> MemorySegment.ofAddress(10);
				case "CreateFileW" -> MemorySegment.ofAddress(11);
				case "GetCurrentProcess" -> MemorySegment.ofAddress(-1);
				case "DuplicateHandle" -> {
					originals.add(((MemorySegment)arguments[1]).address());
					if (originals.size() == duplicateFailure)
						yield 0;
					assertEquals(arguments[5], 1, "Copies must be inheritable");
					((MemorySegment)arguments[3]).set(ValueLayout.ADDRESS, 0,
						MemorySegment.ofAddress(100 + originals.size()));
					yield 1;
				}
				case "InitializeProcThreadAttributeList" -> {
					((MemorySegment)arguments[3]).set(ValueLayout.JAVA_LONG, 0, 64);
					yield ((MemorySegment)arguments[0]).address() == 0 ? 0 : 1;
				}
				case "UpdateProcThreadAttribute" -> {
					var target = (long)arguments[2] == 0x20002L ? inherited : jobList;
					var values = (MemorySegment)arguments[3];
					for (long offset = 0; offset < (long)arguments[4]; offset += 8)
						target.add(values.get(ValueLayout.ADDRESS, offset).address());
					yield 1;
				}
				case "CreateProcessW" -> {
					assertTrue(closedHandles.isEmpty(), "Parent copies must survive process creation");
					creationFlags = (int)arguments[5];
					assertEquals(arguments[4], 1, "Explicit handle inheritance must be enabled");
					var startup = (MemorySegment)arguments[8];
					assertEquals(startup.get(ValueLayout.JAVA_INT, 60), 0x100);
					for (long offset : new long[] {80, 88, 96})
						standardHandles.add(startup.get(ValueLayout.ADDRESS, offset).address());
					var info = (MemorySegment)arguments[9];
					info.set(ValueLayout.ADDRESS, 0, MemorySegment.ofAddress(20));
					info.set(ValueLayout.ADDRESS, 8, MemorySegment.ofAddress(21));
					info.set(ValueLayout.JAVA_INT, 16, 42);
					yield createFailure ? 0 : 1;
				}
				case "CloseHandle" -> {
					closedHandles.add(((MemorySegment)arguments[0]).address());
					yield 1;
				}
				case "QueryInformationJobObject" -> {
					((MemorySegment)arguments[2]).set(ValueLayout.JAVA_INT, 40, 0);
					yield 1;
				}
				case "WaitForSingleObject" -> 0;
				case "GetExitCodeProcess" -> {
					((MemorySegment)arguments[1]).set(ValueLayout.JAVA_INT, 0, 0);
					yield 1;
				}
				case "DeleteProcThreadAttributeList" -> null;
				case "SetInformationJobObject" -> 1;
				default -> throw new AssertionError("Unscripted native operation: " + name);
			};
		}

		/**
		 * Supplies the insufficient-buffer error required by the attribute-size query.
		 * @return the native insufficient-buffer error code
		 */
		@Override
		long lastError() { return 122; }
	}
}
