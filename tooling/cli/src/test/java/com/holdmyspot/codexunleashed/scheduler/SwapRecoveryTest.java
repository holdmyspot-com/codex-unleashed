package com.holdmyspot.codexunleashed.scheduler;

import java.util.List;
import org.testng.annotations.Test;
import com.holdmyspot.codexunleashed.scheduler.Policy.Budget;
import com.holdmyspot.codexunleashed.scheduler.Policy.Control;
import com.holdmyspot.codexunleashed.scheduler.Policy.Job;
import com.holdmyspot.codexunleashed.scheduler.Policy.Observation;
import com.holdmyspot.codexunleashed.scheduler.Policy.State;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

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
        assertFalse(history.observe(100, true));
        assertFalse(history.observe(100, true));
        assertFalse(history.observe(90, true));
    }

    /**
     * Retains an observed incident during pressure, even while swap occupancy is unchanged or decreasing.
     */
    @Test
    public void pressureRetainsObservedGrowth()
    {
        var history = new SwapRecovery();
        assertFalse(history.observe(0, false));
        assertTrue(history.observe(100, true));
        assertTrue(history.observe(100, true));
        assertTrue(history.observe(90, true));
        assertFalse(history.observe(90, false));
        assertFalse(history.observe(90, true));
    }

    /**
     * Preserves growth that precedes the next pressure sample without treating the baseline as growth.
     */
    @Test
    public void growthCanPrecedePressure()
    {
        var history = new SwapRecovery();
        assertFalse(history.observe(100, false));
        assertTrue(history.observe(101, false));
        assertTrue(history.observe(101, true));
        assertFalse(history.observe(101, false));
    }

    /**
     * Rejects invalid samples before they change the baseline or recovery state.
     */
    @Test
    public void rejectedSamplePreservesHistory()
    {
        var history = new SwapRecovery();
        expectThrows(IllegalArgumentException.class, () -> history.observe(-1, false));
        assertFalse(history.observe(100, true));
        assertTrue(history.observe(200, true));
        expectThrows(IllegalArgumentException.class, () -> history.observe(-1, false));
        assertTrue(history.observe(200, true));
    }

    /**
     * Compares exact byte counts without overflow and keeps independent controller histories separate.
     */
    @Test
    public void exactByteBoundariesAndIndependentHistories()
    {
        var history = new SwapRecovery();
        var other = new SwapRecovery();
        assertFalse(history.observe(0, false));
        assertTrue(history.observe(Long.MAX_VALUE, true));
        assertFalse(other.observe(Long.MAX_VALUE, true));
        assertFalse(history.observe(Long.MAX_VALUE, false));
        assertFalse(history.observe(0, false));
        assertTrue(history.observe(1, true));
    }

    /**
     * Lets policy stop recovery after pressure relief while occupancy remains high, and preserves retry ordering.
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
        assertEquals(recovery.control(), Control.TERMINATE);
        assertEquals(recovery.jobName(), "newest");

        var retry = new Job("newest", State.RETRY, 1, 200L, 0, null, null, 1);
        var afterReap = List.of(oldest, retry);
        var beforeCompletion = new Observation(1_000, false, history.observe(200, false), 0);
        assertEquals(Policy.chooseAction(budget, afterReap, beforeCompletion).control(), Control.WAIT);

        var afterCompletion = new Observation(1_000, false, history.observe(200, false), 1);
        var resumed = Policy.chooseAction(budget, afterReap, afterCompletion);
        assertEquals(resumed.control(), Control.START);
        assertEquals(resumed.jobName(), "newest");

        var laterPressure = new Observation(10, true, history.observe(200, true), 1);
        assertFalse(laterPressure.swapRecovery());
        assertEquals(Policy.chooseAction(budget, live, laterPressure).control(), Control.WAIT);
    }
}
