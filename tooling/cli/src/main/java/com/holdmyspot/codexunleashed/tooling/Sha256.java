package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Streams raw files through the Java platform's SHA-256 implementation.
 */
public final class Sha256
{
	/**
	 * Prevents construction.
	 */
	private Sha256()
	{
	}

	/**
	 * Hashes a file without decoding its content or loading the entire file into memory.
	 *
	 * @param file the raw input file
	 * @return the lowercase hexadecimal digest
	 * @throws NullPointerException if {@code file} is null
	 * @throws IOException if the file cannot be read
	 * @throws IllegalStateException if the Java platform does not supply SHA-256
	 */
	public static String digest(Path file) throws IOException
	{
		Objects.requireNonNull(file, "file");
		MessageDigest digest = newDigest();
		try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest))
		{
			input.transferTo(OutputStream.nullOutputStream());
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * Hashes bytes already in memory.
	 *
	 * @param bytes the raw input
	 * @return the lowercase hexadecimal digest
	 */
	public static String digest(byte[] bytes)
	{
		return HexFormat.of().formatHex(newDigest().digest(bytes));
	}

	/**
	 * Creates a digest using the Java platform's SHA-256 implementation.
	 *
	 * @return the new digest
	 */
	private static MessageDigest newDigest()
	{
		try
		{
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException failure)
		{
			throw new IllegalStateException("Java platform does not supply SHA-256", failure);
		}
	}
}
