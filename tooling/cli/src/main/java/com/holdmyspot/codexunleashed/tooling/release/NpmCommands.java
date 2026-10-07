package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Resolves npm's native entry point while retaining direct process argument boundaries.
 */
final class NpmCommands
{
	/** Prevents construction. */
	private NpmCommands()
	{
	}

	/**
	 * Uses the ordinary Unix executable or the JavaScript entry point selected by npm's Windows launcher.
	 *
	 * @param directory the npm working directory
	 * @param temporary the owned capture parent
	 * @param environment the child environment
	 * @return the executable and any entry-point argument
	 * @throws IOException if the installed npm launcher or prefix lookup fails
	 */
	static List<String> command(Path directory, Path temporary, Map<String, String> environment) throws IOException
	{
		if (!directory.getFileSystem().getSeparator().equals("\\"))
			return List.of("npm");
		String searchPath = environment.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase("PATH")).
			map(Map.Entry::getValue).findFirst().orElse("");
		for (String entry : searchPath.split(";", -1))
		{
			String component = entry;
			if (component.startsWith("\"") && component.endsWith("\"") && component.length() >= 2)
				component = component.substring(1, component.length() - 1);
			Path launcher = directory.resolve(component).resolve("npm.cmd");
			if (Files.isRegularFile(launcher))
				return windowsCommand(launcher, directory, temporary, environment);
		}
		throw new IOException("Unable to find npm.cmd in the child PATH");
	}

	/**
	 * Mirrors npm.cmd's adjacent Node selection and optional global-prefix CLI selection without a command shell.
	 *
	 * @param launcher the installed npm.cmd path
	 * @param directory the npm working directory
	 * @param temporary the capture parent
	 * @param environment the child environment
	 * @return the Node executable and selected npm JavaScript entry point
	 * @throws IOException if the prefix command fails or the npm entry point is absent
	 */
	static List<String> windowsCommand(Path launcher, Path directory, Path temporary,
		Map<String, String> environment) throws IOException
	{
		Path home = launcher.toAbsolutePath().getParent();
		Path adjacentNode = home.resolve("node.exe");
		String node = "node";
		if (Files.isRegularFile(adjacentNode))
			node = adjacentNode.toString();
		Path bin = home.resolve("node_modules/npm/bin");
		Path cli = bin.resolve("npm-cli.js");
		SystemCommands.Result prefix = SystemCommands.capture(List.of(node, bin.resolve("npm-prefix.js").toString()),
			directory, temporary, environment);
		if (prefix.status() != 0)
			throw new IOException("npm prefix lookup failed with status " + prefix.status() + ": " + prefix.stderr());
		for (String line : prefix.stdout().lines().toList())
		{
			if (line.isEmpty())
				continue;
			Path candidate = Path.of(line).resolve("node_modules/npm/bin/npm-cli.js");
			if (Files.isRegularFile(candidate))
				cli = candidate;
		}
		if (!Files.isRegularFile(cli))
			throw new IOException("Missing npm JavaScript entry point: " + cli);
		return List.of(node, cli.toString());
	}
}
