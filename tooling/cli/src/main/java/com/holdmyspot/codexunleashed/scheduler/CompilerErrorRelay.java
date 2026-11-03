package com.holdmyspot.codexunleashed.scheduler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Forwards one compiler invocation's standard-error output to Cargo across the scheduler's attempts.
 * <p>
 * Cargo reads the compiler's standard error line by line and accepts each artifact notification once; a repeated
 * notification makes it panic. A retried compiler repeats the notifications of the attempt it replaces, so the relay
 * forwards only complete lines, drops an artifact notification identical to one it already forwarded, and discards the
 * incomplete last line of an attempt that the scheduler retries. Other repeated diagnostics are forwarded again.
 * <p>
 * Instances are confined to one thread at a time; the caller orders an attempt's output before its end.
 */
public final class CompilerErrorRelay
{
	/** Key that names the output file in rustc's JSON artifact notification. */
	private static final String ARTIFACT = "\"artifact\":";
	/** Key that every rustc JSON diagnostic carries and an artifact notification lacks. */
	private static final String MESSAGE = "\"message\":";

	/** Cargo's standard-error stream that receives the forwarded output. */
	private final OutputStream cargo;
	/** Output of the current attempt after its last newline, held until the line completes or the attempt ends. */
	private final ByteArrayOutputStream line = new ByteArrayOutputStream();
	/** Artifact notification lines already forwarded by any attempt; a repeat of one is dropped. */
	private final Set<String> artifacts = new HashSet<>();

	/**
	 * Creates a relay that has forwarded nothing.
	 * @param cargo Cargo's standard-error stream, borrowed for the relay's lifetime
	 * @throws NullPointerException if {@code cargo} is null
	 */
	public CompilerErrorRelay(OutputStream cargo)
	{
		this.cargo = Objects.requireNonNull(cargo, "cargo");
	}

	/**
	 * Accepts output that the current attempt wrote.
	 * @param bytes buffer holding the output
	 * @param offset index of the first output byte
	 * @param length number of output bytes
	 * @throws IOException if writing to Cargo fails
	 */
	public void accept(byte[] bytes, int offset, int length) throws IOException
	{
		Objects.checkFromIndexSize(offset, length, bytes.length);
		int start = offset;
		int end = offset + length;
		for (int index = offset; index < end; ++index)
			if (bytes[index] == '\n')
			{
				line.write(bytes, start, index + 1 - start);
				forwardLine();
				start = index + 1;
			}
		line.write(bytes, start, end - start);
		cargo.flush();
	}

	/**
	 * Ends the current attempt's output after the attempt can no longer write. The attempt's unterminated last line is
	 * discarded if the attempt is superseded and forwarded otherwise.
	 * @param superseded {@code true} if the scheduler will retry the compiler in another attempt
	 * @throws IOException if writing to Cargo fails
	 */
	public void endAttempt(boolean superseded) throws IOException
	{
		if (superseded)
			line.reset();
		else if (line.size() > 0)
			forwardLine();
		cargo.flush();
	}

	/**
	 * Forwards the held line unless it repeats a forwarded artifact notification, then clears it.
	 * @throws IOException if writing to Cargo fails
	 */
	private void forwardLine() throws IOException
	{
		byte[] bytes = line.toByteArray();
		line.reset();
		String text = new String(bytes, StandardCharsets.UTF_8);
		if (isArtifact(text) && !artifacts.add(text))
			return;
		cargo.write(bytes);
	}

	/**
	 * Recognizes rustc's artifact notification, which is a one-line JSON object that names an output file and, unlike a
	 * diagnostic, carries no message. Older rustc releases begin it with the artifact key and newer ones with
	 * {@code $message_type}, so the key order is not relied on.
	 * @param line one complete standard-error line, including its newline if any
	 * @return {@code true} if the line is an artifact notification
	 */
	static boolean isArtifact(String line)
	{
		String trimmed = line.strip();
		return trimmed.startsWith("{") && trimmed.endsWith("}") && trimmed.contains(ARTIFACT) && !trimmed.contains(MESSAGE);
	}
}
