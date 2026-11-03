package com.holdmyspot.codexunleashed.scheduler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Encodes and strictly decodes version 2 of the native Linux Cargo wrapper protocol.
 * <p>
 * A payload is a sequence of UTF-8 fields, each terminated by one NUL byte, whose first field names the message.
 * Decimal fields carry no sign or leading zero. Any other shape is a protocol error, so a peer that drifts from the
 * contract fails closed instead of being half understood. Decoding grants no process authority: the controller
 * authenticates every decoded identity against {@code /proc} before acting on it. The codec holds no state.
 */
public final class LinuxCargoProtocol
{
    /** Protocol version the wrapper and this controller both speak. */
    private static final String VERSION = "2";
    /** Largest raw wait status, because the wrapper reports the C {@code int} status as unsigned. */
    private static final long MAX_WAIT_STATUS = 0xffff_ffffL;

    /**
     * Prevents instantiation of the stateless codec.
     */
    private LinuxCargoProtocol() {}

    /**
     * One decoded wrapper-to-controller message.
     */
    public sealed interface Message permits Register, Child, Exited {}

    /**
     * The wrapper's one registration, sent right after it connects.
     * @param directory   absolute working directory the compiler will run in
     * @param arguments   complete compiler argument vector, beginning with the compiler path Cargo passed
     * @param environment complete compiler environment in wrapper order
     */
    public record Register(Path directory, List<String> arguments, Map<String, String> environment) implements Message
    {
        /**
         * Copies the represented values so later mutation cannot change an authenticated registration.
         * @param directory   absolute working directory the compiler will run in
         * @param arguments   complete compiler argument vector, beginning with the compiler path Cargo passed
         * @param environment complete compiler environment in wrapper order
         * @throws NullPointerException     if any argument or element is null
         * @throws IllegalArgumentException if {@code directory} is relative or {@code arguments} is empty
         */
        public Register
        {
            Objects.requireNonNull(directory, "directory");
            if (!directory.isAbsolute()) throw new IllegalArgumentException("directory must be absolute");
            arguments = List.copyOf(arguments);
            if (arguments.isEmpty()) throw new IllegalArgumentException("arguments must not be empty");
            environment = Collections.unmodifiableMap(new LinkedHashMap<>(environment));
        }
    }

    /**
     * A forked compiler child that is blocked until the controller releases or aborts it.
     * @param attempt   attempt number, counting from 1 within the registration
     * @param pid       child process id
     * @param startTime field 22 of the child's {@code /proc/<pid>/stat}
     */
    public record Child(long attempt, long pid, long startTime) implements Message {}

    /**
     * A reaped attempt whose standard error has reached end of file.
     * @param attempt    attempt number the status belongs to
     * @param waitStatus raw {@code waitpid} status
     */
    public record Exited(long attempt, long waitStatus) implements Message {}

    /**
     * Decodes one complete payload.
     * @param bytes payload without its length prefix
     * @return decoded message
     * @throws NullPointerException if {@code bytes} is null
     * @throws IOException          if the payload violates the version 2 contract
     */
    public static Message decode(byte[] bytes) throws IOException
    {
        List<String> fields = fields(bytes);
        return switch (fields.getFirst())
        {
            case "register" -> register(fields);
            case "child" ->
            {
                requireCount(fields, 4);
                yield new Child(decimal(fields.get(1), 1, Long.MAX_VALUE), decimal(fields.get(2), 1, Integer.MAX_VALUE),
                    decimal(fields.get(3), 0, Long.MAX_VALUE));
            }
            case "exited" ->
            {
                requireCount(fields, 3);
                yield new Exited(decimal(fields.get(1), 1, Long.MAX_VALUE), decimal(fields.get(2), 0, MAX_WAIT_STATUS));
            }
            default -> throw new IOException("Unknown Cargo wrapper message type");
        };
    }

    /**
     * Encodes permission for a blocked attempt to execute the compiler.
     * @param attempt attempt number, at least 1
     * @return payload without its length prefix
     * @throws IllegalArgumentException if {@code attempt} is not positive
     */
    public static byte[] release(long attempt)
    {
        return encode("release", positive(attempt));
    }

    /**
     * Encodes cancellation of an attempt that has not been released.
     * @param attempt attempt number, at least 1
     * @return payload without its length prefix
     * @throws IllegalArgumentException if {@code attempt} is not positive
     */
    public static byte[] abort(long attempt)
    {
        return encode("abort", positive(attempt));
    }

    /**
     * Encodes a request that the wrapper discard the finished attempt and fork the next one.
     * @return payload without its length prefix
     */
    public static byte[] retry()
    {
        return encode("retry");
    }

    /**
     * Encodes the wrapper's own exit status.
     * @param status exit status from 0 through 255
     * @return payload without its length prefix
     * @throws IllegalArgumentException if {@code status} is outside 0 through 255
     */
    public static byte[] finalStatus(int status)
    {
        if (status < 0 || status > 255) throw new IllegalArgumentException("status must be from 0 through 255");
        return encode("final", Integer.toString(status));
    }

    /**
     * Maps a raw wait status to the status a shell would report for it.
     * @param waitStatus raw {@code waitpid} status
     * @return exit code for a normal exit, or 128 plus the signal number for a signal
     * @throws IOException if the status reports a stop, a continue or bits a terminated process cannot have
     */
    public static int exitStatus(long waitStatus) throws IOException
    {
        long signal = waitStatus & 0x7f;
        if (signal == 0 && waitStatus < 0x1_0000) return (int) (waitStatus >> 8);
        if (signal != 0 && signal != 0x7f && waitStatus <= 0xff) return 128 + (int) signal;
        throw new IOException("Wait status does not describe a terminated process");
    }

    /**
     * Decodes a registration after its type field.
     * @param fields every decoded field, including the type
     * @return decoded registration
     * @throws IOException if the version, counts, directory or environment are invalid
     */
    private static Register register(List<String> fields) throws IOException
    {
        if (fields.size() < 5 || !fields.get(1).equals(VERSION))
            throw new IOException("Cargo wrapper registration has an unsupported version");
        Path directory;
        try
        {
            directory = Path.of(fields.get(2));
        }
        catch (InvalidPathException cause)
        {
            throw new IOException("Cargo wrapper directory is malformed", cause);
        }
        if (!directory.isAbsolute()) throw new IOException("Cargo wrapper directory is relative");
        int argc = (int) decimal(fields.get(3), 1, fields.size());
        int envIndex = 4 + argc;
        if (envIndex >= fields.size()) throw new IOException("Cargo wrapper registration is truncated");
        int envc = (int) decimal(fields.get(envIndex), 0, fields.size());
        requireCount(fields, envIndex + 1 + envc);
        Map<String, String> environment = new LinkedHashMap<>();
        for (String entry : fields.subList(envIndex + 1, fields.size()))
        {
            int separator = entry.indexOf('=');
            if (separator <= 0) throw new IOException("Cargo wrapper environment entry has no name");
            if (environment.putIfAbsent(entry.substring(0, separator), entry.substring(separator + 1)) != null)
                throw new IOException("Cargo wrapper environment repeats a name");
        }
        return new Register(directory, fields.subList(4, envIndex), environment);
    }

    /**
     * Splits a payload into strictly decoded UTF-8 fields.
     * @param bytes payload without its length prefix
     * @return at least one field
     * @throws IOException if the payload is empty, lacks its final terminator or is not UTF-8
     */
    private static List<String> fields(byte[] bytes) throws IOException
    {
        if (bytes.length == 0 || bytes[bytes.length - 1] != 0)
            throw new IOException("Cargo wrapper payload lacks its final field terminator");
        var decoder = StandardCharsets.UTF_8.newDecoder().
            onMalformedInput(CodingErrorAction.REPORT).
            onUnmappableCharacter(CodingErrorAction.REPORT);
        List<String> fields = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < bytes.length; ++index)
        {
            if (bytes[index] != 0) continue;
            try
            {
                fields.add(decoder.decode(ByteBuffer.wrap(bytes, start, index - start)).toString());
            }
            catch (CharacterCodingException cause)
            {
                throw new IOException("Cargo wrapper field is not UTF-8", cause);
            }
            start = index + 1;
        }
        return fields;
    }

    /**
     * Requires an exact field count.
     * @param fields   decoded fields, including the type
     * @param expected exact required count
     * @throws IOException if the count differs
     */
    private static void requireCount(List<String> fields, int expected) throws IOException
    {
        if (fields.size() != expected) throw new IOException("Cargo wrapper message has the wrong field count");
    }

    /**
     * Parses a canonical unsigned decimal.
     * @param text    field text
     * @param minimum smallest accepted value
     * @param maximum largest accepted value
     * @return parsed value
     * @throws IOException if the text is not canonical or is out of range
     */
    private static long decimal(String text, long minimum, long maximum) throws IOException
    {
        if (text.isEmpty() || text.length() > 19 || (text.length() > 1 && text.charAt(0) == '0'))
            throw new IOException("Cargo wrapper number is malformed");
        long value = 0;
        for (int index = 0; index < text.length(); ++index)
        {
            char digit = text.charAt(index);
            if (digit < '0' || digit > '9') throw new IOException("Cargo wrapper number is malformed");
            if (value > (Long.MAX_VALUE - (digit - '0')) / 10) throw new IOException("Cargo wrapper number overflows");
            value = value * 10 + (digit - '0');
        }
        if (value < minimum || value > maximum) throw new IOException("Cargo wrapper number is out of range");
        return value;
    }

    /**
     * Formats a positive attempt number.
     * @param attempt attempt number
     * @return canonical decimal
     * @throws IllegalArgumentException if {@code attempt} is not positive
     */
    private static String positive(long attempt)
    {
        if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
        return Long.toString(attempt);
    }

    /**
     * Joins fields with their NUL terminators.
     * @param fields complete field sequence
     * @return payload without its length prefix
     */
    private static byte[] encode(String... fields)
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
