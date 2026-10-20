package com.holdmyspot.codexunleashed.tooling;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides when compilers start, pause, resume, and rerun so that a build's memory use stays within its free memory.
 * <p>
 * Compilers are ordered by when they first asked to start, and a rerun keeps its compiler's place. The oldest active
 * compiler always runs, so the build progresses. When free memory falls below the reserve, the scheduler pauses the
 * newest running compiler; when only the oldest is still running, it kills the newest paused compiler and waits for
 * that compiler to exit before acting again. A killed compiler reruns alone once every other compiler has exited, and a
 * compiler killed while running alone fails, because no schedule can give it more memory. Compilers start while free
 * memory is at least twice the reserve and none is paused, killed, waiting to rerun, or rerunning. Paused compilers
 * resume oldest first, one per reading of at least twice the reserve, so that each reading reflects earlier resumes.
 * <p>
 * Instances are not thread-safe.
 */
public final class CompilerScheduler
{
	/**
	 * An action that the caller performs on a compiler.
	 */
	public sealed interface Action permits Start, Pause, Resume, Kill, Retry, Fail
	{
	}

	/**
	 * Starts a requested compiler.
	 *
	 * @param compiler the compiler's identifier
	 */
	public record Start(long compiler) implements Action
	{
	}

	/**
	 * Pauses a running compiler.
	 *
	 * @param compiler the compiler's identifier
	 */
	public record Pause(long compiler) implements Action
	{
	}

	/**
	 * Resumes a paused compiler.
	 *
	 * @param compiler the compiler's identifier
	 */
	public record Resume(long compiler) implements Action
	{
	}

	/**
	 * Kills a paused compiler to release its memory.
	 *
	 * @param compiler the compiler's identifier
	 */
	public record Kill(long compiler) implements Action
	{
	}

	/**
	 * Tells a killed compiler's requester that it will be started again.
	 *
	 * @param compiler the compiler's identifier
	 */
	public record Retry(long compiler) implements Action
	{
	}

	/**
	 * Tells a killed compiler's requester that it will not be started again.
	 *
	 * @param compiler the compiler's identifier
	 */
	public record Fail(long compiler) implements Action
	{
	}

	/**
	 * The lifecycle state of a compiler.
	 */
	private enum Status
	{
		/**
		 * The compiler waits for permission to start.
		 */
		QUEUED,
		/**
		 * The compiler may start but has not reported that it runs.
		 */
		STARTING,
		/**
		 * The compiler runs.
		 */
		RUNNING,
		/**
		 * The compiler is paused.
		 */
		PAUSED,
		/**
		 * The compiler was told to exit to release its memory.
		 */
		KILLING,
		/**
		 * The compiler was killed and waits to rerun alone.
		 */
		RETRYING
	}

	/**
	 * The scheduling state of one compiler.
	 */
	private static final class Compiler
	{
		private Status status = Status.QUEUED;
		/**
		 * Indicates whether the compiler runs alone after an earlier attempt was killed.
		 */
		private boolean rerun;

		/**
		 * Creates a queued compiler.
		 */
		Compiler()
		{
		}

		/**
		 * Indicates whether the compiler holds memory.
		 *
		 * @return {@code true} if the compiler is starting, running, paused, or being killed
		 */
		boolean isActive()
		{
			return switch (status)
			{
				case STARTING, RUNNING, PAUSED, KILLING -> true;
				case QUEUED, RETRYING -> false;
			};
		}
	}

	private final long reserve;
	/**
	 * The compilers that have not exited, in the order in which they first asked to start.
	 */
	private final Map<Long, Compiler> compilers = new LinkedHashMap<>();
	/**
	 * The most recent free-memory reading, in bytes.
	 */
	private long freeMemory;

	/**
	 * Creates a scheduler.
	 *
	 * @param reserve the free memory, in bytes, below which compilers are paused or killed; compilers start or resume
	 *                only while free memory is at least twice this value
	 * @throws IllegalArgumentException if {@code reserve} is not positive or exceeds half of {@link Long#MAX_VALUE}
	 */
	public CompilerScheduler(long reserve)
	{
		if (reserve <= 0 || reserve > Long.MAX_VALUE / 2)
			throw new IllegalArgumentException("reserve must be positive and at most half of Long.MAX_VALUE: " + reserve);
		this.reserve = reserve;
	}

	/**
	 * Records a free-memory reading and decides how to respond to it.
	 *
	 * @param freeMemory the memory, in bytes, that the build can still use without exceeding its limit
	 * @return the actions to perform, in order
	 */
	public List<Action> observe(long freeMemory)
	{
		this.freeMemory = freeMemory;
		List<Action> actions = new ArrayList<>();
		if (freeMemory < reserve)
			relievePressure(actions);
		else
		{
			if (freeMemory >= 2 * reserve)
			{
				Long paused = oldest(Status.PAUSED);
				if (paused != null)
					resume(paused, actions);
			}
			schedule(actions);
		}
		return List.copyOf(actions);
	}

	/**
	 * Queues a compiler that asks to start and decides whether it may start now.
	 *
	 * @param compiler the identifier of a compiler that has not asked to start before
	 * @return the actions to perform, in order
	 * @throws IllegalArgumentException if {@code compiler} has already asked to start and has not exited
	 */
	public List<Action> request(long compiler)
	{
		if (compilers.putIfAbsent(compiler, new Compiler()) != null)
			throw new IllegalArgumentException("Compiler " + compiler + " has already asked to start");
		List<Action> actions = new ArrayList<>();
		schedule(actions);
		return List.copyOf(actions);
	}

	/**
	 * Records that a started compiler runs and can therefore be paused.
	 *
	 * @param compiler the identifier of a compiler that was told to start
	 * @throws IllegalArgumentException if {@code compiler} was not told to start or already reported that it runs
	 */
	public void started(long compiler)
	{
		Compiler state = compilers.get(compiler);
		if (state == null || state.status != Status.STARTING)
			throw new IllegalArgumentException("Compiler " + compiler + " was not told to start");
		state.status = Status.RUNNING;
	}

	/**
	 * Records that a compiler exited, or that its requester stopped waiting, and decides how to respond.
	 * <p>
	 * A killed compiler receives {@link Retry} and waits to rerun alone, unless it was killed while rerunning alone,
	 * in which case it receives {@link Fail}.
	 *
	 * @param compiler the identifier of a compiler that asked to start
	 * @param killed {@code true} if a kill signal ended the compiler
	 * @return the actions to perform, in order
	 * @throws IllegalArgumentException if {@code compiler} did not ask to start or already exited
	 */
	public List<Action> exited(long compiler, boolean killed)
	{
		Compiler state = compilers.get(compiler);
		if (state == null)
			throw new IllegalArgumentException("Compiler " + compiler + " is not scheduled");
		List<Action> actions = new ArrayList<>();
		boolean ran = state.isActive();
		if (killed && ran && !state.rerun)
		{
			state.status = Status.RETRYING;
			actions.add(new Retry(compiler));
		}
		else
		{
			compilers.remove(compiler);
			if (killed && ran)
				actions.add(new Fail(compiler));
		}
		schedule(actions);
		return List.copyOf(actions);
	}

	/**
	 * Pauses the newest running compiler other than the oldest active one, or kills the newest paused compiler when
	 * only the oldest runs. Does nothing while a compiler is starting or being killed, because the newest compiler
	 * cannot yet be paused or because memory is about to be released.
	 *
	 * @param actions receives the actions to perform
	 */
	private void relievePressure(List<Action> actions)
	{
		Long oldestActive = null;
		Long newestRunning = null;
		Long newestPaused = null;
		for (Map.Entry<Long, Compiler> entry : compilers.entrySet())
		{
			Compiler state = entry.getValue();
			if (state.status == Status.STARTING || state.status == Status.KILLING)
				return;
			if (oldestActive == null && state.isActive())
				oldestActive = entry.getKey();
			if (state.status == Status.RUNNING)
				newestRunning = entry.getKey();
			else if (state.status == Status.PAUSED)
				newestPaused = entry.getKey();
		}
		if (newestRunning != null && !newestRunning.equals(oldestActive))
		{
			compilers.get(newestRunning).status = Status.PAUSED;
			actions.add(new Pause(newestRunning));
		}
		else if (newestPaused != null && !newestPaused.equals(oldestActive))
		{
			compilers.get(newestPaused).status = Status.KILLING;
			actions.add(new Kill(newestPaused));
		}
	}

	/**
	 * Starts or resumes compilers that may run: the oldest compiler waiting to rerun once no compiler is active, a
	 * paused compiler that has become the oldest active one, the first queued compiler once no compiler is active, and
	 * every queued compiler while memory is ample and nothing holds starts back.
	 *
	 * @param actions receives the actions to perform
	 */
	private void schedule(List<Action> actions)
	{
		Long oldestActive = null;
		for (Map.Entry<Long, Compiler> entry : compilers.entrySet())
		{
			if (entry.getValue().isActive())
			{
				oldestActive = entry.getKey();
				break;
			}
		}
		if (oldestActive == null)
		{
			Long retrying = oldest(Status.RETRYING);
			if (retrying != null)
			{
				Compiler state = compilers.get(retrying);
				state.status = Status.STARTING;
				state.rerun = true;
				actions.add(new Start(retrying));
				return;
			}
			Long queued = oldest(Status.QUEUED);
			if (queued == null)
				return;
			start(queued, actions);
		}
		else if (compilers.get(oldestActive).status == Status.PAUSED)
			resume(oldestActive, actions);

		if (freeMemory < 2 * reserve)
			return;
		for (Compiler state : compilers.values())
		{
			boolean holdsStartsBack = switch (state.status)
			{
				case PAUSED, KILLING, RETRYING -> true;
				case QUEUED, STARTING, RUNNING -> state.rerun;
			};
			if (holdsStartsBack)
				return;
		}
		for (Map.Entry<Long, Compiler> entry : compilers.entrySet())
		{
			if (entry.getValue().status == Status.QUEUED)
				start(entry.getKey(), actions);
		}
	}

	/**
	 * Returns the oldest compiler in a state.
	 *
	 * @param status the state
	 * @return the compiler's identifier, or {@code null} if no compiler is in {@code status}
	 */
	private Long oldest(Status status)
	{
		for (Map.Entry<Long, Compiler> entry : compilers.entrySet())
		{
			if (entry.getValue().status == status)
				return entry.getKey();
		}
		return null;
	}

	/**
	 * Starts a queued compiler.
	 *
	 * @param compiler the compiler's identifier
	 * @param actions receives the start action
	 */
	private void start(long compiler, List<Action> actions)
	{
		compilers.get(compiler).status = Status.STARTING;
		actions.add(new Start(compiler));
	}

	/**
	 * Resumes a paused compiler.
	 *
	 * @param compiler the compiler's identifier
	 * @param actions receives the resume action
	 */
	private void resume(long compiler, List<Action> actions)
	{
		compilers.get(compiler).status = Status.RUNNING;
		actions.add(new Resume(compiler));
	}
}
