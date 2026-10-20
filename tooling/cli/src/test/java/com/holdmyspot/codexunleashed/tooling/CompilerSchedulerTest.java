package com.holdmyspot.codexunleashed.tooling;

import com.holdmyspot.codexunleashed.tooling.CompilerScheduler.Fail;
import com.holdmyspot.codexunleashed.tooling.CompilerScheduler.Kill;
import com.holdmyspot.codexunleashed.tooling.CompilerScheduler.Pause;
import com.holdmyspot.codexunleashed.tooling.CompilerScheduler.Resume;
import com.holdmyspot.codexunleashed.tooling.CompilerScheduler.Retry;
import com.holdmyspot.codexunleashed.tooling.CompilerScheduler.Start;
import java.util.List;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

/**
 * Exercises when compilers start, pause, resume, and rerun as the build's free memory changes.
 */
public final class CompilerSchedulerTest
{
	/**
	 * The free memory, in bytes, below which the tested scheduler pauses or kills compilers.
	 */
	private static final long RESERVE = 1000;
	/**
	 * Free memory, in bytes, that lets the tested scheduler start and resume compilers.
	 */
	private static final long AMPLE = 2 * RESERVE;
	/**
	 * Free memory, in bytes, that makes the tested scheduler pause or kill a compiler.
	 */
	private static final long SHORT = RESERVE - 1;

	/**
	 * Creates the scheduler tests.
	 */
	public CompilerSchedulerTest()
	{
	}

	/**
	 * Starts a compiler when none is active, even when memory is short, so the build progresses, and starts others
	 * only when free memory is ample.
	 */
	@Test
	public void startsCompilersWhileMemoryIsAmple()
	{
		CompilerScheduler scheduler = new CompilerScheduler(RESERVE);
		scheduler.observe(SHORT);

		assertEquals(scheduler.request(1), List.of(new Start(1)));
		assertEquals(scheduler.request(2), List.of());
		assertEquals(scheduler.observe(AMPLE), List.of(new Start(2)));
		assertEquals(scheduler.request(3), List.of(new Start(3)));
	}

	/**
	 * Pauses running compilers newest first under memory pressure, never the oldest, kills the newest paused compiler
	 * when pausing cannot help, waits for that kill to release memory, and starts nothing new meanwhile.
	 */
	@Test
	public void pausesNewestCompilerAndFreezesStarts()
	{
		CompilerScheduler scheduler = runningCompilers(3);

		assertEquals(scheduler.observe(SHORT), List.of(new Pause(3)));
		assertEquals(scheduler.observe(SHORT), List.of(new Pause(2)));
		assertEquals(scheduler.observe(SHORT), List.of(new Kill(3)));
		assertEquals(scheduler.observe(SHORT), List.of());

		assertEquals(scheduler.request(4), List.of());
		assertEquals(scheduler.exited(3, true), List.of(new Retry(3)));
		assertEquals(scheduler.observe(AMPLE), List.of(new Resume(2)));
		assertEquals(scheduler.observe(AMPLE), List.of());
	}

	/**
	 * Resumes paused compilers oldest first, one per reading, when memory frees up, then lifts the freeze.
	 */
	@Test
	public void resumesOldestPausedCompilerFirst()
	{
		CompilerScheduler scheduler = runningCompilers(3);
		scheduler.observe(SHORT);
		scheduler.observe(SHORT);
		scheduler.request(4);

		assertEquals(scheduler.observe(AMPLE), List.of(new Resume(2)));
		assertEquals(scheduler.observe(AMPLE), List.of(new Resume(3), new Start(4)));
	}

	/**
	 * Resumes a paused compiler as soon as every older compiler finishes, even when memory is short, because the
	 * oldest compiler always runs.
	 */
	@Test
	public void resumesPausedCompilerThatBecomesOldest()
	{
		CompilerScheduler scheduler = runningCompilers(2);
		scheduler.observe(SHORT);

		assertEquals(scheduler.exited(1, false), List.of(new Resume(2)));
	}

	/**
	 * Reruns a killed compiler once every other compiler finishes and starts nothing beside it until the rerun ends.
	 */
	@Test
	public void rerunsKilledCompilerAlone()
	{
		CompilerScheduler scheduler = runningCompilers(3);
		scheduler.observe(SHORT);
		scheduler.observe(SHORT);
		scheduler.observe(SHORT);
		scheduler.exited(3, true);
		scheduler.observe(AMPLE);
		scheduler.request(4);

		assertEquals(scheduler.exited(1, false), List.of());
		assertEquals(scheduler.exited(2, false), List.of(new Start(3)));
		scheduler.started(3);
		assertEquals(scheduler.request(5), List.of());
		assertEquals(scheduler.exited(3, false), List.of(new Start(4), new Start(5)));
	}

	/**
	 * Reruns a compiler that the system killed while it was the oldest, and fails it when it is killed again while
	 * running alone, because no schedule can then provide it more memory.
	 */
	@Test
	public void failsCompilerKilledWhileAlone()
	{
		CompilerScheduler scheduler = runningCompilers(2);

		assertEquals(scheduler.exited(1, true), List.of(new Retry(1)));
		assertEquals(scheduler.request(3), List.of());
		assertEquals(scheduler.exited(2, false), List.of(new Start(1)));
		scheduler.started(1);
		assertEquals(scheduler.exited(1, true), List.of(new Fail(1), new Start(3)));
	}

	/**
	 * Takes no memory-pressure action while a started compiler has not reported that it is running, because only a
	 * running compiler can be paused and the newest compiler must be paused first.
	 */
	@Test
	public void waitsForStartingCompilerBeforePausing()
	{
		CompilerScheduler scheduler = runningCompilers(1);
		scheduler.request(2);

		assertEquals(scheduler.observe(SHORT), List.of());
		scheduler.started(2);
		assertEquals(scheduler.observe(SHORT), List.of(new Pause(2)));
	}

	/**
	 * Creates a scheduler whose compilers, numbered from one, have started and are running with ample memory.
	 *
	 * @param count the number of running compilers
	 * @return the scheduler
	 */
	private static CompilerScheduler runningCompilers(int count)
	{
		CompilerScheduler scheduler = new CompilerScheduler(RESERVE);
		scheduler.observe(AMPLE);
		for (long compiler = 1; compiler <= count; ++compiler)
		{
			assertEquals(scheduler.request(compiler), List.of(new Start(compiler)));
			scheduler.started(compiler);
		}
		return scheduler;
	}
}
