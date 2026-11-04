package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.AllocationHook;
import com.holdmyspot.codexunleashed.scheduler.AllocationHooks;
import com.holdmyspot.codexunleashed.scheduler.AllocationWaits;
import com.holdmyspot.codexunleashed.scheduler.JobQueue;
import com.holdmyspot.codexunleashed.scheduler.LinuxAttempt;
import com.holdmyspot.codexunleashed.scheduler.LinuxAttempts;
import com.holdmyspot.codexunleashed.scheduler.LinuxCargoProtocol;
import com.holdmyspot.codexunleashed.scheduler.LinuxCargoTokens;
import com.holdmyspot.codexunleashed.scheduler.LinuxCargoWrapperConnection;
import com.holdmyspot.codexunleashed.scheduler.LinuxProcessTree;
import com.holdmyspot.codexunleashed.scheduler.LinuxSupervisor;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Drives one Linux Cargo attempt through a real wrapper connection whose far end is {@link FakeCargoWrapper}.
 * <p>
 * Containment and instrumentation are recording fakes, so the tests need no privilege, cgroup or Frida; they check
 * the order of the per-attempt steps and the wrapper messages the attempt sends.
 */
public final class LinuxWrapperAttemptTest
{
	/** Bound on every control in these tests. */
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	/**
	 * Creates independently owned attempt checks.
	 */
	public LinuxWrapperAttemptTest() {}

	/**
	 * The attempt contains the held child, retains its tokens and attaches before it releases the child, sends nothing
	 * while the child runs, and reports the wrapper's exit status once the containment is empty, leaving the final
	 * status to the session.
	 * @throws IOException          if the socket, the child or a valid attempt step fails
	 * @throws InterruptedException if waiting for the child is interrupted
	 */
	@Test
	public void releasesAfterContainmentAndAttachment() throws IOException, InterruptedException
	{
		try (var fixture = FakeCargoWrapper.connect(); var natives = new Natives())
		{
			var attempts = attempts(fixture, natives);
			Process child = fixture.fork(1);
			natives.children.put(child.pid(), child);
			LinuxAttempt attempt = attempts.create("compile", 0);
			attempt.start();
			long parent = ProcessHandle.current().pid();
			requireThat(natives.events, "startOrder").isEqualTo(List.of("contain 1 " + child.pid() + " " + parent,
				"tokens", "instrument " + child.pid() + " " + parent, "attach", "released"));
			requireThat(fixture.receive(), "release").isEqualTo(List.of("release", "1"));
			requireThat(attempt.poll().isEmpty(), "runningHasNoOutcome").isTrue();
			fixture.finish(child, 1);
			JobQueue.Outcome<LinuxSupervisor.Result> outcome = attempt.poll().orElseThrow();
			requireThat(outcome.result().rootExit(), "rootExit").isEqualTo(3);
			requireThat(outcome.aborted(), "aborted").isFalse();
			requireThat(natives.events.getLast(), "instrumentationClosed").isEqualTo("instrumentation-closed");
			requireThat(natives.tokens.getFirst().isClosed(), "tokensRetired").isTrue();
			requireThat(fixture.pending(), "nothingBeforeFinal").isFalse();
			attempt.close();
			requireThat(natives.events.getLast(), "containmentClosed").isEqualTo("containment-closed");
			requireThat(attempts.hasOpenAttempts(), "forgotten").isFalse();
			fixture.connection().finish(outcome.result().rootExit());
			requireThat(fixture.receive(), "final").isEqualTo(List.of("final", "3"));
		}
	}

	/**
	 * An attempt that pressure control terminated and that did not succeed is aborted and makes the wrapper retry, and
	 * the next attempt contains and releases the wrapper's next child.
	 * @throws IOException          if the socket, a child or a valid attempt step fails
	 * @throws InterruptedException if waiting for a child is interrupted
	 */
	@Test
	public void retriesAfterTermination() throws IOException, InterruptedException
	{
		try (var fixture = FakeCargoWrapper.connect(); var natives = new Natives())
		{
			var attempts = attempts(fixture, natives);
			Process first = fixture.fork(1);
			natives.children.put(first.pid(), first);
			LinuxAttempt attempt = attempts.create("compile", 0);
			attempt.start();
			requireThat(fixture.receive(), "release").isEqualTo(List.of("release", "1"));
			attempt.terminate();
			requireThat(natives.events.getLast(), "killedThroughContainment").isEqualTo("terminate");
			fixture.kill(first, 1);
			JobQueue.Outcome<LinuxSupervisor.Result> outcome = attempt.poll().orElseThrow();
			requireThat(outcome.aborted(), "aborted").isTrue();
			requireThat(outcome.result().rootExit(), "signalStatus").isEqualTo(137);
			requireThat(fixture.receive(), "retry").isEqualTo(List.of("retry"));
			attempt.close();

			Process second = fixture.fork(2);
			natives.children.put(second.pid(), second);
			LinuxAttempt retry = attempts.create("compile", 1);
			retry.start();
			requireThat(fixture.receive(), "secondRelease").isEqualTo(List.of("release", "2"));
			fixture.finish(second, 2);
			requireThat(retry.poll().orElseThrow().aborted(), "secondAborted").isFalse();
			requireThat(fixture.pending(), "noSecondRetry").isFalse();
			retry.close();
		}
	}

	/**
	 * A terminated attempt that still succeeded is accepted rather than retried, so its output is not repeated.
	 * @throws IOException          if the socket, the child or a valid attempt step fails
	 * @throws InterruptedException if waiting for the child is interrupted
	 */
	@Test
	public void acceptsSuccessAfterTermination() throws IOException, InterruptedException
	{
		try (var fixture = FakeCargoWrapper.connect(); var natives = new Natives())
		{
			var attempts = attempts(fixture, natives);
			Process child = fixture.fork(1);
			natives.children.put(child.pid(), child);
			LinuxAttempt attempt = attempts.create("compile", 0);
			attempt.start();
			requireThat(fixture.receive(), "release").isEqualTo(List.of("release", "1"));
			child.getOutputStream().write('\n');
			child.getOutputStream().close();
			child.waitFor();
			attempt.terminate();
			fixture.send("exited", "1", "0");
			JobQueue.Outcome<LinuxSupervisor.Result> outcome = attempt.poll().orElseThrow();
			requireThat(outcome.aborted(), "aborted").isFalse();
			requireThat(outcome.result().rootExit(), "rootExit").isEqualTo(0);
			requireThat(fixture.pending(), "noRetry").isFalse();
			attempt.close();
		}
	}

	/**
	 * A wrapper whose connection is lost before it reports an exit fails the attempt, and cleanup still kills the
	 * containment and releases every owner.
	 * @throws IOException if the socket, the child or a valid attempt step fails
	 */
	@Test
	public void failsWhenConnectionIsLost() throws IOException
	{
		try (var fixture = FakeCargoWrapper.connect(); var natives = new Natives())
		{
			var attempts = attempts(fixture, natives);
			Process child = fixture.fork(1);
			natives.children.put(child.pid(), child);
			LinuxAttempt attempt = attempts.create("compile", 0);
			attempt.start();
			requireThat(fixture.receive(), "release").isEqualTo(List.of("release", "1"));
			fixture.disconnect();
			requireException(IOException.class, attempt::poll);
			requireException(IOException.class, attempt::close);
			requireThat(natives.events.subList(natives.events.size() - 3, natives.events.size()), "cleanup")
				.isEqualTo(List.of("terminate", "instrumentation-closed", "containment-closed"));
			requireThat(child.isAlive(), "killed").isFalse();
			requireThat(attempts.hasOpenAttempts(), "forgotten").isFalse();
		}
	}

	/**
	 * A start that fails before the release aborts the held child instead of releasing it, and cleanup releases every
	 * owner acquired so far.
	 * @throws IOException if the socket, the child or a valid attempt step fails
	 */
	@Test
	public void abortsWhenStartFailsBeforeRelease() throws IOException
	{
		try (var fixture = FakeCargoWrapper.connect(); var natives = new Natives())
		{
			var attempts = attempts(fixture, natives);
			Process child = fixture.fork(1);
			natives.children.put(child.pid(), child);
			natives.instrumentFailure = new IOException("Attach failed");
			LinuxAttempt attempt = attempts.create("compile", 0);
			requireException(IOException.class, attempt::start);
			requireThat(fixture.receive(), "abort").isEqualTo(List.of("abort", "1"));
			requireException(IOException.class, attempt::close);
			requireThat(natives.events.getLast(), "containmentClosed").isEqualTo("containment-closed");
			requireThat(natives.tokens.getFirst().isClosed(), "tokensRetired").isTrue();
		}
	}

	/**
	 * Commands accept only a launch and a wrapper connection.
	 * @throws IOException if the socket fails
	 */
	@Test
	public void validatesCommand() throws IOException
	{
		try (var fixture = FakeCargoWrapper.connect())
		{
			var launch = AllocationHooksTest.launch(List.of("/usr/bin/rustc"), Map.of());
			var command = new LinuxAttempts.Command(launch, fixture.connection());
			requireThat(command.launch(), "launch").isEqualTo(launch);
			requireException(NullPointerException.class, () -> new LinuxAttempts.Command(null, fixture.connection()));
			requireException(NullPointerException.class, () -> new LinuxAttempts.Command(launch, null));
		}
	}

	/**
	 * Creates an attempt factory whose single action is the fixture's registered compiler.
	 * @param fixture connected fake wrapper
	 * @param natives recording native owners
	 * @return factory with action {@code compile}
	 * @throws IOException if the registration cannot be read
	 */
	private static LinuxAttempts attempts(FakeCargoWrapper fixture, Natives natives) throws IOException
	{
		LinuxCargoWrapperConnection connection = fixture.connection();
		var register = connection.register();
		var attempts = new LinuxAttempts(UUID.randomUUID(), natives, TIMEOUT);
		attempts.register("compile", new LinuxAttempts.Command(
			AllocationHooksTest.launch(register.arguments(), register.environment()), connection));
		return attempts;
	}

	/**
	 * Records the native owners an attempt acquires and the order of their operations.
	 */
	public static final class Natives implements LinuxAttempts.Natives, AutoCloseable
	{
		/** Operations in the order they happened. */
		public final List<String> events = new ArrayList<>();
		/** Children by pid, killed by their containment's termination. */
		public final Map<Long, Process> children = new HashMap<>();
		/** Token owners retained so far. */
		public final List<LinuxCargoTokens.Attempt> tokens = new ArrayList<>();
		/** Failure the next instrumentation acquisition throws, or null. */
		public IOException instrumentFailure;
		/** Ledger that every token owner borrows from. */
		private final LinuxCargoTokens ledger = new LinuxCargoTokens(new LinuxCargoTokens.Transfer()
		{
			@Override
			public OptionalInt tryRead()
			{
				return OptionalInt.empty();
			}

			@Override
			public void write(byte token)
			{
				throw new IllegalStateException("No token was borrowed");
			}
		});

		/**
		 * Creates empty recording owners.
		 */
		public Natives() {}

		@Override
		public LinuxAttempts.Containment contain(String name, long sequence, LinuxProcessTree.Launch launch,
			LinuxCargoProtocol.Child child, long parent)
		{
			events.add("contain " + child.attempt() + " " + child.pid() + " " + parent);
			return new Containment(this, children.get(child.pid()));
		}

		@Override
		public LinuxCargoTokens.Attempt retainTokens() throws IOException
		{
			events.add("tokens");
			LinuxCargoTokens.Attempt attempt = ledger.retainAttempt();
			tokens.add(attempt);
			return attempt;
		}

		@Override
		public LinuxAttempts.Instrumentation instrument(UUID identity, LinuxProcessTree.Launch launch,
			AllocationHooks.Root root, LinuxCargoTokens.Attempt tokens, AllocationWaits waits, AllocationHook.Pump peers)
			throws IOException
		{
			events.add("instrument " + root.pid() + " " + root.parent());
			if (instrumentFailure != null)
				throw instrumentFailure;
			return new Instrumentation(events);
		}

		@Override
		public void close()
		{
			for (Process child : children.values())
				child.destroyForcibly();
			ledger.close();
		}
	}

	/**
	 * Records containment operations and reports the child's liveness as the containment's population.
	 */
	public static final class Containment implements LinuxAttempts.Containment
	{
		/** Recording owner. */
		private final Natives natives;
		/** Contained child. */
		private final Process child;

		/**
		 * Contains one child.
		 * @param natives recording owner
		 * @param child   contained child
		 */
		public Containment(Natives natives, Process child)
		{
			this.natives = natives;
			this.child = child;
		}

		@Override
		public Path directory()
		{
			return Path.of("/fake-attempt-group");
		}

		@Override
		public boolean isPopulated()
		{
			return child.isAlive();
		}

		@Override
		public void terminate()
		{
			natives.events.add("terminate");
			child.destroyForcibly();
		}

		@Override
		public void close()
		{
			natives.events.add("containment-closed");
		}
	}

	/**
	 * Records instrumentation operations; starting attaches and then releases the held root.
	 */
	public static final class Instrumentation implements LinuxAttempts.Instrumentation
	{
		/** Shared event record. */
		private final List<String> events;

		/**
		 * Records into the given list.
		 * @param events shared event record
		 */
		public Instrumentation(List<String> events)
		{
			this.events = events;
		}

		@Override
		public void start(AllocationHooks.Release release) throws IOException
		{
			events.add("attach");
			release.release(List.of());
			events.add("released");
		}

		@Override
		public void pump() {}

		@Override
		public void grant(AllocationWaits.Wait wait)
		{
			throw new IllegalStateException("No allocation wait was published");
		}

		@Override
		public void stop() {}

		@Override
		public void close(AllocationHook.ExecutionBarrier barrier) throws IOException
		{
			if (!barrier.reached())
				throw new IOException("Instrumentation closed while the workload can execute");
			events.add("instrumentation-closed");
		}
	}
}
