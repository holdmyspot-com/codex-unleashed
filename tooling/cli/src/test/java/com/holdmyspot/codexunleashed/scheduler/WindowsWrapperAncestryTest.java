package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Checks which retained ancestries may register a Windows compiler wrapper. */
public final class WindowsWrapperAncestryTest
{
    /** Pinned packaged wrapper image. */
    private static final Path WRAPPER = Path.of("C:\\scheduler\\codex-cargo-wrapper.exe");
    /** A Cargo build script that runs the compiler itself. */
    private static final Path BUILD_SCRIPT = Path.of("C:\\target\\release\\build\\proc-macro2-1\\build-script-build.exe");
    /** Original Cargo process identifier. */
    private static final long CARGO = 10;
    /** Original Cargo creation time. */
    private static final long CARGO_BIRTH = 100;

    /** Creates independent ancestry checks. */
    public WindowsWrapperAncestryTest() {}

    /** A wrapper that Cargo created directly is its own chain.
     * @throws IOException if the ancestry is rejected
     */
    @Test
    public void acceptsDirectCargoChild() throws IOException
    {
        var peer = new WindowsCargoRegistration.Link(20, 200, CARGO, CARGO_BIRTH, WRAPPER);
        requireThat(chain(peer, Map.of()), "chain").isEqualTo(List.of(peer));
    }

    /** A jpackage restart keeps every consecutive wrapper in the chain.
     * @throws IOException if the ancestry is rejected
     */
    @Test
    public void acceptsRestartedWrapper() throws IOException
    {
        var launcher = new WindowsCargoRegistration.Link(20, 200, CARGO, CARGO_BIRTH, WRAPPER);
        var peer = new WindowsCargoRegistration.Link(21, 210, 20, 200, WRAPPER);
        requireThat(chain(peer, Map.of(20L, launcher)), "chain").isEqualTo(List.of(peer, launcher));
    }

    /** A build script that Cargo created may run the compiler through the wrapper.
     * @throws IOException if the ancestry is rejected
     */
    @Test
    public void acceptsBuildScriptParent() throws IOException
    {
        var script = new WindowsCargoRegistration.Link(30, 300, CARGO, CARGO_BIRTH, BUILD_SCRIPT);
        var peer = new WindowsCargoRegistration.Link(31, 310, 30, 300, WRAPPER);
        requireThat(chain(peer, Map.of(30L, script)), "chain").isEqualTo(List.of(peer));
    }

    /** A registering process that is not the wrapper is rejected. */
    @Test
    public void rejectsForeignPeer()
    {
        var peer = new WindowsCargoRegistration.Link(30, 300, CARGO, CARGO_BIRTH, BUILD_SCRIPT);
        requireException(IOException.class, () -> chain(peer, Map.of()));
    }

    /** An ancestor without retained Cargo Job creation authority is rejected. */
    @Test
    public void rejectsUnretainedAncestor()
    {
        var peer = new WindowsCargoRegistration.Link(31, 310, 30, 300, WRAPPER);
        requireException(IOException.class, () -> chain(peer, Map.of()));
    }

    /** A recycled ancestor process identifier with a different birth is rejected. */
    @Test
    public void rejectsChangedAncestorBirth()
    {
        var script = new WindowsCargoRegistration.Link(30, 999, CARGO, CARGO_BIRTH, BUILD_SCRIPT);
        var peer = new WindowsCargoRegistration.Link(31, 310, 30, 300, WRAPPER);
        requireException(IOException.class, () -> chain(peer, Map.of(30L, script)));
    }

    /** A wrapper above another image is not a restart and is rejected. */
    @Test
    public void rejectsWrapperAboveOtherImage()
    {
        var outer = new WindowsCargoRegistration.Link(20, 200, CARGO, CARGO_BIRTH, WRAPPER);
        var script = new WindowsCargoRegistration.Link(30, 300, 20, 200, BUILD_SCRIPT);
        var peer = new WindowsCargoRegistration.Link(31, 310, 30, 300, WRAPPER);
        requireException(IOException.class, () -> chain(peer, Map.of(30L, script, 20L, outer)));
    }

    /** A different Cargo birth is rejected. */
    @Test
    public void rejectsChangedCargoBirth()
    {
        var peer = new WindowsCargoRegistration.Link(20, 200, CARGO, CARGO_BIRTH + 1, WRAPPER);
        requireException(IOException.class, () -> chain(peer, Map.of()));
    }

    /**
     * Walks a fixture ancestry.
     * @param peer registering process
     * @param retained retained Cargo Job processes by identifier
     * @return the wrapper chain
     * @throws IOException if the ancestry is rejected
     */
    private static List<WindowsCargoRegistration.Link> chain(WindowsCargoRegistration.Link peer,
        Map<Long, WindowsCargoRegistration.Link> retained) throws IOException
    {
        return WindowsCargoRegistration.wrapperChain(peer, pid -> Optional.ofNullable(retained.get(pid)), CARGO, CARGO_BIRTH,
            WRAPPER);
    }
}
