package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Diverts a suspended process's image entry point to a spin loop in a page of its own, so that its primary thread
 * finishes loader initialization, reports its arrival and waits there until released.
 * <p>
 * The page holds a one-byte flag at offset 0 and the loop at offset 64. The loop sets the flag to 1, waits while it
 * stays 1, then jumps to the entry point. The entry point's original code is restored before release, so the
 * process continues exactly as if it had never been diverted. The page stays allocated because the released thread
 * may still be leaving the loop.
 * <p>
 * Instances are not thread-safe; only the owning process controller's thread may use one.
 */
final class WindowsEntryPark implements AutoCloseable
{
    /**
     * Process access rights the park needs: query information, virtual memory operation, read, write and
     * synchronize.
     */
    static final int ACCESS = 0x0400 | 0x0008 | 0x0010 | 0x0020 | 0x0010_0000;
    /** {@code IMAGE_FILE_MACHINE_I386}. */
    private static final int MACHINE_X86 = 0x014C;
    /** {@code IMAGE_FILE_MACHINE_AMD64}. */
    private static final int MACHINE_X64 = 0x8664;
    /** {@code IMAGE_FILE_MACHINE_ARM64}. */
    private static final int MACHINE_ARM64 = 0xAA64;
    /** {@code MEM_COMMIT | MEM_RESERVE}. */
    private static final int COMMIT_RESERVE = 0x3000;
    /** {@code PAGE_EXECUTE_READWRITE}. */
    private static final int EXECUTE_READ_WRITE = 0x40;
    /** Size of the allocated flag and loop page, in bytes. */
    private static final int PAGE_BYTES = 4096;
    /** Offset of the loop within the page, in bytes. */
    private static final int STUB_OFFSET = 64;
    /** Flag value the loop writes on arrival. */
    private static final byte ARRIVED = 1;
    /** Flag value that lets the loop continue. */
    private static final byte RELEASED = 2;
    /** The longest wait, in milliseconds, for an unreadable process to become signaled as exited. */
    private static final int EXIT_SETTLE_MILLIS = 1000;

    /** Borrowed native bindings of the owning process controller. */
    private final WindowsProcess.Kernel kernel;
    /** Owned process handle with {@link #ACCESS}, closed by {@link #close()}. */
    private final MemorySegment handle;
    /** Address of the flag in the target process. */
    private final long flag;
    /** Address of the image entry point in the target process. */
    private final long entry;
    /** The entry point's original code, overwritten by the jump to the loop. */
    private final byte[] original;
    /** Indicates whether the entry point's original code has been restored. */
    private boolean restored;
    /** Indicates whether the handle has been closed. */
    private boolean closed;

    /**
     * Retains a diverted entry point.
     * @param kernel borrowed native bindings
     * @param handle owned process handle with {@link #ACCESS}
     * @param flag address of the flag in the target process
     * @param entry address of the image entry point in the target process
     * @param original the entry point's original code
     */
    private WindowsEntryPark(WindowsProcess.Kernel kernel, MemorySegment handle, long flag, long entry, byte[] original)
    {
        this.kernel = kernel;
        this.handle = handle;
        this.flag = flag;
        this.entry = entry;
        this.original = original;
    }

    /**
     * Diverts the image entry point of a process whose primary thread has not yet run.
     * @param kernel borrowed native bindings of the owning controller
     * @param handle process handle with {@link #ACCESS}; ownership transfers to the result only on success
     * @return the diverted entry point
     * @throws NullPointerException if an argument is null
     * @throws IOException if the image is not x86, x64 or ARM64, or its memory cannot be read, allocated or written
     */
    static WindowsEntryPark divert(WindowsProcess.Kernel kernel, MemorySegment handle) throws IOException
    {
        Objects.requireNonNull(kernel, "kernel");
        Objects.requireNonNull(handle, "handle");
        long base = readLong(kernel, handle, processEnvironmentBlock(kernel, handle) + 0x10);
        long header = base + Integer.toUnsignedLong(readInt(kernel, handle, base + 0x3C));
        int machine = Short.toUnsignedInt((short) readInt(kernel, handle, header + 4));
        long entryOffset = Integer.toUnsignedLong(readInt(kernel, handle, header + 24 + 16));
        if (entryOffset == 0)
            throw new IOException("Image has no entry point");
        long entry = base + entryOffset;

        MemorySegment page = kernel.pointer("VirtualAllocEx", handle, MemorySegment.NULL, (long) PAGE_BYTES,
            COMMIT_RESERVE, EXECUTE_READ_WRITE);
        if (page.address() == 0)
            throw kernel.failure("VirtualAllocEx(entry park)");
        long flag = page.address();
        long stub = flag + STUB_OFFSET;
        ByteBuffer code = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer jump = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        switch (machine)
        {
            case MACHINE_X64 ->
            {
                // mov rax, flag; mov byte [rax], 1; loop: pause; cmp byte [rax], 1; je loop; mov rax, entry; jmp rax
                code.put(new byte[]{0x48, (byte) 0xB8}).putLong(flag).put(new byte[]{(byte) 0xC6, 0x00, ARRIVED});
                code.put(new byte[]{(byte) 0xF3, (byte) 0x90, (byte) 0x80, 0x38, ARRIVED, 0x74, (byte) 0xF9});
                code.put(new byte[]{0x48, (byte) 0xB8}).putLong(entry).put(new byte[]{(byte) 0xFF, (byte) 0xE0});
                // jmp [rip + 0]; stub
                jump.put(new byte[]{(byte) 0xFF, 0x25, 0, 0, 0, 0}).putLong(stub);
            }
            case MACHINE_ARM64 ->
            {
                // ldr x16, flag; mov w17, #1; strb w17, [x16]; loop: ldrb w17, [x16]; cmp w17, #1; b.eq loop;
                // ldr x16, entry; br x16; flag; entry
                for (int word : new int[]{0x58000110, 0x52800031, 0x39000211, 0x39400211, 0x7100063F, 0x54FFFFC0,
                    0x58000090, 0xD61F0200})
                {
                    code.putInt(word);
                }
                code.putLong(flag).putLong(entry);
                // ldr x16, stub; br x16; stub
                jump.putInt(0x58000050).putInt(0xD61F0200).putLong(stub);
            }
            case MACHINE_X86 ->
            {
                if (stub + 64 > 0xFFFF_FFFFL || entry > 0xFFFF_FFFFL)
                    throw new IOException("32-bit image's entry park lies above 4 GiB");
                // mov byte [flag], 1; loop: pause; cmp byte [flag], 1; je loop; mov eax, entry; jmp eax
                code.put(new byte[]{(byte) 0xC6, 0x05}).putInt((int) flag).put(ARRIVED);
                code.put(new byte[]{(byte) 0xF3, (byte) 0x90, (byte) 0x80, 0x3D}).putInt((int) flag).put(ARRIVED);
                code.put(new byte[]{0x74, (byte) 0xF5, (byte) 0xB8}).putInt((int) entry).put(new byte[]{(byte) 0xFF,
                    (byte) 0xE0});
                // push stub; ret
                jump.put((byte) 0x68).putInt((int) stub).put((byte) 0xC3);
            }
            default -> throw new IOException("Unsupported image machine: 0x" + Integer.toHexString(machine));
        }
        write(kernel, handle, stub, bytes(code));
        byte[] patch = bytes(jump);
        byte[] original = read(kernel, handle, entry, patch.length);
        replaceCode(kernel, handle, entry, patch);
        return new WindowsEntryPark(kernel, handle, flag, entry, original);
    }

    /**
     * Returns how far the primary thread has progressed, restoring the entry point's original code on the first
     * observation of its arrival.
     * @return {@link WindowsAllocationHooks.EntryPark.Arrival#ARRIVED} once the primary thread has arrived
     * @throws IllegalStateException if closed
     * @throws IOException if the flag cannot be read or the entry point cannot be restored
     */
    WindowsAllocationHooks.EntryPark.Arrival arrival() throws IOException
    {
        requireOpen();
        if (restored)
            return WindowsAllocationHooks.EntryPark.Arrival.ARRIVED;
        try
        {
            if (read(kernel, handle, flag, 1)[0] != ARRIVED)
            {
                if (exited(0))
                    return WindowsAllocationHooks.EntryPark.Arrival.EXITED;
                return WindowsAllocationHooks.EntryPark.Arrival.WAITING;
            }
            replaceCode(kernel, handle, entry, original);
        }
        catch (IOException failure)
        {
            // An exiting process's address space is gone, so reading or restoring it fails with ERROR_PARTIAL_COPY;
            // the kernel frees it before the process object is signaled, so wait for that briefly
            boolean gone;
            try
            {
                gone = exited(EXIT_SETTLE_MILLIS);
            }
            catch (IOException wait)
            {
                failure.addSuppressed(wait);
                throw failure;
            }
            if (gone)
                return WindowsAllocationHooks.EntryPark.Arrival.EXITED;
            throw failure;
        }
        restored = true;
        return WindowsAllocationHooks.EntryPark.Arrival.ARRIVED;
    }

    /**
     * Returns whether the process has exited.
     * @param timeoutMillis the longest time to wait for the process to exit, in milliseconds
     * @return {@code true} if the process handle is signaled
     * @throws IOException if the wait fails
     */
    private boolean exited(int timeoutMillis) throws IOException
    {
        int status = kernel.integer("WaitForSingleObject", handle, timeoutMillis);
        // WAIT_OBJECT_0, WAIT_TIMEOUT
        if (status == 0)
            return true;
        if (status == 258)
            return false;
        throw new IOException("WaitForSingleObject on the parked process returned " + Integer.toUnsignedLong(status));
    }

    /**
     * Lets the arrived primary thread continue into the restored entry point.
     * @throws IllegalStateException if closed or the primary thread has not arrived
     * @throws IOException if the flag cannot be written
     */
    void release() throws IOException
    {
        requireOpen();
        if (!restored)
            throw new IllegalStateException("The primary thread has not arrived at the entry point");
        write(kernel, handle, flag, new byte[]{RELEASED});
    }

    /**
     * Closes the process handle without releasing a waiting primary thread.
     * @throws IOException if the handle cannot be closed
     */
    @Override
    public void close() throws IOException
    {
        if (closed)
            return;
        closed = true;
        kernel.closeHandle(handle);
    }

    /**
     * Rejects use after close.
     * @throws IllegalStateException if closed
     */
    private void requireOpen()
    {
        if (closed)
            throw new IllegalStateException("Entry park is closed");
    }

    /**
     * Returns the address of a process's native process environment block.
     * @param kernel native bindings
     * @param handle process handle with query information access
     * @return the block's address
     * @throws IOException if the query fails
     */
    private static long processEnvironmentBlock(WindowsProcess.Kernel kernel, MemorySegment handle) throws IOException
    {
        try (Arena query = Arena.ofConfined())
        {
            // PROCESS_BASIC_INFORMATION: ExitStatus, PebBaseAddress, AffinityMask, BasePriority, UniqueProcessId,
            // InheritedFromUniqueProcessId
            MemorySegment information = query.allocate(48, 8);
            int status = kernel.integer("NtQueryInformationProcess", handle, 0, information, 48, MemorySegment.NULL);
            if (status != 0)
                throw new IOException("NtQueryInformationProcess failed with NTSTATUS 0x" + Integer.toHexString(status));
            return information.get(ValueLayout.JAVA_LONG, 8);
        }
    }

    /**
     * Reads a little-endian 32-bit value from another process.
     * @param kernel native bindings
     * @param handle process handle with read access
     * @param address source address
     * @return the value's bits
     * @throws IOException if the read fails
     */
    private static int readInt(WindowsProcess.Kernel kernel, MemorySegment handle, long address) throws IOException
    {
        return ByteBuffer.wrap(read(kernel, handle, address, 4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /**
     * Reads a little-endian 64-bit value from another process.
     * @param kernel native bindings
     * @param handle process handle with read access
     * @param address source address
     * @return the value's bits
     * @throws IOException if the read fails
     */
    private static long readLong(WindowsProcess.Kernel kernel, MemorySegment handle, long address) throws IOException
    {
        return ByteBuffer.wrap(read(kernel, handle, address, 8)).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    /**
     * Copies memory out of another process.
     * @param kernel native bindings
     * @param handle process handle with read access
     * @param address source address
     * @param length number of bytes
     * @return the copied bytes
     * @throws IOException if the read fails
     */
    private static byte[] read(WindowsProcess.Kernel kernel, MemorySegment handle, long address, int length)
        throws IOException
    {
        try (Arena copy = Arena.ofConfined())
        {
            MemorySegment buffer = copy.allocate(length);
            kernel.require("ReadProcessMemory", kernel.integer("ReadProcessMemory", handle,
                MemorySegment.ofAddress(address), buffer, (long) length, MemorySegment.NULL));
            return buffer.toArray(ValueLayout.JAVA_BYTE);
        }
    }

    /**
     * Copies memory into another process.
     * @param kernel native bindings
     * @param handle process handle with write and virtual memory operation access
     * @param address destination address
     * @param data bytes to copy
     * @throws IOException if the write fails
     */
    private static void write(WindowsProcess.Kernel kernel, MemorySegment handle, long address, byte[] data)
        throws IOException
    {
        try (Arena copy = Arena.ofConfined())
        {
            MemorySegment buffer = copy.allocateFrom(ValueLayout.JAVA_BYTE, data);
            kernel.require("WriteProcessMemory", kernel.integer("WriteProcessMemory", handle,
                MemorySegment.ofAddress(address), buffer, (long) data.length, MemorySegment.NULL));
        }
    }

    /**
     * Overwrites code in another process, temporarily making it writable, and flushes its instruction cache.
     * @param kernel native bindings
     * @param handle process handle with write and virtual memory operation access
     * @param address code address
     * @param code replacement instructions
     * @throws IOException if protection, the write or the flush fails
     */
    private static void replaceCode(WindowsProcess.Kernel kernel, MemorySegment handle, long address, byte[] code)
        throws IOException
    {
        try (Arena protection = Arena.ofConfined())
        {
            MemorySegment target = MemorySegment.ofAddress(address);
            MemorySegment previous = protection.allocate(ValueLayout.JAVA_INT);
            kernel.require("VirtualProtectEx", kernel.integer("VirtualProtectEx", handle, target, (long) code.length,
                EXECUTE_READ_WRITE, previous));
            write(kernel, handle, address, code);
            kernel.require("VirtualProtectEx", kernel.integer("VirtualProtectEx", handle, target, (long) code.length,
                previous.get(ValueLayout.JAVA_INT, 0), previous));
            kernel.require("FlushInstructionCache", kernel.integer("FlushInstructionCache", handle, target,
                (long) code.length));
        }
    }

    /**
     * Copies a buffer's written bytes.
     * @param buffer buffer positioned after its last written byte
     * @return the written bytes
     */
    private static byte[] bytes(ByteBuffer buffer)
    {
        byte[] result = new byte[buffer.position()];
        buffer.flip().get(result);
        return result;
    }
}
