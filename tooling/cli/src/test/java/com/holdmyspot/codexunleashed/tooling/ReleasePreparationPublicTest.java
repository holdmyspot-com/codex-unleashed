package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Exercises the actual public command routing before any native acquisition or administrator execution. */
public final class ReleasePreparationPublicTest
{
    /** Creates independent public-boundary checks. */
    public ReleasePreparationPublicTest() {}

    /** Native preparation is routed and validates its explicit argument contract before acquiring bytes. */
    @Test public void routesPreparationToArgumentGuard()
    {
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(diagnostics, true, StandardCharsets.UTF_8))
        {
            int status = Main.run(new String[] {"prepare-release-native"}, new ByteArrayInputStream(new byte[0]), stream, stream);
            requireThat(status, "actualPreparationGuardStatus").isEqualTo(1);
            requireThat(diagnostics.toString(StandardCharsets.UTF_8).contains("Expected fresh absolute output"),
                "actualPreparationDiagnostic").isTrue();
        }
    }

    /** Installation validation rejects a caller-selected path before an installed executable receives authority. */
    @Test public void routesValidationToProtectedNamespaceGuard()
    {
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(diagnostics, true, StandardCharsets.UTF_8))
        {
            int status = Main.run(new String[] {"validate-release-installation", "/caller-mutable-installation"},
                new ByteArrayInputStream(new byte[0]), stream, stream);
            requireThat(status, "actualInstallationGuardStatus").isEqualTo(1);
            requireThat(diagnostics.toString(StandardCharsets.UTF_8).contains("protected /var/lib or /Library scheduler child"),
                "actualInstallationDiagnostic").isTrue();
        }
    }
}
