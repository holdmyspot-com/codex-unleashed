package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.CargoCompilerRequest;
import com.holdmyspot.codexunleashed.scheduler.CargoMemoryEstimates;
import com.holdmyspot.codexunleashed.scheduler.CargoSession;
import com.holdmyspot.codexunleashed.scheduler.Controller;
import com.holdmyspot.codexunleashed.scheduler.JobQueue;
import com.holdmyspot.codexunleashed.scheduler.MacAttempts;
import com.holdmyspot.codexunleashed.scheduler.MacCargoInvocation;
import com.holdmyspot.codexunleashed.scheduler.MacCargoRegistration;
import com.holdmyspot.codexunleashed.scheduler.MacCargoScope;
import com.holdmyspot.codexunleashed.scheduler.MacNativeModules;
import com.holdmyspot.codexunleashed.scheduler.MacWorkloadNative;
import com.holdmyspot.codexunleashed.scheduler.Policy;
import com.holdmyspot.codexunleashed.scheduler.SchedulerCoordinator;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import org.testng.SkipException;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Runs explicitly selected, connected Darwin Cargo fixtures through the real native invocation and shared coordinator.
 * Each call owns fresh state, original descriptors, scopes, profiles and a foreign sentinel. Nothing runs by default.
 * Select with {@code mac.cargo.native.configuration=/absolute/config.json} and {@code mac.cargo.native.gate=D1|D2}.
 * Selecting either property without the other, an invalid configuration, or another OS fails instead of skipping.
 * <p>
 * The configuration supplies existing absolute {@code fixtureRoot}, {@code cargo}, {@code wrapper}, {@code rustc},
 * {@code toolchainRoot}, {@code workloadLibrary}, {@code helper}, {@code cargoLibrary}, {@code frida}; positive
 * {@code workloadUid}, {@code workloadGid}, {@code jobs}, {@code timeoutSeconds}, {@code executionTimeoutSeconds};
 * The hook script dylibs that {@link MacNativeModules#in(Path)} names must sit beside {@code workloadLibrary}.
 * nonnegative {@code headroomBytes}; a complete
 * {@code environment} object without inherited make variables; and {@code toolSha256} hashes keyed by each tool name.
 * D1 additionally requires the pinned toolchain's exact {@code errorStderrBase64}. D2 requires {@code d2Project}, a
 * real offline Cargo proc-macro source tree; pinned d2Ballast and d2MaxBallastBytes; and exact {@code d2StdoutBase64}/{@code d2StderrBase64}. The caller owns all ancestry,
 * installs immutable tools and authorizes only this fixture's fresh root. No home cache is used.
 * <p>
 * D1 compiles and runs a tiny real Cargo program, repeats warm, introduces a real compiler error, then repairs it.
 * D2 profiles genuine successful cold work, cleans generated output through Cargo, then requires actual allocation
 * denials and native retry controls. It never rewrites submissions, predicts constant demand or replaces measurements.
 * A missing trigger fails D2. Original raw bytes remain immutable; an aborted interval emitting bytes fails the gate.
 * <p>
 * Collection requires production retirement receipts carrying original eighteen-field native authority, published only
 * after actual native/image closure, token retirement, measurement removal and facade cleanup. Queue outcomes or PID
 * lists alone grant no collection proof. The fixture separately checks final opaque FIFO bytes and original root PID
 * absence without using those observations to control processes. Neither gate waives ordinary descriptor-close.
 */
public final class MacCargoNativeTest
{
    /** Creates a resource-free TestNG fixture; all mutable state is method-local. */
    public MacCargoNativeTest() {}

    /**
     * Executes only the requested native gate and preserves first failure, raw bytes and unresolved owners.
     * @throws Exception if explicit configuration, native execution, byte verification or a required gate fails
     */
    @Test
    public void connectedNativeGate() throws Exception
    {
        String selection = System.getProperty("mac.cargo.native.gate");
        String configuration = System.getProperty("mac.cargo.native.configuration");
        if (selection == null && configuration == null)
            throw new SkipException("Select both mac.cargo.native.configuration and mac.cargo.native.gate explicitly");
        if (selection == null || configuration == null)
            throw new IllegalArgumentException("Both native selection properties are required");
        if (!System.getProperty("os.name").equals("Mac OS X"))
            throw new UnsupportedOperationException("Explicit Mac Cargo native selection requires Darwin");
        switch (selection)
        {
            case "D1", "D2" -> { }
            default -> throw new IllegalArgumentException("Native gate must be exactly D1 or D2");
        }
        Path input = Path.of(configuration);
        if (!input.isAbsolute()) throw new IllegalArgumentException("Native configuration must be absolute");
        DefaultToolingScope tooling = new DefaultToolingScope();
        MacCargoScope cargoScope = new MacCargoScope(tooling);
        Fixture fixture = null;
        try
        {
            fixture = new Fixture(tooling, cargoScope, input, selection);
            fixture.run();
            fixture.closeSafeResources();
            cargoScope.close();
            tooling.close();
        }
        catch (Exception | Error failure)
        {
            if (fixture != null)
            {
                fixture.recordFailure(failure);
                if (fixture.safeToClose())
                {
                    try { fixture.closeSafeResources(); }
                    catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
                    if (fixture.safeToClose()) { cargoScope.close(); tooling.close(); }
                }
                failure.addSuppressed(new RetainedFixture(fixture));
            }
            else { cargoScope.close(); tooling.close(); }
            throw failure;
        }
    }

    /**
     * Retains uncertain invocation, descriptor and scoped ownership on the original constructing thread.
     * The marker grants no authority to kill a process or replay a close.
     */
    private static final class RetainedFixture extends IOException
    {
        /** Serialized marker format. */
        private static final long serialVersionUID = 1L;
        /** Original in-JVM owner; deliberately retained after native cleanup uncertainty. */
        private final transient Fixture fixture;

        /**
         * Retains the exact owner without retaining its primary throwable as a cause.
         * @param fixture original method-owned native resources
         */
        private RetainedFixture(Fixture fixture)
        {
            super("Native fixture evidence and unresolved ownership retained at " + fixture.root);
            this.fixture = fixture;
        }

        /**
         * Reports the retained owner namespace without performing another native observation or cleanup attempt.
         * @return original message and its retained owner path, or the serialized marker message
         */
        @Override public String getMessage()
        {
            return fixture == null ? super.getMessage() : super.getMessage() + "; retainedOwner=" + fixture.root;
        }
    }

    /** Owns one selected connected campaign and its immutable raw receipts. */
    private static final class Fixture
    {
        /** Borrowed tooling lifetime retained through every native owner. */
        private final ToolingScope tooling;
        /** Borrowed invocation scope retained through every native owner. */
        private final MacCargoScope cargoScope;
        /** Scoped mapper for configuration and raw JSON evidence. */
        private final JsonMapper json;
        /** Pinned explicit configuration. */
        private final JsonNode config;
        /** Exact selected terminal gate. */
        private final String gate;
        /** Fresh caller-authorized evidence and workload root; never recursively removed on failure. */
        private final Path root;
        /** Genuine Cargo workspace, source identity boundary. */
        private final Path project;
        /** Generated outputs explicitly excluded from reusable source identity. */
        private final Path target;
        /** Genuine completed successful profiles, outside the source/toolchain trees. */
        private final CargoMemoryEstimates.Settings history;
        /** Scoped Darwin descriptor operations; all descriptor owners remain reachable here. */
        private final Descriptors descriptors;
        /** Every original invocation retained before its first native effect. */
        private final List<MacCargoInvocation> invocations = new ArrayList<>();
        /** Every borrowed original stream and independent FIFO audit descriptor. */
        private final List<Integer> retainedDescriptors = new ArrayList<>();
        /** Descriptor numbers whose one consumed close had uncertain effects and must never be replayed. */
        private final List<Integer> uncertainDescriptorCloses = new ArrayList<>();
        /** Actual connected stage receipts in execution order. */
        private final List<Map<String, Object>> stages = new ArrayList<>();
        /** Original foreign process, outside native action coalitions. */
        private Process sentinel;
        /** Indicates its natural exit was collected from the original Process. */
        private boolean sentinelCollected;
        /** Explicit connected D2 workload controls, absent from ordinary D1 execution. */
        private RecoveryWorkload recovery;

        /**
         * Validates pinned input and creates only one fresh invocation-owned namespace.
         * @param tooling caller-retained scoped services
         * @param cargoScope caller-retained Cargo scope
         * @param configuration absolute existing configuration file
         * @param gate exact D1 or D2 selection
         * @throws IOException if input or filesystem preparation fails
         */
        private Fixture(ToolingScope tooling, MacCargoScope cargoScope, Path configuration, String gate)
            throws IOException
        {
            this.tooling = tooling;
            this.cargoScope = cargoScope;
            this.json = tooling.getJsonMapper();
            this.config = json.readTree(Files.readAllBytes(configuration));
            this.gate = gate;
            this.descriptors = new Descriptors(tooling.getNativeLinker());
            requireThat(descriptors.integer("geteuid"), "controllerUid").isEqualTo(0);
            Path parent = path("fixtureRoot");
            requireThat(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS), "ownedFixtureParent").isTrue();
            requireTrustedRoot(parent);
            for (String stage : List.of("clean", "warm", "error", "repair", "profile-cold", "cargo-clean", "denial-retry"))
                requireStagePathBudget(parent.resolve("m-99999999999999999999").resolve(stage));
            this.root = Files.createTempDirectory(parent, "m-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx--x--x")));
            Files.write(root.resolve("configuration-original.json"), Files.readAllBytes(configuration),
                StandardOpenOption.CREATE_NEW);
            pinTools();
            positive("workloadUid"); positive("workloadGid"); positive("jobs"); positive("timeoutSeconds");
            positive("executionTimeoutSeconds"); nonnegative("headroomBytes");
            if (gate.equals("D1")) binary("errorStderrBase64");
            else { binary("d2StdoutBase64"); binary("d2StderrBase64"); }
            requireThat(positive("jobs") <= 1024, "opaqueByteFixtureCapacity").isTrue();
            this.project = root.resolve("project");
            Files.createDirectory(project);
            if (gate.equals("D2")) copyProject(path("d2Project"), project);
            else createTinyProject();
            publishWorkloadTree(project);
            this.target = project.resolve("target");
            Path profiles = Files.createDirectory(root.resolve("profiles"));
            this.history = new CargoMemoryEstimates.Settings(profiles, project, List.of(target), path("toolchainRoot"));
            if (gate.equals("D2")) recovery = new RecoveryWorkload();
            write("initial.json", Map.of("gate", gate, "configurationSha256", sha(configuration),
                "root", root.toString(), "nativeCollectionGate", "unrun_requires_actual_production_retirement_receipts",
                "ordinaryDescriptorCloseGate", "failed_unwaived"));
            write("runtime-identities.json", Map.of("javaVersion", System.getProperty("java.version"),
                "javaRuntimeVersion", System.getProperty("java.runtime.version"), "osArch", System.getProperty("os.arch"),
                "javaExecutableSha256", sha(Path.of(System.getProperty("java.home")).resolve("bin/java")),
                "sentinelInterpreterRealPath", Path.of("/usr/bin/python3").toRealPath().toString(),
                "sentinelInterpreterSha256", sha(Path.of("/usr/bin/python3"))));
        }

        /**
         * Executes actual Cargo stages without inventing native acceptance at an unobservable boundary.
         * @throws Exception if a connected operation or required native acceptance gate fails
         */
        private void run() throws Exception
        {
            sentinel = new ProcessBuilder("/usr/bin/python3", "-c",
                "import sys; sys.stdin.buffer.read(); sys.exit(0)")
                .redirectOutput(root.resolve("sentinel.stdout").toFile())
                .redirectError(root.resolve("sentinel.stderr").toFile()).start();
            write("sentinel-started.json", Map.of("pid", sentinel.pid(), "originalProcessRetained", true,
                "alive", sentinel.isAlive(), "command", List.of("/usr/bin/python3", "stdin-EOF-natural-exit")));
            switch (gate)
            {
                case "D1" -> runD1();
                case "D2" -> runD2();
                default -> throw new IllegalStateException("Previously validated native gate changed");
            }
            requireThat(sentinel.isAlive(), "foreignSentinelSurvivedAllNativeControls").isTrue();
            collectSentinel();
            pinTools();
            write("connected-checks.json", Map.of("connectedChecks", "passed", "stages", stages,
                "nativeCollectionGate", "passed_genuine_production_retirement_receipts",
                "ordinaryDescriptorCloseGate", "failed_unwaived", "selectedGate", gate,
                "selectedNativeGate", "passed_connected_fixture_not_complete_checkpoint3"));
        }

        /**
         * Executes genuine clean, warm, compiler-error and repair invocations with unchanged original binary streams.
         * @throws Exception if real execution, original result, stream or token checks fail
         */
        private void runD1() throws Exception
        {
            byte[] input = new byte[] {0, 1, 127, (byte) 128, (byte) 255, 10};
            byte[] prefix = new byte[] {'c', 'a', 'r', 'g', 'o', 0, (byte) 255, 'D', '1', 10};
            byte[] output = Arrays.copyOf(prefix, prefix.length + input.length);
            System.arraycopy(input, 0, output, prefix.length, input.length);
            byte[] error = new byte[] {'s', 't', 'd', 'e', 'r', 'r', 0, (byte) 128, 'D', '1', 10};
            Trace clean = invoke("clean", "run", input, 0, output, error);
            requireThat(clean.completions.isEmpty(), "realColdCompilerAttemptsObserved").isFalse();
            invoke("warm", "run", input, 0, output, error);
            Files.writeString(project.resolve("src/main.rs"),
                "compile_error!(\"native-fixture-error\");\nfn main() {}\n", StandardCharsets.UTF_8);
            Trace broken = invoke("error", "run", input, 101, new byte[0], binary("errorStderrBase64"));
            requireThat(broken.completions.stream().anyMatch(c -> !c.outcome().aborted() &&
                c.outcome().result() != 0), "genuineCompilerErrorOutcome").isTrue();
            writeTinyMain();
            invoke("repair", "run", input, 0, output, error);
        }

        /**
         * Seeds only genuine completed demand, then requires actual allocation waits and original-order fresh retries.
         * @throws Exception if native triggering, collection order, output or original result checks fail
         */
        private void runD2() throws Exception
        {
            byte[] output = binary("d2StdoutBase64");
            byte[] error = binary("d2StderrBase64");
            Trace seed = invoke("profile-cold", "run", new byte[0], 0, output, error);
            requireThat(seed.completions.isEmpty(), "genuineSuccessfulColdWork").isFalse();
            requireThat(seed.memoryObservations.stream().filter(o -> o.status() == CargoMemoryEstimates.Status.RECORDED)
                .map(CargoMemoryEstimates.Observation::identity).distinct().count() >= 2,
                "genuineCompletedProfilesForConcurrentDifferentCompilerWork").isTrue();
            invoke("cargo-clean", "clean", new byte[0], 0, new byte[0], new byte[0]);
            Trace pressure = invoke("denial-retry", "run", new byte[0], 0, output, error);
            pressure.requireRecovery();
        }

        /**
         * Checks the longest generated native launch directories and controller socket before native acquisition.
         * The decimal temporary suffix reserves all twenty digits of an unsigned 64-bit random value.
         * @param stage absolute prospective invocation stage directory
         * @throws IOException if a generated path exceeds its Darwin native byte budget
         */
        private static void requireStagePathBudget(Path stage) throws IOException
        {
            for (Path directory : List.of(stage.resolve("cargo-state"),
                stage.resolve("attempts/compiler-99999999999999999999")))
                if (directory.toString().getBytes(StandardCharsets.UTF_8).length > 90)
                    throw new IOException("Configured fixture root exceeds the 90-byte native launch directory budget: " +
                        directory);
            Path socket = stage.resolve("endpoint/controller.sock");
            if (socket.toString().getBytes(StandardCharsets.UTF_8).length >= 104)
                throw new IOException("Configured fixture root exceeds the Darwin Unix socket pathname budget: " + socket);
        }

        /**
         * Runs one genuine production invocation, retaining raw observations and owners before their first effect.
         * @param name exclusive stage identity
         * @param operation real Cargo subcommand
         * @param input exact original standard input bytes
         * @param expectedStatus required genuine Cargo root result
         * @param expectedOutput exact original standard output bytes
         * @param expectedError exact original standard error bytes
         * @return transparent trace of authentic session and attempt operations
         * @throws Exception if execution, expected bytes, capacity or cleanup checks fail
         */
        private Trace invoke(String name, String operation, byte[] input, int expectedStatus,
            byte[] expectedOutput, byte[] expectedError) throws Exception
        {
            if (recovery != null) recovery.prepare(name);
            requireStagePathBudget(root.resolve(name));
            Path stage = Files.createDirectory(root.resolve(name));
            Path state = directory(stage.resolve("cargo-state"), "rwx------", 0, 0);
            Path scratch = directory(stage.resolve("attempts"), "rwx------", 0, 0);
            Path endpoint = directory(stage.resolve("endpoint"), "rwx--x---", 0, positive("workloadGid"));
            Files.setPosixFilePermissions(stage, PosixFilePermissions.fromString("rwx--x--x"));
            Path in = stage.resolve("stdin.bin"), out = stage.resolve("stdout.bin"), err = stage.resolve("stderr.bin");
            Files.write(in, input, StandardOpenOption.CREATE_NEW);
            Files.createFile(out); Files.createFile(err);
            int stdin = retain(descriptors.open(in, 0));
            int stdout = retain(descriptors.open(out, 1));
            int stderr = retain(descriptors.open(err, 1));
            Path socket = endpoint.resolve("controller.sock");
            if (socket.toString().getBytes(StandardCharsets.UTF_8).length >= 104)
                throw new IOException("Configured fixture root is too long for the Darwin Unix socket pathname");
            Map<String, String> environment = environment(socket);
            List<String> command = new ArrayList<>(List.of(path("cargo").toString(), operation, "--quiet", "--offline",
                "--color", "never", "--manifest-path", project.resolve("Cargo.toml").toString(),
                "--target-dir", target.toString()));
            var launch = new MacWorkloadNative.Launch(path("workloadLibrary"), path("helper"),
                MacNativeModules.in(path("workloadLibrary").getParent()), state, project,
                new MacWorkloadNative.Streams(stdin, stdout, stderr), command,
                environment.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList(),
                positive("workloadUid"), positive("workloadGid"));
            var registration = new MacCargoRegistration.Configuration(path("cargo"), path("wrapper"), path("rustc"),
                positive("workloadUid"), positive("workloadGid"), environment);
            var configuration = new MacCargoInvocation.Configuration(launch, registration, socket, path("cargoLibrary"),
                path("frida"), scratch, positive("jobs"), Duration.ofSeconds(positive("timeoutSeconds")));
            var invocation = new MacCargoInvocation(cargoScope, configuration, history);
            invocations.add(invocation);
            write(name + "-configuration.json", Map.of("command", command, "environment", environment,
                "originalDescriptors", List.of(stdin, stdout, stderr), "state", state.toString(),
                "attemptScratch", scratch.toString(), "endpoint", socket.toString()));
            invocation.start();
            Path fifo = findFifo(endpoint);
            Object inode = Files.getAttribute(fifo, "unix:ino", LinkOption.NOFOLLOW_LINKS);
            long device = ((Number) Files.getAttribute(fifo, "unix:dev", LinkOption.NOFOLLOW_LINKS)).longValue();
            int audit = retain(descriptors.open(fifo, 2 | 4 | 0x100 | 0x1000000));
            Trace trace = new Trace(invocation, stage, json, sentinel,
                Duration.ofSeconds(positive("executionTimeoutSeconds")),
                name.equals("denial-retry") ? recovery : null);
            if (trace.recovery != null) trace.recovery.fifoIdentity(device, ((Number) inode).longValue());
            var coordinator = new SchedulerCoordinator<Integer>(trace,
                new Policy.Budget(nonnegative("headroomBytes"), positive("jobs")), () ->
                {
                    trace.checkDeadline();
                    Thread.sleep(5);
                });
            trace.coordinator = coordinator;
            int status;
            try { status = coordinator.run(); }
            catch (Exception | Error failure)
            {
                try { trace.save(); }
                catch (IOException evidenceFailure) { failure.addSuppressed(evidenceFailure); }
                throw failure;
            }
            trace.save();
            requireThat(status, "genuineCargoRootStatus").isEqualTo(expectedStatus);
            requireThat(invocation.isClosed(), "invocationClosedBeforeBorrowedDescriptorsAndScopes").isTrue();
            requireThat(invocation.session().acceptedOutcomes(), "unchangedAcceptedNativeOutcomes")
                .isEqualTo(coordinator.acceptedAttempts());
            requireThat(Files.exists(fifo, LinkOption.NOFOLLOW_LINKS), "exactFifoUnlinkedAfterConsumers").isFalse();
            byte[] tokens = descriptors.drain(audit, positive("jobs"));
            int[] observed = new int[256], expected = new int[256];
            for (byte token : tokens) ++observed[Byte.toUnsignedInt(token)];
            for (int token = 0; token < positive("jobs") - 1; ++token) ++expected[token & 0xff];
            write(name + "-opaque-capacity.json", Map.of("fifo", fifo.toString(), "inode", inode,
                "bytesBase64", Base64.getEncoder().encodeToString(tokens), "observedHistogram", observed,
                "expectedHistogram", expected, "allNativeConsumersClosedBeforeRead", invocation.isClosed()));
            requireThat(Arrays.equals(observed, expected), "exactOpaqueExtraCapacityConserved").isTrue();
            requireThat(Arrays.equals(Files.readAllBytes(out), expectedOutput), "exactBinaryOriginalStdout").isTrue();
            requireThat(Arrays.equals(Files.readAllBytes(err), expectedError), "exactBinaryOriginalStderr").isTrue();
            requireThat(sentinel.isAlive(), "foreignSentinelStillOriginalLiveProcess").isTrue();
            requireThat(empty(endpoint) && empty(scratch) && empty(state), "nativeNamespacesCollected").isTrue();
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("stage", name); receipt.put("originalCargoStatus", status);
            receipt.put("acceptedOutcomes", invocation.session().acceptedOutcomes());
            trace.memoryObservations = invocation.memoryObservations();
            receipt.put("completedMemoryObservations", trace.memoryObservations);
            receipt.put("stdoutSha256", sha(out)); receipt.put("stderrSha256", sha(err));
            trace.requireRetirements();
            var cargoRetirement = invocation.originalCargoRetirement().orElseThrow(() ->
                new IOException("Original Cargo has no genuine successful native retirement receipt"));
            requireThat(cargoRetirement.result(), "originalRetiredCargoResultUnchanged").isEqualTo(status);
            requireThat(cargoRetirement.terminationRequested(), "originalCargoCompletedNaturally").isFalse();
            requireAuthority(cargoRetirement.rootAuthority());
            receipt.put("attemptRetirements", invocation.attemptRetirements());
            receipt.put("originalCargoRetirement", cargoRetirement);
            receipt.put("nativeCollectionProof", "genuine_production_terminal_retirement_receipts");
            receipt.put("opaqueCapacityVerified", true); receipt.put("invocationClosed", true);
            stages.add(receipt); write(name + "-result.json", receipt);
            closeDescriptor(audit); closeDescriptor(stdin); closeDescriptor(stdout); closeDescriptor(stderr);
            return trace;
        }

        /** Owns actual proc-macro gates and one bounded physical load outside the compiler's finite VA limit. */
        private final class RecoveryWorkload
        {
            /** Exact actual request in both successful profile and denied/retried native compiler work. */
            private static final long REQUEST = 256L * 1024 * 1024;
            /** Explicit bounded physical growth increment. */
            private static final long CHUNK = 64L * 1024 * 1024;
            /** Source-external immutable environment identity; only root-owned command contents change. */
            private final Path control;
            /** Root-owned commands unreadable as mutable authority by workload credentials. */
            private final Path commands;
            /** Workload-owned raw files, never used as native collection or signal authority. */
            private final Path observations;
            /** Explicit caller-owned physical-load ceiling. */
            private final long maximum;
            /** Original independent physical-load process retained through natural exit. */
            private Process ballast;
            /** Original load's natural result has been collected. */
            private boolean ballastCollected;
            /** Both authentic allocation waits were retained before any physical-pressure change. */
            private boolean armed;
            /** Actual oldest original compiler sequence, chosen from issued production attempts. */
            private long oldest = -1;
            /** Actual newest original compiler sequence, selected unchanged by production Policy. */
            private long newest = -1;
            /** Root-published retirement preceded physical relief and same-process limit restoration. */
            private boolean victimRetired;
            /** Actual oldest proc-macro role associated with its authenticated compiler arguments. */
            private String oldestRole;
            /** Descriptor-bound FIFO device recorded independently by Java before any drain. */
            private long fifoDevice;
            /** Descriptor-bound FIFO inode recorded independently by Java before any drain. */
            private long fifoInode;

            /**
             * Creates source-external controls without inventing demand or changing compiler arguments.
             * @throws IOException if explicit configuration or owned directory publication fails
             */
            private RecoveryWorkload() throws IOException
            {
                maximum = nonnegative("d2MaxBallastBytes");
                if (maximum < CHUNK || maximum > 6L * 1024 * 1024 * 1024 || maximum % CHUNK != 0)
                    throw new IOException("D2 physical ballast must be explicit 64MiB increments, at most 6GiB");
                if (positive("jobs") < 4 || nonnegative("headroomBytes") < CHUNK)
                    throw new IOException("D2 needs four genuine FIFO slots and explicit headroom at least 64MiB");
                control = directory(root.resolve("d2-control"), "rwxr-xr-x", 0, 0);
                commands = directory(control.resolve("commands"), "rwxr-xr-x", 0, 0);
                observations = directory(control.resolve("events"), "rwx------", positive("workloadUid"),
                    positive("workloadGid"));
                command("phase", "0\n");
                command("ballast-bytes", "0\n");
            }

            /**
             * Selects real training or denial work without changing source, authenticated arguments or environment.
             * @param stage actual stage name
             * @throws IOException if command publication or the original bounded load launch fails
             */
            private void prepare(String stage) throws IOException
            {
                command("phase", stage.equals("denial-retry") ? "1\n" : "0\n");
                if (stage.equals("denial-retry"))
                {
                    if (ballast != null) throw new IOException("D2 ballast may start only once");
                    ballast = new ProcessBuilder(path("d2Ballast").toString(), control.toString(),
                        Long.toString(maximum)).redirectOutput(control.resolve("ballast.stdout").toFile())
                        .redirectError(control.resolve("ballast.stderr").toFile()).start();
                    write("d2-ballast-started.json", Map.of("pid", ballast.pid(), "maximumBytes", maximum,
                        "originalProcessRetained", true, "outsideCompilerAddressSpaceLimit", true));
                }
            }

            /**
             * Publishes one root-owned command atomically; workload events never grant command-write authority.
             * @param name fixed command basename
             * @param value exact command bytes
             * @throws IOException if exclusive temporary creation, mode publication or replacement fails
             */
            private void command(String name, String value) throws IOException
            {
                Path temporary = Files.createTempFile(commands, "c-", ".tmp");
                Files.writeString(temporary, value, StandardCharsets.UTF_8);
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-r--r--"));
                Files.move(temporary, commands.resolve(name), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            /**
             * Reads only complete append-only raw workload lines; partial final writes remain pending.
             * @param role exact proc-macro role
             * @return genuine pressure-stage event objects in file order, without fabricated missing events
             * @throws IOException if observed JSON or namespace contents are invalid
             */
            private List<JsonNode> events(String role) throws IOException
            {
                List<JsonNode> result = new ArrayList<>();
                try (var entries = Files.list(observations))
                {
                    for (Path file : entries.filter(f -> f.getFileName().toString().startsWith(role + "-")).sorted().toList())
                    {
                        String text = Files.readString(file, StandardCharsets.UTF_8);
                        int complete = text.lastIndexOf('\n');
                        if (complete < 0) continue;
                        List<JsonNode> values = new ArrayList<>();
                        for (String line : text.substring(0, complete).split("\n"))
                            values.add(json.readTree(line));
                        if (values.stream().anyMatch(v -> "phase".equals(v.path("event").stringValue()) &&
                            v.path("value").longValue() == 1)) result.addAll(values);
                    }
                }
                return result;
            }

            /**
             * Finds an actual named event without treating absence as completion.
             * @param role exact actual proc-macro role
             * @param name required raw event name
             * @return actual first matching event, or empty
             * @throws IOException if raw observation reading fails
             */
            private Optional<JsonNode> event(String role, String name) throws IOException
            {
                return events(role).stream().filter(v -> name.equals(v.path("event").stringValue())).findFirst();
            }

            /**
             * Associates only a genuine authenticated rustc crate-name argument with its explicit source role.
             * @param attempt original production compiler attempt
             * @return exact a or b role
             * @throws IOException if the actual compiler arguments are not one of the two fixture leaves
             */
            private String role(Trace.Tracked attempt) throws IOException
            {
                List<String> arguments = attempt.request.arguments();
                int index = arguments.indexOf("--crate-name");
                if (index >= 0 && index + 1 < arguments.size())
                {
                    String value = arguments.get(index + 1);
                    if (value.equals("d2_a")) return "a";
                    if (value.equals("d2_b")) return "b";
                }
                throw new IOException("Actual compiler is not an authenticated D2 leaf: " + arguments);
            }

            /**
             * Establishes two real native waits before faulting any bounded ballast pages. The original notifications
             * are buffered unchanged, then delivered to the real coordinator before its next actual measurement cycle.
             * No controller permission, memory value, estimate, allocation signal or result is synthesized.
             * @param trace current genuine production invocation decorator
             * @throws IOException if actual compiler setup, native waits or reachable physical scarcity fails
             */
            private void pump(Trace trace) throws IOException
            {
                if (armed || event("a", "ready-before-limit").isEmpty() ||
                    event("b", "ready-before-limit").isEmpty()) return;
                requireThat(trace.active.size(), "exactTwoActualConcurrentLeafAttempts").isEqualTo(2);
                oldest = trace.active.keySet().stream().min(Long::compareTo).orElseThrow();
                newest = trace.active.keySet().stream().max(Long::compareTo).orElseThrow();
                oldestRole = role(trace.active.get(oldest));
                String newestRole = role(trace.active.get(newest));
                requireThat(oldestRole.equals(newestRole), "differentAuthenticatedLeafCompilers").isFalse();
                command("begin-a", "1\n"); command("begin-b", "1\n");
                java.util.Set<Long> denied = new java.util.HashSet<>();
                while (denied.size() < 2)
                {
                    trace.checkDeadline(); trace.session.pump();
                    var notification = trace.session.pollBlocked();
                    while (notification.isPresent())
                    {
                        var actual = notification.orElseThrow();
                        if (!trace.active.containsKey(actual.attemptSequence()))
                            throw new IOException("Native wait does not identify either original live fixture attempt");
                        denied.add(actual.attemptSequence()); trace.pendingFixtureBlocked.add(actual);
                        trace.event("fixture-retained-authentic-native-wait", Map.of("notification", actual));
                        notification = trace.session.pollBlocked();
                    }
                    pause();
                }
                requireDecodedDenial(trace, trace.active.get(oldest), oldestRole);
                requireDecodedDenial(trace, trace.active.get(newest), newestRole);
                long requested = 0;
                while (true)
                {
                    trace.checkDeadline(); trace.session.pump();
                    if (!ballast.isAlive()) throw new IOException("Original bounded physical load exited before relief");
                    Controller.Memory memory = trace.session.measurements().readHost();
                    trace.event("genuine-host-before-policy-scarcity", Map.of("memory", memory,
                        "requestedOwnedBallastBytes", requested, "maximumOwnedBallastBytes", maximum));
                    if (memory.availableBytes() < nonnegative("headroomBytes") - CHUNK) break;
                    if (requested == maximum)
                        throw new IOException("Bounded actual physical load cannot reach configured admission boundary");
                    requested += CHUNK; command("ballast-bytes", requested + "\n");
                    long targetBytes = requested;
                    while (!ballastTouched(targetBytes)) { trace.checkDeadline(); trace.session.pump(); pause(); }
                }
                armed = true;
                trace.event("actual-two-denials-and-physical-scarcity-ready", Map.of("oldest", oldest,
                    "newest", newest, "originalNotificationsRetained", trace.pendingFixtureBlocked));
            }

            /**
             * Correlates a real decoded native frame with the same original authenticated compiler and calling thread.
             * Native thread identity uses the hook's actual pthread_self binding; the kernel thread ID stays separate.
             * @param trace original connected invocation retaining reports through native retirement
             * @param attempt original authenticated compiler attempt
             * @param role original source role selected from authenticated arguments
             * @throws IOException if the actual exact-call report or source observation is absent
             */
            private void requireDecodedDenial(Trace trace, Trace.Tracked attempt, String role) throws IOException
            {
                JsonNode call = event(role, "before-original-malloc").orElseThrow();
                JsonNode thread = event(role, "original-calling-thread").orElseThrow();
                requireThat(thread.path("pid").longValue(), "sameOriginalCallingProcess")
                    .isEqualTo(call.path("pid").longValue());
                requireThat(thread.path("second").longValue() > 0, "actualKernelThreadIdentityRetained").isTrue();
                var receipts = trace.invocation.allocationReceipts().stream()
                    .filter(receipt -> receipt.name().equals(attempt.name) &&
                        receipt.attemptSequence() == attempt.sequence && receipt.denial().pid() == call.path("pid").longValue() &&
                        receipt.denial().blocked().thread().equals(java.math.BigInteger.valueOf(thread.path("value").longValue())) &&
                        receipt.denial().blocked().bytes().equals(java.math.BigInteger.valueOf(REQUEST)) &&
                        receipt.denial().blocked().error() == 12).toList();
                requireThat(receipts.isEmpty(), "genuineDecodedExact256MiBMallocNullEnomem12").isFalse();
                requireThat(receipts.stream().map(receipt -> receipt.denial().attempt()).distinct().count(),
                    "oneOriginalAuthenticatedNativeAttemptIdentity").isEqualTo(1L);
                trace.event("genuine-decoded-original-allocation-denial", Map.of("name", attempt.name,
                    "attemptSequence", attempt.sequence, "kernelThread", thread.path("second").longValue(),
                    "originalCall", call, "decodedNativeReports", receipts));
            }

            /**
             * Observes completion of actual owned page faults without substituting them for host memory readings.
             * @param bytes exact requested physical-load milestone
             * @return whether the real helper has written its matching completed page-fault event
             * @throws IOException if raw helper reading fails
             */
            private boolean ballastTouched(long bytes) throws IOException
            {
                Path log = control.resolve("ballast.jsonl");
                if (!Files.exists(log)) return false;
                String text = Files.readString(log);
                int complete = text.lastIndexOf('\n');
                if (complete < 0) return false;
                for (String line : text.substring(0, complete).split("\n"))
                {
                    JsonNode value = json.readTree(line);
                    if ("ballast-physical-pages-touched".equals(value.path("event").stringValue()) &&
                        value.path("value").longValue() == bytes) return true;
                }
                return false;
            }

            /**
             * Requires the actual unchanged Policy control to select the newest original owner under real scarcity.
             * @param attempt actual requested termination owner
             * @throws IOException if the chosen owner is not the newest original live compiler
             */
            private void beforeTerminate(Trace.Tracked attempt) throws IOException
            {
                if (!armed || victimRetired) return;
                if (attempt.sequence != newest)
                    throw new IOException("Unchanged Policy did not select the newest original fixture attempt");
            }

            /**
             * Rejects any oldest permission that precedes genuine newest retirement and real limit restoration.
             * @param trace original invocation supplying retained authentic native reports
             * @param attempt actual original resume target
             * @throws IOException if production policy tries to continue an unrelieved or different original attempt
             */
            private void beforeResume(Trace trace, Trace.Tracked attempt) throws IOException
            {
                if (!victimRetired || attempt.sequence != oldest || event(oldestRole, "restored-original-limit").isEmpty())
                    throw new IOException("Actual resume preceded genuine victim retirement/oldest limit restoration");
                requireDecodedDenial(trace, attempt, oldestRole);
                requireThat(trace.invocation.attemptRetirements().stream().anyMatch(receipt ->
                    receipt.attemptSequence() == newest && receipt.aborted()), "genuineRetirementBeforeGrant").isTrue();
            }

            /**
             * Relieves actual physical load and requests restoration only after the genuine newest whole-tree receipt.
             * The precreated thread inside the same oldest compiler restores its own unchanged original hard limit.
             * @param trace authentic original session
             * @param attempt original successfully closed native owner
             * @throws IOException if native receipt, original load collection or actual restoration cannot be established
             */
            private void retired(Trace trace, Trace.Tracked attempt) throws IOException
            {
                if (attempt.sequence != newest || victimRetired) return;
                if (!armed || !attempt.retirement.aborted()) throw new IOException("Newest native retirement was not aborted");
                requireAuthority(attempt.retirement.rootAuthority());
                trace.event("genuine-victim-retirement-before-real-relief", Map.of("retirement", attempt.retirement));
                command("ballast-stop", "1\n");
                while (ballast.isAlive()) { trace.checkDeadline(); trace.session.pump(); pause(); }
                try
                {
                    requireThat(ballast.waitFor(), "originalBoundedPhysicalLoadNaturalExit").isEqualTo(0);
                }
                catch (InterruptedException failure)
                {
                    Thread.currentThread().interrupt();
                    throw new IOException("Original physical-load process remains retained", failure);
                }
                ballastCollected = true;
                write("d2-ballast-terminal.json", Map.of("pid", ballast.pid(), "exit", ballast.exitValue(),
                    "originalProcessCollected", true, "signalsSent", List.of()));
                command("restore-" + oldestRole, "1\n");
                while (event(oldestRole, "restored-original-limit").isEmpty())
                { trace.checkDeadline(); trace.session.pump(); pause(); }
                JsonNode ready = event(oldestRole, "ready-before-limit").orElseThrow();
                JsonNode restored = event(oldestRole, "restored-original-limit").orElseThrow();
                requireThat(restored.path("pid").longValue(), "sameActualOldestCompilerRestoresItself")
                    .isEqualTo(ready.path("pid").longValue());
                requireThat(restored.path("value").longValue(), "exactOriginalSoftLimitRestored")
                    .isEqualTo(ready.path("value").longValue());
                requireThat(restored.path("second").longValue(), "exactOriginalHardLimitUnchanged")
                    .isEqualTo(ready.path("second").longValue());
                victimRetired = true;
                trace.event("real-load-collected-and-oldest-original-limit-restored", Map.of("restored", restored));
            }

            /**
             * Records the independent original FIFO identity before the same inode is borrowed by real compiler code.
             * @param device actual device from original native FIFO path
             * @param inode actual original FIFO inode
             */
            private void fifoIdentity(long device, long inode) { fifoDevice = device; fifoInode = inode; }

            /**
             * Requires same-process useful continuation, fresh real compiler work and exact descriptor-bound raw bytes.
             * @param trace actual completed connected invocation
             * @throws IOException if a required real raw event or original descriptor identity is absent
             */
            private void requireRecovery(Trace trace) throws IOException
            {
                requireThat(armed && victimRetired && ballastCollected, "actualScarcityCollectionAndRelief").isTrue();
                JsonNode ready = event(oldestRole, "ready-before-limit").orElseThrow();
                JsonNode success = event(oldestRole, "same-call-success-useful").orElseThrow();
                Trace.Tracked originalOldest = trace.issued.stream().filter(a -> a.sequence == oldest)
                    .findFirst().orElseThrow();
                requireThat(ready.path("pid").longValue(), "oldestRawMacroRunsInsideAuthenticatedNativeRoot")
                    .isEqualTo(originalOldest.retirement.rootAuthority().getFirst().longValue());
                requireThat(success.path("pid").longValue(), "sameOldestActualCompilerReturnsUsefulPointer")
                    .isEqualTo(ready.path("pid").longValue());
                requireThat(success.path("second").longValue(), "exactOriginalFiniteRequestUnchanged")
                    .isEqualTo(REQUEST);
                for (String role : List.of("a", "b"))
                {
                    List<JsonNode> raw = events(role);
                    Trace.Tracked original = trace.issued.stream().filter(a -> a.sequence == oldest ||
                        a.sequence == newest).filter(a -> a.request.arguments().contains("d2_" + role))
                        .findFirst().orElseThrow();
                    requireThat(event(role, "ready-before-limit").orElseThrow().path("pid").longValue(),
                        "rawOriginalMacroMatchesActualNativeRetirementRoot")
                        .isEqualTo(original.retirement.rootAuthority().getFirst().longValue());
                    requireDecodedDenial(trace, original, role);
                    JsonNode initial = event(role, "ready-before-limit").orElseThrow();
                    JsonNode limited = event(role, "finite-limit-applied").orElseThrow();
                    JsonNode call = event(role, "before-original-malloc").orElseThrow();
                    requireThat(limited.path("value").longValue() < initial.path("value").longValue(),
                        "realFiniteSoftLimitNarrowed").isTrue();
                    requireThat(limited.path("second").longValue(), "originalHardLimitPreservedDuringDenial")
                        .isEqualTo(initial.path("second").longValue());
                    requireThat(call.path("value").longValue(), "sameExactFiniteNativeMallocRequest")
                        .isEqualTo(REQUEST);
                    requireThat(call.path("second").longValue(), "originalAttemptActuallyRequestedFiniteDenial")
                        .isEqualTo(1L);
                    requireThat(raw.stream().anyMatch(v -> "borrowed-original-byte".equals(v.path("event").stringValue())),
                        "actualCompilerBorrowsOriginalOpaqueByte").isTrue();
                    requireThat(raw.stream().anyMatch(v -> "returned-original-byte".equals(v.path("event").stringValue())),
                        "successfulCompilerReturnsOriginalOpaqueByte").isTrue();
                    for (JsonNode returned : raw.stream().filter(v ->
                        "returned-original-byte".equals(v.path("event").stringValue())).toList())
                    {
                        JsonNode borrowed = raw.stream().filter(v ->
                            "borrowed-original-byte".equals(v.path("event").stringValue()) &&
                            v.path("pid").longValue() == returned.path("pid").longValue()).findFirst().orElseThrow();
                        requireThat(returned.path("value").longValue(), "exactOriginalCompilerByteReturned")
                            .isEqualTo(borrowed.path("value").longValue());
                        requireThat(returned.path("second").longValue(), "sameOriginalBorrowedDescriptorReturned")
                            .isEqualTo(borrowed.path("second").longValue());
                    }
                    for (JsonNode identity : raw.stream().filter(v ->
                        "fifo-descriptor-identity".equals(v.path("event").stringValue())).toList())
                    {
                        requireThat(identity.path("value").longValue(), "sameDescriptorBoundFifoDevice").isEqualTo(fifoDevice);
                        requireThat(identity.path("second").longValue(), "sameDescriptorBoundFifoInode").isEqualTo(fifoInode);
                    }
                }
                trace.event("connected-d2-real-workload-checks", Map.of("sameCallBytes", REQUEST,
                    "physicalBallastMaximum", maximum, "nativeRetirementBeforeRestore", true,
                    "realVaConstraintDistinctFromPhysicalScarcity", true));
            }

            /**
             * Keeps all owned load lifetime uncertainty retained rather than sending a fallback signal.
             * @return whether no ballast was started or its natural original result was collected
             */
            private boolean collected() { return ballast == null || ballastCollected; }

            /**
             * Requires the independent physical-load owner to have completed before descriptor/scope disposal.
             * @throws IOException if the original load still requires ownership
             */
            private void requireCollected() throws IOException
            {
                if (!collected()) throw new IOException("Retain original D2 physical load and workload owners");
            }

            /**
             * Waits only a small cooperative interval without a destructive process deadline.
             * @throws IOException if the constructing thread is interrupted
             */
            private void pause() throws IOException
            {
                try { Thread.sleep(5); }
                catch (InterruptedException failure)
                {
                    Thread.currentThread().interrupt(); throw new IOException("D2 control interrupted", failure);
                }
            }
        }

        /**
         * Keeps a descriptor owner reachable before it can be borrowed by native code.
         * @param descriptor successful original native descriptor
         * @return the same descriptor
         */
        private int retain(int descriptor) { retainedDescriptors.add(descriptor); return descriptor; }

        /**
         * Consumes exactly one owned close attempt, including failure, without replaying its descriptor number.
         * @param descriptor original known owned descriptor
         * @throws IOException if its one native close fails
         */
        private void closeDescriptor(int descriptor) throws IOException
        {
            requireThat(retainedDescriptors.remove(Integer.valueOf(descriptor)), "ownedDescriptorCloseOnce").isTrue();
            try { descriptors.close(descriptor); }
            catch (IOException | RuntimeException | Error failure)
            {
                uncertainDescriptorCloses.add(descriptor);
                throw failure;
            }
        }

        /**
         * Checks whether all native users permit closing their borrowed descriptors and scopes.
         * @return whether every started original invocation is completely closed
         */
        private boolean safeToClose() { return uncertainDescriptorCloses.isEmpty() && invocations.stream().allMatch(MacCargoInvocation::isClosed) && (recovery == null || recovery.collected()); }

        /**
         * Releases only established-safe descriptor owners and naturally collects the original sentinel.
         * @throws Exception if an owned close or natural collection fails
         */
        private void closeSafeResources() throws Exception
        {
            requireThat(safeToClose(), "allNativeUsersCollectedBeforeScopeClosure").isTrue();
            IOException failure = null;
            for (int descriptor : List.copyOf(retainedDescriptors))
                try { closeDescriptor(descriptor); }
                catch (IOException cleanup)
                {
                    if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
                }
            if (recovery != null) recovery.requireCollected();
            collectSentinel();
            if (failure != null) throw failure;
        }

        /**
         * Closes only this original process's input and collects its natural EOF exit; never sends a signal.
         * @throws Exception if natural collection cannot be established
         */
        private void collectSentinel() throws Exception
        {
            if (sentinel == null || sentinelCollected) return;
            sentinel.getOutputStream().close();
            if (!sentinel.waitFor(positive("timeoutSeconds"), TimeUnit.SECONDS))
                throw new IOException("Original foreign sentinel remains retained; no fallback signal authorized");
            requireThat(sentinel.exitValue(), "foreignSentinelNaturalExit").isEqualTo(0);
            sentinelCollected = true;
            write("sentinel-terminal.json", Map.of("pid", sentinel.pid(), "originalProcessCollected", true,
                "exit", sentinel.exitValue(), "signalsSent", List.of()));
        }

        /**
         * Preserves the first failure and live ownership instead of fabricating completion or deleting evidence.
         * @param failure exact first throwable
         */
        private void recordFailure(Throwable failure)
        {
            try
            {
                write("first-failure.json", Map.of("type", failure.getClass().getName(),
                    "message", String.valueOf(failure.getMessage()), "nativeUsersClosed", safeToClose(),
                    "retainedDescriptors", retainedDescriptors, "uncertainDescriptorCloses", uncertainDescriptorCloses,
                    "sentinelCollected", sentinelCollected,
                    "stages", stages, "D1", "not_accepted", "D2", "not_accepted"));
            }
            catch (IOException evidenceFailure) { failure.addSuppressed(evidenceFailure); }
        }

        /**
         * Parses an existing absolute real path without inheriting an ambiguous working-directory base.
         * @param name required JSON path field
         * @return normalized, unchanged absolute path
         * @throws IOException if the input is absent, relative, normalized differently or not the actual real path
         */
        private Path path(String name) throws IOException
        {
            JsonNode value = config.get(name);
            if (value == null || !value.isString()) throw new IOException("Missing text path: " + name);
            Path result = Path.of(value.stringValue());
            if (!result.isAbsolute() || !result.equals(result.normalize()) || !result.equals(result.toRealPath()))
                throw new IOException("Trusted path must be absolute, normalized and real: " + name);
            return result;
        }

        /**
         * Reads one explicitly supplied nonnegative integer without coercing absent, null or fractional values.
         * @param name required integral field
         * @return exact nonnegative value
         * @throws IOException if the value is missing, fractional, negative or outside signed long range
         */
        private long nonnegative(String name) throws IOException
        {
            JsonNode value = config.get(name);
            if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
                throw new IOException("Expected nonnegative integral configuration field: " + name);
            return value.longValue();
        }

        /**
         * Reads an explicitly supplied positive signed-int bound.
         * @param name required integral field
         * @return exact positive value
         * @throws IOException if the value is absent, zero, negative or outside signed int range
         */
        private int positive(String name) throws IOException
        {
            long value = nonnegative(name);
            if (value == 0 || value > Integer.MAX_VALUE) throw new IOException("Expected positive int: " + name);
            return Math.toIntExact(value);
        }

        /**
         * Decodes exact configured expected bytes, never changing an observed stream.
         * @param name required base64 field
         * @return decoded immutable expectation bytes
         * @throws IOException if absent, nontext or invalid base64
         */
        private byte[] binary(String name) throws IOException
        {
            JsonNode value = config.get(name);
            if (value == null || !value.isString()) throw new IOException("Missing binary expectation: " + name);
            try { return Base64.getDecoder().decode(value.stringValue()); }
            catch (IllegalArgumentException failure) { throw new IOException("Invalid base64: " + name, failure); }
        }

        /**
         * Verifies exact configured immutable native tools before and after execution.
         * @throws IOException if a required tool or its pinned digest differs
         */
        private void pinTools() throws IOException
        {
            requireThat(tooling.isClosed(), "toolingRetainedBeforeAndAfterNativeUse").isFalse();
            JsonNode hashes = config.get("toolSha256");
            if (hashes == null || !hashes.isObject()) throw new IOException("toolSha256 object is required");
            Map<String, Object> observed = new LinkedHashMap<>();
            for (String name : List.of("cargo", "wrapper", "rustc", "workloadLibrary", "helper", "cargoLibrary", "frida"))
            {
                Path tool = path(name);
                if (!Files.isRegularFile(tool, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Installed tool must be a real regular file: " + name);
                requireTrustedRoot(tool);
                JsonNode expected = hashes.get(name);
                String actual = sha(tool);
                if (expected == null || !expected.isString() || !actual.equals(expected.stringValue()))
                    throw new IOException("Pinned immutable tool digest differs: " + name);
                observed.put(name, Map.of("path", tool.toString(), "sha256", actual));
            }
            if (gate.equals("D2"))
            {
                Path ballast = path("d2Ballast");
                requireTrustedRoot(ballast);
                requireThat(Files.isRegularFile(ballast, LinkOption.NOFOLLOW_LINKS), "installedD2Ballast").isTrue();
                JsonNode expected = hashes.get("d2Ballast");
                if (expected == null || !expected.isString() || !sha(ballast).equals(expected.stringValue()))
                    throw new IOException("Pinned D2 ballast digest differs");
                observed.put("d2Ballast", Map.of("path", ballast.toString(), "sha256", sha(ballast)));
            }
            requireTrustedRoot(path("toolchainRoot"));
            write("tools-" + (stages.isEmpty() ? "before" : "after") + ".json", observed);
        }

        /**
         * Builds a complete explicit environment whose jobserver fields remain exclusively production-owned.
         * @param socket exact fresh invocation socket
         * @return exact environment without inherited home cache or make settings
         * @throws IOException if explicit values are malformed or reserved fields are present
         */
        private Map<String, String> environment(Path socket) throws IOException
        {
            JsonNode values = config.get("environment");
            if (values == null || !values.isObject()) throw new IOException("Explicit environment object required");
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : values.properties())
            {
                if (!entry.getValue().isString()) throw new IOException("Environment entries must be text");
                result.put(entry.getKey(), entry.getValue().stringValue());
            }
            for (String reserved : List.of("RUSTC", "RUSTC_WRAPPER", "CODEX_CARGO_SOCKET", "HOME", "CARGO_HOME",
                "CARGO_MAKEFLAGS", "MAKEFLAGS", "MFLAGS", "CODEX_D2_CONTROL"))
                if (result.containsKey(reserved)) throw new IOException("Fixture/production-owned environment: " + reserved);
            Path home = root.resolve("workload-home");
            if (!Files.exists(home)) directory(home, "rwx------", positive("workloadUid"), positive("workloadGid"));
            Path cargoHome = home.resolve("cargo");
            if (!Files.exists(cargoHome)) directory(cargoHome, "rwx------", positive("workloadUid"), positive("workloadGid"));
            result.put("HOME", home.toString()); result.put("CARGO_HOME", cargoHome.toString());
            result.put("RUSTC", path("rustc").toString()); result.put("RUSTC_WRAPPER", path("wrapper").toString());
            result.put("CODEX_CARGO_SOCKET", socket.toString()); result.put("CARGO_TERM_COLOR", "never");
            if (recovery != null) result.put("CODEX_D2_CONTROL", recovery.control.toString());
            return Map.copyOf(result);
        }

        /**
         * Creates a real tiny offline Rust binary that reads original input and writes binary stdout and stderr.
         * @throws IOException if project preparation fails
         */
        private void createTinyProject() throws IOException
        {
            Files.createDirectory(project.resolve("src"));
            Files.writeString(project.resolve("Cargo.toml"),
                "[package]\nname = \"native_fixture\"\nversion = \"0.1.0\"\nedition = \"2021\"\n");
            writeTinyMain();
        }

        /**
         * Restores the exact successful program without touching captured output or retained failure evidence.
         * @throws IOException if the source write fails
         */
        private void writeTinyMain() throws IOException
        {
            Files.writeString(project.resolve("src/main.rs"), """
                use std::io::{Read, Write};
                fn main() {
                    let mut input = Vec::new();
                    std::io::stdin().read_to_end(&mut input).unwrap();
                    let mut out = std::io::stdout();
                    out.write_all(b"cargo\\0\\xffD1\\n").unwrap();
                    out.write_all(&input).unwrap();
                    std::io::stderr().write_all(b"stderr\\0\\x80D1\\n").unwrap();
                }
                """, StandardCharsets.UTF_8);
        }

        /**
         * Copies only configured regular offline source inputs into this invocation's fresh owned project.
         * @param source configured real source root
         * @param destination fresh invocation-owned directory
         * @throws IOException if a symlink, special file or copy failure is encountered
         */
        private void copyProject(Path source, Path destination) throws IOException
        {
            try (var paths = Files.walk(source))
            {
                for (Path entry : paths.sorted().toList())
                {
                    if (entry.equals(source)) continue;
                    if (Files.isSymbolicLink(entry)) throw new IOException("D2 source contains a symlink: " + entry);
                    Path output = destination.resolve(source.relativize(entry));
                    if (Files.isDirectory(entry)) Files.createDirectory(output);
                    else if (Files.isRegularFile(entry)) Files.copy(entry, output);
                    else throw new IOException("D2 source contains a special file: " + entry);
                }
            }
            List<Map<String, Object>> identities = new ArrayList<>();
            try (var paths = Files.walk(destination))
            {
                for (Path entry : paths.filter(Files::isRegularFile).sorted().toList())
                    identities.add(Map.of("projectRelativePath", destination.relativize(entry).toString(), "sha256", sha(entry)));
            }
            write("d2-project-inputs.json", identities);
        }

        /**
         * Publishes only source/generated files to the original positive workload credentials.
         * @param directory exact fresh owned workspace
         * @throws IOException if an ownership or permission change fails
         */
        private void publishWorkloadTree(Path directory) throws IOException
        {
            try (var paths = Files.walk(directory))
            {
                for (Path entry : paths.toList())
                {
                    Files.setAttribute(entry, "unix:uid", positive("workloadUid"));
                    Files.setAttribute(entry, "unix:gid", positive("workloadGid"));
                    Files.setPosixFilePermissions(entry, PosixFilePermissions.fromString(
                        Files.isDirectory(entry) ? "rwx------" : "rw-------"));
                }
            }
        }

        /**
         * Creates one fresh directory with explicit owner, group and readable POSIX permissions.
         * @param path exact new directory
         * @param permissions readable POSIX mode
         * @param uid original intended owner
         * @param gid original intended group
         * @return the created directory
         * @throws IOException if creation or ownership publication fails
         */
        private Path directory(Path path, String permissions, int uid, int gid) throws IOException
        {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions)));
            Files.setAttribute(path, "unix:uid", uid); Files.setAttribute(path, "unix:gid", gid);
            return path;
        }

        /**
         * Finds only this invocation's freshly production-created FIFO namespace, without opening any other workload.
         * @param parent exclusive endpoint parent
         * @return the one real FIFO path
         * @throws IOException if zero/multiple or wrong-kind FIFO names exist
         */
        private Path findFifo(Path parent) throws IOException
        {
            try (var paths = Files.list(parent))
            {
                List<Path> found = paths.filter(p -> p.getFileName().toString().startsWith("mac-cargo-jobserver-"))
                    .map(p -> p.resolve("tokens")).toList();
                if (found.size() != 1) throw new IOException("Expected one invocation-owned FIFO namespace");
                Path result = found.getFirst();
                int mode = ((Number) Files.getAttribute(result, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue();
                if ((mode & 0xf000) != 0x1000) throw new IOException("Expected actual native FIFO inode");
                return result;
            }
        }

        /**
         * Records exact structured observations in a fresh evidence file without replacing earlier failures.
         * @param name invocation-relative fresh evidence name
         * @param value actual observed data
         * @throws IOException if serialization or exclusive publication fails
         */
        private void write(String name, Object value) throws IOException
        {
            Files.write(root.resolve(name), json.writeValueAsBytes(value), StandardOpenOption.CREATE_NEW);
        }
    }

    /** Transparently records genuine native session operations without replacing measurements, requests or outcomes. */
    private static final class Trace implements SchedulerCoordinator.Session<Integer>
    {
        /** Original genuine production invocation, exposing only immutable successful retirement receipts. */
        private final MacCargoInvocation invocation;
        /** Genuine production session, retained after any failure. */
        private final CargoSession<Integer> session;
        /** Exclusive stage output/evidence directory. */
        private final Path stage;
        /** Borrowed scoped JSON serializer. */
        private final JsonMapper json;
        /** Original foreign Process, observed without taking control authority. */
        private final Process sentinel;
        /** Monotonic start for the caller-explicit execution timeout. */
        private final long started = System.nanoTime();
        /** Explicit real-time execution bound, distinct from readiness timeout. */
        private final long timeoutNanos;
        /** Actual ordered event receipts. */
        private final List<Map<String, Object>> events = new ArrayList<>();
        /** Actual discovery positions, never substituted into submissions. */
        private final Map<String, Integer> positions = new LinkedHashMap<>();
        /** Original live attempt decorators; delegates are never replaced during a continuation. */
        private final Map<Long, Tracked> active = new LinkedHashMap<>();
        /** Every issued decorator retained for authentic identity and lifecycle comparisons. */
        private final List<Tracked> issued = new ArrayList<>();
        /** Genuine accepted whole-tree outcomes, including aborted outcomes withheld from final responses. */
        private final List<JobQueue.Completion<Integer>> completions = new ArrayList<>();
        /** Actual completed-work observations from the same production invocation, retained without prediction rewriting. */
        private List<CargoMemoryEstimates.Observation> memoryObservations = List.of();
        /** Names awaiting fresh retry after a genuine aborted outcome. */
        private final Map<String, Long> awaitingRetry = new LinkedHashMap<>();
        /** Genuine native allocation batches consumed from the production session. */
        private final List<SchedulerCoordinator.Blocked> blocked = new ArrayList<>();
        /** Original oldest still-live attempt when the first native denial was observed, or null. */
        private Long oldestAtDenial;
        /** Number of genuinely live attempts at the first native allocation denial. */
        private int concurrentAtDenial;
        /** Whether observed ordinary termination always selected the newest eligible live owner. */
        private boolean newestControls = true;
        /** Complete real controller retained even if native execution fails. */
        private SchedulerCoordinator<Integer> coordinator;
        /** Indicates final trace receipt was exclusively written. */
        private boolean saved;
        /** Optional real D2 workload/control owner; never supplies measurement or allocation values. */
        private final Fixture.RecoveryWorkload recovery;
        /** Authentic notifications retained while the real fixture reaches its physical scarcity boundary. */
        private final List<SchedulerCoordinator.Blocked> pendingFixtureBlocked = new ArrayList<>();

        /**
         * Borrows genuine native operations and sets only the explicitly configured cooperative runtime bound.
         * @param invocation genuine production native invocation owner
         * @param stage exclusive stage namespace
         * @param json scoped mapper
         * @param sentinel original foreign process
         * @param executionTimeout caller-explicit positive total runtime bound
         * @param recovery optional explicit real D2 workload owner, null for ordinary stages
         */
        private Trace(MacCargoInvocation invocation, Path stage, JsonMapper json, Process sentinel,
            Duration executionTimeout, Fixture.RecoveryWorkload recovery)
        {
            this.invocation = invocation;
            this.session = invocation.session();
            this.stage = stage;
            this.json = json;
            this.sentinel = sentinel;
            this.timeoutNanos = executionTimeout.toNanos();
            this.recovery = recovery;
        }

        /**
         * Returns a transparent factory retaining each actual production attempt and authenticated original request.
         * @return genuine factory operations with read-only lifecycle recording
         */
        @Override public JobQueue.AttemptFactory<Integer> attempts()
        {
            return (name, sequence) ->
            {
                JobQueue.Attempt<Integer> delegate = session.attempts().create(name, sequence);
                CargoSession.Attempt receipt = session.attempt(sequence).orElseThrow();
                Tracked prior = issued.stream().filter(a -> a.name.equals(name)).reduce((a, b) -> b).orElse(null);
                if (prior != null)
                {
                    requireThat(prior.closed && prior.retirement != null, "oldExactOwnerRetiredBeforeFreshRetry").isTrue();
                    requireThat(delegate == prior.delegate, "genuinelyFreshAttemptOwner").isFalse();
                    requireThat(receipt.request(), "unchangedAuthenticatedRequestAcrossRetry").isEqualTo(prior.request);
                    int expected = awaitingRetry.keySet().stream().mapToInt(positions::get).min().orElseThrow();
                    requireThat(positions.get(name).intValue(), "retryUsesOriginalQueuePosition").isEqualTo(expected);
                    awaitingRetry.remove(name);
                }
                Tracked tracked = new Tracked(name, sequence, delegate, receipt.request());
                issued.add(tracked); active.put(sequence, tracked);
                return tracked;
            };
        }

        /**
         * Returns genuine native observations without replacing values or complete-batch acceptance.
         * @return production measurements with raw observation recording
         */
        @Override public Controller.Measurements measurements()
        {
            Controller.Measurements actual = session.measurements();
            return new Controller.Measurements()
            {
                /**
                 * Reads and records one genuine native host observation.
                 * @return unchanged complete host memory observation
                 * @throws IOException if the genuine native operation or raw evidence publication fails
                 */
                @Override public Controller.Memory readHost() throws IOException
                {
                    checkDeadline();
                    Controller.Memory result = actual.readHost();
                    event("native-host", Map.of("memory", result));
                    return result;
                }
                /**
                 * Reads genuine residency for the exact production job.
                 * @param job actual live queue job and native attempt ordinal
                 * @return unchanged directly measured resident bytes
                 * @throws IOException if the genuine native operation or raw evidence publication fails
                 */
                @Override public long readResidentBytes(Policy.Job job) throws IOException
                {
                    long result = actual.readResidentBytes(job);
                    event("native-resident", Map.of("job", job, "residentBytes", result));
                    return result;
                }
                /**
                 * Accepts the original production complete-batch observation before recording its receipt.
                 * @throws IOException if the genuine native operation or raw evidence publication fails
                 */
                @Override public void acceptReadings() throws IOException
                {
                    actual.acceptReadings(); event("accepted-complete-native-batch", Map.of());
                }
            };
        }

        /**
         * Services the original native invocation on its constructing coordinator thread.
         * @throws IOException if the genuine native operation or raw evidence publication fails
         */
        @Override public void pump() throws IOException
        {
            checkDeadline(); session.pump();
            if (recovery != null) recovery.pump(this);
        }

        /**
         * Returns authentic submissions in production discovery order without changing their estimates.
         * @return unchanged next submission, or empty
         * @throws IOException if the genuine native operation or raw evidence publication fails
         */
        @Override public Optional<SchedulerCoordinator.Submission> pollSubmission() throws IOException
        {
            var result = session.pollSubmission();
            if (result.isPresent())
            {
                var submission = result.orElseThrow();
                positions.put(submission.name(), positions.size());
                event("authentic-submission", Map.of("submission", submission,
                    "originalPosition", positions.get(submission.name())));
            }
            return result;
        }

        /**
         * Records an authentic native allocation-denial batch without replacing a wait.
         * @return unchanged copied native notification, or empty
         * @throws IOException if the genuine native operation or raw evidence publication fails
         */
        @Override public Optional<SchedulerCoordinator.Blocked> pollBlocked() throws IOException
        {
            Optional<SchedulerCoordinator.Blocked> result = pendingFixtureBlocked.isEmpty() ? session.pollBlocked() :
                Optional.of(pendingFixtureBlocked.removeFirst());
            if (result.isPresent())
            {
                var notification = result.orElseThrow(); blocked.add(notification);
                if (oldestAtDenial == null)
                {
                    concurrentAtDenial = active.size();
                    oldestAtDenial = active.keySet().stream().min(Long::compareTo).orElseThrow();
                }
                event("genuine-native-allocation-blocked", Map.of("notification", notification,
                    "actualLiveAttempts", List.copyOf(active.keySet())));
            }
            return result;
        }

        /**
         * Forwards the genuine outcome after recording its actual owner and original raw output interval.
         * @param completion exact native whole-action outcome and original queue correlation
         * @throws IOException if the genuine native operation or raw evidence publication fails
         */
        @Override public void acceptOutcome(JobQueue.Completion<Integer> completion) throws IOException
        {
            Tracked original = issued.stream().filter(a -> a.sequence == completion.attemptSequence()).findFirst().orElseThrow();
            requireThat(original.closed, "exactNativeOwnerClosePrecedesAcceptedOutcome").isTrue();
            if (completion.outcome().aborted())
            {
                awaitingRetry.put(completion.name(), completion.attemptSequence());
                long output = Files.size(stage.resolve("stdout.bin")), error = Files.size(stage.resolve("stderr.bin"));
                event("aborted-raw-interval", Map.of("attempt", completion.attemptSequence(),
                    "stdoutBefore", original.outputAtStart, "stdoutAfter", output,
                    "stderrBefore", original.errorAtStart, "stderrAfter", error,
                    "attribution", "conservative_shared_original_stream_interval_no_bytes_erased"));
                requireThat(output, "noRawSupersededIntervalStdout").isEqualTo(original.outputAtStart);
                requireThat(error, "noRawSupersededIntervalStderr").isEqualTo(original.errorAtStart);
            }
            session.acceptOutcome(completion);
            completions.add(completion);
            event("genuine-accepted-outcome", Map.of("completion", completion));
        }

        /**
         * Observes original production discovery without converting tool exit into action completion.
         * @return whether the original session reports terminal discovery
         */
        @Override public boolean inputClosed() { return session.inputClosed(); }

        /**
         * Observes and records the actual original Cargo root result.
         * @return unchanged genuine Cargo status, or empty while incomplete
         * @throws IOException if the genuine native operation or raw evidence publication fails
         */
        @Override public OptionalInt pollToolResult() throws IOException
        {
            OptionalInt result = session.pollToolResult();
            if (result.isPresent()) event("genuine-cargo-root-result", Map.of("status", result.getAsInt()));
            return result;
        }

        /**
         * Observes whether the original invocation retains queue-owned native attempts.
         * @return whether any genuine native attempt remains owned
         */
        @Override public boolean hasOpenAttempts() { return session.hasOpenAttempts(); }

        /**
         * Stops the original producer while retaining existing native borrowers and uncertain owners.
         * @throws IOException if the genuine native operation or raw evidence publication fails
         */
        @Override public void stopAccepting() throws IOException { session.stopAccepting(); }

        /**
         * Closes the original production owner and records its genuine successful terminal retirement boundary.
         * @throws IOException if the genuine native operation or raw evidence publication fails
         */
        @Override public void close() throws IOException
        {
            session.close(); event("production-session-closed", Map.of("attemptRetirements", invocation.attemptRetirements(),
                "originalCargoRetirement", invocation.originalCargoRetirement().orElseThrow()));
        }

        /**
         * Enforces only the caller-explicit runtime bound without using timeout as evidence of collection.
         * @throws IOException if execution exceeds its bound or the foreign sentinel exits unexpectedly
         */
        private void checkDeadline() throws IOException
        {
            if (System.nanoTime() - started >= timeoutNanos)
                throw new IOException("Explicit connected runtime bound reached; retain authentic partial ownership");
            if (!sentinel.isAlive()) throw new IOException("Foreign sentinel exited during native control");
        }

        /**
         * Records one real observation in actual call order, without converting it into control authority.
         * @param type actual operation or observation
         * @param details exact authentic fields
         * @throws IOException if the append-only evidence write fails
         */
        private void event(String type, Map<String, ?> details) throws IOException
        {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("ordinal", events.size()); value.put("type", type); value.putAll(details);
            events.add(value);
            byte[] bytes = json.writeValueAsBytes(value);
            Files.write(stage.resolve("events.jsonl"), bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.writeString(stage.resolve("events.jsonl"), "\n", StandardOpenOption.APPEND);
        }

        /**
         * Preserves every real issued request and accepted outcome after success or failure.
         * @throws IOException if exact raw receipts cannot be serialized
         */
        private void save() throws IOException
        {
            if (saved) return;
            saved = true;
            List<Map<String, Object>> attempts = new ArrayList<>();
            for (Tracked attempt : issued)
                attempts.add(Map.of("name", attempt.name, "sequence", attempt.sequence,
                    "request", attempt.request, "closed", attempt.closed, "terminated", attempt.terminated,
                    "resumedBatches", attempt.resumed, "nativeTerminalReceipt",
                    attempt.retirement == null ? "unpublished_incomplete_or_uncertain" : attempt.retirement));
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("originalPositions", positions); value.put("attempts", attempts); value.put("nativeBlocked", blocked);
            value.put("acceptedSessionOutcomes", session.acceptedOutcomes());
            value.put("acceptedCoordinatorOutcomes", coordinator == null ? List.of() : coordinator.acceptedAttempts());
            value.put("productionAttemptRetirements", invocation.attemptRetirements());
            value.put("genuineDecodedAllocationReceipts", invocation.allocationReceipts());
            value.put("originalCargoRetirement", invocation.originalCargoRetirement().orElse(null));
            Files.write(stage.resolve("trace.json"), json.writeValueAsBytes(value), StandardOpenOption.CREATE_NEW);
        }

        /**
         * Matches every genuine completed payload against native retirement independently published by its exact owner.
         * @throws IOException if native result, original authority or retry incarnation cannot be established
         */
        private void requireRetirements() throws IOException
        {
            List<MacAttempts.Retirement> retirements = invocation.attemptRetirements();
            requireThat(retirements.size(), "oneNativeRetirementPerRealAttempt").isEqualTo(issued.size());
            requireThat(retirements.size(), "oneNativeRetirementPerActualOutcome").isEqualTo(completions.size());
            long ordinal = 0;
            for (MacAttempts.Retirement retirement : retirements)
            {
                requireThat(retirement.retirementSequence(), "actualRetirementPublicationOrder").isEqualTo(ordinal++);
                requireAuthority(retirement.rootAuthority());
                var outcome = completions.stream().filter(c -> c.attemptSequence() == retirement.attemptSequence())
                    .findFirst().orElseThrow();
                requireThat(retirement.name(), "nativeRetirementMatchesOriginalRequest").isEqualTo(outcome.name());
                requireThat(retirement.result(), "nativeRetirementOriginalResultUnchanged")
                    .isEqualTo(outcome.outcome().result().intValue());
                requireThat(retirement.aborted(), "nativeRetirementTerminationClassificationUnchanged")
                    .isEqualTo(outcome.outcome().aborted());
                for (MacAttempts.Retirement previous : retirements)
                    if (previous.name().equals(retirement.name()) && previous.attemptSequence() < retirement.attemptSequence())
                        requireThat(previous.rootAuthority().get(1).equals(retirement.rootAuthority().get(1)),
                            "freshRetryHasDistinctActualNativeIncarnation").isFalse();
            }
        }

        /**
         * Requires an actual oldest continuation, newest abortion and fresh original-position retry with useful results.
         * Genuine production retirement receipts separately certify the original whole-tree native terminal composition.
         * @throws IOException if a required connected raw workload or lifecycle observation is absent
         */
        private void requireRecovery() throws IOException
        {
            if (recovery != null) recovery.requireRecovery(this);
            requireThat(blocked.isEmpty(), "realNativeAllocationDenialRequired").isFalse();
            requireThat(concurrentAtDenial >= 2, "actualConcurrentAttemptsAtDenial").isTrue();
            requireThat(newestControls, "actualNewestLiveVictim").isTrue();
            requireThat(oldestAtDenial == null, "actualOldestAtDenialRetained").isFalse();
            Tracked oldest = issued.stream().filter(a -> a.sequence == oldestAtDenial.longValue()).findFirst().orElseThrow();
            requireThat(oldest.terminated, "oldestNeverTerminated").isFalse();
            requireThat(oldest.resumed.isEmpty(), "sameOldestOwnerActuallyContinued").isFalse();
            requireThat(completions.stream().anyMatch(c -> c.attemptSequence() == oldest.sequence &&
                !c.outcome().aborted() && c.outcome().result() == 0), "oldestUsefulNaturalResult").isTrue();
            List<JobQueue.Completion<Integer>> aborted = completions.stream().filter(c -> c.outcome().aborted()).toList();
            requireThat(aborted.isEmpty(), "actualNewestVictimAborted").isFalse();
            for (var victim : aborted)
                requireThat(completions.stream().anyMatch(c -> c.name().equals(victim.name()) &&
                    c.attemptSequence() > victim.attemptSequence() && !c.outcome().aborted() && c.outcome().result() == 0),
                    "freshVictimUsefulOriginalRequestResult").isTrue();
            requireThat(awaitingRetry.isEmpty(), "allAbortedRequestsRetriedInOriginalOrder").isTrue();
            Map<String, Integer> finalResponses = new HashMap<>();
            for (var completion : completions)
                if (!completion.outcome().aborted()) finalResponses.merge(completion.name(), 1, Integer::sum);
            requireThat(finalResponses.values().stream().allMatch(count -> count == 1),
                "oneGenuineNonSupersededResultPerRequest").isTrue();
        }

        /** Observes the same actual attempt object through start, continuation, outcome and close. */
        private final class Tracked implements JobQueue.Attempt<Integer>
        {
            /** Stable authenticated request name. */
            private final String name;
            /** Global ordinal issued by the actual queue. */
            private final long sequence;
            /** Original actual production owner; never replaced for continuation. */
            private final JobQueue.Attempt<Integer> delegate;
            /** Genuine independently authenticated original request, including unchanged arguments. */
            private final CargoCompilerRequest request;
            /** Native denial batches actually granted continuation by production policy. */
            private final List<Long> resumed = new ArrayList<>();
            /** Whether actual terminate was requested. */
            private boolean terminated;
            /** Whether actual close returned successfully. */
            private boolean closed;
            /** Actual native whole-tree retirement published independently of outcome acceptance, or null while incomplete. */
            private MacAttempts.Retirement retirement;
            /** Raw original stdout length before this attempt was released. */
            private long outputAtStart;
            /** Raw original stderr length before this attempt was released. */
            private long errorAtStart;

            /**
             * Borrows one actual attempt without acquiring or mutating its native inputs.
             * @param name authenticated logical name
             * @param sequence actual global ordinal
             * @param delegate original resource-free production attempt
             * @param request genuine authenticated request
             */
            private Tracked(String name, long sequence, JobQueue.Attempt<Integer> delegate, CargoCompilerRequest request)
            {
                this.name = name;
                this.sequence = sequence;
                this.delegate = delegate;
                this.request = request;
            }

            /**
             * Starts the original native attempt while retaining exact original raw stream offsets.
             * @throws IOException if the genuine native operation or raw evidence publication fails
             */
            @Override public void start() throws IOException
            {
                outputAtStart = Files.size(stage.resolve("stdout.bin"));
                errorAtStart = Files.size(stage.resolve("stderr.bin"));
                event("original-attempt-start", Map.of("name", name, "sequence", sequence,
                    "authenticatedRequest", request, "position", positions.get(name)));
                delegate.start();
            }

            /**
             * Continues the same original native attempt using the exact granted real denial batch.
             * @param waitSequence genuine monotonic native allocation batch correlation
             * @throws IOException if the genuine native operation or raw evidence publication fails
             */
            @Override public void resume(long waitSequence) throws IOException
            {
                if (recovery != null) recovery.beforeResume(Trace.this, this);
                delegate.resume(waitSequence); resumed.add(waitSequence);
                event("actual-same-owner-resume", Map.of("name", name, "sequence", sequence, "waitSequence", waitSequence));
            }

            /**
             * Requests termination from the exact production owner and records actual newest-owner selection.
             * @throws IOException if the genuine native operation or raw evidence publication fails
             */
            @Override public void terminate() throws IOException
            {
                if (!closed && !terminated)
                {
                    long newest = active.keySet().stream().max(Long::compareTo).orElseThrow();
                    newestControls &= sequence == newest;
                }
                if (recovery != null) recovery.beforeTerminate(this);
                delegate.terminate(); terminated = true;
                event("actual-owner-terminate", Map.of("name", name, "sequence", sequence));
            }

            /**
             * Observes the original native whole-action result without substituting a timeout or descendant exit.
             * @return unchanged genuine collected result, or empty
             * @throws IOException if the genuine native operation or raw evidence publication fails
             */
            @Override public Optional<JobQueue.Outcome<Integer>> poll() throws IOException
            {
                var result = delegate.poll();
                if (result.isPresent()) event("actual-native-attempt-result", Map.of("name", name,
                    "sequence", sequence, "outcome", result.orElseThrow()));
                return result;
            }

            /**
             * Closes the original production owner and records its genuine successful terminal retirement boundary.
             * @throws IOException if the genuine native operation or raw evidence publication fails
             */
            @Override public void close() throws IOException
            {
                delegate.close();
                retirement = invocation.attemptRetirements().stream().filter(r -> r.attemptSequence() == sequence)
                    .findFirst().orElseThrow(() -> new IOException("Actual owner lacks successful native retirement receipt"));
                requireThat(retirement.name(), "retiredOriginalRequestName").isEqualTo(name);
                requireAuthority(retirement.rootAuthority());
                closed = true; active.remove(sequence);
                if (recovery != null) recovery.retired(Trace.this, this);
                event("actual-owner-retired", Map.of("name", name, "sequence", sequence,
                    "genuineOriginalAuthorityAndTerminalReceipt", retirement));
            }
        }
    }

    /** Scoped Darwin libc descriptor operations; no guessed Java channel internals or global linker is used. */
    private static final class Descriptors
    {
        /** Borrowed linker lifetime retained through all descriptors. */
        private final Linker linker;
        /** Native operations whose libraries are part of the pinned Darwin system runtime. */
        private final Map<String, MethodHandle> functions = new HashMap<>();

        /**
         * Retains borrowed scoped native services without opening a descriptor.
         * @param linker Pouch-provided native linker
         */
        private Descriptors(Linker linker)
        {
            this.linker = linker;
            function("__error", FunctionDescriptor.of(ValueLayout.ADDRESS));
        }

        /**
         * Resolves one exact Darwin C ABI operation through the scoped system lookup.
         * @param name libc operation
         * @param descriptor exact native signature
         * @return cached native operation
         */
        @SuppressWarnings("restricted")
        private MethodHandle function(String name, FunctionDescriptor descriptor)
        {
            return functions.computeIfAbsent(name, key ->
                linker.downcallHandle(linker.defaultLookup().find(key).orElseThrow(), descriptor));
        }

        /**
         * Calls an integral no-argument Darwin identity operation.
         * @param name native identity function
         * @return unchanged native signed int result
         * @throws IOException if the foreign call fails
         */
        private int integer(String name) throws IOException
        {
            try { return (int) function(name, FunctionDescriptor.of(ValueLayout.JAVA_INT)).invokeExact(); }
            catch (Throwable failure) { throw foreignFailure(name, failure); }
        }

        /**
         * Opens an existing exact path once; caller retains the returned descriptor before any native borrower runs.
         * Darwin flags are at this ABI boundary; no create mode or variadic argument is passed.
         * @param path existing original stream or invocation-owned FIFO
         * @param flags Darwin open flags
         * @return actual independently owned native descriptor
         * @throws IOException if native open fails
         */
        private int open(Path path, int flags) throws IOException
        {
            try (Arena arena = Arena.ofConfined())
            {
                MemorySegment name = arena.allocateFrom(path.toString());
                int result = (int) function("open", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT)).invokeExact(name, flags);
                if (result < 0) throw new IOException("Original descriptor open failed for " + path + "; errno=" + errno());
                return result;
            }
            catch (Throwable failure) { throw foreignFailure("open", failure); }
        }

        /**
         * Drains exact opaque bytes only after every actual native consumer has closed.
         * A known Darwin EAGAIN ends the read; any other result preserves failure rather than inventing an empty FIFO.
         * @param descriptor retained independent description of the original now-unlinked FIFO
         * @param capacity explicitly configured total capacity, bounding malformed extra bytes
         * @return unchanged unsigned-byte sequence in actual read order
         * @throws IOException if a read is uncertain, zero, unexpected or exceeds capacity
         */
        private byte[] drain(int descriptor, int capacity) throws IOException
        {
            byte[] result = new byte[capacity];
            int count = 0;
            try (Arena arena = Arena.ofConfined())
            {
                MemorySegment byteValue = arena.allocate(ValueLayout.JAVA_BYTE);
                while (true)
                {
                    long actual = (long) function("read", FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))
                        .invokeExact(descriptor, byteValue, 1L);
                    if (actual == -1)
                    {
                        if (errno() == 35) break;
                        throw new IOException("Opaque FIFO read failed; errno=" + errno());
                    }
                    if (actual != 1 || count == result.length)
                        throw new IOException("Opaque FIFO capacity or exact byte transfer differs");
                    result[count++] = byteValue.get(ValueLayout.JAVA_BYTE, 0);
                }
            }
            catch (Throwable failure) { throw foreignFailure("read", failure); }
            return Arrays.copyOf(result, count);
        }

        /**
         * Consumes one known original owned descriptor close attempt; no caller may replay a failure.
         * @param descriptor actual exclusively owned descriptor
         * @throws IOException if the one native close fails
         */
        private void close(int descriptor) throws IOException
        {
            try
            {
                int result = (int) function("close", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT)).invokeExact(descriptor);
                if (result != 0) throw new IOException("Original descriptor close failed; errno=" + errno());
            }
            catch (Throwable failure) { throw foreignFailure("close", failure); }
        }

        /**
         * Reads constructing-thread Darwin errno immediately after a failed native effect.
         * @return exact current errno
         * @throws IOException if native thread-local errno access fails
         */
        @SuppressWarnings("restricted")
        private int errno() throws IOException
        {
            try
            {
                MemorySegment address = (MemorySegment) function("__error", FunctionDescriptor.of(ValueLayout.ADDRESS)).invokeExact();
                return address.reinterpret(Integer.BYTES).get(ValueLayout.JAVA_INT, 0);
            }
            catch (Throwable failure) { throw foreignFailure("__error", failure); }
        }
    }

    /**
     * Preserves checked native IO failures and exact unchecked/fatal failure categories.
     * @param operation original native boundary
     * @param failure original foreign-call failure
     * @return unchanged checked IO failure or contextual checked adapter failure
     * @throws RuntimeException if the original failure is unchecked
     * @throws Error if the original failure is fatal
     */
    private static IOException foreignFailure(String operation, Throwable failure)
    {
        if (failure instanceof IOException checked) return checked;
        if (failure instanceof RuntimeException unchecked) throw unchecked;
        if (failure instanceof Error fatal) throw fatal;
        return new IOException("Darwin descriptor operation failed: " + operation, failure);
    }

    /**
     * Requires caller-controlled root ownership with no workload group/world rename or file modification authority.
     * @param path existing real trusted file or directory and its entire ancestry
     * @throws IOException if any owner differs or a group/world write bit is set
     */
    private static void requireTrustedRoot(Path path) throws IOException
    {
        for (Path entry = path; entry != null; entry = entry.getParent())
        {
            int uid = ((Number) Files.getAttribute(entry, "unix:uid", LinkOption.NOFOLLOW_LINKS)).intValue();
            int mode = ((Number) Files.getAttribute(entry, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue();
            if (uid != 0 || (mode & 0x12) != 0)
                throw new IOException("Trusted installed tool/state ancestry permits non-root mutation: " + entry);
        }
    }

    /**
     * Checks the original full native authority receipt and conservatively requires its actual PID to be absent.
     * The production receipt's terminal composition proves collection; PID absence alone never grants that proof.
     * @param authority all eighteen original native fields captured before release
     * @throws IOException if authority is incomplete or an original PID is still present, including ambiguous reuse
     */
    private static void requireAuthority(List<Long> authority) throws IOException
    {
        if (authority.size() != 18 || authority.get(0) <= 0 || authority.get(1) <= 0 || authority.get(3) <= 0)
            throw new IOException("Complete original native incarnation and coalition authority is required");
        if (ProcessHandle.of(authority.get(0)).isPresent())
            throw new IOException("Original native root PID still exists or was ambiguously reused: " + authority);
    }

    /**
     * Reads an actual file SHA-256 without interpreting or changing its bytes.
     * @param path exact existing file
     * @return lowercase hexadecimal digest
     * @throws IOException if the original file cannot be read
     */
    private static String sha(Path path) throws IOException
    {
        try (var input = Files.newInputStream(path))
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("Required SHA-256 absent", failure); }
    }

    /**
     * Checks only an exact invocation-owned directory after native closure, without removing uncertain state.
     * @param directory exact retained state directory
     * @return whether no native entry remains
     * @throws IOException if directory enumeration fails
     */
    private static boolean empty(Path directory) throws IOException
    {
        try (var paths = Files.list(directory)) { return paths.findAny().isEmpty(); }
    }
}
