package com.holdmyspot.codexunleashed.tooling;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns a scheduler host process through readiness, parent-input shutdown, and diagnostic transfer.
 */
final class CompilerSchedulerProcess implements AutoCloseable
{
	/** The scheduler host, including its parent-lifetime input pipe. */
	private final Process process;
	/** Transfers host diagnostics to the caller-owned destination. */
	private final Thread diagnostics;
	/** Retains a diagnostic-transfer failure until its owner can report it. */
	private final AtomicReference<IOException> diagnosticFailure = new AtomicReference<>();
	/** Indicates that this owner's shutdown has run. */
	private boolean closed;

	/**
	 * Starts a host in its private IPC directory and waits for the socket to be ready.
	 *
	 * @param directory the existing private IPC directory
	 * @param err the caller-owned diagnostic destination
	 * @throws NullPointerException if an argument is null
	 * @throws IOException if startup, readiness, or construction cleanup fails
	 */
	CompilerSchedulerProcess(Path directory, PrintStream err) throws IOException
	{
		Objects.requireNonNull(directory, "directory");
		Objects.requireNonNull(err, "err");
		process = SystemCommands.createBuilder(CompilerSchedulerHost.command()).directory(directory.toFile()).start();
		Thread pump = null;
		try
		{
			pump = Thread.ofPlatform().name("compiler-scheduler-diagnostics").daemon().start(() -> transfer(err));
			diagnostics = pump;
			try (BufferedReader ready = new BufferedReader(new InputStreamReader(process.getInputStream(),
				StandardCharsets.UTF_8)))
			{
				String message = ready.readLine();
				if (!"ready".equals(message))
					throw new IOException("Compiler scheduler host did not become ready: " + message);
			}
			checkAlive();
		}
		catch (IOException | RuntimeException | Error failure)
		{
			try
			{
				stop(process, pump);
			}
			catch (IOException cleanup)
			{
				failure.addSuppressed(cleanup);
			}
			throw failure;
		}
	}

	/**
	 * Checks that the host and its diagnostic reader remain available.
	 *
	 * @throws IOException if the host exits or diagnostic transfer fails
	 */
	void checkAlive() throws IOException
	{
		IOException failure = diagnosticFailure.get();
		if (failure != null)
			throw new IOException("Cannot read compiler scheduler diagnostics", failure);
		if (!process.isAlive())
			throw new IOException("Compiler scheduler host exited prematurely with status " + process.exitValue());
	}

	/**
	 * Transfers host diagnostics without retaining compiler output in memory.
	 *
	 * @param err the caller-owned destination
	 */
	private void transfer(PrintStream err)
	{
		try
		{
			process.getErrorStream().transferTo(err);
		}
		catch (IOException failure)
		{
			diagnosticFailure.set(failure);
		}
	}

	/**
	 * Closes the parent-lifetime input pipe and retains ownership until the host and diagnostic reader exit.
	 *
	 * @param process the owned host process
	 * @param diagnostics its diagnostic reader, or null if reader startup failed
	 * @throws IOException if shutdown fails, the host fails, or the caller is interrupted
	 */
	private static void stop(Process process, Thread diagnostics) throws IOException
	{
		IOException failure = null;
		boolean interrupted = false;
		try
		{
			process.getOutputStream().close();
		}
		catch (IOException exception)
		{
			failure = exception;
			process.destroyForcibly();
		}
		try
		{
			boolean exited = false;
			while (!exited)
			{
				try
				{
					process.waitFor();
					if (diagnostics != null)
						diagnostics.join();
					exited = true;
				}
				catch (InterruptedException exception)
				{
					interrupted = true;
					IOException interruption = new IOException("Interrupted while stopping the compiler scheduler host",
						exception);
					if (failure == null)
						failure = interruption;
					else
						failure.addSuppressed(interruption);
				}
			}
			process.close();
			if (process.exitValue() != 0)
			{
				IOException exit = new IOException("Compiler scheduler host exited with status " + process.exitValue());
				if (failure == null)
					failure = exit;
				else
					failure.addSuppressed(exit);
			}
		}
		finally
		{
			if (interrupted)
				Thread.currentThread().interrupt();
		}
		if (failure != null)
			throw failure;
	}

	/**
	 * Stops the owned host, verifies its terminal outcome, and leaves the caller's diagnostic stream open.
	 *
	 * @throws IOException if shutdown, the host, or diagnostic transfer fails
	 */
	@Override
	public void close() throws IOException
	{
		if (closed)
			return;
		closed = true;
		try
		{
			stop(process, diagnostics);
		}
		catch (IOException failure)
		{
			IOException transfer = diagnosticFailure.get();
			if (transfer != null)
				failure.addSuppressed(transfer);
			throw failure;
		}
		IOException failure = diagnosticFailure.get();
		if (failure != null)
			throw new IOException("Cannot read compiler scheduler diagnostics", failure);
	}
}
