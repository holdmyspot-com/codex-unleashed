package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.Controller;
import com.holdmyspot.codexunleashed.scheduler.JobQueue;
import com.holdmyspot.codexunleashed.scheduler.Policy;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.util.Optional;
import org.testng.annotations.Test;

/** Checks that the queue exposes the state a stall report needs, without sampling or changing scheduling. */
public final class JobQueueDiagnosticsTest
{
	/** Creates independent queue snapshot checks. */
	public JobQueueDiagnosticsTest()
	{
	}

	/** Attempt that completes when the test says so. */
	private static final class Finishing implements JobQueue.Attempt<String>
	{
		/** Whether the whole job has finished. */
		private boolean finished;

		/** Creates an attempt that has not finished. */
		private Finishing()
		{
		}

		@Override
		public void start()
		{
		}

		@Override
		public void resume(long waitSequence)
		{
		}

		@Override
		public void terminate()
		{
		}

		@Override
		public Optional<JobQueue.Outcome<String>> poll()
		{
			return finished ? Optional.of(new JobQueue.Outcome<>("done", false)) : Optional.empty();
		}

		@Override
		public void close()
		{
		}
	}

	/** Measurements that report a fixed host and no residency. */
	private static final class Fixed implements Controller.Measurements
	{
		/** Creates a fixed provider. */
		private Fixed()
		{
		}

		@Override
		public Controller.Memory readHost()
		{
			return new Controller.Memory(1000, 0, false);
		}

		@Override
		public long readResidentBytes(Policy.Job job)
		{
			return 0;
		}
	}

	/**
	 * The snapshot lists unfinished work, the latest host reading and retained demand, and its progress count rises when
	 * an attempt is created and again when one finishes.
	 *
	 * @throws IOException if the controlled queue fails
	 */
	@Test
	public void snapshotShowsJobsHostDemandAndProgress() throws IOException
	{
		Finishing first = new Finishing();
		try (JobQueue<String> queue = new JobQueue<>(new Policy.Budget(100, 1), new Fixed(), (name, sequence) -> sequence == 0 ? first : new Finishing()))
		{
			queue.submit("a", 300L);
			queue.submit("b", 300L);
			JobQueue.Snapshot beforeSampling = queue.diagnostics();
			requireThat(beforeSampling.host(), "hostBeforeFirstReading").isNull();
			requireThat(beforeSampling.progress(), "progressBeforeStart").isEqualTo(0L);

			queue.cycle();
			JobQueue.Snapshot running = queue.diagnostics();
			requireThat(running.jobs().size(), "unfinishedJobs").isEqualTo(2);
			requireThat(running.jobs().getFirst().state(), "firstState").isEqualTo(Policy.State.RUNNING);
			requireThat(running.host(), "latestHost").isEqualTo(new Controller.Memory(1000, 0, false));
			requireThat(running.progress(), "progressAfterStart").isEqualTo(1L);
			requireThat(running.budget(), "budget").isEqualTo(new Policy.Budget(100, 1));
			requireThat(running.demands().get(new Controller.Action("a", 0)).peakBytes(), "retainedPeak")
				.isEqualTo(300L);

			first.finished = true;
			queue.cycle();
			requireThat(queue.diagnostics().progress(), "progressAfterFinish").isGreaterThan(running.progress());
		}
	}
}
