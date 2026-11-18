package com.holdmyspot.codexunleashed.tooling;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Serial;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Appends a quiet-build header and one dump runner's output to a UTF-8 report file.
 */
public final class QuietBuildReport
{
	/**
	 * Output file receiving appended quiet-build reports.
	 */
	private final Path reportPath;

	/**
	 * Collaborator that writes one process report.
	 */
	private final DumpRunner dumpRunner;

	/**
	 * Creates a report appender.
	 *
	 * @param reportPath file that receives appended report headers and dump output
	 * @param dumpRunner collaborator that writes one dump using the supplied writer
	 * @throws NullPointerException if an argument is null
	 */
	public QuietBuildReport(Path reportPath, DumpRunner dumpRunner)
	{
		requireThat(reportPath, "reportPath").isNotNull();
		requireThat(dumpRunner, "dumpRunner").isNotNull();
		this.reportPath = reportPath;
		this.dumpRunner = dumpRunner;
	}

	/**
	 * Returns a factory that constructs report appenders.
	 *
	 * @return factory for report appenders
	 */
	public static Factory factory()
	{
		return QuietBuildReport::new;
	}

	/**
	 * Appends a rounded quiet-duration header and the runner's output. The header is flushed before the runner executes;
	 * a runner timeout appends {@code <process report timed out>} followed by a newline.
	 *
	 * @param quiet quiet duration, rounded to whole seconds using ties-to-even
	 * @param reportTime report instant, formatted in UTC to whole seconds
	 * @param jcmd optional path supplied to the dump runner
	 * @throws NullPointerException if an argument is null
	 * @throws IOException if the report cannot be opened, written, or closed, or the runner raises another I/O failure
	 */
	public void append(Duration quiet, Instant reportTime, Optional<Path> jcmd) throws IOException
	{
		requireThat(quiet, "quiet").isNotNull();
		requireThat(reportTime, "reportTime").isNotNull();
		requireThat(jcmd, "jcmd").isNotNull();

		try (BufferedWriter output = Files.newBufferedWriter(reportPath, StandardCharsets.UTF_8,
			StandardOpenOption.CREATE, StandardOpenOption.APPEND))
		{
			String roundedQuietSeconds = roundedQuietSeconds(quiet);
			String utcReportTime = DateTimeFormatter.ISO_INSTANT.format(reportTime.truncatedTo(ChronoUnit.SECONDS));
			output.write("=== quiet_seconds=%s time=%s\n".formatted(roundedQuietSeconds, utcReportTime));
			output.flush();
			try
			{
				dumpRunner.dump(jcmd, output, Duration.ofSeconds(600));
			}
			catch (DumpTimeoutException _)
			{
				output.write("<process report timed out>\n");
			}
		}
	}

	/**
	 * Formats a signed duration as whole seconds using ties-to-even rounding.
	 *
	 * @param quiet signed quiet duration
	 * @return rounded whole seconds, preserving a negative sign when the result is zero
	 */
	private static String roundedQuietSeconds(Duration quiet)
	{
		BigDecimal seconds = BigDecimal.valueOf(quiet.getSeconds()).add(BigDecimal.valueOf(quiet.getNano(), 9));
		String rounded = seconds.setScale(0, RoundingMode.HALF_EVEN).toPlainString();
		if (quiet.isNegative() && rounded.equals("0"))
			return "-0";
		return rounded;
	}

	/**
	 * Writes a process report to the borrowed writer before returning.
	 */
	@FunctionalInterface
	public interface DumpRunner
	{
		/**
		 * Writes dump output to the report writer using the supplied optional {@code jcmd} path.
		 *
		 * @param jcmd optional {@code jcmd} executable path supplied by the caller
		 * @param output borrowed writer positioned after the flushed header; the runner must not close it
		 * @param timeout maximum duration allowed for this dump
		 * @throws IOException if the dump runner encounters an I/O failure
		 * @throws DumpTimeoutException if the dump runner reaches its timeout
		 */
		void dump(Optional<Path> jcmd, Writer output, Duration timeout) throws IOException;
	}

	/**
	 * Creates report appenders for caller-selected paths and dump runners.
	 */
	@FunctionalInterface
	public interface Factory
	{
		/**
		 * Creates a report appender for the supplied file and dump runner.
		 *
		 * @param reportPath file that receives appended report headers and dump output
		 * @param dumpRunner collaborator that writes one dump using the supplied writer
		 * @return appender that writes to {@code reportPath} using {@code dumpRunner}
		 * @throws NullPointerException if an argument is null
		 */
		QuietBuildReport create(Path reportPath, DumpRunner dumpRunner);
	}

	/**
	 * Indicates that the dump runner exceeded its report timeout.
	 */
	public static final class DumpTimeoutException extends IOException
	{
		/**
		 * Serialization identifier for saved dump timeout exceptions.
		 */
		@Serial
		private static final long serialVersionUID = 1L;

		/**
		 * Creates a dump timeout failure.
		 *
		 * @param message explanation of the timeout
		 */
		public DumpTimeoutException(String message)
		{
			super(message);
		}
	}
}
