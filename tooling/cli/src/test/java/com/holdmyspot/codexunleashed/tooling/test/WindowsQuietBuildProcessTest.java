package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.QuietBuildLauncher;
import com.holdmyspot.codexunleashed.tooling.QuietBuildProcess;
import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import com.holdmyspot.codexunleashed.tooling.WindowsQuietBuildProcess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies {@link WindowsQuietBuildProcess} result interpretation and its platform-owned launch boundary.
 */
public final class WindowsQuietBuildProcessTest
{
	/**
	 * Creates isolated process-owner tests.
	 */
	public WindowsQuietBuildProcessTest()
	{
	}

	/**
	 * The process owner keeps the entire unsigned DWORD, including high-bit exits.
	 */
	@Test
	public void preservesWindowsExitBits()
	{
		requireThat(WindowsQuietBuildProcess.decodeExit(143), "ordinary").
			isEqualTo(new QuietBuildProcess.WindowsExit(143));
		requireThat(WindowsQuietBuildProcess.decodeExit(-1), "highBits").
			isEqualTo(new QuietBuildProcess.WindowsExit(0xffff_ffffL));
		requireThat(WindowsQuietBuildProcess.decodeExit(Integer.MIN_VALUE), "highBit").
			isEqualTo(new QuietBuildProcess.WindowsExit(0x8000_0000L));
	}

	/**
	 * A failed taskkill retains both diagnostics and does not claim that the tree disappeared.
	 */
	@Test
	public void diagnosesTaskkillFailure()
	{
		QuietBuildProcess.StopOutcome failure = WindowsQuietBuildProcess.classifyTaskkill(
			new SystemCommands.Result(128, "owned root absent", "tree status unknown")).orElseThrow();
		requireThat(failure.state(), "state").isEqualTo(QuietBuildProcess.GroupState.ACCESS_DENIED);
		requireThat(failure.diagnostics(), "diagnostics").
			isEqualTo(List.of("taskkill returned 128", "owned root absent", "tree status unknown"));
		requireThat(WindowsQuietBuildProcess.classifyTaskkill(new SystemCommands.Result(0, "", "")), "success").isEmpty();
	}

	/**
	 * Selects forced tree termination for each owned root without changing its identifier.
	 */
	@Test
	public void createsForcedTreeTerminationCommand()
	{
		requireThat(WindowsQuietBuildProcess.createTaskkillCommand(1234), "ordinaryRoot").
			isEqualTo(List.of("taskkill", "/T", "/F", "/PID", "1234"));
		requireThat(WindowsQuietBuildProcess.createTaskkillCommand(4_294_967_295L), "highRoot").
			isEqualTo(List.of("taskkill", "/T", "/F", "/PID", "4294967295"));
	}

	/**
	 * Supplies ordinary and high-bit exits for the authentic Windows child boundary.
	 *
	 * @return signed Java representations of exit DWORDs
	 */
	@DataProvider
	public Object[][] windowsExitCodes()
	{
		return new Object[][] {{7}, {143}, {-1}};
	}

	/**
	 * The Windows launcher returns and caches the authentic child's exit DWORD.
	 *
	 * @param code requested child exit bits
	 * @throws IOException          if creating the child source, launching, or cleaning up fails
	 * @throws InterruptedException if awaiting the child is interrupted
	 */
	@Test(dataProvider = "windowsExitCodes")
	public void receivesNativeWindowsCompletion(int code) throws IOException, InterruptedException
	{
		if (!System.getProperty("os.name").startsWith("Windows"))
			throw new SkipException("Authentic Windows runner required; root owns both Windows release lanes");
		Path directory = Files.createTempDirectory("quiet-build-windows-");
		Path source = directory.resolve("Child.java");
		try
		{
			Files.writeString(source, """
				class Child
				{
					public static void main(String[] args)
					{
						System.exit(Integer.parseInt(args[0]));
					}
				}
				""");
			try (ToolingScope scope = new DefaultToolingScope();
				QuietBuildProcess process = new QuietBuildLauncher(scope).start(List.of(
					Path.of(System.getProperty("java.home"), "bin/java.exe").toString(), source.toString(),
					Integer.toString(code)), directory, Map.of()))
			{
				requireThat(process.await(), "completion").isEqualTo(WindowsQuietBuildProcess.decodeExit(code));
				requireThat(process.poll().orElseThrow(), "cachedCompletion").isEqualTo(process.await());
			}
		}
		finally
		{
			Files.deleteIfExists(source);
			Files.delete(directory);
		}
	}
}
