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
 * Translates one admitted build hook client's allocation frames to and from the messages that {@link AllocationHook}
 * exchanges with an instrumented process.
 */
public final class LinuxBuildHookMessages
{
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
	public LinuxBuildHookMessages(ToolingScope scope, UUID attempt, long installation, long pid)
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
	 * Translates a client frame that reports an allocation.
	 * @param fields the decoded frame
	 * @return the matching message, or empty if the frame does not concern allocations
	 * @throws IOException if a {@code blocked} or {@code granted} frame has the wrong number of fields
	 */
	public Optional<String> allocation(List<String> fields) throws IOException
	{
		switch (fields.getFirst())
		{
			case "blocked" ->
			{
				requireCount(fields, 5);
				return Optional.of(envelope(payload("blocked").put("wait", fields.get(1)).put("thread", fields.get(2)).
					put("bytes", fields.get(3)).put("error", fields.get(4))));
			}
			case "granted" ->
			{
				requireCount(fields, 3);
				return Optional.of(envelope(payload("granted").put("wait", fields.get(1)).put("status", fields.get(2))));
			}
			default ->
			{
				return Optional.empty();
			}
		}
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
		{
			throw new IOException("A " + fields.getFirst() + " frame carries " + expected + " fields: " + fields);
		}
	}

	/**
	 * Starts a payload that identifies this client.
	 * @param event the event name
	 * @return the payload, ready for the event's own members
	 */
	private ObjectNode payload(String event)
	{
		return json.createObjectNode().put("protocol", "codex-build-allocation").put("version", 1).
			put("attempt", attempt).put("installation", Long.toString(installation)).put("pid", Long.toString(pid)).
			put("event", event);
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
	 * Translates a grant that {@link AllocationMessages#permission} produced into a frame for the client.
	 * @param permission the grant
	 * @return the {@code grant} payload
	 * @throws IOException if the value is not a grant for this client
	 */
	public byte[] grant(JsonNode permission) throws IOException
	{
		if (!permission.path("type").asString("").equals("allocation-control") ||
			!permission.path("operation").asString("").equals("grant") || permission.path("version").asInt(0) != 1)
		{
			throw new IOException("Not a version 1 allocation grant: " + permission);
		}
		if (!permission.path("attempt").asString("").equals(attempt) ||
			!permission.path("installation").asString("").equals(Long.toString(installation)) ||
			!permission.path("pid").asString("").equals(Long.toString(pid)))
		{
			throw new IOException("The grant belongs to another client: " + permission);
		}
		return LinuxBuildHookFrames.encode("grant", permission.path("wait").asString(""));
	}
}
