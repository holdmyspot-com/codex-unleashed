package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Verifies sole-owner static-image selection for both supported Linux native ABIs. */
public final class LinuxBazelStaticImageTest
{
    /** Creates a resource-free instance; every test owns its file fixture. */
    public LinuxBazelStaticImageTest() {}

    /** Selects Java tracing for an AArch64 image without an interpreter.
     * @throws IOException if the independent image fixture cannot be inspected
     */
    @Test public void acceptsStaticArm() throws IOException
    {
        requireThat(selected(183, false), "staticArm").isTrue();
    }

    /** Preserves Java tracing for a static x86-64 image.
     * @throws IOException if the independent image fixture cannot be inspected
     */
    @Test public void acceptsStaticX64() throws IOException
    {
        requireThat(selected(62, false), "staticX64").isTrue();
    }

    /** Leaves interpreted AArch64 executables with their existing native backend.
     * @throws IOException if the independent image fixture cannot be inspected
     */
    @Test public void rejectsDynamicArm() throws IOException
    {
        requireThat(selected(183, true), "dynamicArm").isFalse();
    }

    /** Rejects an unsupported static ELF machine.
     * @throws IOException if the independent image fixture cannot be inspected
     */
    @Test public void rejectsOtherMachine() throws IOException
    {
        requireThat(selected(243, false), "otherMachine").isFalse();
    }

    /** Inspects one independent complete little-endian ELF64 program table.
     * @param machine ELF machine identity
     * @param interpreter whether the program table contains PT_INTERP
     * @return the production selector's actual result
     * @throws IOException if the file cannot be created, read or removed
     */
    private static boolean selected(int machine, boolean interpreter) throws IOException
    {
        ByteBuffer bytes = ByteBuffer.allocate(120).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0, 0x464c457f).put(4, (byte) 2).put(5, (byte) 1);
        bytes.putShort(18, (short) machine).putLong(32, 64);
        bytes.putShort(54, (short) 56).putShort(56, (short) 1);
        bytes.putInt(64, interpreter ? 3 : 1);
        Path path = Files.createTempFile("static-image-", ".elf");
        try
        {
            Files.write(path, bytes.array());
            try (FileChannel image = FileChannel.open(path, StandardOpenOption.READ))
            {
                return LinuxBazelHelperMain.isStaticTraceImage(image);
            }
        }
        finally
        {
            Files.delete(path);
        }
    }
}
