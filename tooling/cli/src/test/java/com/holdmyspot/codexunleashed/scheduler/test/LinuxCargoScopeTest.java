package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxCargoScope;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.util.Arrays;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises scoped Cargo dependencies and their borrowed tooling lifetime.
 */
public final class LinuxCargoScopeTest
{
    /**
     * Creates isolated scope checks.
     */
    public LinuxCargoScopeTest() {}

    /**
     * A scope shares its strict mapper, isolates other scopes and borrows supplied tooling.
     */
    @Test
    public void sharesOnlyWithinTheCargoScope()
    {
        try (var tooling = new DefaultToolingScope(); var first = new LinuxCargoScope(tooling);
            var second = new LinuxCargoScope(tooling))
        {
            requireThat(first.tooling() == tooling, "borrowedTooling").isTrue();
            requireThat(first.json() == first.json(), "sharedParser").isTrue();
            requireThat(first.json() == second.json(), "crossScopeParser").isFalse();
            requireThat(first.json() == tooling.getJsonMapper(), "distinctParserConfiguration").isFalse();
        }
    }

    /**
     * Capabilities are independent 256-bit values returned in caller-owned arrays.
     */
    @Test
    public void mintsFreshCapabilities()
    {
        try (var tooling = new DefaultToolingScope(); var scope = new LinuxCargoScope(tooling))
        {
            byte[] first = scope.capability();
            byte[] second = scope.capability();
            requireThat(first.length, "capabilityBytes").isEqualTo(32);
            requireThat(second.length, "secondCapabilityBytes").isEqualTo(32);
            requireThat(first == second, "sharedArray").isFalse();
            requireThat(Arrays.equals(first, second), "reusedCapability").isFalse();
        }
    }

    /**
     * Closure is idempotent, prevents all acquisition and retains borrowed tooling ownership.
     */
    @Test
    public void closesItsServicesWithoutClosingTooling()
    {
        try (var tooling = new DefaultToolingScope())
        {
            var scope = new LinuxCargoScope(tooling);
            try (scope)
            {
                scope.json();
                scope.capability();
                scope.close();
                scope.close();
                requireThat(scope.isClosed(), "closed").isTrue();
                requireException(IllegalStateException.class, scope::tooling);
                requireException(IllegalStateException.class, scope::json);
                requireException(IllegalStateException.class, scope::capability);
                requireThat(tooling.isClosed(), "borrowedToolingClosed").isFalse();
            }
            requireException(NullPointerException.class, () -> new LinuxCargoScope(null));
        }
    }
}
