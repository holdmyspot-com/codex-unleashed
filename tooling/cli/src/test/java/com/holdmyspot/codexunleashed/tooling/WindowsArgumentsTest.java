package com.holdmyspot.codexunleashed.tooling;

import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

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
		requireThat(WindowsArguments.quote(""), "quoted").isEqualTo("\"\"");
		requireThat(WindowsArguments.quote("plain"), "quoted").isEqualTo("\"plain\"");
		requireThat(WindowsArguments.quote("two words"), "quoted").isEqualTo("\"two words\"");
		requireThat(WindowsArguments.quote("\"quoted\""), "quoted").isEqualTo("\"\\\"quoted\\\"\"");
		requireThat(WindowsArguments.quote("a\\"), "quoted").isEqualTo("\"a\\\\\"");
		requireThat(WindowsArguments.quote("a\\\"b"), "quoted").isEqualTo("\"a\\\\\\\"b\"");
		requireThat(WindowsArguments.quote("a\\\\b"), "quoted").isEqualTo("\"a\\\\b\"");
	}
}
