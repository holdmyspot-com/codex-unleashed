package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import oshi.driver.common.windows.perfmon.PagingFile;
import oshi.ffm.util.platform.windows.PerfCounterQueryFFM;

/**
 * Reads Windows host-memory counters without converting unavailable measurements to zero.
 * Concurrent calls own independent native scopes and notification handles.
 */
public final class WindowsMemory
{
    /**
     * Prevents construction of this stateless reader.
     */
    private WindowsMemory()
    {
    }

    /**
     * Holds sequential host observations rather than an atomic kernel snapshot.
     *
     * @param totalBytes the positive physical-memory capacity in bytes
     * @param availableBytes the available physical bytes, from zero through physical capacity
     * @param swapUsedBytes the nonnegative actual pagefile occupancy in bytes
     * @param lowMemory indicates whether the native low-memory notification is signaled
     */
    public record Sample(long totalBytes, long availableBytes, long swapUsedBytes, boolean lowMemory)
    {
        /**
         * Creates a sample whose byte quantities satisfy their documented ranges.
         *
         * @param totalBytes the positive physical-memory capacity in bytes
         * @param availableBytes the available physical bytes, from zero through physical capacity
         * @param swapUsedBytes the nonnegative actual pagefile occupancy in bytes
         * @param lowMemory indicates whether the native low-memory notification is signaled
         * @throws IllegalArgumentException if a byte quantity violates its documented range
         */
        public Sample
        {
            if (totalBytes <= 0 || availableBytes < 0 || availableBytes > totalBytes || swapUsedBytes < 0)
                throw new IllegalArgumentException("Memory bytes require total > 0, 0 <= available <= total and swap used >= 0: "
                    + totalBytes + "/" + availableBytes + "/" + swapUsedBytes);
        }
    }

    /**
     * Reads physical memory, actual pagefile occupancy and low-memory pressure on 64-bit Windows.
     * A successful zero occupancy and a successful false notification remain valid measurements.
     * The owned notification handle is closed before return, including after acquisition failure;
     * cleanup failure prevents success and is suppressed when another failure already exists.
     *
     * @return a complete host-memory sample
     * @throws UnsupportedOperationException if the platform, process width or required symbol is unsupported
     * @throws IOException if acquisition, counter validation, exact arithmetic or native cleanup fails
     */
    public static Sample read() throws IOException
    {
        if (!System.getProperty("os.name", "").startsWith("Windows"))
            throw new UnsupportedOperationException("WindowsMemory requires Windows");
        if (ValueLayout.ADDRESS.byteSize() != 8)
            throw new UnsupportedOperationException("WindowsMemory requires a 64-bit process");

        try (Arena arena = Arena.ofConfined())
        {
            var library = SymbolLookup.libraryLookup("Kernel32.dll", arena);
            var getInfo = bind(library, "K32GetPerformanceInfo",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            var create = bind(library, "CreateMemoryResourceNotification",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            var query = bind(library, "QueryMemoryResourceNotification",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            var close = bind(library, "CloseHandle",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            var errorLayout = Linker.Option.captureStateLayout();
            var errors = arena.allocate(errorLayout);
            long errorOffset = errorLayout.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"));
            MemorySegment handle = (MemorySegment) invoke("CreateMemoryResourceNotification", create, errors, 0);
            if (handle.address() == 0)
                throw nativeFailure("CreateMemoryResourceNotification", errors, errorOffset);

            try (Notification notification = new Notification(close, errors, errorOffset, handle))
            {
                // The Windows SDK's 64-bit PERFORMANCE_INFORMATION is 104 bytes, aligned to eight bytes.
                var info = arena.allocate(104, 8);
                info.set(ValueLayout.JAVA_INT, 0, 104);
                int infoStatus = (int) invoke("K32GetPerformanceInfo", getInfo, errors, info, 104);
                if (infoStatus == 0)
                    throw nativeFailure("K32GetPerformanceInfo", errors, errorOffset);
                long pageBytes = info.get(ValueLayout.JAVA_LONG, 80);
                long totalPages = info.get(ValueLayout.JAVA_LONG, 32);
                long availablePages = info.get(ValueLayout.JAVA_LONG, 40);
                if (pageBytes <= 0 || totalPages <= 0 || availablePages < 0 || availablePages > totalPages)
                    throw new IOException("Physical counters require page bytes > 0, total pages > 0 and 0 <= available <= total pages: "
                        + pageBytes + "/" + totalPages + "/" + availablePages);
                long totalBytes = Math.multiplyExact(totalPages, pageBytes);
                long availableBytes = Math.multiplyExact(availablePages, pageBytes);

                var occupancy = PerfCounterQueryFFM.queryValuesFromPDH(PagingFile.PagingPercentProperty.class,
                    "Paging File");
                Long usedPages = occupancy.get(PagingFile.PagingPercentProperty.PERCENTUSAGE);
                if (usedPages == null)
                    throw new IOException("Paging File PERCENTUSAGE acquisition returned no value");
                if (usedPages < 0)
                    throw new IOException("Paging File PERCENTUSAGE must return nonnegative used pages: " + usedPages);
                long swapUsedBytes = Math.multiplyExact(usedPages, pageBytes);

                var pressure = arena.allocate(ValueLayout.JAVA_INT);
                int queryStatus = (int) invoke("QueryMemoryResourceNotification", query, errors, handle, pressure);
                if (queryStatus == 0)
                    throw nativeFailure("QueryMemoryResourceNotification", errors, errorOffset);
                boolean lowMemory = pressure.get(ValueLayout.JAVA_INT, 0) != 0;
                return new Sample(totalBytes, availableBytes, swapUsedBytes, lowMemory);
            }
        }
        catch (ArithmeticException e)
        {
            throw new IOException("Native memory counters exceed the signed 64-bit byte range", e);
        }
    }

    /**
     * Binds a required Windows symbol with captured last-error state.
     *
     * @param library the library lookup owned by this call's native scope
     * @param name the required native symbol
     * @param descriptor the function's native return and argument layouts
     * @return the bound native function
     * @throws UnsupportedOperationException if the required symbol is absent
     */
    private static MethodHandle bind(SymbolLookup library, String name, FunctionDescriptor descriptor)
    {
        var symbol = library.find(name).orElseThrow(() ->
            new UnsupportedOperationException("Required Windows native symbol is absent: " + name));
        return Linker.nativeLinker().downcallHandle(symbol, descriptor, Linker.Option.captureCallState("GetLastError"));
    }

    /**
     * Invokes a native operation while preserving runtime exceptions and errors.
     *
     * @param operation the native operation identified in a checked failure
     * @param function the bound function
     * @param arguments the function's native arguments, including captured error storage
     * @return the native result with its declared carrier type
     * @throws IOException if invocation raises a checked failure
     */
    private static Object invoke(String operation, MethodHandle function, Object... arguments) throws IOException
    {
        try
        {
            return function.invokeWithArguments(arguments);
        }
        catch (RuntimeException | Error e)
        {
            throw e;
        }
        catch (Throwable e)
        {
            throw new IOException("Native invocation failed: " + operation, e);
        }
    }

    /**
     * Creates a diagnostic from last-error state captured by a failed native call.
     *
     * @param operation the native operation that reported failure
     * @param errors the storage captured by that call
     * @param errorOffset the last-error field's byte offset in the storage
     * @return the failure with its unsigned Windows error code
     */
    private static IOException nativeFailure(String operation, MemorySegment errors, long errorOffset)
    {
        return new IOException(operation + " failed with Windows error "
            + Integer.toUnsignedLong(errors.get(ValueLayout.JAVA_INT, errorOffset)));
    }

    /**
     * Owns one memory-resource notification handle until its enclosing native scope closes.
     *
     * @param release the native handle-release function
     * @param errors the enclosing native scope's captured-error storage
     * @param errorOffset the last-error field's byte offset in the storage
     * @param handle the owned notification handle
     */
    private record Notification(MethodHandle release, MemorySegment errors, long errorOffset, MemorySegment handle)
        implements AutoCloseable
    {
        /**
         * Closes the owned notification handle.
         *
         * @throws IOException if native handle release fails
         */
        @Override
        public void close() throws IOException
        {
            int status = (int) invoke("CloseHandle", release, errors, handle);
            if (status == 0)
                throw nativeFailure("CloseHandle", errors, errorOffset);
        }
    }
}
