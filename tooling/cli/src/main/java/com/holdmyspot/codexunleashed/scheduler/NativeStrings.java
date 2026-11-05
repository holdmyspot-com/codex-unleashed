package com.holdmyspot.codexunleashed.scheduler;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Copies NUL-terminated strings that native code owns, reading no byte past the terminator.
 * <p>
 * {@link MemorySegment#getString(long, Charset)} looks for the terminator several bytes at a time, so on a segment
 * stretched beyond the string it can read past the terminator into an unmapped page and crash the JVM.
 */
public final class NativeStrings
{
    /** Prevents instantiation. */
    private NativeStrings()
    {
    }

    /**
     * Copies a borrowed NUL-terminated UTF-8 string.
     * @param address address of the string's first byte
     * @param maxBytes most bytes the string and its terminator can occupy
     * @return the copied text
     * @throws IllegalArgumentException if no terminator lies within {@code maxBytes}
     */
    public static String utf8(MemorySegment address, long maxBytes)
    {
        return copy(address, maxBytes, 1, StandardCharsets.UTF_8);
    }

    /**
     * Copies a borrowed NUL-terminated UTF-16LE string.
     * @param address address of the string's first code unit
     * @param maxBytes most bytes the string and its terminator can occupy
     * @return the copied text
     * @throws IllegalArgumentException if no terminator lies within {@code maxBytes}
     */
    public static String utf16(MemorySegment address, long maxBytes)
    {
        return copy(address, maxBytes, 2, StandardCharsets.UTF_16LE);
    }

    /**
     * Finds the terminator one code unit at a time, then decodes exactly the bytes before it.
     * @param address address of the string's first code unit
     * @param maxBytes most bytes the string and its terminator can occupy
     * @param unitBytes bytes in one code unit: 1 or 2
     * @param charset the string's encoding
     * @return the copied text
     * @throws IllegalArgumentException if no terminator lies within {@code maxBytes}
     */
    private static String copy(MemorySegment address, long maxBytes, int unitBytes, Charset charset)
    {
        MemorySegment text = address.reinterpret(maxBytes);
        long limit = maxBytes - unitBytes;
        for (long offset = 0; offset <= limit; offset += unitBytes)
        {
            boolean terminator;
            if (unitBytes == 1)
                terminator = text.get(ValueLayout.JAVA_BYTE, offset) == 0;
            else
                terminator = text.get(ValueLayout.JAVA_SHORT_UNALIGNED, offset) == 0;
            if (terminator)
                return new String(text.asSlice(0, offset).toArray(ValueLayout.JAVA_BYTE), charset);
        }
        throw new IllegalArgumentException("Native string has no terminator within " + maxBytes + " bytes");
    }
}
