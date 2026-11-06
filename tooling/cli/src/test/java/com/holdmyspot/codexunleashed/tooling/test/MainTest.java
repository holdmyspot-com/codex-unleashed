package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.Main;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks command dispatch and caller-owned stream lifetime.
 */
public final class MainTest
{
    /**
     * Creates independent command-boundary checks.
     */
    public MainTest() {}

    /**
     * Reports an unknown command as invalid arguments and lists the supported commands.
     */
    @Test
    public void unknownCommandReportsUsage()
    {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(new ByteArrayOutputStream()); PrintStream err = new PrintStream(errors))
        {
            int status = Main.run(new String[]{"schedule"}, new ByteArrayInputStream(new byte[0]), out, err);
            requireThat(status, "status").isEqualTo(2);
            requireThat(errors.toString(), "usage").contains("Usage: codex-tooling").doesNotContain("schedule");
        }
    }

    /**
     * Leaves borrowed input and output streams open and usable after dispatch reports an argument error.
     * @throws IOException if fixture cleanup unexpectedly fails
     */
    @Test public void preservesBorrowedStreams() throws IOException
    {
        TrackingInput input = new TrackingInput(new byte[]{42});
        TrackingOutput output = new TrackingOutput();
        TrackingOutput errors = new TrackingOutput();
        try (PrintStream out = new PrintStream(output); PrintStream err = new PrintStream(errors))
        {
            requireThat(Main.run(new String[]{"upstream-v8-script"}, input, out, err), "status").isEqualTo(2);
            requireThat(input.closed, "inputClosed").isFalse();
            requireThat(output.closed, "outputClosed").isFalse();
            requireThat(errors.closed, "errorClosed").isFalse();
            requireThat(input.read(), "remainingInput").isEqualTo(42);
            out.print("still-open");
            requireThat(output.toString(), "usableOutput").contains("still-open");
        }
        finally { input.close(); }
    }

    /**
     * Models caller-owned input and records any attempted ownership violation.
     */
    private static final class TrackingInput extends ByteArrayInputStream
    {
        /** Indicates whether an owner has closed this input. */
        private boolean closed;
        /**
         * Creates test-owned input bytes.
         * @param bytes bytes available to the command caller
         */
        private TrackingInput(byte[] bytes) { super(bytes); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }

    /**
     * Models caller-owned output and records any attempted ownership violation.
     */
    private static final class TrackingOutput extends ByteArrayOutputStream
    {
        /** Indicates whether an owner has closed this output. */
        private boolean closed;
        /**
         * Creates empty test-owned output.
         */
        private TrackingOutput() {}
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}
