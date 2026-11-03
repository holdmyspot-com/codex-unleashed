package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxCargoProtocol;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises the version-2 wrapper payload codec without sockets, processes or privileges.
 */
public final class LinuxCargoProtocolTest
{
    /**
     * Creates independently owned codec checks.
     */
    public LinuxCargoProtocolTest() {}

    /**
     * A registration preserves its directory, every argument byte and the environment order.
     * @throws IOException if a valid registration fails
     */
    @Test
    public void decodesRegistration() throws IOException
    {
        var register = (LinuxCargoProtocol.Register) LinuxCargoProtocol.decode(payload("register", "2", "/work", "3",
            "/usr/bin/rustc", "", "é b", "2", "B=x=y", "A="));
        requireThat(register.directory(), "directory").isEqualTo(Path.of("/work"));
        requireThat(register.arguments(), "arguments").isEqualTo(List.of("/usr/bin/rustc", "", "é b"));
        var expected = new LinkedHashMap<String, String>();
        expected.put("B", "x=y");
        expected.put("A", "");
        requireThat(register.environment(), "environment").isEqualTo(expected);
        requireThat(List.copyOf(register.environment().keySet()), "order").isEqualTo(List.of("B", "A"));
    }

    /**
     * Attempt messages preserve their decimal values, including zero and the largest unsigned wait status.
     * @throws IOException if a valid attempt message fails
     */
    @Test
    public void decodesAttemptMessages() throws IOException
    {
        requireThat(LinuxCargoProtocol.decode(payload("child", "1", "42", "0")), "child")
            .isEqualTo(new LinuxCargoProtocol.Child(1, 42, 0));
        requireThat(LinuxCargoProtocol.decode(payload("exited", "7", "4294967295")), "exited")
            .isEqualTo(new LinuxCargoProtocol.Exited(7, 4294967295L));
    }

    /**
     * Every malformed field count, type, number, text or environment entry is a protocol error.
     */
    @Test
    public void rejectsMalformedPayloads()
    {
        List<byte[]> malformed = new ArrayList<>(List.of(
            payload("child", "1", "2"),
            payload("child", "1", "2", "3", "4"),
            payload("unknown", "1"),
            payload("child", "01", "2", "3"),
            payload("child", "+1", "2", "3"),
            payload("child", "-1", "2", "3"),
            payload("child", "", "2", "3"),
            payload("child", "1a", "2", "3"),
            payload("child", "9223372036854775808", "2", "3"),
            payload("exited", "1"),
            payload("exited", "1", "4294967296"),
            payload("exited", "0", "0"),
            payload("child", "1", "0", "3"),
            payload("register", "1", "/work", "1", "rustc", "0"),
            payload("register", "2", "work", "1", "rustc", "0"),
            payload("register", "2", "/work", "0", "0"),
            payload("register", "2", "/work", "2", "rustc", "0"),
            payload("register", "2", "/work", "1", "rustc", "1"),
            payload("register", "2", "/work", "1", "rustc", "0", "extra"),
            payload("register", "2", "/work", "1", "rustc", "1", "NAME"),
            payload("register", "2", "/work", "1", "rustc", "1", "=value"),
            payload("register", "2", "/work", "1", "rustc", "2", "A=1", "A=2"),
            payload("release", "1"),
            new byte[0],
            Arrays.copyOf(payload("child", "1", "2", "3"), payload("child", "1", "2", "3").length - 1)));
        byte[] invalid = payload("register", "2", "/work", "1", "rustc", "0");
        invalid[invalid.length - 4] = (byte) 0xff;
        malformed.add(invalid);
        for (byte[] bytes : malformed)
            requireException(IOException.class, () -> LinuxCargoProtocol.decode(bytes));
    }

    /**
     * Controller commands use the exact field sequences the wrapper accepts.
     */
    @Test
    public void encodesCommands()
    {
        requireThat(LinuxCargoProtocol.release(1), "release").isEqualTo(payload("release", "1"));
        requireThat(LinuxCargoProtocol.abort(12), "abort").isEqualTo(payload("abort", "12"));
        requireThat(LinuxCargoProtocol.retry(), "retry").isEqualTo(payload("retry"));
        requireThat(LinuxCargoProtocol.finalStatus(0), "final0").isEqualTo(payload("final", "0"));
        requireThat(LinuxCargoProtocol.finalStatus(255), "final255").isEqualTo(payload("final", "255"));
        requireException(IllegalArgumentException.class, () -> LinuxCargoProtocol.finalStatus(-1));
        requireException(IllegalArgumentException.class, () -> LinuxCargoProtocol.finalStatus(256));
        requireException(IllegalArgumentException.class, () -> LinuxCargoProtocol.release(0));
        requireException(IllegalArgumentException.class, () -> LinuxCargoProtocol.abort(0));
    }

    /**
     * Raw wait statuses map to shell-style exit statuses, and stop or continue reports are rejected.
     * @throws IOException if a valid status fails
     */
    @Test
    public void mapsWaitStatus() throws IOException
    {
        requireThat(LinuxCargoProtocol.exitStatus(0), "success").isEqualTo(0);
        requireThat(LinuxCargoProtocol.exitStatus(3 << 8), "exit3").isEqualTo(3);
        requireThat(LinuxCargoProtocol.exitStatus(255 << 8), "exit255").isEqualTo(255);
        requireThat(LinuxCargoProtocol.exitStatus(9), "sigkill").isEqualTo(137);
        requireThat(LinuxCargoProtocol.exitStatus(0x80 | 11), "core").isEqualTo(139);
        requireException(IOException.class, () -> LinuxCargoProtocol.exitStatus(0x137f));
        requireException(IOException.class, () -> LinuxCargoProtocol.exitStatus(0xffff));
        requireException(IOException.class, () -> LinuxCargoProtocol.exitStatus(1L << 16));
        requireException(IOException.class, () -> LinuxCargoProtocol.exitStatus(0x100 | 9));
    }

    /**
     * Encodes fields with the wrapper's NUL terminators.
     * @param fields complete field sequence
     * @return wire payload without its length prefix
     */
    private static byte[] payload(String... fields)
    {
        var bytes = new ByteArrayOutputStream();
        for (String field : fields)
        {
            bytes.writeBytes(field.getBytes(StandardCharsets.UTF_8));
            bytes.write(0);
        }
        return bytes.toByteArray();
    }
}
