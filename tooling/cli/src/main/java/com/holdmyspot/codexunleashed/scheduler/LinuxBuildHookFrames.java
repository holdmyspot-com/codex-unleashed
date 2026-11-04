package com.holdmyspot.codexunleashed.scheduler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes and decodes the payloads of linux-build-hook.c's frames: one through {@value #MAX_FIELDS} UTF-8 fields, each
 * terminated by NUL, in at most {@value #MAX_PAYLOAD_BYTES} bytes. {@link LinuxCargoTransport} adds the length header.
 */
public final class LinuxBuildHookFrames
{
	/**
	 * The library's largest payload.
	 */
	public static final int MAX_PAYLOAD_BYTES = 512;
	/**
	 * The library's largest field count.
	 */
	public static final int MAX_FIELDS = 8;

	private LinuxBuildHookFrames()
	{
	}

	/**
	 * Encodes fields as one payload.
	 * @param fields the fields
	 * @return the payload
	 * @throws IllegalArgumentException if there are no fields or more than {@value #MAX_FIELDS}, a field contains NUL,
	 *   or the payload would exceed {@value #MAX_PAYLOAD_BYTES} bytes
	 */
	public static byte[] encode(String... fields)
	{
		if (fields.length == 0 || fields.length > MAX_FIELDS)
			throw new IllegalArgumentException("A frame holds 1 through " + MAX_FIELDS + " fields: " + fields.length);
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		for (String field : fields)
		{
			if (field.indexOf('\0') >= 0)
				throw new IllegalArgumentException("A field may not contain NUL: " + field);
			payload.writeBytes(field.getBytes(StandardCharsets.UTF_8));
			payload.write(0);
		}
		if (payload.size() > MAX_PAYLOAD_BYTES)
		{
			throw new IllegalArgumentException("A payload holds at most " + MAX_PAYLOAD_BYTES + " bytes: " +
				payload.size());
		}
		return payload.toByteArray();
	}

	/**
	 * Decodes one payload.
	 * @param payload the payload
	 * @return its fields
	 * @throws IOException if the payload is empty, exceeds {@value #MAX_PAYLOAD_BYTES} bytes, does not end with NUL,
	 *   holds more than {@value #MAX_FIELDS} fields, or is not UTF-8
	 */
	public static List<String> decode(byte[] payload) throws IOException
	{
		if (payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES)
		{
			throw new IOException("A payload holds 1 through " + MAX_PAYLOAD_BYTES + " bytes: " + payload.length);
		}
		if (payload[payload.length - 1] != 0)
			throw new IOException("The payload's last field is not terminated by NUL");
		List<String> fields = new ArrayList<>();
		int start = 0;
		for (int i = 0; i < payload.length; ++i)
		{
			if (payload[i] != 0)
				continue;
			if (fields.size() == MAX_FIELDS)
				throw new IOException("The payload holds more than " + MAX_FIELDS + " fields");
			try
			{
				fields.add(StandardCharsets.UTF_8.newDecoder().
					onMalformedInput(CodingErrorAction.REPORT).
					onUnmappableCharacter(CodingErrorAction.REPORT).
					decode(ByteBuffer.wrap(payload, start, i - start)).toString());
			}
			catch (CharacterCodingException e)
			{
				throw new IOException("Field " + fields.size() + " is not UTF-8", e);
			}
			start = i + 1;
		}
		return fields;
	}
}
