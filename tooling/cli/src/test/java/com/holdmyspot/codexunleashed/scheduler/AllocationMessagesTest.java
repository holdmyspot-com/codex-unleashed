package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Set;
import java.util.UUID;
import org.testng.annotations.Test;
import tools.jackson.core.JacksonException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;

/** Checks allocation message values and rejections without native acquisition or grant effects. */
public final class AllocationMessagesTest
{
	/** Deterministic attempt identity shared only by the messages in these codec tests. */
	private static final UUID ATTEMPT = UUID.fromString("12345678-90ab-4cde-8fab-0123456789ab");

	/** Creates allocation message checks. */
	public AllocationMessagesTest()
	{
	}

	/**
	 * Accepts readiness for exactly the configured attempt, installation and process.
	 * @throws IOException if the matching message is rejected
	 */
	@Test
	public void acceptsMatchingReadiness() throws IOException
	{
		var messages = new AllocationMessages(ATTEMPT, 9, 42);
		requireThat(messages.read(frame("ready", "")), "event").isEqualTo(new AllocationMessages.Ready(), "expected");
	}

	/**
	 * Preserves full unsigned native magnitudes while restricting queue wait identities to positive signed longs.
	 * @throws IOException if the matching blocked message is rejected
	 */
	@Test
	public void preservesUnsignedBlockedMetadata() throws IOException
	{
		var messages = new AllocationMessages(ATTEMPT, 9, 42);
		var maximum = new BigInteger("18446744073709551615");
		var expected = new AllocationMessages.Blocked(new AllocationWaits.Wait(9, Long.MAX_VALUE), maximum,
			maximum, 4294967295L);
		requireThat(messages.read(frame("blocked", ",\"wait\":\"9223372036854775807\",\"thread\":\"18446744073709551615\",\"bytes\":\"18446744073709551615\",\"error\":\"4294967295\"")), "event")
			.isEqualTo(expected, "expected");
	}

	/**
	 * Keeps accepted, duplicate and unknown acknowledgements distinct without inferring allocation success.
	 * @throws IOException if a matching acknowledgement is rejected
	 */
	@Test
	public void preservesGrantAcknowledgementStates() throws IOException
	{
		var messages = new AllocationMessages(ATTEMPT, 9, 42);
		for (var status : AllocationMessages.GrantStatus.values())
		{
			var expected = new AllocationMessages.Granted(new AllocationWaits.Wait(9, 3), status);
			requireThat(messages.read(frame("granted", ",\"wait\":\"3\",\"status\":\"" + status.name().toLowerCase(java.util.Locale.ROOT) + "\"")), "event")
				.isEqualTo(expected, "expected");
		}
	}

	/** Rejects stale attempt, installation and process identities before returning any event. */
	@Test
	public void rejectsOtherInstallationIdentities()
	{
		var messages = new AllocationMessages(ATTEMPT, 9, 42);
		String valid = frame("ready", "");
		for (String invalid : new String[]{
			valid.replace(ATTEMPT.toString(), "00000000-0000-0000-0000-000000000002"),
			valid.replace(ATTEMPT.toString(), ATTEMPT.toString().toUpperCase(java.util.Locale.ROOT)),
			valid.replace("\"installation\":\"9\"", "\"installation\":\"8\""),
			valid.replace("\"pid\":\"42\"", "\"pid\":\"43\""),
			valid.replace("codex-build-allocation", "other-protocol")})
			requireException(IOException.class, () -> messages.read(invalid));
	}

	/** Rejects coercions, omitted fields, null values, unexpected fields and unsupported event variants. */
	@Test
	public void rejectsIncorrectShapesAndVersions()
	{
		var messages = new AllocationMessages(ATTEMPT, 9, 42);
		String ready = frame("ready", "");
		for (String invalid : new String[]{"", "null", "[]", "{}",
			ready.replace("\"send\"", "\"error\""),
			ready.replace("\"version\":1", "\"version\":1.0"),
			ready.replace("\"version\":1", "\"version\":4294967297"),
			ready.replace("\"version\":1", "\"version\":\"1\""),
			ready.replace("\"version\":1,", ""),
			ready.replace("\"installation\":\"9\"", "\"installation\":9"),
			ready.replace("\"installation\":\"9\"", "\"installation\":null"),
			frame("ready", ",\"wait\":\"1\""), frame("future-event", ""),
			frame("blocked", ",\"wait\":\"1\""),
			frame("granted", ",\"wait\":\"1\",\"status\":\"future-state\"")})
			requireException(IOException.class, () -> messages.read(invalid));
	}

	/** Rejects noncanonical native identifiers and out-of-range metadata after JSON parsing. */
	@Test
	public void rejectsInvalidNativeMagnitudes()
	{
		var messages = new AllocationMessages(ATTEMPT, 9, 42);
		String blocked = frame("blocked", ",\"wait\":\"1\",\"thread\":\"2\",\"bytes\":\"3\",\"error\":\"0\"");
		for (String value : new String[]{"0", "-1", "+1", "01", " 1", "1.0", "١", "9223372036854775808"})
		{
			String invalid = blocked.replace("\"wait\":\"1\"", "\"wait\":\"" + value + "\"");
			requireException(IOException.class, () -> messages.read(invalid));
		}
		for (String invalid : new String[]{
			blocked.replace("\"thread\":\"2\"", "\"thread\":\"0\""),
			blocked.replace("\"thread\":\"2\"", "\"thread\":\"18446744073709551616\""),
			blocked.replace("\"bytes\":\"3\"", "\"bytes\":\"0\""),
			blocked.replace("\"bytes\":\"3\"", "\"bytes\":\"18446744073709551616\""),
			blocked.replace("\"error\":\"0\"", "\"error\":\"-1\""),
			blocked.replace("\"error\":\"0\"", "\"error\":\"4294967296\"")})
			requireException(IOException.class, () -> messages.read(invalid));
	}

	/** Rejects malformed grammar, duplicate names, trailing documents and messages beyond the codec's limits. */
	@Test
	public void rejectsMalformedAndOversizedMessages()
	{
		var messages = new AllocationMessages(ATTEMPT, 9, 42);
		String ready = frame("ready", "");
		for (String invalid : new String[]{"{broken}", ready + " {}", ready.replace("\"version\":1", "\"version\":1,\"version\":1"),
			frame("ready", ",\"extra\":\"" + "x".repeat(129) + "\""), " ".repeat(8193) + ready,
			"{\"type\":\"send\",\"payload\":{\"one\":{\"two\":{\"three\":{}}}}}"})
			requireException(IOException.class, () -> messages.read(invalid));
		var malformed = requireException(IOException.class, () -> messages.read("{broken}"));
		requireThat(malformed.getCause() instanceof JacksonException, "parserFailureRetained").isTrue();

		for (String limited : new String[]{
			frame("ready", ",\"extra\":\"" + "x".repeat(129) + "\""),
			ready.replace("\"version\":1", "\"version\":123456789012345678901"),
			"{\"type\":\"send\",\"payload\":{\"one\":{\"two\":{\"three\":{}}}}}"})
		{
			var failure = requireException(IOException.class, () -> messages.read(limited));
			requireThat(failure.getCause() instanceof JacksonException, "parserLimitFailureRetained").isTrue();
		}

	}

	/**
	 * Constructs fresh grant values with exact immutable configuration and rejects another installation before output.
	 */
	@Test
	public void serializesOnlyMatchingPermissions()
	{
		var messages = new AllocationMessages(ATTEMPT, 9, 42);
		var wait = new AllocationWaits.Wait(9, 7);
		var first = messages.permission(wait);
		requireThat(Set.copyOf(first.propertyNames()), "fields").isEqualTo(Set.of("type", "version", "attempt", "installation", "pid", "operation", "wait"), "expected");
		requireThat(first.path("type").asString(), "type").isEqualTo("allocation-control", "expected");
		requireThat(first.path("operation").asString(), "operation").isEqualTo("grant", "expected");
		requireThat(first.path("attempt").asString(), "attempt").isEqualTo(ATTEMPT.toString(), "expected");
		requireThat(first.path("installation").asString(), "installation").isEqualTo("9", "expected");
		requireThat(first.path("pid").asString(), "pid").isEqualTo("42", "expected");
		requireThat(first.path("wait").asString(), "wait").isEqualTo("7", "expected");
		first.put("wait", "999");
		requireThat(messages.permission(wait).path("wait").asString(), "nextWait").isEqualTo("7", "expected");
		requireException(IllegalArgumentException.class, () -> messages.permission(new AllocationWaits.Wait(8, 7)));
		requireException(NullPointerException.class, () -> messages.permission(null));
	}

	/** Rejects invalid local configuration and null input without acquiring any resource. */
	@Test
	public void validatesLocalArguments()
	{
		requireException(NullPointerException.class, () -> new AllocationMessages(null, 1, 1));
		requireException(IllegalArgumentException.class, () -> new AllocationMessages(ATTEMPT, 0, 1));
		requireException(IllegalArgumentException.class, () -> new AllocationMessages(ATTEMPT, 1, 0));
		requireException(IllegalArgumentException.class, () -> new AllocationMessages(ATTEMPT, 1, 4294967296L));
		var messages = new AllocationMessages(ATTEMPT, 1, 1);
		requireException(NullPointerException.class, () -> messages.read(null));
	}

	/**
	 * Creates one deterministic native send message; suffix contains only the fields selected by its test.
	 * @param event event name written without escaping; callers supply fixed literals
	 * @param suffix additional fixed test fields beginning with a comma, or empty
	 * @return complete JSON fixture for the configured identity
	 */
	private static String frame(String event, String suffix)
	{
		return "{\"type\":\"send\",\"payload\":{\"protocol\":\"codex-build-allocation\",\"version\":1,\"attempt\":\"" + ATTEMPT +
			"\",\"installation\":\"9\",\"pid\":\"42\",\"event\":\"" + event + "\"" + suffix + "}}";
	}
}
