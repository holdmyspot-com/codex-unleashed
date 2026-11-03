package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxProcessTree;
import com.holdmyspot.codexunleashed.scheduler.LinuxSupervisor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Verifies that trusted child JVMs load their scope dependencies before rejecting invalid input. */
public final class LinuxJavaLaunchTest
{
	/** Creates isolated Java launch tests. */
	public LinuxJavaLaunchTest() {}

	/**
	 * Supplies both trusted entry points that create a tooling scope before validating arguments.
	 * @return entry points and their expected argument validation messages
	 */
	@DataProvider(parallel = true)
	public Object[][] entryPoints()
	{
		return new Object[][] {
			{LinuxSupervisor.class, "Dedicated supervisor requires its private attempt directory"},
			{LinuxProcessTree.class, "Expected --gate and the owned scratch directory"}
		};
	}

	/**
	 * Exercises a real child JVM from a different directory without cgroup or native setup.
	 * Argument validation proves that the child linked its tooling scope and external Pouch dependency.
	 * @param entryPoint trusted child main class
	 * @param expectedMessage message that follows successful class and scope loading
	 * @throws Exception if the owned directory, child launch or cleanup fails
	 */
	@Test(dataProvider = "entryPoints")
	public void loadsScopeBeforeArgumentValidation(Class<?> entryPoint, String expectedMessage) throws Exception
	{
		Path directory = Files.createTempDirectory("trusted-java-launch-");
		try
		{
			Path java = Path.of(System.getProperty("java.home"), "bin", "java");
			if (!Files.isRegularFile(java))
				java = java.resolveSibling("java.exe");
			Path location = Path.of(LinuxProcessTree.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			var launch = new LinuxProcessTree.Launch(directory, directory, java, location,
				List.of(java.toString()), Map.of(), directory, 1, 1);
			Path output = directory.resolve("child.log");
			ProcessBuilder builder = new ProcessBuilder(LinuxProcessTree.javaCommand(launch, entryPoint));
			builder.environment().clear();
			Process child = builder.directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
			child.onExit().join();
			child.getOutputStream().close();
			String diagnostics = Files.readString(output);
			requireThat(child.exitValue(), "invalidInputExit").isEqualTo(1, "rejectedInput");
			requireThat(diagnostics.contains(expectedMessage), "argumentValidationReached").isTrue();
			requireThat(diagnostics.contains("NoClassDefFoundError"), "missingRuntimeDependency").isFalse();
		}
		finally
		{
			try (var entries = Files.walk(directory))
			{
				for (Path entry : entries.sorted(java.util.Comparator.reverseOrder()).toList())
					Files.delete(entry);
			}
		}
	}
}
