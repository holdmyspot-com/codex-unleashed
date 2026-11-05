package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Decodes token events and encodes controls for one independently verified native installation.
 * <p>
 * The owner verifies process birth, executable and ownership before constructing this codec. Installation generations
 * must never be reused within an attempt, including across exec. Decoding authenticates no process and transfers no
 * token. The owner rejects duplicate sequences and completes ledger effects before posting controls. Failure,
 * rejection and exhaustion events require the owner to stop transfers and terminate the affected workload.
 * <p>
 * Version 1 accepts exact Frida send envelopes in the {@code codex-build-cargo-tokens} namespace. Native identities
 * use canonical positive decimal strings. Bytes are JSON integers from 0 through 255. Frames have at most 8192 Java
 * characters, strings at most 128 characters, numeric tokens at most 20 characters and nesting at most four.
 * Duplicate members and trailing values are rejected. Concurrent calls are supported while the caller's scope is open.
 */
public final class CargoTokenMessages
{
	/** Maximum copied frame length accepted before parsing, in Java characters. */
	private static final int MAX_MESSAGE_CHARACTERS = 8192;
	/** Largest process identity emitted by the native unsigned-32-bit PID API. */
	private static final long MAX_PID = 4294967295L;
	/** Native token namespace, independent of allocation events. */
	private static final String PROTOCOL = "codex-build-cargo-tokens";
	/** Wire version emitted by the accepted native script. */
	private static final int VERSION = 1;
	/** Exact Frida send-envelope members admitted by this codec. */
	private static final Set<String> ENVELOPE_FIELDS = Set.of("type", "payload");
	/** Exact readiness members required by version 1. */
	private static final Set<String> READY_FIELDS = Set.of("protocol", "version", "attempt", "installation", "pid", "event");
	/** Exact members for a rejected control or a transfer failure's identity. */
	private static final Set<String> REQUEST_FIELDS = Set.of("protocol", "version", "attempt", "installation", "pid", "event", "sequence", "operation");
	/** Exact read-request members, including whether the reading descriptor waits for a token. */
	private static final Set<String> READ_FIELDS = Set.of("protocol", "version", "attempt", "installation", "pid", "event", "sequence", "operation", "blocking");
	/** Exact write-request members, including the byte being returned. */
	private static final Set<String> WRITE_FIELDS = Set.of("protocol", "version", "attempt", "installation", "pid", "event", "sequence", "operation", "byte");
	/** Exact members for a failure associated with one native transfer. */
	private static final Set<String> FAILURE_FIELDS = Set.of("protocol", "version", "attempt", "installation", "pid", "event", "sequence", "operation", "cause");
	/** Exhaustion has no new request sequence because that range is exhausted. */
	private static final Set<String> EXHAUSTED_FIELDS = Set.of("protocol", "version", "attempt", "installation", "pid", "event", "cause");
	/** Scope-owned parser; token and allocation frames require the same bounded JSON configuration. */
	private final JsonMapper json;
	/** Workload identity fixed for all operations on this codec. */
	private final UUID attempt;
	/** Positive generation assigned by the authenticated image owner. */
	private final long installation;
	/** Verified process identity within the unsigned-32-bit range. */
	private final long pid;

	/**
	 * Retains one installation's identities and borrows its scope's immutable bounded parser.
	 * @param scope parser owner retained by the caller through every operation
	 * @param attempt nonnull workload attempt identity
	 * @param installation positive script generation never reused within the attempt
	 * @param pid verified process identity from 1 through 4294967295
	 * @throws NullPointerException if {@code scope} or {@code attempt} is null
	 * @throws IllegalArgumentException if {@code installation} or {@code pid} is outside its range
	 * @throws IllegalStateException if {@code scope} is closed
	 */
	public CargoTokenMessages(ToolingScope scope, UUID attempt, long installation, long pid)
	{
		validateInstallation(attempt, installation, pid);
		this.json = Objects.requireNonNull(scope, "scope may not be null").getAllocationMessageMapper();
		this.attempt = attempt;
		this.installation = installation;
		this.pid = pid;
	}

	/**
	 * Decodes a matching frame without advancing request state or performing native I/O.
	 * @param message complete copied JSON frame from this installation's connection
	 * @return readiness, read, write, rejected-control, transfer-failure or exhaustion event
	 * @throws NullPointerException if {@code message} is null
	 * @throws IOException if grammar, limits, shape, identities, version or values are invalid; parser causes are retained
	 */
	public Event read(String message) throws IOException
	{
		Objects.requireNonNull(message, "message may not be null");
		if (message.length() > MAX_MESSAGE_CHARACTERS)
			throw new IOException("Cargo token frame exceeds " + MAX_MESSAGE_CHARACTERS + " characters");
		JsonNode envelope;
		try
		{
			envelope = json.readTree(message);
		}
		catch (JacksonException cause)
		{
			throw new IOException("Cannot parse native Cargo token frame", cause);
		}
		requireFields(envelope, ENVELOPE_FIELDS);
		if (!text(envelope, "type").equals("send"))
			throw new IOException("Cargo token frame requires a Frida send envelope");
		JsonNode body = envelope.path("payload");
		if (!body.isObject())
			throw new IOException("Cargo token payload must be an object");
		JsonNode version = body.path("version");
		boolean supportedVersion = version.isIntegralNumber() && version.bigIntegerValue().equals(BigInteger.valueOf(VERSION));
		if (!supportedVersion)
			throw new IOException("Cargo token payload requires integer version " + VERSION);
		if (!text(body, "protocol").equals(PROTOCOL))
			throw new IOException("Cargo token payload has a different protocol");
		if (!text(body, "attempt").equals(attempt.toString()))
			throw new IOException("Cargo token payload belongs to another attempt");
		if (positive(body, "installation", Long.MAX_VALUE) != installation)
			throw new IOException("Cargo token payload belongs to another installation");
		if (positive(body, "pid", MAX_PID) != pid)
			throw new IOException("Cargo token payload belongs to another process");

		return switch (text(body, "event"))
		{
			case "ready" ->
			{
				requireFields(body, READY_FIELDS);
				yield new Ready();
			}
			case "transfer" -> switch (operation(body))
			{
				case READ ->
				{
					requireFields(body, READ_FIELDS);
					yield new Read(request(body), blocking(body));
				}
				case WRITE ->
				{
					requireFields(body, WRITE_FIELDS);
					yield new Write(request(body), byteValue(body));
				}
			};
			case "control-rejected" ->
			{
				requireFields(body, REQUEST_FIELDS);
				yield new Rejected(request(body), operation(body));
			}
			case "failure" -> failure(body);
			default -> throw new IOException("Cargo token payload has an unsupported event");
		};
	}

	/**
	 * Creates a grant after the owner has recorded the borrowed byte in its ledger.
	 * @param request decoded read belonging to this codec's attempt, installation and process
	 * @param value exact opaque byte from 0 through 255
	 * @return fresh mutable control with this request's sequence-specific receive type
	 * @throws NullPointerException if {@code request} is null
	 * @throws IllegalArgumentException if {@code request} belongs elsewhere or {@code value} is outside its byte range
	 */
	public ObjectNode grant(Read request, int value)
	{
		Objects.requireNonNull(request, "request may not be null");
		validateByte(value);
		return control(request.request(), Operation.READ).put("byte", value);
	}

	/**
	 * Creates the answer to a nonblocking read that finds no token, which the native call reports as {@code EAGAIN}.
	 * @param request decoded nonblocking read belonging to this codec's attempt, installation and process
	 * @return fresh mutable control with this request's sequence-specific receive type and no byte member
	 * @throws NullPointerException if {@code request} is null
	 * @throws IllegalArgumentException if {@code request} belongs elsewhere or waits for a token
	 */
	public ObjectNode decline(Read request)
	{
		Objects.requireNonNull(request, "request may not be null");
		if (request.blocking())
			throw new IllegalArgumentException("A blocking Cargo token read waits for a grant");
		return control(request.request(), Operation.READ);
	}

	/**
	 * Creates an acknowledgment after the owner has completed and recorded the actual FIFO return.
	 * @param request decoded write belonging to this codec's attempt, installation and process
	 * @return fresh mutable acknowledgment without a byte member
	 * @throws NullPointerException if {@code request} is null
	 * @throws IllegalArgumentException if {@code request} belongs to another attempt, installation or process
	 */
	public ObjectNode acknowledge(Write request)
	{
		Objects.requireNonNull(request, "request may not be null");
		return control(request.request(), Operation.WRITE);
	}

	/**
	 * Builds a control without posting it or changing request state.
	 * @param request native transfer identity
	 * @param operation transfer being authorized
	 * @return independent control containing the fixed installation and request identities
	 * @throws IllegalArgumentException if {@code request} belongs to another attempt, installation or process
	 */
	private ObjectNode control(Request request, Operation operation)
	{
		boolean matchingIdentity = request.attempt().equals(attempt) && request.installation() == installation && request.pid() == pid;
		if (!matchingIdentity)
			throw new IllegalArgumentException("Cargo token control belongs to another attempt, installation or process");
		return json.createObjectNode().put("type", "cargo-token-control-" + request.sequence()).put("protocol", PROTOCOL)
			.put("version", VERSION).put("attempt", attempt.toString()).put("installation", Long.toString(installation))
			.put("pid", Long.toString(pid)).put("sequence", Long.toString(request.sequence()))
			.put("operation", operation.name().toLowerCase(java.util.Locale.ROOT));
	}

	/**
	 * Reads a sequence after the frame's installation has been matched.
	 * @param body identity-checked payload
	 * @return full request identity usable only by the matching codec
	 * @throws IOException if the sequence is missing, noncanonical or outside 1 through 9223372036854775807
	 */
	private Request request(JsonNode body) throws IOException
	{
		return new Request(attempt, installation, pid, positive(body, "sequence", Long.MAX_VALUE));
	}

	/**
	 * Decodes failures that either identify a transfer or report sequence exhaustion.
	 * @param body identity-checked native failure payload
	 * @return event requiring owner termination, without performing that termination
	 * @throws IOException if the failure cause, fields or native values are unsupported
	 */
	private Event failure(JsonNode body) throws IOException
	{
		String cause = text(body, "cause");
		return switch (cause)
		{
			case "Request sequence exhausted" ->
			{
				requireFields(body, EXHAUSTED_FIELDS);
				yield new Exhausted();
			}
			default ->
			{
				requireFields(body, FAILURE_FIELDS);
				FailureCause reason = switch (cause)
				{
					case "Descriptor statx failed" -> FailureCause.DESCRIPTOR_STATX_FAILED;
					case "Descriptor type is unavailable" -> FailureCause.DESCRIPTOR_TYPE_UNAVAILABLE;
					case "FIFO inode is unavailable" -> FailureCause.FIFO_INODE_UNAVAILABLE;
					case "Descriptor flags are unavailable" -> FailureCause.DESCRIPTOR_FLAGS_UNAVAILABLE;
					case "Jobserver transfer count differs from one" -> FailureCause.UNSUPPORTED_COUNT;
					default -> throw new IOException("Cargo token failure has an unsupported cause");
				};
				yield new Failure(request(body), operation(body), reason);
			}
		};
	}

	/**
	 * Converts the native operation spelling to a closed domain choice.
	 * @param body native request payload
	 * @return read or write operation
	 * @throws IOException if the operation is absent, has another type or is unsupported
	 */
	private static Operation operation(JsonNode body) throws IOException
	{
		return switch (text(body, "operation"))
		{
			case "read" -> Operation.READ;
			case "write" -> Operation.WRITE;
			default -> throw new IOException("Cargo token operation must be read or write");
		};
	}

	/**
	 * Requires the exact members of one token protocol shape.
	 * @param node decoded value, including null when parsing produced no value
	 * @param expected exact member names admitted by that shape
	 * @throws IOException if the value is not an object or has missing or unexpected members
	 */
	private static void requireFields(JsonNode node, Set<String> expected) throws IOException
	{
		boolean validObject = node != null && node.isObject();
		if (!validObject)
			throw new IOException("Cargo token message requires an object");
		if (!Set.copyOf(node.propertyNames()).equals(expected))
			throw new IOException("Cargo token object has missing or unexpected members: " + node.propertyNames());
	}

	/**
	 * Reads a required string without coercing other JSON values.
	 * @param body decoded object
	 * @param field required string member
	 * @return decoded text with spelling and case preserved
	 * @throws IOException if the member is absent or is not a string
	 */
	private static String text(JsonNode body, String field) throws IOException
	{
		JsonNode value = body.path(field);
		if (!value.isString())
			throw new IOException("Cargo token member must be a string: " + field);
		return value.asString();
	}

	/**
	 * Parses a positive identity using the JDK parser, then compares its canonical spelling.
	 * @param body decoded object
	 * @param field required canonical decimal-string member
	 * @param maximum inclusive identity bound
	 * @return exact positive identity within the bound
	 * @throws IOException if the member is absent, noncanonical or outside its range
	 */
	private static long positive(JsonNode body, String field, long maximum) throws IOException
	{
		String raw = text(body, field);
		BigInteger value;
		try
		{
			value = new BigInteger(raw);
		}
		catch (NumberFormatException cause)
		{
			throw new IOException("Cargo token member is not a decimal integer: " + field, cause);
		}
		if (value.signum() <= 0 || value.compareTo(BigInteger.valueOf(maximum)) > 0 || !value.toString().equals(raw))
			throw new IOException("Cargo token member requires a canonical decimal string from 1 through " + maximum + ": " + field);
		return value.longValueExact();
	}

	/**
	 * Reads whether a read's descriptor waits for a token.
	 * @param body decoded read request
	 * @return true if the native call waits for a grant, false if it fails with {@code EAGAIN} when none is free
	 * @throws IOException if the member is not a JSON boolean
	 */
	private static boolean blocking(JsonNode body) throws IOException
	{
		JsonNode value = body.path("blocking");
		if (!value.isBoolean())
			throw new IOException("Cargo token blocking member must be a JSON boolean");
		return value.booleanValue();
	}

	/**
	 * Distinguishes a JSON integer byte from an equivalent floating-point token.
	 * @param body decoded write request
	 * @return exact opaque byte from 0 through 255
	 * @throws IOException if the byte is absent, nonintegral or outside its range
	 */
	private static int byteValue(JsonNode body) throws IOException
	{
		JsonNode value = body.path("byte");
		if (!value.isIntegralNumber())
			throw new IOException("Cargo token byte must be a JSON integer");
		BigInteger integer = value.bigIntegerValue();
		if (integer.signum() < 0 || integer.bitLength() > 8)
			throw new IOException("Cargo token byte must be from 0 through 255");
		return integer.intValueExact();
	}

	/**
	 * Validates identities shared by the codec and its direct request values.
	 * @param attempt nonnull workload identity
	 * @param installation positive script generation
	 * @param pid process identity from 1 through 4294967295
	 * @throws NullPointerException if {@code attempt} is null
	 * @throws IllegalArgumentException if {@code installation} or {@code pid} is outside its range
	 */
	private static void validateInstallation(UUID attempt, long installation, long pid)
	{
		Objects.requireNonNull(attempt, "attempt may not be null");
		if (installation <= 0)
			throw new IllegalArgumentException("Cargo token installation must be positive");
		if (pid <= 0 || pid > MAX_PID)
			throw new IllegalArgumentException("Cargo token process must be a positive unsigned-32-bit integer");
	}

	/**
	 * Validates an opaque byte supplied through a direct Java value boundary.
	 * @param value byte magnitude from 0 through 255
	 * @throws IllegalArgumentException if {@code value} is outside that range
	 */
	private static void validateByte(int value)
	{
		if (value < 0 || value > 255)
			throw new IllegalArgumentException("Cargo token byte must be from 0 through 255");
	}

	/**
	 * Represents values only; the owner controls ordering, accounting and termination.
	 */
	sealed interface Event permits Ready, Read, Write, Rejected, Failure, Exhausted
	{
	}

	/**
	 * Reports matching readiness without establishing process ownership.
	 */
	public record Ready() implements Event
	{
	}

	/**
	 * Identifies one transfer without independently authenticating its source.
	 * @param attempt workload identity
	 * @param installation positive script generation unique within the attempt
	 * @param pid positive unsigned-32-bit process identity
	 * @param sequence positive signed-long sequence unique within the installation
	 */
	public record Request(UUID attempt, long installation, long pid, long sequence)
	{
		/**
		 * Establishes the identity invariants needed by control encoding.
		 * @param attempt workload identity
		 * @param installation positive script generation
		 * @param pid process identity from 1 through 4294967295
		 * @param sequence request sequence from 1 through 9223372036854775807
		 * @throws NullPointerException if {@code attempt} is null
		 * @throws IllegalArgumentException if an identity is outside its range
		 */
		public Request
		{
			validateInstallation(attempt, installation, pid);
			if (sequence <= 0)
				throw new IllegalArgumentException("Cargo token sequence must be positive");
		}
	}

	/**
	 * Requests one byte from the owner's ledger-backed transport.
	 * @param request  native transfer identity
	 * @param blocking true if the native call waits for a grant, false if it expects an immediate grant or decline
	 */
	public record Read(Request request, boolean blocking) implements Event
	{
		/**
		 * Retains an identity without borrowing a byte.
		 * @param request  native transfer identity
		 * @param blocking true if the native call waits for a grant, false if it expects an immediate grant or decline
		 * @throws NullPointerException if {@code request} is null
		 */
		public Read
		{
			Objects.requireNonNull(request, "request may not be null");
		}
	}

	/**
	 * Requests return of one exact opaque byte through the owner's transport.
	 * @param request native transfer identity
	 * @param value byte magnitude from 0 through 255
	 */
	public record Write(Request request, int value) implements Event
	{
		/**
		 * Retains a valid request and byte without performing the return.
		 * @param request native transfer identity
		 * @param value byte magnitude from 0 through 255
		 * @throws NullPointerException if {@code request} is null
		 * @throws IllegalArgumentException if {@code value} is outside its byte range
		 */
		public Write
		{
			Objects.requireNonNull(request, "request may not be null");
			validateByte(value);
		}
	}

	/**
	 * Reports a rejected control while the native operation remains parked.
	 * @param request parked transfer
	 * @param operation read or write whose control was rejected
	 */
	public record Rejected(Request request, Operation operation) implements Event
	{
		/**
		 * Retains rejection values without authorizing a transfer.
		 * @param request parked transfer
		 * @param operation rejected control's operation
		 * @throws NullPointerException if {@code request} or {@code operation} is null
		 */
		public Rejected
		{
			Objects.requireNonNull(request, "request may not be null");
			Objects.requireNonNull(operation, "operation may not be null");
		}
	}

	/**
	 * Reports a native failure requiring owner-controlled termination.
	 * @param request failed transfer
	 * @param operation failed read or write
	 * @param cause supported native failure category
	 */
	public record Failure(Request request, Operation operation, FailureCause cause) implements Event
	{
		/**
		 * Retains failure values without native I/O or termination.
		 * @param request failed transfer
		 * @param operation failed read or write
		 * @param cause supported native failure category
		 * @throws NullPointerException if {@code request}, {@code operation} or {@code cause} is null
		 */
		public Failure
		{
			Objects.requireNonNull(request, "request may not be null");
			Objects.requireNonNull(operation, "operation may not be null");
			Objects.requireNonNull(cause, "cause may not be null");
		}
	}

	/**
	 * Reports exhaustion; no further request can be granted in this installation.
	 */
	public record Exhausted() implements Event
	{
	}

	/**
	 * Identifies the two supported one-byte transfer operations.
	 */
	public enum Operation
	{
		/** Borrow one opaque byte. */
		READ,
		/** Return one opaque byte. */
		WRITE
	}

	/**
	 * Identifies native failures reported before untrackable I/O proceeds.
	 */
	public enum FailureCause
	{
		/** Descriptor metadata could not be read. */
		DESCRIPTOR_STATX_FAILED,
		/** Descriptor metadata omitted its type. */
		DESCRIPTOR_TYPE_UNAVAILABLE,
		/** FIFO metadata omitted its inode. */
		FIFO_INODE_UNAVAILABLE,
		/** The status flags of a read's descriptor could not be read. */
		DESCRIPTOR_FLAGS_UNAVAILABLE,
		/** A selected transfer requested more than one byte. */
		UNSUPPORTED_COUNT
	}
}
