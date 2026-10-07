package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.util.List;

/**
 * Executes an external command and returns its standard output.
 */
@FunctionalInterface
public interface CommandRunner
{
	/**
	 * Executes the command with explicit arguments and fails on a nonzero exit status.
	 *
	 * @param command the executable followed by its arguments
	 * @return the command's standard output
	 * @throws IOException if launching, reading, or completing the command fails
	 */
	String run(List<String> command) throws IOException;
}
