package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.ArtifactJson;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;

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
			requireThat(first.getNamespaceControlMapper() == first.getNamespaceControlMapper(), "sharedNamespaceControlMapper").isTrue();
			requireThat(first.getNamespaceControlMapper() == second.getNamespaceControlMapper(), "crossScopeNamespaceControlSharing").isFalse();
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
		requireException(IllegalStateException.class, scope::getNamespaceControlMapper);
		requireException(IllegalStateException.class, scope::getLeaderJsonMapper);
		requireException(IllegalStateException.class, scope::getYamlMapper);
		requireException(IllegalStateException.class, scope::getStableReleaseTagPattern);
	}

	/**
	 * Keeps diagnostic limits independent from the larger authenticated namespace-control frames.
	 * @throws java.io.IOException if a supported bounded control cannot be parsed
	 */
	@Test public void separatesNamespaceAndDiagnosticLimits() throws java.io.IOException
	{
		try (ToolingScope scope = new DefaultToolingScope())
		{
			String control = "{\"arguments\":\"" + "x".repeat(11_376) + "\"}";
			byte[] bytes = control.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			requireThat(scope.getNamespaceControlMapper().readTree(bytes).path("arguments").asString().length(),
				"completeNamespaceArguments").isEqualTo(11_376);
			requireException(tools.jackson.core.JacksonException.class, () -> scope.getAllocationEnvelopeMapper().readTree(bytes));
			requireException(tools.jackson.core.JacksonException.class, () -> scope.getAllocationMessageMapper().readTree(bytes));
			requireException(tools.jackson.core.JacksonException.class, () -> scope.getAllocationEnvelopeMapper().readTree(
				("{\"x\":\"" + "x".repeat(8193) + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		}
	}

	/** Bounds JSON structure and rejects an oversized document through the scoped mapper itself. */
	@Test public void boundsNamespaceControlStructure()
	{
		try (ToolingScope scope = new DefaultToolingScope())
		{
			for (String invalid : java.util.List.of("[".repeat(17) + "0" + "]".repeat(17),
				"{\"value\":123456789012345678901}", "{\"value\":\"" +
				"x".repeat(ToolingScope.MAX_NAMESPACE_CONTROL_FRAME_BYTES + 1) + "\"}"))
				requireException(tools.jackson.core.JacksonException.class, () -> scope.getNamespaceControlMapper().readTree(
					invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		}
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
