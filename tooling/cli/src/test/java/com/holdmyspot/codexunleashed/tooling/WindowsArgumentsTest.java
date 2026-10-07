package com.holdmyspot.codexunleashed.tooling;

import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

/** Checks Windows argument encoding against the documented Microsoft C runtime parsing rules. */
public final class WindowsArgumentsTest
{
	/** Creates Windows argument tests. */
	public WindowsArgumentsTest()
	{
	}

	/** Preserves literal quotes, empty values, and backslashes immediately before quotes or the closing delimiter. */
	@Test
	public void encodesLiteralArguments()
	{
		assertEquals(WindowsArguments.quote(""), "\"\"");
		assertEquals(WindowsArguments.quote("plain"), "\"plain\"");
		assertEquals(WindowsArguments.quote("two words"), "\"two words\"");
		assertEquals(WindowsArguments.quote("\"quoted\""), "\"\\\"quoted\\\"\"");
		assertEquals(WindowsArguments.quote("a\\"), "\"a\\\\\"");
		assertEquals(WindowsArguments.quote("a\\\"b"), "\"a\\\\\\\"b\"");
		assertEquals(WindowsArguments.quote("a\\\\b"), "\"a\\\\b\"");
	}
}
