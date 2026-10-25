import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class ProcessLifecycleProbe {
    static void emit(String command, String stage, String state, long elapsed, long pid) {
        System.out.printf("command=%s stage=%s state=%s elapsed_ns=%d child_pid=%d%n",
            command, stage, state, elapsed, pid);
        System.out.flush();
    }

    static long capture(String label, List<String> command, Path directory) throws Exception {
        emit(label, "capture_setup", "begin", 0, 0);
        long setupBegan = System.nanoTime();
        Path capture = Files.createTempDirectory(directory, "command-capture-");
        long setupElapsed = System.nanoTime() - setupBegan;
        emit(label, "capture_setup", "end", setupElapsed, 0);
        Path stdout = capture.resolve("stdout");
        Path stderr = capture.resolve("stderr");
        Process process = null;
        long pid = 0;
        long operationNanos = setupElapsed;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
            emit(label, "start", "begin", 0, pid);
            long began = System.nanoTime();
            process = builder.start();
            long elapsed = System.nanoTime() - began;
            pid = process.pid();
            operationNanos += elapsed;
            emit(label, "start", "end", elapsed, pid);

            emit(label, "waitFor", "begin", 0, pid);
            began = System.nanoTime();
            int status = process.waitFor();
            elapsed = System.nanoTime() - began;
            operationNanos += elapsed;
            emit(label, "waitFor", "end", elapsed, pid);
            System.out.printf("command=%s exit_code=%d child_pid=%d%n", label, status, pid);

            emit(label, "output_read", "begin", 0, pid);
            began = System.nanoTime();
            String standardOutput = Files.readString(stdout);
            String standardError = Files.readString(stderr);
            elapsed = System.nanoTime() - began;
            operationNanos += elapsed;
            emit(label, "output_read", "end", elapsed, pid);
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
                    emit(label, "close", "begin", 0, pid);
                    long began = System.nanoTime();
                    process.close();
                    long elapsed = System.nanoTime() - began;
                    operationNanos += elapsed;
                    emit(label, "close", "end", elapsed, pid);
                }
            } finally {
                for (Path file : List.of(stdout, stderr, capture)) {
                    String stage = "delete_" + file.getFileName();
                    if (file.equals(capture)) {
                        stage = "delete_capture_directory";
                    }
                    emit(label, stage, "begin", 0, pid);
                    long began = System.nanoTime();
                    Files.deleteIfExists(file);
                    long elapsed = System.nanoTime() - began;
                    operationNanos += elapsed;
                    emit(label, stage, "end", elapsed, pid);
                }
            }
        }
        emit(label, "command_total_operations", "end", operationNanos, pid);
        return operationNanos;
    }

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
        long elapsed = capture("cmd", List.of(cmd.toString(), "/d", "/c", "exit", "0"), directory);
        if (elapsed >= slowCommandNanos) {
            System.out.println("diagnostic=stopped_after_slow_command command=cmd");
            return;
        }
        elapsed = capture("git", List.of(git.toString(), "--version"), directory);
        if (elapsed >= slowCommandNanos) {
            System.out.println("diagnostic=stopped_after_slow_command command=git");
        }
    }
}
