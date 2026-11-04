package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxBuildHookFrames;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies the build hook's field payloads against the layout that linux-build-hook.c sends and accepts.
 */
public final class LinuxBuildHookFramesTest
{
	/**
	 * Creates the tests.
	 */
	public LinuxBuildHookFramesTest()
	{
	}

	/**
	 * Fields are written as UTF-8, each followed by NUL.
	 */
	@Test
	public void encodesNulTerminatedFields()
	{
		requireThat(new String(LinuxBuildHookFrames.encode("welcome", "7"), StandardCharsets.UTF_8), "payload").
			isEqualTo("welcome\0" + "7\0");
	}

	/**
	 * A payload decodes to its fields, including an empty one.
	 * @throws IOException if decoding fails
	 */
	@Test
	public void decodesFields() throws IOException
	{
		requireThat(LinuxBuildHookFrames.decode(("hello\0" + "1\0\0").getBytes(StandardCharsets.UTF_8)), "fields").
			isEqualTo(List.of("hello", "1", ""));
	}

	/**
	 * A payload whose last field lacks its NUL is rejected.
	 * @throws IOException always
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*NUL.*")
	public void rejectsUnterminatedPayload() throws IOException
	{
		LinuxBuildHookFrames.decode("hello".getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * A payload with more fields than the library reads is rejected.
	 * @throws IOException always
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*fields.*")
	public void rejectsTooManyFields() throws IOException
	{
		LinuxBuildHookFrames.decode("a\0b\0c\0d\0e\0f\0g\0h\0i\0".getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * A payload longer than the library's buffer cannot be sent.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*512.*")
	public void rejectsOversizedPayload()
	{
		LinuxBuildHookFrames.encode("x".repeat(LinuxBuildHookFrames.MAX_PAYLOAD_BYTES));
	}

	/**
	 * A field that contains NUL cannot be sent, because it would split into two fields.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*NUL.*")
	public void rejectsFieldContainingNul()
	{
		LinuxBuildHookFrames.encode("a\0b");
	}
}
