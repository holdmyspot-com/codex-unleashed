package com.holdmyspot.codexunleashed.tooling;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Exercises socket ownership in a real JDK process independently of absolute temporary path length.
 */
public final class CompilerSchedulerHostTest
{
	/** Creates scheduler host tests. */
	public CompilerSchedulerHostTest()
	{
	}

	/**
	 * Preserves a preexisting socket-path file when host construction fails.
	 *
	 * @throws IOException if fixture creation or cleanup fails
	 */
	@Test
	public void failedStartupPreservesExistingFile() throws IOException
	{
		Path directory = Files.createTempDirectory(Path.of("../../.cat/work"), "scheduler-startup-");
		Path socket = directory.resolve("s");
		try (PrintStream diagnostics = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
		{
			Files.writeString(socket, "caller-owned");
			expectThrows(IOException.class, () -> new CompilerSchedulerProcess(directory, diagnostics));
			assertEquals(Files.readString(socket), "caller-owned");
		}
		finally
		{
			Files.deleteIfExists(socket);
			Files.delete(directory);
		}
	}

	/**
	 * Finishes host cleanup before restoring a caller's interrupted status.
	 *
	 * @throws IOException if fixture creation, startup, or cleanup fails
	 */
	@Test
	public void interruptedCloseStillRemovesSocket() throws IOException
	{
		Path directory = Files.createTempDirectory(Path.of("../../.cat/work"), "scheduler-interrupted-");
		try (PrintStream diagnostics = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
			CompilerSchedulerProcess host = new CompilerSchedulerProcess(directory, diagnostics))
		{
			assertTrue(Files.exists(directory.resolve("s")));
			Thread.currentThread().interrupt();
			try
			{
				expectThrows(IOException.class, host::close);
				assertTrue(Thread.currentThread().isInterrupted());
				assertFalse(Files.exists(directory.resolve("s")));
			}
			finally
			{
				Thread.interrupted();
			}
		}
		finally
		{
			Files.deleteIfExists(directory.resolve("s"));
			Files.delete(directory);
		}
	}

	/**
	 * Binds using a short relative socket name in a deep directory and removes it after parent input closes.
	 *
	 * @throws IOException if the fixture or host cannot start
	 * @throws InterruptedException if waiting for the host is interrupted
	 */
	@Test
	public void ownsSocketUntilParentClosesInput() throws IOException, InterruptedException
	{
		Path root = Files.createTempDirectory(Path.of("../../.cat/work"), "scheduler-host-");
		Path workingDirectory = Files.createDirectory(root.resolve("deep-" + "x".repeat(128)));
		Path diagnostics = root.resolve("diagnostics");
		Path probe = root.resolve("Probe.java");
		try
		{
			Files.writeString(probe, """
				import java.io.DataInputStream;
				import java.io.DataOutputStream;
				import java.net.StandardProtocolFamily;
				import java.net.UnixDomainSocketAddress;
				import java.nio.channels.Channels;
				import java.nio.channels.SocketChannel;
				class Probe {
				    public static void main(String[] args) throws Exception {
				        try (var socket = SocketChannel.open(StandardProtocolFamily.UNIX)) {
				            socket.connect(UnixDomainSocketAddress.of("s"));
				            var input = new DataInputStream(Channels.newInputStream(socket));
				            var output = new DataOutputStream(Channels.newOutputStream(socket));
				            if (input.readInt() != 1 || input.readInt() != 1 || input.readInt() != 0)
				                throw new AssertionError("Missing first compiler admission");
				            output.writeInt(8); output.writeInt(1); output.writeInt(0);
				            output.writeInt(9); output.writeInt(1); output.writeInt(0);
				            output.flush();
				            if (input.read() != -1)
				                throw new AssertionError("Completed compiler connection remains open");
				        }
				    }
				}
				""");
			ProcessBuilder builder = SystemCommands.createBuilder(CompilerSchedulerHost.command()).
				directory(workingDirectory.toFile()).
				redirectError(diagnostics.toFile());
			try (Process process = builder.start();
				BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(),
					StandardCharsets.UTF_8)))
			{
				assertEquals(output.readLine(), "ready", Files.readString(diagnostics));
				assertTrue(Files.exists(workingDirectory.resolve("s")));
				SystemCommands.Result result = SystemCommands.capture(List.of(javaExecutable().toString(),
					"--source", "27", probe.toAbsolutePath().toString()), workingDirectory, root);
				assertEquals(result.status(), 0, result.stderr());
				process.getOutputStream().close();
				assertEquals(process.waitFor(), 0, Files.readString(diagnostics));
				assertFalse(Files.exists(workingDirectory.resolve("s")));
			}
		}
		finally
		{
			Files.deleteIfExists(workingDirectory.resolve("s"));
			Files.delete(workingDirectory);
			Files.deleteIfExists(diagnostics);
			Files.deleteIfExists(probe);
			Files.delete(root);
		}
	}

	/**
	 * Returns the current JDK's native launcher.
	 *
	 * @return the absolute launcher path
	 */
	private static Path javaExecutable()
	{
		String filename = "java";
		if (File.separatorChar == '\\')
			filename = "java.exe";
		return Path.of(System.getProperty("java.home"), "bin", filename);
	}
}
