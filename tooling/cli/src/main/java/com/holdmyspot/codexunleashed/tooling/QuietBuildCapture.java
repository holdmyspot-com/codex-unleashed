package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Invocation context and ownership contracts for capturing a child process.
 */
@SuppressWarnings("PMD.MissingStaticMethodInNonInstantiatableClass")
public final class QuietBuildCapture
{
	/**
	 * Creates the declaration container.
	 */
	private QuietBuildCapture()
	{
	}

	/**
	 * Paths and explicitly supplied environment for one child invocation.
	 * <p>
	 * Child launchers replace inherited environment variables with this map and preserve its supplied values.
	 * On Windows, the JDK or operating system may additionally provide {@code SystemRoot} and
	 * {@code PROCESSOR_ARCHITECTURE} when omitted from the map. Other parent variables are not inherited.
	 *
	 * @param workingDirectory   child working directory
	 * @param temporaryDirectory child temporary directory
	 * @param environment        explicit child variables and their values
	 */
	public record Context(Path workingDirectory, Path temporaryDirectory, Map<String, String> environment)
	{
		/**
		 * Creates an immutable snapshot of the supplied child variables.
		 *
		 * @param workingDirectory   child working directory
		 * @param temporaryDirectory child temporary directory
		 * @param environment        explicit child variables and their values
		 */
		public Context
		{
			requireThat(workingDirectory, "workingDirectory").isNotNull();
			requireThat(temporaryDirectory, "temporaryDirectory").isNotNull();
			environment = Map.copyOf(environment);
		}
	}

	/** Captured process result whose streams and lifetime belong to one invocation. */
	public interface Captured extends AutoCloseable
	{
		/**
		 * Returns the child exit status.
		 *
		 * @return process exit status
		 */
		int status();

		/**
		 * Returns the child's standard output reader.
		 *
		 * @return standard output reader
		 * @throws IOException if the stream cannot be read
		 */
		java.io.Reader stdout() throws IOException;

		/**
		 * Returns the child's standard error reader.
		 *
		 * @return standard error reader
		 * @throws IOException if the stream cannot be read
		 */
		java.io.Reader stderr() throws IOException;

		/** Releases resources owned by this invocation's capture. */
		@Override
		void close() throws IOException;
	}

	/** Acquires an invocation-owned child-process capture. */
	@FunctionalInterface
	public interface Access
	{
		/**
		 * Captures the supplied command using the paths and environment policy of {@link Context}.
		 *
		 * @param command child command and arguments
		 * @param context child invocation paths and explicitly supplied variables
		 * @param timeout optional invocation timeout
		 * @return captured process result owned by the caller
		 * @throws IOException if capture acquisition fails
		 */
		Captured capture(List<String> command, Context context, Optional<Duration> timeout) throws IOException;
	}
}
