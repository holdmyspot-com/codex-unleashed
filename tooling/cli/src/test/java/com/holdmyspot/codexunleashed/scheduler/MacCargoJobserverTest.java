package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises invocation flags, whole-attempt lifetime and partial cleanup with the contract's FIFO boundary.
 * The in-memory endpoint models native-confirmed byte effects; these cases do not establish Darwin FIFO behavior.
 */
public final class MacCargoJobserverTest
{
    /**
     * Creates independently owned method fixtures.
     */
    public MacCargoJobserverTest() {}

    /**
     * Publishes one immutable environment using exactly the created FIFO and rejects a second invocation.
     * @throws IOException if fixture filesystem operations fail
     */
    @Test public void publishesExactlyOneInvocation() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.open();
            fixture.lease = fixture.owner.retainCargo(Map.of("EXPLICIT", "value"));
            Map<String, String> environment = fixture.lease.environment();
            String auth = "fifo:" + fixture.provider.endpoint.path;
            String flags = "-j --jobserver-fds=" + auth + " --jobserver-auth=" + auth;
            requireThat(environment.get("EXPLICIT"), "explicitEnvironment").isEqualTo("value");
            for (String name : List.of("CARGO_MAKEFLAGS", "MAKEFLAGS", "MFLAGS"))
                requireThat(environment.get(name), name).isEqualTo(flags);
            requireException(UnsupportedOperationException.class, () -> environment.put("x", "y"));
            requireException(IllegalStateException.class, () -> fixture.owner.retainCargo(Map.of()));
            requireThat(fixture.provider.jobs, "publishedNativeCapacity").isEqualTo(3);
        }
    }

    /**
     * An empty inherited make variable is rejected without consuming the one invocation lease.
     * @throws IOException if fixture operations fail
     */
    @Test public void rejectsInheritedFlagsBeforeConsumingLease() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.open();
            for (String name : List.of("CARGO_MAKEFLAGS", "MAKEFLAGS", "MFLAGS"))
                requireException(IllegalArgumentException.class, () -> fixture.owner.retainCargo(Map.of(name, "")));
            fixture.lease = fixture.owner.retainCargo(Map.of());
            requireThat(fixture.lease.environment().size(), "ownedFlagsOnly").isEqualTo(3);
        }
    }

    /**
     * Whole-attempt ownership blocks invocation release and FIFO destruction until exact bytes are reclaimed.
     * @throws IOException if valid token transfers or fixture cleanup fail
     */
    @Test public void liveAttemptPreventsFifoDestruction() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.open();
            fixture.lease = fixture.owner.retainCargo(Map.of());
            LinuxCargoTokens.Attempt attempt = fixture.retainAttempt();
            fixture.provider.endpoint.bytes.add(165);
            requireThat(attempt.tryBorrow().orElseThrow(), "borrowedOpaqueByte").isEqualTo(165);
            requireException(IllegalStateException.class, fixture.lease::close);
            requireException(IllegalStateException.class, fixture.owner::close);
            requireThat(fixture.provider.endpoint.closes, "noPrematureClose").isEqualTo(0);
            requireThat(fixture.provider.endpoint.bytes.isEmpty(), "retainedWhileLive").isTrue();

            attempt.close();
            requireThat(fixture.provider.endpoint.bytes.getFirst(), "reclaimedExactByte").isEqualTo(165);
            fixture.lease.close();
            fixture.owner.close();
            requireThat(fixture.provider.endpoint.closes, "closedOnce").isEqualTo(1);
            fixture.owner.close();
            requireThat(fixture.provider.endpoint.closes, "noRepeatClose").isEqualTo(1);
        }
    }

    /**
     * Retains a partially acquired endpoint when native creation fails and removes it only through normal close.
     * @throws IOException if fixture setup or cleanup fails
     */
    @Test public void failedAcquisitionRetainsPartialEndpoint() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.provider.failCreation = true;
            requireException(IOException.class, fixture::open);
            requireThat(Files.exists(fixture.provider.endpoint.path), "partialRetained").isTrue();
            requireException(IllegalStateException.class, () -> fixture.owner.retainCargo(Map.of()));
            requireException(IllegalStateException.class, fixture::open);
            requireThat(fixture.provider.creates, "singleNativeAttempt").isEqualTo(1);
            fixture.owner.close();
            requireThat(Files.exists(fixture.provider.endpoint.path), "partialRemoved").isFalse();
            requireThat(fixture.provider.endpoint.closes, "onePartialDestruction").isEqualTo(1);
        }
    }

    /**
     * Invalid capacity and relative paths fail before any namespace or native acquisition.
     * @throws IOException if fixture creation or cleanup fails
     */
    @Test public void validatesBeforeExternalEffects() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            requireException(IllegalArgumentException.class, () -> fixture.owner.open(fixture.parent, 1, 1, 0));
            requireException(IllegalArgumentException.class, () -> fixture.owner.open(fixture.parent, 1, 1, 1025));
            requireException(IllegalArgumentException.class, () -> fixture.owner.open(Path.of("relative"), 1, 1, 1));
            requireThat(fixture.provider.directories, "noInvalidDirectoryEffects").isEqualTo(0);
            requireThat(fixture.provider.creates, "noInvalidNativeEffects").isEqualTo(0);
        }
    }

    /**
     * Owns one test's real temporary namespace and all logical capabilities.
     */
    private static final class Fixture implements AutoCloseable
    {
        /** Fresh namespace cleaned by this fixture even on assertion failure. */
        private final Path parent = Files.createTempDirectory("mac-cargo-jobserver-test-");
        /** Controlled native boundary; the production jobserver owns all policy decisions. */
        private final Provider provider = new Provider();
        /** Resource-free owner retained before every filesystem or native effect. */
        private final MacCargoJobserver owner = MacCargoJobserver.SharedSecrets.create(provider);
        /** Logical attempts retained for assertion-failure cleanup. */
        private final List<LinuxCargoTokens.Attempt> attempts = new ArrayList<>();
        /** Invocation borrow retained for fixture cleanup. */
        private MacCargoJobserver.CargoLease lease;

        /**
         * Creates an isolated root for one test.
         * @throws IOException if temporary directory creation fails
         */
        private Fixture() throws IOException {}

        /**
         * Performs the normal acquisition with controlled native results.
         * @throws IOException if acquisition fails
         */
        private void open() throws IOException { owner.open(parent, 1, 1, 3); }

        /**
         * Retains a real ledger capability and registers immediate cleanup ownership.
         * @return fresh attempt capability
         * @throws IOException if ledger state is uncertain
         */
        private LinuxCargoTokens.Attempt retainAttempt() throws IOException
        {
            LinuxCargoTokens.Attempt result = lease.retainAttempt();
            attempts.add(result);
            return result;
        }

        @Override public void close() throws IOException
        {
            try
            {
                for (LinuxCargoTokens.Attempt attempt : attempts) attempt.close();
                if (lease != null) lease.close();
                owner.close();
            }
            finally { Files.delete(parent); }
        }
    }

    /**
     * Models the native producer's retained partial acquisition, with actual private fixture files for cleanup effects.
     */
    private static final class Provider implements MacCargoJobserver.Provider
    {
        /** Endpoint retained before the controlled creation failure. */
        private Endpoint endpoint;
        /** Indicates native creation returns a failure after retaining its partial endpoint. */
        private boolean failCreation;
        /** Number of namespace creations requested by production. */
        private int directories;
        /** Number of native creation attempts requested by production. */
        private int creates;
        /** Capacity production passes to the actual native seed boundary. */
        private int jobs;

        /**
         * Creates a resource-free boundary.
         */
        private Provider() {}

        @Override public Path createDirectory(Path parent) throws IOException
        {
            ++directories;
            return Files.createTempDirectory(parent, "owned-");
        }

        @Override public void publishDirectory(Path directory, int gid) {}

        @Override public MacCargoJobserver.Endpoint create(Path path, int uid, int gid, int jobs) throws IOException
        {
            ++creates;
            this.jobs = jobs;
            endpoint = new Endpoint(path);
            Files.createFile(path);
            if (failCreation) throw new IOException("Controlled partial native FIFO acquisition");
            return endpoint;
        }

        @Override public List<MacCargoJobserver.Endpoint> retained()
        {
            if (endpoint == null) return List.of();
            return List.of(endpoint);
        }
    }

    /**
     * Models documented confirmed single-byte effects; only the production ledger decides reservation and return.
     */
    private static final class Endpoint implements MacCargoJobserver.Endpoint
    {
        /** Exact fixture file representing the native endpoint's retained unlink authority. */
        private final Path path;
        /** Available opaque bytes returned only by actual ledger operations. */
        private final ArrayDeque<Integer> bytes = new ArrayDeque<>();
        /** Number of destructions requested after production ownership barriers. */
        private int closes;

        /**
         * Retains a fresh fixture path before its filesystem creation.
         * @param path native endpoint's exact fixture name
         */
        private Endpoint(Path path) { this.path = path; }

        @Override public OptionalInt tryRead()
        {
            if (bytes.isEmpty()) return OptionalInt.empty();
            return OptionalInt.of(bytes.removeFirst());
        }

        @Override public void write(byte token) { bytes.add(Byte.toUnsignedInt(token)); }
        @Override public MacCargoNative.FifoIdentity identity() { return new MacCargoNative.FifoIdentity(1, 2); }
        @Override public void close() throws IOException { ++closes; Files.delete(path); }
    }
}
