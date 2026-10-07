package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

/** Checks Cargo cache metadata without requiring fields unused by the requested operation. */
public final class SourceCacheMetadataTest
{
	/** Creates Cargo metadata tests. */
	public SourceCacheMetadataTest()
	{
	}

	/**
	 * Preserves empty and relative target paths while distinguishing a missing field from an explicit null.
	 *
	 * @throws IOException if valid metadata is rejected
	 */
	@Test
	public void preservesTargetDirectoryValues() throws IOException
	{
		Path workspace = Path.of("workspace");
		assertEquals(SourceCacheMetadata.parse("{}").targetDirectory(workspace, Map.of()), workspace.resolve("target"));
		assertEquals(SourceCacheMetadata.parse("{}").targetDirectory(workspace,
			Map.of("CARGO_TARGET_DIR", "relative")), Path.of("relative"));
		assertEquals(SourceCacheMetadata.parse("{}").targetDirectory(workspace,
			Map.of("CARGO_TARGET_DIR", "")), Path.of(""));
		assertEquals(SourceCacheMetadata.parse("{\"target_directory\":\"\"}").targetDirectory(workspace,
			Map.of("CARGO_TARGET_DIR", "ignored")), Path.of(""));
		for (String value : List.of("null", "false", "42", "[]"))
		{
			SourceCacheMetadata metadata = SourceCacheMetadata.parse("{\"target_directory\":" + value + "}");
			expectThrows(IOException.class,
				() -> metadata.targetDirectory(workspace, Map.of("CARGO_TARGET_DIR", "fallback")));
		}
	}

	/**
	 * Distinguishes binary owners, library owners and workspace members using Cargo's native fields.
	 *
	 * @throws IOException if valid Cargo metadata is rejected
	 */
	@Test
	public void classifiesCargoPackages() throws IOException
	{
		SourceCacheMetadata metadata = SourceCacheMetadata.parse("""
			{"workspace_members":["z-id","a-id"],"packages":[
			 {"id":"z-id","name":"zebra","targets":[
			   {"name":"codex","kind":["bin"]},{"name":"zebra","kind":["rlib"]}]},
			 {"id":"external-id","name":"cached-external","targets":[{"name":"cached-external","kind":["lib"]}]},
			 {"id":"a-id","name":"alpha","targets":[{"name":"helper","kind":["bin"]}]}
			 ]}
			""");
		assertEquals(metadata.binaryOwners(Set.of("codex", "helper", "absent")),
			Map.of("codex", "zebra", "helper", "alpha"));
		assertEquals(metadata.libraryOwners(), Set.of("zebra", "cached-external"));
		assertEquals(metadata.workspacePackageNames(), List.of("alpha", "zebra"));
	}

	/**
	 * Allows record-only metadata without package arrays but refuses missing fields when cleanup consumes them.
	 *
	 * @throws IOException if target-only metadata is rejected
	 */
	@Test
	public void consumesOnlyRequiredFields() throws IOException
	{
		SourceCacheMetadata metadata = SourceCacheMetadata.parse("{\"target_directory\":\"target\"}");
		assertEquals(metadata.targetDirectory(Path.of("workspace"), Map.of()), Path.of("target"));
		expectThrows(IOException.class, () -> metadata.binaryOwners(Set.of("codex")));
		expectThrows(IOException.class, metadata::libraryOwners);
		expectThrows(IOException.class, metadata::workspacePackageNames);
	}

	/** Verifies strict JSON and native Cargo field types without converting scalars to strings. */
	@Test
	public void rejectsMalformedMetadata()
	{
		for (String text : List.of("", " ", "null", "[]", "{} {}", "{"))
			expectThrows(IOException.class, () -> SourceCacheMetadata.parse(text));
		expectThrows(IOException.class, () -> SourceCacheMetadata.parse("""
			{"packages":[{"name":42,"targets":[{"kind":["bin"],"name":"codex"}]}]}
			""").binaryOwners(Set.of("codex")));
	}
}
