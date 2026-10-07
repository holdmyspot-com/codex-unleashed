package com.holdmyspot.codexunleashed.tooling.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

/**
 * Verifies the prebuilt CLI input contract independently of packaged owner-execute validation.
 */
public final class ExecutableInputsTest
{
	/**
	 * Creates executable input tests.
	 */
	public ExecutableInputsTest()
	{
	}

	/**
	 * Accepts any POSIX execute bit and rejects readable files that have none.
	 *
	 * @throws IOException if fixture access or input resolution fails
	 */
	@Test
	public void acceptsAnyExecuteBit() throws IOException
	{
		Path root = Files.createTempDirectory("executable-inputs-");
		Path executable = root.resolve("tool.exe");
		try
		{
			Files.write(executable, new byte[] {0, (byte) 255});
			if (Files.getFileAttributeView(executable, PosixFileAttributeView.class) == null)
				assertEquals(ExecutableInputs.resolve(executable, "fixture executable"), executable.toRealPath());
			else
			{
				for (String mode : new String[]{"rwx------", "rw---x---", "rw------x"})
				{
					Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString(mode));
					assertEquals(ExecutableInputs.resolve(executable, "fixture executable"), executable.toRealPath());
				}
				Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rw-------"));
				expectThrows(IOException.class, () -> ExecutableInputs.resolve(executable, "fixture executable"));
			}
		}
		finally
		{
			Files.deleteIfExists(executable);
			Files.delete(root);
		}
	}

	/**
	 * Rejects absent files and executable directories instead of handing them off as prebuilt binaries.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void rejectsMissingAndDirectoryInputs() throws IOException
	{
		Path root = Files.createTempDirectory("invalid-executable-inputs-");
		try
		{
			expectThrows(IOException.class, () -> ExecutableInputs.resolve(root.resolve("missing"), "fixture executable"));
			expectThrows(IOException.class, () -> ExecutableInputs.resolve(root, "fixture executable"));
		}
		finally
		{
			Files.delete(root);
		}
	}
}
