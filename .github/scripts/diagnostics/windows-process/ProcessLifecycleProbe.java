import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reports process-start, wait, output-read, close, and capture-file cleanup timings
 * for fixed cmd.exe and git.exe commands on Windows.
 */
class ProcessLifecycleProbe {
    /**
     * Writes and flushes one lifecycle-stage timing record to standard output.
     *
     * @param command diagnostic command label
     * @param stage operation being measured
     * @param state begin or end marker
     * @param elapsed operation duration in nanoseconds; zero at its beginning
     * @param pid child process ID, or zero before the child starts
     */
    static void emitStageTiming(String command, String stage, String state, long elapsed, long pid) {
        System.out.printf("command=%s stage=%s state=%s elapsed_ns=%d child_pid=%d%n",
            command, stage, state, elapsed, pid);
        System.out.flush();
    }

    /**
     * Runs a command with inherited standard input and file-captured output, reporting
     * lifecycle timings, exit status, and output character counts. Reports recognized
     * Git version output when label is {@code git}. Closes the child process and
     * attempts to delete its capture files and directory, including on failure.
     *
     * @param label command label used in diagnostic records
     * @param command executable and arguments to run directly
     * @param directory existing working directory in which temporary captures are created
     * @return sum of measured setup, start, wait, output-read, close, and deletion
     *         durations in nanoseconds; excludes time spent emitting records
     * @throws IOException if process launch, capture access, or cleanup fails, or the
     *         command returns a nonzero exit status
     * @throws InterruptedException if waiting for the command is interrupted
     * @throws Exception if another process or filesystem operation fails
     */
    static long measureCommandLifecycle(String label, List<String> command, Path directory) throws Exception {
        emitStageTiming(label, "capture_setup", "begin", 0, 0);
        long setupBegan = System.nanoTime();
        Path capture = Files.createTempDirectory(directory, "command-capture-");
        long setupElapsed = System.nanoTime() - setupBegan;
        emitStageTiming(label, "capture_setup", "end", setupElapsed, 0);
        Path stdout = capture.resolve("stdout");
        Path stderr = capture.resolve("stderr");
        Process process = null;
        long pid = 0;
        long operationNanos = setupElapsed;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
            emitStageTiming(label, "start", "begin", 0, pid);
            long began = System.nanoTime();
            process = builder.start();
            long elapsed = System.nanoTime() - began;
            pid = process.pid();
            operationNanos += elapsed;
            emitStageTiming(label, "start", "end", elapsed, pid);

            emitStageTiming(label, "waitFor", "begin", 0, pid);
            began = System.nanoTime();
            int status = process.waitFor();
            elapsed = System.nanoTime() - began;
            operationNanos += elapsed;
            emitStageTiming(label, "waitFor", "end", elapsed, pid);
            System.out.printf("command=%s exit_code=%d child_pid=%d%n", label, status, pid);

            emitStageTiming(label, "output_read", "begin", 0, pid);
            began = System.nanoTime();
            String standardOutput = Files.readString(stdout);
            String standardError = Files.readString(stderr);
            elapsed = System.nanoTime() - began;
            operationNanos += elapsed;
            emitStageTiming(label, "output_read", "end", elapsed, pid);
            System.out.printf("command=%s stdout_chars=%d stderr_chars=%d child_pid=%d%n",
                label, standardOutput.length(), standardError.length(), pid);
            if (label.equals("git")) {
                if (standardOutput.matches("git version [0-9A-Za-z.+() _-]+\\R?")) {
                    System.out.println("tool_identity=" + standardOutput.strip());
                }
            }
            if (status != 0) {
                throw new IOException("Diagnostic command returned nonzero status: " + label + " status=" + status);
            }
        } finally {
            try {
                if (process != null) {
                    emitStageTiming(label, "close", "begin", 0, pid);
                    long began = System.nanoTime();
                    process.close();
                    long elapsed = System.nanoTime() - began;
                    operationNanos += elapsed;
                    emitStageTiming(label, "close", "end", elapsed, pid);
                }
            } finally {
                for (Path file : List.of(stdout, stderr, capture)) {
                    String stage = "delete_" + file.getFileName();
                    if (file.equals(capture)) {
                        stage = "delete_capture_directory";
                    }
                    emitStageTiming(label, stage, "begin", 0, pid);
                    long began = System.nanoTime();
                    Files.deleteIfExists(file);
                    long elapsed = System.nanoTime() - began;
                    operationNanos += elapsed;
                    emitStageTiming(label, stage, "end", elapsed, pid);
                }
            }
        }
        emitStageTiming(label, "command_total_operations", "end", operationNanos, pid);
        return operationNanos;
    }

    /**
     * Measures {@code cmd.exe /d /c exit 0}, then {@code git.exe --version} on Windows.
     * Stops after cmd when its summed measured operation durations reach ten seconds;
     * reports the same threshold after Git. This threshold does not bound a command's
     * running time. Prints runtime identity and diagnostic records to standard output.
     *
     * @param arguments absolute paths to existing cmd.exe and git.exe files, followed
     *                  by an absolute existing working directory owned by the caller
     * @throws IllegalArgumentException if there are not exactly three arguments, either
     *         executable path is not absolute or not a regular file, or the directory
     *         path is not absolute or not an existing directory
     * @throws Exception if either diagnostic command fails or capture cleanup fails
     */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            throw new IllegalArgumentException("Pass absolute cmd.exe, git.exe and an owned existing directory");
        }
        Path cmd = Path.of(arguments[0]);
        Path git = Path.of(arguments[1]);
        Path directory = Path.of(arguments[2]);
        for (Path executable : List.of(cmd, git)) {
            if (!executable.isAbsolute() || !Files.isRegularFile(executable)) {
                throw new IllegalArgumentException("Executable must be an absolute existing file: " + executable);
            }
        }
        if (!directory.isAbsolute() || !Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Working directory must be absolute and existing: " + directory);
        }
        System.out.printf("probe_pid=%d java_runtime=%s java_vendor=%s%n", ProcessHandle.current().pid(),
            System.getProperty("java.runtime.version"), System.getProperty("java.vendor"));
        System.out.println("cmd_executable=" + cmd);
        System.out.println("git_executable=" + git);
        long slowCommandNanos = 10_000_000_000L;
        long elapsed = measureCommandLifecycle("cmd", List.of(cmd.toString(), "/d", "/c", "exit", "0"), directory);
        if (elapsed >= slowCommandNanos) {
            System.out.println("diagnostic=stopped_after_slow_command command=cmd");
            return;
        }
        elapsed = measureCommandLifecycle("git", List.of(git.toString(), "--version"), directory);
        if (elapsed >= slowCommandNanos) {
            System.out.println("diagnostic=stopped_after_slow_command command=git");
        }
    }
}
