package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.AllocationHooks;
import com.holdmyspot.codexunleashed.scheduler.AllocationWaits;
import com.holdmyspot.codexunleashed.scheduler.LinuxBuildHookServer;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.testng.SkipException;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Runs {@link LinuxBuildHookServer} against a held root that execs {@code linux-build-hook.c}'s program with the
 * environment that the server releases it with.
 * <p>
 * The root is a shell that reads the released {@code NAME=value} entries from its standard input, one per line, and
 * execs {@code env} with them and the program. The attempt's cgroup is the test's own, so every process the root starts
 * is admitted.
 */
public final class LinuxBuildHookServerNativeTest
{
	/** Upper bound on any one test, so that a client that never connects fails the test instead of hanging it. */
	private static final long TEST_TIMEOUT_MILLIS = 120_000;
	/** Bound on each server control. */
	private static final Duration TIMEOUT = Duration.ofSeconds(30);
	/** Mountpoint of the cgroup-v2 hierarchy. */
	private static final Path CGROUP_ROOT = Path.of("/sys/fs/cgroup");
	/**
	 * The held root: it splits its standard input into entries at newlines only and execs {@code env} with them,
	 * followed by its arguments.
	 */
	private static final String ROOT_SCRIPT = "set -f; IFS='\n'; exec env $(cat) \"$@\"";

	/** Creates a resource-free test instance; each method owns its compiled files, server and processes. */
	public LinuxBuildHookServerNativeTest()
	{
	}

	/**
	 * The root's program runs with the library preloaded ahead of the inherited preloads and the attempt's endpoint
	 * named, so the server must welcome it before it prints its environment.
	 * @throws IOException          if the fixture cannot be built or run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void releasesRootIntoLibrary() throws IOException, InterruptedException
	{
		try (LinuxBuildHookInstallation installation = LinuxBuildHookInstallation.create();
			ToolingScope scope = new DefaultToolingScope())
		{
			Path endpoint = installation.directory().resolve("e");
			Path inherited = installation.directory().resolve("inherited.so");
			Process root = startRoot(installation, "env");
			try
			{
				LinuxBuildHookServer server = newServer(scope, installation, root, List.of(inherited), endpoint,
					new AllocationWaits());
				server.start(environment -> release(root, environment));
				pumpUntilExit(server, root);
				// Only the hook's entries are compared, so that a failure never reports the test's other variables.
				Set<String> hookEntries = Stream.of(readAll(root).split("\n")).
					filter(entry -> entry.startsWith("LD_PRELOAD=") || entry.startsWith("CODEX_BUILD_HOOK")).
					collect(Collectors.toSet());
				requireThat(hookEntries, "hookEntries").isEqualTo(Set.of(
					"LD_PRELOAD=" + installation.library() + ":" + inherited, "CODEX_BUILD_HOOK=" + endpoint));
				requireThat(root.waitFor(), "exitValue").isEqualTo(0);
				server.close(() -> !root.isAlive());
			}
			finally
			{
				root.destroyForcibly();
			}
		}
	}

	/**
	 * A denied allocation is published as a wait, and granting it through the server lets the program continue.
	 * @throws IOException          if the fixture cannot be built or run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void grantsDeniedAllocation() throws IOException, InterruptedException
	{
		try (LinuxBuildHookInstallation installation = LinuxBuildHookInstallation.create();
			ToolingScope scope = new DefaultToolingScope())
		{
			Process root = startRoot(installation, "deny");
			try
			{
				AllocationWaits waits = new AllocationWaits();
				LinuxBuildHookServer server = newServer(scope, installation, root, List.of(),
					installation.directory().resolve("e"), waits);
				server.start(environment -> release(root, environment));
				OptionalLong published = waits.poll();
				while (published.isEmpty())
				{
					requireThat(root.isAlive(), "root.isAlive()").isTrue();
					server.pump();
					published = waits.poll();
				}
				requireThat(root.getInputStream().available(), "available").isEqualTo(0);
				signal(root, "-USR1");
				waits.resume(published.getAsLong(), server::grant);
				pumpUntilExit(server, root);
				requireThat(readAll(root), "output").isEqualTo("allocated\n");
				requireThat(root.waitFor(), "exitValue").isEqualTo(0);
				server.close(() -> !root.isAlive());
			}
			finally
			{
				root.destroyForcibly();
			}
		}
	}

	/**
	 * A static target, which ignores the library, is seized by the server's tracer and runs to completion under it.
	 * <p>
	 * The root spawns the target rather than exec it: the root is this JVM's child, and Java's process reaper would
	 * consume the ptrace stops of a child that this JVM also traces.
	 * @throws IOException          if the fixture cannot be built or run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void tracesStaticSpawnTarget() throws IOException, InterruptedException
	{
		try (LinuxBuildHookInstallation installation = LinuxBuildHookInstallation.create();
			ToolingScope scope = new DefaultToolingScope())
		{
			Path target = installation.compileStatic();
			Process root = startRoot(installation, "spawn-static", target.toString());
			try
			{
				LinuxBuildHookServer server = newServer(scope, installation, root, List.of(),
					installation.directory().resolve("e"), new AllocationWaits());
				server.start(environment -> release(root, environment));
				pumpUntilExit(server, root);
				requireThat(readAll(root), "output").isEqualTo("static-child\n");
				requireThat(root.waitFor(), "exitValue").isEqualTo(0);
				server.close(() -> !root.isAlive());
			}
			finally
			{
				root.destroyForcibly();
			}
		}
	}

	/**
	 * Starts the held root, which waits for its released environment on its standard input.
	 * @param installation the compiled program
	 * @param arguments    the program's arguments
	 * @return the root
	 * @throws IOException if it cannot start
	 */
	private static Process startRoot(LinuxBuildHookInstallation installation, String... arguments) throws IOException
	{
		List<String> command = new ArrayList<>(List.of("sh", "-c", ROOT_SCRIPT, "sh",
			installation.program().toString()));
		command.addAll(List.of(arguments));
		return new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
	}

	/**
	 * Creates a server for the held root in the test's own cgroup.
	 * @param scope        supplies native functions and message mappers
	 * @param installation the compiled library
	 * @param root         the held root
	 * @param preloads     libraries the workload already preloads
	 * @param endpoint     the attempt's socket path
	 * @param waits        the attempt's allocation waits
	 * @return the unstarted server
	 * @throws IOException if the root's identity or the test's cgroup cannot be read
	 */
	private static LinuxBuildHookServer newServer(ToolingScope scope, LinuxBuildHookInstallation installation,
		Process root, List<Path> preloads, Path endpoint, AllocationWaits waits) throws IOException
	{
		String membership = Files.readString(Path.of("/proc/self/cgroup"));
		if (!membership.startsWith("0::/") || membership.equals("0::/\n") ||
			membership.indexOf('\n') != membership.length() - 1)
		{
			throw new SkipException("The test needs a single non-root cgroup v2 membership: " + membership);
		}
		Path group = CGROUP_ROOT.resolve(membership.substring("0::/".length(), membership.length() - 1));
		long started = AllocationHooks.startTime(Files.readString(Path.of("/proc", Long.toString(root.pid()),
			"stat")));
		var held = new AllocationHooks.Root(root.pid(), started, ProcessHandle.current().pid(), group);
		int uid = (Integer) Files.getAttribute(Path.of("/proc/self"), "unix:uid");
		int gid = (Integer) Files.getAttribute(Path.of("/proc/self"), "unix:gid");
		return new LinuxBuildHookServer(scope, UUID.randomUUID(), held, CGROUP_ROOT, uid, gid,
			installation.library(), preloads, endpoint, waits, TIMEOUT, () ->
			{
			}, Optional.empty());
	}

	/**
	 * Writes the released entries to the root, one per line, and closes its standard input.
	 * @param root        the held root
	 * @param environment the entries
	 * @throws IOException if writing fails
	 */
	private static void release(Process root, List<String> environment) throws IOException
	{
		try (OutputStream input = root.getOutputStream())
		{
			input.write(String.join("\n", environment).getBytes(StandardCharsets.UTF_8));
		}
	}

	/**
	 * Services the server until the root exits.
	 * @param server the server
	 * @param root   the root
	 * @throws IOException if servicing fails
	 */
	private static void pumpUntilExit(LinuxBuildHookServer server, Process root) throws IOException
	{
		while (root.isAlive())
		{
			server.pump();
			Thread.onSpinWait();
		}
		server.pump();
	}

	/**
	 * Sends a signal to a process.
	 * @param process the process
	 * @param signal  the {@code kill} option naming the signal
	 * @throws IOException          if the signal cannot be sent
	 * @throws InterruptedException if interrupted
	 */
	private static void signal(Process process, String signal) throws IOException, InterruptedException
	{
		Process kill = new ProcessBuilder("kill", signal, Long.toString(process.pid())).inheritIO().start();
		if (kill.waitFor() != 0)
			throw new IOException("kill " + signal + " failed");
	}

	/**
	 * Reads a process's whole standard output.
	 * @param process the process
	 * @return its output
	 * @throws IOException if reading fails
	 */
	private static String readAll(Process process) throws IOException
	{
		try (InputStream output = process.getInputStream())
		{
			return new String(output.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
