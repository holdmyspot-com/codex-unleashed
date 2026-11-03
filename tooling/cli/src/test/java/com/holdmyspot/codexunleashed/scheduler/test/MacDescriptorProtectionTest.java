package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.MacDescriptorProtection;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.util.List;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Exercises generation admission with per-method scoped parsers and independent copied observations. */
public final class MacDescriptorProtectionTest
{
    /** Creates stateless, independently executable protocol tests. */
    public MacDescriptorProtectionTest() {}

    /** Rejects a genuine send envelope copied from another held image generation. */
    @Test public void rejectsAnotherImageChallenge()
    {
        try (var scope = new DefaultToolingScope())
        {
            String frame = "{\"type\":\"send\",\"payload\":{\"kind\":\"mac-descriptor-ready\","
                + "\"marker\":\"previous\",\"pid\":10,\"registry\":\"0x1000\",\"mutations\":0,"
                + "\"creators\":1,\"closers\":1,\"inheritedDescriptors\":[]}}";
            requireException(IOException.class,
                () -> MacDescriptorProtection.ready(scope.getLeaderJsonMapper(), frame, 10, "current"));
        }
    }

    /** Rejects duplicate numeric identities even when their observed native types disagree. */
    @Test public void rejectsAmbiguousDescriptorIdentity()
    {
        requireException(IllegalArgumentException.class, () -> new MacDescriptorProtection.Inventory(362, 5,
            List.of(new MacDescriptorProtection.Descriptor(7, 1), new MacDescriptorProtection.Descriptor(7, 5))));
    }

    /** Keeps ordinary pipes and files outside the authenticated runtime queue observation. */
    @Test public void preservesOrdinaryDescriptorKinds()
    {
        var inventory = new MacDescriptorProtection.Inventory(362, 5,
            List.of(new MacDescriptorProtection.Descriptor(9, 5), new MacDescriptorProtection.Descriptor(4, 1),
                new MacDescriptorProtection.Descriptor(8, 5)));
        requireThat(inventory.queues(), "observedQueues").isEqualTo(List.of(8, 9), "expected");
    }
}
