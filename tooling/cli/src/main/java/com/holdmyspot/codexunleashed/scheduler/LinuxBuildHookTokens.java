package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Translates one admitted build hook client's jobserver frames to and from the messages that {@link CargoTokenHook}
 * exchanges with an instrumented process.
 */
public final class LinuxBuildHookTokens
{
	/**
	 * The protocol that {@link CargoTokenMessages} reads.
	 */
	private static final String PROTOCOL = "codex-build-cargo-tokens";
	/**
	 * The prefix of a token control's type, followed by the transfer's sequence.
	 */
	private static final String CONTROL_TYPE = "cargo-token-control-";
	/**
	 * The mapper that writes the messages.
	 */
	private final JsonMapper json;
	/**
	 * The attempt that admitted the client.
	 */
	private final String attempt;
	/**
	 * The identifier assigned to the client's installation.
	 */
	private final long installation;
	/**
	 * The client's process ID.
	 */
	private final long pid;

	/**
	 * Creates a translator for one admitted client.
	 * @param scope        supplies the allocation message mapper; the caller keeps ownership
	 * @param attempt      the attempt that admitted the client
	 * @param installation the identifier sent in the client's {@code welcome}
	 * @param pid          the client's process ID
	 * @throws NullPointerException if {@code scope} or {@code attempt} is null
	 */
	public LinuxBuildHookTokens(ToolingScope scope, UUID attempt, long installation, long pid)
	{
		this.json = Objects.requireNonNull(scope, "scope").getAllocationMessageMapper();
		this.attempt = Objects.requireNonNull(attempt, "attempt").toString();
		this.installation = installation;
		this.pid = pid;
	}

	/**
	 * Returns the message that reports the client ready, which the library implies by completing its handshake.
	 * @return the {@code ready} message
	 */
	public String ready()
	{
		return envelope(payload("ready"));
	}

	/**
	 * Translates a client frame that concerns the jobserver.
	 * @param fields the decoded frame
	 * @return the matching message, or empty if the frame does not concern the jobserver
	 * @throws IOException if a {@code transfer}, {@code failure} or {@code exhausted} frame has the wrong number of
	 *   fields
	 */
	public Optional<String> transfer(List<String> fields) throws IOException
	{
		switch (fields.getFirst())
		{
			case "transfer" ->
			{
				requireCount(fields, 4);
				ObjectNode payload = payload("transfer").put("sequence", fields.get(1)).put("operation", fields.get(2));
				if (fields.get(2).equals("write"))
					payload.put("byte", unsignedByte(fields.get(3)));
				else
					payload.put("blocking", blocking(fields.get(3)));
				return Optional.of(envelope(payload));
			}
			case "failure" ->
			{
				requireCount(fields, 4);
				return Optional.of(envelope(payload("failure").put("sequence", fields.get(1)).
					put("operation", fields.get(2)).put("cause", fields.get(3))));
			}
			case "exhausted" ->
			{
				requireCount(fields, 1);
				return Optional.of(envelope(payload("failure").put("cause", "Request sequence exhausted")));
			}
			default ->
			{
				return Optional.empty();
			}
		}
	}

	/**
	 * Parses whether a read transfer's descriptor waits for a token.
	 * @param value the field
	 * @return true for {@code blocking}, false for {@code nonblocking}
	 * @throws IOException if the field is neither
	 */
	private static boolean blocking(String value) throws IOException
	{
		return switch (value)
		{
			case "blocking" -> true;
			case "nonblocking" -> false;
			default -> throw new IOException("A read transfer is blocking or nonblocking: " + value);
		};
	}

	/**
	 * Parses the byte of a write transfer.
	 * @param value the field
	 * @return the byte, from 0 through 255
	 * @throws IOException if the field is not a canonical decimal from 0 through 255
	 */
	private static int unsignedByte(String value) throws IOException
	{
		if (!value.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(value) > 255)
			throw new IOException("A transfer byte is a canonical decimal from 0 through 255: " + value);
		return Integer.parseInt(value);
	}

	/**
	 * Rejects a frame with the wrong number of fields.
	 * @param fields   the frame
	 * @param expected the number its type carries
	 * @throws IOException if the count differs
	 */
	private static void requireCount(List<String> fields, int expected) throws IOException
	{
		if (fields.size() != expected)
			throw new IOException("A " + fields.getFirst() + " frame carries " + expected + " fields: " + fields);
	}

	/**
	 * Starts a payload that identifies this client.
	 * @param event the event name
	 * @return the payload, ready for the event's own members
	 */
	private ObjectNode payload(String event)
	{
		return json.createObjectNode().put("protocol", PROTOCOL).put("version", 1).put("attempt", attempt).
			put("installation", Long.toString(installation)).put("pid", Long.toString(pid)).put("event", event);
	}

	/**
	 * Wraps a payload in the envelope that an instrumented process sends.
	 * @param payload the payload
	 * @return the message
	 */
	private String envelope(ObjectNode payload)
	{
		ObjectNode envelope = json.createObjectNode().put("type", "send");
		envelope.set("payload", payload);
		return json.writeValueAsString(envelope);
	}

	/**
	 * Translates a control that {@link CargoTokenMessages#grant}, {@link CargoTokenMessages#decline} or
	 * {@link CargoTokenMessages#acknowledge} produced into a frame for the client.
	 * @param control the control
	 * @return the {@code token} payload, or the {@code empty} payload of a declined read
	 * @throws IOException if the value is not a token control for this client
	 */
	public byte[] token(JsonNode control) throws IOException
	{
		String sequence = control.path("sequence").asString("");
		if (!control.path("type").asString("").equals(CONTROL_TYPE + sequence) ||
			!control.path("protocol").asString("").equals(PROTOCOL) || control.path("version").asInt(0) != 1)
		{
			throw new IOException("Not a version 1 token control: " + control);
		}
		if (!control.path("attempt").asString("").equals(attempt) ||
			!control.path("installation").asString("").equals(Long.toString(installation)) ||
			!control.path("pid").asString("").equals(Long.toString(pid)))
		{
			throw new IOException("The token control belongs to another client: " + control);
		}
		return switch (control.path("operation").asString(""))
		{
			case "read" ->
			{
				if (!control.has("byte"))
					yield LinuxBuildHookFrames.encode("empty", sequence);
				int value = control.path("byte").asInt(-1);
				if (!control.path("byte").isIntegralNumber() || value < 0 || value > 255)
					throw new IOException("A read token carries a byte from 0 through 255: " + control);
				yield LinuxBuildHookFrames.encode("token", sequence, Integer.toString(value));
			}
			case "write" -> LinuxBuildHookFrames.encode("token", sequence);
			default -> throw new IOException("A token control's operation is read or write: " + control);
		};
	}
}
