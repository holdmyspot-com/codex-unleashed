package com.holdmyspot.codexunleashed.tooling;

import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static org.testng.Assert.assertEquals;

/**
 * Proves the selected library's compatible ASCII artifact formatting at its real writer boundary.
 */
public final class ArtifactJsonApiTest
{
	/**
	 * Creates the artifact format API proof.
	 */
	public ArtifactJsonApiTest()
	{
	}

	/**
	 * Retains sorted keys, two-space indentation, empty arrays, literal slashes, and lowercase Unicode escapes.
	 */
	@Test
	public void writesCompatibleAsciiArtifacts()
	{
		DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
		DefaultPrettyPrinter printer = new DefaultPrettyPrinter(Separators.createDefaultInstance().
			withObjectNameValueSpacing(Separators.Spacing.AFTER).withObjectEmptySeparator("").withArrayEmptySeparator("")).
			withObjectIndenter(indenter).withArrayIndenter(indenter);
		JsonMapper mapper = JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).
			disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE, JsonWriteFeature.ESCAPE_FORWARD_SLASHES).
			enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
		String result = mapper.writer().with(printer).writeValueAsString(Map.of("z", 2, "empty", List.of(), "a",
			List.of(Map.of("path", "patches/é.patch")))) + "\n";
		assertEquals(result, "{\n  \"a\": [\n    {\n      \"path\": \"patches/\\u00e9.patch\"\n    }\n  ],\n" +
			"  \"empty\": [],\n  \"z\": 2\n}\n");
		assertEquals(ArtifactJson.format(Map.of("z", 2, "empty", List.of(), "a",
			List.of(Map.of("path", "patches/é.patch")))), result);
	}
}
