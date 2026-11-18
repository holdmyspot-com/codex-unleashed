package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Observes fixed diagnostic tools at the producer boundary without replaying historical records.
 */
public final class NativeToolObservations
{
	/** Required stable tool order. */
	private static final List<String> NAMES = List.of("rustc", "cargo", "git", "zig", "cc", "cxx", "linker",
		"strip", "objcopy", "dsymutil", "python", "zstd");

	/**
	 * Prevents construction.
	 */
	private NativeToolObservations()
	{
	}

	/**
	 * Collects fresh diagnostic observations through fixed Bash argument boundaries.
	 *
	 * @param scope borrowed JSON services
	 * @param capture explicitly injected process boundary
	 * @param environment explicitly resolved current process settings
	 * @return the complete stable tool inventory
	 * @throws IOException if Bash startup, capture, decoding or lookup protocol fails
	 */
	public static JsonNode collect(ToolingScope scope, SystemCommands.Capture capture,
		ReleaseManifestNativeInputs.Environment environment) throws IOException
	{
		ArrayNode tools = scope.getJsonMapper().createArrayNode();
		for (String name : NAMES)
		{
			ObjectNode tool = tools.addObject().put("name", name);
			tool.putNull("path").putNull("version");
			if (name.equals("dsymutil") && !environment.runnerOs().equals("macOS"))
			{
				tool.put("status", "not_applicable");
				continue;
			}
			String executable = executable(name, environment.runnerOs());
			SystemCommands.Result lookup = probe(capture, environment, "type -P -- \"$1\"", executable);
			String path = terminalLines(lookup.stdout());
			boolean unavailable = lookup.status() == 1 && path.isEmpty() && lookup.stderr().isEmpty();
			if (unavailable)
			{
				tool.put("status", "unavailable");
				continue;
			}
			boolean validPath = !path.isEmpty() && path.indexOf('\n') < 0 && path.indexOf('\r') < 0 &&
				path.indexOf('\0') < 0;
			if (lookup.status() != 0 || !validPath || !lookup.stderr().isEmpty())
				throw new IOException("Invalid diagnostic tool lookup for " + name);
			tool.put("status", "observed").put("path", path);
			SystemCommands.Result version = probe(capture, environment, "\"$1\" --version", executable);
			if (version.status() == 0)
			{
				String text = terminalLines(version.stdout());
				if (text.isEmpty())
					text = terminalLines(version.stderr());
				if (!text.isEmpty())
					tool.put("version", text);
			}
		}
		return tools;
	}

	/**
	 * Chooses only a fixed project-defined executable name.
	 *
	 * @param name stable diagnostic tool role
	 * @param runnerOs resolved runner platform
	 * @return approved executable name
	 */
	private static String executable(String name, String runnerOs)
	{
		boolean windows = runnerOs.equals("Windows");
		return switch (name)
		{
			case "cc", "cxx" -> compiler(name, windows);
			case "linker" -> linker(windows);
			case "python" -> "python3";
			default -> name;
		};
	}

	/**
	 * Selects the fixed C or C++ compiler name for a platform.
	 *
	 * @param name diagnostic role
	 * @param windows whether MSVC is selected
	 * @return fixed compiler executable
	 */
	private static String compiler(String name, boolean windows)
	{
		if (windows)
			return "cl";
		if (name.equals("cxx"))
			return "c++";
		return "cc";
	}

	/**
	 * Selects the fixed platform linker name.
	 *
	 * @param windows whether MSVC is selected
	 * @return fixed linker executable
	 */
	private static String linker(boolean windows)
	{
		if (windows)
			return "link";
		return "ld";
	}

	/**
	 * Runs one fixed script with the executable supplied only as a positional argument.
	 *
	 * @param capture injected process boundary
	 * @param environment current process settings
	 * @param script fixed project script
	 * @param executable fixed executable name
	 * @return captured command result
	 * @throws IOException if capture fails
	 */
	private static SystemCommands.Result probe(SystemCommands.Capture capture,
		ReleaseManifestNativeInputs.Environment environment, String script, String executable) throws IOException
	{
		return capture.capture(List.of(environment.bashExecutable(), "-c", script, "release-native-inputs", executable),
			environment.workingDirectory(), environment.temporaryDirectory(), environment.variables());
	}

	/**
	 * Removes only terminal CR and LF characters, preserving other whitespace and embedded lines.
	 *
	 * @param text captured output
	 * @return output without final line endings
	 */
	private static String terminalLines(String text)
	{
		int end = text.length();
		while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r'))
			--end;
		return text.substring(0, end);
	}
}
