package com.holdmyspot.codexunleashed.scheduler.test;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.testng.SkipException;

/**
 * A compiled {@code linux-build-hook.c} library, its trampoline {@code codex-build-hook-exec} and a small dynamic
 * program, with a Unix-domain listener that a test's hook server can accept on, in one owned directory.
 * <p>
 * The library links the Frida Gum devkit that {@value #DEVKIT_ENV} names; without it {@link #create()} skips the
 * calling test.
 */
public final class LinuxBuildHookInstallation implements AutoCloseable
{
	/** Environment variable naming the extracted {@code frida-gum-devkit} directory. */
	private static final String DEVKIT_ENV = "CODEX_TEST_GUM_DEVKIT";

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
		  if (strcmp(argv[1], "token-pair") == 0) {
		    int fifo = open(argv[2], O_RDWR);
		    if (fifo < 0) return 7;
		    char pair[2];
		    return read(fifo, pair, 2) < 0 ? 8 : 9;
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

	/** The owned directory. */
	private final Path directory;
	/** The compiled library. */
	private final Path library;
	/** The compiled program. */
	private final Path program;
	/** The listener's path. */
	private final Path socket;
	/** The listener. */
	private final ServerSocketChannel listener;

	/**
	 * Takes ownership of a compiled installation.
	 * @param directory the owned directory
	 * @param library   the compiled library
	 * @param program   the compiled program
	 * @param socket    the listener's path
	 * @param listener  the listener
	 */
	private LinuxBuildHookInstallation(Path directory, Path library, Path program, Path socket,
		ServerSocketChannel listener)
	{
		this.directory = directory;
		this.library = library;
		this.program = program;
		this.socket = socket;
		this.listener = listener;
	}

	/**
	 * Compiles the library and program and starts listening.
	 * @return the installation, which the caller must close
	 * @throws SkipException if the system is not Linux, or the devkit or a C compiler is missing
	 * @throws IOException   if compiling fails
	 */
	public static LinuxBuildHookInstallation create() throws IOException
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
			return new LinuxBuildHookInstallation(directory, library, program, socket, listener);
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
	 * Returns the owned directory, which {@link #close()} deletes.
	 * @return the directory
	 */
	public Path directory()
	{
		return directory;
	}

	/**
	 * Returns the compiled library, beside which the trampoline is installed.
	 * @return the library
	 */
	public Path library()
	{
		return library;
	}

	/**
	 * Returns the compiled program, which runs the modes that this class's program source documents.
	 * @return the program
	 */
	public Path program()
	{
		return program;
	}

	/**
	 * Returns the listener's path.
	 * @return the path
	 */
	public Path socket()
	{
		return socket;
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
	public Process start(String... arguments) throws IOException
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
	public Process startWithFifo(String fifo, String... arguments) throws IOException
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
	public Path compileStatic() throws IOException
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
	public SocketChannel accept() throws IOException
	{
		return listener.accept();
	}

	@Override
	public void close() throws IOException
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
