package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the fields that cache retention plans require from an inventory, refusing missing or malformed values rather
 * than treating them as empty.
 */
final class RetentionInventory
{
	/**
	 * Prevents construction.
	 */
	private RetentionInventory()
	{
	}

	/**
	 * Reads a complete inventory object.
	 *
	 * @param json the mapper to parse with
	 * @param in the inventory JSON; remains open
	 * @return the inventory object
	 * @throws IOException if input cannot be read or is not exactly one JSON object
	 */
	static JsonNode readObject(JsonMapper json, InputStream in) throws IOException
	{
		JsonNode document;
		try
		{
			document = json.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		catch (JacksonException failure)
		{
			throw new IOException("Retention inventory must contain exactly one JSON object", failure);
		}
		if (document == null || !document.isObject())
			throw new IOException("Retention inventory must be a JSON object");
		return document;
	}

	/**
	 * Requires an inventory array without treating a missing field as an empty inventory.
	 *
	 * @param object containing object
	 * @param field required field name
	 * @return the array
	 * @throws IOException if the field is missing or is not an array
	 */
	static JsonNode array(JsonNode object, String field) throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isArray())
			throw new IOException("Retention inventory field must be an array: " + field);
		return value;
	}

	/**
	 * Validates a required inventory string array.
	 *
	 * @param array array selected from the inventory
	 * @return its strings, in input order
	 * @throws IOException if an element is not a string
	 */
	static List<String> strings(JsonNode array) throws IOException
	{
		List<String> result = new ArrayList<>();
		for (JsonNode value : array)
		{
			if (!value.isString())
				throw new IOException("Retention inventory array must contain strings");
			result.add(value.stringValue());
		}
		return result;
	}

	/**
	 * Reads a record's required {@code created_at} instant.
	 *
	 * @param record the inventory record
	 * @param subject the record's name in diagnostics, such as {@code Cache 7}
	 * @return the instant
	 * @throws IOException if the field is missing or is not an ISO-8601 instant
	 */
	static Instant instant(JsonNode record, String subject) throws IOException
	{
		JsonNode created = record.path("created_at");
		try
		{
			if (created.isString())
				return Instant.parse(created.stringValue());
		}
		catch (DateTimeParseException _)
		{
			// Reported below with the record's name.
		}
		throw new IOException(subject + " requires an ISO-8601 created_at instant");
	}
}
