package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.AllocationHooks;
import com.holdmyspot.codexunleashed.scheduler.FridaRuntime;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Checks invocation-owned cross-manager routing without native acquisition or fabricated action authority. */
public final class LinuxCargoRootDescendantTest
{
    /** Creates method-owned routing checks. */
    public LinuxCargoRootDescendantTest() {}

    /** A foreign receiver delivers only to the independently selected original image controller.
     * @throws IOException if controlled setup or expected successful readiness fails
     */
    @Test
    public void routesCrossOwnerDeliveryToOriginalController() throws IOException
    {
        var routing = new AllocationHooks.Routing();
        List<FridaRuntime.Child> metadata = new ArrayList<>();
        List<FridaRuntime.Child> attempt = new ArrayList<>();
        var cargo = routing.register(pid -> pid == 10, metadata::add);
        var compiler = routing.register(pid -> pid == 11, attempt::add);
        var child = event();
        routing.route(cargo, child);
        requireThat(metadata.isEmpty(), "metadataDidNotAdoptCompiler").isTrue();
        requireThat(attempt, "originalAttemptQueue").isEqualTo(List.of(child));
        requireException(IOException.class, () -> routing.prepare(cargo, child));
        routing.prepare(compiler, child);
        routing.completed(compiler, child);
        cargo.close();
        compiler.close();
    }

    /** Copied pending deliveries from two sources retain one transition and one continuation capability.
     * @throws IOException if controlled setup or expected successful readiness fails
     */
    @Test
    public void coalescesPendingCopiesWithoutDoubleResume() throws IOException
    {
        var routing = new AllocationHooks.Routing();
        List<FridaRuntime.Child> queue = new ArrayList<>();
        var cargo = routing.register(pid -> false, ignored -> {});
        var compiler = routing.register(pid -> true, queue::add);
        routing.route(cargo, event());
        routing.route(compiler, event());
        requireThat(queue.size(), "oneHeldTransition").isEqualTo(1);
        routing.prepare(compiler, event());
        routing.completed(compiler, event());
        requireException(IOException.class, () -> routing.prepare(compiler, event()));
    }

    /** A source that already delivered a completed transition reports an identical event only for a new held
     * transition, such as a process executing the same path again, which its owner receives and continues once.
     * @throws IOException if controlled setup or expected successful readiness fails
     */
    @Test
    public void acceptsRepeatedTransitionFromSameSourceAfterCompletion() throws IOException
    {
        var routing = new AllocationHooks.Routing();
        List<FridaRuntime.Child> queue = new ArrayList<>();
        var compiler = routing.register(pid -> true, queue::add);
        routing.route(compiler, event());
        routing.prepare(compiler, event());
        routing.completed(compiler, event());
        routing.route(compiler, event());
        requireThat(queue.size(), "secondHeldTransition").isEqualTo(2);
        routing.prepare(compiler, event());
        routing.completed(compiler, event());
        requireException(IOException.class, () -> routing.prepare(compiler, event()));
    }

    /** Another source's late copy of a completed transition grants no second continuation and is not delivered.
     * @throws IOException if controlled setup or expected successful readiness fails
     */
    @Test
    public void ignoresLateCopyFromAnotherSourceAfterCompletion() throws IOException
    {
        var routing = new AllocationHooks.Routing();
        List<FridaRuntime.Child> queue = new ArrayList<>();
        var cargo = routing.register(pid -> false, ignored -> {});
        var compiler = routing.register(pid -> true, queue::add);
        routing.route(compiler, event());
        routing.prepare(compiler, event());
        routing.completed(compiler, event());
        routing.route(cargo, event());
        requireThat(queue.size(), "lateCopyNotDelivered").isEqualTo(1);
        requireException(IOException.class, () -> routing.prepare(compiler, event()));
        routing.route(cargo, event());
        requireThat(queue.size(), "cargoRepeatIsNewTransition").isEqualTo(2);
    }

    /** A canceled original owner never transfers continuation to another manager.
     * @throws IOException if controlled setup or expected successful readiness fails
     */
    @Test
    public void rejectsCancellationBeforeContinuation() throws IOException
    {
        var routing = new AllocationHooks.Routing();
        var compiler = routing.register(pid -> true, ignored -> {});
        routing.route(compiler, event());
        compiler.cancel();
        requireException(IOException.class, () -> routing.prepare(compiler, event()));
        requireException(IOException.class, () -> routing.route(compiler, event()));
    }

    /** A possibly effective native resume cannot be authorized again after its original call throws.
     * @throws IOException if controlled setup or expected successful readiness fails
     */
    @Test
    public void rejectsUncertainContinuationReplay() throws IOException
    {
        var routing = new AllocationHooks.Routing();
        var compiler = routing.register(pid -> true, ignored -> {});
        routing.route(compiler, event());
        routing.prepare(compiler, event());
        requireException(IOException.class, () -> routing.prepare(compiler, event()));
        requireException(IOException.class, () -> routing.route(compiler, event()));
    }

    /** Ambiguous containment never selects the first manager by iteration order. */
    @Test
    public void rejectsAmbiguousOwnership()
    {
        var routing = new AllocationHooks.Routing();
        var first = routing.register(pid -> true, ignored -> { throw new IllegalStateException("Unexpected delivery"); });
        routing.register(pid -> true, ignored -> { throw new IllegalStateException("Unexpected delivery"); });
        requireException(IOException.class, () -> routing.route(first, event()));
    }

    /** An event without actual owned membership cannot create an image or compiler action. */
    @Test
    public void rejectsUnownedDelivery()
    {
        var routing = new AllocationHooks.Routing();
        var first = routing.register(pid -> false, ignored -> { throw new IllegalStateException("Unexpected delivery"); });
        requireException(IOException.class, () -> routing.route(first, event()));
        first.close();
        requireException(IOException.class, () -> routing.route(first, event()));
    }

    /** Returns an immutable self-parented EXEC notification with no independent authority.
     * @return copied native event
     */
    private static FridaRuntime.Child event()
    { return new FridaRuntime.Child(11, 11, 1, Path.of("compiler").toAbsolutePath().toString()); }
}
