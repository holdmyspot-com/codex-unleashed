package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Preserves the decimal numeric grammar and shortest round-trip text used by project tooling inputs.
 */
public final class DecimalNumberText
{
	private static final String DIGITS = "\\p{Nd}(?:_?\\p{Nd})*";
	private static final Pattern INTEGER = Pattern.compile("\\A[+-]?" + DIGITS + "\\z");
	private static final Pattern FLOAT = Pattern.compile("\\A[+-]?(?:(?:" + DIGITS + "(?:\\.(?:" + DIGITS +
		")?)?|\\.(?:" + DIGITS + "))(?:[eE][+-]?" + DIGITS + ")?|(?i:inf(?:inity)?|nan))\\z");
	private static final int MAX_DOUBLE_DIGITS = 17;

	/**
	 * Prevents construction.
	 */
	private DecimalNumberText()
	{
	}

	/**
	 * Parses signed decimal integers with Unicode decimal digits and valid digit separators.
	 *
	 * @param text the supplied integer spelling
	 * @return the exact integer value
	 * @throws IOException if the spelling is invalid
	 * @throws NullPointerException if the text is null
	 */
	public static BigInteger parseInteger(String text) throws IOException
	{
		String stripped = trim(text);
		if (!INTEGER.matcher(stripped).matches())
			throw new IOException("Invalid decimal integer timestamp: " + text);
		return new BigInteger(normalize(stripped));
	}

	/**
	 * Parses retained decimal floating-point forms without accepting Java hexadecimal or type suffix forms.
	 *
	 * @param text the supplied floating-point spelling
	 * @return the binary floating-point value
	 * @throws IOException if the spelling is invalid
	 */
	public static double parseDouble(String text) throws IOException
	{
		String stripped = trim(text);
		if (!FLOAT.matcher(stripped).matches())
			throw new IOException("Invalid numeric member timestamp: " + text);
		String normalized = normalize(stripped).toLowerCase(Locale.ROOT);
		if (normalized.endsWith("nan"))
			return Double.NaN;
		if (normalized.endsWith("inf") || normalized.endsWith("infinity"))
		{
			if (normalized.startsWith("-"))
				return Double.NEGATIVE_INFINITY;
			return Double.POSITIVE_INFINITY;
		}
		return Double.parseDouble(normalized);
	}

	/**
	 * Formats the closest shortest decimal that round-trips to the supplied binary value.
	 *
	 * @param value the binary floating-point value
	 * @return retained fixed or scientific timestamp text
	 */
	public static String formatDouble(double value)
	{
		if (Double.isNaN(value))
			return "nan";
		if (value == Double.POSITIVE_INFINITY)
			return "inf";
		if (value == Double.NEGATIVE_INFINITY)
			return "-inf";
		if (value == 0)
			return Double.toString(value);
		BigDecimal decimal = shortestDecimal(value);
		int exponent = decimal.precision() - decimal.scale() - 1;
		if (exponent >= 16 || exponent < -4)
			return decimal.movePointLeft(exponent).toPlainString() + "e" + String.format(Locale.ROOT, "%+03d", exponent);
		String text = decimal.toPlainString();
		if (!text.contains("."))
			text += ".0";
		return text;
	}

	/**
	 * Uses exact binary-value decimal rounding to find the shortest round-trip representation.
	 *
	 * @param value a finite nonzero binary value
	 * @return the shortest decimal with ties rounded to even
	 */
	private static BigDecimal shortestDecimal(double value)
	{
		BigDecimal exact = new BigDecimal(value, MathContext.UNLIMITED);
		int exponent = exact.precision() - exact.scale() - 1;
		for (int digits = 1; digits <= MAX_DOUBLE_DIGITS; ++digits)
		{
			BigDecimal candidate = exact.round(new MathContext(digits, RoundingMode.HALF_EVEN));
			if (candidate.doubleValue() == value)
				return candidate.stripTrailingZeros();
			BigDecimal unit = BigDecimal.ONE.scaleByPowerOfTen(exponent - digits + 1);
			BigDecimal lower = candidate.subtract(unit);
			if (lower.doubleValue() == value)
				return lower.stripTrailingZeros();
			BigDecimal upper = candidate.add(unit);
			if (upper.doubleValue() == value)
				return upper.stripTrailingZeros();
		}
		throw new IllegalStateException("No round-trip decimal representation for finite double: " + value);
	}

	/**
	 * Removes digit separators and converts Unicode decimal digits to ASCII.
	 *
	 * @param text the validated numeric text
	 * @return the normalized numeric text
	 */
	private static String normalize(String text)
	{
		StringBuilder normalized = new StringBuilder();
		for (int codePoint : text.codePoints().toArray())
		{
			if (codePoint == '_')
				continue;
			int digit = Character.digit(codePoint, 10);
			if (digit >= 0)
				normalized.append(digit);
			else
				normalized.appendCodePoint(codePoint);
		}
		return normalized.toString();
	}

	/**
	 * Trims the whitespace accepted by numeric conversion without accepting ASCII separator controls.
	 *
	 * @param text the supplied numeric spelling
	 * @return the trimmed text
	 */
	private static String trim(String text)
	{
		int first = 0;
		int last = text.length();
		while (first < last && isNumericWhitespace(text.codePointAt(first)))
			first += Character.charCount(text.codePointAt(first));
		while (last > first && isNumericWhitespace(text.codePointBefore(last)))
			last -= Character.charCount(text.codePointBefore(last));
		return text.substring(first, last);
	}

	/**
	 * Classifies ASCII numeric whitespace and non-ASCII Unicode whitespace.
	 *
	 * @param codePoint the candidate character
	 * @return whether numeric conversion trims the character
	 */
	private static boolean isNumericWhitespace(int codePoint)
	{
		if (codePoint < 128)
			return codePoint == ' ' || codePoint >= '\t' && codePoint <= '\r';
		return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x85;
	}
}
