package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises real Linux attachment and script messages against a disposable Java target with an independent parent.
 * The independent parent prevents the controller's JVM reaper from consuming the native tracer's stop events.
 * This fixed-child diagnostic does not establish production containment, descendant ownership or compiler behavior.
 */
public final class FridaScriptProbe
{
	/**
	 * Prevents construction of this command entry point.
	 */
	private FridaScriptProbe()
	{
	}

	/**
	 * Runs native acceptance, its independent target owner, or that owner's target.
	 * The acceptance caller supplies an existing empty scratch directory and retains its files for archival and removal.
	 * Only the acceptance JVM loads Frida. Each process that launches a child owns its termination and waiting.
	 * @param args producer path, Java executable, complete class path and scratch path; or the internal
	 * --supervisor selector followed by Java executable, class path and scratch path; or --target and its readiness path
	 * @throws IllegalArgumentException if arguments do not select one of those operations
	 * @throws Exception if native behavior, message delivery, target execution, readiness or cleanup fails
	 */
	public static void main(String[] args) throws Exception
	{
		if (args.length == 4 && args[0].equals("--supervisor"))
		{
			supervise(Path.of(args[1]), args[2], Path.of(args[3]));
			return;
		}
		if (args.length == 2 && args[0].equals("--target"))
		{
			Files.writeString(Path.of(args[1]), "ready\n");
			System.in.read();
			return;
		}
		if (args.length != 4)
			throw new IllegalArgumentException("Expected producer, Java executable, class path and scratch directory");
		Path producer = Path.of(args[0]);
		Path java = Path.of(args[1]);
		String classPath = args[2];
		Path scratch = Path.of(args[3]);
		Path ready = scratch.resolve("supervisor-ready");
		ProcessBuilder builder = new ProcessBuilder(java.toString(), "-Djava.io.tmpdir=" + scratch,
			"-cp", classPath, FridaScriptProbe.class.getName(), "--supervisor", java.toString(), classPath, scratch.toString());
		builder.environment().clear();
		builder.environment().put("TMPDIR", scratch.toString());
		builder.directory(scratch.toFile());
		builder.redirectOutput(scratch.resolve("supervisor.out").toFile());
		builder.redirectError(scratch.resolve("supervisor.err").toFile());
		Process supervisor = builder.start();
		ProcessHandle target = null;
		try
		{
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while (Files.exists(ready) == false)
			{
				require(supervisor.isAlive(), "Supervisor exited before readiness");
				require(System.nanoTime() < deadline, "Target did not publish readiness");
				Thread.sleep(10);
			}
			long targetPid = Long.parseLong(Files.readString(ready).strip());
			target = ProcessHandle.of(targetPid).orElseThrow();
			require(target.parent().orElseThrow().pid() == supervisor.pid(), "Target's real parent is not the independent owner");
			System.out.println("owned_target_pid=" + target.pid() + "; independent_supervisor_pid=" + supervisor.pid());
			try (var runtime = new FridaRuntime(producer))
			{
				runtime.start();
				try (var manager = runtime.newManager())
				{
					manager.start();
					try (var absent = manager.newSession(0xffff_ffffL))
					{
						try
						{
							absent.start();
							throw new AssertionError("Attachment to the absent PID succeeded");
						}
						catch (FridaRuntime.NativeFailure expected)
						{
							require(expected.operation().equals("frida_device_attach_sync"), "Wrong failing operation");
							require(expected.domain().equals("frida-error-quark"), "Native domain was not preserved: " + expected.domain());
							require(expected.code() == 3, "Expected PROCESS_NOT_FOUND, got " + expected.code());
							System.out.println("typed_attachment_error=" + expected.getMessage());
						}
					}
					try (var session = manager.newSession(target.pid()))
					{
						session.start();
						try (var invalid = session.newScript("}"))
						{
							try
							{
								invalid.start();
								throw new AssertionError("Invalid JavaScript compiled");
							}
							catch (FridaRuntime.NativeFailure expected)
							{
								require(expected.operation().equals("frida_session_create_script_sync"), "Wrong compilation error operation");
								System.out.println("partial_script_start_error=" + expected.getMessage());
							}
						}
						try (var first = session.newScript("send('first-ready');");
							var second = session.newScript("send('second-ready'); recv('ping', m => send(m.token));"))
						{
							first.start();
							second.start();
							expect(first, "first-ready");
							expect(second, "second-ready");
							try
							{
								session.close();
								throw new AssertionError("Session detached while scripts remained");
							}
							catch (IOException expected)
							{
								require(expected.getMessage().contains("still owns scripts"), "Unexpected session close failure");
							}
							first.close();
							first.close();
							String token = UUID.randomUUID() + "\0\"\\\n☃";
							second.post(JsonMapper.builder().build().createObjectNode().put("type", "ping").put("token", token));
							expect(second, token);
							System.out.println("script_survives_peer_close=true");
						}
						try (var binary = session.newScript("send('binary', new Uint8Array([1,2]).buffer);"))
						{
							binary.start();
							long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
							while (true)
							{
								try { binary.pollMessage(); }
								catch (IOException expected)
								{
									require(expected.getCause() instanceof IllegalStateException, "Callback failure lost its cause");
									System.out.println("callback_failure_retained=true");
									break;
								}
								require(System.nanoTime() < limit, "Binary callback failure was not reported");
								Thread.sleep(10);
							}
						}
					}
				}
			}
			System.out.println("native_teardown_with_target_alive=" + target.isAlive());
			require(target.isAlive(), "Instrumentation cleanup terminated its borrowed target");
			supervisor.getOutputStream().write(1);
			supervisor.getOutputStream().close();
			require(supervisor.waitFor(10, TimeUnit.SECONDS), "Owner did not finish after target release");
			require(supervisor.exitValue() == 0, "Supervisor did not exit zero");
			require(Files.readString(scratch.resolve("target-result")).strip().equals("0"), "Target did not exit zero");
			require(Files.exists(Path.of("/proc", Long.toString(target.pid()))) == false, "Target kernel PID remains after owner wait");
			System.out.println("owned_target_reaped=0; script_ownership=passed; CP3=incomplete");
		}
		finally
		{
			Files.writeString(scratch.resolve("stop-target"), "stop\n");
			supervisor.getOutputStream().close();
			if (supervisor.waitFor(10, TimeUnit.SECONDS) == false)
			{
				if (target != null && target.isAlive())
					target.destroyForcibly();
				if (supervisor.waitFor(10, TimeUnit.SECONDS) == false)
				{
					supervisor.destroyForcibly();
					require(supervisor.waitFor(10, TimeUnit.SECONDS), "Supervisor cleanup did not finish");
					throw new IOException("Independent target cleanup was not verified");
				}
			}
		}
	}

	/**
	 * Keeps the target's real parent separate from its native tracer and reaps its actual exit.
	 * A stop marker requests forced termination after an acceptance failure.
	 * @param java absolute Java executable for the disposable target
	 * @param classPath complete class path containing this probe and its dependencies
	 * @param scratch caller-owned case directory receiving readiness, logs and the actual target result
	 * @throws Exception if target creation, readiness, result publication or cleanup fails
	 */
	private static void supervise(Path java, String classPath, Path scratch) throws Exception
	{
		Path ready = scratch.resolve("target-ready");
		var builder = new ProcessBuilder(java.toString(), "-Djava.io.tmpdir=" + scratch, "-cp", classPath,
			FridaScriptProbe.class.getName(), "--target", ready.toString());
		builder.environment().clear();
		builder.environment().put("TMPDIR", scratch.toString());
		builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
		builder.redirectOutput(scratch.resolve("target.out").toFile());
		builder.redirectError(scratch.resolve("target.err").toFile());
		Process target = builder.start();
		try
		{
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while (Files.exists(ready) == false)
			{
				require(target.isAlive(), "Target exited before readiness");
				require(System.nanoTime() < deadline, "Target did not publish readiness");
				Thread.sleep(10);
			}
			Path publication = scratch.resolve("supervisor-ready.tmp");
			Files.writeString(publication, target.pid() + "\n");
			Files.move(publication, scratch.resolve("supervisor-ready"), StandardCopyOption.ATOMIC_MOVE);
			while (target.waitFor(100, TimeUnit.MILLISECONDS) == false)
			{
				if (Files.exists(scratch.resolve("stop-target")))
					target.destroyForcibly();
			}
			Files.writeString(scratch.resolve("target-result"), target.exitValue() + "\n");
		}
		finally
		{
			if (target.isAlive())
			{
				target.destroyForcibly();
				require(target.waitFor(10, TimeUnit.SECONDS), "Supervisor could not reap its owned target");
			}
		}
	}

	/**
	 * Waits at most ten seconds for a genuine script message and checks its decoded type and payload.
	 * @param script loaded script receiving the message
	 * @param payload expected text, including characters which JSON must escape
	 * @throws Exception if copying, parsing or equality fails, or the message does not arrive
	 */
	private static void expect(FridaRuntime.Manager.Session.Script script, String payload) throws Exception
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (true)
		{
			var message = script.pollMessage();
			if (message.isPresent())
			{
				var value = JsonMapper.builder().build().readTree(message.get());
				require(value.path("type").asString().equals("send") && value.path("payload").asString().equals(payload),
					"Unexpected native message: " + message.get());
				System.out.println("matched_message=" + message.get());
				return;
			}
			require(System.nanoTime() < deadline, "Expected script message was not delivered");
			Thread.sleep(10);
		}
	}

	/**
	 * Raises a failed acceptance result when its required observation is absent.
	 * @param condition indicates whether the required observation holds
	 * @param message diagnostic naming the missing observation
	 * @throws IOException if the condition is false
	 */
	private static void require(boolean condition, String message) throws IOException
	{
		if (condition == false)
			throw new IOException(message);
	}
}
