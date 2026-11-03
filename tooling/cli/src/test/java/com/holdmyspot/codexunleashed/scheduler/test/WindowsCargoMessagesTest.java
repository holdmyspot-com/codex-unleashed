package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.CargoCompilerRequest;
import com.holdmyspot.codexunleashed.scheduler.WindowsCargoMessages;
import com.holdmyspot.codexunleashed.scheduler.WindowsCargoScope;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies Windows wire interpretation without claiming native authentication or execution.
 */
public final class WindowsCargoMessagesTest
{
    /**
     * Creates independent per-method message checks.
     */
    public WindowsCargoMessagesTest() {}

    /**
     * Final messages retain unsigned DWORD boundary bits without signed-int truncation.
     * @throws IOException if a valid message fails
     */
    @Test public void preservesUnsignedDword() throws IOException
    {
        try (DefaultToolingScope tooling = new DefaultToolingScope(); WindowsCargoScope scope = new WindowsCargoScope(tooling))
        {
            WindowsCargoMessages codec = new WindowsCargoMessages(scope);
            for (long status : new long[]{0, 125, 255, 256, 0x80000000L, 0xc0000005L, 0xffffffffL})
                requireThat(((WindowsCargoMessages.Final) codec.readResponse(codec.finalResult(status))).status(), "status").isEqualTo(status);
            requireException(IllegalArgumentException.class, () -> codec.finalResult(-1));
            requireException(IllegalArgumentException.class, () -> codec.finalResult(0x100000000L));
        }
    }

    /**
     * Registration preserves empty arguments, environment and stream aliases while rejecting remote demand.
     * @throws IOException if valid registration fails
     */
    @Test public void preservesExactRegistration() throws IOException
    {
        try (DefaultToolingScope tooling = new DefaultToolingScope(); WindowsCargoScope scope = new WindowsCargoScope(tooling))
        {
            WindowsCargoMessages codec = new WindowsCargoMessages(scope);
            CargoCompilerRequest request = new CargoCompilerRequest(UUID.randomUUID(),
                new CargoCompilerRequest.Wrapper(71, 101, 70, 100),
                List.of(Path.of("rustc").toAbsolutePath().toString(), "", "a b", "é", "a\\\"b"),
                Path.of("").toAbsolutePath(), Map.of("EMPTY", "", "VALUE", "é"), null);
            WindowsCargoMessages.Registration offer = new WindowsCargoMessages.Registration(request, 11, 12, 12);
            requireThat(codec.readRegistration(codec.registration(offer)), "registration").isEqualTo(offer);
            byte[] changed = new String(codec.registration(offer), StandardCharsets.UTF_8).replace("\"pid\":71", "\"peakBytes\":1,\"pid\":71").getBytes(StandardCharsets.UTF_8);
            requireException(IOException.class, () -> codec.readRegistration(changed));
        }
    }

    /**
     * Strict parsing rejects duplicate, trailing, coerced and out-of-range result values.
     */
    @Test public void rejectsAmbiguousResponses()
    {
        try (DefaultToolingScope tooling = new DefaultToolingScope(); WindowsCargoScope scope = new WindowsCargoScope(tooling))
        {
            WindowsCargoMessages codec = new WindowsCargoMessages(scope);
            for (String json : List.of("{}", "null", "{\"kind\":\"final\",\"value\":0.0}",
                "{\"kind\":\"final\",\"value\":\"0\"}", "{\"kind\":\"final\",\"value\":-1}",
                "{\"kind\":\"final\",\"value\":4294967296}", "{\"kind\":\"accepted\",\"value\":1}",
                "{\"kind\":\"final\",\"kind\":\"final\",\"value\":0}",
                "{\"kind\":\"final\",\"value\":0} {}"))
                requireException(IOException.class, () -> codec.readResponse(json.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
