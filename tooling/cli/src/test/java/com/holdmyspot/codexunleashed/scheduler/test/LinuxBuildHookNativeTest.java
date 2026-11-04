package com.holdmyspot.codexunleashed.scheduler.test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Runs {@code linux-build-hook.c}, preloaded into a small dynamic program, against a hook server in the test.
 * <p>
 * Each test compiles the library and its program into its own temporary directory. The library links the Frida Gum
 * devkit that {@link LinuxBuildHookInstallation} requires; without it the test is skipped. The test's Unix-domain
 * listener plays the scheduler's hook server and speaks protocol version 1.
 */
public final class LinuxBuildHookNativeTest
{
	/** Upper bound on any one test, so that a program that never connects fails the test instead of hanging it. */
	private static final long TEST_TIMEOUT_MILLIS = 120_000;
	/** Exit status of a process whose hook could not be installed. */
	private static final int FAILURE_STATUS = 125;
	/** Linux ENOMEM. */
	private static final String ENOMEM = "12";

	/** Creates a resource-free test instance; each method owns its compiled files, listener and processes. */
	public LinuxBuildHookNativeTest()
	{
	}

	/**
	 * The library identifies the process with its PID and start time and waits for welcome before main runs.
	 * @throws IOException          if the fixture cannot be built or run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void welcomesBeforeMain() throws IOException, InterruptedException
	{
		try (LinuxBuildHookInstallation installation = LinuxBuildHookInstallation.create())
		{
			Process program = installation.start();
			try (SocketChannel connection = installation.accept())
			{
				List<String> hello = receive(connection);
				requireThat(hello, "hello").isEqualTo(List.of("hello", "1", Long.toString(program.pid()),
					startTime(program.pid())));
				// main has not run while the welcome is outstanding.
				requireThat(program.isAlive(), "program.isAlive()").isTrue();
				requireThat(program.getInputStream().available(), "available").isEqualTo(0);
				send(connection, "welcome", "1");
				requireThat(readAll(program), "output").isEqualTo("main\n");
				requireThat(program.waitFor(), "exitValue").isEqualTo(0);
			}
			finally
			{
				program.destroyForcibly();
			}
		}
	}

	/**
	 * A server that closes the connection instead of welcoming the process ends it before main runs.
	 * @throws IOException          if the fixture cannot be built or run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void exitsWithoutWelcome() throws IOException, InterruptedException
	{
		try (LinuxBuildHookInstallation installation = LinuxBuildHookInstallation.create())
		{
			Process program = installation.start();
			try
			{
				try (SocketChannel connection = installation.accept())
				{
					receive(connection);
				}
				requireThat(readAll(program), "output").isEmpty();
				requireThat(program.waitFor(), "exitValue").isEqualTo(FAILURE_STATUS);
			}
			finally
			{
				program.destroyForcibly();
			}
		}
	}

	/**
	 * A malloc that is denied with ENOMEM is reported and held; once the server grants it, the retry succeeds.
	 * @throws IOException          if the fixture cannot be built or run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void holdsDeniedAllocationUntilGranted() throws IOException, InterruptedException
	{
		try (LinuxBuildHookInstallation installation = LinuxBuildHookInstallation.create())
		{
			Process program = installation.start("deny");
			try (SocketChannel connection = installation.accept())
			{
				receive(connection);
				send(connection, "welcome", "1");
				List<String> blocked = receive(connection);
				requireThat(blocked.size(), "blocked.size()").isEqualTo(5);
				requireThat(blocked.get(0), "blocked[0]").isEqualTo("blocked");
				requireThat(blocked.get(1), "wait").isEqualTo("1");
				requireThat(blocked.get(3), "bytes").isEqualTo(Long.toString(512L << 20));
				requireThat(blocked.get(4), "errno").isEqualTo(ENOMEM);
				requireThat(program.getInputStream().available(), "available").isEqualTo(0);

				free(program);
				send(connection, "grant", "1");
				requireThat(receive(connection), "granted").isEqualTo(List.of("granted", "1", "accepted"));
				requireThat(readAll(program), "output").isEqualTo("allocated\n");
				requireThat(program.waitFor(), "exitValue").isEqualTo(0);
			}
			finally
			{
				program.destroyForcibly();
			}
		}
	}

	/**
	 * A forked child that is denied connects as its own process, and a repeated grant is reported as a duplicate.
	 * @throws IOException          if the fixture cannot be built or run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void forkedChildConnectsAsItself() throws IOException, InterruptedException
	{
		try (LinuxBuildHookInstallation installation = LinuxBuildHookInstallation.create())
		{
			Process program = installation.start("fork");
			try (SocketChannel parent = installation.accept())
			{
				receive(parent);
				send(parent, "welcome", "1");
				receive(parent);
				free(program);
				send(parent, "grant", "1");
				receive(parent);
				send(parent, "grant", "1");
				requireThat(receive(parent), "granted").isEqualTo(List.of("granted", "1", "duplicate"));

				try (SocketChannel child = installation.accept())
				{
					List<String> hello = receive(child);
					long childPid = Long.parseLong(hello.get(2));
					requireThat(childPid, "childPid").isNotEqualTo(program.pid());
					requireThat(hello, "hello").isEqualTo(List.of("hello", "1", hello.get(2), startTime(childPid)));
					send(child, "welcome", "2");
					List<String> blocked = receive(child);
					requireThat(blocked.subList(0, 2), "blocked").isEqualTo(List.of("blocked", "1"));
					requireThat(blocked.get(2), "thread").isEqualTo(hello.get(2));
					requireThat(blocked.get(3), "bytes").isEqualTo(Long.toString(900L << 20));
				}
				// The child parks once its connection is gone; the server would end the attempt here.
				for (ProcessHandle descendant : program.descendants().toList())
					descendant.destroyForcibly();
				requireThat(readAll(program), "output").isEqualTo("allocated\n");
				requireThat(program.waitFor(), "exitValue").isEqualTo(0);
			}
			finally
			{
				program.descendants().forEach(ProcessHandle::destroyForcibly);
				program.destroyForcibly();
			}
		}
	}

	/**
	 * A one-byte write and read of the jobserver FIFO wait for the server's token and move no byte through the FIFO;
	 * the read returns the token's byte. Standard output, also a pipe, is not mediated.
	 * @throws IOException          if the fixture cannot be built or run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void mediatesJobserverTokens() throws IOException, InterruptedException
	{
		try (LinuxBuildHookInstallation installation = LinuxBuildHookInstallation.create())
		{
			Path fifo = installation.directory().resolve("jobserver");
			Process mkfifo = new ProcessBuilder("mkfifo", fifo.toString()).inheritIO().start();
			requireThat(mkfifo.waitFor(), "mkfifo").isEqualTo(0);
			Process stat = new ProcessBuilder("stat", "-c", "%Hd:%Ld:%i", fifo.toString()).start();
			String identity = new String(stat.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
			requireThat(stat.waitFor(), "stat").isEqualTo(0);

			Process program = installation.startWithFifo(identity, "token", fifo.toString());
			try (SocketChannel connection = installation.accept())
			{
				receive(connection);
				send(connection, "welcome", "1");
				requireThat(receive(connection), "write").isEqualTo(List.of("transfer", "1", "write",
					Integer.toString('x')));
				send(connection, "token", "1");
				requireThat(receive(connection), "read").isEqualTo(List.of("transfer", "2", "read"));
				send(connection, "token", "2", Integer.toString('A'));
				// Had the write reached the FIFO, the read would have returned x without a transfer.
				requireThat(readAll(program), "output").isEqualTo("wrote 1\nread 1 A\n");
				requireThat(program.waitFor(), "exitValue").isEqualTo(0);
			}
			finally
			{
				program.destroyForcibly();
			}
		}
	}

	/**
	 * A jobserver transfer of more than one byte is reported with its operation and the cause that the Frida token
	 * script reports, and the caller parks without native I/O.
	 * @throws IOException          if the fixture cannot be built or run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void reportsMultibyteTransfer() throws IOException, InterruptedException
	{
		try (LinuxBuildHookInstallation installation = LinuxBuildHookInstallation.create())
		{
			Path fifo = installation.directory().resolve("jobserver");
			Process mkfifo = new ProcessBuilder("mkfifo", fifo.toString()).inheritIO().start();
			requireThat(mkfifo.waitFor(), "mkfifo").isEqualTo(0);
			Process stat = new ProcessBuilder("stat", "-c", "%Hd:%Ld:%i", fifo.toString()).start();
			String identity = new String(stat.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
			requireThat(stat.waitFor(), "stat").isEqualTo(0);

			Process program = installation.startWithFifo(identity, "token-pair", fifo.toString());
			try (SocketChannel connection = installation.accept())
			{
				receive(connection);
				send(connection, "welcome", "1");
				requireThat(receive(connection), "failure").isEqualTo(List.of("failure", "1", "read",
					"Jobserver transfer count differs from one"));
				requireThat(program.isAlive(), "parked").isTrue();
			}
			finally
			{
				program.destroyForcibly();
			}
		}
	}

	/**
	 * An image that execs with an environment lacking the hook's variables gets them back, so the new image loads the
	 * library and connects as itself.
	 * @throws IOException          if the fixture cannot be built or run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void restoresEnvironmentAcrossExec() throws IOException, InterruptedException
	{
		requireRestoredChild("exec-clear", true);
	}

	/**
	 * A child started by {@code posix_spawn} with an environment lacking the hook's variables gets them back.
	 * @throws IOException          if the fixture cannot be built or run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void restoresEnvironmentAcrossSpawn() throws IOException, InterruptedException
	{
		requireRestoredChild("spawn-clear", false);
	}

	/**
	 * Runs a mode that starts the {@code env} mode with the environment {@code A=1} and checks that the new image
	 * connected and printed {@code A=1} plus the hook's own variables.
	 * @param mode     the program mode
	 * @param sameProcess whether the new image replaces the program, keeping its PID
	 * @throws IOException          if the fixture cannot be built or run
	 * @throws InterruptedException if the test is interrupted
	 */
	private static void requireRestoredChild(String mode, boolean sameProcess)
		throws IOException, InterruptedException
	{
		try (LinuxBuildHookInstallation installation = LinuxBuildHookInstallation.create())
		{
			Process program = installation.start(mode);
			try (SocketChannel parent = installation.accept())
			{
				receive(parent);
				send(parent, "welcome", "1");
				try (SocketChannel child = installation.accept())
				{
					List<String> hello = receive(child);
					requireThat(hello.get(0), "hello").isEqualTo("hello");
					requireThat(Long.parseLong(hello.get(2)) == program.pid(), "samePid").isEqualTo(sameProcess);
					send(child, "welcome", "2");
					requireThat(Set.of(readAll(program).split("\n")), "environment").isEqualTo(Set.of("A=1",
						"LD_PRELOAD=" + installation.library(), "CODEX_BUILD_HOOK=" + installation.socket()));
					requireThat(program.waitFor(), "exitValue").isEqualTo(0);
				}
			}
			finally
			{
				program.destroyForcibly();
			}
		}
	}

	/**
	 * A static target of execve, which would ignore the library, is announced, stops for the tracer to adopt it, and
	 * runs with its own arguments once continued.
	 * @throws IOException          if the fixture cannot be built or run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void handsStaticExecTargetToTracer() throws IOException, InterruptedException
	{
		requireStaticHandoff("exec-static", true);
	}

	/**
	 * A static target of posix_spawn is announced from the child and stops for the tracer to adopt it.
	 * @throws IOException          if the fixture cannot be built or run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void handsStaticSpawnTargetToTracer() throws IOException, InterruptedException
	{
		requireStaticHandoff("spawn-static", false);
	}

	/**
	 * Runs a mode that starts a static program, adopts it with SIGCONT in place of the tracer, and checks its output.
	 * @param mode        the program mode
	 * @param sameProcess whether the static program replaces the program, keeping its PID
	 * @throws IOException          if the fixture cannot be built or run
	 * @throws InterruptedException if the test is interrupted
	 */
	private static void requireStaticHandoff(String mode, boolean sameProcess) throws IOException, InterruptedException
	{
		try (LinuxBuildHookInstallation installation = LinuxBuildHookInstallation.create())
		{
			Path target = installation.compileStatic();
			Process program = installation.start(mode, target.toString());
			try (SocketChannel parent = installation.accept())
			{
				receive(parent);
				send(parent, "welcome", "1");
				try (SocketChannel trampoline = installation.accept())
				{
					List<String> hello = receive(trampoline);
					long pid = Long.parseLong(hello.get(2));
					requireThat(pid == program.pid(), "samePid").isEqualTo(sameProcess);
					send(trampoline, "welcome", "2");
					requireThat(receive(trampoline), "announcement").isEqualTo(List.of("exec-static", target.toString()));
					requireThat(program.getInputStream().available(), "available").isEqualTo(0);
					send(trampoline, "adopt");
					while (!state(pid).equals("T"))
						Thread.onSpinWait();
					Process resume = new ProcessBuilder("kill", "-CONT", Long.toString(pid)).inheritIO().start();
					requireThat(resume.waitFor(), "kill").isEqualTo(0);
					requireThat(readAll(program), "output").isEqualTo("static-child\n");
					requireThat(program.waitFor(), "exitValue").isEqualTo(0);
				}
			}
			finally
			{
				program.destroyForcibly();
			}
		}
	}

	/**
	 * Reads field 3 of a process's {@code /proc/<pid>/stat}.
	 * @param pid the process
	 * @return its state letter
	 * @throws IOException if the file cannot be read
	 */
	private static String state(long pid) throws IOException
	{
		String stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
		return stat.substring(stat.lastIndexOf(')') + 2).split(" ")[0];
	}

	/**
	 * Unmaps the program's ballast so that its next allocation succeeds.
	 * @param program the program
	 * @throws IOException          if the signal cannot be sent
	 * @throws InterruptedException if interrupted
	 */
	private static void free(Process program) throws IOException, InterruptedException
	{
		Process kill = new ProcessBuilder("kill", "-USR1", Long.toString(program.pid())).inheritIO().start();
		if (kill.waitFor() != 0) throw new IOException("kill -USR1 failed");
	}

	/**
	 * Reads field 22 of a process's {@code /proc/<pid>/stat}.
	 * @param pid the process
	 * @return the start time in clock ticks
	 * @throws IOException if the file cannot be read
	 */
	private static String startTime(long pid) throws IOException
	{
		String stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
		String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
		// fields[0] is field 3.
		return fields[22 - 3];
	}

	/**
	 * Reads a process's whole standard output.
	 * @param program the process
	 * @return its output
	 * @throws IOException if reading fails
	 */
	private static String readAll(Process program) throws IOException
	{
		try (InputStream output = program.getInputStream())
		{
			return new String(output.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * Sends one frame.
	 * @param connection the connection
	 * @param fields     the fields
	 * @throws IOException if writing fails
	 */
	private static void send(SocketChannel connection, String... fields) throws IOException
	{
		var payload = new java.io.ByteArrayOutputStream();
		for (String field : fields)
		{
			payload.write(field.getBytes(StandardCharsets.UTF_8));
			payload.write(0);
		}
		ByteBuffer frame = ByteBuffer.allocate(4 + payload.size());
		frame.putInt(payload.size()).put(payload.toByteArray()).flip();
		while (frame.hasRemaining())
			connection.write(frame);
	}

	/**
	 * Receives one frame.
	 * @param connection the connection
	 * @return its fields
	 * @throws IOException if reading fails or the connection ends
	 */
	private static List<String> receive(SocketChannel connection) throws IOException
	{
		ByteBuffer header = readExactly(connection, 4);
		ByteBuffer payload = readExactly(connection, header.getInt());
		List<String> fields = new ArrayList<>();
		int start = 0;
		byte[] bytes = payload.array();
		for (int index = 0; index < bytes.length; ++index)
		{
			if (bytes[index] != 0) continue;
			fields.add(new String(bytes, start, index - start, StandardCharsets.UTF_8));
			start = index + 1;
		}
		return fields;
	}

	/**
	 * Reads exactly the given number of bytes.
	 * @param connection the connection
	 * @param length     the byte count
	 * @return the bytes, flipped for reading
	 * @throws IOException if the connection ends first
	 */
	private static ByteBuffer readExactly(SocketChannel connection, int length) throws IOException
	{
		ByteBuffer buffer = ByteBuffer.allocate(length);
		while (buffer.hasRemaining())
			if (connection.read(buffer) < 0) throw new IOException("The hook closed its connection");
		return buffer.flip();
	}
}
