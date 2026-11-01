package com.holdmyspot.codexunleashed.tooling;

import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;

/** Verifies sharing within an invocation, isolation between invocations and scope lifetime boundaries. */
public final class ToolingScopeTest
{
	/** Creates scope tests. */
	public ToolingScopeTest()
	{
	}

	/** A command can reuse expensive values without sharing them with an independent command or test. */
	@Test
	public void sharesValuesOnlyWithinTheirScope()
	{
		try (ToolingScope first = new DefaultToolingScope(); ToolingScope second = new DefaultToolingScope())
		{
			requireThat(first.getJsonMapper() == first.getJsonMapper(), "sharedJsonMapper").isTrue();
			requireThat(first.getNativeLinker() == first.getNativeLinker(), "sharedNativeLinker").isTrue();
			requireThat(first.getArtifactJsonMapper() == first.getArtifactJsonMapper(), "sharedArtifactMapper").isTrue();
			requireThat(first.getAllocationMessageMapper() == first.getAllocationMessageMapper(), "sharedMessageMapper").isTrue();
			requireThat(first.getAllocationEnvelopeMapper() == first.getAllocationEnvelopeMapper(), "sharedEnvelopeMapper").isTrue();
			requireThat(first.getLeaderJsonMapper() == first.getLeaderJsonMapper(), "sharedLeaderMapper").isTrue();
			requireThat(first.getYamlMapper() == first.getYamlMapper(), "sharedYamlMapper").isTrue();
			requireThat(first.getStableReleaseTagPattern() == first.getStableReleaseTagPattern(), "sharedPattern").isTrue();
			requireThat(first.getJsonMapper() == second.getJsonMapper(), "crossScopeJsonSharing").isFalse();
			requireThat(first.getArtifactJsonMapper() == second.getArtifactJsonMapper(), "crossScopeArtifactSharing").isFalse();
			requireThat(first.getAllocationMessageMapper() == second.getAllocationMessageMapper(), "crossScopeMessageSharing").isFalse();
			requireThat(first.getAllocationEnvelopeMapper() == second.getAllocationEnvelopeMapper(), "crossScopeEnvelopeSharing").isFalse();
			requireThat(first.getLeaderJsonMapper() == second.getLeaderJsonMapper(), "crossScopeLeaderSharing").isFalse();
			requireThat(first.getYamlMapper() == second.getYamlMapper(), "crossScopeYamlSharing").isFalse();
			requireThat(first.getStableReleaseTagPattern() == second.getStableReleaseTagPattern(), "crossScopePatternSharing").isFalse();
		}
	}

	/** Closing is idempotent and prevents acquiring either initialized or still-uninitialized values. */
	@Test
	public void refusesAcquisitionAfterClose()
	{
		ToolingScope scope = new DefaultToolingScope();
		scope.getJsonMapper();
		scope.close();
		scope.close();

		requireThat(scope.isClosed(), "closed").isTrue();
		requireException(IllegalStateException.class, scope::getJsonMapper);
		requireException(IllegalStateException.class, scope::getNativeLinker);
		requireException(IllegalStateException.class, scope::getLinuxBorrowedStreamBindings);
		requireException(IllegalStateException.class, scope::getUnixFileTimes);
		requireException(IllegalStateException.class, scope::getUnixFileMetadata);
		requireException(IllegalStateException.class, scope::getWindowsFileTimes);
		requireException(IllegalStateException.class, scope::getArtifactJsonMapper);
		requireException(IllegalStateException.class, scope::getAllocationMessageMapper);
		requireException(IllegalStateException.class, scope::getAllocationEnvelopeMapper);
		requireException(IllegalStateException.class, scope::getLeaderJsonMapper);
		requireException(IllegalStateException.class, scope::getYamlMapper);
		requireException(IllegalStateException.class, scope::getStableReleaseTagPattern);
	}

	/** Per-document writers preserve ASCII formatting and never change the next document's key ordering. */
	@Test
	public void preservesIndependentArtifactFormatting()
	{
		try (ToolingScope scope = new DefaultToolingScope())
		{
			var values = new java.util.LinkedHashMap<String, Object>();
			values.put("z", "é/path");
			values.put("a", java.util.List.of());
			String sorted = "{\n  \"a\": [],\n  \"z\": \"\\u00e9/path\"\n}\n";
			String ordered = "{\n  \"z\": \"\\u00e9/path\",\n  \"a\": []\n}\n";
			requireThat(ArtifactJson.format(scope, values), "sorted").isEqualTo(sorted, "expected");
			requireThat(ArtifactJson.formatPreservingKeyOrder(scope, values), "ordered").isEqualTo(ordered, "expected");
			requireThat(ArtifactJson.format(scope, values), "sortedAgain").isEqualTo(sorted, "expected");
		}
	}
}
