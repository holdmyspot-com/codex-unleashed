package com.holdmyspot.codexunleashed.scheduler;

/**
 * Tracks observed swap growth for one single-thread-confined controller.
 * <p>
 * Call only after a successful native sample. Fresh growth permits recovery even without a native pressure signal.
 * A non-growing sample without pressure clears the incident; occupied swap need not decrease. This class performs
 * no process control.
 */
public final class SwapRecovery
{
    /** Occupied swap bytes from the last successful observation. */
    private long previousBytes = 0;

    /** Indicates whether a successful observation has established the comparison baseline. */
    private boolean sampled = false;

    /** Indicates whether observed growth still awaits a non-growing sample without pressure. */
    private boolean active = false;

    /**
     * Creates empty history; occupied swap on the first observation is not growth.
     */
    public SwapRecovery()
    {
    }

    /**
     * Records a successful native sample and reports an active swap-growth incident.
     *
     * @param swapUsedBytes the nonnegative currently occupied swap bytes
     * @param memoryPressure indicates whether native measurements show current memory pressure
     * @return true after observed growth, until a non-growing sample has no pressure
     * @throws IllegalArgumentException if {@code swapUsedBytes} is negative; history stays unchanged
     */
    public boolean observe(long swapUsedBytes, boolean memoryPressure)
    {
        if (swapUsedBytes < 0)
            throw new IllegalArgumentException("Occupied swap bytes must be nonnegative: " + swapUsedBytes);

        boolean grew = sampled && (swapUsedBytes > previousBytes);
        active = grew || (active && memoryPressure);
        previousBytes = swapUsedBytes;
        sampled = true;
        return active;
    }
}
