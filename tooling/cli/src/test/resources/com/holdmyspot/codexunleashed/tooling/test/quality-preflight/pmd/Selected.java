package sample;

/**
 * Supplies a small independent Java source for quality preflight acceptance.
 */
public final class Selected
{
	/**
	 * Prevents construction.
	 */
	private Selected()
	{
	}

	/**
	 * Returns the fixture's answer.
	 *
	 * @return the fixture's answer
	 */
	public static int answer()
	{
		int unreferenced = 1;
		return 42;
	}
}
