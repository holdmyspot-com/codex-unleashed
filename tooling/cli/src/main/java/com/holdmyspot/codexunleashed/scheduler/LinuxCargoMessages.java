package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Encodes and validates Linux Cargo registration, attempt authorization and final compiler messages.
 * Callers supply one complete payload without its transport header. The codec preserves represented values and
 * rejects unknown fields and noncanonical binding properties; decoding grants no process, stream or result authority.
 * The invocation authenticates those values before registration or native execution. Instances borrow their scope
 * and may be used concurrently while it remains open.
 */
public final class LinuxCargoMessages
{
    /** Registration field containing the existing borrowed-stream property representation. */
    private static final String BINDING = "binding";
    /** Response discriminator field, required for both supported response forms. */
    private static final String KIND = "kind";
    /** Response payload field, containing an attempt binding or final integer status. */
    private static final String VALUE = "value";
    /** Response discriminator for a controller-selected attempt binding. */
    private static final String ATTEMPT = "attempt";
    /** Response discriminator for the native compiler's final status. */
    private static final String FINAL = "final";
    /** Exact permitted registration members; extras and omissions are rejected. */
    private static final Set<String> REGISTRATION_FIELDS = Set.of(BINDING);
    /** Exact permitted response members; extras and omissions are rejected. */
    private static final Set<String> RESPONSE_FIELDS = Set.of(KIND, VALUE);
    /** Caller-owned invocation dependencies, retained open through all codec calls. */
    private final LinuxCargoScope scope;

    /**
     * Borrows a Cargo dependency scope without acquiring native resources.
     * @param scope caller-owned services retained open through all codec calls
     * @throws NullPointerException if {@code scope} is null
     */
    public LinuxCargoMessages(LinuxCargoScope scope)
    {
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    /**
     * Encodes the wrapper's untrusted initial receipt without granting registration authority.
     * @param binding complete represented wrapper receipt
     * @return complete bounded registration payload with canonical binding properties
     * @throws NullPointerException if {@code binding} is null
     * @throws IllegalStateException if the borrowed scope is closed
     * @throws IOException if encoding exceeds a message or parser bound
     */
    public byte[] registration(LinuxBorrowedStreams.Binding binding) throws IOException
    {
        return encode(Map.of(BINDING, properties(binding)));
    }

    /**
     * Decodes exactly one complete registration message.
     * @param bytes complete registration payload, from one byte through 1 MiB
     * @return untrusted receipt requiring independent native authentication before registration
     * @throws NullPointerException if {@code bytes} is null
     * @throws IllegalStateException if the borrowed scope is closed
     * @throws IOException if JSON, message fields, bounds or canonical binding values are invalid
     */
    public LinuxBorrowedStreams.Binding readRegistration(byte[] bytes) throws IOException
    {
        return binding(decode(bytes, REGISTRATION_FIELDS).path(BINDING));
    }

    /**
     * Encodes a controller-selected receipt for one compiler attempt.
     * @param binding complete receipt whose authority the invocation separately established
     * @return complete bounded attempt response payload
     * @throws NullPointerException if {@code binding} is null
     * @throws IllegalStateException if the borrowed scope is closed
     * @throws IOException if encoding exceeds a message or parser bound
     */
    public byte[] attempt(LinuxBorrowedStreams.Binding binding) throws IOException
    {
        return encode(Map.of(KIND, ATTEMPT, VALUE, properties(binding)));
    }

    /**
     * Encodes the native compiler's final status without establishing result authority.
     * @param status actual Linux compiler exit status from zero through 255
     * @return complete bounded final response payload
     * @throws IllegalArgumentException if {@code status} is outside zero through 255
     * @throws IllegalStateException if the borrowed scope is closed
     * @throws IOException if serialization fails
     */
    public byte[] finalResult(int status) throws IOException
    {
        return encode(Map.of(KIND, FINAL, VALUE, new Final(status).status()));
    }

    /**
     * Decodes one attempt authorization or final compiler status without coercing JSON types.
     * @param bytes complete response payload, from one byte through 1 MiB
     * @return represented attempt receipt or final integer status from zero through 255
     * @throws NullPointerException if {@code bytes} is null
     * @throws IllegalStateException if the borrowed scope is closed
     * @throws IOException if JSON, fields, discriminator, bounds or represented values are invalid
     */
    public Response readResponse(byte[] bytes) throws IOException
    {
        JsonNode message = decode(bytes, RESPONSE_FIELDS);
        JsonNode kind = message.path(KIND);
        if (!kind.isString()) throw new IOException("Cargo response kind must be text");
        JsonNode value = message.path(VALUE);
        return switch (kind.asString())
        {
            case ATTEMPT -> new Attempt(binding(value));
            case FINAL ->
            {
                if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0 || value.intValue() > 255)
                    throw new IOException("Cargo final status must be an integer from zero through 255");
                yield new Final(value.intValue());
            }
            default -> throw new IOException("Cargo response kind must be attempt or final");
        };
    }

    /**
     * Encodes an application-owned message and enforces the receiving parser's limits before publication.
     * @param message complete message members with already validated values
     * @return bounded complete payload
     * @throws IllegalStateException if the borrowed scope is closed
     * @throws IOException if serialization or a receiving parser bound fails
     */
    private byte[] encode(Map<String, ?> message) throws IOException
    {
        try
        {
            byte[] bytes = scope.json().writeValueAsBytes(message);
            requireLength(bytes);
            scope.json().readTree(bytes);
            return bytes;
        }
        catch (JacksonException failure)
        {
            throw new IOException("Cargo message cannot be encoded within its parser bounds", failure);
        }
    }

    /**
     * Parses one bounded object and validates its complete expected member set.
     * @param bytes complete payload
     * @param fields all required and permitted message members
     * @return parsed object retaining JSON types
     * @throws NullPointerException if {@code bytes} is null
     * @throws IllegalStateException if the borrowed scope is closed
     * @throws IOException if parsing, bounds or member validation fails
     */
    private JsonNode decode(byte[] bytes, Set<String> fields) throws IOException
    {
        requireLength(bytes);
        try
        {
            JsonNode message = scope.json().readTree(bytes);
            if (message == null || !message.isObject() || !Set.copyOf(message.propertyNames()).equals(fields))
                throw new IOException("Cargo message must contain exactly its required fields: " + fields);
            return message;
        }
        catch (JacksonException failure)
        {
            throw new IOException("Cargo message is not one bounded JSON document", failure);
        }
    }

    /**
     * Enforces the complete transport payload bound without examining JSON syntax.
     * @param bytes caller-owned complete payload
     * @throws NullPointerException if {@code bytes} is null
     * @throws IOException if the payload is empty or larger than 1 MiB
     */
    private static void requireLength(byte[] bytes) throws IOException
    {
        if (bytes.length == 0 || bytes.length > LinuxCargoTransport.MAX_FRAME_BYTES)
            throw new IOException("Cargo message payload must contain from one byte through 1 MiB");
    }

    /**
     * Copies a receipt's existing canonical property representation without native operations.
     * @param binding represented receipt
     * @return complete string properties
     * @throws NullPointerException if {@code binding} is null
     */
    private static Map<String, String> properties(LinuxBorrowedStreams.Binding binding)
    {
        Properties properties = new Properties();
        LinuxBorrowedStreams.writeTo(properties, Objects.requireNonNull(binding, "binding"));
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) values.put(key, properties.getProperty(key));
        return Map.copyOf(values);
    }

    /**
     * Decodes the existing receipt representation and rejects omitted, extra or noncanonical properties.
     * @param message represented binding as a JSON object of string properties
     * @return untrusted reconstructed receipt
     * @throws IOException if a type, property or represented value is invalid
     */
    private static LinuxBorrowedStreams.Binding binding(JsonNode message) throws IOException
    {
        if (!message.isObject()) throw new IOException("Cargo binding must be an object of string properties");
        Map<String, String> values = new LinkedHashMap<>();
        for (var entry : message.properties())
        {
            if (!entry.getValue().isString()) throw new IOException("Cargo binding properties must contain string values");
            values.put(entry.getKey(), entry.getValue().asString());
        }
        Properties properties = new Properties();
        properties.putAll(values);
        var binding = LinuxBorrowedStreams.readFrom(properties);
        if (binding == null || !properties(binding).equals(values))
            throw new IOException("Cargo binding contains missing, unknown or noncanonical properties");
        return binding;
    }

    /**
     * Distinguishes attempt authorizations from final compiler outcomes.
     */
    sealed interface Response permits Attempt, Final {}

    /**
     * Carries a decoded attempt receipt without granting native authority.
     * @param binding represented receipt
     */
    public record Attempt(LinuxBorrowedStreams.Binding binding) implements Response
    {
        /**
         * Requires a complete represented receipt without authenticating it.
         * @param binding represented receipt
         * @throws NullPointerException if {@code binding} is null
         */
        public Attempt
        {
            Objects.requireNonNull(binding, "binding");
        }
    }

    /**
     * Carries a decoded final compiler status.
     * @param status represented native exit status
     */
    public record Final(int status) implements Response
    {
        /**
         * Requires a Linux compiler exit status from zero through 255.
         * @param status represented native exit status
         * @throws IllegalArgumentException if {@code status} is outside zero through 255
         */
        public Final
        {
            if (status < 0 || status > 255) throw new IllegalArgumentException("Cargo final status must be from zero through 255");
        }
    }
}
