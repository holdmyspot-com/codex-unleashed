package com.holdmyspot.codexunleashed.scheduler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies the compiler standard-error output that Cargo receives when the scheduler retries a compiler.
 */
public final class CompilerErrorRelayTest
{
	/** Artifact notification that rustc writes when it finishes the crate metadata, as cargo's JSON protocol defines. */
	private static final String METADATA =
		"{\"artifact\":\"/work/target/debug/deps/libhash32-0d3b.rmeta\",\"emit\":\"metadata\"}\n";

	/**
	 * Creates relay tests.
	 */
	public CompilerErrorRelayTest()
	{
	}

	/**
	 * Forwards a typed artifact notification once, because current rustc releases begin each JSON message with its
	 * {@code $message_type}.
	 * @throws IOException if the in-memory stream fails
	 */
	@Test
	public void forwardsRepeatedTypedArtifactNotificationOnce() throws IOException
	{
		String typed = "{\"$message_type\":\"artifact\",\"artifact\":\"/work/target/release/deps/libhash32-0d3b.rmeta\"," +
			"\"emit\":\"metadata\"}\n";
		String diagnostic = "{\"$message_type\":\"diagnostic\",\"message\":\"unused \\\"artifact\\\":\",\"level\":\"warning\"}\n";
		var cargo = new ByteArrayOutputStream();
		var relay = new CompilerErrorRelay(cargo);
		accept(relay, diagnostic + typed);
		relay.endAttempt(true);
		accept(relay, diagnostic + typed);
		relay.endAttempt(false);
		requireThat(cargo.toString(StandardCharsets.UTF_8), "cargoStandardError").isEqualTo(diagnostic + typed + diagnostic);
	}

	/**
	 * Forwards an artifact notification once when a retried compiler repeats it, because Cargo panics on a repeat.
	 * @throws IOException if the in-memory stream fails
	 */
	@Test
	public void forwardsRepeatedArtifactNotificationOnce() throws IOException
	{
		var cargo = new ByteArrayOutputStream();
		var relay = new CompilerErrorRelay(cargo);

		accept(relay, METADATA);
		relay.endAttempt(true);
		accept(relay, METADATA + "{\"artifact\":\"/work/target/debug/deps/libhash32-0d3b.rlib\",\"emit\":\"link\"}\n");
		relay.endAttempt(false);

		requireThat(cargo.toString(StandardCharsets.UTF_8), "cargo").isEqualTo(METADATA +
			"{\"artifact\":\"/work/target/debug/deps/libhash32-0d3b.rlib\",\"emit\":\"link\"}\n");
	}

	/**
	 * Drops the incomplete last line of a superseded attempt, so a retry's output never joins it.
	 * @throws IOException if the in-memory stream fails
	 */
	@Test
	public void dropsIncompleteLineOfSupersededAttempt() throws IOException
	{
		var cargo = new ByteArrayOutputStream();
		var relay = new CompilerErrorRelay(cargo);

		accept(relay, "warning: unused\n{\"artifact\":\"/work/target/de");
		relay.endAttempt(true);
		accept(relay, METADATA);
		relay.endAttempt(false);

		requireThat(cargo.toString(StandardCharsets.UTF_8), "cargo").isEqualTo("warning: unused\n" + METADATA);
	}

	/**
	 * Joins a line that arrives in several reads and forwards the final attempt's unterminated last line.
	 * @throws IOException if the in-memory stream fails
	 */
	@Test
	public void forwardsSplitLinesAndFinalUnterminatedLine() throws IOException
	{
		var cargo = new ByteArrayOutputStream();
		var relay = new CompilerErrorRelay(cargo);

		accept(relay, METADATA.substring(0, 10));
		accept(relay, METADATA.substring(10) + "error: abort");
		relay.endAttempt(false);

		requireThat(cargo.toString(StandardCharsets.UTF_8), "cargo").isEqualTo(METADATA + "error: abort");
	}

	/**
	 * Delivers text to the relay as one read from the compiler's standard-error pipe.
	 * @param relay the relay under test
	 * @param text output that the compiler wrote
	 * @throws IOException if the in-memory stream fails
	 */
	private static void accept(CompilerErrorRelay relay, String text) throws IOException
	{
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		relay.accept(bytes, 0, bytes.length);
	}
}
