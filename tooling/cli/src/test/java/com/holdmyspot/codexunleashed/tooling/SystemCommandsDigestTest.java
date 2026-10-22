package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies raw output hashes, explicit process context, and capture cleanup.
 */
public final class SystemCommandsDigestTest
{
	/**
	 * Creates the digest tests.
	 */
	public SystemCommandsDigestTest()
	{
	}

	/**
	 * Hashes invalid UTF-8 bytes without decoding them and retains both ordinary exit statuses.
	 * The expected digest is independently calculated by GNU sha256sum for 200,000 bytes of 0xff.
	 *
	 * @throws IOException if fixture access or capture fails
	 * @throws URISyntaxException if the Java fixture location is invalid
	 */
	@Test
	public void hashesRawOutputWithExplicitContext() throws IOException, URISyntaxException
	{
		Path root = Files.createTempDirectory("binary capture ");
		Path captures = Files.createDirectory(root.resolve("captures"));
		try
		{
			for (int status : new int[]{0, 7})
			{
				SystemCommands.DigestResult result = SystemCommands.digest(JavaFixtures.command(BinaryCommandFixture.class,
					root.toString(), Integer.toString(status)), root, captures,
					Map.of("CODEX_UNLEASHED_CAPTURE_MARKER", "explicit context"));
				requireThat(result.status(), "status").isEqualTo(status);
				requireThat(result.stdoutSha256(), "stdoutSha256").isEqualTo(
					"2b48f79297030f8a1bbbdfc58f6607b5ce7a8c5c6624cbe0a1a6a8ecb76d0db4");
				requireThat(result.stderr(), "stderr").isEqualTo("e".repeat(200_000));
				try (Stream<Path> files = Files.list(captures))
				{
					requireThat(files.count(), "captureCount").isEqualTo(0L);
				}
			}
		}
		finally
		{
			Files.delete(captures);
			Files.delete(root);
		}
	}
}
