package com.holdmyspot.codexunleashed.tooling;

import java.util.Objects;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Formats release artifact documents as indented ASCII JSON.
 */
public final class ArtifactJson
{
	/**
	 * Prevents construction.
	 */
	private ArtifactJson()
	{
	}

	/**
	 * Formats a supported JSON document with lowercase Unicode escapes and a final newline.
	 *
	 * @param document the document's JSON-compatible maps, lists, and scalar values
	 * @return the ASCII JSON document
	 * @throws NullPointerException if {@code document} is null
	 */
	public static String format(Object document)
	{
		return format(document, true);
	}

	/**
	 * Formats a document while retaining the supplied map key order.
	 *
	 * @param document the document's JSON-compatible maps, lists, and scalar values
	 * @return the ASCII JSON document with a final newline
	 * @throws NullPointerException if {@code document} is null
	 */
	public static String formatPreservingKeyOrder(Object document)
	{
		return format(document, false);
	}

	/**
	 * Formats a document with the selected key ordering.
	 *
	 * @param document the document's JSON-compatible values
	 * @param sortKeys whether map keys are sorted
	 * @return the formatted document
	 */
	private static String format(Object document, boolean sortKeys)
	{
		Objects.requireNonNull(document, "document");
		DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
		DefaultPrettyPrinter printer = new DefaultPrettyPrinter(Separators.createDefaultInstance().
			withObjectNameValueSpacing(Separators.Spacing.AFTER).withObjectEmptySeparator("").withArrayEmptySeparator("")).
			withObjectIndenter(indenter).withArrayIndenter(indenter);
		JsonMapper mapper = JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).
			disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE, JsonWriteFeature.ESCAPE_FORWARD_SLASHES).
			configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, sortKeys).build();
		return mapper.writer().with(printer).writeValueAsString(document) + "\n";
	}
}
