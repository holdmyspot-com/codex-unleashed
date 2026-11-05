package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Reads the layout of a Windows executable from its PE header.
 */
public final class WindowsExecutables
{
    /**
     * Prevents instantiation of this static reader.
     */
    private WindowsExecutables()
    {
    }

    /**
     * Returns whether an executable is a 32-bit (PE32) image rather than a 64-bit (PE32+) one.
     * @param executable executable file
     * @return {@code true} if the image's optional header is the 32-bit PE32 layout
     * @throws IOException if the file cannot be read or is not a PE image
     */
    public static boolean is32Bit(Path executable) throws IOException
    {
        try (SeekableByteChannel channel = Files.newByteChannel(executable, StandardOpenOption.READ))
        {
            ByteBuffer dos = read(channel, 0, 64);
            long offset = Integer.toUnsignedLong(dos.getInt(60));
            if (dos.get(0) != 'M' || dos.get(1) != 'Z' || offset < 64)
                throw new IOException("Executable has no DOS header naming a PE header: " + executable);
            // PE signature, the 20-byte COFF file header, then the optional header's magic.
            ByteBuffer header = read(channel, offset, 26);
            if (header.getInt(0) != 0x00004550) throw new IOException("Executable has no PE signature: " + executable);
            int magic = Short.toUnsignedInt(header.getShort(24));
            return switch (magic)
            {
                case 0x010b -> true;
                case 0x020b -> false;
                default -> throw new IOException("Executable optional header has unknown magic 0x" +
                    Integer.toHexString(magic) + ": " + executable);
            };
        }
    }

    /**
     * Reads an exact little-endian byte range.
     * @param channel open executable
     * @param offset  first byte
     * @param size    number of bytes
     * @return the range
     * @throws IOException if the file ends before the range does
     */
    private static ByteBuffer read(SeekableByteChannel channel, long offset, int size) throws IOException
    {
        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        channel.position(offset);
        while (buffer.hasRemaining() && channel.read(buffer) >= 0)
        {
        }
        if (buffer.hasRemaining()) throw new IOException("Executable header is truncated at byte " + (offset + buffer.position()));
        return buffer;
    }
}
