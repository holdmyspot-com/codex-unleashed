package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.DecimalNumberText;
import java.io.IOException;
import java.math.BigInteger;

/** Retains the archive numeric boundary through the shared project numeric grammar. */
final class ArchiveNumberText
{
	/** Prevents construction. */
	private ArchiveNumberText()
	{
	}

	/**
	 * Parses signed decimal integers with Unicode decimal digits and valid digit separators.
	 *
	 * @param text the supplied integer spelling
	 * @return the exact integer value
	 * @throws IOException if the spelling is invalid
	 */
	static BigInteger parseInteger(String text) throws IOException
	{
		return DecimalNumberText.parseInteger(text);
	}

	/**
	 * Parses retained decimal floating-point spellings.
	 *
	 * @param text the supplied floating-point spelling
	 * @return the binary floating-point value
	 * @throws IOException if the spelling is invalid
	 */
	static double parseDouble(String text) throws IOException
	{
		return DecimalNumberText.parseDouble(text);
	}

	/**
	 * Formats the closest shortest decimal that round-trips to the supplied binary value.
	 *
	 * @param value the binary floating-point value
	 * @return the retained fixed or scientific timestamp text
	 */
	static String formatDouble(double value)
	{
		return DecimalNumberText.formatDouble(value);
	}
}
