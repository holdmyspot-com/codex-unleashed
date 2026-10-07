package com.holdmyspot.codexunleashed.tooling.release;

import java.io.IOException;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resolves archive environment options lazily while retaining numeric spellings and override precedence.
 */
public final class ArchiveEnvironment implements ArchiveTimestamps
{
	private static final String TAR_TIME = "CODEX_PACKAGE_ARCHIVE_MTIME";
	private static final String GZIP_TIME = "CODEX_PACKAGE_ARCHIVE_GZIP_MTIME";
	private static final String MEMBER_TIMES = "CODEX_PACKAGE_ARCHIVE_MEMBER_MTIMES";
	private static final Set<String> NON_FINITE_JSON_VALUES = Set.of("NaN", "Infinity", "-Infinity");
	private static final Pattern NUMBER_WORD = Pattern.compile("[+-]?[A-Za-z]+");
	private final Map<String, String> environment;

	/**
	 * Retains an immutable explicit environment boundary without eagerly parsing unused options.
	 *
	 * @param environment the supplied environment variables
	 * @throws NullPointerException if the environment, a variable name, or its value is null
	 */
	public ArchiveEnvironment(Map<String, String> environment)
	{
		this.environment = Map.copyOf(environment);
	}

	/**
	 * Resolves the blanket tar override, falling back to SOURCE_DATE_EPOCH only when absent.
	 *
	 * @return the optional epoch-second value
	 * @throws IOException if the consumed value is not a decimal integer
	 */
	@Override
	public Optional<BigInteger> tarModificationTime() throws IOException
	{
		String value = environment.get(TAR_TIME);
		if (value == null)
			value = environment.get("SOURCE_DATE_EPOCH");
		if (value == null)
			return Optional.empty();
		try
		{
			return Optional.of(ArchiveNumberText.parseInteger(value));
		}
		catch (IOException failure)
		{
			throw new IOException("SOURCE_DATE_EPOCH must be an integer", failure);
		}
	}

	/**
	 * Resolves the independent gzip header override without using SOURCE_DATE_EPOCH.
	 *
	 * @return the optional epoch-second value
	 * @throws IOException if the consumed value is not a decimal integer
	 */
	@Override
	public Optional<BigInteger> gzipHeaderTime() throws IOException
	{
		String value = environment.get(GZIP_TIME);
		if (value == null)
			return Optional.empty();
		try
		{
			return Optional.of(ArchiveNumberText.parseInteger(value));
		}
		catch (IOException failure)
		{
			throw new IOException(GZIP_TIME + " must be an integer", failure);
		}
	}

	/**
	 * Parses member timestamp mappings only when tar member processing requests them.
	 *
	 * @return immutable validated member timestamp text
	 * @throws IOException if the input is not an object or contains unsupported timestamp values
	 */
	@Override
	public Map<String, String> memberModificationTimes() throws IOException
	{
		String value = environment.get(MEMBER_TIMES);
		if (value == null)
			return Map.of();
		JsonNode document;
		try
		{
			JsonMapper mapper = JsonMapper.builder().enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS).
				enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
			validateNonFiniteSpellings(mapper, value);
			document = mapper.readTree(value);
		}
		catch (JacksonException failure)
		{
			throw new IOException(MEMBER_TIMES + " must be a JSON object", failure);
		}
		if (document == null || !document.isObject())
			throw new IOException(MEMBER_TIMES + " must be a JSON object");
		Map<String, String> result = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : document.properties())
			result.put(entry.getKey(), timestampText(entry.getValue()));
		return Map.copyOf(result);
	}

	/**
	 * Uses parser token text to reject additional native number spellings before tree conversion loses their form.
	 *
	 * @param mapper the configured JSON mapper
	 * @param document the supplied JSON document
	 * @throws IOException if a native numeric token is outside the retained spelling set
	 */
	private static void validateNonFiniteSpellings(JsonMapper mapper, String document) throws IOException
	{
		try (JsonParser parser = mapper.createParser(document))
		{
			while (true)
			{
				JsonToken token = parser.nextToken();
				if (token == null)
					break;
				if (token == JsonToken.VALUE_NUMBER_FLOAT)
				{
					String spelling = parser.getString();
					if (NUMBER_WORD.matcher(spelling).matches() && !NON_FINITE_JSON_VALUES.contains(spelling))
						throw new IOException(MEMBER_TIMES + " must be a JSON object");
				}
			}
		}
	}

	/**
	 * Preserves explicit strings and applies retained number-to-text conversion to numeric JSON values.
	 *
	 * @param value the member timestamp value
	 * @return the validated timestamp text
	 * @throws IOException if the value is not a supported number or numeric string
	 */
	private static String timestampText(JsonNode value) throws IOException
	{
		if (value.isString())
		{
			String text = value.stringValue();
			ArchiveNumberText.parseDouble(text);
			return text;
		}
		if (value.isIntegralNumber())
		{
			BigInteger number = value.bigIntegerValue();
			if (!Double.isFinite(number.doubleValue()))
				throw new IOException(MEMBER_TIMES + " integer value exceeds floating-point range");
			return number.toString();
		}
		if (value.isNumber())
			return ArchiveNumberText.formatDouble(value.doubleValue());
		throw new IOException(MEMBER_TIMES + " values must be numbers");
	}
}
