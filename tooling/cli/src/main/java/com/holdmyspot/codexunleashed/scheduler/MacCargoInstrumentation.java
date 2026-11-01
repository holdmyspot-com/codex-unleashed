package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Owns allocation and jobserver instrumentation for every authenticated image in one native workload.
 * The workload owns image release and the terminal barrier; this owner never resumes a process.
 */
final class MacCargoInstrumentation implements MacImages.InstrumentationFactory
{
    /** Selects whether this workload has compiler allocation permission authority. */
    enum Mode
    {
        /** Compiler images receive allocation and FIFO instrumentation. */
        COMPILER,
        /** Cargo and wrapper images receive FIFO instrumentation without compiler grant authority. */
        ROOT_CARGO
    }
    /** Borrowed scoped serializer services. */
    private final ToolingScope scope;
    /** Unique native attempt correlation shared by its images. */
    private final UUID identity;
    /** Original manager borrowed through image closure. */
    private final FridaRuntime.Manager manager;
    /** Exact image and terminal authority. */
    private final MacWorkloadNative.Owner nativeOwner;
    /** Invocation FIFO lease retained by the caller. */
    private final MacCargoJobserver.CargoLease lease;
    /** Whole-workload token ledger retired by the caller after the terminal barrier. */
    private final LinuxCargoTokens.Attempt tokens;
    /** Shared native allocation batches for this attempt. */
    private final AllocationWaits waits;
    /** Positive readiness and permission acknowledgement bound. */
    private final Duration timeout;
    /** Fair owner-thread native frame service. */
    private final AllocationHook.Pump peers;
    /** Determines whether images have compiler allocation grant authority. */
    private final Mode mode;
    /** Every image generation retains exact ownership until detached retirement or whole-workload collection. */
    private final Map<Long, Image> images = new LinkedHashMap<>();
    /** Highest issued image installation ordinal. */
    private long generation;
    /** Indicates whether permission authority was withdrawn. */
    private boolean stopped;

    /**
     * Retains borrowed invocation and attempt authority without acquiring native resources.
     * @param scope scoped shared services
     * @param identity unique native attempt correlation
     * @param manager original active workload manager
     * @param nativeOwner exact workload supplying image and terminal authority
     * @param lease invocation-owned FIFO lease
     * @param tokens whole-workload token owner, retired by the attempt after workload closure
     * @param waits shared allocation batches
     * @param timeout positive acknowledgement bound
     * @param peers fair owner-thread service
     * @param mode compiler grants or root token instrumentation
     */
    MacCargoInstrumentation(ToolingScope scope, UUID identity, FridaRuntime.Manager manager,
        MacWorkloadNative.Owner nativeOwner, MacCargoJobserver.CargoLease lease,
        LinuxCargoTokens.Attempt tokens, AllocationWaits waits, Duration timeout,
        AllocationHook.Pump peers, Mode mode)
    {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.manager = Objects.requireNonNull(manager, "manager");
        this.nativeOwner = Objects.requireNonNull(nativeOwner, "nativeOwner");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.waits = Objects.requireNonNull(waits, "waits");
        this.timeout = LinuxAttempts.checkedTimeout(timeout);
        this.peers = Objects.requireNonNull(peers, "peers");
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    @Override public MacImages.Instrumentation create(MacImages.Identity image)
    {
        if (stopped) throw new IllegalStateException("Image instrumentation has stopped");
        long selected = Math.incrementExact(generation);
        Image owner = new Image(Objects.requireNonNull(image, "image"), selected);
        images.put(selected, owner);
        generation = selected;
        return owner;
    }

    /**
     * Services copied hook frames while leaving image admissions to the workload owner.
     * @throws IOException if a hook reports a protocol or native failure
     */
    void pump() throws IOException
    {
        for (Image image : images.values())
        {
            if (image.allocation != null && !stopped && !image.retired) image.allocation.pump();
            if (image.tokenHook != null && !stopped && !image.retired) image.tokenHook.pump();
        }
    }

    /**
     * Copies authentic image denial history, including detached and closed installations.
     * @return immutable installation-order history without native polling or permission effects
     */
    List<AllocationHook.Denial> denials()
    {
        return images.values().stream().filter(image -> image.allocation != null)
            .flatMap(image -> image.allocation.denials().stream()).toList();
    }

    /**
     * Acknowledges one selected native allocation wait.
     * @param wait exact image and native wait correlation
     * @throws IOException if its image is absent or acknowledgement fails
     */
    void grant(AllocationWaits.Wait wait) throws IOException
    {
        Image image = images.get(wait.installation());
        if (stopped || image == null || image.retired || image.allocation == null)
            throw new IOException("Allocation wait has no active compiler image");
        image.allocation.grant(wait, peers);
    }

    /** Withdraws all permission authority without waking native waiters. */
    void stop()
    {
        stopped = true;
        waits.stop();
        for (Image image : images.values())
        {
            if (image.allocation != null) image.allocation.stop();
            if (image.tokenHook != null) image.tokenHook.stop();
        }
    }

    /**
     * Reads the packaged Darwin allocation listener.
     * @return exact packaged source
     * @throws IOException if the resource is absent or unreadable
     */
    static String program() throws IOException
    {
        try (InputStream input = MacCargoInstrumentation.class.getResourceAsStream("mac-allocation-hook.js"))
        {
            if (input == null) throw new IOException("Packaged macOS allocation hook is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Retains partial image hook acquisitions before each fallible start operation. */
    private final class Image implements MacImages.Instrumentation
    {
        /** Authenticated held image incarnation. */
        private final MacImages.Identity image;
        /** Never-reused positive installation ordinal. */
        private final long installation;
        /** Partial or active allocation hook, retained before native startup. */
        private AllocationHook allocation;
        /** Partial or active FIFO hook, retained before native startup. */
        private MacCargoTokenHook tokenHook;
        /** Indicates whether hook startup consumed its one opportunity. */
        private boolean attempted;
        /** Indicates successful retirement of every detached exact-image hook. */
        private boolean retired;

        /**
         * Binds one authenticated held image generation.
         * @param image exact workload image
         * @param installation never-reused positive generation
         */
        private Image(MacImages.Identity image, long installation)
        {
            this.image = image;
            this.installation = installation;
        }

        @Override public void start() throws IOException
        {
            if (attempted || stopped) throw new IllegalStateException("Image hooks start once");
            attempted = true;
            nativeOwner.authority(image);
            tokenHook = new MacCargoTokenHook(scope, identity, installation, image.pid(), lease.identity(),
                manager, tokens, MacCargoTokenHook.program(), timeout);
            tokenHook.start();
            tokenHook.awaitReady(peers);
            if (mode == Mode.COMPILER)
            {
                allocation = new AllocationHook(scope, identity, installation, image.pid(), manager,
                    waits, program(), timeout);
                allocation.start();
                allocation.awaitReady(peers);
            }
            nativeOwner.authority(image);
        }

        @Override public boolean detached() throws IOException
        {
            return !retired && tokenHook != null && tokenHook.detached() &&
                (allocation == null || allocation.detached());
        }

        @Override public void closeDetached() throws IOException
        {
            if (retired) return;
            if (!detached()) throw new IOException("Exact instrumentation detachment is unproven");
            if (allocation != null) allocation.closeDetached();
            tokenHook.closeDetached();
            retired = true;
        }

        @Override public void close() throws IOException
        {
            AllocationHook.ExecutionBarrier barrier = () -> nativeOwner.terminalResult().isPresent();
            if (allocation != null) allocation.close(barrier);
            if (tokenHook != null) tokenHook.close(barrier);
        }
    }
}
