package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.QuietBuildCapture;
import com.holdmyspot.codexunleashed.tooling.QuietBuildDump;
import com.holdmyspot.codexunleashed.tooling.QuietBuildDumpProcess;
import com.holdmyspot.codexunleashed.tooling.QuietBuildDumpRunner;
import com.holdmyspot.codexunleashed.tooling.QuietBuildReport;
import com.holdmyspot.codexunleashed.tooling.ToolingLauncher;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests packaged command selection by {@link QuietBuildDumpRunner}.
 */
public final class QuietBuildDumpRunnerTest
{
	/**
	 * Creates tests with invocation-local command and capture state.
	 */
	public QuietBuildDumpRunnerTest()
	{
	}

	/**
	 * An already-root Unix invocation passes launcher and jcmd paths as literal arguments without a sudo probe.
	 *
	 * @throws IOException if diagnostic command selection fails
	 */
	@Test
	public void rootUsesPackagedLauncher() throws IOException
	{
		Path launcher = Path.of("packaged launcher/bin/quiet-build");
		Path jcmd = Path.of("jdk with spaces/bin/jcmd");
		QuietBuildCapture.Context context = new QuietBuildCapture.Context(Path.of("work"), Path.of("temp"),
			Map.of());
		List<String> command = new ArrayList<>();
		QuietBuildCapture.Access capture = (arguments, childContext, timeout) ->
		{
			throw new UnsupportedOperationException("An already-root invocation must not probe for elevation");
		};
		QuietBuildDumpProcess dumpProcess = (arguments, childContext, output, timeout) ->
		{
			command.addAll(arguments);
			return 0;
		};
		QuietBuildDumpRunner runner = new QuietBuildDumpRunner(capture, dumpProcess, context,
			QuietBuildDump.Platform.LINUX, launcher, 0);

		runner.dump(Optional.of(jcmd), new StringWriter(), Duration.ofSeconds(30));

		requireThat(command, "command").isEqualTo(List.of(launcher.toString(), "watch-quiet-build", "--dump",
			"--jcmd", jcmd.toString()));
	}

	/**
	 * The noninteractive sudo exit status selects elevation for a nonroot Unix diagnostic command.
	 * The in-memory capture supplies exit statuses defined by {@link QuietBuildCapture.Captured}.
	 *
	 * @param platform       Unix platform selecting the probe
	 * @param probeStatus    sudo exit status
	 * @param expectedPrefix privilege arguments selected from that status
	 * @throws IOException if command selection fails
	 */
	@Test(dataProvider = "unixProbeResults")
	public void nonrootSelectsPrivilegeByProbe(QuietBuildDump.Platform platform, int probeStatus,
		List<String> expectedPrefix) throws IOException
	{
		Path launcher = Path.of("packaged launcher/bin/quiet-build");
		QuietBuildCapture.Context context = new QuietBuildCapture.Context(Path.of("work"), Path.of("temp"),
			Map.of());
		List<String> probe = new ArrayList<>();
		List<String> command = new ArrayList<>();
		List<String> lifecycle = new ArrayList<>();
		StringWriter diagnosticOutput = new StringWriter();
		Duration diagnosticTimeout = Duration.ofSeconds(45);
		QuietBuildCapture.Access capture = (arguments, childContext, timeout) ->
		{
			probe.addAll(arguments);
			requireThat(childContext, "childContext").isEqualTo(context);
			requireThat(timeout, "timeout").isEqualTo(Optional.of(Duration.ofSeconds(30)));
			return new QuietBuildCapture.Captured()
			{
				@Override
				public int status()
				{
					return probeStatus;
				}

				@Override
				public Reader stdout()
				{
					return Reader.nullReader();
				}

				@Override
				public Reader stderr()
				{
					return Reader.nullReader();
				}

				@Override
				public void close()
				{
					lifecycle.add("probe_closed");
				}
			};
		};
		QuietBuildDumpProcess dumpProcess = (arguments, childContext, output, timeout) ->
		{
			lifecycle.add("dump_started");
			command.addAll(arguments);
			requireThat(childContext, "childContext").isEqualTo(context);
			requireThat(output, "output").isReferenceEqualTo(diagnosticOutput, "diagnosticOutput");
			requireThat(timeout, "timeout").isEqualTo(diagnosticTimeout);
			return 0;
		};
		QuietBuildDumpRunner runner = new QuietBuildDumpRunner(capture, dumpProcess, context,
			platform, launcher, 1000);

		runner.dump(Optional.empty(), diagnosticOutput, diagnosticTimeout);

		List<String> expectedCommand = new ArrayList<>(expectedPrefix);
		expectedCommand.addAll(List.of(launcher.toString(), "watch-quiet-build", "--dump"));
		requireThat(probe, "probe").isEqualTo(List.of("sudo", "-n", "true"));
		requireThat(command, "command").isEqualTo(expectedCommand);
		requireThat(lifecycle, "lifecycle").isEqualTo(List.of("probe_closed", "dump_started"));
	}

	/**
	 * Supplies successful and refused elevation on each supported Unix platform.
	 *
	 * @return platform, sudo exit status, and expected privilege arguments
	 */
	@DataProvider
	public Object[][] unixProbeResults()
	{
		return new Object[][]
		{
			{QuietBuildDump.Platform.LINUX, 0, List.of("sudo", "-n")},
			{QuietBuildDump.Platform.LINUX, 1, List.of()},
			{QuietBuildDump.Platform.DARWIN, 0, List.of("sudo", "-n")},
			{QuietBuildDump.Platform.DARWIN, 1, List.of()}
		};
	}

	/**
	 * An unavailable sudo executable leaves diagnostics running with the current user's privileges.
	 * The capture access supplies its documented startup IOException without starting a real process.
	 *
	 * @throws IOException if diagnostics incorrectly propagate the unavailable privilege probe
	 */
	@Test
	public void unavailableSudoUsesCurrentUser() throws IOException
	{
		Path launcher = Path.of("packaged launcher/bin/quiet-build");
		QuietBuildCapture.Context context = new QuietBuildCapture.Context(Path.of("work"), Path.of("temp"),
			Map.of());
		List<String> command = new ArrayList<>();
		QuietBuildCapture.Access capture = (arguments, childContext, timeout) ->
		{
			throw new IOException("Cannot start sudo");
		};
		QuietBuildDumpProcess dumpProcess = (arguments, childContext, output, timeout) ->
		{
			command.addAll(arguments);
			return 0;
		};
		QuietBuildDumpRunner runner = new QuietBuildDumpRunner(capture, dumpProcess, context,
			QuietBuildDump.Platform.DARWIN, launcher, 1000);

		runner.dump(Optional.empty(), new StringWriter(), Duration.ofSeconds(45));

		requireThat(command, "command").isEqualTo(List.of(launcher.toString(), "watch-quiet-build", "--dump"));
	}

	/**
	 * Windows diagnostics run directly even when the supplied user ID is nonzero.
	 *
	 * @throws IOException if diagnostic command selection fails
	 */
	@Test
	public void windowsNeverProbesSudo() throws IOException
	{
		Path launcher = Path.of("packaged launcher/bin/codex-tooling");
		Path jcmd = Path.of("jdk space/%KEEP% &!^'/bin/jcmd.exe");
		QuietBuildCapture.Context context = new QuietBuildCapture.Context(Path.of("work"), Path.of("temp"),
			Map.of());
		List<String> command = new ArrayList<>();
		QuietBuildCapture.Access capture = (arguments, childContext, timeout) ->
		{
			throw new UnsupportedOperationException("Windows must not probe for sudo");
		};
		QuietBuildDumpProcess dumpProcess = (arguments, childContext, output, timeout) ->
		{
			command.addAll(arguments);
			return 0;
		};
		QuietBuildDumpRunner runner = new QuietBuildDumpRunner(capture, dumpProcess, context,
			QuietBuildDump.Platform.WINDOWS, launcher, 1000);

		runner.dump(Optional.of(jcmd), new StringWriter(), Duration.ofSeconds(45));

		requireThat(command, "command").isEqualTo(List.of(launcher.resolveSibling("java.exe").toString(),
			"-Djava.io.tmpdir=" + context.temporaryDirectory(), "-Dcodex.tooling.launcher=" + launcher,
			"-Xlog:all=off:stdout", "-Xlog:all=warning:stderr",
			"-Djdk.lang.Process.allowAmbiguousCommands=true",
			"--enable-native-access=com.holdmyspot.codexunleashed.tooling", "-m",
			"com.holdmyspot.codexunleashed.tooling/com.holdmyspot.codexunleashed.tooling.Main",
			"watch-quiet-build", "--dump", "--jcmd", jcmd.toString()));
	}

	/**
	 * A Windows wrapper without a parent directory is rejected before either process collaborator is used.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void windowsRejectsParentlessLauncher()
	{
		QuietBuildCapture.Context context = new QuietBuildCapture.Context(Path.of("work"), Path.of("temp"),
			Map.of());
		QuietBuildCapture.Access capture = (arguments, childContext, timeout) ->
		{
			throw new UnsupportedOperationException("Construction must not probe for elevation");
		};
		QuietBuildDumpProcess dumpProcess = (arguments, childContext, output, timeout) ->
		{
			throw new UnsupportedOperationException("Construction must not start diagnostics");
		};

		new QuietBuildDumpRunner(capture, dumpProcess, context, QuietBuildDump.Platform.WINDOWS,
			Path.of("codex-tooling"), 1000);
	}

	/**
	 * The selected Windows entry prefix cannot be changed by its caller.
	 */
	@Test(expectedExceptions = UnsupportedOperationException.class)
	public void toolingCommandPrefixIsImmutable()
	{
		List<String> command = ToolingLauncher.command(QuietBuildDump.Platform.WINDOWS,
			Path.of("packaged launcher/bin/codex-tooling"), Path.of("temp"));
		requireThat(command, "command").isNotEmpty();
		command.add("unexpected");
	}

	/**
	 * Partial diagnostic text survives a dump timeout and precedes the report's timeout marker.
	 * The dump-process access supplies its documented timeout exception after writing partial output.
	 *
	 * @throws IOException if the report cannot be written, read, or removed
	 */
	@Test
	public void reportKeepsPartialDumpOnTimeout() throws IOException
	{
		Path reportPath = Files.createTempFile("quiet-build-runner-", ".txt");
		try
		{
			QuietBuildCapture.Context context = new QuietBuildCapture.Context(Path.of("work"), Path.of("temp"),
				Map.of());
			QuietBuildCapture.Access capture = (arguments, childContext, timeout) ->
			{
				throw new UnsupportedOperationException("An already-root invocation must not probe for elevation");
			};
			QuietBuildDumpProcess dumpProcess = (arguments, childContext, output, timeout) ->
			{
				output.write("partial dump\n");
				throw new QuietBuildReport.DumpTimeoutException("Diagnostic deadline exceeded");
			};
			QuietBuildDumpRunner runner = new QuietBuildDumpRunner(capture, dumpProcess, context,
				QuietBuildDump.Platform.LINUX, Path.of("packaged launcher/bin/quiet-build"), 0);
			QuietBuildReport report = new QuietBuildReport(reportPath, runner);

			report.append(Duration.ZERO, Instant.EPOCH, Optional.empty());

			requireThat(Files.readString(reportPath, StandardCharsets.UTF_8), "report").isEqualTo(
				"=== quiet_seconds=0 time=1970-01-01T00:00:00Z\npartial dump\n<process report timed out>\n");
		}
		finally
		{
			Files.deleteIfExists(reportPath);
		}
	}
}
