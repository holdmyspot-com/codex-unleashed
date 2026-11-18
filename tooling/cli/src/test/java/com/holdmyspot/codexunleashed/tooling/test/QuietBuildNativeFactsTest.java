package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DarwinQuietBuildFacts;
import com.holdmyspot.codexunleashed.tooling.QuietBuildCapture;
import com.holdmyspot.codexunleashed.tooling.QuietBuildDump;
import com.holdmyspot.codexunleashed.tooling.QuietBuildJavaDump;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests pure Darwin formatting and JVM diagnostic command boundaries.
 */
public final class QuietBuildNativeFactsTest
{
	/**
	 * Creates native facts tests.
	 */
	public QuietBuildNativeFactsTest()
	{
	}

	/**
	 * Formats available Darwin process and thread stop facts.
	 *
	 * @throws IOException if the report writer fails
	 */
	@Test
	public void formatsDarwinStopFacts() throws IOException
	{
		StringWriter output = new StringWriter();
		DarwinQuietBuildFacts.writeStop(output, 4, 2,
			List.of(new DarwinQuietBuildFacts.ThreadState(2, 1), new DarwinQuietBuildFacts.ThreadState(4, 0)));
		requireThat(output.toString(), "output").isEqualTo("stop: bsd_status=SSTOP task_suspend_count=2 " +
			"threads=stopped/1,uninterruptible/0\n");
	}

	/**
	 * Formats Darwin stop signals in stable lexical order.
	 *
	 * @throws IOException if the report writer fails
	 */
	@Test
	public void formatsDarwinSignals() throws IOException
	{
		StringWriter output = new StringWriter();
		DarwinQuietBuildFacts.writeSignals(output, true, 18, (1L << 14) | (1L << 16));
		requireThat(output.toString(), "output").isEqualTo("signals: traced=yes stop_signal=SIGTSTP " +
			"pending=SIGSTOP,SIGTERM\n");
	}

	/**
	 * Adds a non-interactive user switch only for root Unix diagnostics.
	 *
	 * @throws IOException if the diagnostic capture or report writer fails
	 */
	@Test
	public void switchesJvmUserOnlyWhenRoot() throws IOException
	{
		AtomicReference<List<String>> observed = new AtomicReference<>();
		QuietBuildJavaDump dump = new QuietBuildJavaDump((command, context, timeout) ->
		{
			observed.set(command);
			return captured("out", "err");
		}, context(), QuietBuildDump.Platform.LINUX, 0);
		StringWriter output = new StringWriter();
		dump.write(501, List.of("/runtime/jcmd", "12"), Duration.ofSeconds(30), output);
		requireThat(observed.get(), "command").isEqualTo(List.of("sudo", "-n", "-u#501", "/runtime/jcmd", "12"));
		requireThat(output.toString(), "output").isEqualTo("outerr");
	}

	/**
	 * Preserves diagnostic command failure text in the report.
	 *
	 * @throws IOException if report writing fails
	 */
	@Test
	public void keepsDiagnosticFailureText() throws IOException
	{
		QuietBuildJavaDump dump = new QuietBuildJavaDump((command, context, timeout) ->
		{
			throw new IOException("permission denied");
		}, context(), QuietBuildDump.Platform.LINUX, 1000);
		StringWriter output = new StringWriter();
		dump.write(1000, List.of("jcmd", "12"), Duration.ofSeconds(30), output);
		requireThat(output.toString(), "output").isEqualTo("<jcmd failed: permission denied>\n");
	}

	/**
	 * Creates a minimal invocation context for fake command capture.
	 *
	 * @return invocation context
	 */
	private static QuietBuildCapture.Context context()
	{
		Path current = Path.of(".").toAbsolutePath().normalize();
		return new QuietBuildCapture.Context(current, current, Map.of());
	}

	/**
	 * Creates a fake capture containing supplied stream text.
	 *
	 * @param stdout captured standard output
	 * @param stderr captured standard error
	 * @return fake capture
	 */
	private static QuietBuildCapture.Captured captured(String stdout, String stderr)
	{
		return new QuietBuildCapture.Captured()
		{
			@Override
			public int status()
			{
				return 7;
			}

			@Override
			public java.io.Reader stdout()
			{
				return new StringReader(stdout);
			}

			@Override
			public java.io.Reader stderr()
			{
				return new StringReader(stderr);
			}

			@Override
			public void close()
		{
			}
		};
	}
}
