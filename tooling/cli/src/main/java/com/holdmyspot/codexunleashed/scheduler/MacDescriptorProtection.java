package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Retains the held-image descriptor admission protocol independently of allocation instrumentation. */
public final class MacDescriptorProtection
{
    /** Scoped mapper borrowed for this image's admission. */
    private final JsonMapper json;
    /** Independently retained transport; the image owner closes its native resources. */
    private final AllocationHook.Connection connection;
    /** Independently authenticated image lineage. */
    private final MacImages.Identity identity;
    /** Exact image-generation challenge. */
    private final String marker;
    /** Native descriptor authority borrowed through admission. */
    private final MacImages.Operations operations;
    /** Positive monotonic admission duration in nanoseconds. */
    private final long timeout;
    /** Borrowed monotonic clock. */
    private final LongSupplier clock;
    /** Target-owned mapping; inherited only by an authenticated native fork child. */
    private String registry;
    /** Separately owned Script whose destruction terminates its runtime Worker. */
    private AllocationHook.Connection bootstrap;
    /** Indicates whether destruction of the bootstrap Script has already been attempted. */
    private boolean bootstrapCloseAttempted;
    /** Indicates whether bootstrap Script destruction completed successfully. */
    private boolean bootstrapClosed;
    /** Exact uncertain bootstrap destruction failure, retained without retrying its effect. */
    private Throwable bootstrapFailure;

    /**
     * Retains a resource-free protocol owner before attaching a held image.
     * @param json scoped strict mapper
     * @param connection retained image transport
     * @param identity authenticated held image
     * @param marker exact generation challenge
     * @param operations authenticated complete native observations
     * @param timeout positive nanosecond duration
     * @param clock monotonic readiness clock
     * @throws NullPointerException if a borrowed value is null
     * @throws IllegalArgumentException if timeout is not positive
     */
    MacDescriptorProtection(JsonMapper json, AllocationHook.Connection connection, MacImages.Identity identity,
        String marker, MacImages.Operations operations, long timeout, LongSupplier clock)
    {
        this.json = Objects.requireNonNull(json, "json");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.marker = Objects.requireNonNull(marker, "marker");
        this.operations = Objects.requireNonNull(operations, "operations");
        if (timeout <= 0) throw new IllegalArgumentException("Positive admission duration required");
        this.timeout = timeout;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Authenticates pre-attach provenance and seals a complete post-attach native inventory.
     * @param inheritedRegistry exact retained parent mapping for a native fork, otherwise null
     * @throws IOException if source loading, attachment, native provenance or inventory agreement fails
     */
    void start(String inheritedRegistry) throws IOException
    {
        Inventory before = operations.descriptors(identity);
        if (inheritedRegistry == null && !before.queues().isEmpty())
            throw new IOException("Fresh held image contains unattributed native queues before attachment");
        var configuration = new java.util.LinkedHashMap<String, Object>();
        configuration.put("pid", identity.pid());
        configuration.put("marker", marker);
        configuration.put("inheritedRegistry", inheritedRegistry);
        configuration.put("kqueueSyscall", before.kqueueSyscall());
        // Every gated image loads this script before its release, so it also carries the guard that keeps the image's
        // PATH-searched children resumable under Frida's child gating. The guard follows the protection script so that
        // script's strict-mode directive stays first.
        String script = resource("mac-descriptor-protection.js")
            .replace("@CONFIGURATION@", json.writeValueAsString(configuration))
            .replace("@NATIVE_SOURCE@", json.writeValueAsString(resource("mac-descriptor-protection.c"))) +
            "\n" + MacEntryPatch.withPrelude("mac-spawn-search.js");
        connection.start(script);
        JsonNode installed = envelope(awaitFrame(connection), "mac-descriptor-installed",
            Set.of("kind", "marker", "pid", "registry", "inheritedDescriptors"));
        if (!installed.path("registry").isString() ||
            !installed.path("registry").asString().matches("0x[1-9a-f][0-9a-f]*"))
            throw new IOException("Installed descriptor registry has an invalid address");
        requireDescriptorNumbers(installed.path("inheritedDescriptors"));
        if (!numbers(installed.path("inheritedDescriptors")).equals(before.queues()))
            throw new IOException("Native early-fork registry disagrees with complete pre-attach inventory");

        bootstrap = Objects.requireNonNull(operations.connection(identity.pid()), "bootstrap connection");
        String bootstrapMain = "const configuration = " + json.writeValueAsString(Map.of("marker", marker,
            "pid", identity.pid())) + ";\nconst bootstrap = new Worker('file:///mac-descriptor-bootstrap-worker.js', "
            + "{ onMessage(payload) { if (payload !== configuration.marker) throw new Error('Bootstrap marker differs'); "
            + "send(payload); } });";
        bootstrap.start(packageScripts(bootstrapMain, "export function run() { send(" + json.writeValueAsString(marker) + "); }"));
        JsonNode receipt = json.readTree(awaitFrame(bootstrap));
        if (!receipt.isObject() || !receipt.propertyNames().equals(Set.of("type", "payload")) ||
            !receipt.path("type").isString() || !receipt.path("type").asString().equals("send") ||
            !receipt.path("payload").isString() || !receipt.path("payload").asString().equals(marker))
            throw new IOException("Runtime bootstrap did not acknowledge this image generation");
        closeBootstrap();
        connection.post(json.valueToTree(Map.of("type", "mac-descriptor-bootstrap-completed", "marker", marker,
            "pid", identity.pid())));
        JsonNode payload = ready(json, awaitFrame(connection), identity.pid(), marker);
        if (!payload.path("registry").equals(installed.path("registry")) ||
            !numbers(payload.path("inheritedDescriptors")).equals(before.queues()))
            throw new IOException("Descriptor readiness changed the installed native registry");
        Inventory after = operations.descriptors(identity);
        requireConstants(before, after);
        agree("mac-descriptor-seal", "mac-descriptor-sealed", payload.path("mutations").asInt(), before, after);
        registry = payload.path("registry").asString();
    }

    /**
     * Destroys the bootstrap Script once, terminating its retained runtime Worker.
     * A failed attempt remains retained and is reported again without replaying native destruction.
     * MacImages invokes this during barrier cleanup if admission failed before normal destruction.
     * @throws IOException if destruction failed with a checked failure
     */
    void closeBootstrap() throws IOException
    {
        if (bootstrap == null || bootstrapClosed) return;
        if (!bootstrapCloseAttempted)
        {
            bootstrapCloseAttempted = true;
            try { bootstrap.close(); bootstrapClosed = true; }
            catch (IOException | RuntimeException | Error cause) { bootstrapFailure = cause; }
        }
        if (bootstrapFailure instanceof IOException checked) throw checked;
        if (bootstrapFailure instanceof RuntimeException unchecked) throw unchecked;
        if (bootstrapFailure instanceof Error fatal) throw fatal;
    }

    /**
     * Rejects malformed or duplicate descriptor numbers from a copied native registry.
     * @param values copied descriptor number array
     * @throws IOException if values are missing, duplicated or outside the native registry
     */
    private static void requireDescriptorNumbers(JsonNode values) throws IOException
    {
        if (!values.isArray()) throw new IOException("Native inherited descriptors are not an array");
        Set<Integer> seen = new HashSet<>();
        for (JsonNode descriptor : values)
            if (!nonnegativeInt(descriptor) || descriptor.asInt() >= 65536 || !seen.add(descriptor.asInt()))
                throw new IOException("Inherited native descriptor observation is ambiguous");
    }

    /**
     * Verifies complete native agreement again after caller-required instrumentation is ready.
     * @throws IOException if any private descriptor lifetime changed during the authenticated observation
     */
    void verify() throws IOException
    {
        connection.post(json.valueToTree(Map.of("type", "mac-descriptor-observe", "marker", marker,
            "pid", identity.pid())));
        JsonNode observed = envelope(awaitFrame(connection), "mac-descriptor-observed",
            Set.of("kind", "marker", "pid", "mutations", "fault", "sealed"));
        if (!nonnegativeInt(observed.path("mutations")) || !nonnegativeInt(observed.path("fault")) ||
            observed.path("fault").asInt() != 0 || !nonnegativeInt(observed.path("sealed")) ||
            observed.path("sealed").asInt() != 1)
            throw new IOException("Native descriptor registry is not sealed and healthy");
        Inventory inventory = operations.descriptors(identity);
        agree("mac-descriptor-validate", "mac-descriptor-validated", observed.path("mutations").asInt(), inventory, inventory);
    }

    /**
     * Repeats a complete authenticated observation when a held runtime legitimately changes a descriptor lifetime.
     * Each retry retains the same authority and deadline; no application code is released without exact agreement.
     * @param request native operation that must authenticate the complete observation
     * @param acknowledgement exact successful operation
     * @param mutations native generation preceding the first observation
     * @param reference authenticated SDK constants that every subsequent observation must retain
     * @param inventory first complete independently authenticated kernel descriptor inventory
     * @throws IOException if a frame is malformed, authority changes, transport fails or no stable agreement is reached
     */
    private void agree(String request, String acknowledgement, int mutations, Inventory reference, Inventory inventory)
        throws IOException
    {
        long began = clock.getAsLong();
        while (true)
        {
            if (clock.getAsLong() - began >= timeout) throw new IOException("Descriptor agreement deadline exceeded");
            connection.post(json.valueToTree(Map.of("type", request, "marker", marker, "pid", identity.pid(),
                "mutations", mutations, "descriptors", inventory.queues())));
            String frame = awaitFrame(connection);
            if (clock.getAsLong() - began >= timeout) throw new IOException("Descriptor agreement deadline exceeded");
            if (json.readTree(frame).path("payload").path("kind").asString().equals(acknowledgement))
            {
                requireAcknowledgement(frame, acknowledgement);
                return;
            }
            JsonNode retry = envelope(frame, request + "-retry", Set.of("kind", "marker", "pid", "mutations"));
            if (!nonnegativeInt(retry.path("mutations"))) throw new IOException("Native descriptor retry generation is invalid");
            mutations = retry.path("mutations").asInt();
            inventory = operations.descriptors(identity);
            requireConstants(reference, inventory);
        }
    }

    /**
     * Returns the exact retained mapping only after native admission succeeded.
     * @return target-owned native mapping address
     * @throws IllegalStateException if native admission did not publish a mapping
     */
    String registry()
    {
        if (registry == null) throw new IllegalStateException("Native descriptor admission is incomplete");
        return registry;
    }

    /**
     * Requires stable native SDK constants across one attachment.
     * @param before complete earlier observation
     * @param after complete later observation
     * @throws IOException if native constants changed
     */
    private static void requireConstants(Inventory before, Inventory after) throws IOException
    {
        if (before.kqueueSyscall() != after.kqueueSyscall() || before.kqueueType() != after.kqueueType())
            throw new IOException("Native descriptor SDK constants changed during admission");
    }

    /**
     * Reads one maintained UTF-8 resource without acquiring native resources.
     * @param name exact sibling resource name
     * @return complete resource text
     * @throws IOException if the maintained resource is absent or unreadable
     */
    private static String resource(String name) throws IOException
    {
        try (var stream = MacDescriptorProtection.class.getResourceAsStream(name))
        {
            if (stream == null) throw new IOException("Descriptor protection resource is absent: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Packages the actual entrypoint and temporary runtime-worker asset using pinned Frida ES syntax.
     * @param entrypoint complete source executed in the held image
     * @param worker complete worker source executed only by the entrypoint's Worker
     * @return caller-facing Frida Script package with exact UTF-8 byte sizes
     */
    static String packageScripts(String entrypoint, String worker)
    {
        return "📦\n" + entrypoint.getBytes(StandardCharsets.UTF_8).length + " /mac-descriptor-protection.js\n"
            + worker.getBytes(StandardCharsets.UTF_8).length + " /mac-descriptor-bootstrap-worker.js"
            + "\n✄\n" + entrypoint + "\n✄\n" + worker;
    }

    /**
     * Waits for one copied transport frame within a monotonic bound.
     * @param transport retained Script connection that owes the next receipt
     * @return complete copied message, including a native error envelope for strict rejection
     * @throws IOException if interrupted, detached, unreadable or timed out
     */
    private String awaitFrame(AllocationHook.Connection transport) throws IOException
    {
        long began = clock.getAsLong();
        while (true)
        {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Descriptor admission interrupted");
            var frame = transport.pollMessage();
            if (clock.getAsLong() - began >= timeout) throw new IOException("Descriptor admission deadline exceeded");
            if (frame.isPresent()) return frame.orElseThrow();
            if (transport.detached()) throw new IOException("Descriptor admission image detached");
            try { Thread.sleep(1); }
            catch (InterruptedException cause)
            {
                Thread.currentThread().interrupt();
                throw new IOException("Descriptor admission interrupted", cause);
            }
        }
    }

    /**
     * Requires the exact acknowledgement for one image-generation operation.
     * @param frame copied envelope
     * @param kind expected operation acknowledgement
     * @throws IOException if the copied envelope names another operation or image
     */
    private void requireAcknowledgement(String frame, String kind) throws IOException
    {
        envelope(frame, kind, Set.of("kind", "marker", "pid"));
    }

    /**
     * Reads one exact-generation protocol envelope with no unrecognized fields.
     * @param frame copied transport frame
     * @param kind required operation
     * @param fields exact required payload fields
     * @return authenticated payload
     * @throws IOException if the envelope or challenge is invalid
     */
    private JsonNode envelope(String frame, String kind, Set<String> fields) throws IOException
    {
        JsonNode value = json.readTree(frame);
        JsonNode payload = value.path("payload");
        if (!value.isObject() || value.size() != 2 || !value.path("type").isString() ||
            !value.path("type").asString().equals("send") || !payload.isObject() ||
            !payload.propertyNames().equals(fields) || !payload.path("kind").isString() ||
            !payload.path("kind").asString().equals(kind) || !payload.path("marker").isString() ||
            !payload.path("marker").asString().equals(marker) || !payload.path("pid").isIntegralNumber() ||
            !payload.path("pid").canConvertToLong() || payload.path("pid").asLong() != identity.pid())
            throw new IOException("Descriptor protocol did not authenticate the exact image operation");
        return payload;
    }

    /**
     * Copies an already validated numeric descriptor set in comparison order.
     * @param values validated integral descriptor array
     * @return sorted immutable descriptor numbers
     */
    private static List<Integer> numbers(JsonNode values)
    {
        var result = new ArrayList<Integer>();
        for (JsonNode value : values) result.add(value.asInt());
        return result.stream().sorted().toList();
    }

    /**
     * Copies a complete native descriptor observation.
     * @param number nonnegative descriptor number within the native registry bound
     * @param type native SDK descriptor type
     */
    public record Descriptor(int number, int type)
    {
        /**
         * Rejects a descriptor outside the supported complete observation range.
         * @throws IllegalArgumentException if a number or type is invalid
         */
        public Descriptor
        {
            if (number < 0 || number >= 65536 || type < 0)
                throw new IllegalArgumentException("Native descriptor is outside its supported range");
        }
    }

    /**
     * Retains an independently authenticated complete kernel inventory and SDK constants.
     * @param kqueueSyscall native SDK syscall number
     * @param kqueueType native SDK descriptor kind
     * @param descriptors complete immutable observed rows
     */
    public record Inventory(int kqueueSyscall, int kqueueType, List<Descriptor> descriptors)
    {
        /**
         * Copies complete rows without allowing duplicate numeric identities.
         * @throws NullPointerException if rows or a member are null
         * @throws IllegalArgumentException if constants, capacity or duplicate descriptors are invalid
         */
        public Inventory
        {
            if (kqueueSyscall <= 0 || kqueueType <= 0)
                throw new IllegalArgumentException("Native SDK descriptor constants are unavailable");
            descriptors = List.copyOf(descriptors);
            var numbers = new HashSet<Integer>();
            for (Descriptor descriptor : descriptors)
                if (!numbers.add(descriptor.number()))
                    throw new IllegalArgumentException("Native inventory repeats a descriptor number");
        }

        /**
         * Returns the sorted queue numbers from this complete authenticated observation.
         * @return immutable sorted native queue descriptors
         */
        public List<Integer> queues()
        {
            return descriptors.stream().filter(value -> value.type() == kqueueType)
                .map(Descriptor::number).sorted().toList();
        }
    }

    /**
     * Reads one copied ready envelope for a held image.
     * @param json scoped strict mapper borrowed through this call
     * @param frame copied transport envelope
     * @param pid independently authenticated image process identifier
     * @param marker exact image-generation challenge
     * @return copied ready payload
     * @throws IOException if the envelope does not acknowledge this exact held image
     */
    public static JsonNode ready(JsonMapper json, String frame, long pid, String marker) throws IOException
    {
        Objects.requireNonNull(marker, "marker");
        JsonNode envelope = json.readTree(frame);
        if (!envelope.isObject() || envelope.size() != 2 ||
            !envelope.path("type").isString() || !envelope.path("type").asString().equals("send"))
            throw new IOException("Descriptor readiness is not a copied send envelope");
        JsonNode payload = envelope.path("payload");
        Set<String> fields = Set.of("kind", "marker", "pid", "registry", "mutations", "creators", "closers",
            "inheritedDescriptors");
        if (!payload.isObject() || !payload.propertyNames().equals(fields) ||
            !payload.path("kind").isString() || !payload.path("kind").asString().equals("mac-descriptor-ready") ||
            !payload.path("marker").isString() || !payload.path("marker").asString().equals(marker) ||
            !payload.path("pid").isIntegralNumber() || !payload.path("pid").canConvertToLong() ||
            payload.path("pid").asLong() != pid ||
            !payload.path("registry").isString() || !payload.path("registry").asString().matches("0x[1-9a-f][0-9a-f]*") ||
            !nonnegativeInt(payload.path("mutations")) || !nonnegativeInt(payload.path("creators")) ||
            payload.path("creators").asInt() == 0 || !nonnegativeInt(payload.path("closers")) ||
            payload.path("closers").asInt() == 0 || !payload.path("inheritedDescriptors").isArray())
            throw new IOException("Descriptor readiness does not authenticate this image generation");
        var inherited = new HashSet<Integer>();
        for (JsonNode descriptor : payload.path("inheritedDescriptors"))
            if (!nonnegativeInt(descriptor) || descriptor.asInt() >= 65536 || !inherited.add(descriptor.asInt()))
                throw new IOException("Inherited native descriptor observation is ambiguous");
        return payload;
    }

    /**
     * Checks a copied JSON integer without accepting truncation or a fractional coercion.
     * @param value copied field
     * @return whether the field is an exact nonnegative signed native integer
     */
    private static boolean nonnegativeInt(JsonNode value)
    {
        return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0;
    }
}
