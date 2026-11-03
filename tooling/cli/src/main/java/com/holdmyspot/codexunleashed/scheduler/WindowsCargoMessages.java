package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Encodes untrusted Windows compiler receipts and final unsigned DWORD results.
 * Native process, stream and result authority must be established independently of decoding.
 * Instances borrow invocation services and support concurrent codec calls.
 */
public final class WindowsCargoMessages
{
    /** All members required in a wrapper registration; no member grants native authority. */
    private static final Set<String> REGISTRATION_FIELDS = Set.of("identity", "pid", "birth", "parentPid",
        "parentBirth", "arguments", "directory", "environment", "input", "output", "error");
    /** Borrowed bounded parser services. */
    private final WindowsCargoScope scope;

    /**
     * Borrows invocation services without acquiring native resources.
     * @param scope caller-owned services retained open through codec calls
     * @throws NullPointerException if {@code scope} is null
     */
    public WindowsCargoMessages(WindowsCargoScope scope)
    {
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    /**
     * Encodes one untrusted registration with exact request values and offered source handle values.
     * @param registration immutable wrapper receipt, authenticated later by the controller
     * @return bounded complete JSON payload
     * @throws IOException if encoding exceeds protocol bounds
     */
    public byte[] registration(Registration registration) throws IOException
    {
        CargoCompilerRequest request = registration.request();
        CargoCompilerRequest.Wrapper wrapper = request.wrapper();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("identity", request.identity().toString());
        values.put("pid", wrapper.pid());
        values.put("birth", wrapper.birth());
        values.put("parentPid", wrapper.parentPid());
        values.put("parentBirth", wrapper.parentBirth());
        values.put("arguments", request.arguments());
        values.put("directory", request.directory().toString());
        values.put("environment", request.environment());
        values.put("input", registration.input());
        values.put("output", registration.output());
        values.put("error", registration.error());
        return encode(values);
    }

    /**
     * Decodes a receipt without opening a process or importing any offered handle.
     * The resulting request always has unknown demand; remote demand estimates are rejected as extra fields.
     * @param bytes complete bounded JSON payload
     * @return immutable untrusted registration
     * @throws IOException if types, fields or request invariants are invalid
     */
    public Registration readRegistration(byte[] bytes) throws IOException
    {
        JsonNode message = decode(bytes, REGISTRATION_FIELDS);
        try
        {
            UUID identity = UUID.fromString(text(message.path("identity")));
            if (!identity.toString().equals(text(message.path("identity"))))
                throw new IOException("Cargo request identity must have canonical UUID spelling");
            CargoCompilerRequest.Wrapper wrapper = new CargoCompilerRequest.Wrapper(integer(message.path("pid")),
                integer(message.path("birth")), integer(message.path("parentPid")), integer(message.path("parentBirth")));
            JsonNode arguments = message.path("arguments");
            if (!arguments.isArray()) throw new IOException("Cargo arguments must be an array of strings");
            List<String> command = new ArrayList<>();
            for (JsonNode argument : arguments) command.add(text(argument));
            JsonNode environment = message.path("environment");
            if (!environment.isObject()) throw new IOException("Cargo environment must be an object of strings");
            Map<String, String> variables = new LinkedHashMap<>();
            for (var entry : environment.properties()) variables.put(entry.getKey(), text(entry.getValue()));
            CargoCompilerRequest request = new CargoCompilerRequest(identity, wrapper, command,
                Path.of(text(message.path("directory"))), variables, null);
            return new Registration(request, integer(message.path("input")), integer(message.path("output")),
                integer(message.path("error")));
        }
        catch (IllegalArgumentException failure)
        {
            throw new IOException("Cargo registration violates request or handle constraints", failure);
        }
    }

    /**
     * Encodes the controller acknowledgement that imported stream copies are now owned.
     * @return complete acknowledgement payload
     * @throws IOException if encoding fails
     */
    public byte[] accepted() throws IOException
    {
        return encode(Map.of("kind", "accepted", "value", 0));
    }

    /**
     * Encodes one terminal native compiler result without changing its DWORD bits.
     * @param status native unsigned DWORD value from zero through 4294967295
     * @return bounded final response
     * @throws IllegalArgumentException if {@code status} is outside the DWORD range
     * @throws IOException if encoding fails
     */
    public byte[] finalResult(long status) throws IOException
    {
        return encode(Map.of("kind", "final", "value", new Final(status).status()));
    }

    /**
     * Decodes a stream-import acknowledgement or final compiler result without numeric coercion.
     * @param bytes complete bounded payload
     * @return represented acknowledgement or unsigned DWORD final result
     * @throws IOException if fields, types or status range are invalid
     */
    public Response readResponse(byte[] bytes) throws IOException
    {
        JsonNode message = decode(bytes, Set.of("kind", "value"));
        String kind = text(message.path("kind"));
        long value = integer(message.path("value"));
        return switch (kind)
        {
            case "accepted" ->
            {
                if (value != 0) throw new IOException("Cargo acceptance value must be zero");
                yield new Accepted();
            }
            case "final" ->
            {
                if (value < 0 || value > 0xffffffffL) throw new IOException("Cargo final result must be an unsigned DWORD");
                yield new Final(value);
            }
            default -> throw new IOException("Cargo response kind must be accepted or final");
        };
    }

    /**
     * Serializes and validates the receiving parser's bounds before publication.
     * @param values complete application-owned message
     * @return bounded payload
     * @throws IOException if serialization or parser limits fail
     */
    private byte[] encode(Map<String, ?> values) throws IOException
    {
        try
        {
            byte[] result = scope.json().writeValueAsBytes(values);
            requireLength(result);
            scope.json().readTree(result);
            return result;
        }
        catch (JacksonException failure)
        {
            throw new IOException("Cargo message exceeds parser constraints", failure);
        }
    }

    /**
     * Parses exactly one bounded object and validates its complete member set.
     * @param bytes complete payload
     * @param fields required and permitted fields
     * @return uncoerced parsed object
     * @throws IOException if JSON or the member set is invalid
     */
    private JsonNode decode(byte[] bytes, Set<String> fields) throws IOException
    {
        requireLength(bytes);
        try
        {
            JsonNode message = scope.json().readTree(bytes);
            if (message == null || !message.isObject() || !Set.copyOf(message.propertyNames()).equals(fields))
                throw new IOException("Cargo message must contain exactly " + fields);
            return message;
        }
        catch (JacksonException failure)
        {
            throw new IOException("Cargo message must be one bounded JSON document", failure);
        }
    }

    /**
     * Requires a transport-sized nonempty payload without interpreting syntax.
     * @param bytes complete payload
     * @throws IOException if the length is outside one byte through 1 MiB
     */
    private static void requireLength(byte[] bytes) throws IOException
    {
        if (bytes.length == 0 || bytes.length > WindowsCargoTransport.MAX_FRAME_BYTES)
            throw new IOException("Cargo payload must contain one byte through 1 MiB");
    }

    /**
     * Reads text without coercing another JSON type.
     * @param value parsed value
     * @return represented text
     * @throws IOException if the value is not a JSON string
     */
    private static String text(JsonNode value) throws IOException
    {
        if (!value.isString()) throw new IOException("Cargo value must be text");
        return value.asString();
    }

    /**
     * Reads an exact signed Java long without accepting floating point or text.
     * @param value parsed number
     * @return exact represented integer
     * @throws IOException if the value is not an integral number fitting a long
     */
    private static long integer(JsonNode value) throws IOException
    {
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw new IOException("Cargo value must be an exact long integer");
        return value.longValue();
    }

    /**
     * Represents an untrusted logical compiler request and handle values in its wrapper process.
     * @param request immutable compiler invocation with unknown demand
     * @param input wrapper-local source stdin handle bits
     * @param output wrapper-local source stdout handle bits
     * @param error wrapper-local source stderr handle bits; it may alias output
     */
    public record Registration(CargoCompilerRequest request, long input, long output, long error)
    {
        /**
         * Checks represented values without importing handles or authenticating the process.
         * @param request immutable request whose demand must remain unknown
         * @param input source stdin handle bits
         * @param output source stdout handle bits
         * @param error source stderr handle bits
         * @throws NullPointerException if {@code request} is null
         * @throws IllegalArgumentException if demand is supplied or a handle is NULL or INVALID_HANDLE_VALUE
         */
        public Registration
        {
            Objects.requireNonNull(request, "request");
            if (request.peakBytes() != null) throw new IllegalArgumentException("Wrapper cannot supply compiler demand");
            if (input == 0 || input == -1 || output == 0 || output == -1 || error == 0 || error == -1)
                throw new IllegalArgumentException("Offered standard handles must be nonzero valid handle values");
        }
    }

    /**
     * Distinguishes initial stream acknowledgement from a terminal compiler result.
     */
    sealed interface Response permits Accepted, Final {}

    /**
     * Indicates that controller-owned stream copies are ready and source endpoints may remain frozen until close.
     */
    record Accepted() implements Response {}

    /**
     * Carries an exact Windows unsigned DWORD exit status.
     * @param status native status from zero through 4294967295
     */
    public record Final(long status) implements Response
    {
        /**
         * Validates the unsigned native result range.
         * @param status native DWORD represented as a nonnegative long
         * @throws IllegalArgumentException if {@code status} is outside zero through 4294967295
         */
        public Final
        {
            if (status < 0 || status > 0xffffffffL) throw new IllegalArgumentException("Final status must be an unsigned DWORD");
        }
    }
}
