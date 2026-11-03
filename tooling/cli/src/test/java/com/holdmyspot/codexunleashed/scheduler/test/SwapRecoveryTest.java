package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.Policy;
import com.holdmyspot.codexunleashed.scheduler.SwapRecovery;
import java.util.List;
import org.testng.annotations.Test;
import com.holdmyspot.codexunleashed.scheduler.Policy.Budget;
import com.holdmyspot.codexunleashed.scheduler.Policy.Control;
import com.holdmyspot.codexunleashed.scheduler.Policy.Job;
import com.holdmyspot.codexunleashed.scheduler.Policy.Observation;
import com.holdmyspot.codexunleashed.scheduler.Policy.State;

import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises swap-growth history and its use by the actual scheduling policy without claiming native effects.
 */
public final class SwapRecoveryTest
{
    /**
     * Creates history behavior checks.
     */
    public SwapRecoveryTest()
    {
    }

    /**
     * Establishes a baseline even when swap is already occupied and the machine is under pressure.
     */
    @Test
    public void existingOccupancyIsNotGrowth()
    {
        var history = new SwapRecovery();
        requireThat(history.observe(100, true), "history").isFalse();
        requireThat(history.observe(100, true), "history").isFalse();
        requireThat(history.observe(90, true), "history").isFalse();
    }

    /**
     * Retains an observed incident during pressure, even while swap occupancy is unchanged or decreasing.
     */
    @Test
    public void pressureRetainsObservedGrowth()
    {
        var history = new SwapRecovery();
        requireThat(history.observe(0, false), "history").isFalse();
        requireThat(history.observe(100, true), "history").isTrue();
        requireThat(history.observe(100, true), "history").isTrue();
        requireThat(history.observe(90, true), "history").isTrue();
        requireThat(history.observe(90, false), "history").isFalse();
        requireThat(history.observe(90, true), "history").isFalse();
    }

    /**
     * Preserves growth that precedes the next pressure sample without treating the baseline as growth.
     */
    @Test
    public void growthCanPrecedePressure()
    {
        var history = new SwapRecovery();
        requireThat(history.observe(100, false), "history").isFalse();
        requireThat(history.observe(101, false), "history").isTrue();
        requireThat(history.observe(101, true), "history").isTrue();
        requireThat(history.observe(101, false), "history").isFalse();
    }

    /**
     * Rejects invalid samples before they change the baseline or recovery state.
     */
    @Test
    public void rejectedSamplePreservesHistory()
    {
        var history = new SwapRecovery();
        requireException(IllegalArgumentException.class, () -> history.observe(-1, false));
        requireThat(history.observe(100, true), "history").isFalse();
        requireThat(history.observe(200, true), "history").isTrue();
        requireException(IllegalArgumentException.class, () -> history.observe(-1, false));
        requireThat(history.observe(200, true), "history").isTrue();
    }

    /**
     * Compares exact byte counts without overflow and keeps independent controller histories separate.
     */
    @Test
    public void exactByteBoundariesAndIndependentHistories()
    {
        var history = new SwapRecovery();
        var other = new SwapRecovery();
        requireThat(history.observe(0, false), "history").isFalse();
        requireThat(history.observe(Long.MAX_VALUE, true), "history").isTrue();
        requireThat(other.observe(Long.MAX_VALUE, true), "other").isFalse();
        requireThat(history.observe(Long.MAX_VALUE, false), "history").isFalse();
        requireThat(history.observe(0, false), "history").isFalse();
        requireThat(history.observe(1, true), "history").isTrue();
    }

    /**
     * Ends swap recovery after pressure relief while preserving retry order and later physical-pressure control.
     */
    @Test
    public void policyStopsRecoveryWithoutWaitingForSwapDecrease()
    {
        var history = new SwapRecovery();
        var budget = new Budget(100, 2);
        var oldest = new Job("oldest", State.RUNNING, 0, 200L, 200, 0L, null, 0);
        var newest = new Job("newest", State.RUNNING, 1, 200L, 200, 1L, null, 0);
        var live = List.of(oldest, newest);
        history.observe(100, false);

        var pressure = new Observation(10, true, history.observe(200, true), 0);
        var recovery = Policy.chooseAction(budget, live, pressure);
        requireThat(recovery.control(), "recovery.control()").isEqualTo(Control.TERMINATE);
        requireThat(recovery.jobName(), "recovery.jobName()").isEqualTo("newest");

        var retry = new Job("newest", State.RETRY, 1, 200L, 0, null, null, 1);
        var afterReap = List.of(oldest, retry);
        var beforeCompletion = new Observation(1_000, false, history.observe(200, false), 0);
        requireThat(Policy.chooseAction(budget, afterReap, beforeCompletion).control(), "Policy")
            .isEqualTo(Control.WAIT);

        var afterCompletion = new Observation(1_000, false, history.observe(200, false), 1);
        var resumed = Policy.chooseAction(budget, afterReap, afterCompletion);
        requireThat(resumed.control(), "resumed.control()").isEqualTo(Control.START);
        requireThat(resumed.jobName(), "resumed.jobName()").isEqualTo("newest");

        var laterPressure = new Observation(10, true, history.observe(200, true), 1);
        requireThat(laterPressure.swapRecovery(), "laterPressure.swapRecovery()").isFalse();
        var physicalRecovery = Policy.chooseAction(budget, live, laterPressure);
        requireThat(physicalRecovery.control(), "physicalRecovery.control()").isEqualTo(Control.TERMINATE);
        requireThat(physicalRecovery.jobName(), "physicalRecovery.jobName()").isEqualTo("newest");
    }
}
