package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.WindowsCargoScope;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies invocation parser sharing and acquisition lifetime without native resources.
 */
public final class WindowsCargoScopeTest
{
    /**
     * Creates independently owned scope checks.
     */
    public WindowsCargoScopeTest() {}

    /**
     * Closing the invocation prevents new acquisition while keeping borrowed tooling open.
     */
    @Test public void closesOnlyOwnedServices()
    {
        try (DefaultToolingScope tooling = new DefaultToolingScope(); WindowsCargoScope scope = new WindowsCargoScope(tooling))
        {
            requireThat(scope.json() == scope.json(), "sharedMapper").isTrue();
            requireThat(scope.capability().length, "capabilityBytes").isEqualTo(32);
            scope.close();
            requireException(IllegalStateException.class, scope::json);
            requireException(IllegalStateException.class, scope::capability);
            requireThat(tooling.isClosed(), "toolingClosed").isFalse();
        }
    }
}
