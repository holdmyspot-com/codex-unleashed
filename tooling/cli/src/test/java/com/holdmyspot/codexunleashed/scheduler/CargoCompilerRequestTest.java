package com.holdmyspot.codexunleashed.scheduler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Checks immutable invocation data without treating it as native authentication evidence. */
public final class CargoCompilerRequestTest
{
    /** Creates focused request tests. */
    public CargoCompilerRequestTest() {}
    /**
     * Creates one modeled authenticated request with an unknown estimate.
     * @param number unique action suffix
     * @return immutable request without native resources
     */
    static CargoCompilerRequest request(long number)
    {
        return new CargoCompilerRequest(new UUID(0, number), new CargoCompilerRequest.Wrapper(10, 20, 30, 40),
            List.of(Path.of("/compiler").toAbsolutePath().toString(), "", "a\nb", "λ"),
            Path.of("/working/../exact").toAbsolutePath(), Map.of("CARGO_MAKEFLAGS", "borrowed", "EMPTY", ""), null);
    }
    /** Arguments, environment, paths and unknown demand retain their exact distinctions. */
    @Test public void copiesExactInvocationWithoutNormalizing()
    {
        var original = request(1);
        var arguments = new ArrayList<>(original.arguments());
        var environment = new HashMap<>(original.environment());
        var copy = new CargoCompilerRequest(original.identity(), original.wrapper(), arguments, original.directory(), environment, null);
        arguments.clear(); environment.clear();
        requireThat(copy, "exactCopiedRequest").isEqualTo(original);
        requireThat(copy.directory().toString().contains(".."), "cwdNotNormalized").isTrue();
        requireThat(copy.peakBytes(), "unknownDemand").isNull();
        var zero = new CargoCompilerRequest(original.identity(), original.wrapper(), original.arguments(), original.directory(), original.environment(), 0L);
        requireThat(zero.peakBytes(), "explicitZero").isEqualTo(0L);
    }
    /** Native text and launch validation reject data that cannot reach a compiler unchanged. */
    @Test public void rejectsUnrepresentableInvocation()
    {
        var r = request(1);
        requireException(IllegalArgumentException.class, () -> new CargoCompilerRequest(r.identity(), r.wrapper(),
            List.of("relative-rustc"), r.directory(), r.environment(), null));
        requireException(IllegalArgumentException.class, () -> new CargoCompilerRequest(r.identity(), r.wrapper(),
            r.arguments(), Path.of("relative"), r.environment(), null));
        requireException(IllegalArgumentException.class, () -> new CargoCompilerRequest(r.identity(), r.wrapper(),
            List.of(r.arguments().getFirst(), "bad\0arg"), r.directory(), r.environment(), null));
        requireException(IllegalArgumentException.class, () -> new CargoCompilerRequest(r.identity(), r.wrapper(),
            r.arguments(), r.directory(), Map.of("K", "bad\0value"), null));
        requireException(IllegalArgumentException.class, () -> new CargoCompilerRequest(r.identity(), r.wrapper(),
            r.arguments(), r.directory(), r.environment(), -1L));
    }
    /** Birth identities stay explicit; self-parent and missing values never become zero defaults. */
    @Test public void rejectsMissingOrCollapsedPeerIdentities()
    {
        requireException(IllegalArgumentException.class, () -> new CargoCompilerRequest.Wrapper(10, 0, 20, 30));
        requireException(IllegalArgumentException.class, () -> new CargoCompilerRequest.Wrapper(10, 20, 10, 30));
        var r = request(1);
        requireThat(r.wrapper(), "exactBirthReceipt").isEqualTo(new CargoCompilerRequest.Wrapper(10, 20, 30, 40));
    }
}
