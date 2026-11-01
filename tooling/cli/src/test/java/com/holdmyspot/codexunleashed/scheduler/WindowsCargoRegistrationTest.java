package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies admission decisions against independently retained native observations.
 */
public final class WindowsCargoRegistrationTest
{
    /**
     * Creates independent method-owned tests.
     */
    public WindowsCargoRegistrationTest() {}

    /**
     * Rejects a wrapper-supplied peak rather than converting it into scheduling authority.
     */
    @Test public void rejectsUntrustedDemand()
    {
        var fixture = new Fixture();
        var request = fixture.request(1L);
        requireException(IOException.class, () -> WindowsCargoRegistration.authenticate(request, fixture, fixture.configuration()));
        requireThat(fixture.observations, "nativeChecksBeforeRejectedDemand").isEqualTo(0);
    }

    /**
     * Accepts exact independently observed launch facts and preserves unknown demand.
     * @throws IOException if the independent native authority is rejected
     */
    @Test public void authenticatesExactNativeLaunch() throws IOException
    {
        var fixture = new Fixture();
        var request = fixture.request(null);
        requireThat(WindowsCargoRegistration.authenticate(request, fixture, fixture.configuration()) == request,
            "unchangedAuthenticatedRequest").isTrue();
        requireThat(fixture.observations, "liveChecksAroundAuthentication").isEqualTo(2);
    }

    /**
     * Treats same-PID wrong-birth and forged launch environment as different native invocations.
     */
    @Test public void rejectsWrongBirthAndEnvironment()
    {
        var fixture = new Fixture();
        fixture.nativeBirth = 99;
        requireException(IOException.class, () -> WindowsCargoRegistration.authenticate(fixture.request(null), fixture, fixture.configuration()));
        fixture.nativeBirth = 11;
        fixture.nativeEnvironment = Map.of("PATH", "forged");
        requireException(IOException.class, () -> WindowsCargoRegistration.authenticate(fixture.request(null), fixture, fixture.configuration()));
    }

    /**
     * Refuses an exited or uncontained source before publishing a compiler registration.
     */
    @Test public void rejectsLostNativeAuthority()
    {
        var fixture = new Fixture();
        fixture.live = false;
        requireException(IOException.class, () -> WindowsCargoRegistration.authenticate(fixture.request(null), fixture, fixture.configuration()));
    }

    /**
     * Supplies controlled native facts without fabricating an original process handle.
     */
    private static final class Fixture implements WindowsCargoRegistration.Source
    {
        /** Native liveness observations made by authentication. */
        private int observations;
        /** Actual retained-process creation FILETIME used to detect PID reuse. */
        private long nativeBirth = 11;
        /** Whether the original process remains live in the exact Cargo Job. */
        private boolean live = true;
        /** Actual JVM environment observed by the creation producer. */
        private Map<String, String> nativeEnvironment = Map.of("PATH", "trusted");
        /** Absolute directory selected independently by the fixture. */
        private final Path directory = Path.of("").toAbsolutePath().normalize();
        /**
         * Creates a resource-free native-facts fixture.
         */
        private Fixture() {}
        /**
         * Creates an untrusted request using the permitted launch.
         * @param peak wrapper-provided peak or null
         * @return request awaiting native authentication
         */
        private CargoCompilerRequest request(Long peak)
        {
            return new CargoCompilerRequest(UUID.randomUUID(), new CargoCompilerRequest.Wrapper(10, 11, 20, 21),
                List.of(directory.resolve("rustc.exe").toString(), "", "a\\b"), directory, Map.of("PATH", "trusted"), peak);
        }
        /**
         * Supplies trusted caller boundaries without acquiring resources.
         * @return immutable compiler and Cargo boundaries
         */
        private WindowsCargoRegistration.Configuration configuration()
        {
            return new WindowsCargoRegistration.Configuration(directory.resolve("rustc.exe"), 1, 2, Map.of("PATH", "trusted"));
        }
        @Override public long pid() { return 10; }
        @Override public long birth() { return nativeBirth; }
        @Override public long parentPid() { return 20; }
        @Override public long parentBirth() { return 21; }
        @Override public long cargoPid() { return 1; }
        @Override public long cargoBirth() { return 2; }
        @Override public List<String> compilerArguments() { return request(null).arguments(); }
        @Override public Path directory() { return directory; }
        @Override public Map<String, String> environment() { return nativeEnvironment; }
        @Override public void requireLive() throws IOException
        {
            observations++;
            if (!live) throw new IOException("Original process is exited or outside Cargo Job");
        }
    }
}
