package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Runs one JVM diagnostic command and streams its captured output to a report.
 */
public final class QuietBuildJavaDump
{
	/**
	 * Buffer size for copying a diagnostic stream to the report.
	 */
	private static final int COPY_BUFFER_SIZE = 8192;
	/**
	 * Process capture borrowed from the current tooling invocation.
	 */
	private final QuietBuildCapture.Access capture;
	/**
	 * Complete child-process context borrowed for diagnostic commands.
	 */
	private final QuietBuildCapture.Context context;
	/**
	 * Platform that determines whether a Unix user switch applies.
	 */
	private final QuietBuildDump.Platform platform;
	/**
	 * Effective user ID of the diagnostic process.
	 */
	private final long effectiveUid;

	/**
	 * Creates a JVM diagnostic command writer.
	 *
	 * @param capture invocation-owned external command capture
	 * @param context child working directory, temporary directory and environment
	 * @param platform operating system used to choose user-switch behavior
	 * @param effectiveUid current effective Unix user ID
	 * @throws NullPointerException if {@code capture}, {@code context} or {@code platform} is null
	 */
	public QuietBuildJavaDump(QuietBuildCapture.Access capture, QuietBuildCapture.Context context,
		QuietBuildDump.Platform platform, long effectiveUid)
	{
		requireThat(capture, "capture").isNotNull();
		requireThat(context, "context").isNotNull();
		requireThat(platform, "platform").isNotNull();
		this.capture = capture;
		this.context = context;
		this.platform = platform;
		this.effectiveUid = effectiveUid;
	}

	/**
	 * Runs a JVM diagnostic and writes stdout followed by stderr; ordinary nonzero status still contributes output.
	 *
	 * @param uid target process owner used for a non-interactive root user switch on Unix
	 * @param command executable and literal arguments
	 * @param timeout maximum command run time
	 * @param output report writer, which remains open
	 * @throws NullPointerException if {@code command}, {@code timeout} or {@code output} is null
	 * @throws IllegalArgumentException if {@code command} is empty
	 * @throws IOException if captured output cannot be read, written, or released
	 */
	public void write(long uid, List<String> command, Duration timeout, Writer output) throws IOException
	{
		requireThat(command, "command").isNotNull().isNotEmpty();
		requireThat(timeout, "timeout").isNotNull();
		requireThat(output, "output").isNotNull();
		List<String> actualCommand = command;
		if (platform != QuietBuildDump.Platform.WINDOWS && effectiveUid == 0 && uid != 0)
		{
			actualCommand = new ArrayList<>(command.size() + 3);
			actualCommand.add("sudo");
			actualCommand.add("-n");
			actualCommand.add("-u#" + uid);
			actualCommand.addAll(command);
		}

		QuietBuildCapture.Captured captured;
		try
		{
			captured = capture.capture(actualCommand, context, Optional.of(timeout));
		}
		catch (IOException failure)
		{
			writeFailure(actualCommand.getFirst(), timeout, failure, output);
			return;
		}
		try (captured)
		{
			copy(captured.stdout(), output);
			copy(captured.stderr(), output);
		}
	}

	/**
	 * Copies data for copy.
	 *
	 * @param input supplied input
	 * @param output supplied output
	 * @throws IOException when the operation fails
	 */
	private static void copy(Reader input, Writer output) throws IOException
	{
		char[] buffer = new char[COPY_BUFFER_SIZE];
		for (int count = input.read(buffer); count != -1; count = input.read(buffer))
		{
			output.write(buffer, 0, count);
		}
	}

	/**
	 * Performs data for writeFailure.
	 *
	 * @param executable supplied executable
	 * @param timeout supplied timeout
	 * @param failure supplied failure
	 * @param output supplied output
	 * @throws IOException if diagnostic failure text cannot be written
	 */
	private static void writeFailure(String executable, Duration timeout, IOException failure, Writer output)
		throws IOException
	{
		String description = failure.getMessage();
		if (failure instanceof QuietBuildProcessAccess.CaptureTimeoutException)
			description = "timed out after " + timeout;
		if (description == null || description.isBlank())
			description = "could not start or capture the diagnostic process";
		output.write("<" + executable + " failed: " + description + ">\n");
	}
}
