package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises attempt-level byte accounting and decisions after uncertain transport effects.
 * The controlled transport supplies the Transfer contract's before-effect and after-effect failures;
 * these checks establish ledger policy, not actual native syscall failure or process termination.
 */
public final class LinuxCargoTokensTest
{
	/**
	 * Creates isolated ledger policy checks.
	 */
	public LinuxCargoTokensTest()
	{
	}

	/**
	 * Preserves two distinct opaque values, including the unsigned high-bit value.
	 * @throws IOException if an ordinary ledger operation fails
	 */
	@Test
	public void preservesOpaqueBytesAcrossIndependentAttempts() throws IOException
	{
		ControlledTransfer transfer = new ControlledTransfer(0x51, 0xa5);
		try (LinuxCargoTokens owner = new LinuxCargoTokens(transfer);
			LinuxCargoTokens.Attempt first = owner.retainAttempt();
			LinuxCargoTokens.Attempt second = owner.retainAttempt())
		{
			requireThat(first.tryBorrow().getAsInt(), "firstByte").isEqualTo(0x51, "originalFirstByte");
			requireThat(second.tryBorrow().getAsInt(), "secondByte").isEqualTo(0xa5, "originalSecondByte");
			requireThat(first.tryBorrow().isEmpty(), "capacityExhausted").isTrue();
			second.returnToken((byte) 0xa5);
			first.returnToken((byte) 0x51);
			requireThat(transfer.bytes(), "returnedBytes").isEqualTo(List.of(0xa5, 0x51), "exactOriginals");
		}
		requireThat(transfer.writes, "returnCount").isEqualTo(2, "oneReturnPerAcquiredByte");
	}

	/**
	 * Rejects another attempt's return before native I/O while leaving the valid borrow usable.
	 * @throws IOException if ordinary setup or the valid return fails
	 */
	@Test
	public void rejectsForeignAttemptReturnsWithoutPoisoningKnownState() throws IOException
	{
		ControlledTransfer transfer = new ControlledTransfer(0x51);
		try (LinuxCargoTokens owner = new LinuxCargoTokens(transfer);
			LinuxCargoTokens.Attempt borrower = owner.retainAttempt();
			LinuxCargoTokens.Attempt other = owner.retainAttempt())
		{
			borrower.tryBorrow();
			requireException(IOException.class, () -> other.returnToken((byte) 0x51));
			requireThat(transfer.writes, "rejectedReturnNativeEffects").isEqualTo(0, "noNativeWrite");
			borrower.returnToken((byte) 0x51);
			requireThat(other.tryBorrow().getAsInt(), "validLaterAcquisition").isEqualTo(0x51, "originalByte");
		}
		requireThat(transfer.bytes(), "finalBytes").isEqualTo(List.of(0x51), "oneOriginalToken");
	}

	/**
	 * Recovers each remaining occurrence exactly once after the caller releases the attempt boundary.
	 * No process exists in this in-memory fixture; this does not establish a native descendant barrier.
	 * @throws IOException if acquisition or recovery fails
	 */
	@Test
	public void recoversOutstandingOccurrencesOnce() throws IOException
	{
		ControlledTransfer transfer = new ControlledTransfer(0xa5, 0x51, 0xa5);
		try (LinuxCargoTokens owner = new LinuxCargoTokens(transfer);
			LinuxCargoTokens.Attempt attempt = owner.retainAttempt())
		{
			attempt.tryBorrow();
			attempt.tryBorrow();
			attempt.tryBorrow();
			attempt.returnToken((byte) 0xa5);
			attempt.close();
			attempt.close();
			requireThat(attempt.isClosed(), "retiredAttempt").isTrue();
			requireThat(transfer.bytes().stream().sorted().toList(), "finalByteOccurrences")
				.isEqualTo(List.of(0x51, 0xa5, 0xa5), "originalMultiset");
			requireThat(transfer.writes, "totalNativeReturns").isEqualTo(3, "eachOccurrenceOnce");
		}
	}

	/**
	 * Stops every subsequent transport call after a failed return, regardless of its hidden native effect.
	 * @throws IOException if resource-free setup or initial acquisition fails
	 */
	@Test
	public void uncertainReturnsNeverRepeat() throws IOException
	{
		for (Boundary boundary : List.of(Boundary.BEFORE, Boundary.AFTER))
			for (int value : List.of(0x51, 0xa5))
			{
				ControlledTransfer transfer = new ControlledTransfer(value);
				try (LinuxCargoTokens owner = new LinuxCargoTokens(transfer);
					LinuxCargoTokens.Attempt borrower = owner.retainAttempt();
					LinuxCargoTokens.Attempt other = owner.retainAttempt())
				{
					borrower.tryBorrow();
					transfer.writeFailure = boundary;
					requireThat(requireException(IOException.class, () -> borrower.returnToken((byte) value)), "initialFailure")
						.isEqualTo(transfer.failure, "originalTransferFailure");
					requireThat(requireException(IOException.class, other::tryBorrow).getCause(), "preservedFailure")
						.isEqualTo(transfer.failure, "originalCause");
					requireException(IOException.class, owner::retainAttempt);
					requireException(IOException.class, borrower::close);
					requireException(IOException.class, other::close);
					requireThat(borrower.isClosed(), "failedBorrowerCleanupRetired").isTrue();
					requireThat(other.isClosed(), "otherAttemptCleanupRetired").isTrue();
					requireThat(transfer.reads, "readsAfterFailure").isEqualTo(1, "initialAcquisitionOnly");
					requireThat(transfer.writes, "writesAfterFailure").isEqualTo(1, "uncertainReturnAttemptedOnce");
					List<Integer> expected = boundary == Boundary.AFTER ? List.of(value) : List.of();
					requireThat(transfer.bytes(), "hiddenTransferOutcome").isEqualTo(expected, "noCompensatingDuplicate");
				}
			}
	}

	/**
	 * Stops recovery after an uncertain read without fabricating a byte the owner did not observe.
	 * @throws IOException if resource-free owner setup fails
	 */
	@Test
	public void uncertainReadsNeverFabricateRecovery() throws IOException
	{
		for (Boundary boundary : List.of(Boundary.BEFORE, Boundary.AFTER))
		{
			ControlledTransfer transfer = new ControlledTransfer(0xa5);
			try (LinuxCargoTokens owner = new LinuxCargoTokens(transfer);
				LinuxCargoTokens.Attempt attempt = owner.retainAttempt())
			{
				transfer.readFailure = boundary;
				requireException(IOException.class, attempt::tryBorrow);
				requireException(IOException.class, attempt::tryBorrow);
				requireException(IOException.class, attempt::close);
				requireThat(attempt.isClosed(), "failedReadOwnerRetired").isTrue();
				requireThat(transfer.reads, "readAttempts").isEqualTo(1, "uncertainReadNotRetried");
				requireThat(transfer.writes, "inventedRecoveryWrites").isEqualTo(0, "noKnownByteToReturn");
				List<Integer> expected = boundary == Boundary.BEFORE ? List.of(0xa5) : List.of();
				requireThat(transfer.bytes(), "remainingNativeBytes").isEqualTo(expected, "originalTransferEffect");
			}
		}
	}

	/**
	 * Retires failed recovery and prevents a second close from repeating the uncertain native return.
	 * @throws IOException if initial acquisition fails
	 */
	@Test
	public void failedRecoveryRetiresWithoutRetrying() throws IOException
	{
		for (Boundary boundary : List.of(Boundary.BEFORE, Boundary.AFTER))
		{
			ControlledTransfer transfer = new ControlledTransfer(0x51, 0xa5);
			try (LinuxCargoTokens owner = new LinuxCargoTokens(transfer);
				LinuxCargoTokens.Attempt attempt = owner.retainAttempt())
			{
				attempt.tryBorrow();
				attempt.tryBorrow();
				transfer.writeFailure = boundary;
				requireException(IOException.class, attempt::close);
				attempt.close();
				requireThat(attempt.isClosed(), "failedCleanupRetired").isTrue();
				requireThat(transfer.writes, "recoveryWriteAttempts").isEqualTo(1, "stopAtFirstUncertainEffect");
				List<Integer> expected = boundary == Boundary.AFTER ? List.of(0x51) : List.of();
				requireThat(transfer.bytes(), "partialRecoveryBytes").isEqualTo(expected, "noRetryOrLaterWrites");
			}
		}
	}

	/**
	 * Rejects an invalid native byte and prevents later transfers without indexing it as a known token.
	 * @throws IOException if resource-free setup fails
	 */
	@Test
	public void invalidNativeBytesPoisonFurtherTransfers() throws IOException
	{
		for (int value : List.of(-1, 256))
		{
			ControlledTransfer transfer = new ControlledTransfer(value);
			try (LinuxCargoTokens owner = new LinuxCargoTokens(transfer);
				LinuxCargoTokens.Attempt attempt = owner.retainAttempt())
			{
				requireException(IOException.class, attempt::tryBorrow);
				requireException(IOException.class, attempt::tryBorrow);
				requireException(IOException.class, attempt::close);
				requireThat(transfer.reads, "invalidNativeReadAttempts").isEqualTo(1, "noRetryAfterInvalidResult");
				requireThat(transfer.writes, "inventedByteWrites").isEqualTo(0, "invalidByteNeverRecovered");
			}
		}
	}

	/**
	 * Preserves a retained attempt when premature ledger close is rejected and rejects future use after retirement.
	 * @throws IOException if normal acquisition or cleanup fails
	 */
	@Test
	public void requiresAllAttemptOwnersToRetireBeforeLedgerClose() throws IOException
	{
		ControlledTransfer transfer = new ControlledTransfer(0x51);
		try (LinuxCargoTokens owner = new LinuxCargoTokens(transfer);
			LinuxCargoTokens.Attempt attempt = owner.retainAttempt())
		{
			requireException(IllegalStateException.class, owner::close);
			requireThat(attempt.tryBorrow().getAsInt(), "borrowAfterPrematureCloseRejected").isEqualTo(0x51, "originalByte");
			attempt.close();
			requireException(IllegalStateException.class, attempt::tryBorrow);
			requireException(IllegalStateException.class, () -> attempt.returnToken((byte) 0x51));
			owner.close();
			owner.close();
			requireException(IllegalStateException.class, owner::retainAttempt);
		}
		requireThat(transfer.bytes(), "finalOriginalByte").isEqualTo(List.of(0x51), "recoveredOnce");
	}

	/**
	 * Rejects a missing borrowed transport before an attempt can be retained.
	 */
	@Test
	public void rejectsNullTransport()
	{
		requireException(NullPointerException.class, () -> new LinuxCargoTokens(null));
	}

	/**
	 * Selects a controlled failure before an effect, after an effect, or no failure.
	 */
	private enum Boundary
	{
		/** No failure is supplied. */
		NONE,
		/** The transport throws before changing its controlled byte queue. */
		BEFORE,
		/** The transport throws after changing its controlled byte queue. */
		AFTER
	}

	/**
	 * Supplies the byte-transfer contract's normal values and uncertain failure boundaries.
	 * Its queue models FIFO bytes; the ledger's refusal to issue more calls is the behavior under test.
	 */
	private static final class ControlledTransfer implements LinuxCargoTokens.Transfer
	{
		/** Controlled available integers, including deliberately invalid native results in validation cases. */
		private final ArrayDeque<Integer> fifo = new ArrayDeque<>();
		/** Failure identity used to check that the ledger preserves its original cause. */
		private final IOException failure = new IOException("Controlled transfer effect is not reported");
		/** Selected failure timing for subsequent reads in this fixture. */
		private Boundary readFailure = Boundary.NONE;
		/** Selected failure timing for subsequent writes in this fixture. */
		private Boundary writeFailure = Boundary.NONE;
		/** Number of calls the ledger actually makes to this fixture's read boundary. */
		private int reads = 0;
		/** Number of calls the ledger actually makes to this fixture's write boundary. */
		private int writes = 0;

		/**
		 * Copies controlled input integers without retaining the caller's array.
		 * @param values initial native results in read order, including invalid results when selected by a check
		 */
		private ControlledTransfer(int... values)
		{
			for (int value : values)
				fifo.add(value);
		}

		/**
		 * Throws the fixture's preserved failure at the selected side of an effect.
		 * @param selected configured failure boundary
		 * @param current side of the effect being executed
		 * @throws IOException if the configured boundary is the current side
		 */
		private void failAt(Boundary selected, Boundary current) throws IOException
		{
			if (selected == current)
				throw failure;
		}

		@Override
		public OptionalInt tryRead() throws IOException
		{
			++reads;
			failAt(readFailure, Boundary.BEFORE);
			OptionalInt result = fifo.isEmpty() ? OptionalInt.empty() : OptionalInt.of(fifo.removeFirst());
			failAt(readFailure, Boundary.AFTER);
			return result;
		}

		@Override
		public void write(byte token) throws IOException
		{
			++writes;
			failAt(writeFailure, Boundary.BEFORE);
			fifo.addLast(Byte.toUnsignedInt(token));
			failAt(writeFailure, Boundary.AFTER);
		}

		/**
		 * Copies the remaining modeled FIFO bytes for an exact observable-effect assertion.
		 * @return current available integers in FIFO order
		 */
		private List<Integer> bytes()
		{
			return new ArrayList<>(fifo);
		}
	}
}
