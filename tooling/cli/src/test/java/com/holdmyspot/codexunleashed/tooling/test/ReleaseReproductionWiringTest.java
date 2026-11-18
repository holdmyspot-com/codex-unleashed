package com.holdmyspot.codexunleashed.tooling.test;

import java.util.ArrayList;
import tools.jackson.databind.JsonNode;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.ReleaseManifestSchema;
import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.file.PathUtils;
import org.testng.SkipException;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Runs the actual shell wrapper against the actual packaged Java launcher. */
public final class ReleaseReproductionWiringTest
{
	/** Creates packaged-entry checks. */
	public ReleaseReproductionWiringTest()
	{
	}

	/**
	 * Preserves literal space-containing arguments and the packaged command's rejected-input status.
	 *
	 * @throws IOException if invocation or fixture cleanup fails
	 */
	@Test
	public void realWrapperAndPackagedLauncher() throws IOException
	{
		Path context = Path.of(System.getProperty("quiet.build.test.context"));
		Path launcher = context.getParent().resolve("build/runtime/bin/codex-tooling");
		if (!Files.isRegularFile(launcher))
			throw new SkipException("Packaged reproduction wiring requires the runtime package phase first");
		Path fixture = Files.createTempDirectory("reproduction-wiring-");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			JsonNode settings = ReleaseManifestSchema.read(scope, context);
			Path project = Path.of(settings.path("working_directory").asString());
			Map<String, String> environment = new HashMap<>();
			for (Map.Entry<String, JsonNode> property : settings.path("environment").properties())
				environment.put(property.getKey(), property.getValue().asString());
			environment.put("CODEX_TOOLING", launcher.toString());
			environment.put("TMPDIR", fixture.toString());
			environment.put("TMP", fixture.toString());
			environment.put("TEMP", fixture.toString());
			Path manifest = fixture.resolve("release manifest with spaces.json");
			Path output = fixture.resolve("output with spaces");
			List<String> arguments = List.of(manifest.toString(), "--target", "unsupported-target", "--output-dir",
				output.toString());
			List<String> wrapped = new ArrayList<>(List.of("bash",
				project.resolve("scripts/reproduce-release.sh").toString()));
			wrapped.addAll(arguments);
			List<String> direct = new ArrayList<>(List.of("bash", launcher.toString(), "release-reproduction"));
			direct.addAll(arguments);
			direct.addAll(List.of("--project-root", project.toString()));
			SystemCommands.Result expected = SystemCommands.capture(direct, project, fixture, environment);
			SystemCommands.Result actual = SystemCommands.capture(wrapped, fixture, fixture, environment);
			requireThat(actual.status(), "wrapperExit").isEqualTo(2);
			requireThat(actual.status(), "directExit").isEqualTo(expected.status());
			requireThat(actual.stderr(), "literalDiagnostic").isEqualTo(expected.stderr());
			requireThat(actual.stderr(), "commandDispatch").contains("Unsupported reproduction target: unsupported-target");
			requireThat(Files.exists(output), "invalidInputDoesNotBindOutput").isFalse();
		}
		finally
		{
			PathUtils.deleteDirectory(fixture);
		}
	}
}
