package com.holdmyspot.codexunleashed.scheduler;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Checks exception outcomes with Requirements while TestNG supplies only test discovery and execution. */
final class TestRequirements
{
	/** Prevents construction of this resource-free group of test operations. */
	private TestRequirements()
	{
	}

	/**
	 * Executes an operation once and requires an exception assignable to the expected type.
	 *
	 * @param <T> expected exception type
	 * @param expected expected nonnull exception class
	 * @param operation nonnull operation borrowed for this call
	 * @return the original exception, preserving its identity and suppressed exceptions
	 * @throws NullPointerException if an argument is null or the operation throws nothing
	 * @throws IllegalArgumentException if the thrown exception has another type
	 */
	static <T extends Throwable> T requireException(Class<T> expected, Operation operation)
	{
		requireThat(expected, "expected").isNotNull();
		requireThat(operation, "operation").isNotNull();
		Throwable failure = null;
		try
		{
			operation.run();
		}
		catch (Throwable thrown)
		{
			failure = thrown;
		}
		requireThat(failure, "exception").isNotNull().isInstanceOf(expected);
		return expected.cast(failure);
	}

	/** Executes a test operation that may return normally or throw any exception or error. */
	@FunctionalInterface
	interface Operation
	{
		/**
		 * Executes the test operation once.
		 *
		 * @throws Throwable if the operation fails
		 */
		void run() throws Throwable;
	}
}
