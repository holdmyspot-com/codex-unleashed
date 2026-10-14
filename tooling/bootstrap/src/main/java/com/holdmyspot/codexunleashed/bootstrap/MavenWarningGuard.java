package com.holdmyspot.codexunleashed.bootstrap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs Maven before the tooling runtime exists and rejects successful warning diagnostics.
 */
public final class MavenWarningGuard
{
	/**
	 * Prevents construction of this bootstrap entry point.
	 */
	private MavenWarningGuard()
	{
	}

	/**
	 * Forwards the child command's streams and preserves its failure status.
	 *
	 * @param arguments the command and its arguments
	 * @throws IOException if the command cannot start
	 * @throws InterruptedException if waiting for the command is interrupted
	 * @throws ExecutionException if reading a command stream fails
	 */
	public static void main(String[] arguments) throws IOException, InterruptedException, ExecutionException
	{
		ProcessBuilder builder = new ProcessBuilder(arguments);
		builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
		int status;
		try (Process process = builder.start();
			ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor())
		{
			Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() ->
			{
				process.descendants().forEach(ProcessHandle::destroyForcibly);
				process.destroyForcibly();
			}));
			Future<Boolean> stdout = executor.submit(() -> forward(process.getInputStream(), System.out));
			Future<Boolean> stderr = executor.submit(() -> forward(process.getErrorStream(), System.err));
			try
			{
				status = process.waitFor();
				boolean stdoutDiagnostic = stdout.get();
				boolean stderrDiagnostic = stderr.get();
				if (status == 0 && (stdoutDiagnostic || stderrDiagnostic))
				{
					System.err.println("Maven emitted a warning or error diagnostic; failing the build.");
					status = 1;
				}
			}
			finally
			{
				if (process.isAlive())
					process.destroyForcibly().waitFor();
			}
		}
		System.exit(status);
	}

	/**
	 * Forwards a text stream while identifying structured build diagnostics.
	 *
	 * @param input the child stream
	 * @param output the corresponding parent stream
	 * @return whether the stream contains a warning or error diagnostic
	 * @throws IOException if reading the stream fails
	 */
	private static boolean forward(InputStream input, PrintStream output) throws IOException
	{
		boolean diagnostic = false;
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)))
		{
			for (String line = reader.readLine(); line != null; line = reader.readLine())
			{
				output.println(line);
				diagnostic |= line.startsWith("[WARNING]") || line.startsWith("[ERROR]") ||
					line.startsWith("SLF4J(W):") || line.startsWith("SLF4J(E):") || line.startsWith("WARNING:");
			}
		}
		return diagnostic;
	}
}
