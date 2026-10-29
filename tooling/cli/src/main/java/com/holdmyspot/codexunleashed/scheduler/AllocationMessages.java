package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Encodes permissions and decodes allocation events for one verified native script installation.
 * <p>
 * The adapter must verify process ownership before constructing this codec and assign an installation number that is
 * never reused within the attempt, including after exec. Native messages must match all three configured identities.
 * This resource-free codec performs no native effect and retains no acknowledgement state. Its caller must match
 * acknowledgements to posted permissions and stop grants after a decoding or transport failure.
 * <p>
 * Version 1 uses a Frida send envelope with an object payload. Native integers are canonical decimal strings;
 * allocation sizes and thread identities preserve unsigned 64-bit magnitudes. A message has at most 8192 Java
 * characters, strings at most 128 characters, numeric tokens at most 20 characters and object nesting at most four.
 * Duplicate or unexpected fields, trailing values and unsupported versions are rejected.
 */
final class AllocationMessages
{
	/** Maximum copied-message length accepted before JSON parsing. */
	private static final int MAX_MESSAGE_CHARACTERS = 8192;
	/** Wire version accepted for native payloads and emitted in permission controls. */
	private static final int VERSION = 1;
	/** Maximum unsigned-32-bit process identity or native error magnitude. */
	private static final long MAX_UNSIGNED_INT = 4294967295L;
	/** Exact native payload namespace, distinct from the Frida transport envelope. */
	private static final String PROTOCOL = "codex-build-allocation";
	/** Frida send-envelope members accepted by this allocation-only codec. */
	private static final Set<String> ENVELOPE_FIELDS = Set.of("type", "payload");
	/** Readiness members shared by every version-1 payload. */
	private static final Set<String> READY_FIELDS = Set.of("protocol", "version", "attempt", "installation", "pid", "event");
	/** Blocked-event members, including native diagnostic values. */
	private static final Set<String> BLOCKED_FIELDS = Set.of("protocol", "version", "attempt", "installation", "pid", "event",
		"wait", "thread", "bytes", "error");
	/** Permission acknowledgement members; acknowledgement does not imply allocation success. */
	private static final Set<String> GRANTED_FIELDS = Set.of("protocol", "version", "attempt", "installation", "pid", "event",
		"wait", "status");
	/** Immutable parser enforcing JSON grammar, duplicate rejection, trailing-token rejection and bounded parsing. */
	private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(128)
			.maxNumberLength(20).maxDocumentLength(MAX_MESSAGE_CHARACTERS).build()).build())
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

	/** Canonical attempt identity supplied by the owning adapter. */
	private final String attempt;
	/** Positive generation reserved for this script installation. */
	private final long installation;
	/** Verified positive unsigned-32-bit process identity. */
	private final long pid;

	/**
	 * Retains one already verified installation's identities without acquiring resources.
	 * @param attempt nonnull identity unique to this workload attempt
	 * @param installation positive generation never reused within the attempt
	 * @param pid verified process identity in the range 1 through 4294967295
	 * @throws NullPointerException if {@code attempt} is null
	 * @throws IllegalArgumentException if the installation or process identity is outside its range
	 */
	AllocationMessages(UUID attempt, long installation, long pid)
	{
		Objects.requireNonNull(attempt, "attempt may not be null");
		if (installation <= 0)
			throw new IllegalArgumentException("Allocation installation identity must be positive");
		if (pid <= 0 || pid > MAX_UNSIGNED_INT)
			throw new IllegalArgumentException("Allocation process identity must be a positive unsigned-32-bit integer");
		this.attempt = attempt.toString();
		this.installation = installation;
		this.pid = pid;
	}

	/**
	 * Decodes one copied Frida message without changing grant authority or queue state.
	 * @param message complete JSON text from this installation's script receiver
	 * @return matching readiness, allocation denial or permission acknowledgement
	 * @throws NullPointerException if {@code message} is null
	 * @throws IOException if JSON, shape, identities, version or native values are invalid; parser failures retain causes
	 */
	Event read(String message) throws IOException
	{
		Objects.requireNonNull(message, "message may not be null");
		if (message.length() > MAX_MESSAGE_CHARACTERS)
			throw new IOException("Allocation message exceeds " + MAX_MESSAGE_CHARACTERS + " characters");
		JsonNode envelope;
		try
		{
			envelope = JSON.readTree(message);
		}
		catch (JacksonException cause)
		{
			throw new IOException("Cannot parse native allocation message", cause);
		}
		requireFields(envelope, ENVELOPE_FIELDS, "envelope");
		if (text(envelope, "type").equals("send") == false)
			throw new IOException("Allocation message requires a Frida send envelope");
		JsonNode body = envelope.get("payload");
		if (body.isObject() == false)
			throw new IOException("Allocation message payload must be an object");
		JsonNode version = body.path("version");
		boolean supportedVersion = version.isIntegralNumber() && version.bigIntegerValue().equals(BigInteger.valueOf(VERSION));
		if (supportedVersion == false)
			throw new IOException("Allocation payload requires integer version " + VERSION);
		if (text(body, "protocol").equals(PROTOCOL) == false)
			throw new IOException("Allocation payload has a different protocol");
		if (text(body, "attempt").equals(attempt) == false)
			throw new IOException("Allocation payload belongs to another attempt");
		if (unsigned(body, "installation", 63, false).longValueExact() != installation)
			throw new IOException("Allocation payload belongs to another script installation");
		if (unsigned(body, "pid", 32, false).longValueExact() != pid)
			throw new IOException("Allocation payload belongs to another process");

		return switch (text(body, "event"))
		{
			case "ready" ->
			{
				requireFields(body, READY_FIELDS, "ready payload");
				yield new Ready();
			}
			case "blocked" ->
			{
				requireFields(body, BLOCKED_FIELDS, "blocked payload");
				yield new Blocked(identity(body), unsigned(body, "thread", 64, false),
					unsigned(body, "bytes", 64, false), unsigned(body, "error", 32, true).longValueExact());
			}
			case "granted" ->
			{
				requireFields(body, GRANTED_FIELDS, "granted payload");
				GrantStatus status = switch (text(body, "status"))
				{
					case "accepted" -> GrantStatus.ACCEPTED;
					case "duplicate" -> GrantStatus.DUPLICATE;
					case "unknown" -> GrantStatus.UNKNOWN;
					default -> throw new IOException("Allocation acknowledgement has an unsupported status");
				};
				yield new Granted(identity(body), status);
			}
			default -> throw new IOException("Allocation payload has an unsupported event");
		};
	}

	/**
	 * Creates a fresh control value; the caller owns posting and matching its acknowledgement.
	 * @param wait positive native wait identity belonging to this installation
	 * @return mutable JSON control value independent of every earlier returned value
	 * @throws NullPointerException if {@code wait} is null
	 * @throws IllegalArgumentException if the wait belongs to another installation
	 */
	ObjectNode permission(AllocationWaits.Wait wait)
	{
		Objects.requireNonNull(wait, "wait may not be null");
		if (wait.installation() != installation)
			throw new IllegalArgumentException("Allocation permission belongs to another script installation");
		return JSON.createObjectNode().put("type", "allocation-control").put("version", VERSION).put("attempt", attempt)
			.put("installation", Long.toString(installation)).put("pid", Long.toString(pid)).put("operation", "grant")
			.put("wait", Long.toString(wait.sequence()));
	}

	/**
	 * Reads a positive native wait sequence only after its installation was matched.
	 * @param body identity-checked payload
	 * @return native wait within this codec's installation
	 * @throws IOException if the wait sequence is missing, noncanonical or outside the positive signed-long range
	 */
	private AllocationWaits.Wait identity(JsonNode body) throws IOException
	{
		return new AllocationWaits.Wait(installation, unsigned(body, "wait", 63, false).longValueExact());
	}

	/**
	 * Requires an object with exactly the supported members, including members whose value is null.
	 * @param node parsed value, or null when parsing produced no value
	 * @param expected exact supported member names
	 * @param context message location used in rejection diagnostics
	 * @throws IOException if the object shape differs
	 */
	private static void requireFields(JsonNode node, Set<String> expected, String context) throws IOException
	{
		boolean supportedObject = node != null && node.isObject();
		if (supportedObject == false)
			throw new IOException("Allocation " + context + " must be an object");
		if (Set.copyOf(node.propertyNames()).equals(expected) == false)
			throw new IOException("Allocation " + context + " has missing or unexpected members: " + node.propertyNames());
	}

	/**
	 * Reads a JSON string without coercing numeric, boolean, absent or null values.
	 * @param object parsed object
	 * @param field required string member
	 * @return decoded string content with its spelling and case preserved
	 * @throws IOException if the member is absent or is not a string
	 */
	private static String text(JsonNode object, String field) throws IOException
	{
		JsonNode value = object.path(field);
		if (value.isString() == false)
			throw new IOException("Allocation member must be a string: " + field);
		return value.asString();
	}

	/**
	 * Parses an unsigned native magnitude and compares its canonical decimal representation with the decoded string.
	 * @param object parsed payload
	 * @param field required decimal-string member
	 * @param bits maximum unsigned magnitude width
	 * @param allowZero whether zero is permitted for this member
	 * @return exact nonnegative magnitude within the field's range
	 * @throws IOException if the member is missing, noncanonical or outside its unsigned range
	 */
	private static BigInteger unsigned(JsonNode object, String field, int bits, boolean allowZero) throws IOException
	{
		String raw = text(object, field);
		BigInteger value;
		try
		{
			value = new BigInteger(raw);
		}
		catch (NumberFormatException cause)
		{
			throw new IOException("Allocation member is not a decimal integer: " + field, cause);
		}
		boolean allowedSign = allowZero ? value.signum() >= 0 : value.signum() > 0;
		boolean validMagnitude = allowedSign && value.bitLength() <= bits;
		if (validMagnitude == false || value.toString().equals(raw) == false)
			throw new IOException("Allocation member requires a canonical unsigned-" + bits + "-bit decimal string: " + field);
		return value;
	}

	/** Decoded values only; callers still own readiness, acknowledgement and queue transitions. */
	sealed interface Event permits Ready, Blocked, Granted
	{
	}

	/** Readiness for this codec's identity, without independently establishing process ownership. */
	record Ready() implements Event
	{
	}

	/**
	 * Preserves one actual native allocation denial and its diagnostic magnitudes.
	 * @param identity native wait unique within its installation
	 * @param thread positive unsigned-64-bit native thread identity
	 * @param bytes positive unsigned-64-bit allocation size
	 * @param error captured unsigned-32-bit native error value, including zero
	 */
	record Blocked(AllocationWaits.Wait identity, BigInteger thread, BigInteger bytes, long error) implements Event
	{
		/**
		 * Validates value-only construction without acquiring resources.
		 * @throws NullPointerException if an identity or magnitude is null
		 * @throws IllegalArgumentException if a magnitude is outside its declared range
		 */
		Blocked
		{
			Objects.requireNonNull(identity, "identity may not be null");
			Objects.requireNonNull(thread, "thread may not be null");
			Objects.requireNonNull(bytes, "bytes may not be null");
			boolean validThread = thread.signum() > 0 && thread.bitLength() <= 64;
			boolean validSize = bytes.signum() > 0 && bytes.bitLength() <= 64;
			if (validThread == false || validSize == false)
				throw new IllegalArgumentException("Allocation denial requires positive unsigned-64-bit thread and size values");
			if (error < 0 || error > MAX_UNSIGNED_INT)
				throw new IllegalArgumentException("Allocation denial requires an unsigned-32-bit native error value");
		}
	}

	/**
	 * Acknowledges a permission lookup; this does not say whether the allocation later succeeds.
	 * @param identity native wait addressed by the control
	 * @param status native permission result, including an unknown wait requiring caller recovery
	 */
	record Granted(AllocationWaits.Wait identity, GrantStatus status) implements Event
	{
		/**
		 * Validates value-only acknowledgement construction.
		 * @throws NullPointerException if either component is null
		 */
		Granted
		{
			Objects.requireNonNull(identity, "identity may not be null");
			Objects.requireNonNull(status, "status may not be null");
		}
	}

	/** Distinct native permission outcomes; none independently establishes allocation completion. */
	enum GrantStatus
	{
		/** This permission changed the matching pending wait to granted. */
		ACCEPTED,
		/** This wait already received permission; this control did not repeat the grant. */
		DUPLICATE,
		/** The native registry did not recognize this wait; the caller must not accept continuation. */
		UNKNOWN
	}
}
