package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;

/**
 * Reports that the Linux leader script could not run its launch callback on the leader's thread, so the compiler was
 * never executed. The leader sends this as a {@code dispatch-error:} receipt.
 */
public final class LeaderDispatchFailure extends IOException
{
	/** Prefix of the leader receipt that reports a dispatch failure, as {@code linux-leader.js} sends it. */
	private static final String PREFIX = "dispatch-error:";
	/** Serialization version of this failure. */
	private static final long serialVersionUID = 1L;

	/**
	 * Creates the failure for one leader receipt.
	 * @param receipt the complete leader frame
	 */
	private LeaderDispatchFailure(String receipt)
	{
		super("Leader could not dispatch the launch: " + receipt);
	}

	/**
	 * Creates the failure for a leader receipt that differs from the expected one.
	 * @param payload the receipt's payload, or an empty string if the frame has none
	 * @param receipt the complete leader frame
	 * @return a {@code LeaderDispatchFailure} if {@code payload} reports a dispatch failure, otherwise a generic
	 *     {@code IOException}
	 */
	public static IOException unexpected(String payload, String receipt)
	{
		if (payload.startsWith(PREFIX))
			return new LeaderDispatchFailure(receipt);
		return new IOException("Unexpected leader receipt: " + receipt);
	}
}
