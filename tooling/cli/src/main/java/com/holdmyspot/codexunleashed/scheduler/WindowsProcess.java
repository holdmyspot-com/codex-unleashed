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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * Owns a direct Windows workload and its descendants through one kill-on-close Job Object.
 * The single controlling thread must close this owner. Root exit does not complete the tree.
 * Only duplicated standard handles are inherited; all supplied handles remain borrowed.
 */
public final class WindowsProcess implements AutoCloseable
{
    /** The native termination code requested for aborted unfinished processes. */
    private static final int ABORT_EXIT = 125;
    /** The maximum tree-drain duration during automatic close. */
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);
    /** Windows 64-bit PROCESS_MEMORY_COUNTERS structure size from psapi.h. */
    private static final int MEMORY_COUNTER_BYTES = 72;
    /** Bounds list retries under a continuously growing job; an exhausted sample fails without a partial result. */
    private static final int MEMBERSHIP_QUERY_ATTEMPTS = 8;
    /** Caps any membership buffer at eight MiB of process IDs; larger jobs fail rather than allocating without a bound. */
    private static final int MAX_MEMBERSHIP_PROCESSES = 1_048_576;
    /** Single thread authorized to acquire sequential residency samples from this confined owner. */
    private final Thread controllingThread = Thread.currentThread();
    /** Unresolved process-query handles after failed release; retained for ownership accounting, never silently retried. */
    private final List<MemorySegment> unresolvedResidencyHandles = new ArrayList<>();
    /** First query-release failure, with later failed releases suppressed, or null before any failure. */
    private IOException residencyCleanupFailure = null;
    private final Kernel kernel;
    private final Releases releases;
    private final MemorySegment job;
    /** Reused native job accounting storage; calls are confined to the controlling thread. */
    private final MemorySegment accounting;
    /** Reused native root exit-code storage. */
    private final MemorySegment exitCode;
    private MemorySegment process;
    private long pid;
    private boolean aborted;
    private boolean closed;

    /**
     * Reports the root's original unsigned DWORD exit status and explicit attempt abortion.
     *
     * @param rootExit the original root exit status, from zero through 4294967295
     * @param aborted indicates whether this owner requested tree abortion
     */
    public record Result(long rootExit, boolean aborted)
    {
        /**
         * Creates a completion result with an unsigned Windows status.
         *
         * @param rootExit the original root exit status, from zero through 4294967295
         * @param aborted indicates whether this owner requested tree abortion
         * @throws IllegalArgumentException if the status is outside its unsigned DWORD range
         */
        public Result
        {
            if (rootExit < 0 || rootExit > 0xffff_ffffL)
                throw new IllegalArgumentException("Root exit must be an unsigned DWORD: " + rootExit);
        }
    }

    /**
     * Starts the direct command atomically in an owned Job Object on native 64-bit Windows.
     * The environment is explicit and copied. Arguments follow Windows CRT quoting semantics;
     * names beginning with '=' retain the drive-current-directory environment convention.
     * Standard input is NUL, and both output streams use a duplicated borrowed output handle.
     * Launch failure releases owned resources without closing the borrowed output.
     *
     * @param command nonempty argv whose first element is an absolute executable file
     * @param directory an existing absolute working directory
     * @param environment child names and values without NUL; names must be nonempty and may
     * contain '=' only as their first character
     * @param output a live native output handle which the caller continues to own
     * @return the owner, whose close terminates and drains any remaining descendants
     * @throws NullPointerException if an input or command/environment member is null
     * @throws IllegalArgumentException if an input violates its documented form
     * @throws UnsupportedOperationException if the platform, width or required symbols are unsupported
     * @throws IOException if native acquisition, launch or cleanup fails
     */
    public static WindowsProcess start(List<String> command, Path directory,
        Map<String,String> environment, MemorySegment output) throws IOException
    {
        var arguments = validate(command, directory, environment, output);
        return launch(arguments, directory, Map.copyOf(environment), output, new Kernel());
    }

    /**
     * Starts a direct command with separately borrowed standard handles.
     * Launch arguments, platform requirements and ownership follow
     * {@link #start(List, Path, Map, MemorySegment)}. Each standard handle is duplicated
     * separately for inheritance; the caller retains ownership of all three originals.
     *
     * @param command nonempty argv whose first element is an absolute executable file
     * @param directory an existing absolute working directory
     * @param environment child names and values without NUL; names must be nonempty and may
     * contain '=' only as their first character
     * @param input a live native standard-input handle owned by the caller
     * @param output a live native standard-output handle owned by the caller
     * @param error a live native standard-error handle owned by the caller
     * @return the owner, whose close terminates and drains any remaining descendants
     * @throws NullPointerException if an input or command/environment member is null
     * @throws IllegalArgumentException if an input violates its documented form
     * @throws UnsupportedOperationException if the platform, width or required symbols are unsupported
     * @throws IOException if native acquisition, launch or cleanup fails
     */
    public static WindowsProcess start(List<String> command, Path directory,
        Map<String,String> environment, MemorySegment input, MemorySegment output,
        MemorySegment error) throws IOException
    {
        validateStandardHandle(input, "Input");
        validateStandardHandle(output, "Output");
        validateStandardHandle(error, "Error");
        var arguments = validate(command, directory, environment, output);
        return launch(arguments, directory, Map.copyOf(environment), input, output, error, new Kernel());
    }

    /**
     * Launches with separate borrowed standard handles and supplied native access.
     *
     * @param command copied, validated direct argv
     * @param directory validated absolute working directory
     * @param environment copied, validated child environment
     * @param input borrowed live standard-input handle
     * @param output borrowed live standard-output handle
     * @param error borrowed live standard-error handle
     * @param kernel owned native access to release even after failed construction
     * @return the completed process owner
     * @throws NullPointerException if a standard handle is null
     * @throws IllegalArgumentException if a standard handle is not live and native
     * @throws IOException if native launch or cleanup fails
     */
    static WindowsProcess launch(List<String> command, Path directory,
        Map<String,String> environment, MemorySegment input, MemorySegment output,
        MemorySegment error, Kernel kernel) throws IOException
    {
        try
        {
            validateStandardHandle(input, "Input");
            validateStandardHandle(output, "Output");
            validateStandardHandle(error, "Error");
            return new WindowsProcess(command, directory, environment, input, output, error, kernel);
        }
        catch (IOException | RuntimeException | Error failure)
        {
            try { kernel.close(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /**
     * Launches using supplied native access, transferring ownership only after complete construction.
     *
     * @param command copied, validated direct argv
     * @param directory validated absolute working directory
     * @param environment copied, validated child environment
     * @param output borrowed live output handle
     * @param kernel owned native access to release even after failed construction
     * @return the completed process owner
     * @throws IOException if native launch or cleanup fails
     */
    static WindowsProcess launch(List<String> command, Path directory,
        Map<String,String> environment, MemorySegment output, Kernel kernel) throws IOException
    {
        try
        {
            return new WindowsProcess(command, directory, environment, null, output, null, kernel);
        }
        catch (IOException | RuntimeException | Error failure)
        {
            try { kernel.close(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /**
     * Acquires a contained workload and arranges reverse-order cleanup before every acquisition.
     *
     * @param command validated direct argv
     * @param directory validated absolute working directory
     * @param environment copied child environment
     * @param input borrowed input handle, or null for NUL input and merged output
     * @param output borrowed output handle
     * @param error borrowed error handle, ignored when input is null
     * @param kernel native access owned after successful construction
     * @throws IOException if native acquisition, launch or cleanup fails
     */
    private WindowsProcess(List<String> command, Path directory,
        Map<String,String> environment, MemorySegment input, MemorySegment output,
        MemorySegment error, Kernel kernel) throws IOException
    {
        this.kernel = Objects.requireNonNull(kernel, "kernel may not be null");
        accounting = kernel.arena.allocate(48, 8);
        exitCode = kernel.arena.allocate(ValueLayout.JAVA_INT);
        try (var owned = new Releases())
        {
            job = kernel.pointer("CreateJobObjectW", MemorySegment.NULL, MemorySegment.NULL);
            kernel.requireHandle("CreateJobObjectW", job);
            owned.add(() -> kernel.closeHandle(job));
            var limits = kernel.arena.allocate(144, 8);
            limits.set(ValueLayout.JAVA_INT, 16, 0x2000);
            kernel.require("SetInformationJobObject", kernel.call("SetInformationJobObject", job, 9, limits, 144));
            create(command, directory, environment, input, output, error, owned);
            releases = owned.transfer();
        }
    }

    /**
     * Creates a process with atomic job membership and an explicit inherited-handle list.
     *
     * @param command validated direct argv
     * @param directory validated absolute working directory
     * @param environment copied child environment
     * @param input borrowed input handle, or null for NUL input and merged output
     * @param output borrowed output handle
     * @param error borrowed error handle, ignored when input is null
     * @param owned receives all lifetime resources before subsequent fallible work
     * @throws IOException if native launch or release fails
     */
    private void create(List<String> command, Path directory, Map<String,String> environment,
        MemorySegment input, MemorySegment output, MemorySegment error, Releases owned) throws IOException
    {
        try (var launch = new Releases())
        {
            var standardInput = input;
            if (standardInput == null)
            {
                var nullInput = kernel.pointer("CreateFileW", kernel.wide("NUL"), 0x80000000, 3,
                    MemorySegment.NULL, 3, 0, MemorySegment.NULL);
                kernel.requireHandle("CreateFileW(NUL)", nullInput);
                launch.add(() -> kernel.closeHandle(nullInput));
                standardInput = nullInput;
            }
            var current = kernel.pointer("GetCurrentProcess");
            var inputCopy = kernel.duplicate(current, standardInput);
            launch.add(() -> kernel.closeHandle(inputCopy));
            var outputCopy = kernel.duplicate(current, output);
            launch.add(() -> kernel.closeHandle(outputCopy));
            var errorCopy = outputCopy;
            if (input != null)
            {
                var separateErrorCopy = kernel.duplicate(current, error);
                launch.add(() -> kernel.closeHandle(separateErrorCopy));
                errorCopy = separateErrorCopy;
            }
            var size = kernel.arena.allocate(ValueLayout.JAVA_LONG);
            int query = kernel.integer("InitializeProcThreadAttributeList", MemorySegment.NULL, 2, 0, size);
            long queryError = kernel.lastError();
            if (query != 0 || queryError != 122)
                throw new IOException("Attribute-list size query requires error 122; got result " + query + " and error " + queryError);
            long attributeBytes = size.get(ValueLayout.JAVA_LONG, 0);
            if (attributeBytes <= 0)
                throw new IOException("Attribute-list size must be positive: " + attributeBytes);
            var attributes = kernel.arena.allocate(attributeBytes, 8);
            kernel.require("InitializeProcThreadAttributeList",
                kernel.call("InitializeProcThreadAttributeList", attributes, 2, 0, size));
            launch.add(() -> kernel.call("DeleteProcThreadAttributeList", attributes));
            var jobs = kernel.arena.allocate(ValueLayout.ADDRESS);
            jobs.set(ValueLayout.ADDRESS, 0, job);
            long inheritedBytes = input == null ? 16 : 24;
            var inherited = kernel.arena.allocate(inheritedBytes, 8);
            inherited.set(ValueLayout.ADDRESS, 0, inputCopy);
            inherited.set(ValueLayout.ADDRESS, 8, outputCopy);
            if (input != null)
                inherited.set(ValueLayout.ADDRESS, 16, errorCopy);
            kernel.require("UpdateProcThreadAttribute(JOB_LIST)", kernel.call("UpdateProcThreadAttribute",
                attributes, 0, 0x2000dL, jobs, 8L, MemorySegment.NULL, MemorySegment.NULL));
            kernel.require("UpdateProcThreadAttribute(HANDLE_LIST)", kernel.call("UpdateProcThreadAttribute",
                attributes, 0, 0x20002L, inherited, inheritedBytes, MemorySegment.NULL, MemorySegment.NULL));

            // STARTUPINFOEXW is 112 bytes on both native Windows 64-bit targets.
            var startup = kernel.arena.allocate(112, 8);
            startup.set(ValueLayout.JAVA_INT, 0, 112);
            startup.set(ValueLayout.JAVA_INT, 60, 0x100);
            startup.set(ValueLayout.ADDRESS, 80, inputCopy);
            startup.set(ValueLayout.ADDRESS, 88, outputCopy);
            startup.set(ValueLayout.ADDRESS, 96, errorCopy);
            startup.set(ValueLayout.ADDRESS, 104, attributes);
            var info = kernel.arena.allocate(24, 8);
            kernel.require("CreateProcessW", kernel.call("CreateProcessW", kernel.wide(command.getFirst()),
                kernel.wide(commandLine(command)), MemorySegment.NULL, MemorySegment.NULL, 1, 0x80400,
                kernel.wide(environmentBlock(environment)), kernel.wide(directory.toString()), startup, info));
            process = info.get(ValueLayout.ADDRESS, 0);
            pid = Integer.toUnsignedLong(info.get(ValueLayout.JAVA_INT, 16));
            owned.add(() -> kernel.closeHandle(process));
            var thread = info.get(ValueLayout.ADDRESS, 8);
            owned.add(() -> kernel.closeHandle(thread));
            owned.add(this::drain);
        }
    }

    /**
     * Returns completion only after the owned job has no active processes and the root is signaled.
     *
     * @return empty while any owned work remains, otherwise original root status and abortion state
     * @throws IllegalStateException if this owner is closed
     * @throws IOException if native status acquisition fails
     */
    public Optional<Result> poll() throws IOException
    {
        requireOpen();
        kernel.require("QueryInformationJobObject", kernel.call("QueryInformationJobObject", job, 1,
            accounting, 48, MemorySegment.NULL));
        if (accounting.get(ValueLayout.JAVA_INT, 40) != 0)
            return Optional.empty();
        int status = kernel.integer("WaitForSingleObject", process, 0);
        if (status == 0x102)
            return Optional.empty();
        if (status == -1)
            throw kernel.failure("WaitForSingleObject");
        if (status != 0)
            throw new IOException("Unexpected root wait status: " + Integer.toUnsignedLong(status));
        kernel.require("GetExitCodeProcess", kernel.call("GetExitCodeProcess", process, exitCode));
        return Optional.of(new Result(Integer.toUnsignedLong(exitCode.get(ValueLayout.JAVA_INT, 0)), aborted));
    }

    /**
     * Reads the sum of current working-set bytes for every process in this owner's Job Object, including child jobs.
     * All retained member handles must remain live and in the job, and complete membership and accounting observations
     * must agree before and after reading. These sequential observations are not an atomic snapshot or a lifetime peak;
     * shared pages may be counted once per process. The root may have exited while descendants remain.
     * <p>
     * A successful empty-job observation returns zero. Missing, inaccessible, changing or unrepresentable observations
     * fail rather than returning a partial sum. Sampling neither terminates work nor accepts whole-job completion.
     * Query handles are invocation-owned. Failed release permanently prevents another sample; close still releases
     * lifetime resources and reports the unresolved release without repeating a possibly ambiguous handle operation.
     *
     * @return nonnegative current working-set bytes summed across observed job members
     * @throws IllegalStateException if closed or called outside the controlling thread
     * @throws IOException if native acquisition, membership, counters, bounded enumeration or query cleanup fails
     */
    public long readResidentBytes() throws IOException
    {
        requireOpen();
        if (Thread.currentThread() != controllingThread)
            throw new IllegalStateException("Windows process residency must be read by its controlling thread");
        if (residencyCleanupFailure != null)
            throw new IOException("Job residency sampling stopped after unresolved query-handle cleanup",
                residencyCleanupFailure);

        try (var scope = Arena.ofConfined(); var queries = new Releases())
        {
            var before = readMembershipAccounting(scope);
            var members = readMembers(scope);
            if (before.active() != members.size())
                throw new IOException("Job active count differs from its complete process list");
            var handles = new ArrayList<MemorySegment>();
            var counters = scope.allocate(MEMORY_COUNTER_BYTES, 8);
            var inJob = scope.allocate(ValueLayout.JAVA_INT);
            long total = 0;
            for (long member : members)
            {
                var handle = kernel.pointer("OpenProcess", 0x101000, 0, (int)member);
                kernel.requireHandle("OpenProcess for job member " + member, handle);
                queries.add(() -> closeResidencyQuery(handle));
                handles.add(handle);
                requireLiveMember(handle, inJob);
                counters.fill((byte)0);
                counters.set(ValueLayout.JAVA_INT, 0, MEMORY_COUNTER_BYTES);
                kernel.require("K32GetProcessMemoryInfo", kernel.call("K32GetProcessMemoryInfo",
                    handle, counters, MEMORY_COUNTER_BYTES));
                long bytes = counters.get(ValueLayout.JAVA_LONG, 16);
                if (bytes < 0)
                    throw new IOException("Process working-set bytes exceed the supported signed-long range");
                try { total = Math.addExact(total, bytes); }
                catch (ArithmeticException failure)
                {
                    throw new IOException("Summed job working-set bytes exceed the supported signed-long range", failure);
                }
            }

            for (var handle : handles)
                requireLiveMember(handle, inJob);
            var afterMembers = readMembers(scope);
            var after = readMembershipAccounting(scope);
            if (!members.equals(afterMembers) || before.total() != after.total() || after.active() != members.size())
                throw new IOException("Job membership changed while working-set bytes were sampled");
            return total;
        }
    }

    /**
     * Reads job birth and active counts used to reject observed membership churn.
     * @param scope invocation-owned storage
     * @return unsigned total-created and current-active process counts
     * @throws IOException if accounting fails or returns an incomplete structure
     */
    private MembershipAccounting readMembershipAccounting(Arena scope) throws IOException
    {
        var data = scope.allocate(48, 8);
        var length = scope.allocate(ValueLayout.JAVA_INT);
        kernel.require("QueryInformationJobObject(accounting)", kernel.call("QueryInformationJobObject",
            job, 1, data, 48, length));
        if (Integer.toUnsignedLong(length.get(ValueLayout.JAVA_INT, 0)) != 48)
            throw new IOException("Job accounting query did not return its complete 48-byte structure");
        return new MembershipAccounting(Integer.toUnsignedLong(data.get(ValueLayout.JAVA_INT, 36)),
            Integer.toUnsignedLong(data.get(ValueLayout.JAVA_INT, 40)));
    }

    /**
     * Reads one complete job membership list with bounded resizing, rejecting truncation and invalid identities.
     * @param scope invocation-owned storage released after this sample
     * @return distinct unsigned nonzero DWORD process IDs, preserving native enumeration order
     * @throws IOException if native enumeration, list validation or its resource bound fails
     */
    private Set<Long> readMembers(Arena scope) throws IOException
    {
        int capacity = 16;
        for (int attempt = 0; attempt < MEMBERSHIP_QUERY_ATTEMPTS; attempt++)
        {
            int byteCount = Math.addExact(8, Math.multiplyExact(capacity, 8));
            var data = scope.allocate(byteCount, 8);
            var length = scope.allocate(ValueLayout.JAVA_INT);
            int success = kernel.integer("QueryInformationJobObject", job, 3, data, byteCount, length);
            if (success == 0)
            {
                long error = kernel.lastError();
                if (error != 234)
                    throw new IOException("QueryInformationJobObject(process list) failed with Windows error " + error);
                long assigned = Integer.toUnsignedLong(data.get(ValueLayout.JAVA_INT, 0));
                long nextCapacity = Math.max(assigned, (long)capacity * 2);
                if (nextCapacity > MAX_MEMBERSHIP_PROCESSES)
                    throw new IOException("Job process list exceeds the sampling bound of " + MAX_MEMBERSHIP_PROCESSES);
                capacity = Math.toIntExact(nextCapacity);
                continue;
            }

            long assigned = Integer.toUnsignedLong(data.get(ValueLayout.JAVA_INT, 0));
            long returned = Integer.toUnsignedLong(data.get(ValueLayout.JAVA_INT, 4));
            long returnedBytes = Integer.toUnsignedLong(length.get(ValueLayout.JAVA_INT, 0));
            boolean incomplete = assigned != returned || returned > capacity ||
                returnedBytes < 8 + returned * 8 || returnedBytes > byteCount;
            if (incomplete)
                throw new IOException("Job process list has incomplete counts or returned length");
            var members = new LinkedHashSet<Long>();
            for (long index = 0; index < returned; index++)
            {
                long member = data.get(ValueLayout.JAVA_LONG, 8 + index * 8);
                if (member <= 0 || member > 0xffff_ffffL || !members.add(member))
                    throw new IOException("Job process list contains an invalid or duplicate process ID: " + member);
            }
            return members;
        }
        throw new IOException("Job process list did not fit after " + MEMBERSHIP_QUERY_ATTEMPTS + " attempts");
    }

    /**
     * Requires a retained process handle to remain unsignaled and associated with this exact job.
     * @param handle invocation-owned query handle
     * @param inJob reused invocation-owned BOOL storage
     * @throws IOException if the query fails, the process left the job or it exited
     */
    private void requireLiveMember(MemorySegment handle, MemorySegment inJob) throws IOException
    {
        kernel.require("IsProcessInJob", kernel.call("IsProcessInJob", handle, job, inJob));
        if (inJob.get(ValueLayout.JAVA_INT, 0) == 0)
            throw new IOException("Retained process is no longer a member of the owned Job Object");
        int status = kernel.integer("WaitForSingleObject", handle, 0);
        if (status == -1)
            throw kernel.failure("WaitForSingleObject(job residency)");
        if (status != 258)
            throw new IOException("Retained job member is not unsignaled; wait status=" + Integer.toUnsignedLong(status));
    }

    /**
     * Attempts query-handle release once, retaining unresolved ownership and preventing later successful sampling.
     * @param handle invocation-owned process query handle
     * @throws IOException if release fails
     */
    private void closeResidencyQuery(MemorySegment handle) throws IOException
    {
        try { kernel.closeHandle(handle); }
        catch (IOException failure)
        {
            unresolvedResidencyHandles.add(handle);
            var contextual = new IOException("Job residency query handle " +
                Long.toUnsignedString(handle.address()) + " could not be released", failure);
            if (residencyCleanupFailure == null)
                residencyCleanupFailure = contextual;
            else
                residencyCleanupFailure.addSuppressed(contextual);
            throw contextual;
        }
    }

    /**
     * Holds sequential unsigned Job Object accounting counts used for a membership-change check.
     * @param total number of processes assigned over this job's lifetime, modulo unsigned DWORD range
     * @param active number of currently active job processes
     */
    private record MembershipAccounting(long total, long active) { }

    /**
     * Marks this attempt aborted and requests code-125 termination of the entire job.
     * Already-exited roots retain their original status alongside the aborted flag.
     *
     * @throws IllegalStateException if this owner is closed
     * @throws IOException if native termination fails
     */
    public void terminate() throws IOException
    {
        requireOpen();
        aborted = true;
        kernel.require("TerminateJobObject", kernel.call("TerminateJobObject", job, ABORT_EXIT));
    }

    /**
     * Waits for whole-tree completion for no longer than the given monotonic duration.
     *
     * @param timeout a nonnegative maximum wait, representable in nanoseconds
     * @return original root status and explicit abortion state after all descendants are terminal
     * @throws NullPointerException if timeout is null
     * @throws IllegalArgumentException if timeout is negative or exceeds signed nanosecond range
     * @throws IllegalStateException if this owner is closed
     * @throws IOException if native status acquisition fails or the waiting thread is interrupted
     * @throws TimeoutException if any owned work remains at the deadline
     */
    public Result waitFor(Duration timeout) throws IOException, TimeoutException
    {
        Objects.requireNonNull(timeout, "timeout may not be null");
        if (timeout.isNegative())
            throw new IllegalArgumentException("Timeout must be nonnegative: " + timeout);
        final long maximum;
        try { maximum = timeout.toNanos(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Timeout exceeds signed nanosecond range", e); }
        long started = System.nanoTime();
        for (;;)
        {
            var result = poll();
            if (result.isPresent())
                return result.get();
            if (System.nanoTime() - started >= maximum)
                throw new TimeoutException("Owned process tree " + pid + " remains active after " + timeout);
            try { Thread.sleep(10); }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for owned process tree " + pid, e);
            }
        }
    }

    /**
     * Returns the launched root process identifier.
     *
     * @return the unsigned native root process identifier
     */
    public long pid()
    {
        return pid;
    }

    /**
     * Terminates unfinished work, waits up to ten seconds and closes every owned native handle.
     * Release failures are preserved, with subsequent failures suppressed; borrowed handles stay open.
     * Calling close again has no effect. A failed drain never becomes a successful completion receipt.
     *
     * @throws IOException if termination, drain or handle release fails
     */
    @Override
    public void close() throws IOException
    {
        if (closed)
            return;
        try (kernel)
        {
            try { releases.close(); }
            catch (IOException failure)
            {
                if (residencyCleanupFailure != null)
                    failure.addSuppressed(new IOException("Residency query handles remain unresolved: " +
                        unresolvedResidencyHandles.size(), residencyCleanupFailure));
                throw failure;
            }
            if (residencyCleanupFailure != null)
                throw new IOException("Residency query handles remain unresolved: " +
                    unresolvedResidencyHandles.size(), residencyCleanupFailure);
        }
        finally
        {
            closed = true;
        }
    }

    /**
     * Terminates and drains work only when the job is still active.
     *
     * @throws IOException if native control or the bounded drain fails
     */
    private void drain() throws IOException
    {
        if (poll().isEmpty())
        {
            terminate();
            try { waitFor(CLOSE_TIMEOUT); }
            catch (TimeoutException e) { throw new IOException("Owned process tree did not drain during close", e); }
        }
    }

    /**
     * Rejects use after native resources have been released.
     *
     * @throws IllegalStateException if closed
     */
    private void requireOpen()
    {
        if (closed)
            throw new IllegalStateException("Windows process owner is closed");
    }

    /**
     * Rejects invalid borrowed standard handles before any native acquisition.
     *
     * @param handle caller-owned standard handle
     * @param name standard stream named in a validation failure
     * @throws NullPointerException if handle is null
     * @throws IllegalArgumentException if its segment is closed, nonnative, NULL or INVALID_HANDLE_VALUE
     */
    private static void validateStandardHandle(MemorySegment handle, String name)
    {
        Objects.requireNonNull(handle, name + " may not be null");
        if (!handle.scope().isAlive() || !handle.isNative() || handle.address() == 0 || handle.address() == -1)
            throw new IllegalArgumentException(name + " must be a live native Windows handle");
    }

    /**
     * Copies and checks launch inputs before any native acquisition.
     *
     * @param command direct argv with an absolute executable
     * @param directory absolute existing working directory
     * @param environment explicit child environment
     * @param output borrowed native output handle
     * @return immutable command copy
     * @throws NullPointerException if any required value is null
     * @throws IllegalArgumentException if an input violates the launch contract
     */
    private static List<String> validate(List<String> command, Path directory,
        Map<String,String> environment, MemorySegment output)
    {
        var arguments = List.copyOf(command);
        Objects.requireNonNull(directory, "directory may not be null");
        Objects.requireNonNull(environment, "environment may not be null");
        Objects.requireNonNull(output, "output may not be null");
        if (arguments.isEmpty())
            throw new IllegalArgumentException("Command must contain an executable");
        for (String argument : arguments)
            if (argument.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Command arguments must contain no NUL");
        var executable = Path.of(arguments.getFirst());
        if (!executable.isAbsolute() || !Files.isRegularFile(executable))
            throw new IllegalArgumentException("Executable must be an existing absolute file: " + executable);
        if (!directory.isAbsolute() || !Files.isDirectory(directory))
            throw new IllegalArgumentException("Working directory must be an existing absolute directory: " + directory);
        if (!output.isNative() || output.address() == 0 || output.address() == -1)
            throw new IllegalArgumentException("Output must be a live native Windows handle");
        for (var entry : environment.entrySet())
        {
            String name = Objects.requireNonNull(entry.getKey(), "environment name may not be null");
            String value = Objects.requireNonNull(entry.getValue(), "environment value may not be null");
            if (name.isEmpty() || name.indexOf('\0') >= 0 || name.indexOf('=', 1) >= 0 || value.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Environment names must be nonempty with '=' only first, and names/values must contain no NUL");
        }
        return arguments;
    }

    /**
     * Encodes argv using the Microsoft CRT backslash-and-double-quote rules.
     *
     * @param command validated argv
     * @return CreateProcess command-line text without its terminating NUL
     */
    private static String commandLine(List<String> command)
    {
        var encoded = new ArrayList<String>();
        for (String argument : command)
        {
            var result = new StringBuilder("\"");
            int slashes = 0;
            for (char character : argument.toCharArray())
            {
                if (character == '\\')
                    slashes++;
                else
                {
                    if (character == '"')
                        result.append("\\".repeat(slashes * 2 + 1));
                    else
                        result.append("\\".repeat(slashes));
                    result.append(character);
                    slashes = 0;
                }
            }
            result.append("\\".repeat(slashes * 2)).append('"');
            encoded.add(result.toString());
        }
        return String.join(" ", encoded);
    }

    /**
     * Creates a case-insensitively ordered double-NUL environment block.
     *
     * @param environment copied explicit child environment
     * @return Windows UTF-16 environment text including its two terminating NULs
     */
    private static String environmentBlock(Map<String,String> environment)
    {
        return environment.entrySet().stream().sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
            .map(entry -> entry.getKey() + "=" + entry.getValue()).reduce((left,right) -> left + '\0' + right)
            .orElse("") + "\0\0";
    }

    /**
     * Owns native release callbacks in reverse acquisition order.
     */
    private static final class Releases implements AutoCloseable
    {
        private final ArrayDeque<Release> callbacks = new ArrayDeque<>();

        /**
         * Registers a release immediately after acquisition.
         * @param release the owned release action
         */
        void add(Release release) { callbacks.push(Objects.requireNonNull(release)); }

        /**
         * Transfers callbacks so failed scope exit no longer releases successfully handed-off resources.
         * @return the new owner of all callbacks
         */
        Releases transfer()
        {
            var result = new Releases();
            result.callbacks.addAll(callbacks);
            callbacks.clear();
            return result;
        }

        /**
         * Runs all releases even if one fails, suppressing subsequent errors onto the first.
         * @throws IOException if any release fails
         */
        @Override
        public void close() throws IOException
        {
            IOException failure = null;
            while (callbacks.isEmpty() == false)
            {
                try { callbacks.pop().run(); }
                catch (IOException e)
                {
                    if (failure == null)
                        failure = e;
                    else
                        failure.addSuppressed(e);
                }
            }
            if (failure != null)
                throw failure;
        }
    }

    /**
     * Releases one invocation-owned native resource.
     */
    @FunctionalInterface
    private interface Release
    {
        /**
         * Releases the resource.
         * @throws IOException if native release fails
         */
        void run() throws IOException;
    }

    /**
     * Binds this operation's native Windows calls with captured last-error state.
     * Package visibility permits a focused fixture to exercise cleanup-error aggregation.
     */
    static class Kernel implements AutoCloseable
    {
        final Arena arena;
        private final Map<String,MethodHandle> functions = new HashMap<>();
        private final MemorySegment errors;
        private final long errorOffset;

        /**
         * Acquires native access on 64-bit Windows, releasing its scope if binding fails.
         * @throws UnsupportedOperationException if the platform or required symbols are unsupported
         */
        Kernel()
        {
            if (!System.getProperty("os.name", "").startsWith("Windows") || ValueLayout.ADDRESS.byteSize() != 8)
                throw new UnsupportedOperationException("WindowsProcess requires native 64-bit Windows");
            arena = Arena.ofConfined();
            try
            {
                var lookup = SymbolLookup.libraryLookup("Kernel32.dll", arena);
                errors = arena.allocate(Linker.Option.captureStateLayout());
                errorOffset = Linker.Option.captureStateLayout().byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"));
                bind(lookup,"CreateJobObjectW",ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.ADDRESS);
                bind(lookup,"SetInformationJobObject",ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.JAVA_INT);
                bind(lookup,"QueryInformationJobObject",ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.JAVA_INT,ValueLayout.ADDRESS);
                bind(lookup,"CloseHandle",ValueLayout.JAVA_INT,ValueLayout.ADDRESS);
                bind(lookup,"CreateFileW",ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.JAVA_INT,ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.JAVA_INT,ValueLayout.JAVA_INT,ValueLayout.ADDRESS);
                bind(lookup,"GetCurrentProcess",ValueLayout.ADDRESS);
                bind(lookup,"DuplicateHandle",ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.JAVA_INT,ValueLayout.JAVA_INT,ValueLayout.JAVA_INT);
                bind(lookup,"InitializeProcThreadAttributeList",ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.JAVA_INT,ValueLayout.JAVA_INT,ValueLayout.ADDRESS);
                bind(lookup,"UpdateProcThreadAttribute",ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.JAVA_INT,ValueLayout.JAVA_LONG,ValueLayout.ADDRESS,ValueLayout.JAVA_LONG,ValueLayout.ADDRESS,ValueLayout.ADDRESS);
                bind(lookup,"DeleteProcThreadAttributeList",null,ValueLayout.ADDRESS);
                bind(lookup,"CreateProcessW",ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.JAVA_INT,ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.ADDRESS);
                bind(lookup,"TerminateJobObject",ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.JAVA_INT);
                bind(lookup,"WaitForSingleObject",ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.JAVA_INT);
                bind(lookup,"GetExitCodeProcess",ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.ADDRESS);
                bind(lookup,"OpenProcess",ValueLayout.ADDRESS,ValueLayout.JAVA_INT,ValueLayout.JAVA_INT,ValueLayout.JAVA_INT);
                bind(lookup,"IsProcessInJob",ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.ADDRESS);
                bind(lookup,"K32GetProcessMemoryInfo",ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.JAVA_INT);
            }
            catch (RuntimeException | Error failure)
            {
                try { arena.close(); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        /**
         * Supplies scoped storage for a native-access fixture without binding Windows symbols.
         *
         * @param arena owned storage released by close
         */
        Kernel(Arena arena)
        {
            this.arena = Objects.requireNonNull(arena, "arena may not be null");
            errors = arena.allocate(ValueLayout.JAVA_INT);
            errorOffset = 0;
        }

        /**
         * Binds a required native symbol using the Windows ABI and captured GetLastError.
         * @param lookup native library symbols
         * @param name the required symbol
         * @param result nullable native result layout; null means void
         * @param arguments native argument layouts
         */
        private void bind(SymbolLookup lookup, String name, MemoryLayout result, MemoryLayout... arguments)
        {
            var address = lookup.find(name).orElseThrow(() -> new UnsupportedOperationException("Missing Windows symbol: " + name));
            var descriptor = result == null ? FunctionDescriptor.ofVoid(arguments) : FunctionDescriptor.of(result,arguments);
            functions.put(name, Linker.nativeLinker().downcallHandle(address,descriptor,Linker.Option.captureCallState("GetLastError")));
        }

        /**
         * Invokes a bound native operation with this scope's captured error storage.
         * @param name the native operation
         * @param arguments arguments in native signature order
         * @return the native return value, or null for void
         * @throws IOException if a checked native invocation fails
         */
        Object call(String name, Object... arguments) throws IOException
        {
            var values = new ArrayList<Object>();
            values.add(errors);
            values.addAll(List.of(arguments));
            try { return functions.get(name).invokeWithArguments(values); }
            catch (RuntimeException | Error e) { throw e; }
            catch (Throwable e) { throw new IOException("Native invocation failed: " + name,e); }
        }

        /**
         * Invokes an operation returning a native handle.
         * @param name the native operation
         * @param arguments native arguments
         * @return the native pointer value
         * @throws IOException if invocation fails
         */
        MemorySegment pointer(String name, Object... arguments) throws IOException { return (MemorySegment)call(name,arguments); }

        /**
         * Invokes an operation returning a native DWORD or BOOL.
         * @param name the native operation
         * @param arguments native arguments
         * @return the native integer bits
         * @throws IOException if invocation fails
         */
        int integer(String name, Object... arguments) throws IOException { return (int)call(name,arguments); }

        /**
         * Requires a successful native BOOL result.
         * @param name operation described on failure
         * @param result native BOOL result
         * @throws IOException if the native BOOL is false
         */
        void require(String name, Object result) throws IOException
        {
            if ((int)result == 0)
                throw failure(name);
        }

        /**
         * Rejects null and INVALID_HANDLE_VALUE acquisition results.
         * @param name acquisition described on failure
         * @param handle returned native handle
         * @throws IOException if the acquisition failed
         */
        void requireHandle(String name, MemorySegment handle) throws IOException
        {
            if (handle.address() == 0 || handle.address() == -1)
                throw failure(name);
        }

        /**
         * Duplicates a borrowed handle as inheritable without changing the original.
         * @param current pseudo-handle of this process
         * @param original borrowed native handle
         * @return an owned inheritable duplicate
         * @throws IOException if duplication fails
         */
        MemorySegment duplicate(MemorySegment current, MemorySegment original) throws IOException
        {
            var copy = arena.allocate(ValueLayout.ADDRESS);
            require("DuplicateHandle",call("DuplicateHandle",current,original,current,copy,0,1,2));
            return copy.get(ValueLayout.ADDRESS,0);
        }

        /**
         * Closes an owned native handle.
         * @param handle the handle to release
         * @throws IOException if native release fails
         */
        void closeHandle(MemorySegment handle) throws IOException { require("CloseHandle",call("CloseHandle",handle)); }

        /**
         * Returns the last native call's captured Windows error code.
         * @return the unsigned GetLastError value
         */
        long lastError() { return Integer.toUnsignedLong(errors.get(ValueLayout.JAVA_INT,errorOffset)); }

        /**
         * Creates a failure preserving a captured native error.
         * @param name the failed operation
         * @return an IOException containing its unsigned Windows error
         */
        IOException failure(String name) { return new IOException(name + " failed with Windows error " + lastError()); }

        /**
         * Allocates mutable NUL-terminated Windows UTF-16 text in this scope.
         * @param text text excluding its implicit terminal NUL
         * @return owned native UTF-16 storage
         */
        MemorySegment wide(String text)
        {
            return arena.allocateFrom(ValueLayout.JAVA_CHAR, (text + '\0').toCharArray());
        }

        @Override
        public void close() { arena.close(); }
    }
}
