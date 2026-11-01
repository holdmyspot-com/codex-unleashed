package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises actual helper framing and failure withdrawal with isolated resource-free native boundary substitutes.
 * These tests perform no namespace transition, process dispatch, native load or Frida call.
 */
public final class LinuxBazelHelperMainTest
{
    /**
     * Creates per-method service fixtures without class-scoped mutable state.
     */
    public LinuxBazelHelperMainTest() {}

    /**
     * Admits genuine same-leader exec while rejecting missing, substituted and changed native identities.
     */
    @Test public void authenticatesHeldLeaderExec()
    {
        LinuxAllocationTrace.Identity leader = new LinuxAllocationTrace.Identity(3, 30);
        LinuxAllocationTrace.Held event = new LinuxAllocationTrace.Held(leader, 3, 1, "/program", leader);
        requireThat(LinuxBazelTraceHelper.authenticatedHeldExec(event, leader, 3, 30, 30),
            "unchangedLeaderExec").isTrue();
        requireThat(LinuxBazelTraceHelper.authenticatedHeldExec(event, leader, 3, 29, 30),
            "changedPredecessorBirth").isFalse();
        requireThat(LinuxBazelTraceHelper.authenticatedHeldExec(event, leader, 3, 30, 31),
            "changedObservedBirth").isFalse();
        requireThat(LinuxBazelTraceHelper.authenticatedHeldExec(event,
            new LinuxAllocationTrace.Identity(3, 31), 3, 30, 30), "substitutedFormerBirth").isFalse();
        requireThat(LinuxBazelTraceHelper.authenticatedHeldExec(event, null, 3, 30, 30),
            "missingFormerIdentity").isFalse();
        requireThat(LinuxBazelTraceHelper.authenticatedHeldExec(event, leader, 4, 30, 30),
            "unrelatedProcess").isFalse();
        LinuxAllocationTrace.Held fork = new LinuxAllocationTrace.Held(leader, 3, 0, "/program", leader);
        requireThat(LinuxBazelTraceHelper.authenticatedHeldExec(fork, leader, 3, 30, 30),
            "forkIsNotExec").isFalse();
    }

    /**
     * Preserves exact nonleader migration authority and both genuine retained-birth representations.
     */
    @Test public void authenticatesHeldNonleaderExec()
    {
        LinuxAllocationTrace.Identity former = new LinuxAllocationTrace.Identity(4, 31);
        for (long birth : List.of(30L, 31L))
        {
            LinuxAllocationTrace.Held event = new LinuxAllocationTrace.Held(
                new LinuxAllocationTrace.Identity(3, birth), 3, 1, "/program", former);
            requireThat(LinuxBazelTraceHelper.authenticatedHeldExec(event, former, 3, 30, birth),
                "genuineNonleaderMigration").isTrue();
            requireThat(LinuxBazelTraceHelper.authenticatedHeldExec(event,
                new LinuxAllocationTrace.Identity(5, 31), 3, 30, birth), "substitutedFormerThread").isFalse();
        }
        LinuxAllocationTrace.Held changed = new LinuxAllocationTrace.Held(
            new LinuxAllocationTrace.Identity(3, 32), 3, 1, "/program", former);
        requireThat(LinuxBazelTraceHelper.authenticatedHeldExec(changed, former, 3, 30, 32),
            "unrelatedPostExecBirth").isFalse();
    }

    /**
     * Accepts real namespace controls larger than the independent Frida diagnostic envelope.
     * @throws IOException if a valid bounded frame cannot traverse actual helper dispatch
     */
    @Test public void acceptsLargeNamespaceControl() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.add("init").put("copiedArguments", "x".repeat(11_376));
            fixture.add("close").put("executionImpossible", true);
            fixture.run();
            requireThat(fixture.acquisitions, "originalBackendAcquired").isEqualTo(1);
            requireThat(fixture.responses().size(), "helloAndAcknowledgments").isEqualTo(3);
        }
    }

    /**
     * Accepts an exact maximum-size control while retaining the declared byte framing bound.
     * @throws IOException if a valid boundary frame cannot be serialized or decoded
     */
    @Test public void acceptsMaximumNamespaceControl() throws IOException
    {
        try (DefaultToolingScope scope = new DefaultToolingScope())
        {
            JsonMapper json = scope.getNamespaceControlMapper();
            ObjectNode value = json.createObjectNode().put("schema", LinuxBazelHelperMain.SCHEMA).put("arguments", "");
            int padding = LinuxBazelHelperMain.MAX_FRAME_BYTES - json.writeValueAsBytes(value).length;
            value.put("arguments", "x".repeat(padding));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            LinuxBazelHelperMain.write(new DataOutputStream(bytes), json, value);
            requireThat(bytes.size(), "lengthPrefixAndMaximumFrame").isEqualTo(LinuxBazelHelperMain.MAX_FRAME_BYTES + 4);
            ObjectNode decoded = LinuxBazelHelperMain.read(new DataInputStream(
                new ByteArrayInputStream(bytes.toByteArray())), json);
            requireThat(decoded.path("arguments").asString().length(), "unchangedArguments").isEqualTo(padding);
        }
    }

    /**
     * Rejects malformed, duplicate and trailing controls before any native dispatch.
     * @throws IOException if test-owned input serialization fails
     */
    @Test public void rejectsInvalidNamespaceJson() throws IOException
    {
        try (DefaultToolingScope scope = new DefaultToolingScope())
        {
            String valid = "{\"schema\":\"" + LinuxBazelHelperMain.SCHEMA + "\"}";
            for (String invalid : List.of("{", valid + " {}", "{\"schema\":\"" +
                LinuxBazelHelperMain.SCHEMA + "\",\"schema\":\"" + LinuxBazelHelperMain.SCHEMA + "\"}"))
            {
                byte[] encoded = invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream output = new DataOutputStream(bytes);
                output.writeInt(encoded.length);
                output.write(encoded);
                requireException(tools.jackson.core.JacksonException.class, () -> LinuxBazelHelperMain.read(new DataInputStream(
                    new ByteArrayInputStream(bytes.toByteArray())), scope.getNamespaceControlMapper()));
            }
        }
    }

    /**
     * Rejects a declared oversize frame before requesting any payload bytes.
     * @throws IOException if the test-owned length prefix cannot be serialized
     */
    @Test public void rejectsOversizeNamespaceDeclaration() throws IOException
    {
        try (DefaultToolingScope scope = new DefaultToolingScope())
        {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            new DataOutputStream(bytes).writeInt(LinuxBazelHelperMain.MAX_FRAME_BYTES + 1);
            requireException(IOException.class, () -> LinuxBazelHelperMain.read(new DataInputStream(
                new ByteArrayInputStream(bytes.toByteArray())), scope.getNamespaceControlMapper()));
            ObjectNode oversized = scope.getNamespaceControlMapper().createObjectNode()
                .put("arguments", "x".repeat(LinuxBazelHelperMain.MAX_FRAME_BYTES));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            requireException(IOException.class, () -> LinuxBazelHelperMain.write(new DataOutputStream(output),
                scope.getNamespaceControlMapper(), oversized));
            requireThat(output.size(), "noPartialOversizePublication").isEqualTo(0);
        }
    }

    /**
     * Selects syscall tracing for static original ELF images using either supported native ABI.
     * @throws IOException if the test-owned executable fixture cannot be read or removed
     */
    @Test public void selectsStaticTraceImage() throws IOException
    {
        checkStaticSelection(62, false, true);
        checkStaticSelection(62, true, false);
        checkStaticSelection(183, false, true);
        checkStaticSelection(183, true, false);
        checkStaticSelection(243, false, false);
    }

    /**
     * Exercises the original-image selection decision on a bounded ELF program-header fixture.
     * @param machine ELF architecture identifier
     * @param interpreter indicates whether the original program declares a dynamic interpreter
     * @param selected indicates whether syscall tracing supports this original image
     * @throws IOException if the test-owned fixture cannot be created or removed
     */
    private static void checkStaticSelection(int machine, boolean interpreter, boolean selected) throws IOException
    {
        java.nio.file.Path path = java.nio.file.Files.createTempFile("trace-image-", ".elf");
        try
        {
            java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(120).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            bytes.putInt(0, 0x464c457f).put(4, (byte) 2).put(5, (byte) 1).putShort(18, (short) machine);
            bytes.putLong(32, 64).putShort(54, (short) 56).putShort(56, (short) 1);
            bytes.putInt(64, interpreter ? 3 : 1);
            java.nio.file.Files.write(path, bytes.array());
            try (java.nio.channels.FileChannel image = java.nio.channels.FileChannel.open(path))
            {
                requireThat(LinuxBazelHelperMain.isStaticTraceImage(image), "staticTraceBackendSelected").isEqualTo(selected);
            }
        }
        finally { java.nio.file.Files.delete(path); }
    }

    /**
     * Refuses a static PIE even when an injected runtime could expose glibc malloc.
     * @throws IOException if the per-test retained image cannot be written or closed
     */
    @Test public void rejectsStaticAllocatorImage() throws IOException
    {
        checkAllocatorImage(null, false);
    }

    /**
     * Refuses a musl interpreter for the packaged glibc allocation implementation.
     * @throws IOException if the per-test retained image cannot be written or closed
     */
    @Test public void rejectsMuslAllocatorImage() throws IOException
    {
        checkAllocatorImage("/lib/ld-musl-aarch64.so.1", false);
    }

    /**
     * Accepts the original ARM64 glibc loader declaration without consulting injected modules.
     * @throws IOException if the per-test retained image cannot be written or closed
     */
    @Test public void acceptsOriginalGlibcAllocatorImage() throws IOException
    {
        checkAllocatorImage("/lib/ld-linux-aarch64.so.1", true);
    }

    /**
     * Checks allocator eligibility through an independently encoded ELF64 program-header fixture.
     * @param interpreter original loader, or null for a static PIE
     * @param supported whether the original image satisfies the packaged allocator contract
     * @throws IOException if the per-test image cannot be created or removed
     */
    private static void checkAllocatorImage(String interpreter, boolean supported) throws IOException
    {
        java.nio.file.Path path = java.nio.file.Files.createTempFile("original-allocator-", ".elf");
        try
        {
            byte[] loader = interpreter == null ? new byte[0] :
                (interpreter + "\0").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(120 + loader.length)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            bytes.putInt(0, 0x464c457f).put(4, (byte) 2).put(5, (byte) 1);
            bytes.putShort(16, (short) 3).putShort(18, (short) 183).putLong(32, 64);
            bytes.putShort(54, (short) 56).putShort(56, (short) 1);
            bytes.putInt(64, interpreter == null ? 2 : 3).putLong(72, 120).putLong(96, loader.length);
            bytes.position(120); bytes.put(loader);
            java.nio.file.Files.write(path, bytes.array());
            try (java.nio.channels.FileChannel image = java.nio.channels.FileChannel.open(path))
            {
                if (supported) LinuxBazelHelperMain.requireGlibcAllocatorImage(image);
                else requireException(IOException.class, () -> LinuxBazelHelperMain.requireGlibcAllocatorImage(image));
            }
        }
        finally { java.nio.file.Files.delete(path); }
    }

    /**
     * Preserves original operation order and raw callback text through the real framed service.
     * @throws IOException if valid framing fails
     */
    @Test public void forwardsOriginalFramesAndCommands() throws IOException
    {
        try (var fixture = new Fixture())
        {
            fixture.add("init"); fixture.add("prepareHeld"); fixture.add("install"); fixture.add("resumeImage");
            fixture.add("pump"); fixture.add("grant").put("installation", "1").put("sequence", "7");
            fixture.add("stop"); fixture.add("close").put("executionImpossible", true);
            fixture.run();
            requireThat(fixture.backend.calls, "originalOperations").isEqualTo(
                List.of("prepareHeld", "install", "resumeImage", "pump", "grant:1:7", "stop", "close"));
            List<ObjectNode> responses = fixture.responses();
            requireThat(responses.size(), "helloAndResponses").isEqualTo(9);
            requireThat(responses.get(3).path("frames").get(0).asString(), "unchangedNativeText")
                .isEqualTo("{\"raw\":\"copied\\nframe\"}");
            requireThat(responses.stream().skip(1).allMatch(response -> response.path("ok").asBoolean()),
                "allAcknowledgements").isTrue();
        }
    }

    /**
     * Withdraws all work effects after an uncertain install while preserving explicit stop and cleanup controls.
     * @throws IOException if the service cannot reach its explicit final close
     */
    @Test public void failedInstallNeverResumesOrGrants() throws IOException
    {
        try (var fixture = new Fixture())
        {
            fixture.backend.fail = "install";
            fixture.add("init"); fixture.add("prepareHeld"); fixture.add("install"); fixture.add("resumeImage");
            fixture.add("grant").put("installation", "1").put("sequence", "7");
            fixture.add("stop"); fixture.add("close").put("executionImpossible", true);
            fixture.run();
            requireThat(fixture.backend.calls, "effectsAfterFailure").isEqualTo(List.of("prepareHeld", "install", "stop", "close"));
            List<ObjectNode> responses = fixture.responses();
            requireThat(responses.get(3).path("ok").asBoolean(), "failedInstall").isFalse();
            requireThat(responses.get(3).path("cause").asString(), "originalNativeFailure")
                .isEqualTo("java.io.IOException: Controlled uncertain native effect");
            requireThat(responses.get(4).path("ok").asBoolean(), "withdrawnResume").isFalse();
            requireThat(responses.get(5).path("ok").asBoolean(), "withdrawnGrant").isFalse();
        }
    }

    /**
     * Rejects replayed request identity before repeating any backend operation.
     * @throws IOException if fixture framing cannot be prepared
     */
    @Test public void replayedRequestNeverRepeatsAnEffect() throws IOException
    {
        try (var fixture = new Fixture())
        {
            fixture.add("init"); fixture.add("prepareHeld"); fixture.add("prepareHeld").put("id", "2");
            requireException(IOException.class, fixture::run);
            requireThat(fixture.backend.calls, "solePrepare").isEqualTo(List.of("prepareHeld"));
        }
    }

    /**
     * Requires an actual JSON boolean barrier, allowing later true cleanup without an earlier native close.
     * @throws IOException if the valid final close cannot be served
     */
    @Test public void coercedBarrierCannotCloseNativeResources() throws IOException
    {
        try (var fixture = new Fixture())
        {
            fixture.add("init"); fixture.add("close").put("executionImpossible", "true");
            fixture.add("close").put("executionImpossible", true);
            fixture.run();
            requireThat(fixture.backend.calls, "soleRealBarrierClose").isEqualTo(List.of("close"));
            requireThat(fixture.responses().get(2).path("ok").asBoolean(), "coercionRejected").isFalse();
        }
    }

    /**
     * Keeps a native close failure uncertain and rejects a later attempt instead of repeating it.
     * @throws IOException if fixture preparation fails
     */
    @Test public void uncertainCloseCannotBeReplayed() throws IOException
    {
        try (var fixture = new Fixture())
        {
            fixture.backend.fail = "close";
            fixture.add("init"); fixture.add("close").put("executionImpossible", true);
            fixture.add("close").put("executionImpossible", true);
            requireException(IOException.class, fixture::run);
            requireThat(fixture.backend.calls, "soleUncertainNativeClose").isEqualTo(List.of("close"));
            requireThat(fixture.responses().get(3).path("ok").asBoolean(), "replayRejected").isFalse();
        }
    }

    /**
     * Rejects a declared oversized frame before acquiring a backend owner or allocating its body.
     * @throws IOException if isolated scope setup fails
     */
    @Test public void oversizedFramesFailBeforeBackendAcquisition() throws IOException
    {
        try (var fixture = new Fixture())
        {
            var bytes = new ByteArrayOutputStream();
            new DataOutputStream(bytes).writeInt(LinuxBazelHelperMain.MAX_FRAME_BYTES + 1);
            requireException(IOException.class, () -> LinuxBazelHelperMain.serve(fixture.scope,
                new ByteArrayInputStream(bytes.toByteArray()), fixture.output, request ->
                { fixture.acquisitions++; return fixture.backend; }));
            requireThat(fixture.acquisitions, "noBackendAcquisition").isEqualTo(0);
        }
    }

    /**
     * Distinguishes a truncated frame from an empty successful observation.
     * @throws IOException if isolated scope setup fails
     */
    @Test public void partialFramesAreNotEmptyObservations() throws IOException
    {
        try (var fixture = new Fixture())
        {
            var bytes = new ByteArrayOutputStream();
            var output = new DataOutputStream(bytes);
            output.writeInt(20); output.writeByte('{');
            requireException(IOException.class, () -> LinuxBazelHelperMain.read(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), fixture.json));
        }
    }

    /**
     * Rejects a noncanonical textual identity before acquiring the native backend.
     * @throws IOException if fixture preparation fails
     */
    @Test public void noncanonicalIdentityFailsBeforeInit() throws IOException
    {
        try (var fixture = new Fixture())
        {
            fixture.add("init").put("id", "01");
            requireException(IOException.class, fixture::run);
            requireThat(fixture.acquisitions, "noNativeFactoryCall").isEqualTo(0);
        }
    }

    /**
     * Admits the resolved launch path and the actual interpreter while rejecting an unrelated native argv zero.
     */
    @Test public void matchingExecUsesExecutableIdentityInsteadOfArgvZero()
    {
        try (DefaultToolingScope scope = new DefaultToolingScope())
        {
            JsonMapper json = scope.getNamespaceControlMapper();
            ObjectNode execution = json.createObjectNode().put("executable", "/runtime/interpreter");
            execution.putArray("arguments").add("/runtime/interpreter").add("/work/script");
            ObjectNode launch = json.createObjectNode().put("executable", "/work/script");
            launch.putArray("arguments").add("custom-argv-zero").add("literal argument");

            requireThat(LinuxBazelHelperMain.matchesInitialExec(41, execution, launch,
                new FridaRuntime.Child(41, 41, 1, "/work/script")), "resolvedLaunchAccepted").isTrue();
            requireThat(LinuxBazelHelperMain.matchesInitialExec(41, execution, launch,
                new FridaRuntime.Child(41, 41, 1, "/runtime/interpreter")), "heldInterpreterAccepted").isTrue();
            requireThat(LinuxBazelHelperMain.matchesInitialExec(41, execution, launch,
                new FridaRuntime.Child(41, 41, 1, "custom-argv-zero")), "argvZeroIsNotExecutableIdentity").isFalse();
        }
    }

    /**
     * Rejects another process, parent or origin even when its executable path matches the original launch.
     */
    @Test public void matchingPathDoesNotAuthorizeAnotherProcess()
    {
        try (DefaultToolingScope scope = new DefaultToolingScope())
        {
            ObjectNode execution = scope.getNamespaceControlMapper().createObjectNode()
                .put("executable", "/work/action");
            execution.putArray("arguments").add("/work/action");
            for (FridaRuntime.Child child : List.of(new FridaRuntime.Child(42, 41, 1, "/work/action"),
                new FridaRuntime.Child(41, 42, 1, "/work/action"),
                new FridaRuntime.Child(41, 41, 0, "/work/action"),
                new FridaRuntime.Child(41, 41, 1, "/work/other")))
                requireThat(LinuxBazelHelperMain.matchesInitialExec(41, execution, execution, child),
                    "differentSelfExecIdentity").isFalse();
            requireThat(LinuxBazelHelperMain.matchesInitialExec(41, execution, execution,
                new FridaRuntime.Child(41, 41, 1, "/work/action")), "directExecutableSelfExec").isTrue();
        }
    }

    /**
     * Keeps descendant hook readiness and original-manager resume as separate private-pipe effects.
     * @throws IOException if valid isolated framing fails
     */
    @Test public void descendantInstallationDoesNotImplicitlyResume() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.add("init"); fixture.add("installHeld"); fixture.add("pump"); fixture.add("resumeHeld");
            fixture.add("close").put("executionImpossible", true);
            fixture.run();
            requireThat(fixture.backend.calls, "separateHeldEffects").isEqualTo(List.of("installHeld", "pump", "resumeHeld", "close"));
            requireThat(fixture.responses().get(2).path("ok").asBoolean(), "installationAcknowledged").isTrue();
        }
    }

    /**
     * Withholds descendant continuation after its uncertain hook installation.
     * @throws IOException if explicit final cleanup framing fails
     */
    @Test public void failedDescendantInstallationWithdrawsResume() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.backend.fail = "installHeld";
            fixture.add("init"); fixture.add("installHeld"); fixture.add("resumeHeld");
            fixture.add("close").put("executionImpossible", true);
            fixture.run();
            requireThat(fixture.backend.calls, "noUnreadyDescendantResume").isEqualTo(List.of("installHeld", "close"));
            requireThat(fixture.responses().get(3).path("ok").asBoolean(), "unreadyResumeRejected").isFalse();
        }
    }

    /**
     * Preserves ordinary and signaled native root results separately from workload completion.
     * @throws IOException if actual helper framing or response decoding fails
     */
    @Test public void copiesRootTerminalWithoutInventingWholeWorkloadCompletion() throws IOException
    {
        for (int status : List.of(23 << 8, 5, 5 | 128))
        {
            try (Fixture fixture = new Fixture())
            {
                fixture.backend.terminal = new LinuxAllocationTrace.Terminal(new LinuxAllocationTrace.Identity(41, 900), status);
                fixture.add("init");
                fixture.add("pump");
                fixture.add("close").put("executionImpossible", true);
                fixture.run();
                ObjectNode response = fixture.responses().get(2);
                requireThat(response.path("ok").asBoolean(), "successfulPump").isTrue();
                requireThat(response.path("rootTerminal").path("waitStatus").isInt(), "nativeStatusPresent").isTrue();
                requireThat(response.path("rootTerminal").path("waitStatus").asInt(), "unmodifiedKernelStatus").isEqualTo(status);
                requireThat(response.path("rootTerminal").path("identity").path("tid").asInt(), "retainedRootPid").isEqualTo(41);
                requireThat(response.path("rootTerminal").path("identity").path("birthTicks").asLong(), "retainedRootBirth").isEqualTo(900L);
                requireThat(fixture.backend.calls, "noImplicitStopOrResume").isEqualTo(List.of("pump", "close"));
            }
        }
    }

    /**
     * Owns every in-memory stream and pouch per test invocation.
     */
    private static final class Fixture implements AutoCloseable
    {
        /** Original per-test pouch scope. */
        private final DefaultToolingScope scope = new DefaultToolingScope();
        /** Borrowed strict mapper. */
        private final JsonMapper json = scope.getNamespaceControlMapper();
        /** Controls encoded only when run begins, allowing explicit malformed test identities. */
        private final List<ObjectNode> requests = new ArrayList<>();
        /** Original output receipt stream. */
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        /** Single controlled native-access boundary. */
        private final Backend backend = new Backend();
        /** Number of original backend factory calls. */
        private int acquisitions;

        /**
         * Creates isolated in-memory ownership.
         */
        Fixture() {}

        /**
         * Appends the next exact command identity.
         * @param operation package-owned operation
         * @return mutable fixture request
         */
        ObjectNode add(String operation)
        {
            ObjectNode request = json.createObjectNode().put("schema", LinuxBazelHelperMain.SCHEMA)
                .put("id", Integer.toString(requests.size() + 1)).put("operation", operation);
            requests.add(request);
            return request;
        }

        /**
         * Runs actual service dispatch against controlled framed host input.
         * @throws IOException if framing or final service completion fails
         */
        void run() throws IOException
        {
            var input = new ByteArrayOutputStream();
            for (ObjectNode request : requests)
                LinuxBazelHelperMain.write(new DataOutputStream(input), json, request);
            LinuxBazelHelperMain.serve(scope, new ByteArrayInputStream(input.toByteArray()), output,
                request -> { acquisitions++; return backend; });
        }

        /**
         * Decodes all actual emitted response frames.
         * @return complete original response receipts, including HELLO
         * @throws IOException if service emitted malformed framing
         */
        List<ObjectNode> responses() throws IOException
        {
            var input = new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
            List<ObjectNode> values = new ArrayList<>();
            while (input.available() > 0) values.add(LinuxBazelHelperMain.read(input, json));
            return List.copyOf(values);
        }

        @Override public void close() { scope.close(); }
    }

    /**
     * Substitutes only the external native access boundary, preserving actual framing and dispatch.
     */
    private static final class Backend implements LinuxBazelHelperMain.Backend
    {
        /** Observed original native-effect operations. */
        private final List<String> calls = new ArrayList<>();
        /** Operation whose effect is followed by an uncertain failure. */
        private String fail;
        /** Pending copied raw frame created by the install operation. */
        private boolean copied;
        /** Exact copied root result supplied independently of descendant completion. */
        private LinuxAllocationTrace.Terminal terminal;

        /**
         * Creates a resource-free controlled boundary.
         */
        Backend() {}

        @Override public void prepareHeld() throws IOException { effect("prepareHeld"); }
        @Override public void install() throws IOException { effect("install"); copied = true; }
        @Override public void resumeImage() throws IOException { effect("resumeImage"); }
        @Override public void pump() throws IOException { effect("pump"); }
        @Override public LinuxAllocationTrace.Terminal rootTerminal() { return terminal; }
        @Override public void installHeld(ObjectNode request) throws IOException { effect("installHeld"); }
        @Override public void resumeHeld(ObjectNode request) throws IOException { effect("resumeHeld"); }
        @Override public void grant(AllocationWaits.Wait wait) throws IOException
        { effect("grant:" + wait.installation() + ":" + wait.sequence()); }
        @Override public void stop() throws IOException { effect("stop"); }
        @Override public void close() throws IOException { effect("close"); }
        @Override public LinuxBazelHelperMain.Snapshot drain()
        {
            boolean send = copied;
            copied = false;
            return new LinuxBazelHelperMain.Snapshot(send ? List.of("{\"raw\":\"copied\\nframe\"}") : List.of(), List.of());
        }

        /**
         * Records the effect before optionally failing, modeling an uncertain native operation.
         * @param operation original operation identity
         * @throws IOException if the configured effect fails after being recorded
         */
        private void effect(String operation) throws IOException
        {
            calls.add(operation);
            if (operation.equals(fail)) throw new IOException("Controlled uncertain native effect");
        }
    }
}
