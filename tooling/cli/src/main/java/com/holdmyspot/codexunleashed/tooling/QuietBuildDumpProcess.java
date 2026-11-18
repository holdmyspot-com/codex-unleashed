package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.Writer;
import java.time.Duration;
import java.util.List;

/**
 * Runs one diagnostic child and writes its output to a caller-owned report.
 */
@FunctionalInterface
public interface QuietBuildDumpProcess
{
	/**
	 * Runs a child command with the supplied context and timeout.
	 *
	 * @param command executable and literal arguments
	 * @param context child invocation paths and explicitly supplied variables
	 * @param output  borrowed report writer that remains open after this call
	 * @param timeout maximum time allowed for the child
	 * @return the child's ordinary exit status, including nonzero values
	 * @throws NullPointerException                  if an argument or command element is null
	 * @throws IllegalArgumentException              if {@code command} is empty or {@code timeout} is negative
	 * @throws QuietBuildReport.DumpTimeoutException if the child exceeds {@code timeout}
	 * @throws IOException                           if starting the child, streaming its output, waiting, or cleanup
	 *                                               fails
	 */
	int run(List<String> command, QuietBuildCapture.Context context, Writer output, Duration timeout) throws IOException;
}
