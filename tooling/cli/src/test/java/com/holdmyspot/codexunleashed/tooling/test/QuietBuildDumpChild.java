package com.holdmyspot.codexunleashed.tooling.test;

/**
 * Emits one line on each child output stream and exits with the requested status.
 */
public final class QuietBuildDumpChild
{
	/**
	 * Prevents direct construction of the test child.
	 */
	private QuietBuildDumpChild()
	{
	}

	/**
	 * Writes both child streams and exits with the supplied status.
	 *
	 * @param arguments exactly one decimal exit status
	 */
	public static void main(String[] arguments)
	{
		System.out.println("stdout");
		System.err.println("stderr");
		System.exit(Integer.parseInt(arguments[0]));
	}
}
