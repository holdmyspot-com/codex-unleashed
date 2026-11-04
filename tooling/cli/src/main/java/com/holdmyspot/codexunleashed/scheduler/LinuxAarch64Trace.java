package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** Implements the native AArch64 Linux register and instruction ABI for the sole allocation tracer. */
public final class LinuxAarch64Trace
{
    /** Native mmap syscall number from the AArch64 Linux syscall ABI. */
    static final long MMAP = 222;
    /** Native execve syscall number from the AArch64 Linux syscall ABI. */
    static final long EXECVE = 221;
    /** Native execveat syscall number from the AArch64 Linux syscall ABI. */
    static final long EXECVEAT = 281;
    /** Native munmap, prctl and seccomp syscall numbers from the AArch64 Linux syscall ABI. */
    static final long MUNMAP = 215, PRCTL = 167, SECCOMP = 277;
    /** Width in bytes of an AArch64 instruction. */
    static final int INSTRUCTION_BYTES = 4;
    /** Linux user_pt_regs size, including x0..x30, SP, PC and PSTATE. */
    private static final long REGISTER_BYTES = 272;
    /** Native general-register regset note identifier. */
    private static final int NT_PRSTATUS = 1;
    /** Native pending-syscall regset note identifier, independent of x8. */
    private static final int NT_ARM_SYSTEM_CALL = 0x404;
    /** Linux ptrace requests for reading and writing an iovec-backed regset. */
    private static final int GETREGSET = 0x4204, SETREGSET = 0x4205;
    /** x8 identifies the next userspace SVC syscall. */
    private static final long X8_OFFSET = 64;
    /** PC follows the general registers and SP in user_pt_regs. */
    private static final long PC_OFFSET = 256;
    /** Little-endian BRK #0 instruction; Linux reports PC at this instruction. */
    private static final long BRK = 0xd4200000L;
    /** Mask covering one complete fixed-width instruction. */
    private static final long INSTRUCTION_MASK = 0xffffffffL;

    /** Prevents construction of this stateless ABI implementation. */
    private LinuxAarch64Trace()
    {
    }

    /**
     * Reads the complete native general-register regset and validates the returned ABI size.
     * @param trace borrowed operation from the existing exclusive tracing owner
     * @param tid stopped owned native task
     * @param arena register storage lifetime
     * @return complete native general registers
     * @throws IOException if ptrace fails or reports a different regset size
     */
    public static MemorySegment readRegisters(LinuxAllocationTrace.NativeTrace trace, int tid, Arena arena)
        throws IOException
    {
        MemorySegment registers = arena.allocate(REGISTER_BYTES, 8);
        regset(trace, GETREGSET, tid, NT_PRSTATUS, registers, arena);
        return registers;
    }

    /**
     * Writes prepared general registers and disables restart of the interrupted parked syscall.
     * Linux keeps the pending syscall separately from x8; setting only x8 would retain that old operation.
     * @param trace borrowed operation from the existing exclusive tracing owner
     * @param tid stopped owned native task
     * @param registers complete prepared native registers
     * @throws IOException if either native regset cannot be written
     */
    public static void writeRegisters(LinuxAllocationTrace.NativeTrace trace, int tid, MemorySegment registers)
        throws IOException
    {
        try (Arena arena = Arena.ofConfined())
        {
            regset(trace, SETREGSET, tid, NT_PRSTATUS, registers, arena);
            MemorySegment syscall = arena.allocateFrom(ValueLayout.JAVA_INT, -1);
            regset(trace, SETREGSET, tid, NT_ARM_SYSTEM_CALL, syscall, arena);
        }
    }

    /**
     * Transfers one regset through its LP64 iovec and rejects partial native layouts.
     * @param trace exclusive owner's native operation
     * @param operation GETREGSET or SETREGSET request
     * @param tid stopped native task
     * @param note native regset identifier
     * @param data complete native regset storage
     * @param arena iovec lifetime
     * @throws IOException if the transfer fails or its returned length differs from the required size
     */
    private static void regset(LinuxAllocationTrace.NativeTrace trace, int operation, int tid, int note,
        MemorySegment data, Arena arena) throws IOException
    {
        MemorySegment vector = arena.allocate(16, 8);
        vector.set(ValueLayout.ADDRESS, 0, data);
        vector.set(ValueLayout.JAVA_LONG, 8, data.byteSize());
        trace.invoke(operation, tid, note, vector);
        long actual = vector.get(ValueLayout.JAVA_LONG, 8);
        if (actual != data.byteSize())
            throw new IOException("AArch64 regset " + note + " returned " + actual +
                " bytes; required " + data.byteSize());
    }

    /**
     * Prepares a fresh userspace SVC with at most six native syscall arguments.
     * @param registers complete native register storage
     * @param pc verified original SVC instruction address
     * @param number next native syscall number
     * @param arguments native syscall arguments in x0..x5 order
     * @throws IllegalArgumentException if more than six arguments are supplied or PC is unaligned
     */
    public static void prepareSyscall(MemorySegment registers, long pc, long number, long... arguments)
    {
        if (arguments.length > 6 || (pc & 3) != 0)
            throw new IllegalArgumentException("AArch64 syscall requires aligned PC and at most six arguments");
        registers.set(ValueLayout.JAVA_LONG, X8_OFFSET, number);
        registers.set(ValueLayout.JAVA_LONG, PC_OFFSET, pc);
        for (int index = 0; index < arguments.length; ++index)
            registers.set(ValueLayout.JAVA_LONG, index * 8L, arguments[index]);
    }

    /**
     * Reads the signed native result from x0.
     * @param registers complete native register storage
     * @return unmodified signed syscall result
     */
    public static long result(MemorySegment registers)
    {
        return registers.get(ValueLayout.JAVA_LONG, 0);
    }

    /**
     * Reads the native instruction pointer.
     * @param registers complete native register storage
     * @return native PC
     */
    public static long pc(MemorySegment registers)
    {
        return registers.get(ValueLayout.JAVA_LONG, PC_OFFSET);
    }

    /**
     * Extracts the raw original instruction bits from their aligned eight-byte ptrace word.
     * @param word native ptrace text word
     * @param address four-byte-aligned instruction address
     * @return original instruction, retaining all 32 bits
     * @throws IllegalArgumentException if the address is not instruction-aligned
     */
    public static int originalInstruction(long word, long address)
    {
        return (int) ((word >>> shift(address)) & INSTRUCTION_MASK);
    }

    /**
     * Replaces exactly one instruction with BRK while preserving its neighboring instruction.
     * @param word original aligned eight-byte text word
     * @param address four-byte-aligned instruction address
     * @return patched native text word for PTRACE_POKETEXT
     * @throws IllegalArgumentException if the address is not instruction-aligned
     */
    public static long patchTrap(long word, long address)
    {
        int shift = shift(address);
        long mask = INSTRUCTION_MASK << shift;
        return (word & ~mask) | (BRK << shift);
    }

    /**
     * Resolves an instruction's bit position inside an aligned native ptrace word.
     * @param address native instruction address
     * @return zero for the lower instruction or 32 for the upper instruction
     * @throws IllegalArgumentException if the address is not instruction-aligned
     */
    private static int shift(long address)
    {
        if ((address & 3) != 0)
            throw new IllegalArgumentException("AArch64 instruction address must be four-byte aligned: " + address);
        return Math.toIntExact((address & 7) * 8);
    }
}
