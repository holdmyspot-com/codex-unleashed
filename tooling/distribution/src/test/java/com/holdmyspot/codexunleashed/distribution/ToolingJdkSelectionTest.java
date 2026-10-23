package com.holdmyspot.codexunleashed.distribution;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Executes the maintained tooling action's JDK selector for supported runner platforms.
 */
public final class ToolingJdkSelectionTest
{
	/**
	 * Creates tooling JDK selection tests.
	 */
	public ToolingJdkSelectionTest()
	{
	}

	/**
	 * Supplies supported runner platforms and the selected setup-java architecture, cache identity and vendor.
	 *
	 * @return platform inputs and expected selector outputs
	 */
	@DataProvider
	public Object[][] platforms()
	{
		return new Object[][]{
			{"Windows", "ARM64", "aarch64", "aarch64", "liberica"},
			{"Windows", "X64", "", "X64", "temurin"},
			{"Linux", "X64", "", "X64", "temurin"},
			{"Linux", "ARM64", "", "ARM64", "temurin"},
			{"macOS", "X64", "", "X64", "zulu"},
			{"macOS", "ARM64", "", "ARM64", "temurin"}
		};
	}

	/**
	 * Runs the action's actual Bash selector for each runner without installing a JDK or changing global defaults.
	 *
	 * @param runnerOs     the GitHub runner operating system
	 * @param runnerArch   the native runner architecture
	 * @param architecture the setup-java architecture override
	 * @param effective    the JVM architecture included in the runtime cache key
	 * @param distribution the setup-java distribution
	 * @throws IOException          if action, process or fixture operations fail
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test(dataProvider = "platforms")
	public void selectsJdkForRunner(String runnerOs, String runnerArch, String architecture, String effective,
		String distribution) throws IOException, InterruptedException
	{
		JsonNode selector = findStep(readAction(), "jdk-platform");
		Path root = Files.createTempDirectory("tooling-jdk-selection-");
		try (Closeable _ = () -> FixtureDirectories.deleteTree(root))
		{
			Path output = root.resolve("github-output");
			Path console = root.resolve("console");
			ProcessBuilder builder = NativeCommands.createBuilder("bash", "-eu", "-c", selector.path("run").asString()).
				directory(root.toFile()).redirectErrorStream(true).redirectOutput(console.toFile());
			builder.environment().put("RUNNER_OS", runnerOs);
			builder.environment().put("RUNNER_ARCH", runnerArch);
			builder.environment().put("GITHUB_OUTPUT", output.toString().replace('\\', '/'));
			try (Process process = builder.start())
			{
				process.getOutputStream().close();
				requireThat(process.waitFor(), "selectorStatus").isEqualTo(0);
			}
			Properties outputs = new Properties();
			try (Reader reader = Files.newBufferedReader(output))
			{
				outputs.load(reader);
			}
			requireThat(outputs.getProperty("architecture"), "architecture").isEqualTo(architecture);
			requireThat(outputs.getProperty("effective"), "effective").isEqualTo(effective);
			requireThat(outputs.getProperty("distribution"), "distribution").isEqualTo(distribution);
		}
	}

	/**
	 * Reads the actual composite action from the checkout selected by the maintained Maven wrapper.
	 *
	 * @return the parsed action
	 * @throws IOException if the action cannot be read
	 */
	private static JsonNode readAction() throws IOException
	{
		Path project = Path.of(System.getProperty("tooling.release.workflow")).getParent().getParent().getParent();
		Path action = project.resolve(".github/actions/setup-tooling/action.yml");
		return YAMLMapper.builder().build().readTree(Files.readString(action));
	}

	/**
	 * Finds an explicitly identified step in the action, failing if it is absent.
	 *
	 * @param action the parsed composite action
	 * @param id     the required step identifier
	 * @return the matching step
	 * @throws IOException if the step is missing
	 */
	private static JsonNode findStep(JsonNode action, String id) throws IOException
	{
		JsonNode steps = action.path("runs").path("steps");
		for (int index = 0; index < steps.size(); ++index)
		{
			JsonNode step = steps.get(index);
			if (id.equals(step.path("id").asString()))
				return step;
		}
		throw new IOException("Tooling action omits step " + id);
	}
}
