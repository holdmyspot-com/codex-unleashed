package com.holdmyspot.codexunleashed.scheduler;

import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.expectThrows;
import static org.testng.Assert.assertEquals;

/** Verifies the gate's post-release execution decision without acquiring native resources. */
public final class LinuxProcessTreeTest {
    /** Creates gate dispatch tests. */
    public LinuxProcessTreeTest() { }

    /** An expired attached gate fails without selecting the native workload exec operation. */
    @Test
    public void expiresAttachedGateWithoutExecutingWorkload() {
        long expiredDeadline = System.nanoTime();
        expectThrows(IOException.class, () -> LinuxProcessTree.SharedSecrets.executeAfterRelease(
            LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC,
            () -> { throw new AssertionError("Attached mode selected direct workload exec"); }, expiredDeadline));
    }

    /** Direct mode selects native exec once even when the external replacement deadline has elapsed. */
    @Test
    public void executesDirectWorkloadWithoutWaitingForReplacement() throws IOException, InterruptedException {
        AtomicInteger executions = new AtomicInteger();
        LinuxProcessTree.SharedSecrets.executeAfterRelease(LinuxProcessTree.ExecutionMode.DIRECT_EXEC,
            executions::incrementAndGet, System.nanoTime());
        assertEquals(executions.get(), 1);
    }

    /** An interrupted attached wait propagates interruption without falling back to native exec. */
    @Test
    public void interruptsAttachedWaitWithoutExecutingWorkload() {
        boolean previouslyInterrupted = Thread.interrupted();
        try {
            Thread.currentThread().interrupt();
            expectThrows(InterruptedException.class, () -> LinuxProcessTree.SharedSecrets.executeAfterRelease(
                LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC,
                () -> { throw new AssertionError("Interrupted attached mode selected direct workload exec"); },
                System.nanoTime() + TimeUnit.MINUTES.toNanos(1)));
        } finally {
            Thread.interrupted();
            if (previouslyInterrupted) Thread.currentThread().interrupt();
        }
    }

    /** The compatibility constructor retains the legacy direct-exec decision. */
    @Test
    public void legacyConstructorExecutesDirectly() throws IOException, InterruptedException {
        Path parent = Path.of("unresolved-gate-context").toAbsolutePath();
        var launch = new LinuxProcessTree.Launch(parent, parent, parent.resolve("java"), parent.resolve("classes"),
            List.of(parent.resolve("workload").toString()), Map.of(), parent, 1001, 1001);
        AtomicInteger executions = new AtomicInteger();
        LinuxProcessTree.SharedSecrets.executeAfterRelease(launch.executionMode(), executions::incrementAndGet,
            System.nanoTime());
        assertEquals(executions.get(), 1);
    }

    /** Missing legacy XML mode selects direct exec. */
    @Test
    public void absentXmlModeExecutesDirectly() throws IOException, InterruptedException {
        AtomicInteger executions = new AtomicInteger();
        LinuxProcessTree.SharedSecrets.executeAfterRelease(
            LinuxProcessTree.ExecutionMode.readFrom(xmlRoundTrip(new Properties())), executions::incrementAndGet,
            System.nanoTime());
        assertEquals(executions.get(), 1);
    }

    /**
     * Supplies each supported explicit release mode.
     * @return the direct and attached modes
     */
    @DataProvider
    public Object[][] executionModes() {
        return new Object[][] {{LinuxProcessTree.ExecutionMode.DIRECT_EXEC},
            {LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC}};
    }

    /**
     * The shared mode codec retains the dispatch decision through two XML serializations.
     * Local Properties represent the two launch handoffs; this does not exercise native process ownership.
     * @param mode explicit launch mode
     */
    @Test(dataProvider = "executionModes")
    public void retainsExecutionDecisionThroughXml(LinuxProcessTree.ExecutionMode mode)
            throws IOException, InterruptedException {
        Properties controller = new Properties();
        mode.writeTo(controller);
        LinuxProcessTree.ExecutionMode supervisorMode = LinuxProcessTree.ExecutionMode.readFrom(xmlRoundTrip(controller));
        Properties supervisor = new Properties();
        supervisorMode.writeTo(supervisor);
        LinuxProcessTree.ExecutionMode gateMode = LinuxProcessTree.ExecutionMode.readFrom(xmlRoundTrip(supervisor));
        AtomicInteger executions = new AtomicInteger();
        if (mode == LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC) {
            expectThrows(IOException.class, () -> LinuxProcessTree.SharedSecrets.executeAfterRelease(gateMode,
                executions::incrementAndGet, System.nanoTime()));
            assertEquals(executions.get(), 0);
        } else {
            LinuxProcessTree.SharedSecrets.executeAfterRelease(gateMode, executions::incrementAndGet, System.nanoTime());
            assertEquals(executions.get(), 1);
        }
    }

    /** Unknown explicit XML mode fails instead of selecting legacy direct exec. */
    @Test
    public void rejectsUnknownXmlMode() throws IOException {
        Properties values = new Properties();
        values.setProperty("execution.mode", "AUTOMATIC");
        Properties decoded = xmlRoundTrip(values);
        expectThrows(IOException.class, () -> LinuxProcessTree.ExecutionMode.readFrom(decoded));
    }

    /** An explicit empty XML mode is invalid rather than an absent legacy mode. */
    @Test
    public void rejectsEmptyXmlMode() throws IOException {
        Properties values = new Properties();
        values.setProperty("execution.mode", "");
        Properties decoded = xmlRoundTrip(values);
        expectThrows(IOException.class, () -> LinuxProcessTree.ExecutionMode.readFrom(decoded));
    }

    /** Missing explicit launch mode is rejected before any acquisition. */
    @Test
    public void rejectsNullExecutionMode() {
        Path parent = Path.of("unresolved-gate-context").toAbsolutePath();
        expectThrows(NullPointerException.class, () -> new LinuxProcessTree.Launch(parent, parent,
            parent.resolve("java"), parent.resolve("classes"), List.of(parent.resolve("workload").toString()),
            Map.of(), parent, 1001, 1001, null));
    }

    /**
     * Serializes and reads launch properties using the XML format consumed by both launch layers.
     * @param values properties to transmit
     * @return independently decoded properties
     * @throws IOException if XML serialization or decoding fails
     */
    private Properties xmlRoundTrip(Properties values) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        values.storeToXML(bytes, "Launch mode fixture");
        Properties decoded = new Properties();
        decoded.loadFromXML(new ByteArrayInputStream(bytes.toByteArray()));
        return decoded;
    }


    /** An installation failure never publishes a runnable gate receipt. */
    @Test
    public void borrowedInstallationFailurePreventsReadiness() throws IOException {
        Path scratch = java.nio.file.Files.createTempDirectory("borrowed-ready-");
        try {
            requireException(IOException.class, () -> LinuxProcessTree.publishBorrowedReady(scratch,
                () -> { throw new IOException("controlled failed descriptor install"); }));
            requireThat(java.nio.file.Files.exists(scratch.resolve("ready")), "readyAfterFailure").isFalse();
        } finally { java.nio.file.Files.delete(scratch); }
    }

    /** The same gate publication boundary installs descriptors before making readiness observable. */
    @Test
    public void borrowedInstallationCompletesBeforeReadiness() throws IOException {
        Path scratch = java.nio.file.Files.createTempDirectory("borrowed-ready-");
        try {
            LinuxProcessTree.publishBorrowedReady(scratch, () ->
                requireThat(java.nio.file.Files.exists(scratch.resolve("ready")), "readyDuringInstallation").isFalse());
            requireThat(java.nio.file.Files.readString(scratch.resolve("ready")), "readyPid").
                isEqualTo(Long.toString(ProcessHandle.current().pid()), "expected");
        } finally {
            java.nio.file.Files.deleteIfExists(scratch.resolve("ready"));
            java.nio.file.Files.delete(scratch);
        }
    }
}
