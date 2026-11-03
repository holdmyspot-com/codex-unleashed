package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Accounts for extra FIFO tokens borrowed by each attempt of one Linux Cargo invocation.
 * The invocation's implicit slot is outside this ledger. Each attempt retains its exact borrowed bytes until
 * a verified process in that attempt returns them or the whole attempt can no longer execute.
 * Instances, attempts and their borrowed transport are confined to one controller thread.
 * <p>
 * A transfer failure permanently prevents further FIFO effects because its native effect may be unknown.
 * The invocation coordinator must then abort and collect every consumer before destroying the FIFO.
 * This owner neither discovers processes nor establishes their termination boundary; callers must establish
 * whole-attempt execution impossibility before closing an attempt. Closing this owner does not close its transport.
 */
public final class LinuxCargoTokens implements AutoCloseable
{
	/** Native FIFO access borrowed through the invocation's complete cleanup. */
	private final Transfer transfer;
	/** Attempts whose execution-impossibility boundary has not yet been released by their caller. */
	private final Set<Attempt> attempts = new HashSet<>();
	/** First uncertain transfer or invalid native read, or null while all observed effects remain accounted for. */
	private Throwable transferFailure = null;
	/** Indicates whether this ledger has closed after all attempts retired. */
	private boolean closed = false;

	/**
	 * Retains resource-free access to an already owned FIFO.
	 * @param transfer borrowed nonblocking byte transport, kept open until this ledger closes
	 * @throws NullPointerException if {@code transfer} is null
	 */
	public LinuxCargoTokens(Transfer transfer)
	{
		this.transfer = Objects.requireNonNull(transfer, "transfer may not be null");
	}

	/**
	 * Retains token ownership for one attempt before any of its process images can borrow a byte.
	 * The caller must bind the returned capability only to authenticated images of that attempt and retain it
	 * across process replacement and root-process exit.
	 * @return new attempt owner with no outstanding tokens
	 * @throws IllegalStateException if this ledger is closed
	 * @throws IOException if an earlier transfer left the invocation's FIFO state uncertain
	 */
	public Attempt retainAttempt() throws IOException
	{
		requireAvailable();
		Attempt attempt = new Attempt();
		attempts.add(attempt);
		return attempt;
	}

	/**
	 * Rejects further FIFO effects after closure or the first uncertain transfer.
	 * @throws IllegalStateException if this ledger is closed
	 * @throws IOException if an earlier transfer failed or returned an invalid value
	 */
	private void requireAvailable() throws IOException
	{
		if (closed)
			throw new IllegalStateException("Cargo token ledger is closed");
		if (transferFailure != null)
			throw new IOException("Cargo FIFO state is uncertain; abort and collect the invocation", transferFailure);
	}

	/**
	 * Prevents further FIFO effects while preserving the original failure for invocation cleanup.
	 * @param failure native transfer or result-processing failure
	 */
	private void recordFailure(Throwable failure)
	{
		if (transferFailure == null)
			transferFailure = failure;
	}

	/**
	 * Returns one exact byte and permanently stops transfers if its native effect cannot be established.
	 * @param token opaque byte whose return the caller has already authorized
	 * @throws IOException if the return fails, possibly after a native effect
	 * @throws RuntimeException if the transport fails unexpectedly
	 * @throws Error if the transport fails fatally
	 */
	private void returnByte(byte token) throws IOException
	{
		try
		{
			transfer.write(token);
		}
		catch (IOException | RuntimeException | Error failure)
		{
			recordFailure(failure);
			throw failure;
		}
	}

	/**
	 * Closes this ledger after every retained attempt has crossed its termination boundary and retired.
	 * The borrowed FIFO remains owned by the invocation. A prior transfer failure is not retried or rethrown here;
	 * the operation that observed it and each affected attempt's cleanup already report it.
	 * Repeated successful close has no effect.
	 * @throws IllegalStateException if any attempt remains retained
	 */
	@Override
	public void close()
	{
		if (!attempts.isEmpty())
			throw new IllegalStateException("Cargo attempts still retain FIFO token ownership");
		closed = true;
	}

	/**
	 * Retains exact outstanding byte counts for one authenticated whole attempt.
	 * A token may be returned by a different verified process in the same attempt. The caller must not reclaim
	 * tokens on the death of only one image while another descendant can still execute.
	 */
	public final class Attempt implements AutoCloseable
	{
		/** Outstanding occurrences of each unsigned byte value for this whole attempt. */
		private final int[] outstanding = new int[1 << Byte.SIZE];
		/** Indicates whether the caller retired this owner after whole-attempt execution became impossible. */
		private boolean closed = false;

		/**
		 * Creates a resource-free attempt owner with no borrowed tokens.
		 */
		private Attempt()
		{
		}

		/**
		 * Borrows an available byte and records it before returning it for delivery to a process image.
		 * An empty result grants nothing and permits a later retry. A byte remains reserved even if delivery to
		 * the requesting process fails; the whole-attempt termination boundary is required to recover it.
		 * @return unsigned byte in {@code 0..255}, or empty when no token is currently available
		 * @throws IllegalStateException if this attempt or its ledger is closed
		 * @throws IOException if a native transfer failed, returned an invalid value, or the FIFO was already uncertain
		 * @throws RuntimeException if the transport or result processing fails unexpectedly
		 * @throws Error if the transport or result processing fails fatally
		 */
		public OptionalInt tryBorrow() throws IOException
		{
			requireOpen();
			try
			{
				OptionalInt token = Objects.requireNonNull(transfer.tryRead(), "FIFO read result may not be null");
				if (token.isEmpty())
					return token;
				int value = token.getAsInt();
				if (value < 0 || value >= outstanding.length)
					throw new IOException("FIFO read returned an invalid unsigned byte: " + value);
				outstanding[value] = Math.incrementExact(outstanding[value]);
				return token;
			}
			catch (IOException | RuntimeException | Error failure)
			{
				recordFailure(failure);
				throw failure;
			}
		}

		/**
		 * Returns a byte borrowed by any authenticated process in this attempt.
		 * The reservation retires only after the transport confirms the complete return. An unborrowed byte is
		 * rejected before native I/O and does not make other attempts' known FIFO state uncertain.
		 * @param token exact original opaque byte being returned
		 * @throws IllegalStateException if this attempt or its ledger is closed
		 * @throws IOException if this attempt did not borrow that byte, the return fails, or the FIFO was already uncertain
		 * @throws RuntimeException if the transport fails unexpectedly
		 * @throws Error if the transport fails fatally
		 */
		public void returnToken(byte token) throws IOException
		{
			requireOpen();
			int value = Byte.toUnsignedInt(token);
			if (outstanding[value] == 0)
				throw new IOException("Cargo attempt returned an unborrowed token byte: " + value);
			returnByte(token);
			--outstanding[value];
		}

		/**
		 * Rejects operations after either owner closes or the shared FIFO becomes uncertain.
		 * @throws IllegalStateException if this attempt or its ledger is closed
		 * @throws IOException if the shared FIFO state is uncertain
		 */
		private void requireOpen() throws IOException
		{
			if (closed)
				throw new IllegalStateException("Cargo attempt token owner is closed");
			requireAvailable();
		}

		/**
		 * Indicates whether this attempt owner retired after its caller's execution-impossibility boundary.
		 * @return {@code true} after close, including close that reported a transfer failure
		 */
		public boolean isClosed()
		{
			return closed;
		}

		/**
		 * Reclaims outstanding known bytes after the whole attempt can no longer execute, then retires this owner.
		 * The caller must first prevent new image starts and establish execution impossibility for every descendant.
		 * A failed return poisons the shared FIFO and stops reclamation immediately. This attempt still retires,
		 * preserving its remaining counters without retrying any uncertain effect on a later close. The invocation
		 * coordinator must abort, collect every remaining consumer, and destroy the FIFO after such a failure.
		 * Repeated close has no effect, including after a failed close.
		 * @throws IOException if reclamation fails or an earlier transfer made the shared FIFO uncertain
		 * @throws RuntimeException if the transport fails unexpectedly
		 * @throws Error if the transport fails fatally
		 */
		@Override
		public void close() throws IOException
		{
			if (closed)
				return;
			try
			{
				requireAvailable();
				for (int value = 0; value < outstanding.length; ++value)
					while (outstanding[value] > 0)
					{
						returnByte((byte) value);
						--outstanding[value];
					}
			}
			finally
			{
				closed = true;
				attempts.remove(this);
			}
		}
	}

	/**
	 * Transfers individual opaque bytes through an already owned native FIFO.
	 * A failure may occur before or after a native effect. The ledger therefore prevents every subsequent
	 * transfer after any exception or error; implementations must never retry an operation with an unknown effect.
	 */
	public interface Transfer
	{
		/**
		 * Reads at most one byte without waiting for another consumer.
		 * @return unsigned byte in {@code 0..255}, or empty when a known no-effect capacity check finds no byte
		 * @throws IOException if native I/O fails or its result cannot be established
		 */
		OptionalInt tryRead() throws IOException;

		/**
		 * Writes one exact byte and returns only after confirming its complete native effect.
		 * @param token original opaque byte to return
		 * @throws IOException if native I/O fails or a complete one-byte effect cannot be established
		 */
		void write(byte token) throws IOException;
	}
}
