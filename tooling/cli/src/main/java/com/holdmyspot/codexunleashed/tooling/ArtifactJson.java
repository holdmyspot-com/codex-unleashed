package com.holdmyspot.codexunleashed.tooling;

import java.util.Objects;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.ObjectWriter;

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
	 * Formats a supported JSON document with sorted map keys, lowercase Unicode escapes, and a final newline.
	 *
	 * @param scope borrowed shared values; the caller retains ownership through all operations
	 * @param document the document's JSON-compatible maps, lists, and scalar values
	 * @return the ASCII JSON document
	 * @throws NullPointerException if {@code document} is null
	 */
	public static String format(ToolingScope scope, Object document)
	{
		return format(scope, document, true);
	}

	/**
	 * Formats a document while retaining the supplied map key order.
	 *
	 * @param scope borrowed shared values; the caller retains ownership through all operations
	 * @param document the document's JSON-compatible maps, lists, and scalar values
	 * @return the ASCII JSON document with a final newline
	 * @throws NullPointerException if {@code document} is null
	 */
	public static String formatPreservingKeyOrder(ToolingScope scope, Object document)
	{
		return format(scope, document, false);
	}

	/**
	 * Formats a document with the selected key ordering.
	 *
	 * @param scope borrowed shared values; the caller retains ownership through all operations
	 * @param document the document's JSON-compatible values
	 * @param sortKeys true if map keys must be sorted
	 * @return the formatted document
	 */
	private static String format(ToolingScope scope, Object document, boolean sortKeys)
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(document, "document");
		DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
		DefaultPrettyPrinter printer = new DefaultPrettyPrinter(Separators.createDefaultInstance().
			withObjectNameValueSpacing(Separators.Spacing.AFTER).withObjectEmptySeparator("").withArrayEmptySeparator("")).
			withObjectIndenter(indenter).withArrayIndenter(indenter);
		ObjectWriter writer = scope.getArtifactJsonMapper().writer().with(printer);
		if (sortKeys)
			writer = writer.with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
		else
			writer = writer.without(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
		return writer.writeValueAsString(document) + "\n";
	}
}
