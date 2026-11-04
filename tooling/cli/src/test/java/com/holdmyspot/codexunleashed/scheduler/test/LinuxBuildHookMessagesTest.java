package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.AllocationMessages;
import com.holdmyspot.codexunleashed.scheduler.AllocationWaits;
import com.holdmyspot.codexunleashed.scheduler.LinuxBuildHookFrames;
import com.holdmyspot.codexunleashed.scheduler.LinuxBuildHookMessages;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import org.testng.annotations.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies that build hook frames become the allocation messages that {@link AllocationMessages} accepts.
 */
public final class LinuxBuildHookMessagesTest
{
	/**
	 * The attempt that admits the client.
	 */
	private static final UUID ATTEMPT = UUID.fromString("00000000-0000-0000-0000-000000000003");

	/**
	 * Creates the tests.
	 */
	public LinuxBuildHookMessagesTest()
	{
	}

	/**
	 * The handshake's ready message identifies the client.
	 * @throws IOException if the message does not decode
	 */
	@Test
	public void reportsReady() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			LinuxBuildHookMessages messages = new LinuxBuildHookMessages(scope, ATTEMPT, 9, 42);
			requireThat(new AllocationMessages(scope, ATTEMPT, 9, 42).read(messages.ready()), "event").
				isEqualTo(new AllocationMessages.Ready());
		}
	}

	/**
	 * A blocked frame becomes a blocked event with the same wait, thread, size and errno.
	 * @throws IOException if the message does not decode
	 */
	@Test
	public void translatesBlocked() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			LinuxBuildHookMessages messages = new LinuxBuildHookMessages(scope, ATTEMPT, 9, 42);
			String message = messages.allocation(List.of("blocked", "5", "43", "4096", "12")).orElseThrow();
			requireThat(new AllocationMessages(scope, ATTEMPT, 9, 42).read(message), "event").isEqualTo(
				new AllocationMessages.Blocked(new AllocationWaits.Wait(9, 5), BigInteger.valueOf(43),
					BigInteger.valueOf(4096), 12));
		}
	}

	/**
	 * A granted frame becomes a granted event with the same status.
	 * @throws IOException if the message does not decode
	 */
	@Test
	public void translatesGranted() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			LinuxBuildHookMessages messages = new LinuxBuildHookMessages(scope, ATTEMPT, 9, 42);
			String message = messages.allocation(List.of("granted", "5", "duplicate")).orElseThrow();
			requireThat(new AllocationMessages(scope, ATTEMPT, 9, 42).read(message), "event").isEqualTo(
				new AllocationMessages.Granted(new AllocationWaits.Wait(9, 5),
					AllocationMessages.GrantStatus.DUPLICATE));
		}
	}

	/**
	 * A frame about another subject is left to its own handler.
	 * @throws IOException if translation fails
	 */
	@Test
	public void ignoresOtherFrames() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			requireThat(new LinuxBuildHookMessages(scope, ATTEMPT, 9, 42).allocation(List.of("transfer", "1", "read")).
				isPresent(), "translated").isFalse();
		}
	}

	/**
	 * A blocked frame missing a field is rejected rather than forwarded.
	 * @throws IOException always
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*blocked.*")
	public void rejectsShortBlocked() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			new LinuxBuildHookMessages(scope, ATTEMPT, 9, 42).allocation(List.of("blocked", "5", "43", "4096"));
		}
	}

	/**
	 * A grant for this client becomes a grant frame naming its wait.
	 * @throws IOException if the frame does not decode
	 */
	@Test
	public void encodesGrant() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			LinuxBuildHookMessages messages = new LinuxBuildHookMessages(scope, ATTEMPT, 9, 42);
			byte[] frame = messages.grant(new AllocationMessages(scope, ATTEMPT, 9, 42).
				permission(new AllocationWaits.Wait(9, 5)));
			requireThat(LinuxBuildHookFrames.decode(frame), "frame").isEqualTo(List.of("grant", "5"));
		}
	}

	/**
	 * A grant addressed to another process is rejected rather than sent.
	 * @throws IOException always
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*another.*")
	public void rejectsGrantForAnotherClient() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			new LinuxBuildHookMessages(scope, ATTEMPT, 9, 42).grant(new AllocationMessages(scope, ATTEMPT, 9, 43).
				permission(new AllocationWaits.Wait(9, 5)));
		}
	}
}
