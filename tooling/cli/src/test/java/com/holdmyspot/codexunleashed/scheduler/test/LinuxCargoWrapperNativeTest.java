package com.holdmyspot.codexunleashed.scheduler.test;

import java.io.EOFException;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.testng.SkipException;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Runs the native Linux Cargo compiler wrapper against a fake controller in the test.
 * <p>
 * Each test compiles {@code linux-cargo-wrapper.c} into its own temporary directory. Cargo is represented by this test
 * starting the wrapper with a compiler path and arguments; rustc is represented by a shell script that the wrapper's
 * child executes; the root controller is represented by a Unix-domain listener in this test that speaks protocol
 * version 3. The test build defines {@code CODEX_TEST_ALLOW_NONROOT_CONTROLLER}, which lets the test's own user stand in
 * for root as the owner of the configuration and as the controller's peer.
 */
public final class LinuxCargoWrapperNativeTest
{
	/** Environment variable through which the wrapper finds its configuration. */
	private static final String CONFIG_ENV = "CODEX_LINUX_CARGO_WRAPPER_CONFIG";
	/** Largest frame payload either side may send. */
	private static final int MAX_FRAME_BYTES = 1_048_576;
	/** Controller read limit for tests whose controller always answers. */
	private static final int PATIENT_TIMEOUT_MILLIS = 60_000;
	/** Upper bound on any one test, so that a wrapper that never connects fails the test instead of hanging it. */
	private static final long TEST_TIMEOUT_MILLIS = 120_000;
	/** An artifact notification in the form that rustc 1.95 prints. */
	private static final String ARTIFACT_LINE = "{\"$message_type\":\"artifact\"," +
		"\"artifact\":\"/work/target/debug/deps/libfixture.rlib\",\"emit\":\"link\"}";

	/**
	 * Creates a resource-free test instance; each method owns its compiled wrapper, directory and processes.
	 */
	public LinuxCargoWrapperNativeTest()
	{
	}

	/**
	 * A released compiler runs with the registered argv, environment and working directory, its stdout reaches the
	 * wrapper's caller, its raw wait status reaches the controller, and the wrapper exits with the controller's final
	 * status.
	 * @throws IOException          if the fixture cannot be built or the wrapper cannot be run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void releasedCompilerReportsStatusAndOutput() throws IOException, InterruptedException
	{
		try (WrapperInstallation installation = WrapperInstallation.create(PATIENT_TIMEOUT_MILLIS, true))
		{
			Path compiler = installation.script("compiler", """
				#!/bin/sh
				for argument in "$@"; do printf 'argument:%s\\n' "$argument"; done
				printf 'directory:%s\\n' "$(pwd -P)"
				printf 'value:%s\\n' "$FIXTURE_VALUE"
				exit 3
				""");
			Process wrapper = installation.start(List.of(compiler.toString(), "first argument", "second"),
				Map.of("FIXTURE_VALUE", "two words"));
			try (ControllerConnection controller = installation.accept())
			{
				List<String> registration = controller.receive();
				requireThat(registration.subList(0, 7), "registration").isEqualTo(List.of("register", "3",
					installation.work().toRealPath().toString(), "3", compiler.toString(), "first argument", "second"));
				int environmentCount = Integer.parseInt(registration.get(7));
				List<String> environment = registration.subList(8, registration.size());
				requireThat(environment.size(), "environment.size()").isEqualTo(environmentCount);
				requireThat(environment, "environment").contains("FIXTURE_VALUE=two words");

				List<String> child = controller.receive();
				requireThat(child.subList(0, 2), "child").isEqualTo(List.of("child", "1"));
				controller.send("release", "1");
				requireThat(controller.receive(), "exited").isEqualTo(List.of("exited", "1", Integer.toString(3 << 8)));
				controller.send("final", "3");
				requireThat(wrapper.waitFor(), "wrapperStatus").isEqualTo(3);
			}
			finally
			{
				installation.stop(wrapper);
			}
			requireThat(installation.stdout(), "stdout").isEqualTo("""
				argument:first argument
				argument:second
				directory:%s
				value:two words
				""".formatted(installation.work().toRealPath()));
		}
	}

	/**
	 * Environment entries sent with the release reach the compiler: they replace a registered value of the same name
	 * and add new names, and a value may itself contain {@code =}.
	 * @throws IOException          if the fixture cannot be built or the wrapper cannot be run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void releaseSetsEnvironmentEntries() throws IOException, InterruptedException
	{
		try (WrapperInstallation installation = WrapperInstallation.create(PATIENT_TIMEOUT_MILLIS, true))
		{
			Path compiler = installation.script("compiler", """
				#!/bin/sh
				printf 'value:%s\\n' "$FIXTURE_VALUE"
				printf 'added:%s\\n' "$FIXTURE_ADDED"
				printf 'kept:%s\\n' "$FIXTURE_KEPT"
				""");
			Process wrapper = installation.start(List.of(compiler.toString()),
				Map.of("FIXTURE_VALUE", "registered", "FIXTURE_KEPT", "unchanged"));
			try (ControllerConnection controller = installation.accept())
			{
				controller.receive();
				controller.receive();
				controller.send("release", "1", "FIXTURE_VALUE=replaced", "FIXTURE_ADDED=a=b");
				requireThat(controller.receive(), "exited").isEqualTo(List.of("exited", "1", "0"));
				controller.send("final", "0");
				requireThat(wrapper.waitFor(), "wrapperStatus").isEqualTo(0);
			}
			finally
			{
				installation.stop(wrapper);
			}
			requireThat(installation.stdout(), "stdout").isEqualTo("""
				value:replaced
				added:a=b
				kept:unchanged
				""");
		}
	}

	/**
	 * A release entry without a name before its {@code =} is a protocol error: the wrapper kills and reaps the held
	 * child, which never executes the compiler, and exits 125.
	 * @throws IOException          if the fixture cannot be built or the wrapper cannot be run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void releaseRejectsUnnamedEntry() throws IOException, InterruptedException
	{
		try (WrapperInstallation installation = WrapperInstallation.create(PATIENT_TIMEOUT_MILLIS, true))
		{
			Path marker = installation.work().resolve("executed");
			Path compiler = installation.script("compiler", """
				#!/bin/sh
				: > "$1"
				""");
			Process wrapper = installation.start(List.of(compiler.toString(), marker.toString()), Map.of());
			try (ControllerConnection controller = installation.accept())
			{
				controller.receive();
				controller.receive();
				controller.send("release", "1", "=value");
				requireThat(wrapper.waitFor(), "wrapperStatus").isEqualTo(125);
			}
			finally
			{
				installation.stop(wrapper);
			}
			requireThat(Files.exists(marker), "markerExists").isFalse();
		}
	}

	/**
	 * The forked child stays the wrapper's own image, blocked, until the controller releases it, and executes the
	 * compiler only after the release.
	 * @throws IOException          if the fixture cannot be built or the wrapper cannot be run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void childWaitsForRelease() throws IOException, InterruptedException
	{
		try (WrapperInstallation installation = WrapperInstallation.create(PATIENT_TIMEOUT_MILLIS, true))
		{
			Path marker = installation.work().resolve("executed");
			Path compiler = installation.script("compiler", """
				#!/bin/sh
				: > "$1"
				""");
			Process wrapper = installation.start(List.of(compiler.toString(), marker.toString()), Map.of());
			try (ControllerConnection controller = installation.accept())
			{
				controller.receive();
				long pid = Long.parseLong(controller.receive().get(2));
				String wrapperCommand = commandName(wrapper.pid());
				String state;
				String current;
				do
				{
					spinOnce();
					state = statFields(pid).getFirst();
					current = commandName(pid);
				}
				while (!state.equals("S") && current.equals(wrapperCommand));
				requireThat(current, "childCommand").isEqualTo(wrapperCommand);
				requireThat(state, "childState").isEqualTo("S");
				requireThat(Files.exists(marker), "markerExistsBeforeRelease").isFalse();

				controller.send("release", "1");
				requireThat(controller.receive(), "exited").isEqualTo(List.of("exited", "1", "0"));
				requireThat(marker, "marker").isRegularFile();
				controller.send("final", "0");
				requireThat(wrapper.waitFor(), "wrapperStatus").isEqualTo(0);
			}
			finally
			{
				installation.stop(wrapper);
			}
		}
	}

	/**
	 * The {@code child} message names the wrapper's direct child by the pid and start time that {@code /proc} reports for
	 * it.
	 * @throws IOException          if the fixture cannot be built or the wrapper cannot be run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void childIdentityMatchesProc() throws IOException, InterruptedException
	{
		try (WrapperInstallation installation = WrapperInstallation.create(PATIENT_TIMEOUT_MILLIS, true))
		{
			Path compiler = installation.script("compiler", """
				#!/bin/sh
				exit 0
				""");
			Process wrapper = installation.start(List.of(compiler.toString()), Map.of());
			try (ControllerConnection controller = installation.accept())
			{
				controller.receive();
				List<String> child = controller.receive();
				requireThat(child.size(), "child.size()").isEqualTo(4);
				long pid = Long.parseLong(child.get(2));
				List<String> stat = statFields(pid);
				// Field 4 is the parent pid and field 22 the start time; the list starts at field 3.
				requireThat(stat.get(1), "parentPid").isEqualTo(Long.toString(wrapper.pid()));
				requireThat(child.get(3), "startTime").isEqualTo(stat.get(19));

				controller.send("release", "1");
				controller.receive();
				controller.send("final", "0");
				requireThat(wrapper.waitFor(), "wrapperStatus").isEqualTo(0);
			}
			finally
			{
				installation.stop(wrapper);
			}
		}
	}

	/**
	 * After a retry, the second attempt runs; an artifact notification that both attempts print reaches the caller's
	 * stderr once, a repeated ordinary line reaches it twice, the first attempt's unterminated last line is discarded and
	 * the second attempt's is forwarded at the final status.
	 * @throws IOException          if the fixture cannot be built or the wrapper cannot be run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void retryRelaysArtifactOnce() throws IOException, InterruptedException
	{
		try (WrapperInstallation installation = WrapperInstallation.create(PATIENT_TIMEOUT_MILLIS, true))
		{
			Path counter = installation.work().resolve("attempts");
			Path compiler = installation.script("compiler", """
				#!/bin/sh
				count=1
				if [ -e "$1" ]; then count=$(($(cat "$1") + 1)); fi
				echo "$count" > "$1"
				printf '%%s\\n' '%s' >&2
				printf 'note: repeated\\n' >&2
				printf 'partial %%s' "$count" >&2
				""".formatted(ARTIFACT_LINE));
			Process wrapper = installation.start(List.of(compiler.toString(), counter.toString()), Map.of());
			try (ControllerConnection controller = installation.accept())
			{
				controller.receive();
				requireThat(controller.receive().subList(0, 2), "firstChild").isEqualTo(List.of("child", "1"));
				controller.send("release", "1");
				requireThat(controller.receive(), "firstExited").isEqualTo(List.of("exited", "1", "0"));
				controller.send("retry");
				requireThat(controller.receive().subList(0, 2), "secondChild").isEqualTo(List.of("child", "2"));
				controller.send("release", "2");
				requireThat(controller.receive(), "secondExited").isEqualTo(List.of("exited", "2", "0"));
				controller.send("final", "0");
				requireThat(wrapper.waitFor(), "wrapperStatus").isEqualTo(0);
			}
			finally
			{
				installation.stop(wrapper);
			}
			requireThat(Files.readString(counter).strip(), "attempts").isEqualTo("2");
			requireThat(installation.stderr(), "stderr").isEqualTo(ARTIFACT_LINE + "\n" +
				"note: repeated\n" +
				"note: repeated\n" +
				"partial 2");
		}
	}

	/**
	 * When the controller closes its connection before releasing the child, the wrapper exits 125 with a diagnostic and
	 * leaves no child behind, and the compiler never runs.
	 * @throws IOException          if the fixture cannot be built or the wrapper cannot be run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void disconnectBeforeReleaseKillsChild() throws IOException, InterruptedException
	{
		try (WrapperInstallation installation = WrapperInstallation.create(PATIENT_TIMEOUT_MILLIS, true))
		{
			Path marker = installation.work().resolve("executed");
			Path compiler = installation.script("compiler", """
				#!/bin/sh
				: > "$1"
				""");
			Process wrapper = installation.start(List.of(compiler.toString(), marker.toString()), Map.of());
			List<String> child;
			try
			{
				try (ControllerConnection controller = installation.accept())
				{
					controller.receive();
					child = controller.receive();
				}
				requireThat(wrapper.waitFor(), "wrapperStatus").isEqualTo(125);
			}
			finally
			{
				installation.stop(wrapper);
			}
			long pid = Long.parseLong(child.get(2));
			requireThat(startTime(pid), "childStartTimeAfterWrapperExit").isNotEqualTo(Optional.of(child.get(3)));
			requireThat(Files.exists(marker), "markerExists").isFalse();
			requireThat(installation.stderr(), "stderr").contains("controller connection reached EOF");
		}
	}

	/**
	 * When the controller aborts the child before releasing it, the wrapper kills it, reports the SIGKILL wait status and
	 * exits with the controller's final status; the compiler never runs.
	 * @throws IOException          if the fixture cannot be built or the wrapper cannot be run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void abortBeforeReleaseKillsChild() throws IOException, InterruptedException
	{
		try (WrapperInstallation installation = WrapperInstallation.create(PATIENT_TIMEOUT_MILLIS, true))
		{
			Path marker = installation.work().resolve("executed");
			Path compiler = installation.script("compiler", """
				#!/bin/sh
				: > "$1"
				""");
			Process wrapper = installation.start(List.of(compiler.toString(), marker.toString()), Map.of());
			try (ControllerConnection controller = installation.accept())
			{
				controller.receive();
				controller.receive();
				controller.send("abort", "1");
				// A raw wait status of 9 means the child was terminated by signal 9, SIGKILL.
				requireThat(controller.receive(), "exited").isEqualTo(List.of("exited", "1", "9"));
				controller.send("final", "7");
				requireThat(wrapper.waitFor(), "wrapperStatus").isEqualTo(7);
			}
			finally
			{
				installation.stop(wrapper);
			}
			requireThat(Files.exists(marker), "markerExists").isFalse();
		}
	}

	/**
	 * When the controller does not answer within {@code timeout_millis}, the wrapper exits 125 with a diagnostic and
	 * leaves no child behind.
	 * @throws IOException          if the fixture cannot be built or the wrapper cannot be run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void silentControllerTimesOut() throws IOException, InterruptedException
	{
		try (WrapperInstallation installation = WrapperInstallation.create(200, true))
		{
			Path compiler = installation.script("compiler", """
				#!/bin/sh
				exit 0
				""");
			Process wrapper = installation.start(List.of(compiler.toString()), Map.of());
			List<String> child;
			try (ControllerConnection controller = installation.accept())
			{
				controller.receive();
				child = controller.receive();
				// The connection stays open, so only the read limit can end the wrapper's wait.
				requireThat(wrapper.waitFor(), "wrapperStatus").isEqualTo(125);
			}
			finally
			{
				installation.stop(wrapper);
			}
			long pid = Long.parseLong(child.get(2));
			requireThat(startTime(pid), "childStartTimeAfterWrapperExit").isNotEqualTo(Optional.of(child.get(3)));
			requireThat(installation.stderr(), "stderr").contains("exceeded timeout_millis");
		}
	}

	/**
	 * The compiler inherits the open jobserver descriptors that the make flags name, but no other descriptor above
	 * stderr that the wrapper's parent left open and none that the wrapper opened under a number the make flags name.
	 * @throws IOException          if the fixture cannot be built or the wrapper cannot be run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void compilerInheritsOnlyJobserverDescriptors() throws IOException, InterruptedException
	{
		try (WrapperInstallation installation = WrapperInstallation.create(PATIENT_TIMEOUT_MILLIS, true))
		{
			Path compiler = installation.script("compiler", """
				#!/bin/sh
				for descriptor in 3 4 5 6 7 8 9; do
				  if [ -e /proc/$$/fd/$descriptor ]; then echo "$descriptor"; fi
				done
				""");
			// The shell stands in for Cargo: it leaves descriptor 7 open by accident and 8 and 9 open as the jobserver.
			// MAKEFLAGS also names descriptors 3 and 4, which are closed, so the wrapper's own descriptors take those
			// numbers and must not reach the compiler.
			Path parent = installation.script("cargo", """
				#!/bin/sh
				exec 7</dev/null 8</dev/null 9>/dev/null
				exec "$@"
				""");
			Process wrapper = installation.startCommand(List.of(parent.toString(), installation.executable().toString(),
				compiler.toString()), Map.of("CARGO_MAKEFLAGS", "-j --jobserver-fds=8,9 --jobserver-auth=8,9",
				"MAKEFLAGS", "-j --jobserver-auth=3,4"));
			try (ControllerConnection controller = installation.accept())
			{
				controller.receive();
				controller.receive();
				controller.send("release", "1");
				requireThat(controller.receive(), "exited").isEqualTo(List.of("exited", "1", "0"));
				controller.send("final", "0");
				requireThat(wrapper.waitFor(), "wrapperStatus").isEqualTo(0);
			}
			finally
			{
				installation.stop(wrapper);
			}
			requireThat(installation.stdout(), "openDescriptors").isEqualTo("8\n9\n");
		}
	}

	/**
	 * A wrapper built without the test define rejects a configuration that a non-root user owns and exits 125 without
	 * connecting to the controller.
	 * @throws IOException          if the fixture cannot be built or the wrapper cannot be run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void productionBuildRejectsUserOwnedConfiguration() throws IOException, InterruptedException
	{
		try (WrapperInstallation installation = WrapperInstallation.create(PATIENT_TIMEOUT_MILLIS, false))
		{
			if (Files.getAttribute(installation.work(), "unix:uid").equals(0))
				throw new SkipException("A configuration that root owns is trusted, so this test needs a non-root user");
			Path compiler = installation.script("compiler", """
				#!/bin/sh
				exit 0
				""");
			Process wrapper = installation.start(List.of(compiler.toString()), Map.of());
			try
			{
				requireThat(wrapper.waitFor(), "wrapperStatus").isEqualTo(125);
			}
			finally
			{
				installation.stop(wrapper);
			}
			requireThat(installation.pendingConnection(), "pendingConnection").isFalse();
			requireThat(installation.stderr(), "stderr").contains("owned by uid 0");
		}
	}

	/**
	 * A termination signal other than SIGINT, SIGTERM and SIGHUP, here SIGQUIT, that reaches the wrapper before the
	 * release makes it kill and reap the child and exit 125 instead of ending with the child unreaped.
	 * @throws IOException          if the fixture cannot be built or a process cannot be run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void quitSignalBeforeReleaseKillsChild() throws IOException, InterruptedException
	{
		try (WrapperInstallation installation = WrapperInstallation.create(PATIENT_TIMEOUT_MILLIS, true))
		{
			Path marker = installation.work().resolve("executed");
			Path compiler = installation.script("compiler", """
				#!/bin/sh
				: > "$1"
				""");
			Process wrapper = installation.start(List.of(compiler.toString(), marker.toString()), Map.of());
			List<String> child;
			try (ControllerConnection controller = installation.accept())
			{
				controller.receive();
				child = controller.receive();
				// Java has no API that sends SIGQUIT, so the kill program delivers it.
				Process kill = new ProcessBuilder("kill", "-s", "QUIT", Long.toString(wrapper.pid())).
					redirectErrorStream(true).redirectOutput(Redirect.DISCARD).start();
				requireThat(kill.waitFor(), "killStatus").isEqualTo(0);
				requireThat(wrapper.waitFor(), "wrapperStatus").isEqualTo(125);
			}
			finally
			{
				installation.stop(wrapper);
			}
			long pid = Long.parseLong(child.get(2));
			requireThat(startTime(pid), "childStartTimeAfterWrapperExit").isNotEqualTo(Optional.of(child.get(3)));
			requireThat(Files.exists(marker), "markerExists").isFalse();
			requireThat(installation.stderr(), "stderr").contains("received signal 3");
		}
	}

	/**
	 * When Cargo stops reading the wrapper's stderr while the compiler keeps writing, so that the wrapper cannot relay
	 * more, losing the controller still makes the wrapper kill and reap the compiler and exit 125 with a diagnostic.
	 * @throws IOException          if the fixture cannot be built or the wrapper cannot be run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void blockedStderrStillSeesControllerLoss() throws IOException, InterruptedException
	{
		try (WrapperInstallation installation = WrapperInstallation.create(PATIENT_TIMEOUT_MILLIS, true))
		{
			// 16 MiB of 1024-byte lines exceeds the capacity of both pipes and the wrapper's read buffer on any page size.
			int lineBytes = 1024;
			Path compiler = installation.script("compiler", """
				#!/bin/sh
				yes "$(printf '%%0%dd' 0)" | head -c 16777216 >&2
				""".formatted(lineBytes - 1));
			int heldWhenFull = pollWritableLimit(lineBytes);
			// The test stands in for Cargo by holding the read end of the wrapper's stderr pipe without reading it.
			Process wrapper = installation.startCommand(List.of(installation.executable().toString(),
				compiler.toString()), Map.of(), Redirect.from(Path.of("/dev/null").toFile()), Redirect.PIPE);
			List<String> child;
			try
			{
				try (ControllerConnection controller = installation.accept())
				{
					controller.receive();
					child = controller.receive();
					controller.send("release", "1");
					// Once the pipe holds this much, the wrapper holds more compiler output that it cannot write.
					while (wrapper.getErrorStream().available() < heldWhenFull)
						spinOnce();
				}
				long pid = Long.parseLong(child.get(2));
				while (startTime(pid).equals(Optional.of(child.get(3))))
					spinOnce();
				String stderr = new String(wrapper.getErrorStream().readAllBytes(), UTF_8);
				requireThat(wrapper.waitFor(), "wrapperStatus").isEqualTo(125);
				requireThat(stderr, "stderr").contains("controller connection reached EOF");
			}
			finally
			{
				installation.stop(wrapper);
			}
		}
	}

	/**
	 * While the child waits for the release, no other process of the build user can open the wrapper's or the child's
	 * descriptors, such as the release pipe, through {@code /proc}; the compiler that the release lets run is as
	 * accessible as any program that the build user starts.
	 * @throws IOException          if the fixture cannot be built or the wrapper cannot be run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = TEST_TIMEOUT_MILLIS)
	public void descriptorsHiddenUntilRelease() throws IOException, InterruptedException
	{
		try (WrapperInstallation installation = WrapperInstallation.create(PATIENT_TIMEOUT_MILLIS, true))
		{
			if (Files.getAttribute(installation.work(), "unix:uid").equals(0))
				throw new SkipException("Root can open the descriptors of every process, so this test needs a non-root user");
			Path marker = installation.work().resolve("executed");
			Path compiler = installation.script("compiler", """
				#!/bin/sh
				: > "$1"
				read line
				exit 0
				""");
			// The compiler stays alive, reading stdin, until the test closes stdin.
			Process wrapper = installation.startCommand(List.of(installation.executable().toString(),
				compiler.toString(), marker.toString()), Map.of(), Redirect.PIPE, installation.capturedStderr());
			try (ControllerConnection controller = installation.accept())
			{
				controller.receive();
				long pid = Long.parseLong(controller.receive().get(2));
				requireThat(descriptorsListable(wrapper.pid()), "wrapperDescriptorsListable").isFalse();
				requireThat(descriptorsListable(pid), "waitingChildDescriptorsListable").isFalse();

				controller.send("release", "1");
				while (!Files.exists(marker))
					spinOnce();
				requireThat(descriptorsListable(pid), "compilerDescriptorsListable").isTrue();
				wrapper.getOutputStream().close();
				requireThat(controller.receive(), "exited").isEqualTo(List.of("exited", "1", "0"));
				controller.send("final", "0");
				requireThat(wrapper.waitFor(), "wrapperStatus").isEqualTo(0);
			}
			finally
			{
				installation.stop(wrapper);
			}
		}
	}

	/**
	 * Reads the fields of {@code /proc/PID/stat} that follow the command name.
	 * @param pid the process to inspect
	 * @return the fields starting with field 3, the process state
	 * @throws NoSuchFileException if no process has this pid
	 * @throws IOException         if the file cannot be read
	 */
	private static List<String> statFields(long pid) throws IOException
	{
		String stat = Files.readString(Path.of("/proc/" + pid + "/stat"));
		// The command name in field 2 may contain spaces and parentheses; it ends at the last closing parenthesis.
		return List.of(stat.substring(stat.lastIndexOf(')') + 2).strip().split(" "));
	}

	/**
	 * Returns the start time of the process that currently has a pid.
	 * @param pid the process to inspect
	 * @return field 22 of {@code /proc/PID/stat}, or empty when no process has this pid
	 * @throws IOException if the file exists but cannot be read
	 */
	private static Optional<String> startTime(long pid) throws IOException
	{
		try
		{
			return Optional.of(statFields(pid).get(19));
		}
		catch (NoSuchFileException _)
		{
			return Optional.empty();
		}
		catch (IOException failure)
		{
			// A process reaped between opening and reading its stat file makes the read fail with ESRCH.
			if (Files.notExists(Path.of("/proc/" + pid)))
				return Optional.empty();
			throw failure;
		}
	}

	/**
	 * Waits briefly in a busy-wait loop, ending the loop when the test's timeout interrupts it so that a condition that
	 * never holds cannot keep the test's thread running after the test has failed.
	 * @throws InterruptedException if the thread is interrupted
	 */
	private static void spinOnce() throws InterruptedException
	{
		if (Thread.interrupted())
			throw new InterruptedException("Interrupted while waiting for a process condition");
		Thread.onSpinWait();
	}

	/**
	 * Returns the command name of a process.
	 * @param pid the process to inspect
	 * @return field 2 of {@code /proc/PID/stat}, without its parentheses
	 * @throws NoSuchFileException if no process has this pid
	 * @throws IOException         if the file cannot be read
	 */
	private static String commandName(long pid) throws IOException
	{
		String stat = Files.readString(Path.of("/proc/" + pid + "/stat"));
		return stat.substring(stat.indexOf('(') + 1, stat.lastIndexOf(')'));
	}

	/**
	 * Reports whether this test's process may list another process's open descriptors, which is also what opening one
	 * of them through {@code /proc/PID/fd} requires.
	 * @param pid the process to inspect
	 * @return {@code false} if the kernel denies access to {@code /proc/PID/fd}
	 * @throws IOException if the directory cannot be listed for another reason
	 */
	private static boolean descriptorsListable(long pid) throws IOException
	{
		try (Stream<Path> descriptors = Files.list(Path.of("/proc/" + pid + "/fd")))
		{
			return descriptors.findAny().isPresent();
		}
		catch (AccessDeniedException _)
		{
			return false;
		}
	}

	/**
	 * Measures how many bytes a new pipe of this user holds once a writer that writes fixed-size chunks only while poll
	 * reports the pipe writable must stop, as the wrapper does with each relayed line. The kernel stops reporting a pipe
	 * writable while its last page still has room, so this is less than the pipe's capacity. The stderr pipe that the
	 * JVM creates for a started process has the same size as a new pipe.
	 * @param chunkBytes the size of each write
	 * @return the number of bytes the pipe holds when poll first reports it not writable
	 * @throws IOException if the pipe cannot be created or written
	 */
	private static int pollWritableLimit(int chunkBytes) throws IOException
	{
		Pipe pipe = Pipe.open();
		try (Pipe.SinkChannel sink = pipe.sink();
			Pipe.SourceChannel _ = pipe.source();
			Selector selector = Selector.open())
		{
			sink.configureBlocking(false);
			sink.register(selector, SelectionKey.OP_WRITE);
			ByteBuffer chunk = ByteBuffer.allocate(chunkBytes);
			int held = 0;
			while (selector.selectNow() > 0)
			{
				selector.selectedKeys().clear();
				chunk.clear();
				held += sink.write(chunk);
			}
			return held;
		}
	}

	/**
	 * A test-owned directory holding a compiled wrapper, its configuration, fixture scripts, captured output and the
	 * fake controller's listening socket. Closing it closes the socket and deletes the directory.
	 * @param directory  the owned directory
	 * @param executable the compiled wrapper
	 * @param config     the wrapper's configuration file
	 * @param listener   the fake controller's listening socket
	 */
	private record WrapperInstallation(Path directory, Path executable, Path config, ServerSocketChannel listener)
		implements AutoCloseable
	{
		/**
		 * Compiles the wrapper, writes its configuration and starts listening as its controller.
		 * @param timeoutMillis the configuration's {@code timeout_millis}
		 * @param testBuild     {@code true} to compile with the test define, which accepts the test's user in place of
		 *                      root, or {@code false} to compile the production program
		 * @return the installation, which the caller must close
		 * @throws SkipException if the operating system is not Linux or no C compiler is installed
		 * @throws IOException   if a file cannot be written or the wrapper does not compile cleanly
		 */
		static WrapperInstallation create(int timeoutMillis, boolean testBuild) throws IOException
		{
			if (!System.getProperty("os.name").equals("Linux"))
				throw new SkipException("The Cargo wrapper requires Linux");
			try
			{
				new ProcessBuilder("cc", "--version").redirectErrorStream(true).redirectOutput(
					Redirect.DISCARD).start().waitFor();
			}
			catch (IOException unavailable)
			{
				throw new SkipException("No C compiler is installed", unavailable);
			}
			catch (InterruptedException interrupted)
			{
				Thread.currentThread().interrupt();
				throw new SkipException("Interrupted while probing for a C compiler", interrupted);
			}

			// The short prefix keeps the socket path below the 108-byte Unix-domain limit.
			Path directory = Files.createTempDirectory("cw-");
			ServerSocketChannel listener = null;
			try
			{
				Path executable = compile(directory, testBuild);
				Files.createDirectory(directory.resolve("work"));
				Path config = directory.resolve("config");
				Path socket = directory.resolve("c");
				Files.writeString(config, "version=2\ncontroller=" + socket + "\ntimeout_millis=" + timeoutMillis + "\n");
				Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("r--r--r--"));
				listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
				listener.bind(UnixDomainSocketAddress.of(socket));
				return new WrapperInstallation(directory, executable, config, listener);
			}
			catch (IOException | RuntimeException | Error failure)
			{
				try
				{
					if (listener != null)
						listener.close();
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
		 * Compiles the wrapper source with every warning treated as an error.
		 * @param directory the owned directory that receives the source and the program
		 * @param testBuild {@code true} to compile with the test define
		 * @return the compiled program
		 * @throws IOException if the source is missing, cannot be copied, or does not compile cleanly
		 */
		private static Path compile(Path directory, boolean testBuild) throws IOException
		{
			Path source = directory.resolve("linux-cargo-wrapper.c");
			// The main module does not open its resource package, so the test reads the source from the tree.
			Files.copy(Path.of(System.getProperty("basedir", ".")).resolve(
				"src/main/resources/com/holdmyspot/codexunleashed/scheduler/linux-cargo-wrapper.c"), source);
			Path executable = directory.resolve("linux-cargo-wrapper");
			List<String> command = new ArrayList<>(List.of("cc", "-std=c11", "-Wall", "-Wextra", "-Werror", "-O2"));
			if (testBuild)
			{
				command.add("-DCODEX_TEST_ALLOW_NONROOT_CONTROLLER");
				// The checked namespace starts at this directory because the test cannot control its ancestors.
				String quoted = directory.toString().replace("\\", "\\\\").replace("\"", "\\\"");
				command.add("-DCODEX_TEST_TRUSTED_DIRECTORY=\"" + quoted + "\"");
			}
			command.addAll(List.of("-o", executable.toString(), source.toString()));
			Process compiler = new ProcessBuilder(command).redirectErrorStream(true).start();
			String diagnostics = new String(compiler.getInputStream().readAllBytes(), UTF_8);
			int status;
			try
			{
				status = compiler.waitFor();
			}
			catch (InterruptedException interrupted)
			{
				compiler.destroyForcibly();
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while compiling the wrapper", interrupted);
			}
			if (status != 0)
				throw new IOException("The wrapper did not compile cleanly:\n" + diagnostics);
			return executable;
		}

		/**
		 * Returns the directory in which the wrapper starts.
		 * @return the wrapper's working directory
		 */
		Path work()
		{
			return directory.resolve("work");
		}

		/**
		 * Writes an executable fixture script.
		 * @param name    the script's file name
		 * @param content the complete script
		 * @return the script
		 * @throws IOException if the script cannot be written
		 */
		Path script(String name, String content) throws IOException
		{
			Path script = directory.resolve(name);
			Files.writeString(script, content);
			Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
			return script;
		}

		/**
		 * Starts the wrapper the way Cargo starts {@code RUSTC_WRAPPER}, with stdout and stderr captured to files.
		 * @param arguments   the compiler and its arguments, which follow the wrapper on its command line
		 * @param environment variables added to a minimal environment that already names the configuration
		 * @return the started process
		 * @throws IOException if the process cannot be started
		 */
		Process start(List<String> arguments, Map<String, String> environment) throws IOException
		{
			List<String> command = new ArrayList<>();
			command.add(executable.toString());
			command.addAll(arguments);
			return startCommand(command, environment);
		}

		/**
		 * Starts a command in the wrapper's working directory with its environment and output capture.
		 * @param command     the complete command, which executes the wrapper itself or through a parent program
		 * @param environment variables added to a minimal environment that already names the configuration
		 * @return the started process
		 * @throws IOException if the process cannot be started
		 */
		Process startCommand(List<String> command, Map<String, String> environment) throws IOException
		{
			return startCommand(command, environment, Redirect.from(Path.of("/dev/null").toFile()), capturedStderr());
		}

		/**
		 * Starts a command in the wrapper's working directory with its environment, stdout capture and the given stdin
		 * and stderr.
		 * @param command     the complete command, which executes the wrapper itself or through a parent program
		 * @param environment variables added to a minimal environment that already names the configuration
		 * @param input       the source of the command's stdin
		 * @param error       the destination of the command's stderr
		 * @return the started process
		 * @throws IOException if the process cannot be started
		 */
		Process startCommand(List<String> command, Map<String, String> environment, Redirect input, Redirect error)
			throws IOException
		{
			ProcessBuilder builder = new ProcessBuilder(command).directory(work().toFile()).
				redirectInput(input).
				redirectOutput(directory.resolve("stdout").toFile()).
				redirectError(error);
			builder.environment().clear();
			builder.environment().put("PATH", "/usr/bin:/bin");
			builder.environment().put(CONFIG_ENV, config.toString());
			builder.environment().putAll(environment);
			return builder.start();
		}

		/**
		 * Accepts the wrapper's connection to the fake controller.
		 * @return the connection, which the caller must close
		 * @throws IOException if accepting fails
		 */
		ControllerConnection accept() throws IOException
		{
			return new ControllerConnection(listener.accept());
		}

		/**
		 * Reports whether a wrapper connected to the fake controller without being accepted.
		 * @return {@code true} if a connection is pending
		 * @throws IOException if polling the listener fails
		 */
		boolean pendingConnection() throws IOException
		{
			listener.configureBlocking(false);
			try (SocketChannel pending = listener.accept())
			{
				return pending != null;
			}
		}

		/**
		 * Ends a wrapper that is still running and waits for it to exit.
		 * @param wrapper the wrapper process
		 * @throws InterruptedException if the wait is interrupted
		 */
		void stop(Process wrapper) throws InterruptedException
		{
			if (wrapper.isAlive())
				wrapper.destroyForcibly();
			wrapper.waitFor();
		}

		/**
		 * Returns what the wrapper and its compiler wrote to stdout.
		 * @return the captured stdout
		 * @throws IOException if the capture cannot be read
		 */
		String stdout() throws IOException
		{
			return Files.readString(directory.resolve("stdout"));
		}

		/**
		 * Returns the stderr destination that {@link #stderr()} reads.
		 * @return a redirect to the installation's stderr capture file
		 */
		Redirect capturedStderr()
		{
			return Redirect.to(directory.resolve("stderr").toFile());
		}

		/**
		 * Returns what the wrapper wrote to stderr, including the relayed compiler output.
		 * @return the captured stderr
		 * @throws IOException if the capture cannot be read
		 */
		String stderr() throws IOException
		{
			return Files.readString(directory.resolve("stderr"));
		}

		/**
		 * Closes the listener and deletes the directory.
		 * @throws IOException if either fails
		 */
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
		 * @param root the directory to delete
		 * @throws IOException if an entry cannot be deleted
		 */
		private static void delete(Path root) throws IOException
		{
			try (var paths = Files.walk(root))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}

	/**
	 * The fake controller's end of one wrapper connection, exchanging version-2 frames.
	 * @param channel the accepted connection
	 */
	private record ControllerConnection(SocketChannel channel) implements AutoCloseable
	{
		/**
		 * Sends one message.
		 * @param fields the message type followed by its fields
		 * @throws IOException if writing fails
		 */
		void send(String... fields) throws IOException
		{
			List<byte[]> encoded = new ArrayList<>();
			int length = 0;
			for (String field : fields)
			{
				byte[] bytes = field.getBytes(UTF_8);
				encoded.add(bytes);
				length += bytes.length + 1;
			}
			ByteBuffer frame = ByteBuffer.allocate(4 + length).putInt(length);
			for (byte[] bytes : encoded)
				frame.put(bytes).put((byte) 0);
			frame.flip();
			while (frame.hasRemaining())
				channel.write(frame);
		}

		/**
		 * Receives one message.
		 * @return the message type followed by its fields
		 * @throws EOFException if the wrapper closes the connection
		 * @throws IOException  if reading fails
		 */
		List<String> receive() throws IOException
		{
			ByteBuffer header = ByteBuffer.allocate(4);
			readFully(header);
			int length = header.flip().getInt();
			requireThat(length, "length").isPositive().isLessThanOrEqualTo(MAX_FRAME_BYTES);
			ByteBuffer payload = ByteBuffer.allocate(length);
			readFully(payload);
			byte[] bytes = payload.array();
			requireThat(bytes[length - 1], "lastByte").isEqualTo((byte) 0);
			List<String> fields = new ArrayList<>();
			int start = 0;
			for (int index = 0; index < length; ++index)
				if (bytes[index] == 0)
				{
					fields.add(new String(bytes, start, index - start, UTF_8));
					start = index + 1;
				}
			return fields;
		}

		/**
		 * Fills a buffer from the connection.
		 * @param buffer the buffer to fill
		 * @throws EOFException if the wrapper closes the connection first
		 * @throws IOException  if reading fails
		 */
		private void readFully(ByteBuffer buffer) throws IOException
		{
			while (buffer.hasRemaining())
				if (channel.read(buffer) < 0)
					throw new EOFException("The wrapper closed the controller connection");
		}

		/**
		 * Closes the connection.
		 * @throws IOException if closing fails
		 */
		@Override
		public void close() throws IOException
		{
			channel.close();
		}
	}
}
