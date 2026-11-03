package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxAttempt;
import com.holdmyspot.codexunleashed.scheduler.LinuxAttempts;
import com.holdmyspot.codexunleashed.scheduler.LinuxCargoTokens;
import java.time.Duration;
import java.util.List;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Checks the resource-free control bound and the ordering of attempt owner cleanup. */
public final class LinuxAttemptTest
{
	/** Creates isolated configuration tests. */
	public LinuxAttemptTest() {}

	/** Control bounds retain overflow and nonpositive distinctions before any acquisition. */
	@Test
	public void validatesControlBound()
	{
		requireThat(LinuxAttempts.checkedTimeout(Duration.ofSeconds(30)), "bounded").isEqualTo(Duration.ofSeconds(30), "expected");
		requireException(IllegalArgumentException.class, () -> LinuxAttempts.checkedTimeout(Duration.ZERO));
		requireException(IllegalArgumentException.class, () -> LinuxAttempts.checkedTimeout(Duration.ofSeconds(-1)));
		requireException(IllegalArgumentException.class, () -> LinuxAttempts.checkedTimeout(Duration.ofSeconds(Long.MAX_VALUE)));
	}

	/**
	 * A retired logical token owner's transfer fault remains fatal while native collection still completes.
	 * The returned fault lets the attempt finish authentic supervisor cleanup before reporting its retained failure.
	 */
	@Test
	public void collectsAfterRetiredTokenFault()
	{
		var events = new java.util.ArrayList<String>();
		java.io.IOException fault = new java.io.IOException("Retired token owner has uncertain FIFO state");
		Throwable observed;
		boolean returned = false;
		try
		{
			observed = LinuxAttempt.closeInstrumentationOwners(() -> events.add("instrumentation"), () ->
			{
				events.add("tokens-retired");
				throw fault;
			}, () -> events.add("collect"));
			returned = true;
		}
		catch (java.io.IOException cause)
		{
			observed = cause;
		}
		requireThat(events, "releaseAndCollectionOrder")
			.isEqualTo(List.of("instrumentation", "tokens-retired", "collect"), "completeCleanupOrder");
		requireThat(returned, "logicalFaultReturnedAfterCollection").isTrue();
		requireThat(observed, "retainedTokenFault").isReferenceEqualTo(fault, "originalFailure");
	}

	/**
	 * Collection follows actual logical token retirement even when its transport throws an unchecked failure.
	 * The in-memory transport models an uncertain FIFO return; collection must see the attempt owner retired.
	 * @throws java.io.IOException if the controlled borrow or ordinary owner closure fails
	 */
	@Test
	public void collectsAfterUncheckedTokenRetirement() throws java.io.IOException
	{
		for (Throwable fault : List.of(new IllegalStateException("Uncertain token return"),
			new AssertionError("Fatal token return")))
		{
			var events = new java.util.ArrayList<String>();
			try (var ledger = new LinuxCargoTokens(new LinuxCargoTokens.Transfer()
			{
				@Override
				public java.util.OptionalInt tryRead()
				{
					return java.util.OptionalInt.of(0xa5);
				}

				@Override
				public void write(byte token)
				{
					events.add("return-attempted");
					switch (fault)
					{
						case RuntimeException cause -> throw cause;
						case Error cause -> throw cause;
						default -> throw new IllegalStateException("Unsupported test exception", fault);
					}
				}
			}); var attempt = ledger.retainAttempt())
			{
				requireThat(attempt.tryBorrow().getAsInt(), "borrowedByte").isEqualTo(0xa5, "opaqueByte");
				Throwable observed;
				boolean returned = false;
				try
				{
					observed = LinuxAttempt.closeInstrumentationOwners(() -> events.add("instrumentation"),
						attempt::close, () ->
						{
							requireThat(attempt.isClosed(), "retiredBeforeCollection").isTrue();
							ledger.close();
							events.add("collect");
						});
					returned = true;
				}
				catch (java.io.IOException | RuntimeException | Error cause)
				{
					observed = cause;
				}

				requireThat(events, "retirementAndCollectionOrder")
					.isEqualTo(List.of("instrumentation", "return-attempted", "collect"), "completeCleanup");
				requireThat(returned, "faultDeferredUntilCollection").isTrue();
				requireThat(observed, "retainedUncheckedFault").isReferenceEqualTo(fault, "originalFailure");
			}
		}
	}

	/**
	 * Preserves both failures if collection also fails, retaining unchecked collection failures as their own type.
	 * A checked collection failure cannot erase the earlier token fault or imply that collection completed.
	 */
	@Test
	public void preservesTokenAndCollectionFaults()
	{
		for (Throwable collection : List.of(new java.io.IOException("COLLECT acknowledgement failed"),
			new IllegalStateException("Collection state failed"), new AssertionError("Fatal collection failure")))
		{
			var events = new java.util.ArrayList<String>();
			java.io.IOException token = new java.io.IOException("Retired token transfer failed");
			Throwable observed = requireException(Throwable.class, () -> LinuxAttempt.closeInstrumentationOwners(
				() -> events.add("instrumentation"), () ->
				{
					events.add("tokens-retired");
					throw token;
				}, () ->
				{
					events.add("collect-attempted");
					switch (collection)
					{
						case java.io.IOException cause -> throw cause;
						case RuntimeException cause -> throw cause;
						case Error cause -> throw cause;
						default -> throw new IllegalStateException("Unsupported test exception", collection);
					}
				}));
			requireThat(events, "attemptedCleanupOrder")
				.isEqualTo(List.of("instrumentation", "tokens-retired", "collect-attempted"), "orderedCleanup");
			boolean checkedCollection = collection instanceof java.io.IOException;
			Throwable primary = checkedCollection ? token : collection;
			Throwable secondary = checkedCollection ? collection : token;
			requireThat(observed, "primaryFailure").isReferenceEqualTo(primary, "requiredPrimary");
			requireThat(java.util.Arrays.asList(observed.getSuppressed()), "retainedOtherFailure")
				.isEqualTo(List.of(secondary), "requiredSecondary");
		}
	}

	/**
	 * A failed collection preserves both unchecked retirement and collection faults without changing their identities.
	 * Checked collection failures retain the token fault as primary; unchecked collection failures remain primary.
	 */
	@Test
	public void preservesUncheckedTokenAndCollectionFaults()
	{
		for (var newToken : List.<java.util.function.Supplier<Throwable>>of(
			IllegalStateException::new, AssertionError::new))
			for (var newCollection : List.<java.util.function.Supplier<Throwable>>of(
				java.io.IOException::new, IllegalStateException::new, AssertionError::new))
			{
				Throwable token = newToken.get();
				Throwable collection = newCollection.get();
				var events = new java.util.ArrayList<String>();
				Throwable observed = requireException(Throwable.class, () -> LinuxAttempt.closeInstrumentationOwners(
					() -> events.add("instrumentation"), () ->
					{
						events.add("tokens-retired");
						switch (token)
						{
							case RuntimeException cause -> throw cause;
							case Error cause -> throw cause;
							default -> throw new IllegalStateException("Unsupported test exception", token);
						}
					}, () ->
					{
						events.add("collect-attempted");
						switch (collection)
						{
							case java.io.IOException cause -> throw cause;
							case RuntimeException cause -> throw cause;
							case Error cause -> throw cause;
							default -> throw new IllegalStateException("Unsupported test exception", collection);
						}
					}));
				boolean checkedCollection = collection instanceof java.io.IOException;
				Throwable primary = checkedCollection ? token : collection;
				Throwable secondary = checkedCollection ? collection : token;
				requireThat(events, "cleanupOrder")
					.isEqualTo(List.of("instrumentation", "tokens-retired", "collect-attempted"), "orderedCleanup");
				requireThat(observed, "primaryFailure").isReferenceEqualTo(primary, "requiredPrimary");
				requireThat(java.util.Arrays.asList(observed.getSuppressed()), "otherFailure")
					.isEqualTo(List.of(secondary), "requiredSecondary");
			}
	}

	/**
	 * Reporting the same unchecked fault from retirement and collection preserves it without self-suppression.
	 */
	@Test
	public void preservesRepeatedUncheckedFailure()
	{
		for (Throwable fault : List.of(new IllegalStateException("Repeated failure"),
			new AssertionError("Repeated fatal failure")))
		{
			LinuxAttempt.Close fail = () ->
			{
				switch (fault)
				{
					case RuntimeException cause -> throw cause;
					case Error cause -> throw cause;
					default -> throw new IllegalStateException("Unsupported test exception", fault);
				}
			};
			Throwable observed = requireException(Throwable.class,
				() -> LinuxAttempt.closeInstrumentationOwners(() -> {}, fail, fail));
			requireThat(observed, "primaryFailure").isReferenceEqualTo(fault, "originalFailure");
			requireThat(observed.getSuppressed().length, "noSelfSuppression").isEqualTo(0, "none");
		}
	}

	/**
	 * Complete attachment closure precedes token retirement and collection; unresolved attachments stop both.
	 * @throws java.io.IOException if ordinary ordered cleanup fails
	 */
	@Test
	public void requiresInstrumentationClosureBeforeTokenRetirement() throws java.io.IOException
	{
		var events = new java.util.ArrayList<String>();
		Throwable result = LinuxAttempt.closeInstrumentationOwners(() -> events.add("instrumentation"),
			() -> events.add("tokens-retired"), () -> events.add("collect"));
		requireThat(result == null, "noTokenFault").isTrue();
		requireThat(events, "successfulCleanupOrder")
			.isEqualTo(List.of("instrumentation", "tokens-retired", "collect"), "orderedCleanup");
		events.clear();
		java.io.IOException unresolved = new java.io.IOException("Native attachment still retained");
		java.io.IOException observed = requireException(java.io.IOException.class,
			() -> LinuxAttempt.closeInstrumentationOwners(() -> { throw unresolved; },
				() -> events.add("tokens-retired"), () -> events.add("collect")));
		requireThat(observed, "instrumentationFailure").isReferenceEqualTo(unresolved, "originalFailure");
		requireThat(events.isEmpty(), "noLaterCleanupWithUnresolvedAttachments").isTrue();
	}
}
