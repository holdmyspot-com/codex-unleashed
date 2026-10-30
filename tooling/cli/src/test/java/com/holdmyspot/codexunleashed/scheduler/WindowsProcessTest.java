package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

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
	 * Retains the original preparation fault and usable Job authority until actual zero, then preserves failed releases.
	 * This executes production Java ownership with modeled Kernel replies, not native Windows effects.
	 * @throws IOException if modeled terminal observation unexpectedly fails
	 */
	@Test
	public void retainsFailedPreparationOwnership() throws IOException
	{
		var kernel = new FailedPreparationKernel(true);
		try
		{
			var failure = requireException(WindowsProcess.PreparationFailure.class, () -> prepareSeparate(kernel));
			var process = failure.owner();
			Throwable original = failure.getCause();
			requireThat(kernel.created, "modeledCreateSucceeded").isTrue();
			requireThat(original, "preparationFault").isNotNull();
			Throwable nativeFault = original;
			while (nativeFault.getCause() != null) nativeFault = nativeFault.getCause();
			requireThat(nativeFault == kernel.postCreateFailure, "originalPostCreateFaultIdentity").isTrue();
			requireThat(kernel.liveHandles, "unreleasedAcquiredHandles").isEqualTo(Set.of(10L, 20L, 21L), "jobRootThread");
			requireThat(kernel.failedCloses.isEmpty(), "noDependentReleaseBeforeZero").isTrue();
			requireThat(kernel.arena.scope().isAlive(), "unresolvedPreparationNativeStorageRetained").isTrue();
			requireException(IOException.class, process::poll);
			requireException(IllegalStateException.class, process::releaseRoot);
			requireException(IllegalStateException.class, () -> process.openMember(77));
			kernel.statusFailure = false;
			kernel.active = 0;
			requireThat(process.poll().orElseThrow(), "authenticTerminalStatus").isEqualTo(
				new WindowsProcess.Result(0, false), "unchangedRootAndNoTerminationRequest");
			requireException(IOException.class, failure::close);
			requireThat(kernel.failedCloses, "oneNativeAttemptPerRelease").isEqualTo(List.of(21L, 20L, 10L), "reverseOrder");
			requireException(IOException.class, failure::close);
			requireThat(kernel.failedCloses.size(), "noAmbiguousReleaseRetry").isEqualTo(3, "originalAttempts");
			requireThat(kernel.attributeDestructions, "noAmbiguousAttributeRetry").isEqualTo(1, "originalAttempt");
			requireThat(failure.getCause() == original, "originalFaultUnchanged").isTrue();
			requireThat(kernel.arena.scope().isAlive(), "unresolvedNativeStorageRetained").isTrue();
		}
		finally { if (kernel.arena.scope().isAlive()) kernel.close(); }
	}

	/**
	 * Reconciles actual terminal state after failed termination, without repeating cancellation or uncertain releases.
	 * @throws IOException if initial preparation or terminal observation unexpectedly fails
	 */
	@Test
	public void retainsFailedDrainOwnership() throws IOException
	{
		var kernel = new FailedPreparationKernel(false);
		try
		{
			var process = prepareSeparate(kernel);
			requireException(IOException.class, process::close);
			requireThat(kernel.failedCloses.isEmpty(), "noHandleReleaseBeforeZero").isTrue();
			requireThat(process.poll().isEmpty(), "activeJobStillObservable").isTrue();
			requireThat(kernel.arena.scope().isAlive(), "unresolvedDrainNativeStorageRetained").isTrue();
			requireException(IllegalStateException.class, process::terminate);
			kernel.active = 0;
			requireThat(process.poll().orElseThrow(), "actualCollectedStatus").isEqualTo(
				new WindowsProcess.Result(0, true), "nativeRootAndExplicitRequest");
			requireException(IOException.class, process::close);
			requireThat(kernel.failedCloses, "failedNativeReleases").isEqualTo(List.of(21L, 20L, 10L), "reverseOrder");
			requireException(IOException.class, process::close);
			requireThat(kernel.failedCloses.size(), "noReleaseRetry").isEqualTo(3, "firstAttemptsOnly");
			requireThat(kernel.calls.stream().filter("TerminateJobObject"::equals).count(), "singleTerminationRequest")
				.isEqualTo(1L, "firstAttemptOnly");
			requireThat(kernel.arena.scope().isAlive(), "unresolvedNativeStorageRetained").isTrue();
		}
		finally { if (kernel.arena.scope().isAlive()) kernel.close(); }
	}

	/**
	 * Releases storage after later actual zero while keeping the original cleanup fault visible to its caller.
	 * @throws IOException if preparation or terminal observation unexpectedly fails
	 */
	@Test
	public void reconcilesDrainWithoutRepeatingTermination() throws IOException
	{
		var kernel = new FailedPreparationKernel(false);
		try
		{
			kernel.failLifetimeClose = false;
			var process = prepareSeparate(kernel);
			var original = requireException(IOException.class, process::close);
			kernel.active = 0;
			kernel.rootExit = 0xc0000017L;
			requireThat(process.poll().orElseThrow(), "originalUnsignedRoot").isEqualTo(
				new WindowsProcess.Result(0xc0000017L, true), "unchangedNativeStatus");
			var reconciled = requireException(IOException.class, process::close);
			requireThat(reconciled.getCause() == original.getCause(), "firstCleanupFaultRetained").isTrue();
			requireThat(kernel.liveHandles.isEmpty(), "allModeledHandlesReleased").isTrue();
			requireThat(kernel.arena.scope().isAlive(), "terminalStorageReleased").isFalse();
			requireException(IOException.class, process::close);
			requireThat(kernel.calls.stream().filter("TerminateJobObject"::equals).count(), "singleTerminationRequest")
				.isEqualTo(1L, "firstAttemptOnly");
		}
		finally { if (kernel.arena.scope().isAlive()) kernel.close(); }
	}

	/**
	 * Supplies native BOOL rejection and an invocation with an unavailable result, both of which forbid retry.
	 * @return whether a modeled handle close throws instead of returning a failed BOOL
	 */
	@DataProvider
	public Object[][] uncertainLifetimeReleases() { return new Object[][] {{false}, {true}}; }

	/**
	 * Retains failed lifetime-handle release after Job zero, including an unavailable native return.
	 * @param throwsAfterCall whether the modeled result is unavailable
	 * @throws IOException if preparation or terminal observation unexpectedly fails
	 */
	@Test(dataProvider = "uncertainLifetimeReleases")
	public void retainsUncertainLifetimeRelease(boolean throwsAfterCall) throws IOException
	{
		var kernel = new FailedPreparationKernel(false);
		try
		{
			kernel.active = 0;
			kernel.throwOnClose = throwsAfterCall;
			var process = prepareSeparate(kernel);
			requireException(IOException.class, process::close);
			requireThat(process.poll().orElseThrow(), "cachedNativeResult").isEqualTo(
				new WindowsProcess.Result(0, false), "naturalRootStatus");
			requireException(IOException.class, process::close);
			requireThat(kernel.failedCloses, "ambiguousReleasesNeverRepeated").isEqualTo(List.of(21L, 20L, 10L), "oneEach");
			requireThat(kernel.arena.scope().isAlive(), "unresolvedReleaseStorageRetained").isTrue();
		}
		finally { if (kernel.arena.scope().isAlive()) kernel.close(); }
	}

	/**
	 * Retains a temporary handle whose close failed after process creation, while closing independent terminal owners.
	 * @throws IOException if retained status observation unexpectedly fails
	 */
	@Test
	public void retainsFailedTemporaryLaunchRelease() throws IOException
	{
		var kernel = new FailedPreparationKernel(false);
		try
		{
			kernel.active = 0;
			kernel.failLifetimeClose = false;
			kernel.failTemporaryClose = true;
			var failure = requireException(WindowsProcess.PreparationFailure.class, () -> prepareSeparate(kernel));
			requireThat(kernel.liveHandles, "retainedTemporaryHandle").isEqualTo(Set.of(103L), "failedErrorDuplicate");
			requireThat(kernel.failedCloses, "onlyFailedNativeRelease").isEqualTo(List.of(103L), "oneAttempt");
			requireException(IOException.class, failure::close);
			requireThat(kernel.failedCloses.size(), "temporaryReleaseNeverRetried").isEqualTo(1, "firstAttemptOnly");
			requireThat(kernel.arena.scope().isAlive(), "temporaryNativeStorageRetained").isTrue();
		}
		finally { if (kernel.arena.scope().isAlive()) kernel.close(); }
	}

	/** Models actual Java lifecycle decisions only; no native Windows process or handle is acquired. */
	private static final class FailedPreparationKernel extends RecordingKernel
	{
		private final boolean failPreparation;
		/** Exact original fault allocated before the modeled native acquisition. */
		private final IllegalStateException postCreateFailure =
			new IllegalStateException("modeled post-CreateProcess temporary cleanup failure");
		private boolean created;
		private boolean statusFailure;
		private int active = 1;
		private long rootExit;
		private boolean failLifetimeClose = true;
		private boolean failTemporaryClose;
		private boolean throwOnClose;
		private int attributeDestructions;
		private final Set<Long> liveHandles = new HashSet<>();
		private final List<Long> failedCloses = new ArrayList<>();

		/**
		 * Chooses the post-create failure boundary without acquiring resources.
		 * @param failPreparation whether temporary attribute destruction fails
		 */
		private FailedPreparationKernel(boolean failPreparation)
		{
			this.failPreparation = failPreparation;
			statusFailure = failPreparation;
		}

		@Override
		Object call(String name, Object... arguments)
		{
			if (name.equals("CreateJobObjectW")) liveHandles.add(10L);
			if (name.equals("DeleteProcThreadAttributeList"))
			{
				++attributeDestructions;
				if (failPreparation)
				{
					calls.add(name);
					throw postCreateFailure;
				}
			}
			if (name.equals("QueryInformationJobObject"))
			{
				calls.add(name);
				((MemorySegment) arguments[2]).set(ValueLayout.JAVA_INT, 40, active);
				return statusFailure ? 0 : 1;
			}
			if (name.equals("GetExitCodeProcess"))
			{
				calls.add(name);
				((MemorySegment) arguments[1]).set(ValueLayout.JAVA_INT, 0, (int) rootExit);
				return 1;
			}
			if (name.equals("TerminateJobObject"))
			{
				calls.add(name);
				return 0;
			}
			if (name.equals("CloseHandle"))
			{
				long handle = ((MemorySegment) arguments[0]).address();
				boolean lifetime = handle == 10 || handle == 20 || handle == 21;
				if ((lifetime && failLifetimeClose) || (handle == 103 && failTemporaryClose))
				{
					calls.add(name);
					failedCloses.add(handle);
					if (throwOnClose) throw new IllegalStateException("modeled native close result unavailable");
					return 0;
				}
				liveHandles.remove(handle);
			}
			Object result = super.call(name, arguments);
			if (name.equals("CreateProcessW") && (int) result == 1)
			{
				created = true;
				liveHandles.add(20L);
				liveHandles.add(21L);
			}
			if (name.equals("DuplicateHandle") && (int) result == 1)
				liveHandles.add(((MemorySegment) arguments[3]).get(ValueLayout.ADDRESS, 0).address());
			return result;
		}
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
	 * Keeps the prepared primary thread suspended while retaining atomic job membership and its native handles.
	 * These modeled calls verify Java decisions, not Windows execution or Frida attachment.
	 * @throws IOException if the modeled launch or cleanup fails
	 */
	@Test
	public void preparesRootWithoutReleasingIt() throws IOException
	{
		var kernel = new RecordingKernel();
		try (var process = WindowsProcess.launchPrepared(List.of("compiler"), Path.of("."), Map.of(),
			MemorySegment.ofAddress(31), MemorySegment.ofAddress(32), MemorySegment.ofAddress(33), kernel))
		{
			requireThat(kernel.creationFlags, "creationFlags").isEqualTo(0x80404, "preparedFlags");
			requireThat(kernel.jobList, "atomicMembership").isEqualTo(List.of(10L), "ownedJob");
			requireThat(kernel.calls.contains("ResumeThread"), "releasedBeforeRequest").isFalse();
			requireThat(kernel.closedHandles.contains(21L), "primaryThreadReleased").isFalse();
		}
		requireThat(kernel.closedHandles, "releaseOrder")
			.isEqualTo(List.of(103L, 102L, 101L, 21L, 20L, 10L), "ownedHandles");
	}

	/**
	 * Releases only acquired resources when a prepared launch fails before it returns an owner.
	 * @param duplicateFailure one-based duplicate failure, or zero
	 * @param createFailure indicates native process creation failure
	 * @param expectedClosed expected owned handles released in reverse order
	 */
	@Test(dataProvider = "launchFailures")
	public void releasesPreparedLaunchFailure(int duplicateFailure, boolean createFailure,
		List<Long> expectedClosed)
	{
		var kernel = new RecordingKernel();
		kernel.duplicateFailure = duplicateFailure;
		kernel.createFailure = createFailure;
		requireException(IOException.class, () -> prepareSeparate(kernel));
		requireThat(kernel.closedHandles, "releasedPreparedResources").isEqualTo(expectedClosed, "acquiredHandles");
		requireThat(kernel.arena.scope().isAlive(), "releasedNativeStorage").isFalse();
		requireThat(kernel.resumedThreads.isEmpty(), "releaseAfterFailedPreparation").isTrue();
	}

	/**
	 * Releases only the retained prepared primary thread and rejects a second native release.
	 * @throws IOException if a modeled native operation fails
	 */
	@Test
	public void releasesPreparedRootOnlyOnce() throws IOException
	{
		var kernel = new RecordingKernel();
		try (var process = prepareSeparate(kernel))
		{
			process.releaseRoot();
			requireException(IllegalStateException.class, process::releaseRoot);
			requireThat(kernel.resumedThreads, "resumedThreads").isEqualTo(List.of(21L), "primaryThread");
		}
		requireThat(kernel.closedHandles.stream().filter(handle -> handle == 21L).count(), "primaryThreadCloses")
			.isEqualTo(1L, "oneClose");
	}

	/**
	 * Supplies exact native results that cannot prove release from one owned suspension.
	 * @return previous suspend-count bits, including native failure
	 */
	@DataProvider
	public Object[][] failedReleaseResults()
	{
		return new Object[][] {{0}, {2}, {-1}, {0x8000_0000}};
	}

	/**
	 * Retains failed and ambiguous release state without retrying a possibly effective native operation.
	 * @param previous exact native return bits
	 * @throws IOException if launch or cleanup fails
	 */
	@Test(dataProvider = "failedReleaseResults")
	public void retainsFailedRootRelease(int previous) throws IOException
	{
		var kernel = new RecordingKernel();
		kernel.resumeResult = previous;
		try (var process = prepareSeparate(kernel))
		{
			var failure = requireException(IOException.class, process::releaseRoot);
			var repeat = requireException(IllegalStateException.class, process::releaseRoot);
			requireThat(repeat.getCause(), "retainedFailure").isEqualTo(failure, "original");
			requireThat(kernel.resumedThreads.size(), "nativeReleaseRequests").isEqualTo(1, "oneRequest");
			String diagnostic = previous == -1 ? "122" : Integer.toUnsignedString(previous);
			requireThat(failure.getMessage().contains(diagnostic), "nativeStatusPreserved").isTrue();
		}
	}

	/**
	 * Consumes release authority even when an invocation throws without a trustworthy native result.
	 * @throws IOException if launch or cleanup fails
	 */
	@Test
	public void retainsAmbiguousRootRelease() throws IOException
	{
		var kernel = new RecordingKernel();
		kernel.resumeFailure = new IllegalArgumentException("native result unavailable");
		try (var process = prepareSeparate(kernel))
		{
			requireException(IllegalArgumentException.class, process::releaseRoot);
			var repeat = requireException(IllegalStateException.class, process::releaseRoot);
			requireThat(repeat.getCause(), "retainedInvocationFailure").isEqualTo(kernel.resumeFailure, "original");
			requireThat(kernel.resumedThreads.size(), "nativeReleaseRequests").isEqualTo(1, "oneRequest");
		}
	}

	/**
	 * Denies release for legacy starts, terminated prepared roots and closed owners before touching the primary thread.
	 * @throws IOException if launch or cleanup fails
	 */
	@Test
	public void rejectsReleaseWithoutAuthority() throws IOException
	{
		var legacy = new RecordingKernel();
		try (var process = launchSeparate(legacy))
		{
			requireException(IllegalStateException.class, process::releaseRoot);
			requireThat(legacy.resumedThreads.isEmpty(), "legacyReleaseRequests").isTrue();
		}
		var stopped = new RecordingKernel();
		var process = prepareSeparate(stopped);
		process.terminate();
		requireException(IllegalStateException.class, process::releaseRoot);
		process.close();
		requireException(IllegalStateException.class, process::releaseRoot);
		requireThat(stopped.resumedThreads.isEmpty(), "stoppingReleaseRequests").isTrue();
	}

	/**
	 * Retains a native identity across checks and never reopens its PID after the retained process exits.
	 * @throws IOException if a valid modeled native operation fails
	 */
	@Test
	public void retainsVerifiedMemberIdentity() throws IOException
	{
		var kernel = new MemberKernel();
		try (var process = prepareSeparate(kernel))
		{
			try (var member = process.openMember(0xffff_ffffL))
			{
				requireThat(member.pid(), "memberPid").isEqualTo(0xffff_ffffL, "unsignedPid");
				member.requireLive();
				kernel.memberWait = 0;
				requireException(IOException.class, member::requireLive);
				requireThat(kernel.openedMembers, "openedProcessIds").isEqualTo(List.of(0xffff_ffffL), "oneIdentity");
			}
			requireThat(kernel.memberCloses.size(), "memberHandleCloses").isEqualTo(1, "oneClose");
		}
	}

	/**
	 * Supplies acquisition, membership and liveness failures at the owned identity boundary.
	 * @return exact failing native boundary
	 */
	@DataProvider
	public Object[][] memberFaults()
	{
		return new Object[][] {{"open"}, {"membershipCall"}, {"outsideJob"}, {"exited"}, {"waitFailure"},
			{"unexpectedWait"}};
	}

	/**
	 * Rejects unverifiable members and closes an acquired query handle without affecting unrelated native work.
	 * @param fault failed native boundary
	 * @throws IOException if owner creation or cleanup fails
	 */
	@Test(dataProvider = "memberFaults")
	public void rejectsUnverifiedMemberIdentity(String fault) throws IOException
	{
		var kernel = new MemberKernel();
		kernel.fault = fault;
		try (var process = prepareSeparate(kernel))
		{
			requireException(IOException.class, () -> process.openMember(77));
			requireThat(kernel.memberCloses.size(), "releasedQueries")
				.isEqualTo(fault.equals("open") ? 0 : 1, "acquiredQueries");
			requireThat(kernel.calls.contains("TerminateJobObject"), "memberFailureTermination").isFalse();
		}
	}

	/**
	 * Rechecks the same handle's membership and lets the enclosing owner close a still-retained identity exactly once.
	 * @throws IOException if valid modeled operations fail
	 */
	@Test
	public void rechecksAndClosesRetainedMember() throws IOException
	{
		var kernel = new MemberKernel();
		var process = prepareSeparate(kernel);
		var member = process.openMember(77);
		kernel.fault = "outsideJob";
		requireException(IOException.class, member::requireLive);
		process.close();
		requireException(IllegalStateException.class, member::requireLive);
		member.close();
		requireThat(kernel.memberCloses, "memberHandleCloses").isEqualTo(List.of(1077L), "oneClose");
		requireThat(kernel.closedHandles, "releaseOrder")
			.isEqualTo(List.of(103L, 102L, 101L, 1077L, 21L, 20L, 10L), "ownedHandles");
	}

	/**
	 * Retains an ambiguous query-handle release, prevents further acquisition and reports it during owner cleanup.
	 * @throws IOException if launch or member acquisition fails
	 */
	@Test
	public void retainsFailedMemberCleanup() throws IOException
	{
		var kernel = new MemberKernel();
		var process = prepareSeparate(kernel);
		var member = process.openMember(77);
		kernel.fault = "close";
		requireException(IOException.class, member::close);
		member.close();
		requireException(IOException.class, () -> process.openMember(99));
		requireException(IOException.class, process::close);
		requireThat(kernel.openedMembers, "acquisitionsBeforeFailure").isEqualTo(List.of(77L), "firstOnly");
		requireThat(kernel.memberCloses, "uncertainCloseAttempts").isEqualTo(List.of(1077L), "oneAttempt");
		requireThat(kernel.arena.scope().isAlive(), "unresolvedMemberStorageRetained").isTrue();
		kernel.close();
	}

	/**
	 * Preserves an invocation failure after an uncertain member close and never acquires or closes again silently.
	 * @throws IOException if launch or member acquisition fails
	 */
	@Test
	public void retainsAmbiguousMemberCleanup() throws IOException
	{
		var kernel = new MemberKernel();
		var process = prepareSeparate(kernel);
		var member = process.openMember(77);
		kernel.fault = "ambiguousClose";
		requireException(IllegalArgumentException.class, member::close);
		member.close();
		requireException(IOException.class, process::close);
		requireThat(kernel.memberCloses, "uncertainCloseAttempts").isEqualTo(List.of(1077L), "oneAttempt");
	}

	/**
	 * Preserves both failed membership verification and failed partial-acquisition cleanup.
	 * @throws IOException if the prepared launch fails
	 */
	@Test
	public void preservesMemberVerificationAndCleanupFailures() throws IOException
	{
		var kernel = new MemberKernel();
		var process = prepareSeparate(kernel);
		kernel.fault = "outsideAndClose";
		var failure = requireException(IOException.class, () -> process.openMember(77));
		requireThat(failure.getSuppressed().length, "cleanupFailureCount").isEqualTo(1, "retainedCleanup");
		requireException(IOException.class, process::close);
		requireThat(kernel.memberCloses, "uncertainCloseAttempts").isEqualTo(List.of(1077L), "oneAttempt");
	}

	/**
	 * Rejects invalid process identifiers before acquiring any native query handle.
	 * @throws IOException if launch or cleanup fails
	 */
	@Test
	public void validatesMemberIdsBeforeAcquisition() throws IOException
	{
		var kernel = new MemberKernel();
		try (var process = prepareSeparate(kernel))
		{
			for (long pid : new long[] {-1, 0, 0x1_0000_0000L})
				requireException(IllegalArgumentException.class, () -> process.openMember(pid));
			requireThat(kernel.openedMembers.isEmpty(), "invalidPidAcquisitions").isTrue();
		}
	}

	/**
	 * Creates a prepared owner with separately borrowed stream handles through the existing native fixture boundary.
	 * @param kernel native replies and request recorder
	 * @return suspended workload owner
	 * @throws IOException if modeled launch fails
	 */
	private WindowsProcess prepareSeparate(RecordingKernel kernel) throws IOException
	{
		return WindowsProcess.launchPrepared(List.of("compiler"), Path.of("."), Map.of(),
			MemorySegment.ofAddress(31), MemorySegment.ofAddress(32), MemorySegment.ofAddress(33), kernel);
	}

	/**
	 * Models only process-query identity and release decisions; native PID reuse and ABI behavior remain unproven here.
	 */
	private static final class MemberKernel extends RecordingKernel
	{
		/** Process IDs for which a query was requested, retaining unsigned DWORD values. */
		private final List<Long> openedMembers = new ArrayList<>();
		/** Member query-handle close requests, including failed requests. */
		private final List<Long> memberCloses = new ArrayList<>();
		/** Selected native boundary fault, or empty for valid replies. */
		private String fault = "";
		/** Native member wait result, independent of the root's terminal wait. */
		private int memberWait = 258;

		@Override
		Object call(String name, Object... arguments)
		{
			if (name.equals("OpenProcess"))
			{
				calls.add(name);
				requireThat(arguments[0], "minimalQueryRights").isEqualTo(0x101000, "queryAndSynchronize");
				requireThat(arguments[1], "inheritedQuery").isEqualTo(0, "notInheritable");
				long pid = Integer.toUnsignedLong((int) arguments[2]);
				openedMembers.add(pid);
				return fault.equals("open") ? MemorySegment.NULL : MemorySegment.ofAddress(pid + 1000);
			}
			if (name.equals("IsProcessInJob"))
			{
				calls.add(name);
				requireThat(((MemorySegment) arguments[1]).address(), "membershipJob").isEqualTo(10L, "ownedJob");
				boolean outsideJob = fault.equals("outsideJob") || fault.equals("outsideAndClose");
				((MemorySegment) arguments[2]).set(ValueLayout.JAVA_INT, 0, outsideJob ? 0 : 1);
				return fault.equals("membershipCall") ? 0 : 1;
			}
			if (name.equals("WaitForSingleObject") && ((MemorySegment) arguments[0]).address() >= 1000)
			{
				calls.add(name);
				return switch (fault)
				{
					case "exited" -> 0;
					case "waitFailure" -> -1;
					case "unexpectedWait" -> 128;
					default -> memberWait;
				};
			}
			if (name.equals("CloseHandle") && ((MemorySegment) arguments[0]).address() >= 1000)
			{
				memberCloses.add(((MemorySegment) arguments[0]).address());
				if (fault.equals("ambiguousClose"))
					throw new IllegalArgumentException("native close result unavailable");
				boolean closeFails = fault.equals("close") || fault.equals("outsideAndClose");
				if (closeFails)
				{
					calls.add(name);
					return 0;
				}
			}
			return super.call(name, arguments);
		}
	}

	/**
	 * Sums the owned job's root and detached descendant, excluding a process absent from its membership list.
	 * The native-access fixture models documented Win32 replies, rather than proving native membership or ABI layouts.
	 * @throws IOException if a valid modeled observation fails
	 */
	@Test
	public void sumsCompleteOwnedJobResidency() throws IOException
	{
		var kernel = new ResidencyKernel();
		try (var process = launchSeparate(kernel))
		{
			kernel.sampling = true;
			try
			{
				requireThat(process.readResidentBytes(), "wholeJobBytes").isEqualTo(30L, "expected");
				requireThat(kernel.opened, "ownedMembers").isEqualTo(List.of(42L, 77L), "expected");
				requireThat(kernel.queryCloses, "queryReleases").isEqualTo(List.of(1077L, 1042L), "expected");
				requireThat(kernel.closedHandles.contains(10L), "jobRemainsOwned").isFalse();
			}
			finally { kernel.sampling = false; }
		}
	}

	/**
	 * Supplies failed native calls, inconsistent membership and unusable counters that must not become partial totals.
	 * @return one fault introduced at an observation boundary
	 */
	@DataProvider
	public Object[][] residencyFaults()
	{
		return new Object[][] {
			{"accounting"}, {"list"}, {"truncated"}, {"length"}, {"duplicate"}, {"zeroPid"},
			{"widePid"}, {"open"}, {"membershipCall"}, {"membershipFalse"}, {"exited"}, {"wait"}, {"waitUnknown"},
			{"memory"}, {"unsignedMemory"}, {"sumOverflow"}, {"changedSet"}, {"changedBirths"},
			{"changedActive"}, {"recheckExit"}, {"recheckMembership"}, {"resizeExhausted"}, {"oversize"}
		};
	}

	/**
	 * Rejects an incomplete sample while closing every query handle acquired before the fault.
	 * @param fault failing native boundary or inconsistent reply
	 * @throws IOException if launch or owner cleanup fails
	 */
	@Test(dataProvider = "residencyFaults")
	public void rejectsIncompleteResidency(String fault) throws IOException
	{
		var kernel = new ResidencyKernel();
		kernel.fault = fault;
		try (var process = launchSeparate(kernel))
		{
			kernel.sampling = true;
			try
			{
				requireException(IOException.class, process::readResidentBytes);
				requireThat(new HashSet<>(kernel.queryCloses), "releasedQueryHandles")
					.isEqualTo(new HashSet<>(kernel.opened.stream().map(pid -> pid + 1000).toList()), "acquiredHandles");
				requireThat(kernel.calls.contains("TerminateJobObject"), "noSamplingTermination").isFalse();
			}
			finally { kernel.sampling = false; }
		}
	}

	/**
	 * Distinguishes successful zero working sets and a successfully observed empty job from failed measurements.
	 * @throws IOException if a valid observation fails
	 */
	@Test
	public void acceptsOnlyObservedZeroResidency() throws IOException
	{
		for (boolean empty : List.of(false, true))
		{
			var kernel = new ResidencyKernel();
			kernel.members = empty ? List.of() : List.of(77L);
			kernel.bytes.put(77L, 0L);
			try (var process = launchSeparate(kernel))
			{
				kernel.sampling = true;
				try { requireThat(process.readResidentBytes(), "observedZero").isEqualTo(0L, "expected"); }
				finally { kernel.sampling = false; }
			}
		}
	}

	/**
	 * Resizes a partial job-list reply, then samples the complete membership without retaining query handles across reads.
	 * @throws IOException if a valid observation fails
	 */
	@Test
	public void resizesAndReleasesEverySample() throws IOException
	{
		var kernel = new ResidencyKernel();
		kernel.fault = "resizeOnce";
		try (var process = launchSeparate(kernel))
		{
			kernel.sampling = true;
			try
			{
				for (int sample = 0; sample < 3; sample++)
					requireThat(process.readResidentBytes(), "wholeJobBytes").isEqualTo(30L, "expected");
				requireThat(kernel.queryCloses.size(), "closedQueries").isEqualTo(6, "expected");
			}
			finally { kernel.sampling = false; }
		}
		requireException(IllegalStateException.class, () -> {
			try (var process = launchSeparate(new ResidencyKernel()))
			{
				process.close();
				process.readResidentBytes();
			}
		});
	}

	/**
	 * Reports an unresolved query-handle release, prevents another sample, and preserves both read and cleanup failures.
	 * @throws IOException if launch fails
	 */
	@Test
	public void retainsFailedQueryCleanup() throws IOException
	{
		for (boolean memoryFails : List.of(false, true))
		{
			var kernel = new ResidencyKernel();
			kernel.fault = memoryFails ? "memory" : "";
			kernel.failedClose.add(1042L);
			var process = launchSeparate(kernel);
			kernel.sampling = true;
			try
			{
				IOException failure = requireException(IOException.class, process::readResidentBytes);
				if (memoryFails)
					requireThat(failure.getSuppressed().length, "secondaryCleanupFailure").isEqualTo(1, "expected");
				int calls = kernel.calls.size();
				requireException(IOException.class, process::readResidentBytes);
				requireThat(kernel.calls.size(), "noFurtherNativeSample").isEqualTo(calls, "before");
			}
			finally
			{
				kernel.sampling = false;
				requireException(IOException.class, process::close);
			}
			requireThat(kernel.queryCloses.stream().filter(handle -> handle == 1042L).count(), "oneReleaseRequest")
				.isEqualTo(1L, "expected");
			requireThat(kernel.arena.scope().isAlive(), "unresolvedQueryStorageRetained").isTrue();
			kernel.close();
		}
	}

	/**
	 * Trusted native churn is retryable only after every query handle releases successfully.
	 * @throws IOException if a modeled owner cannot launch or close
	 */
	@Test
	public void distinguishesTrustedChurnFromUncertainty() throws IOException
	{
		var retryable = Set.of("exited", "recheckExit", "changedSet", "changedBirths", "changedActive");
		for (Object[] row : residencyFaults())
		{
			String fault = (String) row[0];
			var kernel = new ResidencyKernel();
			kernel.fault = fault;
			try (var process = launchSeparate(kernel))
			{
				kernel.sampling = true;
				try
				{
					var failure = requireException(IOException.class, process::readResidentBytes);
					requireThat(failure instanceof WindowsProcess.MembershipChanged, "onlyTrustedChurn:" + fault)
						.isEqualTo(retryable.contains(fault));
				}
				finally { kernel.sampling = false; }
			}
		}
	}

	/**
	 * Failed query cleanup defeats a primary trusted-churn marker and remains sticky.
	 * @throws IOException if a modeled owner cannot launch
	 */
	@Test
	public void cleanupFailureDefeatsTrustedChurn() throws IOException
	{
		var kernel = new ResidencyKernel();
		kernel.fault = "recheckExit";
		kernel.failedClose.add(1042L);
		var process = launchSeparate(kernel);
		kernel.sampling = true;
		try
		{
			var failure = requireException(IOException.class, process::readResidentBytes);
			requireThat(failure instanceof WindowsProcess.MembershipChanged, "cleanupDeniesRetry").isFalse();
			requireThat(failure.getCause(), "retainedChurnDiagnostic").isInstanceOf(WindowsProcess.MembershipChanged.class);
			requireThat(failure.getCause().getSuppressed().length, "retainedCleanupDiagnostic").isEqualTo(1);
			int requests = kernel.calls.size();
			var later = requireException(IOException.class, process::readResidentBytes);
			requireThat(later instanceof WindowsProcess.MembershipChanged, "stickyFailureDeniesRetry").isFalse();
			requireThat(kernel.calls.size(), "noFurtherAcquisition").isEqualTo(requests);
		}
		finally
		{
			kernel.sampling = false;
			requireException(IOException.class, process::close);
			kernel.close();
		}
	}

	/**
	 * Models job-list/accounting, membership and memory replies documented by Microsoft Win32 APIs.
	 * Process handles are local identity tokens; the fixture does not establish native handle behavior or layout validity.
	 */
	private static final class ResidencyKernel extends RecordingKernel
	{
		/** Indicates whether requests belong to the sampler rather than owner launch/drain. */
		private boolean sampling = false;
		/** Boundary fault selected by this test, or empty for valid replies. */
		private String fault = "";
		/** Complete members modeled for this owned Job Object; no parent PID relationships are supplied. */
		private List<Long> members = List.of(42L, 77L);
		/** Current working-set bytes, including an unrelated process that must not be queried. */
		private final Map<Long, Long> bytes = new HashMap<>(Map.of(42L, 10L, 77L, 20L, 99L, 999L));
		/** Successfully opened process identities in request order. */
		private final List<Long> opened = new ArrayList<>();
		/** Query-handle release requests, including failed releases. */
		private final List<Long> queryCloses = new ArrayList<>();
		/** Query-handle addresses whose release fails. */
		private final Set<Long> failedClose = new HashSet<>();
		/** Job-list query count for resize and final recheck scenarios. */
		private int lists = 0;
		/** Accounting query count for the birth/active-count recheck. */
		private int accounts = 0;
		/** Membership-query count for invalidation during final per-handle recheck. */
		private int memberships = 0;
		/** Wait-query count for exit during final per-handle recheck. */
		private int waits = 0;

		/**
		 * Creates independently owned native storage for one in-memory scenario.
		 */
		private ResidencyKernel() { }

		@Override
		Object call(String name, Object... arguments)
		{
			if (!sampling)
				return super.call(name, arguments);
			calls.add(name);
			return switch (name)
			{
				case "QueryInformationJobObject" -> queryJob(arguments);
				case "OpenProcess" -> {
					requireThat(arguments[0], "minimalQueryRights").isEqualTo(0x101000, "expected");
					requireThat(arguments[1], "nonInheritable").isEqualTo(0, "expected");
					long pid = Integer.toUnsignedLong((int) arguments[2]);
					if (fault.equals("open") && pid == 77)
						yield MemorySegment.NULL;
					opened.add(pid);
					yield MemorySegment.ofAddress(pid + 1000);
				}
				case "IsProcessInJob" -> {
					++memberships;
					if (fault.equals("membershipCall")) yield 0;
					boolean leavesJob = fault.equals("membershipFalse") ||
						(fault.equals("recheckMembership") && memberships > members.size());
					((MemorySegment) arguments[2]).set(ValueLayout.JAVA_INT, 0, leavesJob ? 0 : 1);
					requireThat(((MemorySegment) arguments[1]).address(), "authoritativeJob").isEqualTo(10L, "expected");
					yield 1;
				}
				case "WaitForSingleObject" -> {
					++waits;
					yield switch (fault)
					{
						case "exited" -> 0;
						case "wait" -> -1;
						case "waitUnknown" -> 128;
						case "recheckExit" -> waits > members.size() ? 0 : 258;
						default -> 258;
					};
				}
				case "K32GetProcessMemoryInfo" -> {
					if (fault.equals("memory")) yield 0;
					long pid = ((MemorySegment) arguments[0]).address() - 1000;
					long value = switch (fault)
					{
						case "unsignedMemory" -> -1;
						case "sumOverflow" -> Long.MAX_VALUE;
						default -> bytes.get(pid);
					};
					((MemorySegment) arguments[1]).set(ValueLayout.JAVA_LONG, 16, value);
					yield 1;
				}
				case "CloseHandle" -> {
					long handle = ((MemorySegment) arguments[0]).address();
					queryCloses.add(handle);
					yield failedClose.contains(handle) ? 0 : 1;
				}
				default -> throw new AssertionError("Unexpected sampling request: " + name);
			};
		}

		/**
		 * Supplies accounting and a complete or deliberately malformed variable-length list.
		 * @param arguments modeled QueryInformationJobObject arguments
		 * @return Win32 BOOL result
		 */
		private int queryJob(Object[] arguments)
		{
			requireThat(((MemorySegment) arguments[0]).address(), "ownedJob").isEqualTo(10L, "expected");
			var output = (MemorySegment) arguments[2];
			var returned = (MemorySegment) arguments[4];
			if ((int) arguments[1] == 1)
			{
				++accounts;
				if (fault.equals("accounting")) return 0;
				output.set(ValueLayout.JAVA_INT, 36, fault.equals("changedBirths") && accounts > 1 ? 4 : 3);
				output.set(ValueLayout.JAVA_INT, 40,
					fault.equals("changedActive") && accounts > 1 ? members.size() + 1 : members.size());
				returned.set(ValueLayout.JAVA_INT, 0, 48);
				return 1;
			}
			++lists;
			if (fault.equals("list")) return 0;
			boolean resize = fault.equals("resizeExhausted") || (fault.equals("resizeOnce") && lists == 1);
			if (resize || fault.equals("oversize"))
			{
				output.set(ValueLayout.JAVA_INT, 0, fault.equals("oversize") ? Integer.MAX_VALUE : 32);
				return 0;
			}
			List<Long> values = switch (fault)
			{
				case "duplicate" -> List.of(42L, 42L);
				case "zeroPid" -> List.of(0L, 77L);
				case "widePid" -> List.of(0x1_0000_0000L, 77L);
				case "changedSet" -> lists > 1 ? List.of(42L, 99L) : members;
				default -> members;
			};
			output.set(ValueLayout.JAVA_INT, 0, values.size());
			output.set(ValueLayout.JAVA_INT, 4, fault.equals("truncated") ? values.size() - 1 : values.size());
			for (int index = 0; index < values.size(); index++)
				output.set(ValueLayout.JAVA_LONG, 8L + index * 8L, values.get(index));
			returned.set(ValueLayout.JAVA_INT, 0, fault.equals("length") ? 8 : 8 + values.size() * 8);
			return 1;
		}

		@Override
		long lastError()
		{
			if (!sampling)
				return super.lastError();
			return switch (fault)
			{
				case "resizeOnce", "resizeExhausted", "oversize" -> 234;
				default -> 5;
			};
		}
	}

	/**
	 * Records the requests made through Kernel's existing Win32 ABI layouts. The scripted BOOL,
	 * size-query error 122 and empty-job replies exercise Java decisions only; they establish
	 * neither Windows inheritance nor Job Object containment effects.
	 */
	private static class RecordingKernel extends WindowsProcess.Kernel
	{
		/** Native operation names in invocation order. */
		protected final List<String> calls = new ArrayList<>();
		/** Borrowed handle addresses passed to DuplicateHandle, in order. */
		private final List<Long> originals = new ArrayList<>();
		/** Handle addresses passed to CloseHandle, in order. */
		protected final List<Long> closedHandles = new ArrayList<>();
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
		/** Exact native primary-thread release results, normally one previous suspension. */
		private int resumeResult = 1;
		/** Failure thrown after recording a release request, without an authoritative native return. */
		private RuntimeException resumeFailure = null;
		/** Primary-thread identities supplied to native release requests. */
		private final List<Long> resumedThreads = new ArrayList<>();

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
				case "ResumeThread" -> {
					resumedThreads.add(((MemorySegment) arguments[0]).address());
					if (resumeFailure != null)
						throw resumeFailure;
					yield resumeResult;
				}
				case "TerminateJobObject" -> 1;
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
