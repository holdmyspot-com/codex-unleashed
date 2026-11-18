package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Runs the packaged diagnostic command for {@link QuietBuildReport}.
 */
public final class QuietBuildDumpRunner implements QuietBuildReport.DumpRunner
{
	/**
	 * Privilege-probe process access borrowed for this invocation.
	 */
	private final QuietBuildCapture.Access capture;
	/**
	 * Diagnostic process access borrowed for this invocation.
	 */
	private final QuietBuildDumpProcess dumpProcess;
	/**
	 * Complete child context retained for each diagnostic command.
	 */
	private final QuietBuildCapture.Context context;
	/**
	 * Operating system used to decide whether Unix privilege probing applies.
	 */
	private final QuietBuildDump.Platform platform;
	/**
	 * Fixed command prefix for entering the packaged diagnostic command.
	 */
	private final List<String> launcherCommand;
	/**
	 * Effective Unix user ID; zero suppresses redundant elevation.
	 */
	private final long effectiveUid;

	/**
	 * Creates a runner using the invocation's capture and diagnostic process access.
	 *
	 * @param capture      borrowed privilege-probe access
	 * @param dumpProcess  borrowed diagnostic process access
	 * @param context      complete immutable child execution context
	 * @param platform     operating system that selects privilege handling
	 * @param launcher     packaged wrapper identity used to select the platform's tooling entry
	 * @param effectiveUid effective user ID; zero identifies an already-root Unix invocation
	 * @throws NullPointerException     if a reference argument is null
	 * @throws IllegalArgumentException if {@code effectiveUid} is negative or a Windows launcher has no parent
	 */
	public QuietBuildDumpRunner(QuietBuildCapture.Access capture, QuietBuildDumpProcess dumpProcess,
		QuietBuildCapture.Context context, QuietBuildDump.Platform platform, Path launcher, long effectiveUid)
	{
		requireThat(capture, "capture").isNotNull();
		requireThat(dumpProcess, "dumpProcess").isNotNull();
		requireThat(context, "context").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(launcher, "launcher").isNotNull();
		requireThat(effectiveUid, "effectiveUid").isGreaterThanOrEqualTo(0L);
		this.capture = capture;
		this.dumpProcess = dumpProcess;
		this.context = context;
		this.platform = platform;
		this.launcherCommand = ToolingLauncher.command(platform, launcher, context.temporaryDirectory());
		this.effectiveUid = effectiveUid;
	}

	@Override
	public void dump(Optional<Path> jcmd, Writer output, Duration timeout) throws IOException
	{
		List<String> command = new ArrayList<>();
		if (canElevate())
			command.addAll(List.of("sudo", "-n"));
		command.addAll(launcherCommand);
		command.addAll(List.of("watch-quiet-build", "--dump"));
		jcmd.ifPresent(path ->
		{
			command.add("--jcmd");
			command.add(path.toString());
		});
		dumpProcess.run(command, context, output, timeout);
	}

	/**
	 * Indicates whether a nonroot Unix invocation can use noninteractive sudo.
	 *
	 * @return true when the 30-second sudo probe succeeds; false when elevation is unavailable or the probe fails
	 */
	private boolean canElevate()
	{
		boolean alreadyRoot = effectiveUid == 0;
		if (platform == QuietBuildDump.Platform.WINDOWS || alreadyRoot)
			return false;

		try (QuietBuildCapture.Captured probe = capture.capture(List.of("sudo", "-n", "true"), context,
			Optional.of(Duration.ofSeconds(30))))
		{
			return probe.status() == 0;
		}
		catch (IOException _)
		{
			// Unavailable elevation must not prevent collecting diagnostics accessible to the current user.
			return false;
		}
	}
}
