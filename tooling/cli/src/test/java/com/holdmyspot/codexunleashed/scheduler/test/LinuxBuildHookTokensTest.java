package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.CargoTokenMessages;
import com.holdmyspot.codexunleashed.scheduler.LinuxBuildHookFrames;
import com.holdmyspot.codexunleashed.scheduler.LinuxBuildHookTokens;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies that build hook jobserver frames become the messages that {@link CargoTokenMessages} accepts.
 */
public final class LinuxBuildHookTokensTest
{
	/**
	 * The attempt that admits the client.
	 */
	private static final UUID ATTEMPT = UUID.fromString("00000000-0000-0000-0000-000000000003");

	/**
	 * Creates the tests.
	 */
	public LinuxBuildHookTokensTest()
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
			String message = new LinuxBuildHookTokens(scope, ATTEMPT, 9, 42).ready();
			requireThat(new CargoTokenMessages(scope, ATTEMPT, 9, 42).read(message), "event").
				isEqualTo(new CargoTokenMessages.Ready());
		}
	}

	/**
	 * A write transfer becomes a write request carrying its byte.
	 * @throws IOException if the message does not decode
	 */
	@Test
	public void translatesWrite() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			String message = new LinuxBuildHookTokens(scope, ATTEMPT, 9, 42).
				transfer(List.of("transfer", "4", "write", "120")).orElseThrow();
			requireThat(new CargoTokenMessages(scope, ATTEMPT, 9, 42).read(message), "event").isEqualTo(
				new CargoTokenMessages.Write(new CargoTokenMessages.Request(ATTEMPT, 9, 42, 4), 120));
		}
	}

	/**
	 * A read transfer becomes a read request.
	 * @throws IOException if the message does not decode
	 */
	@Test
	public void translatesRead() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			String message = new LinuxBuildHookTokens(scope, ATTEMPT, 9, 42).
				transfer(List.of("transfer", "4", "read")).orElseThrow();
			requireThat(new CargoTokenMessages(scope, ATTEMPT, 9, 42).read(message), "event").isEqualTo(
				new CargoTokenMessages.Read(new CargoTokenMessages.Request(ATTEMPT, 9, 42, 4)));
		}
	}

	/**
	 * A failure keeps its operation and cause.
	 * @throws IOException if the message does not decode
	 */
	@Test
	public void translatesFailure() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			String message = new LinuxBuildHookTokens(scope, ATTEMPT, 9, 42).transfer(List.of("failure", "4", "read",
				"Jobserver transfer count differs from one")).orElseThrow();
			requireThat(new CargoTokenMessages(scope, ATTEMPT, 9, 42).read(message), "event").isEqualTo(
				new CargoTokenMessages.Failure(new CargoTokenMessages.Request(ATTEMPT, 9, 42, 4),
					CargoTokenMessages.Operation.READ, CargoTokenMessages.FailureCause.UNSUPPORTED_COUNT));
		}
	}

	/**
	 * Exhausted sequences become the exhaustion event.
	 * @throws IOException if the message does not decode
	 */
	@Test
	public void translatesExhausted() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			String message = new LinuxBuildHookTokens(scope, ATTEMPT, 9, 42).transfer(List.of("exhausted")).
				orElseThrow();
			requireThat(new CargoTokenMessages(scope, ATTEMPT, 9, 42).read(message), "event").
				isEqualTo(new CargoTokenMessages.Exhausted());
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
			requireThat(new LinuxBuildHookTokens(scope, ATTEMPT, 9, 42).transfer(List.of("blocked", "1", "2", "3",
				"12")).isPresent(), "translated").isFalse();
		}
	}

	/**
	 * A grant for a read becomes a token frame carrying the byte.
	 * @throws IOException if the frame does not decode
	 */
	@Test
	public void encodesReadToken() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			byte[] frame = new LinuxBuildHookTokens(scope, ATTEMPT, 9, 42).token(new CargoTokenMessages(scope, ATTEMPT,
				9, 42).grant(new CargoTokenMessages.Read(new CargoTokenMessages.Request(ATTEMPT, 9, 42, 4)), 65));
			requireThat(LinuxBuildHookFrames.decode(frame), "frame").isEqualTo(List.of("token", "4", "65"));
		}
	}

	/**
	 * An acknowledged write becomes a token frame without a byte.
	 * @throws IOException if the frame does not decode
	 */
	@Test
	public void encodesWriteToken() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			byte[] frame = new LinuxBuildHookTokens(scope, ATTEMPT, 9, 42).token(new CargoTokenMessages(scope, ATTEMPT,
				9, 42).acknowledge(new CargoTokenMessages.Write(new CargoTokenMessages.Request(ATTEMPT, 9, 42, 4), 120)));
			requireThat(LinuxBuildHookFrames.decode(frame), "frame").isEqualTo(List.of("token", "4"));
		}
	}

	/**
	 * A control addressed to another installation is rejected rather than sent.
	 * @throws IOException always
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*another.*")
	public void rejectsControlForAnotherClient() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			new LinuxBuildHookTokens(scope, ATTEMPT, 9, 42).token(new CargoTokenMessages(scope, ATTEMPT, 8, 42).
				acknowledge(new CargoTokenMessages.Write(new CargoTokenMessages.Request(ATTEMPT, 8, 42, 4), 120)));
		}
	}
}
