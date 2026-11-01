package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks Cargo cache metadata without requiring fields unused by the requested operation.
 */
public final class SourceCacheMetadataTest
{
	/**
	 * Creates Cargo metadata tests.
	 */
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
		try (ToolingScope scope = new DefaultToolingScope())
		{
			Path workspace = Path.of("workspace");
			requireThat(SourceCacheMetadata.parse(scope, "{}").targetDirectory(workspace, Map.of()), "SourceCacheMetadata")
				.isEqualTo(workspace.resolve("target"));
			requireThat(SourceCacheMetadata.parse(scope, "{}").targetDirectory(workspace,
				Map.of("CARGO_TARGET_DIR", "relative")), "actual").isEqualTo(Path.of("relative"));
			requireThat(SourceCacheMetadata.parse(scope, "{}").targetDirectory(workspace,
				Map.of("CARGO_TARGET_DIR", "")), "actual").isEqualTo(Path.of(""));
			requireThat(SourceCacheMetadata.parse(scope, "{\"target_directory\":\"\"}").targetDirectory(workspace,
				Map.of("CARGO_TARGET_DIR", "ignored")), "actual").isEqualTo(Path.of(""));
			for (String value : List.of("null", "false", "42", "[]"))
			{
				SourceCacheMetadata metadata = SourceCacheMetadata.parse(scope, "{\"target_directory\":" + value + "}");
				requireException(IOException.class, () -> metadata.targetDirectory(workspace, Map.of("CARGO_TARGET_DIR", "fallback")));
			}
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
		try (ToolingScope scope = new DefaultToolingScope())
		{
			SourceCacheMetadata metadata = SourceCacheMetadata.parse(scope, """
				{"workspace_members":["z-id","a-id"],"packages":[
				 {"id":"z-id","name":"zebra","targets":[
				   {"name":"codex","kind":["bin"]},{"name":"zebra","kind":["rlib"]}]},
				 {"id":"external-id","name":"cached-external","targets":[{"name":"cached-external","kind":["lib"]}]},
				 {"id":"a-id","name":"alpha","targets":[{"name":"helper","kind":["bin"]}]}
				 ]}
				""");
			requireThat(metadata.binaryOwners(Set.of("codex", "helper", "absent")), "metadata")
				.isEqualTo(Map.of("codex", "zebra", "helper", "alpha"));
			requireThat(metadata.libraryOwners(), "metadata.libraryOwners()").isEqualTo(Set.of("zebra", "cached-external"));
			requireThat(metadata.workspacePackageNames(), "metadata.workspacePackageNames()")
				.isEqualTo(List.of("alpha", "zebra"));
		}
	}

	/**
	 * Allows record-only metadata without package arrays but refuses missing fields when cleanup consumes them.
	 *
	 * @throws IOException if target-only metadata is rejected
	 */
	@Test
	public void consumesOnlyRequiredFields() throws IOException
	{
		try (ToolingScope scope = new DefaultToolingScope())
		{
			SourceCacheMetadata metadata = SourceCacheMetadata.parse(scope, "{\"target_directory\":\"target\"}");
			requireThat(metadata.targetDirectory(Path.of("workspace"), Map.of()), "metadata").isEqualTo(Path.of("target"));
			requireException(IOException.class, () -> metadata.binaryOwners(Set.of("codex")));
			requireException(IOException.class, metadata::libraryOwners);
			requireException(IOException.class, metadata::workspacePackageNames);
		}
	}

	/**
	 * Verifies strict JSON and native Cargo field types without converting scalars to strings.
	 */
	@Test
	public void rejectsMalformedMetadata()
	{
		try (ToolingScope scope = new DefaultToolingScope())
		{
			for (String text : List.of("", " ", "null", "[]", "{} {}", "{"))
				requireException(IOException.class, () -> SourceCacheMetadata.parse(scope, text));
			requireException(IOException.class, () -> SourceCacheMetadata.parse(scope, """
				{"packages":[{"name":42,"targets":[{"kind":["bin"],"name":"codex"}]}]}
				""").binaryOwners(Set.of("codex")));
		}
	}
}
