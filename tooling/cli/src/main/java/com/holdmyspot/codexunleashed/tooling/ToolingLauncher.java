package com.holdmyspot.codexunleashed.tooling;

import java.nio.file.Path;
import java.util.List;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Selects the packaged tooling entry command for a diagnostic child process.
 */
public final class ToolingLauncher
{
	/**
	 * System property that supplies the packaged wrapper identity to the tooling entry point.
	 */
	public static final String PATH_PROPERTY = "codex.tooling.launcher";

	/**
	 * Tooling module launched by the packaged Java runtime.
	 */
	private static final String MODULE = "com.holdmyspot.codexunleashed.tooling";
	/**
	 * Main class in the tooling module.
	 */
	private static final String MAIN_CLASS = MODULE + ".Main";
	/**
	 * Java executable bundled beside the packaged wrapper on Windows.
	 */
	private static final String WINDOWS_JAVA = "java.exe";

	/**
	 * Prevents instances because command selection has no retained state.
	 */
	private ToolingLauncher()
	{
	}

	/**
	 * Selects the executable and fixed arguments that enter the packaged tooling module.
	 *
	 * @param platform          operating system that selects the packaged entry
	 * @param launcher          packaged wrapper identity
	 * @param temporaryDirectory invocation temporary directory passed to the Java runtime
	 * @return immutable executable and argument prefix
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if a Windows launcher has no parent directory
	 */
	public static List<String> command(QuietBuildDump.Platform platform, Path launcher, Path temporaryDirectory)
	{
		requireThat(platform, "platform").isNotNull();
		requireThat(launcher, "launcher").isNotNull();
		requireThat(temporaryDirectory, "temporaryDirectory").isNotNull();

		if (platform == QuietBuildDump.Platform.WINDOWS)
		{
			if (launcher.getParent() == null)
				throw new IllegalArgumentException("Windows launcher must have a parent directory to locate java.exe");

			return List.of(launcher.resolveSibling(WINDOWS_JAVA).toString(),
				"-Djava.io.tmpdir=" + temporaryDirectory, "-D" + PATH_PROPERTY + "=" + launcher,
				"-Xlog:all=off:stdout", "-Xlog:all=warning:stderr",
				"-Djdk.lang.Process.allowAmbiguousCommands=true", "--enable-native-access=" + MODULE, "-m",
				MODULE + "/" + MAIN_CLASS);
		}

		return List.of(launcher.toString());
	}
}
