package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.WindowsExecutables;

import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link WindowsExecutables}.
 */
public final class WindowsExecutablesTest
{
    /**
     * Distinguishes a 32-bit PE32 image from a 64-bit PE32+ image by its optional-header magic.
     * @throws IOException if a fixture cannot be written or read
     */
    @Test
    public void readsOptionalHeaderLayout() throws IOException
    {
        requireThat(is32Bit(image(0x014c, 0x010b)), "pe32").isTrue();
        requireThat(is32Bit(image(0x8664, 0x020b)), "pe32Plus").isFalse();
    }

    /**
     * Rejects a file that is not a PE image instead of classifying it.
     * @throws IOException if a fixture cannot be written
     */
    @Test
    public void rejectsNonPeFile() throws IOException
    {
        byte[] bytes = image(0x8664, 0x020b);
        bytes[0] = 'Z';
        requireException(IOException.class, () -> is32Bit(bytes));
    }

    /**
     * Builds the smallest header prefix that names a PE machine and optional-header magic.
     * @param machine COFF machine identifier
     * @param magic   optional-header magic
     * @return DOS header, PE signature, COFF header and optional-header magic
     */
    private static byte[] image(int machine, int magic)
    {
        ByteBuffer buffer = ByteBuffer.allocate(64 + 24 + 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(0, (byte) 'M').put(1, (byte) 'Z').putInt(60, 64);
        buffer.putInt(64, 0x00004550).putShort(68, (short) machine).putShort(84, (short) 2);
        buffer.putShort(88, (short) magic);
        return buffer.array();
    }

    /**
     * Classifies header bytes through a temporary file that this call removes.
     * @param bytes executable header bytes
     * @return the classification of the written file
     * @throws IOException if the file cannot be written, read or classified
     */
    private static boolean is32Bit(byte[] bytes) throws IOException
    {
        Path file = Files.createTempFile("windows-executable-", ".exe");
        try
        {
            Files.write(file, bytes);
            return WindowsExecutables.is32Bit(file);
        }
        finally
        {
            Files.delete(file);
        }
    }
}
