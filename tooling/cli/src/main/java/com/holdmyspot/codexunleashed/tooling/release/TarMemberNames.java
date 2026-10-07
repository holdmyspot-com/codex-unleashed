package com.holdmyspot.codexunleashed.tooling.release;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarConstants;

/**
 * Retains original tar path spellings before the archive library normalizes PAX and GNU extended names.
 */
final class TarMemberNames
{
	private static final int RECORD_SIZE = TarConstants.DEFAULT_RCDSIZE;

	/**
	 * Prevents construction.
	 */
	private TarMemberNames()
	{
	}

	/**
	 * Reads naming metadata using the sequential reader's observed physical offsets and spans to skip payloads.
	 *
	 * @param tar the uncompressed tar file
	 * @param entries the library's ordered metadata entries and observed physical payload spans
	 * @return original names paired with those entries
	 * @throws IOException if metadata framing, encoding, or input access fails
	 */
	static List<Names> read(Path tar, List<Payload> entries) throws IOException
	{
		List<Names> result = new ArrayList<>();
		Map<String, byte[]> global = new HashMap<>();
		long position = 0;
		try (InputStream input = Files.newInputStream(tar))
		{
			for (Payload entryPayload : entries)
			{
				TarArchiveEntry entry = entryPayload.entry();
				Map<String, byte[]> attributes = new HashMap<>(global);
				String longName = null;
				String longLink = null;
				TarArchiveEntry header = null;
				while (position < entryPayload.offset())
				{
					byte[] record = input.readNBytes(RECORD_SIZE);
					if (record.length != RECORD_SIZE)
						throw new IOException("Truncated tar naming header");
					position += RECORD_SIZE;
					header = new TarArchiveEntry(record);
					if (header.isPaxHeader() || header.isGlobalPaxHeader() || header.isGNULongNameEntry() ||
						header.isGNULongLinkEntry())
					{
						byte[] payload = readMetadata(input, header.getSize());
						long padding = padding(header.getSize());
						input.skipNBytes(padding);
						position += header.getSize() + padding;
						if (header.isGlobalPaxHeader())
						{
							readPax(payload, global);
							attributes = new HashMap<>(global);
						}
						else if (header.isPaxHeader())
							readPax(payload, attributes);
						else if (header.isGNULongNameEntry())
							longName = decodeLongName(payload);
						else
							longLink = decodeLongName(payload);
					}
					else
						break;
				}
				if (header == null || position > entryPayload.offset())
					throw new IOException("Tar naming metadata does not match the payload offset");
				input.skipNBytes(entryPayload.offset() - position);
				position = entryPayload.offset();
				String name = longName;
				if (name == null)
					name = header.getName();
				String link = longLink;
				if (link == null)
					link = header.getLinkName();
				byte[] selectedName = attributes.get("path");
				if (entry.isPaxGNUSparse())
					selectedName = attributes.getOrDefault("GNU.sparse.name", selectedName);
				if (selectedName != null)
					name = decode(selectedName, 0, selectedName.length);
				byte[] selectedLink = attributes.get("linkpath");
				if (selectedLink != null)
					link = decode(selectedLink, 0, selectedLink.length);
				result.add(new Names(name, link));
				long payload = entryPayload.size() + padding(entryPayload.size());
				input.skipNBytes(payload);
				position += payload;
			}
		}
		return List.copyOf(result);
	}

	/**
	 * Reads a metadata payload whose size is representable by a Java byte array.
	 *
	 * @param input the tar stream
	 * @param size the metadata payload size
	 * @return the complete bytes
	 * @throws IOException if size representation or input completeness fails
	 */
	private static byte[] readMetadata(InputStream input, long size) throws IOException
	{
		if (size < 0 || size > Integer.MAX_VALUE)
			throw new IOException("Tar naming metadata exceeds Java array size");
		byte[] result = input.readNBytes((int) size);
		if (result.length != size)
			throw new IOException("Truncated tar naming metadata");
		return result;
	}

	/**
	 * Calculates the record alignment bytes after a payload.
	 *
	 * @param size the validated payload size
	 * @return the number of padding bytes
	 */
	private static long padding(long size)
	{
		return (RECORD_SIZE - size % RECORD_SIZE) % RECORD_SIZE;
	}

	/**
	 * Reads length-prefixed PAX records and retains raw naming values until the authoritative name is selected.
	 *
	 * @param payload the PAX bytes
	 * @param attributes the effective naming fields, with empty values removing inherited fields
	 * @throws IOException if framing or a field key's UTF-8 encoding is invalid
	 */
	private static void readPax(byte[] payload, Map<String, byte[]> attributes) throws IOException
	{
		int start = 0;
		while (start < payload.length)
		{
			int cursor = start;
			long length = 0;
			while (cursor < payload.length && payload[cursor] >= '0' && payload[cursor] <= '9')
			{
				length = length * 10 + payload[cursor] - '0';
				if (length > payload.length - start)
					throw new IOException("Invalid PAX naming record length");
				++cursor;
			}
			if (cursor == start || cursor >= payload.length || payload[cursor] != ' ' || length == 0)
				throw new IOException("Invalid PAX naming record framing");
			int end = start + (int) length;
			++cursor;
			int keyStart = cursor;
			while (cursor < end && payload[cursor] != '=')
				++cursor;
			if (cursor >= end - 1 || payload[end - 1] != '\n')
				throw new IOException("Invalid PAX naming record value");
			String key = decode(payload, keyStart, cursor - keyStart);
			if (key.equals("path") || key.equals("linkpath") || key.equals("GNU.sparse.name"))
			{
				byte[] value = Arrays.copyOfRange(payload, cursor + 1, end - 1);
				if (value.length == 0)
					attributes.remove(key);
				else
					attributes.put(key, value);
			}
			start = end;
		}
	}

	/**
	 * Removes only the GNU extension's trailing NUL terminators.
	 *
	 * @param payload the extended name bytes
	 * @return the unchanged name text
	 * @throws IOException if UTF-8 decoding fails
	 */
	private static String decodeLongName(byte[] payload) throws IOException
	{
		int length = payload.length;
		while (length > 0 && payload[length - 1] == 0)
			--length;
		return decode(payload, 0, length);
	}

	/**
	 * Decodes an exact UTF-8 field without replacement or whitespace normalization.
	 *
	 * @param bytes the metadata bytes
	 * @param offset the field offset
	 * @param length the field length
	 * @return the field text
	 * @throws IOException if UTF-8 decoding fails
	 */
	private static String decode(byte[] bytes, int offset, int length) throws IOException
	{
		return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).
			onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, offset, length)).toString();
	}

	/**
	 * Retains original member and link names independently of normalized archive metadata.
	 *
	 * @param name the member name
	 * @param link the link name, or empty text for ordinary members
	 */
	record Names(String name, String link)
	{
	}

	/**
	 * Associates parsed member metadata with its physically observed payload span.
	 *
	 * @param entry the parsed member
	 * @param offset the physical tar payload offset after extended sparse metadata
	 * @param size the physically stored payload byte count, excluding alignment padding
	 */
	record Payload(TarArchiveEntry entry, long offset, long size)
	{
	}
}
