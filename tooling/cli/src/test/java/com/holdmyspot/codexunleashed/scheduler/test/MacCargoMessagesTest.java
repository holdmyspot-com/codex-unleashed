package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.MacCargoMessages;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Verifies exact native registration representation independently of transport or execution authority. */
public final class MacCargoMessagesTest
{
    /** Creates tests whose input arrays and codecs belong to each invocation. */
    public MacCargoMessagesTest() {}

    /**
     * Preserves empty arguments, Unicode, newline arguments and explicit empty environment values.
     * @throws IOException if a complete valid frame is rejected
     */
    @Test public void preservesExactNativeInputs() throws IOException
    {
        TestRequirements.requirePosixFileSystem();
        List<String> command = List.of("/usr/bin/true", "", "λ雪", "line\nbreak");
        MacCargoMessages.Invocation value = MacCargoMessages.decode(frame(command, List.of("EMPTY=", "VALUE=a=b"), List.of("/cwd λ")));
        requireThat(value.arguments(), "exactArguments").isEqualTo(command, "expected");
        requireThat(value.environment().get("EMPTY"), "explicitEmpty").isEqualTo("", "expected");
        requireThat(value.environment().get("VALUE"), "valueWithEquals").isEqualTo("a=b", "expected");
        requireThat(value.directory(), "exactDirectory").isEqualTo(Path.of("/cwd λ"), "expected");
    }

    /** Rejects ambiguous environment names and incomplete or trailing frames. */
    @Test public void rejectsAmbiguousAndIncompleteFrames()
    {
        byte[] valid = frame(List.of("/usr/bin/true"), List.of("A="), List.of("/"));
        requireException(IOException.class, () -> MacCargoMessages.decode(java.util.Arrays.copyOf(valid, valid.length - 1)));
        requireException(IOException.class, () -> MacCargoMessages.decode(java.util.Arrays.copyOf(valid, valid.length + 1)));
        requireException(IOException.class, () -> MacCargoMessages.decode(frame(List.of("/usr/bin/true"), List.of("A=1", "A=2"), List.of("/"))));
    }

    /** Rejects malformed native text and relative execution inputs without coercion. */
    @Test public void rejectsInvalidTextAndRelativePaths()
    {
        byte[] invalid = frame(List.of("/usr/bin/true", "x"), List.of(), List.of("/"));
        invalid[invalid.length - 1] = (byte) 0xff;
        requireException(IOException.class, () -> MacCargoMessages.decode(invalid));
        requireException(IOException.class, () -> MacCargoMessages.decode(frame(List.of("relative"), List.of(), List.of("/"))));
        requireException(IOException.class, () -> MacCargoMessages.decode(frame(List.of("/usr/bin/true"), List.of(), List.of("relative"))));
    }

    /**
     * Encodes explicit test inputs using the documented native count and byte-length framing.
     * @param command compiler command vector
     * @param environment complete environment vector
     * @param directory one-entry directory vector
     * @return independent complete frame
     */
    private static byte[] frame(List<String> command, List<String> environment, List<String> directory)
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (List<String> vector : List.of(command, environment, directory))
        {
            output.writeBytes(ByteBuffer.allocate(4).putInt(vector.size()).array());
            for (String entry : vector)
            {
                byte[] bytes = entry.getBytes(StandardCharsets.UTF_8);
                output.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array());
                output.writeBytes(bytes);
            }
        }
        return output.toByteArray();
    }
}
