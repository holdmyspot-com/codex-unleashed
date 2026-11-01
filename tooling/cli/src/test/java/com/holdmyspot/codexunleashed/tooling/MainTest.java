package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import org.testng.SkipException;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks command dispatch, Linux configuration refusals and caller-owned stream lifetime.
 */
public final class MainTest
{
    /**
     * Creates independent command-boundary checks.
     */
    public MainTest() {}

    /**
     * The public native-reuse command reports its own argument error through the borrowed diagnostic stream.
     */
    @Test
    public void dispatchesNativeReuseValidation()
    {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(new ByteArrayOutputStream()); PrintStream err = new PrintStream(errors))
        {
            int status = Main.run(new String[]{"validate-release-native"}, new ByteArrayInputStream(new byte[0]), out, err);
            requireThat(status, "status").isEqualTo(1);
            requireThat(errors.toString(), "nativeReuseDiagnostic").contains("Expected native output, producer-support, host and architecture");
        }
    }

    /**
     * Reports missing Linux launch authorization as invalid command input before native construction.
     */
    @Test public void missingLinuxConfigurationIsInvalidInput()
    {
        requireLinuxSelection();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int status = Main.run(arguments(false), new ByteArrayInputStream(new byte[0]),
            new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        requireThat(status, "status").isEqualTo(2);
        requireThat(errors.toString(), "diagnostic").contains("--linux-launch-config");
    }

    /**
     * Refuses arbitrary Java streams before trying to read the supplied nonexistent authorization document.
     */
    @Test public void customStreamsRefusedBeforeConfigurationAccess()
    {
        requireLinuxSelection();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int status = Main.run(arguments(true), new ByteArrayInputStream(new byte[0]),
            new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        requireThat(status, "status").isEqualTo(1);
        requireThat(errors.toString(), "diagnostic").contains("inherited System");
    }

    /**
     * Leaves borrowed input and output streams open and usable after dispatch refuses an unavailable adapter.
     * @throws IOException if fixture cleanup unexpectedly fails
     */
    @Test public void preservesBorrowedStreams() throws IOException
    {
        TrackingInput input = new TrackingInput(new byte[]{42});
        TrackingOutput output = new TrackingOutput();
        TrackingOutput errors = new TrackingOutput();
        try (PrintStream out = new PrintStream(output); PrintStream err = new PrintStream(errors))
        {
            String[] arguments = arguments(false);
            arguments[2] = "bazel";
            requireThat(Main.run(arguments, input, out, err), "status").isEqualTo(1);
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
     * Public Windows command selection rejects unsupported platform or custom streams before reading configuration.
     * Borrowed input remains usable on each platform.
     * @throws IOException if reading the borrowed fixture input fails
     */
    @Test
    public void windowsSelectionRefusesInvalidContextBeforeConfiguration() throws IOException
    {
        List<String> options = new ArrayList<>(List.of(arguments(false)));
        options.addAll(1, List.of("--windows-launch-config", "never-opened-windows.json"));
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        try (ByteArrayInputStream input = new ByteArrayInputStream(new byte[]{42});
            PrintStream out = new PrintStream(new ByteArrayOutputStream()); PrintStream err = new PrintStream(errors))
        {
            int status = Main.run(options.toArray(String[]::new), input, out, err);
            boolean windows = System.getProperty("os.name", "").startsWith("Windows");
            requireThat(status, "preflightRefusalStatus").isEqualTo(windows ? 1 : 2);
            requireThat(errors.toString(), "platformOrStreamRefusal")
                .contains(windows ? "inherited System" : "requires Windows");
            requireThat(input.read(), "borrowedInputStillUsable").isEqualTo(42);
        }
    }

    /**
     * Supplies a literal command whose authorization path is deliberately never opened in these checks.
     * @param configuration indicates whether to supply the Linux authorization option
     * @return complete command arguments
     */
    private static String[] arguments(boolean configuration)
    {
        List<String> arguments = new ArrayList<>(List.of("schedule", "--tool", "cargo", "--workspace", ".",
            "--scratch", ".", "--frida-library", "absent-frida.so", "--headroom-bytes", "0", "--max-jobs", "2",
            "--control-timeout-ms", "1000"));
        if (configuration) arguments.addAll(List.of("--linux-launch-config", "absent-authorization.json"));
        arguments.addAll(List.of("--", "build"));
        return arguments.toArray(String[]::new);
    }

    /**
     * Selects checks for the Linux adapter branch without changing process-wide platform state.
     * @throws SkipException if another platform selects a different adapter
     */
    private static void requireLinuxSelection()
    {
        if (!System.getProperty("os.name").equals("Linux")) throw new SkipException("Linux adapter selection requires Linux");
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
