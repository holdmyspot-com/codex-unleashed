package com.holdmyspot.codexunleashed.scheduler;

import java.util.List;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks physical-pressure victim selection and relief using independent immutable snapshots.
 * Native acceptance separately proves the source of the pressure and the effects of each control.
 */
public final class PolicyPhysicalPressureTest
{
    /**
     * Creates stateless tests whose individual invocations own every fixture value.
     */
    public PolicyPhysicalPressureTest()
    {
    }

    /**
     * Reclaims the newest attempt under physical pressure while retaining the oldest useful process.
     */
    @Test
    public void terminatesNewestForPhysicalPressure()
    {
        List<Policy.Job> jobs = List.of(
            new Policy.Job("oldest", Policy.State.RUNNING, 0, 400L, 300, 0L, null, 0),
            new Policy.Job("newest", Policy.State.RUNNING, 1, 400L, 300, 1L, null, 0));
        Policy.Decision action = Policy.chooseAction(new Policy.Budget(100, 2), jobs,
            new Policy.Observation(10, true, false, 0));
        requireThat(action.control(), "control").isEqualTo(Policy.Control.TERMINATE, "expected");
        requireThat(action.jobName(), "victim").isEqualTo("newest", "expected");
    }

    /**
     * Preserves the sole running process under physical pressure.
     */
    @Test
    public void protectsSoleRunningProcess()
    {
        List<Policy.Job> jobs = List.of(
            new Policy.Job("oldest", Policy.State.RUNNING, 0, 400L, 300, 0L, null, 0));
        Policy.Decision action = Policy.chooseAction(new Policy.Budget(100, 2), jobs,
            new Policy.Observation(10, true, false, 0));
        requireThat(action.control(), "control").isEqualTo(Policy.Control.WAIT, "expected");
        requireThat(action.jobName(), "victim").isNull();
    }

    /**
     * Defers further controls until a selected whole process tree has actually been collected.
     */
    @Test
    public void waitsWhileVictimIsStopping()
    {
        List<Policy.Job> jobs = List.of(
            new Policy.Job("oldest", Policy.State.RUNNING, 0, 400L, 300, 0L, null, 0),
            new Policy.Job("newest", Policy.State.STOPPING, 1, 400L, 300, 1L, null, 0));
        Policy.Decision action = Policy.chooseAction(new Policy.Budget(100, 2), jobs,
            new Policy.Observation(10, true, false, 0));
        requireThat(action.control(), "control").isEqualTo(Policy.Control.WAIT, "expected");
    }

    /**
     * Restarts the original earlier retry before later ready work when actual pressure has cleared.
     */
    @Test
    public void retriesOriginalPositionAfterRelief()
    {
        List<Policy.Job> jobs = List.of(
            new Policy.Job("retry", Policy.State.RETRY, 1, 100L, 0, null, null, 1),
            new Policy.Job("tail", Policy.State.READY, 2, 100L, 0, null, null, 0));
        Policy.Decision action = Policy.chooseAction(new Policy.Budget(100, 2), jobs,
            new Policy.Observation(1000, false, false, 1));
        requireThat(action.control(), "control").isEqualTo(Policy.Control.START, "expected");
        requireThat(action.jobName(), "next").isEqualTo("retry", "expected");
    }
}
