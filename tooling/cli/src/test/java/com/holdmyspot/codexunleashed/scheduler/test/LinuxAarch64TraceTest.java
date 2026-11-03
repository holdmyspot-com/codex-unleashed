package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxAarch64Trace;
import com.holdmyspot.codexunleashed.scheduler.LinuxAllocationTrace;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;

/** Verifies architecture-specific denial receipts without requiring the host architecture. */
public final class LinuxAarch64TraceTest
{
    /** Creates an independent, resource-free test instance. */
    public LinuxAarch64TraceTest()
    {
    }

    /** Accepts a genuine AArch64 mmap error followed by an executed BRK at the return address. */
    @Test
    public void acceptsAarch64Denial()
    {
        LinuxAllocationTrace.Denial denial = new LinuxAllocationTrace.Denial(
            new LinuxAllocationTrace.Identity(1, 0), 1, 222, 33562624, -12,
            4096, 4096, "010000d4", 0xd65f03c0, "1000-2000 r-xp 00000000 00:00 0");
        requireThat(denial.trapPc(), "trapPc").isEqualTo(4096L);
        requireThat(denial.originalInstructionBits(), "originalInstructionBits").isEqualTo(0xd65f03c0);
    }

    /** Retains the existing x86-64 receipt and post-INT3 instruction pointer. */
    @Test
    public void preservesX8664Denial()
    {
        LinuxAllocationTrace.Denial denial = new LinuxAllocationTrace.Denial(
            new LinuxAllocationTrace.Identity(1, 0), 1, 9, 33562624, -12,
            4096, 4097, "0f05", 0xc3, "1000-2000 r-xp 00000000 00:00 0");
        requireThat(denial.trapPc(), "trapPc").isEqualTo(4097L);
    }

    /** Rejects x86-64 post-trap PC semantics applied to an AArch64 denial receipt. */
    @Test
    public void rejectsWrongTrapPc()
    {
        requireException(IllegalArgumentException.class, () -> new LinuxAllocationTrace.Denial(
            new LinuxAllocationTrace.Identity(1, 0), 1, 222, 33562624, -12,
            4096, 4100, "010000d4", 0xd65f03c0, "1000-2000 r-xp 00000000 00:00 0"));
    }

    /** Preserves the adjacent instruction for either aligned half of a native ptrace word. */
    @Test
    public void patchesOneInstruction()
    {
        long original = 0xd65f03c0d503201fL;
        requireThat(LinuxAarch64Trace.patchTrap(original, 4096), "lowerPatch")
            .isEqualTo(0xd65f03c0d4200000L);
        requireThat(LinuxAarch64Trace.patchTrap(original, 4100), "upperPatch")
            .isEqualTo(0xd4200000d503201fL);
        requireThat(LinuxAarch64Trace.originalInstruction(original, 4096), "lowerInstruction")
            .isEqualTo(0xd503201f);
        requireThat(LinuxAarch64Trace.originalInstruction(original, 4100), "upperInstruction")
            .isEqualTo(0xd65f03c0);
        requireException(IllegalArgumentException.class, () -> LinuxAarch64Trace.patchTrap(original, 4098));
    }

    /** Writes only syscall argument, syscall-number and PC registers while preserving the stack pointer. */
    @Test
    public void preparesFreshSvc()
    {
        try (Arena arena = Arena.ofConfined())
        {
            MemorySegment registers = arena.allocate(272, 8);
            registers.set(ValueLayout.JAVA_LONG, 248, 0x12345000L);
            LinuxAarch64Trace.prepareSyscall(registers, 4096, 222, 0, 33562624, 3, 0x22, -1, 0);
            requireThat(LinuxAarch64Trace.pc(registers), "PC").isEqualTo(4096L);
            requireThat(registers.get(ValueLayout.JAVA_LONG, 64), "x8").isEqualTo(222L);
            requireThat(registers.get(ValueLayout.JAVA_LONG, 8), "x1").isEqualTo(33562624L);
            requireThat(registers.get(ValueLayout.JAVA_LONG, 32), "x4").isEqualTo(-1L);
            requireThat(registers.get(ValueLayout.JAVA_LONG, 248), "SP").isEqualTo(0x12345000L);
            registers.set(ValueLayout.JAVA_LONG, 0, -12);
            requireThat(LinuxAarch64Trace.result(registers), "nativeResult").isEqualTo(-12L);
            requireException(IllegalArgumentException.class,
                () -> LinuxAarch64Trace.prepareSyscall(registers, 4098, 222));
        }
    }

    /**
     * Sends complete PRSTATUS followed by the separate pending-syscall restart cancellation.
     * The recording boundary follows Linux's documented LP64 iovec and AArch64 regset ABI;
     * this verifies the requests produced, while native acceptance must prove their kernel effect.
     * @throws IOException if the production ABI adapter rejects these complete request buffers
     */
    @Test
    public void writesBothNativeRegsets() throws IOException
    {
        try (Arena arena = Arena.ofConfined())
        {
            MemorySegment registers = arena.allocate(272, 8);
            List<Long> notes = new ArrayList<>();
            LinuxAarch64Trace.writeRegisters((request, tid, note, vector) ->
            {
                requireThat(request, "request").isEqualTo(0x4205);
                requireThat(tid, "tid").isEqualTo(7);
                notes.add(note);
                long bytes = vector.get(ValueLayout.JAVA_LONG, 8);
                if (note == 1)
                {
                    requireThat(bytes, "prstatusBytes").isEqualTo(272L);
                    requireThat(vector.get(ValueLayout.ADDRESS, 0).address(), "prstatusAddress")
                        .isEqualTo(registers.address());
                }
                else
                {
                    requireThat(bytes, "pendingSyscallBytes").isEqualTo(4L);
                    requireThat(pendingSyscall(vector), "pendingSyscall").isEqualTo(-1);
                }
                return 0;
            }, 7, registers);
            requireThat(notes, "regsetOrder").isEqualTo(List.of(1L, 0x404L));
        }
    }

    /** Rejects a truncated native regset instead of interpreting missing register bytes as zero. */
    @Test
    public void rejectsTruncatedRegset()
    {
        try (Arena arena = Arena.ofConfined())
        {
            requireException(IOException.class, () -> LinuxAarch64Trace.readRegisters(
                (request, tid, note, vector) ->
                {
                    vector.set(ValueLayout.JAVA_LONG, 8, 264);
                    return 0;
                }, 7, arena));
        }
    }

    /**
     * Reads the adapter-owned four-byte pending-syscall buffer synchronously before its arena closes.
     * @param vector native iovec whose length the caller has required to equal four
     * @return pending syscall value sent to the kernel
     */
    // The production adapter owns this exact four-byte buffer throughout the synchronous callback.
    @SuppressWarnings("restricted")
    private static int pendingSyscall(MemorySegment vector)
    {
        return vector.get(ValueLayout.ADDRESS, 0).reinterpret(4).get(ValueLayout.JAVA_INT, 0);
    }
}
