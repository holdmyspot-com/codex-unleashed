package com.holdmyspot.codexunleashed.scheduler.test;

import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.testng.SkipException;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Runs {@code linux-build-hook.c}, preloaded into a small dynamic program, against a hook server in the test.
 * <p>
 * Each test compiles the library and its program into its own temporary directory. The library links the Frida Gum
 * devkit that {@value #DEVKIT_ENV} names; without it the test is skipped. The test's Unix-domain listener plays the
 * scheduler's hook server and speaks protocol version 1.
 */
public final class LinuxBuildHookNativeTest
{
	/** Environment variable naming the extracted {@code frida-gum-devkit} directory. */
	private static final String DEVKIT_ENV = "CODEX_TEST_GUM_DEVKIT";
	/** Upper bound on any one test, so that a program that never connects fails the test instead of hanging it. */
	private static final long TEST_TIMEOUT_MILLIS = 120_000;
	/** Exit status of a process whose hook could not be installed. */
	private static final int FAILURE_STATUS = 125;
	/** Linux ENOMEM. */
	private static final String ENOMEM = "12";
	/**
	 * The program under the hook. With no argument it prints {@code main} and exits 0. With {@code deny} it limits its
	 * address space to 1 GiB, maps 768 MiB of ballast that SIGUSR1 unmaps, and allocates 512 MiB, which is denied until
	 * the ballast is gone; it prints {@code allocated} and exits 0. With {@code fork} it does the same, then forks a
	 * child that allocates 900 MiB, which is denied, and waits for that child. With {@code token <fifo>} it opens the
	 * FIFO, writes the byte {@code x} to it and prints the result, then reads one byte from it and prints that byte.
	 * With {@code env} it prints each entry of its environment on its own line. With {@code exec-clear} it execs itself
	 * with {@code env} and the environment {@code A=1}; {@code spawn-clear} does the same through {@code posix_spawn}
	 * and waits for the child. With {@code exec-static <program>} it execs that program as {@code static-child}, and
	 * {@code spawn-static <program>} does the same through {@code posix_spawn} and waits for the child.
	 */
	private static final String PROGRAM_SOURCE = """
		#include <fcntl.h>
		#include <signal.h>
		#include <spawn.h>
		#include <stdio.h>
		#include <stdlib.h>
		#include <string.h>
		#include <sys/mman.h>
		#include <sys/resource.h>
		#include <sys/wait.h>
		#include <unistd.h>
		static void *ballast;
		static const size_t ballast_size = 768UL << 20;
		static void release(int signal_number) { (void) signal_number; munmap(ballast, ballast_size); }
		int main(int argc, char **argv) {
		  if (argc < 2) { puts("main"); return 0; }
		  extern char **environ;
		  if (strcmp(argv[1], "env") == 0) {
		    for (char **entry = environ; *entry != NULL; ++entry) puts(*entry);
		    return 0;
		  }
		  char *env_arguments[] = { argv[0], "env", NULL };
		  char *cleared[] = { "A=1", NULL };
		  if (strcmp(argv[1], "exec-clear") == 0) {
		    execve(argv[0], env_arguments, cleared);
		    return 8;
		  }
		  if (strcmp(argv[1], "spawn-clear") == 0) {
		    pid_t child;
		    if (posix_spawn(&child, argv[0], NULL, NULL, env_arguments, cleared) != 0) return 9;
		    int status;
		    if (waitpid(child, &status, 0) != child || !WIFEXITED(status)) return 10;
		    return WEXITSTATUS(status);
		  }
		  char *static_arguments[] = { "static-child", NULL };
		  if (strcmp(argv[1], "exec-static") == 0) {
		    execve(argv[2], static_arguments, environ);
		    return 11;
		  }
		  if (strcmp(argv[1], "spawn-static") == 0) {
		    pid_t child;
		    if (posix_spawn(&child, argv[2], NULL, NULL, static_arguments, environ) != 0) return 12;
		    int status;
		    if (waitpid(child, &status, 0) != child || !WIFEXITED(status)) return 13;
		    return WEXITSTATUS(status);
		  }
		  if (strcmp(argv[1], "token") == 0) {
		    int fifo = open(argv[2], O_RDWR);
		    if (fifo < 0) return 7;
		    char byte = 'x';
		    printf("wrote %zd\\n", write(fifo, &byte, 1));
		    ssize_t got = read(fifo, &byte, 1);
		    printf("read %zd %c\\n", got, byte);
		    return 0;
		  }
		  struct rlimit limit = { 1UL << 30, 1UL << 30 };
		  if (setrlimit(RLIMIT_AS, &limit) != 0) return 2;
		  signal(SIGUSR1, release);
		  ballast = mmap(NULL, ballast_size, PROT_READ, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
		  if (ballast == MAP_FAILED) return 3;
		  if (malloc(512UL << 20) == NULL) return 4;
		  puts("allocated");
		  fflush(stdout);
		  if (strcmp(argv[1], "fork") != 0) return 0;
		  pid_t child = fork();
		  if (child == 0) _exit(malloc(900UL << 20) == NULL ? 5 : 0);
		  int status;
		  if (waitpid(child, &status, 0) != child) return 6;
		  return 0;
		}
		""";

	/** A statically linked program, which ignores {@code LD_PRELOAD}; it prints its first argument. */
	private static final String STATIC_SOURCE = """
		#include <stdio.h>
		int main(int argc, char **argv) { (void) argc; puts(argv[0]); return 0; }
		""";

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
		try (Installation installation = Installation.create())
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
		try (Installation installation = Installation.create())
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
		try (Installation installation = Installation.create())
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
		try (Installation installation = Installation.create())
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
		try (Installation installation = Installation.create())
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
		try (Installation installation = Installation.create())
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
		try (Installation installation = Installation.create())
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

	/**
	 * A compiled library and program and the hook server's listener, in one owned directory.
	 * @param directory the owned directory
	 * @param library   the compiled library
	 * @param program   the compiled program
	 * @param socket    the listener's path
	 * @param listener  the listener
	 */
	private record Installation(Path directory, Path library, Path program, Path socket, ServerSocketChannel listener)
		implements AutoCloseable
	{
		/**
		 * Compiles the library and program and starts listening.
		 * @return the installation, which the caller must close
		 * @throws SkipException if the system is not Linux, or the devkit or a C compiler is missing
		 * @throws IOException   if compiling fails
		 */
		static Installation create() throws IOException
		{
			if (!System.getProperty("os.name").equals("Linux"))
				throw new SkipException("The build hook requires Linux");
			String devkit = System.getenv(DEVKIT_ENV);
			if (devkit == null)
				throw new SkipException(DEVKIT_ENV + " does not name a Frida Gum devkit");
			// The short prefix keeps the socket path below the 108-byte Unix-domain limit.
			Path directory = Files.createTempDirectory("bh-");
			try
			{
				Path source = directory.resolve("linux-build-hook.c");
				// The main module does not open its resource package, so the test reads the source from the tree.
				Files.copy(Path.of(System.getProperty("basedir", ".")).resolve(
					"src/main/resources/com/holdmyspot/codexunleashed/scheduler/linux-build-hook.c"), source);
				Path library = directory.resolve("libbuildhook.so");
				Path devkitPath = Path.of(devkit);
				compile(List.of("cc", "-Wall", "-Wextra", "-Werror", "-O2", "-fPIC", "-shared",
					"-I" + devkitPath, "-ffunction-sections", "-fdata-sections", source.toString(), "-o", library.toString(),
					devkitPath.resolve("libfrida-gum.a").toString(), "-Wl,--gc-sections", "-lm", "-lresolv", "-latomic",
					"-ldl", "-lrt", "-pthread"));
				Path trampolineSource = directory.resolve("linux-build-hook-exec.c");
				Files.copy(Path.of(System.getProperty("basedir", ".")).resolve(
					"src/main/resources/com/holdmyspot/codexunleashed/scheduler/linux-build-hook-exec.c"), trampolineSource);
				compile(List.of("cc", "-Wall", "-Wextra", "-Werror", "-O2", "-o",
					directory.resolve("codex-build-hook-exec").toString(), trampolineSource.toString(), "-ldl"));
				Path programSource = directory.resolve("program.c");
				Files.writeString(programSource, PROGRAM_SOURCE);
				Path program = directory.resolve("program");
				compile(List.of("cc", "-Wall", "-Wextra", "-Werror", "-O0", "-o", program.toString(),
					programSource.toString()));
				Path socket = directory.resolve("s");
				ServerSocketChannel listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
				listener.bind(UnixDomainSocketAddress.of(socket));
				return new Installation(directory, library, program, socket, listener);
			}
			catch (IOException | RuntimeException | Error failure)
			{
				try
				{
					delete(directory);
				}
				catch (IOException cleanup)
				{
					failure.addSuppressed(cleanup);
				}
				throw failure;
			}
		}

		/**
		 * Runs one compiler command.
		 * @param command the command
		 * @throws IOException if it fails
		 */
		private static void compile(List<String> command) throws IOException
		{
			Process compiler = new ProcessBuilder(command).redirectErrorStream(true).start();
			String diagnostics = new String(compiler.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			try
			{
				if (compiler.waitFor() != 0) throw new IOException("Compiling failed: " + diagnostics);
			}
			catch (InterruptedException interrupted)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while compiling", interrupted);
			}
		}

		/**
		 * Starts the program with the library preloaded.
		 * @param arguments the program's arguments
		 * @return the process
		 * @throws IOException if it cannot start
		 */
		Process start(String... arguments) throws IOException
		{
			return startWithFifo(null, arguments);
		}

		/**
		 * Starts the program with the library preloaded.
		 * @param fifo      the jobserver FIFO as {@code major:minor:inode}, or {@code null} for none
		 * @param arguments the program's arguments
		 * @return the process
		 * @throws IOException if it cannot start
		 */
		Process startWithFifo(String fifo, String... arguments) throws IOException
		{
			List<String> command = new ArrayList<>(List.of(program.toString()));
			command.addAll(List.of(arguments));
			ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT);
			builder.environment().put("LD_PRELOAD", library.toString());
			builder.environment().put("CODEX_BUILD_HOOK", socket.toString());
			if (fifo != null)
				builder.environment().put("CODEX_BUILD_HOOK_FIFO", fifo);
			return builder.start();
		}

		/**
		 * Compiles the static program.
		 * @return its path
		 * @throws IOException if compiling fails
		 */
		Path compileStatic() throws IOException
		{
			Path source = directory.resolve("static.c");
			Files.writeString(source, STATIC_SOURCE);
			Path binary = directory.resolve("static");
			compile(List.of("cc", "-static", "-O2", "-o", binary.toString(), source.toString()));
			return binary;
		}

		/**
		 * Accepts the next connection.
		 * @return the connection
		 * @throws IOException if accepting fails
		 */
		SocketChannel accept() throws IOException
		{
			return listener.accept();
		}

		@Override public void close() throws IOException
		{
			try
			{
				listener.close();
			}
			finally
			{
				delete(directory);
			}
		}

		/**
		 * Deletes a directory tree.
		 * @param directory the tree
		 * @throws IOException if deleting fails
		 */
		private static void delete(Path directory) throws IOException
		{
			try (Stream<Path> paths = Files.walk(directory))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}
}
