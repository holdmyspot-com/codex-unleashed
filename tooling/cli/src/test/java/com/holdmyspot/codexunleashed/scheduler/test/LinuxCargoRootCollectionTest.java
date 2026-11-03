package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxCargoInvocation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks Cargo's attachment-to-collection decisions using independent supervisor and attachment outcomes.
 * These in-process checks establish ordering and failure propagation, not actual kernel zero or native cleanup.
 */
public final class LinuxCargoRootCollectionTest
{
    /**
     * Creates independent root collection checks.
     */
    public LinuxCargoRootCollectionTest() {}

    /**
     * A live whole-workload observation retains attachments and prevents collection acknowledgement.
     * @throws IOException if the controlled observation fails
     */
    @Test
    public void liveWorkloadPreventsAttachmentClosure() throws IOException
    {
        List<String> effects = new ArrayList<>();
        boolean finished = LinuxCargoInvocation.finishRootInstrumentation(() -> false,
            () -> effects.add("attachments"), () -> effects.add("collect"));
        requireThat(finished, "liveWorkloadRetainsOwner").isFalse();
        requireThat(effects, "noEarlyNativeCleanup").isEmpty();
    }

    /**
     * An actual-zero observation permits attachment closure before the collection acknowledgement.
     * @throws IOException if the controlled close fails
     */
    @Test
    public void closesAttachmentsBeforeAcknowledgingCollection() throws IOException
    {
        List<String> effects = new ArrayList<>();
        boolean finished = LinuxCargoInvocation.finishRootInstrumentation(() ->
        {
            effects.add("zero");
            return true;
        }, () -> effects.add("attachments"), () -> effects.add("collect"));
        requireThat(finished, "collectionAcknowledged").isTrue();
        requireThat(effects, "zeroThenNativeClosureThenCollection").isEqualTo(List.of("zero", "attachments", "collect"));
    }

    /**
     * A failed zero observation retains all attachments without inventing a completed collection.
     */
    @Test
    public void failedZeroObservationPreventsCleanup()
    {
        List<String> effects = new ArrayList<>();
        IOException original = new IOException("population unavailable");
        IOException actual = requireException(IOException.class, () -> LinuxCargoInvocation.finishRootInstrumentation(
            () -> { throw original; }, () -> effects.add("attachments"), () -> effects.add("collect")));
        requireThat(actual == original, "exactObservationFailure").isTrue();
        requireThat(effects, "nativeOwnersRetained").isEmpty();
    }

    /**
     * Every attachment failure category prevents collection and preserves the original failure identity.
     */
    @Test
    public void failedAttachmentClosurePreventsCollection()
    {
        for (Throwable original : List.of(new IOException("attachment"), new IllegalStateException("attachment"),
            new AssertionError("attachment")))
        {
            List<String> effects = new ArrayList<>();
            Throwable actual = requireException(Throwable.class, () -> LinuxCargoInvocation.finishRootInstrumentation(
                () -> true, () -> fail(original), () -> effects.add("collect")));
            requireThat(actual == original, "exactAttachmentFailure").isTrue();
            requireThat(effects, "noCollectionAfterUncertainNativeClosure").isEmpty();
        }
    }

    /**
     * A failed collection acknowledgement does not produce the successful handoff result.
     */
    @Test
    public void failedCollectionAcknowledgementRemainsUnresolved()
    {
        List<String> effects = new ArrayList<>();
        IOException original = new IOException("COLLECT uncertain");
        IOException actual = requireException(IOException.class, () -> LinuxCargoInvocation.finishRootInstrumentation(
            () -> true, () -> effects.add("attachments"), () -> { throw original; }));
        requireThat(actual == original, "exactCollectionFailure").isTrue();
        requireThat(effects, "attachmentsClosedBeforeCollectionFailure").isEqualTo(List.of("attachments"));
    }

    /**
     * Reports the controlled attachment fault in its original category.
     * @param failure checked, unchecked or fatal attachment outcome
     * @throws IOException if the controlled failure is checked
     */
    private static void fail(Throwable failure) throws IOException
    {
        if (failure instanceof IOException checked) throw checked;
        if (failure instanceof RuntimeException unchecked) throw unchecked;
        if (failure instanceof Error fatal) throw fatal;
        throw new AssertionError("Unsupported controlled failure", failure);
    }
}
