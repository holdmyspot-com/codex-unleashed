package com.holdmyspot.codexunleashed.distribution;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

/** Models only registry transport and GitHub inventories while the production script executes real tar and Java. */
public final class GhcrBoundaryFixture
{
	/** Prevents construction. */
	private GhcrBoundaryFixture()
	{
	}

	/**
	 * Records exact argument vectors and serves the explicitly configured local registry or package inventory.
	 *
	 * @param arguments native gh or oras arguments
	 * @throws IOException if fixture access fails or an unexpected operation is requested
	 */
	public static void main(String[] arguments) throws IOException
	{
		try (var output = new DataOutputStream(Files.newOutputStream(
			Path.of(System.getenv("CACHE_TEST_COMMANDS")), StandardOpenOption.CREATE, StandardOpenOption.APPEND)))
		{
			output.writeInt(arguments.length);
			for (String argument : arguments)
				output.writeUTF(argument);
		}
		switch (arguments[0])
		{
			case "api" -> System.exit(github(arguments));
			case "push" -> Files.copy(Path.of("cargo-target.tar.zst"), Path.of(System.getenv("CACHE_TEST_REMOTE")),
				StandardCopyOption.REPLACE_EXISTING);
			case "pull" ->
			{
				if ("1".equals(System.getenv("CACHE_TEST_STABLE_MISS")) && arguments[1].contains(":cargo-v2-"))
					System.exit(1);
				List<String> command = Arrays.asList(arguments);
				Path destination = Path.of(command.get(command.indexOf("--output") + 1));
				Files.copy(Path.of(System.getenv("CACHE_TEST_REMOTE")), destination.resolve("cargo-target.tar.zst"));
			}
			case "tag" ->
			{
				// The argument log is the independent tag-association receipt.
			}
			default -> throw new IOException("Unexpected registry fixture operation: " + Arrays.toString(arguments));
		}
	}

	/**
	 * Supplies the original release and package fixtures and records deletion identities.
	 *
	 * @param arguments gh argument vector
	 * @return configured native process status
	 * @throws IOException if deletion markers cannot be written
	 */
	private static int github(String[] arguments) throws IOException
	{
		List<String> command = Arrays.asList(arguments);
		if (command.contains("--method"))
		{
			String endpoint = command.getLast();
			Files.writeString(Path.of(System.getenv("CACHE_TEST_DELETIONS")),
				endpoint.substring(endpoint.lastIndexOf('/') + 1) + "\n", StandardOpenOption.CREATE,
				StandardOpenOption.APPEND);
			return 0;
		}
		if (command.stream().anyMatch(argument -> argument.contains("repos/openai/codex/releases")))
		{
			String failure = System.getenv("CACHE_TEST_LOOKUP");
			if (failure != null)
				return Integer.parseInt(failure);
			System.out.println("rust-v0.99.0\nrust-v0.158.0\nrust-v0.160.0\nrust-v0.159.1");
			return 0;
		}
		System.out.println("1\tcargo-v2-x86_64-apple-darwin-off-" + "a".repeat(64) + ",cargo-release-" +
			"a".repeat(64) + "-rust-v0.160.0,cargo-x86_64-apple-darwin-rust-v0.158.0\n" +
			"2\tcargo-release-" + "b".repeat(64) + "-rust-v0.159.1\n3\tcargo-x86_64-apple-darwin-rust-v0.159.1\n" +
			"4\tcargo-x86_64-apple-darwin-rust-v0.158.0\n5\t\n6\tcargo-release-" + "c".repeat(64) +
			"-rust-v0.158.0\n7\tcargo-v2-x86_64-pc-windows-msvc-off-" + "d".repeat(64) + "\n8\tunrelated-tag");
		return 0;
	}
}
