package com.holdmyspot.codexunleashed.tooling;

import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.toml.TomlMapper;
import tools.jackson.dataformat.toml.TomlReadFeature;

/** Parses TOML without converting native temporal values into package or configuration strings. */
public final class TomlDocuments
{
	private static final TomlMapper MAPPER = TomlMapper.builder().enable(TomlReadFeature.PARSE_JAVA_TIME).build();

	/** Prevents construction. */
	private TomlDocuments()
	{
	}

	/**
	 * Parses a TOML document with validated native date, time, local datetime, and offset datetime nodes.
	 *
	 * @param text the decoded TOML document
	 * @return the parsed document with native temporal objects distinct from strings
	 * @throws JacksonException if the document or native temporal value is invalid
	 * @throws NullPointerException if {@code text} is null
	 */
	public static JsonNode parse(String text)
	{
		Objects.requireNonNull(text, "text");
		return MAPPER.readTree(text);
	}
}
