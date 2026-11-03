package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxAllocationTrace;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.SkipException;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises the public sole-tracer operation against unchanged static ARM64 Zig and authentic process outcomes.
 * These tests prove the native tracing boundary; connected scheduler adoption and retry remain separate acceptance.
 */
public final class LinuxAarch64TraceNativeTest
{
    /** Creates a resource-free test instance; each method owns its scope, directory and native attempt. */
    public LinuxAarch64TraceNativeTest()
    {
    }

    /**
     * Runs the unchanged static Zig version operation through the real tracing owner.
     * @throws Exception if native setup, tracing or cleanup fails
     */
    @Test
    public void staticZigVersion() throws Exception
    {
        executeCase("version");
    }

    /**
     * Delivers genuine mmap ENOMEM after executed BRK while retaining the unmodified native return.
     * @throws Exception if native setup, tracing, held-state observation or cleanup fails
     */
    @Test
    public void genuineDeniedMmapBrk() throws Exception
    {
        executeCase("denial");
    }

    /**
     * Produces a useful ARM64 ELF object through unchanged static Zig and the real tracing owner.
     * @throws Exception if native compilation, output verification or cleanup fails
     */
    @Test
    public void usefulStaticCompile() throws Exception
    {
        executeCase("compile");
    }

    /**
     * Preserves a real nonzero exit as an ordinary result without inventing a denial.
     * @throws Exception if native execution or cleanup fails
     */
    @Test
    public void ordinaryExit23() throws Exception
    {
        executeCase("exit23");
    }

    /**
     * Collects an authentic fork/exec descendant after its original root exits.
     * @throws Exception if descendant collection or cleanup fails
     */
    @Test
    public void descendantOutlivesRoot() throws Exception
    {
        executeCase("fork");
    }

    /**
     * Reuses the accepted standalone capability cases with AArch64-specific receipt checks.
     * @param mode native acceptance case
     * @throws Exception if setup, actual native behavior, an assertion or cleanup fails
     */
    private static void executeCase(String mode) throws Exception
    {
        boolean arm = switch (System.getProperty("os.arch"))
        {
            case "aarch64", "arm64" -> true;
            default -> false;
        };
        if (!System.getProperty("os.name").equals("Linux") || !arm)
            throw new SkipException("Requires genuine Linux AArch64");
        String configuredZig = System.getProperty("codex.arm64.zig");
        String configuredRoot = System.getProperty("codex.arm64.fixtureRoot");
        if (configuredZig == null || configuredRoot == null)
            throw new SkipException("Set codex.arm64.zig and codex.arm64.fixtureRoot to the native fixture");
        Path zig = Path.of(configuredZig).toRealPath();
        Path root = Path.of(configuredRoot).toRealPath();
        Path work = Files.createTempDirectory(root, "trace-" + mode + "-");
        try (var scope = new DefaultToolingScope())
        {
            String before = digest(zig);
            Files.writeString(work.resolve("tiny.c"), "int value(void) { return 42; }\n");
            Map<String, String> environment = Map.of("PATH", "/usr/bin:/bin", "HOME", work.toString(),
                "TMPDIR", work.toString(), "ZIG_LOCAL_CACHE_DIR", work.resolve("zig-local").toString(),
                "ZIG_GLOBAL_CACHE_DIR", work.resolve("zig-global").toString());
            List<String> command = switch (mode)
            {
                case "version" -> List.of(zig.toString(), "version");
                case "denial", "compile" -> List.of(zig.toString(), "cc", "-c", "tiny.c", "-o", "tiny.o");
                case "exit23" -> List.of("/bin/sh", "-c", "exit 23");
                case "fork" -> List.of("/bin/sh", "-c", "sleep 2 & exit 23");
                default -> throw new IllegalArgumentException("Unknown native case: " + mode);
            };
            AtomicInteger denials = new AtomicInteger();
            long cap = mode.equals("denial") ? 209715200 : 0;
            LinuxAllocationTrace.Outcome outcome = LinuxAllocationTrace.run(scope.getNativeLinker(), command,
                environment, work, cap, denial ->
                {
                    denials.incrementAndGet();
                    requireThat(denial.syscall(), "syscall").isEqualTo(222L);
                    requireThat(denial.nativeResult(), "nativeResult").isEqualTo(-12L);
                    requireThat(denial.syscallBytes(), "syscallBytes").isEqualTo("010000d4");
                    requireThat(denial.trapPc(), "trapPc").isEqualTo(denial.returnAddress());
                    String stat = Files.readString(Path.of("/proc", Integer.toString(denial.thread().tid()), "stat"));
                    requireThat(stat.substring(stat.lastIndexOf(')') + 2).startsWith("t "), "heldNativeTask")
                        .isTrue();
                    System.out.println("ARM64_NATIVE_DENIAL=" + denial);
                });
            if (mode.equals("denial"))
            {
                requireThat(denials.get(), "denials").isEqualTo(1);
                requireThat(outcome.denied(), "denied").isTrue();
                requireThat(outcome.root().waitStatus() & 127, "rootSignal").isEqualTo(9);
                requireThat(Files.exists(work.resolve("tiny.o")), "deniedOutput").isFalse();
            }
            else
            {
                int expected = switch (mode)
                {
                    case "version", "compile" -> 0;
                    default -> 23;
                };
                requireThat(denials.get(), "denials").isEqualTo(0);
                requireThat(outcome.denied(), "denied").isFalse();
                requireThat(outcome.root().waitStatus(), "rootWaitStatus").isEqualTo(expected << 8);
            }
            if (mode.equals("fork"))
            {
                requireThat(outcome.collected().size() >= 2, "rootAndDescendantCollected").isTrue();
                requireThat(outcome.collected().getFirst(), "firstCollected").isEqualTo(outcome.root());
            }
            if (mode.equals("compile"))
            {
                byte[] elf = Files.readAllBytes(work.resolve("tiny.o"));
                requireThat(elf.length > 64, "usefulElfLength").isTrue();
                requireThat(HexFormat.of().formatHex(elf, 0, 4), "elfMagic").isEqualTo("7f454c46");
                requireThat(Byte.toUnsignedInt(elf[18]), "elfMachineLowByte").isEqualTo(183);
                requireThat(Byte.toUnsignedInt(elf[19]), "elfMachineHighByte").isEqualTo(0);
            }
            for (LinuxAllocationTrace.Terminal terminal : outcome.collected())
                requireThat(Files.exists(Path.of("/proc", Integer.toString(terminal.identity().tid()))),
                    "collectedTaskRemains").isFalse();
            requireThat(digest(zig), "unchangedZig").isEqualTo(before);
            System.out.println("ARM64_GATE=" + mode + ":PASSED " + outcome);
        }
        finally
        {
            try (var paths = Files.walk(work))
            {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }

    /**
     * Hashes the unchanged native executable for the before/after assertion.
     * @param file native executable
     * @return lowercase SHA-256
     * @throws Exception if the file cannot be read or SHA-256 is unavailable
     */
    private static String digest(Path file) throws Exception
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
