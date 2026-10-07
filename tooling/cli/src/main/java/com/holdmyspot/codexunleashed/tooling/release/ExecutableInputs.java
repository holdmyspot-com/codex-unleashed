package com.holdmyspot.codexunleashed.tooling.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves prebuilt CLI inputs using the retained any-execute-bit permission contract.
 */
public final class ExecutableInputs
{
	private static final Set<PosixFilePermission> EXECUTE_PERMISSIONS = Set.of(PosixFilePermission.OWNER_EXECUTE,
		PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE);

	/**
	 * Prevents construction.
	 */
	private ExecutableInputs()
	{
	}

	/**
	 * Resolves a supplied file and requires any POSIX execute bit, or native executable access on another filesystem.
	 *
	 * @param input the supplied executable path
	 * @param description the executable description used in failures
	 * @return the canonical executable path
	 * @throws IOException if resolution, file validation, or executable validation fails
	 * @throws NullPointerException if an argument is null
	 */
	public static Path resolve(Path input, String description) throws IOException
	{
		Objects.requireNonNull(input, "input");
		Objects.requireNonNull(description, "description");
		Path file = input.toRealPath();
		if (!Files.isRegularFile(file))
			throw new IOException(description + " is not a regular file: " + file);
		PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
		boolean executable;
		if (view == null)
			executable = Files.isExecutable(file);
		else
			executable = !Collections.disjoint(view.readAttributes().permissions(), EXECUTE_PERMISSIONS);
		if (!executable)
			throw new IOException(description + " is not executable: " + file);
		return file;
	}
}
