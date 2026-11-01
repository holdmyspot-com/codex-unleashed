package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.util.UUID;
import org.testng.annotations.Test;
import tools.jackson.core.JacksonException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;

/**
 * Checks token event decoding and control encoding without transport or FIFO effects.
 */
public final class CargoTokenMessagesTest
{
	/** Deterministic workload identity used by this codec's message fixtures. */
	private static final UUID ATTEMPT = UUID.fromString("12345678-90ab-4cde-8fab-0123456789ab");

	/**
	 * Creates isolated token codec checks.
	 */
	public CargoTokenMessagesTest()
	{
	}

	/**
	 * Accepts the matching native readiness event through the codec's actual entry point.
	 * @throws IOException if the matching frame is rejected
	 */
	@Test
	public void acceptsMatchingReadiness() throws IOException
	{
		try (var scope = new DefaultToolingScope())
		{
			var messages = new CargoTokenMessages(scope, ATTEMPT, 9, 42);
			String frame = "{\"type\":\"send\",\"payload\":{\"protocol\":\"codex-build-cargo-tokens\",\"version\":1," +
				"\"attempt\":\"" + ATTEMPT + "\",\"installation\":\"9\",\"pid\":\"42\",\"event\":\"ready\"}}";
			requireThat(messages.read(frame), "event").isEqualTo(new CargoTokenMessages.Ready(), "expected");
		}
	}

	/**
	 * Preserves exact request identities and both unsigned-byte endpoints.
	 * @throws IOException if a matching transfer is rejected
	 */
	@Test
	public void preservesTransferIdentitiesAndByteEndpoints() throws IOException
	{
		try (var scope = new DefaultToolingScope())
		{
			var messages = new CargoTokenMessages(scope, ATTEMPT, 9, 42);
			requireThat(messages.read(frame("transfer", ",\"sequence\":\"1\",\"operation\":\"read\"")), "read")
				.isEqualTo(new CargoTokenMessages.Read(request(1)), "expected");
			for (int value : new int[]{0, 255})
				requireThat(messages.read(frame("transfer", ",\"sequence\":\"9223372036854775807\",\"operation\":\"write\",\"byte\":" + value)), "write")
					.isEqualTo(new CargoTokenMessages.Write(request(Long.MAX_VALUE), value), "expected");
		}
	}

	/**
	 * Accepts the maximum installation and native PID without narrowing or overflow.
	 * @throws IOException if a valid maximum identity is rejected
	 */
	@Test
	public void acceptsMaximumInstallationAndPid() throws IOException
	{
		try (var scope = new DefaultToolingScope())
		{
			var messages = new CargoTokenMessages(scope, ATTEMPT, Long.MAX_VALUE, 4294967295L);
			String ready = frame("ready", "").replace("\"installation\":\"9\"", "\"installation\":\"9223372036854775807\"")
				.replace("\"pid\":\"42\"", "\"pid\":\"4294967295\"");
			requireThat(messages.read(ready), "event").isEqualTo(new CargoTokenMessages.Ready(), "expected");
		}
	}

	/**
	 * Emits the exact native receive type and byte shape independently for each operation.
	 * @throws IOException if a matching request or expected JSON value is rejected
	 */
	@Test
	public void encodesSequenceSpecificControls() throws IOException
	{
		try (var scope = new DefaultToolingScope())
		{
			var messages = new CargoTokenMessages(scope, ATTEMPT, 9, 42);
			var read = (CargoTokenMessages.Read) messages.read(frame("transfer", ",\"sequence\":\"1\",\"operation\":\"read\""));
			var write = (CargoTokenMessages.Write) messages.read(frame("transfer", ",\"sequence\":\"2\",\"operation\":\"write\",\"byte\":165"));
			String common = "\"protocol\":\"codex-build-cargo-tokens\",\"version\":1,\"attempt\":\"" + ATTEMPT +
				"\",\"installation\":\"9\",\"pid\":\"42\",";
			var expectedRead = scope.getAllocationMessageMapper().readTree("{" + common +
				"\"type\":\"cargo-token-control-1\",\"sequence\":\"1\",\"operation\":\"read\",\"byte\":165}");
			var expectedWrite = scope.getAllocationMessageMapper().readTree("{" + common +
				"\"type\":\"cargo-token-control-2\",\"sequence\":\"2\",\"operation\":\"write\"}");
			requireThat(messages.grant(read, 165), "grant").isEqualTo(expectedRead, "expected");
			requireThat(messages.acknowledge(write), "acknowledgment").isEqualTo(expectedWrite, "expected");
			var changed = messages.grant(read, 0);
			changed.put("byte", 255);
			requireThat(messages.grant(read, 0).path("byte").intValue(), "independentGrant").isEqualTo(0);
		}
	}

	/**
	 * Preserves rejection and every supported fatal native failure as distinct typed events.
	 * @throws IOException if a matching failure or rejection is rejected
	 */
	@Test
	public void decodesRejectionsAndFatalFailures() throws IOException
	{
		try (var scope = new DefaultToolingScope())
		{
			var messages = new CargoTokenMessages(scope, ATTEMPT, 9, 42);
			requireThat(messages.read(frame("control-rejected", ",\"sequence\":\"1\",\"operation\":\"read\"")), "rejected")
				.isEqualTo(new CargoTokenMessages.Rejected(request(1), CargoTokenMessages.Operation.READ), "expected");
			String[] causes = {"Descriptor statx failed", "Descriptor type is unavailable", "FIFO inode is unavailable", "Jobserver transfer count differs from one"};
			for (int index = 0; index < causes.length; ++index)
			{
				var expected = new CargoTokenMessages.Failure(request(2), CargoTokenMessages.Operation.WRITE,
					CargoTokenMessages.FailureCause.values()[index]);
				requireThat(messages.read(frame("failure", ",\"sequence\":\"2\",\"operation\":\"write\",\"cause\":\"" + causes[index] + "\"")), "failure")
					.isEqualTo(expected, "expected");
			}
			requireThat(messages.read(frame("failure", ",\"cause\":\"Request sequence exhausted\"")), "exhausted")
				.isEqualTo(new CargoTokenMessages.Exhausted(), "expected");
		}
	}

	/**
	 * Rejects identities belonging to another installation and equivalent UUID spellings.
	 */
	@Test
	public void rejectsForeignFrameIdentities()
	{
		try (var scope = new DefaultToolingScope())
		{
			var messages = new CargoTokenMessages(scope, ATTEMPT, 9, 42);
			String ready = frame("ready", "");
			for (String invalid : new String[]{ready.replace(ATTEMPT.toString(), UUID.randomUUID().toString()),
				ready.replace(ATTEMPT.toString(), ATTEMPT.toString().toUpperCase(java.util.Locale.ROOT)),
				ready.replace("\"installation\":\"9\"", "\"installation\":\"10\""),
				ready.replace("\"pid\":\"42\"", "\"pid\":\"43\""),
				ready.replace("codex-build-cargo-tokens", "codex-build-allocation")})
				requireException(IOException.class, () -> messages.read(invalid));
		}
	}

	/**
	 * Rejects noncanonical decimal spellings and request identities outside their exact range.
	 */
	@Test
	public void rejectsNoncanonicalAndOutOfRangeIdentities()
	{
		try (var scope = new DefaultToolingScope())
		{
			var messages = new CargoTokenMessages(scope, ATTEMPT, 9, 42);
			String read = frame("transfer", ",\"sequence\":\"1\",\"operation\":\"read\"");
			for (String value : new String[]{"0", "-1", "+1", "01", " 1", "1.0", "١", "9223372036854775808"})
				requireException(IOException.class, () -> messages.read(read.replace("\"sequence\":\"1\"", "\"sequence\":\"" + value + "\"")));
			for (String invalid : new String[]{read.replace("\"installation\":\"9\"", "\"installation\":\"09\""),
				read.replace("\"pid\":\"42\"", "\"pid\":\"042\""),
				read.replace("\"pid\":\"42\"", "\"pid\":\"4294967296\""),
				read.replace("\"sequence\":\"1\"", "\"sequence\":1")})
				requireException(IOException.class, () -> messages.read(invalid));
		}
	}

	/**
	 * Rejects floating-point, boolean, string, null and oversized byte values without coercion.
	 */
	@Test
	public void rejectsNonintegerAndOutOfRangeBytes()
	{
		try (var scope = new DefaultToolingScope())
		{
			var messages = new CargoTokenMessages(scope, ATTEMPT, 9, 42);
			for (String value : new String[]{"-1", "256", "0.0", "255.0", "1.25", "true", "null", "\"255\"", "9223372036854775808"})
				requireException(IOException.class, () -> messages.read(frame("transfer", ",\"sequence\":\"1\",\"operation\":\"write\",\"byte\":" + value)));
		}
	}

	/**
	 * Rejects unsupported envelopes, versions, shapes, events and failure causes.
	 */
	@Test
	public void rejectsUnsupportedShapesAndVersions()
	{
		try (var scope = new DefaultToolingScope())
		{
			var messages = new CargoTokenMessages(scope, ATTEMPT, 9, 42);
			String ready = frame("ready", "");
			for (String invalid : new String[]{"", "null", "[]", "{}", ready.replace("\"send\"", "\"error\""),
				ready.replace("\"version\":1", "\"version\":1.0"), ready.replace("\"version\":1", "\"version\":true"),
				ready.replace("\"version\":1", "\"version\":4294967297"), ready.replace("\"version\":1", "\"version\":\"1\""),
				ready.replace("\"version\":1,", ""), frame("ready", ",\"extra\":null"), frame("future-event", ""),
				frame("transfer", ",\"sequence\":\"1\",\"operation\":\"read\",\"byte\":0"),
				frame("transfer", ",\"sequence\":\"1\",\"operation\":\"write\""),
				frame("transfer", ",\"sequence\":\"1\",\"operation\":\"future-operation\""),
				frame("failure", ",\"sequence\":\"1\",\"operation\":\"write\",\"cause\":\"future-cause\""),
				frame("failure", ",\"sequence\":\"1\",\"operation\":\"read\",\"cause\":\"Request sequence exhausted\"")})
				requireException(IOException.class, () -> messages.read(invalid));
		}
	}

	/**
	 * Rejects malformed, duplicate, trailing and over-limit JSON while retaining parser failures.
	 */
	@Test
	public void rejectsMalformedAndOverLimitFrames()
	{
		try (var scope = new DefaultToolingScope())
		{
			var messages = new CargoTokenMessages(scope, ATTEMPT, 9, 42);
			String ready = frame("ready", "");
			for (String invalid : new String[]{"{broken}", ready + " {}", ready.replace("\"version\":1", "\"version\":1,\"version\":1"),
				frame("ready", ",\"extra\":\"" + "x".repeat(129) + "\""),
				ready.replace("\"version\":1", "\"version\":123456789012345678901"),
				"{\"type\":\"send\",\"payload\":{\"one\":{\"two\":{\"three\":{}}}}}"})
			{
				var failure = requireException(IOException.class, () -> messages.read(invalid));
				requireThat(failure.getCause() instanceof JacksonException, "parserCauseRetained").isTrue();
			}
			requireException(IOException.class, () -> messages.read(" ".repeat(8193) + ready));
			requireException(NullPointerException.class, () -> messages.read(null));
		}
	}

	/**
	 * Rejects controls for another attempt, installation or process before producing a control value.
	 */
	@Test
	public void rejectsControlsForForeignRequests()
	{
		try (var scope = new DefaultToolingScope())
		{
			var messages = new CargoTokenMessages(scope, ATTEMPT, 9, 42);
			for (var foreign : new CargoTokenMessages.Request[]{new CargoTokenMessages.Request(UUID.randomUUID(), 9, 42, 1),
				new CargoTokenMessages.Request(ATTEMPT, 10, 42, 1), new CargoTokenMessages.Request(ATTEMPT, 9, 43, 1)})
			{
				requireException(IllegalArgumentException.class, () -> messages.grant(new CargoTokenMessages.Read(foreign), 0));
				requireException(IllegalArgumentException.class, () -> messages.acknowledge(new CargoTokenMessages.Write(foreign, 255)));
			}
			requireException(NullPointerException.class, () -> messages.grant(null, 0));
			requireException(NullPointerException.class, () -> messages.acknowledge(null));
			for (int value : new int[]{-1, 256})
				requireException(IllegalArgumentException.class, () -> messages.grant(new CargoTokenMessages.Read(request(1)), value));
		}
	}

	/**
	 * Establishes constructor invariants before any request can be used to encode controls.
	 */
	@Test
	public void validatesDirectRequestAndEventValues()
	{
		requireException(NullPointerException.class, () -> new CargoTokenMessages.Request(null, 9, 42, 1));
		for (long[] invalid : new long[][]{{0, 42, 1}, {9, 0, 1}, {9, 4294967296L, 1}, {9, 42, 0}})
			requireException(IllegalArgumentException.class, () -> new CargoTokenMessages.Request(ATTEMPT, invalid[0], invalid[1], invalid[2]));
		requireException(NullPointerException.class, () -> new CargoTokenMessages.Read(null));
		requireException(NullPointerException.class, () -> new CargoTokenMessages.Write(null, 0));
		for (int value : new int[]{-1, 256})
			requireException(IllegalArgumentException.class, () -> new CargoTokenMessages.Write(request(1), value));
		requireException(NullPointerException.class, () -> new CargoTokenMessages.Rejected(null, CargoTokenMessages.Operation.READ));
		requireException(NullPointerException.class, () -> new CargoTokenMessages.Rejected(request(1), null));
		requireException(NullPointerException.class, () -> new CargoTokenMessages.Failure(null, CargoTokenMessages.Operation.READ, CargoTokenMessages.FailureCause.UNSUPPORTED_COUNT));
		requireException(NullPointerException.class, () -> new CargoTokenMessages.Failure(request(1), null, CargoTokenMessages.FailureCause.UNSUPPORTED_COUNT));
		requireException(NullPointerException.class, () -> new CargoTokenMessages.Failure(request(1), CargoTokenMessages.Operation.READ, null));
	}

	/**
	 * Rejects invalid codec identities and scope acquisition after its owner closes.
	 */
	@Test
	public void validatesCodecIdentityAndScopeLifetime()
	{
		try (var scope = new DefaultToolingScope())
		{
			requireException(NullPointerException.class, () -> new CargoTokenMessages(scope, null, 9, 42));
			requireException(NullPointerException.class, () -> new CargoTokenMessages(null, ATTEMPT, 9, 42));
			requireException(IllegalArgumentException.class, () -> new CargoTokenMessages(scope, ATTEMPT, 0, 42));
			requireException(IllegalArgumentException.class, () -> new CargoTokenMessages(scope, ATTEMPT, 9, 0));
			requireException(IllegalArgumentException.class, () -> new CargoTokenMessages(scope, ATTEMPT, 9, 4294967296L));
		}
		var closed = new DefaultToolingScope();
		closed.close();
		requireException(IllegalStateException.class, () -> new CargoTokenMessages(closed, ATTEMPT, 9, 42));
	}

	/**
	 * Creates a deterministic transfer identity without native acquisition.
	 * @param sequence positive request sequence
	 * @return identity belonging to the codec fixture
	 */
	private static CargoTokenMessages.Request request(long sequence)
	{
		return new CargoTokenMessages.Request(ATTEMPT, 9, 42, sequence);
	}

	/**
	 * Builds a native protocol fixture without using the codec under test.
	 * @param event native event name
	 * @param fields event-specific JSON members including their leading comma
	 * @return complete Frida send envelope
	 */
	private static String frame(String event, String fields)
	{
		return "{\"type\":\"send\",\"payload\":{\"protocol\":\"codex-build-cargo-tokens\",\"version\":1," +
			"\"attempt\":\"" + ATTEMPT + "\",\"installation\":\"9\",\"pid\":\"42\",\"event\":\"" + event + "\"" + fields + "}}";
	}
}
