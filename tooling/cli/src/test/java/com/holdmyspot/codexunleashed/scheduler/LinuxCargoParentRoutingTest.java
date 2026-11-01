package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks selection of independently retained Cargo parent births before native registration authentication.
 * These checks establish selection only; the existing authenticator remains responsible for native authority.
 */
public final class LinuxCargoParentRoutingTest
{
    /**
     * Creates method-owned parent selection checks.
     */
    public LinuxCargoParentRoutingTest() {}

    /**
     * Selects the actual direct nested parent rather than reusing the root authenticator.
     * @throws IOException if a retained parent cannot be selected
     */
    @Test public void selectsNestedParentBirth() throws IOException
    {
        LinuxBorrowedStreams.Peer root = new LinuxBorrowedStreams.Peer(10, 100, 1000, 1000);
        LinuxBorrowedStreams.Peer nested = new LinuxBorrowedStreams.Peer(20, 200, 1000, 1000);
        Map<Long, LinuxBorrowedStreams.Peer> retained = Map.of(root.pid(), root, nested.pid(), nested);
        requireThat(LinuxCargoInvocation.retainedCargoParent(retained, nested.pid()), "selectedParent").isEqualTo(nested);
        requireException(IOException.class, () -> LinuxCargoInvocation.retainedCargoParent(retained, 30));
    }

    /**
     * Uses a newly independently observed birth when the native PID is reused.
     * @throws IOException if the replaced parent cannot be selected
     */
    @Test public void selectsCurrentRetainedBirth() throws IOException
    {
        Map<Long, LinuxBorrowedStreams.Peer> retained = new LinkedHashMap<>();
        retained.put(10L, new LinuxBorrowedStreams.Peer(10, 100, 1000, 1000));
        LinuxBorrowedStreams.Peer current = new LinuxBorrowedStreams.Peer(10, 200, 1000, 1000);
        retained.put(current.pid(), current);
        requireThat(LinuxCargoInvocation.retainedCargoParent(retained, 10), "currentBirth").isEqualTo(current);
    }
}
