package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxBorrowedStreams;
import com.holdmyspot.codexunleashed.scheduler.LinuxCargoMessages;
import com.holdmyspot.codexunleashed.scheduler.LinuxCargoScope;
import com.holdmyspot.codexunleashed.scheduler.LinuxCargoTransport;
import com.holdmyspot.codexunleashed.scheduler.LinuxProcessTree;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises Cargo message values without opening sockets or authenticating processes.
 */
public final class LinuxCargoMessagesTest
{
    /**
     * Creates independently owned message checks.
     */
    public LinuxCargoMessagesTest() {}

    /**
     * Registration and attempt messages preserve all represented launch and identity values.
     * @throws IOException if a valid message fails
     */
    @Test
    public void preservesRegistrationAndAttemptValues() throws IOException
    {
        try (var tooling = new DefaultToolingScope(); var scope = new LinuxCargoScope(tooling))
        {
            var codec = new LinuxCargoMessages(scope);
            var original = binding("value");
            var registration = codec.readRegistration(codec.registration(original));
            var attempt = (LinuxCargoMessages.Attempt) codec.readResponse(codec.attempt(original));
            requireThat(properties(registration), "registrationValues").isEqualTo(properties(original));
            requireThat(properties(attempt.binding()), "attemptValues").isEqualTo(properties(original));
            requireThat(registration.approvedLaunch().command(), "arguments")
                .isEqualTo(List.of(Path.of("/fixture/rustc").toAbsolutePath().toString(), "", "a b", "é"));
            requireThat(registration.approvedLaunch().environment(), "environment").isEqualTo(Map.of("EMPTY", "", "VALUE", "value"));
        }
    }

    /**
     * Final messages preserve every native Linux exit status including both boundaries.
     * @throws IOException if a valid status fails
     */
    @Test
    public void preservesEveryFinalStatus() throws IOException
    {
        try (var tooling = new DefaultToolingScope(); var scope = new LinuxCargoScope(tooling))
        {
            var codec = new LinuxCargoMessages(scope);
            for (int status = 0; status <= 255; status++)
                requireThat(((LinuxCargoMessages.Final) codec.readResponse(codec.finalResult(status))).status(), "status").isEqualTo(status);
            requireException(IllegalArgumentException.class, () -> codec.finalResult(-1));
            requireException(IllegalArgumentException.class, () -> codec.finalResult(256));
            requireException(IllegalArgumentException.class, () -> new LinuxCargoMessages.Final(-1));
            requireException(NullPointerException.class, () -> new LinuxCargoMessages.Attempt(null));
        }
    }

    /**
     * Response decoding rejects malformed JSON, changed fields and coercible noninteger statuses.
     */
    @Test
    public void rejectsInvalidResponses()
    {
        try (var tooling = new DefaultToolingScope(); var scope = new LinuxCargoScope(tooling))
        {
            var codec = new LinuxCargoMessages(scope);
            for (String message : List.of("", "null", "[]", "{}", "{\"kind\":\"final\"}",
                "{\"kind\":\"final\",\"value\":null}", "{\"kind\":\"final\",\"value\":0.0}",
                "{\"kind\":\"final\",\"value\":\"0\"}", "{\"kind\":\"final\",\"value\":true}",
                "{\"kind\":\"final\",\"value\":-1}", "{\"kind\":\"final\",\"value\":256}",
                "{\"kind\":\"final\",\"value\":2147483648}", "{\"kind\":\"final\",\"value\":01}",
                "{\"kind\":0,\"value\":0}", "{\"kind\":\"other\",\"value\":0}",
                "{\"kind\":\"final\",\"value\":0,\"extra\":0}",
                "{\"kind\":\"final\",\"kind\":\"attempt\",\"value\":0}",
                "{\"kind\":\"final\",\"value\":0} {}", "{\"kind\":\"attempt\",\"value\":{}}"))
                requireException(IOException.class, () -> codec.readResponse(message.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /**
     * Registration decoding rejects missing, unknown, duplicate and wrongly typed fields.
     * @throws IOException if valid fixture encoding fails
     */
    @Test
    public void rejectsInvalidRegistrationShapes() throws IOException
    {
        try (var tooling = new DefaultToolingScope(); var scope = new LinuxCargoScope(tooling))
        {
            var codec = new LinuxCargoMessages(scope);
            for (String message : List.of("{}", "{\"binding\":null}", "{\"binding\":[]}",
                "{\"binding\":{\"borrowed.action\":1}}", "{\"binding\":{},\"extra\":0}",
                "{\"binding\":{},\"binding\":{}}", "{\"binding\":{}} {}"))
                requireException(IOException.class, () -> codec.readRegistration(message.getBytes(StandardCharsets.UTF_8)));
            var values = properties(binding("value"));
            values.put("unexpected", "value");
            requireException(IOException.class, () -> codec.readRegistration(scope.json().writeValueAsBytes(Map.of("binding", values))));
            values.remove("unexpected");
            values.remove("borrowed.arg.1");
            requireException(IOException.class, () -> codec.readRegistration(scope.json().writeValueAsBytes(Map.of("binding", values))));
        }
    }

    /**
     * Existing binding parsing is followed by canonical property comparison for equivalent spellings.
     */
    @Test
    public void rejectsNoncanonicalBindingValues()
    {
        try (var tooling = new DefaultToolingScope(); var scope = new LinuxCargoScope(tooling))
        {
            var codec = new LinuxCargoMessages(scope);
            for (var change : Map.of("borrowed.sequence", "01", "borrowed.pid", "+71",
                "borrowed.action", "00000000-0000-0000-0000-00000000000A",
                "borrowed.capability", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").entrySet())
            {
                var values = properties(binding("value"));
                values.put(change.getKey(), change.getValue());
                requireException(IOException.class, () -> codec.readRegistration(scope.json().writeValueAsBytes(Map.of("binding", values))));
                requireException(IOException.class, () -> codec.readResponse(scope.json().writeValueAsBytes(Map.of("kind", "attempt", "value", values))));
            }
        }
    }

    /**
     * Frame and string limits apply to incoming and generated messages before transport publication.
     * @throws IOException if a valid boundary message fails
     */
    @Test
    public void enforcesMessageBounds() throws IOException
    {
        try (var tooling = new DefaultToolingScope(); var scope = new LinuxCargoScope(tooling))
        {
            var codec = new LinuxCargoMessages(scope);
            String prefix = "{\"kind\":\"final\",\"value\":7}";
            byte[] boundary = (prefix + " ".repeat(LinuxCargoTransport.MAX_FRAME_BYTES - prefix.length())).getBytes(StandardCharsets.UTF_8);
            requireThat(((LinuxCargoMessages.Final) codec.readResponse(boundary)).status(), "boundaryStatus").isEqualTo(7);
            requireException(IOException.class, () -> codec.readResponse(new byte[LinuxCargoTransport.MAX_FRAME_BYTES + 1]));
            requireException(IOException.class, () -> codec.readRegistration(new byte[0]));
            requireException(IOException.class, () -> codec.registration(binding("x".repeat(262145))));
            requireException(IOException.class, () -> codec.attempt(binding("x".repeat(LinuxCargoTransport.MAX_FRAME_BYTES))));
        }
    }

    /**
     * Closed services prevent codec reuse while leaving borrowed tooling available.
     * @throws IOException if valid fixture encoding fails
     */
    @Test
    public void refusesUseAfterScopeClose() throws IOException
    {
        try (var tooling = new DefaultToolingScope())
        {
            var scope = new LinuxCargoScope(tooling);
            try (scope)
            {
                var codec = new LinuxCargoMessages(scope);
                byte[] message = codec.finalResult(0);
                scope.close();
                requireException(IllegalStateException.class, () -> codec.readResponse(message));
                requireException(IllegalStateException.class, () -> codec.finalResult(0));
            }
            requireThat(tooling.isClosed(), "borrowedToolingClosed").isFalse();
            requireThat(tooling.getJsonMapper().readTree("0").intValue(), "borrowedToolingValue").isEqualTo(0);
        }
    }

    /**
     * Creates an untrusted value fixture with host-absolute paths; no represented process or pathname is accessed.
     * @param value represented compiler environment value
     * @return complete Linux receipt for pure message checks
     */
    private static LinuxBorrowedStreams.Binding binding(String value)
    {
        Path path = Path.of("/fixture").toAbsolutePath();
        var launch = new LinuxProcessTree.Launch(path, path, path, path,
            List.of(path.resolve("rustc").toString(), "", "a b", "é"), Map.of("EMPTY", "", "VALUE", value), path,
            1000, 1000, LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC);
        var stream = new LinuxBorrowedStreams.Terminal(false, "", 0, 0, List.of());
        return new LinuxBorrowedStreams.Binding(new UUID(1, 2), 1,
            new LinuxBorrowedStreams.Peer(71, 81, 1000, 1000), path.resolve("streams.sock"), new byte[32],
            new LinuxBorrowedStreams.TerminalContext(1, 1, List.of(stream, stream, stream)), launch);
    }

    /**
     * Copies the existing complete property representation for assertions and controlled wire inputs.
     * @param binding represented receipt
     * @return mutable fixture map
     */
    private static Map<String, String> properties(LinuxBorrowedStreams.Binding binding)
    {
        Properties properties = new Properties();
        LinuxBorrowedStreams.writeTo(properties, binding);
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) values.put(key, properties.getProperty(key));
        return values;
    }
}
