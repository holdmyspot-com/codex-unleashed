package com.holdmyspot.codexunleashed.scheduler;

import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.Test;

/** Exercises production stream ownership through controlled native operations on every platform. */
public final class WindowsCargoStreamsTest
{
    /**
     * Direct Cargo launch borrows original local console handles and aliases without acquiring relay pipes.
     * @throws IOException if original-handle capture fails
     */
    @Test
    public void rootCapturePreservesOriginalHandlesAndAliases() throws IOException
    {
        Calls calls = new Calls();
        calls.consoleOutput = true;
        calls.aliasError = true;
        WindowsCargoStreams.Root streams = WindowsCargoStreams.root(calls);
        try
        {
            requireThat(List.of(streams.input().address(), streams.output().address(), streams.error().address()),
                "unchangedLocalRootHandles").isEqualTo(List.of(10L, 11L, 11L));
            requireThat(calls.pipeCount, "noConsoleRelayForLocalRoot").isEqualTo(0);
            streams.close();
        }
        finally { streams.close(); }
        requireThat(calls.closedHandles.isEmpty(), "originalHandlesRemainBorrowed").isTrue();
        requireThat(calls.closes, "bindingsClosedOnce").isEqualTo(1);
    }

    /**
     * Invalid original root handles close only binding ownership without closing caller-owned earlier handles.
     */
    @Test
    public void invalidRootCaptureReleasesOnlyBindings()
    {
        Calls calls = new Calls();
        calls.invalidOutput = true;
        requireException(IOException.class, () -> WindowsCargoStreams.root(calls));
        requireThat(calls.closedHandles.isEmpty(), "noBorrowedHandleClosedOnFailure").isTrue();
        requireThat(calls.closes, "bindingsClosedAfterRejectedCapture").isEqualTo(1);
    }

    /**
     * An uncertain binding close remains a retained failure and cannot become successful through a later no-op.
     * @throws IOException if controlled capture fails
     */
    @Test
    public void rootBindingCloseFailureRemainsSticky() throws IOException
    {
        Calls calls = new Calls();
        calls.bindingFailure = new IllegalStateException("controlled binding close uncertainty");
        WindowsCargoStreams.Root streams = WindowsCargoStreams.root(calls);
        requireThat(requireException(IllegalStateException.class, streams::close), "originalBindingFailure").isEqualTo(calls.bindingFailure);
        requireThat(requireException(IllegalStateException.class, streams::close), "retainedBindingFailure").isEqualTo(calls.bindingFailure);
        requireThat(calls.closes, "noRepeatedUncertainBindingEffect").isEqualTo(1);
    }

    /** Creates a resource-free test instance; each method owns all stream state. */
    public WindowsCargoStreamsTest() {}

    /**
     * Redirected endpoints remain borrowed across acceptance and terminal cleanup.
     * @throws Exception if acquisition or cleanup fails
     */
    @Test
    public void redirectedEndpointsRemainBorrowed() throws Exception
    {
        Calls calls = new Calls();
        try (WindowsCargoStreams streams = WindowsCargoStreams.open(calls))
        {
            requireThat(List.of(streams.input(), streams.output(), streams.error()), "originalHandles")
                .isEqualTo(List.of(10L, 11L, 12L));
            requireException(IOException.class, streams::finish);
            streams.accepted();
            requireException(IllegalStateException.class, streams::accepted);
            streams.finish();
            requireException(IllegalStateException.class, streams::accepted);
        }
        requireThat(calls.closedHandles.isEmpty(), "borrowedHandlesNeverClosed").isTrue();
        requireThat(calls.closes, "bindingCloseOnce").isEqualTo(1);
    }

    /**
     * A later invalid standard handle closes bindings without consuming earlier borrowed handles.
     * @throws Exception if failure verification fails
     */
    @Test
    public void failedCaptureCleansBindingsWithoutClosingBorrowedHandles() throws Exception
    {
        Calls calls = new Calls(); calls.invalidOutput = true;
        requireException(IOException.class, () -> WindowsCargoStreams.open(calls));
        requireThat(calls.closedHandles.isEmpty(), "earlierBorrowedInputPreserved").isTrue();
        requireThat(calls.closes, "failedAcquisitionBindingsClosed").isEqualTo(1);
    }

    /**
     * Shared console stdout and stderr share one relay while short writes preserve all byte values.
     * @throws Exception if relay progress, acquisition or cleanup fails
     */
    @Test
    public void aliasedConsoleOutputHasOneOwnedRelayAndPreservesShortWrites() throws Exception
    {
        Calls calls = new Calls(); calls.consoleOutput = true; calls.aliasError = true;
        try (WindowsCargoStreams streams = WindowsCargoStreams.open(calls))
        {
            requireThat(streams.output(), "offeredWriteEnd").isEqualTo(102L);
            requireThat(streams.error(), "preservedOutputAlias").isEqualTo(streams.output());
            streams.accepted();
            requireThat(calls.copied.await(5, TimeUnit.SECONDS), "relayReachedEOF").isTrue();
            streams.finish();
        }
        byte[] expected = new byte[256];
        for (int index = 0; index < expected.length; ++index) expected[index] = (byte) index;
        requireThat(java.util.Arrays.equals(calls.output.toByteArray(), expected), "allByteValuesPreserved").isTrue();
        requireThat(calls.pipeCount, "singleAliasPipe").isEqualTo(1);
        requireThat(calls.closedHandles.stream().sorted().toList(), "ownedPipeAndThreadHandlesOnly")
            .isEqualTo(List.of(101L, 102L, 301L));
        requireThat(calls.closes, "bindingsAfterWorkerJoin").isEqualTo(1);
    }

    /**
     * A failed native close consumes its authority and remains sticky across repeated close calls.
     * @throws Exception if fixture progress or failure verification fails
     */
    @Test
    public void failedOwnedCloseIsNotRepeated() throws Exception
    {
        Calls calls = new Calls(); calls.consoleOutput = true; calls.failClose = 102;
        WindowsCargoStreams streams = WindowsCargoStreams.open(calls);
        requireThat(calls.copied.await(5, TimeUnit.SECONDS), "relayStoppedBeforeCloseFault").isTrue();
        requireException(IOException.class, streams::close);
        List<Long> first = List.copyOf(calls.closedHandles);
        requireException(IOException.class, streams::close);
        requireThat(calls.closedHandles, "nativeCloseEffectsNotRepeated").isEqualTo(first);
        requireThat(calls.closedHandles.stream().filter(value -> value == 102).count(), "failedCloseAttemptedOnce").isEqualTo(1L);
        requireThat(calls.closes, "bindingsClosedDespiteOwnedHandleFault").isEqualTo(1);
    }

    /** Controlled Windows operations; native platform behavior remains a separate acceptance gate. */
    private static final class Calls implements WindowsCargoStreams.Calls
    {
        /** Storage shared with the real production relay thread until its join. */
        private final Arena arena = Arena.ofShared();
        /** Native closure attempts made by production ownership code. */
        private final List<Long> closedHandles = new ArrayList<>();
        /** Bytes accepted by modeled borrowed console output. */
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        /** Signals that the production relay encountered deterministic EOF. */
        private final CountDownLatch copied = new CountDownLatch(1);
        /** Whether stdout needs a private console relay. */
        private boolean consoleOutput;
        /** Whether stderr aliases stdout's original console endpoint. */
        private boolean aliasError;
        /** Whether standard-output capture fails. */
        private boolean invalidOutput;
        /** Exact owned handle whose close attempt fails, zero when absent. */
        private long failClose;
        /** Number of private pipe acquisitions. */
        private int pipeCount;
        /** Number of native binding disposal calls. */
        private int closes;
        /** Controlled binding disposal uncertainty after releasing fixture-owned storage. */
        private RuntimeException bindingFailure;
        /** Whether the relay's first read already returned its binary payload. */
        private boolean read;

        /** Creates an independent operation owner without native platform acquisition. */
        private Calls() {}
        @Override public Arena arena() { return arena; }
        @Override public MemorySegment address(String name, Object... arguments)
        {
            if (name.equals("OpenThread")) return MemorySegment.ofAddress(301);
            int role = -10 - (int) arguments[0];
            if (role == 1 && invalidOutput) return MemorySegment.NULL;
            return MemorySegment.ofAddress(role == 2 && aliasError ? 11 : 10 + role);
        }
        @Override public int integer(String name, Object... arguments)
        {
            switch (name)
            {
                case "CreatePipe" ->
                {
                    ++pipeCount;
                    ((MemorySegment) arguments[0]).set(ValueLayout.ADDRESS, 0, MemorySegment.ofAddress(101));
                    ((MemorySegment) arguments[1]).set(ValueLayout.ADDRESS, 0, MemorySegment.ofAddress(102));
                    return 1;
                }
                case "GetCurrentThreadId", "CancelSynchronousIo" -> { return 1; }
                case "ReadFile" ->
                {
                    if (read) { copied.countDown(); return 0; }
                    read = true;
                    MemorySegment buffer = (MemorySegment) arguments[1];
                    for (int index = 0; index < 256; ++index) buffer.set(ValueLayout.JAVA_BYTE, index, (byte) index);
                    ((MemorySegment) arguments[3]).set(ValueLayout.JAVA_INT, 0, 256);
                    return 1;
                }
                case "WriteFile" ->
                {
                    int length = Math.min(7, (int) arguments[2]);
                    MemorySegment buffer = (MemorySegment) arguments[1];
                    for (int index = 0; index < length; ++index) output.write(buffer.get(ValueLayout.JAVA_BYTE, index));
                    ((MemorySegment) arguments[3]).set(ValueLayout.JAVA_INT, 0, length);
                    return 1;
                }
                default -> throw new IllegalStateException("Unexpected modeled operation: " + name);
            }
        }
        @Override public int error() { return 109; }
        @Override public boolean console(MemorySegment handle) { return consoleOutput && handle.address() == 11; }
        @Override public synchronized void closeHandle(MemorySegment handle) throws IOException
        {
            closedHandles.add(handle.address());
            if (handle.address() == failClose) throw new IOException("injected owned close failure");
        }
        @Override public void close() { ++closes; arena.close(); if (bindingFailure != null) throw bindingFailure; }
    }
}
