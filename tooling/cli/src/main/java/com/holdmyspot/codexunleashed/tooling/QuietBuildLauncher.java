package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Starts a build with explicit command, cwd and explicitly supplied environment, borrowing invocation-scoped
 * platform services.
 * <p>
 * On Windows, the JDK or operating system may additionally provide {@code SystemRoot} and
 * {@code PROCESSOR_ARCHITECTURE} when omitted from the supplied map.
 */
public final class QuietBuildLauncher
{
	/**
	 * Borrowed immutable Unix calls; null when the selected platform uses the JDK launcher.
	 */
	private final UnixQuietBuildProcess.Binding unix;

	/**
	 * Constructs an invocation service.
	 *
	 * @param scope open invocation scope
	 * @throws NullPointerException          if scope is null
	 * @throws UnsupportedOperationException if the selected Unix ABI or required libc function is unavailable
	 */
	public QuietBuildLauncher(ToolingScope scope)
	{
		requireThat(scope, "scope").isNotNull();
		String os = System.getProperty("os.name");
		if (os.equals("Linux") || os.equals("Mac OS X"))
			unix = new UnixQuietBuildProcess.Binding(scope.getNativeLinker());
		else
			unix = null;
	}

	/**
	 * Starts an owned build with literal arguments, inherited standard streams and this class's environment policy.
	 *
	 * @param command          executable and literal arguments
	 * @param workingDirectory child cwd
	 * @param environment      explicit child variables and their values
	 * @return owned process
	 * @throws NullPointerException          if an argument, command element, environment key or value is null
	 * @throws IllegalArgumentException      if the command is empty or a command/environment string contains NUL, or an
	 *                                  environment key is empty or contains equals
	 * @throws IOException                   if startup fails
	 * @throws UnsupportedOperationException if the platform has no verified launch binding
	 */
	public QuietBuildProcess start(List<String> command, Path workingDirectory, Map<String, String> environment)
		throws IOException
	{
		// Lazy class loading can occupy a closed standard descriptor; preserve the entry state for child file actions.
		List<Integer> closedStandardDescriptors = List.of();
		if (unix != null)
			closedStandardDescriptors = unix.closedStandards();

		List<String> arguments = List.copyOf(command);
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		Map<String, String> variables = Map.copyOf(environment);
		if (arguments.isEmpty())
			throw new IllegalArgumentException("An external command requires an executable");
		for (String argument : arguments)
			if (argument.indexOf(0) >= 0)
				throw new IllegalArgumentException("Command arguments must not contain NUL");
		for (Map.Entry<String, String> entry : variables.entrySet())
		{
			if (entry.getKey().isEmpty() || entry.getKey().indexOf('=') >= 0 || entry.getKey().indexOf(0) >= 0 ||
				entry.getValue().indexOf(0) >= 0)
			{
				throw new IllegalArgumentException("Environment keys must be nonempty and exclude '=' and NUL; " +
					"values must exclude NUL");
			}
		}
		String os = System.getProperty("os.name");
		if (os.startsWith("Windows"))
			return new WindowsQuietBuildProcess(arguments, workingDirectory, variables);
		if (os.equals("Linux") || os.equals("Mac OS X"))
			return UnixQuietBuildProcess.start(unix, arguments, workingDirectory, variables, closedStandardDescriptors);
		throw new UnsupportedOperationException("Quiet-build native launch ABI has not been verified for " + os);
	}
}
