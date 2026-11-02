package com.holdmyspot.codexunleashed.scheduler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
final class CompilerErrorRelay
{
	/** Prefix of the JSON line that rustc writes when it finishes an output file, as Cargo's message format defines. */
	private static final byte[] ARTIFACT = "{\"artifact\":".getBytes(StandardCharsets.UTF_8);

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
	CompilerErrorRelay(OutputStream cargo)
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
	void accept(byte[] bytes, int offset, int length) throws IOException
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
	void endAttempt(boolean superseded) throws IOException
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
		boolean artifact = bytes.length >= ARTIFACT.length &&
			Arrays.equals(bytes, 0, ARTIFACT.length, ARTIFACT, 0, ARTIFACT.length);
		if (artifact && !artifacts.add(new String(bytes, StandardCharsets.UTF_8)))
			return;
		cargo.write(bytes);
	}
}
