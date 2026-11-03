package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.CargoCompilerRequest;
import com.holdmyspot.codexunleashed.scheduler.CargoMemoryEstimates;
import com.holdmyspot.codexunleashed.scheduler.CargoMemoryHistory;
import com.holdmyspot.codexunleashed.scheduler.CargoWorkIdentity;
import com.holdmyspot.codexunleashed.scheduler.Controller;
import com.holdmyspot.codexunleashed.scheduler.LinuxBorrowedStreams;
import com.holdmyspot.codexunleashed.scheduler.LinuxCargoProtocol;
import com.holdmyspot.codexunleashed.scheduler.LinuxCargoRegistration;
import com.holdmyspot.codexunleashed.scheduler.LinuxProcessTree;
import com.holdmyspot.codexunleashed.scheduler.Policy;
import com.holdmyspot.codexunleashed.secrets.LinuxCargoRegistrationSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;

/**
 * Checks sample eligibility and later registration predictions through the production estimator.
 */
public final class CargoMemoryEstimatesTest
{
    /**
     * Creates independent estimate checks.
     */
    public CargoMemoryEstimatesTest() {}

    /**
     * Successful completion publishes only the greatest accepted complete-batch sample for later equivalent work.
     * @throws IOException if fixture setup, sampling or cleanup fails
     */
    @Test
    public void successfulCompletionSeedsEquivalentRegistration() throws IOException
    {
        Path root = Files.createTempDirectory("cargo-estimates-");
        try
        {
            Path source = Files.createDirectory(root.resolve("source"));
            Path toolchain = Files.createDirectory(root.resolve("toolchain"));
            Path compiler = Files.writeString(toolchain.resolve("rustc"), "compiler");
            Files.writeString(source.resolve("main.rs"), "fn main() {}\n");
            Path history = Files.createDirectory(root.resolve("history"));
            CargoWorkIdentity identities = new CargoWorkIdentity(source, List.of(), toolchain);
            SampleSource samples = new SampleSource();
            CargoMemoryEstimates estimates = new CargoMemoryEstimates(samples, new CargoMemoryHistory(history), identities);
            CargoCompilerRequest cold = request(source, compiler);
            requireThat(estimates.register(cold).peakBytes() == null, "coldUnknown").isTrue();
            Policy.Job running = new Policy.Job(cold.name(), Policy.State.RUNNING, 0, null, 0, 2L, null, 0);
            estimates.readHost();
            estimates.readResidentBytes(running);
            estimates.acceptReadings();
            samples.resident = 10000;
            estimates.readHost();
            estimates.readResidentBytes(running);
            estimates.completed(cold.name(), 2, true);
            CargoMemoryEstimates next = new CargoMemoryEstimates(new SampleSource(), new CargoMemoryHistory(history), identities);
            requireThat(next.register(request(source, compiler)).peakBytes(), "completedAcceptedSamplePrediction").isEqualTo(200L);
        }
        finally { remove(root); }
    }

    /**
     * Unknown work from a compiler that already completed measured work in this invocation receives a provisional
     * estimate of twice that compiler's greatest accepted sample, so a cold build measures one unit per compiler
     * exclusively instead of every unit. Work from an unmeasured compiler, and work after only failed attempts,
     * stays unknown.
     * @throws IOException if fixture setup, sampling or cleanup fails
     */
    @Test
    public void completedCompilerSeedsProvisionalEstimateForUnknownWork() throws IOException
    {
        Path root = Files.createTempDirectory("cargo-provisional-");
        try
        {
            Path source = Files.createDirectory(root.resolve("source"));
            Path toolchain = Files.createDirectory(root.resolve("toolchain"));
            Path compiler = Files.writeString(toolchain.resolve("rustc"), "compiler");
            Path linker = Files.writeString(toolchain.resolve("cc"), "linker");
            Files.writeString(source.resolve("main.rs"), "fn main() {}\n");
            Files.writeString(source.resolve("lib.rs"), "pub fn f() {}\n");
            Files.writeString(source.resolve("other.rs"), "pub fn g() {}\n");
            Path history = Files.createDirectory(root.resolve("history"));
            CargoWorkIdentity identities = new CargoWorkIdentity(source, List.of(), toolchain);
            SampleSource samples = new SampleSource();
            CargoMemoryEstimates estimates = new CargoMemoryEstimates(samples, new CargoMemoryHistory(history), identities);
            CargoCompilerRequest failed = request(source, compiler, "other.rs");
            requireThat(estimates.register(failed).peakBytes() == null, "firstUnknown").isTrue();
            Policy.Job failing = new Policy.Job(failed.name(), Policy.State.RUNNING, 0, null, 0, 1L, null, 0);
            estimates.readHost();
            estimates.readResidentBytes(failing);
            estimates.acceptReadings();
            estimates.completed(failed.name(), 1, false);
            CargoCompilerRequest cold = request(source, compiler, "main.rs");
            requireThat(estimates.register(cold).peakBytes() == null, "failedAttemptSeedsNothing").isTrue();
            Policy.Job running = new Policy.Job(cold.name(), Policy.State.RUNNING, 0, null, 0, 2L, null, 0);
            estimates.readHost();
            estimates.readResidentBytes(running);
            estimates.acceptReadings();
            estimates.completed(cold.name(), 2, true);
            requireThat(estimates.register(request(source, compiler, "lib.rs")).peakBytes(), "provisionalEstimate").
                isEqualTo(400L);
            requireThat(estimates.register(request(source, linker, "lib.rs")).peakBytes() == null, "otherCompilerUnknown").
                isTrue();
        }
        finally { remove(root); }
    }

    /**
     * Failed, aborted, incomplete and changed-input work cannot publish completed training samples.
     * @throws IOException if fixture setup or cleanup fails
     */
    @Test
    public void rejectsIneligibleTrainingSamples() throws IOException
    {
        for (String condition : List.of("aborted", "unaccepted", "read-failed", "accept-failed", "inputs-changed"))
        {
            Path root = Files.createTempDirectory("cargo-ineligible-");
            try
            {
                Path source = Files.createDirectory(root.resolve("source"));
                Path toolchain = Files.createDirectory(root.resolve("toolchain"));
                Path compiler = Files.writeString(toolchain.resolve("rustc"), "compiler");
                Path input = Files.writeString(source.resolve("main.rs"), "fn main() {}\n");
                Path history = Files.createDirectory(root.resolve("history"));
                CargoWorkIdentity identities = new CargoWorkIdentity(source, List.of(), toolchain);
                SampleSource samples = new SampleSource();
                CargoMemoryEstimates estimates = new CargoMemoryEstimates(samples, new CargoMemoryHistory(history), identities);
                CargoCompilerRequest request = request(source, compiler);
                estimates.register(request);
                Policy.Job running = new Policy.Job(request.name(), Policy.State.RUNNING, 0, null, 0, 2L, null, 0);
                estimates.readHost();
                estimates.readResidentBytes(running);
                if (condition.equals("read-failed"))
                {
                    samples.failRead = true;
                    requireException(IOException.class, () -> estimates.readResidentBytes(running));
                }
                if (condition.equals("accept-failed")) samples.failAcceptance = true;
                if (condition.equals("accept-failed")) requireException(IOException.class, estimates::acceptReadings);
                else if (!condition.equals("unaccepted")) estimates.acceptReadings();
                if (condition.equals("inputs-changed")) Files.writeString(input, "changed source");
                estimates.completed(request.name(), 2, !condition.equals("aborted"));
                Files.writeString(input, "fn main() {}\n");
                CargoMemoryEstimates next = new CargoMemoryEstimates(new SampleSource(), new CargoMemoryHistory(history), identities);
                requireThat(next.register(request(source, compiler)).peakBytes() == null, condition + "RetainsUnknown").isTrue();
            }
            finally { remove(root); }
        }
    }

    /**
     * A warm native-authenticated registration obtains its prediction from completed production history.
     * @throws IOException if a valid owned fixture cannot be sampled or cleaned
     */
    @Test
    public void authenticationConsultsCompletedHistory() throws IOException
    {
        Path root = Files.createTempDirectory("cargo-registration-history-");
        try
        {
            Path source = Files.createDirectory(root.resolve("source"));
            Path toolchain = Files.createDirectory(root.resolve("toolchain"));
            Path compiler = Files.writeString(toolchain.resolve("rustc"), "compiler");
            Files.writeString(source.resolve("main.rs"), "fn main() {}\n");
            Path history = Files.createDirectory(root.resolve("history"));
            CargoWorkIdentity identities = new CargoWorkIdentity(source, List.of(), toolchain);
            CargoMemoryEstimates estimates = new CargoMemoryEstimates(new SampleSource(), new CargoMemoryHistory(history), identities);
            LinuxBorrowedStreams.Peer wrapper = new LinuxBorrowedStreams.Peer(71, 81, 1000, 1000);
            LinuxBorrowedStreams.Peer cargo = new LinuxBorrowedStreams.Peer(61, 91, 1000, 1000);
            Path java = root.resolve("java");
            List<String> prefix = List.of(java.toString(), "Wrapper");
            List<String> command = List.of(compiler.toString(), "main.rs");
            Map<String, String> environment = Map.of("LANG", "C");
            LinuxProcessTree.Launch launch = new LinuxProcessTree.Launch(root, root, java, root.resolve("classes"),
                command, environment, source, 1000, 1000, LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC);
            LinuxCargoRegistration.Configuration configuration = new LinuxCargoRegistration.Configuration(root.resolve("cargo"),
                java, compiler, prefix, launch, Map.of());
            LinuxCargoRegistration.Observer nativeObservations = new LinuxCargoRegistration.Observer()
            {
                @Override public void requireLive(LinuxBorrowedStreams.Peer peer) {}
                @Override public long parent(long pid) { return cargo.pid(); }
                @Override public boolean sameFile(Path observed, Path expected) { return true; }
                @Override public List<String> arguments(long pid)
                {
                    java.util.ArrayList<String> arguments = new java.util.ArrayList<>(prefix);
                    arguments.addAll(command);
                    return arguments;
                }
                @Override public Map<String, String> environment(long pid) { return environment; }
            };
            var register = new LinuxCargoProtocol.Register(source, command, environment);
            CargoCompilerRequest cold = LinuxCargoRegistrationSecrets.getLinuxCargoRegistrationAccess().create(configuration, cargo, nativeObservations).
                authenticate(wrapper, register);
            estimates.register(cold);
            estimates.readHost();
            estimates.readResidentBytes(new Policy.Job(cold.name(), Policy.State.RUNNING, 0, null, 0, 2L, null, 0));
            estimates.acceptReadings();
            estimates.completed(cold.name(), 2, true);
            CargoMemoryEstimates next = new CargoMemoryEstimates(new SampleSource(), new CargoMemoryHistory(history), identities);
            CargoCompilerRequest warm = LinuxCargoRegistrationSecrets.getLinuxCargoRegistrationAccess().create(configuration, cargo, nativeObservations, next).
                authenticate(wrapper, register);
            requireThat(warm.peakBytes(), "authenticatedProductionEstimate").isEqualTo(200L);
        }
        finally { remove(root); }
    }

    /**
     * Creates immutable authenticated-data inputs with a fresh native action identity.
     * @param source source working directory
     * @param compiler exact toolchain executable
     * @return authenticated-data request model
     */
    private static CargoCompilerRequest request(Path source, Path compiler)
    {
        return request(source, compiler, "main.rs");
    }

    /**
     * Creates immutable authenticated-data inputs for one source file with a fresh native action identity.
     * @param source source working directory
     * @param compiler exact toolchain executable
     * @param file source file argument
     * @return authenticated-data request model
     */
    private static CargoCompilerRequest request(Path source, Path compiler, String file)
    {
        return new CargoCompilerRequest(UUID.randomUUID(), new CargoCompilerRequest.Wrapper(71, 81, 61, 91),
            List.of(compiler.toString(), file), source, Map.of("LANG", "C"), null);
    }

    /**
     * Removes only one test's owned content boundary.
     * @param root owned fixture root
     * @throws IOException if cleanup fails
     */
    private static void remove(Path root) throws IOException
    {
        try (Stream<Path> paths = Files.walk(root))
        {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    /**
     * Models Controller.Measurements' complete-batch inputs; production decides which samples become reusable.
     */
    private static final class SampleSource implements Controller.Measurements
    {
        /** Residency in bytes returned by the next modeled native observation. */
        private long resident = 200;
        /** Indicates whether the modeled native residency operation fails. */
        private boolean failRead;
        /** Indicates whether final native batch verification fails. */
        private boolean failAcceptance;
        /**
         * Creates one independent measurement source.
         */
        private SampleSource() {}
        @Override public Controller.Memory readHost() { return new Controller.Memory(100000, 0, false); }
        @Override public long readResidentBytes(Policy.Job job) throws IOException
        {
            if (failRead) throw new IOException("Modeled native residency is unavailable");
            return resident;
        }
        @Override public void acceptReadings() throws IOException
        {
            if (failAcceptance) throw new IOException("Modeled final authority verification failed");
        }
    }
}
