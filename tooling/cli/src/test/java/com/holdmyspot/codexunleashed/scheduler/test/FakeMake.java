package com.holdmyspot.codexunleashed.scheduler.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/**
 * A {@code make} stand-in that records what the lease's make front end hands to the real make.
 * <p>
 * How GNU Make reads its jobserver flags is GNU Make's documented behavior. These tests check only what our front end
 * passes on: the make variables, the open descriptors and the arguments.
 */
final class FakeMake
{
	/** The directory that holds the stand-in, to be placed first on the lease's search path. */
	final Path directory;
	/** The file the stand-in writes its observations to. */
	private final Path observed;

	/**
	 * Writes the stand-in into a new directory.
	 * @param parent an existing directory that the caller owns
	 * @param read the jobserver read descriptor whose target to record
	 * @param write the jobserver write descriptor whose target to record
	 * @throws IOException if the stand-in cannot be written
	 */
	FakeMake(Path parent, int read, int write) throws IOException
	{
		directory = Files.createDirectory(parent.resolve("fake-make"));
		observed = directory.resolve("observed");
		Path make = directory.resolve("make");
		Files.writeString(make, """
			#!/bin/sh
			{
			  printf '%%s\\n' "${MAKEFLAGS-unset}" "${CARGO_MAKEFLAGS-unset}" "${MFLAGS-unset}"
			  readlink "/proc/$$/fd/%1$d" || echo closed
			  readlink "/proc/$$/fd/%2$d" || echo closed
			  printf '%%s\\n' "$@"
			} > '%3$s'
			""".formatted(read, write, observed));
		Files.setPosixFilePermissions(make, PosixFilePermissions.fromString("rwx------"));
	}

	/**
	 * Returns what the stand-in recorded.
	 * @return MAKEFLAGS, CARGO_MAKEFLAGS, MFLAGS ({@code unset} when absent), the read and write descriptor targets
	 *   ({@code closed} when not open), then each argument
	 * @throws IOException if the stand-in did not run
	 */
	List<String> observations() throws IOException
	{
		return Files.readAllLines(observed, StandardCharsets.UTF_8);
	}
}
