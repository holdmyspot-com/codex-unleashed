package com.holdmyspot.codexunleashed.distribution;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Supplies inert native Bazel build/query/info behavior for workflow delivery checks. */
public final class V8ChildFixture
{
	/** Prevents construction. */
	private V8ChildFixture()
	{
	}

	/**
	 * Records actual arguments and cwd, materializes a small artifact pair, or reports its paths.
	 *
	 * @param arguments native Bazel arguments
	 * @throws IOException if fixture output access fails
	 */
	public static void main(String[] arguments) throws IOException
	{
		Path root = Path.of("").toAbsolutePath();
		try (var output = new DataOutputStream(Files.newOutputStream(Path.of(System.getenv("V8_FIXTURE_LOG")),
			java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)))
		{
			output.writeUTF(root.toString());
			output.writeUTF(String.join("\0", arguments));
		}
		List<String> args = Arrays.asList(arguments);
		if (args.contains("build"))
		{
			Path output = Files.createDirectories(root.resolve("bazel-fixture"));
			Files.write(output.resolve("library.a"), new byte[]{0, (byte) 255, 7});
			Files.write(output.resolve("binding.rs"), new byte[]{1, (byte) 254, 9});
		}
		else if (args.contains("cquery"))
			System.out.println("bazel-fixture/library.a\nbazel-fixture/binding.rs");
		else if (args.contains("info"))
			System.out.println(root);
		else
			throw new IOException("unexpected fixture Bazel command: " + args);
	}
}
