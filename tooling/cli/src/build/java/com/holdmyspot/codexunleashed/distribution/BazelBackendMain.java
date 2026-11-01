package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;

/**
 * Retains the existing native backend build entry point through the shared target-aware producer.
 * Ordinary Maven builds never invoke this driver.
 */
public final class BazelBackendMain
{
    /**
     * Prevents construction of the build entry point.
     */
    private BazelBackendMain() {}

    /**
     * Builds or resumes the pinned backend on a matching native worker.
     * @param args target triple, owned build root, staging root and optional {@code --resume}
     * @throws IOException if acquisition, compilation or identity validation fails
     * @throws InterruptedException if an original child command is interrupted after collection
     * @throws IllegalArgumentException if arguments or root relationships are invalid
     * @throws UnsupportedOperationException if the host or target is unsupported
     */
    public static void main(String[] args) throws IOException, InterruptedException
    {
        BazelNativeBackendMain.main(args);
    }
}
