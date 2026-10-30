package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Exercises the production deferred FIFO with actual hook/batch interpretation and the real queue. */
public final class LinuxAttemptsTest
{
	/** Creates isolated coordinator tests. */
	public LinuxAttemptsTest() {}

	/** Peer denial during A's permission acknowledgement precedes A's following batch.
	 * @throws IOException if valid composed control fails
	 */
	@Test
	public void preservesPeerBeforeFreshDenial() throws IOException
	{
		var events = new LinuxAttempts.Notifications();
		var aWaits = new AllocationWaits();
		var bWaits = new AllocationWaits();
		var aConnection = new AllocationHooksTest.Connection();
		var bConnection = new AllocationHooksTest.Connection();
		var a = AllocationHooksTest.ready(aConnection, aWaits);
		var b = AllocationHooksTest.ready(bConnection, bWaits);
		Controller.Measurements measurements = new Controller.Measurements()
		{
			@Override public Controller.Memory readHost() { return new Controller.Memory(1000, 0, false); }
			@Override public long readResidentBytes(Policy.Job job) { return 1; }
		};
		try (var queue = new JobQueue<String>(new Policy.Budget(0, 2), measurements, (name, sequence) ->
			new JobQueue.Attempt<String>()
			{
				@Override public void start() {}
				@Override public void resume(long batch) throws IOException
				{
					if (name.equals("A")) aWaits.resume(batch, wait -> a.grant(wait, () ->
					{
						b.pump();
						var peer = bWaits.poll();
						if (peer.isPresent()) events.add(new LinuxAttempts.Blocked("B", 1, peer.getAsLong()));
					}));
				}
				@Override public void terminate() {}
				@Override public java.util.Optional<JobQueue.Outcome<String>> poll() { return java.util.Optional.empty(); }
				@Override public void close() {}
			}))
		{
			queue.submit("A", 10L);
			queue.submit("B", 10L);
			queue.cycle(); queue.cycle();
			aConnection.frames.add(AllocationHooksTest.frame("blocked", 1));
			a.pump();
			queue.allocationBlocked("A", 0, aWaits.poll().orElseThrow());
			aConnection.onPost = permission ->
			{
				bConnection.frames.add(AllocationHooksTest.frame("blocked", 1));
				aConnection.frames.add(AllocationHooksTest.frame("blocked", 2));
				aConnection.frames.add(AllocationHooksTest.frame("granted", 1));
			};
			requireThat(queue.cycle().jobName(), "firstResume").isEqualTo("A", "expected");
			events.add(new LinuxAttempts.Blocked("A", 0, aWaits.poll().orElseThrow()));
			var first = events.poll().orElseThrow();
			var second = events.poll().orElseThrow();
			requireThat(List.of(first.name(), second.name()), "deferredOrder").isEqualTo(List.of("B", "A"), "expected");
			queue.allocationBlocked(first.name(), first.attemptSequence(), first.waitSequence());
			queue.allocationBlocked(second.name(), second.attemptSequence(), second.waitSequence());
			requireThat(queue.cycle().jobName(), "oldestDenial").isEqualTo("B", "expected");
			requireThat(queue.completedAttempts().size(), "noInventedCompletion").isEqualTo(0, "expected");
		}
		finally { a.close(() -> true); b.close(() -> true); }
	}

	/** Fixed-width identity derives the exact agreed correlation without resource acquisition. */
	@Test
	public void derivesFixedWidthIdentity()
	{
		UUID build = UUID.fromString("00000000-0000-0001-0000-000000000002");
		UUID expected = UUID.nameUUIDFromBytes(ByteBuffer.allocate(24).putLong(1).putLong(2).putLong(3).array());
		requireThat(LinuxAttempts.attemptIdentity(build, 3), "exactIdentity").isEqualTo(expected, "expected");
		requireThat(LinuxAttempts.attemptIdentity(build, 4).equals(expected), "differentOrdinal").isEqualTo(false, "expected");
		requireException(IllegalArgumentException.class, () -> LinuxAttempts.attemptIdentity(build, -1));
	}

	/** Invalid notifications cannot collapse native, attempt and queue batch identities. */
	@Test
	public void rejectsInvalidNotification()
	{
		requireException(IllegalArgumentException.class, () -> new LinuxAttempts.Blocked(" ", 0, 0));
		requireException(IllegalArgumentException.class, () -> new LinuxAttempts.Blocked("A", -1, 0));
		requireException(IllegalArgumentException.class, () -> new LinuxAttempts.Blocked("A", 0, -1));
		var events = new LinuxAttempts.Notifications();
		requireThat(events.poll().isEmpty(), "initiallyEmpty").isEqualTo(true, "expected");
	}
}
