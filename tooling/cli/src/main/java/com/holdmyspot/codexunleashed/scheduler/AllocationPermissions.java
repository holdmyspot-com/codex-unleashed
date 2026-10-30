package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Owns readiness and permission acknowledgement for one verified native allocation script installation.
 * <p>
 * Every operation belongs to the constructing thread. Native callbacks must only queue messages for that thread;
 * they must not call this owner. A posted permission must receive one matching accepted acknowledgement before another
 * permission is posted. An acknowledgement permits an allocation retry; it does not establish allocation or task success.
 * <p>
 * This resource-free owner performs only the borrowed post operation. Its native consumer must verify installation
 * ownership, selected batch membership and transport health, pump copied messages, enforce acknowledgement deadlines,
 * and terminate and clean native resources after failure. That native composition remains separately unaccepted.
 */
final class AllocationPermissions
{
	/** Borrowed codec that validates this installation's native frames and constructs its permissions. */
	private final AllocationMessages messages;
	/** Borrowed native-script post operation; this owner never closes the underlying transport. */
	private final Poster poster;
	/** Thread that constructs and exclusively operates this installation's permission state. */
	private final Thread owner = Thread.currentThread();
	/** Successfully acknowledged wait identities retained for this installation's lifetime. */
	private final Set<AllocationWaits.Wait> acknowledged = new HashSet<>();
	/** Indicates whether this installation has supplied its one accepted readiness event. */
	private boolean ready = false;
	/** Indicates whether the native owner has permanently withdrawn permission authority. */
	private boolean stopped = false;
	/** Outstanding permission identity retained through delivery uncertainty, or null with no outstanding permission. */
	private AllocationWaits.Wait pending = null;
	/** Delivery stage of the outstanding permission; null when no permission is outstanding. */
	private Delivery delivery = null;
	/** First protocol or transport failure, retained permanently; null before any failure. */
	private IOException failure = null;

	/**
	 * Creates empty permission state without acquiring resources or invoking the poster.
	 * @param messages codec configured for one already verified script installation
	 * @param poster borrowed operation that posts a serialized permission once
	 * @throws NullPointerException if either collaborator is null
	 */
	AllocationPermissions(AllocationMessages messages, Poster poster)
	{
		this.messages = Objects.requireNonNull(messages, "messages may not be null");
		this.poster = Objects.requireNonNull(poster, "poster may not be null");
	}

	/**
	 * Decodes and accepts one copied native message, returning its validated event unchanged.
	 * Readiness is accepted exactly once. Blocked reports require readiness and create no queue state; repeated denial
	 * identities remain values for the batching owner to interpret. Only an accepted response matching the outstanding
	 * posted permission records acknowledgement and clears that pending identity. Responses during posting, unsolicited
	 * or repeated acknowledgements, duplicate/unknown statuses and decoding failures permanently withdraw grant authority.
	 * @param message complete message copied from this installation
	 * @return the accepted readiness, blocked or matching accepted acknowledgement event
	 * @throws IllegalStateException if called outside the constructing thread
	 * @throws IOException if stopped, a prior failure exists, or decoding or the event transition fails
	 */
	AllocationMessages.Event read(String message) throws IOException
	{
		requireOwner();
		requireAuthority();
		try
		{
			AllocationMessages.Event event = messages.read(message);
			switch (event)
			{
				case AllocationMessages.Ready _ ->
				{
					if (ready)
						throw new IOException("Allocation installation already reported readiness");
					ready = true;
				}
				case AllocationMessages.Blocked _ -> requireReady();
				case AllocationMessages.Granted grant ->
				{
					requireReady();
					boolean matchesPosted = delivery == Delivery.POSTED && grant.identity().equals(pending);
					if (!matchesPosted)
						throw new IOException("Allocation acknowledgement does not match an outstanding posted permission: " +
							grant.identity() + "; pending=" + pending + ", delivery=" + delivery);
					if (grant.status() != AllocationMessages.GrantStatus.ACCEPTED)
						throw new IOException("Allocation permission was not accepted: " + grant.status() + "; wait=" + pending);
					acknowledged.add(pending);
					pending = null;
					delivery = null;
				}
			}
			return event;
		}
		catch (IOException cause)
		{
			transportFailed(cause);
			throw withdrawn();
		}
		catch (RuntimeException cause)
		{
			transportFailed(new IOException("Reading the native allocation message failed", cause));
			throw withdrawn();
		}
		catch (Error cause)
		{
			transportFailed(new IOException("Reading the native allocation message did not complete", cause));
			throw cause;
		}
	}

	/**
	 * Posts one codec-validated permission, recording its identity and POSTING stage before the external call.
	 * The POSTED stage is recorded only after that call returns successfully without a reentrant protocol failure.
	 * A throwing post retains the first failure and pending identity; no subsequent permission or resend is allowed.
	 * The native owner remains responsible for verifying that this wait belongs to its selected batch.
	 * @param wait positive native wait belonging to this codec's installation
	 * @throws NullPointerException if {@code wait} is null
	 * @throws IllegalArgumentException if the wait belongs to another installation
	 * @throws IllegalStateException if called outside the constructing thread
	 * @throws IOException if stopped, unready, already posting or awaiting a response, previously acknowledged,
	 * previously failed, or posting fails; operational runtime failures retain their cause
	 * @throws Error if posting throws an error, after grant authority has been withdrawn and its cause retained
	 */
	void grant(AllocationWaits.Wait wait) throws IOException
	{
		requireOwner();
		String permission = messages.permission(wait).toString();
		requireAuthority();
		try
		{
			requireReady();
			if (pending != null || acknowledged.contains(wait))
				throw new IOException("Allocation permission is already pending or acknowledged: " + wait + "; pending=" + pending);
		}
		catch (IOException cause)
		{
			transportFailed(cause);
			throw withdrawn();
		}

		pending = wait;
		delivery = Delivery.POSTING;
		try
		{
			poster.post(permission);
		}
		catch (IOException | RuntimeException cause)
		{
			transportFailed(new IOException("Posting allocation permission failed: " + wait, cause));
			throw withdrawn();
		}
		catch (Error cause)
		{
			transportFailed(new IOException("Posting allocation permission did not complete: " + wait, cause));
			throw cause;
		}
		if (failure != null)
			throw withdrawn();
		delivery = Delivery.POSTED;
		requireAuthority();
	}

	/**
	 * Indicates whether this installation supplied its one accepted readiness event, including before stop or failure.
	 * @return true after accepted readiness
	 * @throws IllegalStateException if called outside the constructing thread
	 */
	boolean ready()
	{
		requireOwner();
		return ready;
	}

	/**
	 * Returns the outstanding permission identity, including during posting and after failure or stop.
	 * @return the retained pending wait, or null before posting and after matching accepted acknowledgement
	 * @throws IllegalStateException if called outside the constructing thread
	 */
	AllocationWaits.Wait pending()
	{
		requireOwner();
		return pending;
	}

	/**
	 * Indicates whether this exact wait received its matching accepted acknowledgement.
	 * Earlier acknowledged waits remain distinguishable from a later pending permission, including after failure or stop.
	 * @param wait exact installation and wait identity to query
	 * @return true after matching acknowledgement of this wait
	 * @throws NullPointerException if {@code wait} is null
	 * @throws IllegalStateException if called outside the constructing thread
	 */
	boolean acknowledged(AllocationWaits.Wait wait)
	{
		requireOwner();
		Objects.requireNonNull(wait, "wait may not be null");
		return acknowledged.contains(wait);
	}

	/**
	 * Returns the first retained protocol or transport failure without changing grant state.
	 * @return the first failure, or null before any failure; an explicit stop alone is not a failure
	 * @throws IllegalStateException if called outside the constructing thread
	 */
	IOException failure()
	{
		requireOwner();
		return failure;
	}

	/**
	 * Permanently withdraws grants after an owner-observed transport failure, retaining its first cause and pending wait.
	 * @param cause confirmed transport or protocol failure
	 * @throws NullPointerException if {@code cause} is null
	 * @throws IllegalStateException if called outside the constructing thread
	 */
	void transportFailed(IOException cause)
	{
		requireOwner();
		Objects.requireNonNull(cause, "cause may not be null");
		if (failure == null)
			failure = cause;
	}

	/**
	 * Permanently prohibits grants and message transitions without posting, waking waiters or releasing native resources.
	 * Pending and acknowledged identities and the first failure remain available for diagnosis. Repeated calls are harmless.
	 * @throws IllegalStateException if called outside the constructing thread
	 */
	void stop()
	{
		requireOwner();
		stopped = true;
	}

	/**
	 * Requires use from this installation state's constructing thread.
	 * @throws IllegalStateException if another thread calls this owner
	 */
	private void requireOwner()
	{
		if (Thread.currentThread() != owner)
			throw new IllegalStateException("Allocation permissions belong to their constructing thread");
	}

	/**
	 * Requires permission authority before another transition.
	 * @throws IOException if a failure has withdrawn authority or the owner is stopped
	 */
	private void requireAuthority() throws IOException
	{
		if (failure != null)
			throw withdrawn();
		if (stopped)
			throw new IOException("Allocation permission owner is stopped");
	}

	/**
	 * Requires this installation's readiness before denials or permission transitions.
	 * @throws IOException if readiness has not been accepted
	 */
	private void requireReady() throws IOException
	{
		if (!ready)
			throw new IOException("Allocation installation has not reported readiness");
	}

	/**
	 * Creates a fresh exception wrapper around the retained first failure.
	 * @return contextual failure without changing the original cause
	 */
	private IOException withdrawn()
	{
		return new IOException("Automatic allocation permission authority is withdrawn", failure);
	}

	/**
	 * Supplies one serialized native script post. Native callbacks must queue copied messages for later owner-thread reads.
	 */
	@FunctionalInterface
	interface Poster
	{
		/**
		 * Posts one complete serialized permission exactly once for this invocation.
		 * @param permission codec-produced allocation control JSON
		 * @throws IOException if delivery fails or its effect is uncertain
		 */
		void post(String permission) throws IOException;
	}

	/**
	 * Distinguishes an external post that has not returned from a successfully posted permission awaiting acknowledgement.
	 */
	private enum Delivery
	{
		/** The external post has begun and has not returned successfully. */
		POSTING,
		/** The external post returned successfully; its matching response may now be consumed. */
		POSTED
	}
}
