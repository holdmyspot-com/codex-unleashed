package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.List;
import java.util.Map;

/** Decodes the bounded application-owned native registration frame without granting execution authority. */
public final class MacCargoMessages
{
    /** Prevents construction of this stateless decoder. */
    private MacCargoMessages() {}

    /**
     * Decodes all three vectors of one complete native frame.
     * @param payload complete frame without its transport header
     * @return exact represented invocation requiring separate native authentication
     * @throws NullPointerException if payload is null
     * @throws IOException if bounds, UTF-8, environment, command or directory are invalid
     */
    public static Invocation decode(byte[] payload) throws IOException
    {
        Objects.requireNonNull(payload, "payload");
        if (payload.length == 0 || payload.length > 1024 * 1024)
            throw new IOException("Native registration exceeds its complete frame bound");
        ByteBuffer input = ByteBuffer.wrap(payload);
        List<String> command = vector(input);
        List<String> representedEnvironment = vector(input);
        List<String> directory = vector(input);
        if (input.hasRemaining() || directory.size() != 1 || command.isEmpty())
            throw new IOException("Native registration requires three complete invocation vectors");
        Map<String, String> environment = entries(representedEnvironment);
        try
        {
            if (command.getFirst().isBlank() || !Path.of(command.getFirst()).isAbsolute() ||
                !Path.of(directory.getFirst()).isAbsolute())
                throw new IOException("Compiler executable and directory must be absolute");
            return new Invocation(command, environment, Path.of(directory.getFirst()));
        }
        catch (InvalidPathException failure)
        {
            throw new IOException("Native invocation path is invalid", failure);
        }
    }

    /**
     * Decodes the single environment vector of one complete native parent-environment frame.
     * @param payload complete frame
     * @return environment names and values
     * @throws NullPointerException if {@code payload} is null
     * @throws IOException          if bounds, UTF-8 or an environment entry is invalid, or a name repeats
     */
    public static Map<String, String> environment(byte[] payload) throws IOException
    {
        Objects.requireNonNull(payload, "payload");
        if (payload.length == 0 || payload.length > 1024 * 1024)
            throw new IOException("Native parent environment exceeds its complete frame bound");
        ByteBuffer input = ByteBuffer.wrap(payload);
        List<String> represented = vector(input);
        if (input.hasRemaining()) throw new IOException("Native parent environment requires one complete vector");
        return entries(represented);
    }

    /**
     * Splits NAME=value environment strings.
     * @param represented environment strings in native order
     * @return names and values
     * @throws IOException if an entry lacks a nonempty name and equals sign, or a name repeats
     */
    private static Map<String, String> entries(List<String> represented) throws IOException
    {
        Map<String, String> environment = new LinkedHashMap<>();
        for (String entry : represented)
        {
            int separator = entry.indexOf('=');
            if (separator <= 0) throw new IOException("Environment entry requires a nonempty name and equals sign");
            if (environment.putIfAbsent(entry.substring(0, separator), entry.substring(separator + 1)) != null)
                throw new IOException("Environment contains duplicate names");
        }
        return Map.copyOf(environment);
    }

    /**
     * Reads one complete bounded vector without accepting malformed UTF-8 or native NUL text.
     * @param input remaining complete frame
     * @return immutable ordered represented strings
     * @throws IOException if a count, length, encoding or text value is invalid
     */
    private static List<String> vector(ByteBuffer input) throws IOException
    {
        int count = integer(input);
        if (count < 0 || count > 65536 || count > input.remaining() / Integer.BYTES)
            throw new IOException("Native vector count exceeds remaining frame bounds");
        List<String> result = new ArrayList<>(count);
        for (int i = 0; i < count; ++i)
        {
            int size = integer(input);
            if (size < 0 || size > input.remaining()) throw new IOException("Native string exceeds remaining frame bounds");
            ByteBuffer bytes = input.slice(input.position(), size);
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(bytes).toString();
            if (text.indexOf('\0') >= 0) throw new IOException("Native string contains NUL");
            result.add(text);
            input.position(input.position() + size);
        }
        return List.copyOf(result);
    }

    /**
     * Reads one network-order signed storage value, whose caller validates its unsigned protocol bound.
     * @param input remaining complete frame
     * @return exact stored bits
     * @throws IOException if the complete integer is absent
     */
    private static int integer(ByteBuffer input) throws IOException
    {
        if (input.remaining() < Integer.BYTES) throw new IOException("Native frame contains an incomplete integer");
        return input.getInt();
    }

    /**
     * Retains represented compiler inputs without acquiring process or descriptor authority.
     * @param arguments exact executable and arguments
     * @param environment exact environment names and values
     * @param directory absolute working directory
     */
    public record Invocation(List<String> arguments, Map<String, String> environment, Path directory)
    {
        /**
         * Copies represented inputs without normalizing text or acquiring native authority.
         * @param arguments exact executable and argument strings
         * @param environment complete unique environment names and values
         * @param directory absolute represented working directory
         * @throws NullPointerException if an input or collection entry is null
         */
        public Invocation
        {
            arguments = List.copyOf(arguments);
            environment = Map.copyOf(environment);
            Objects.requireNonNull(directory, "directory");
        }
    }
}
