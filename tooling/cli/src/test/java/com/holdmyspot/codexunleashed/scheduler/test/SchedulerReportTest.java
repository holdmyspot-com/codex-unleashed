package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.Scheduler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Checks that scheduler failures report their root cause. */
public final class SchedulerReportTest
{
    /** Creates independent reporting checks. */
    public SchedulerReportTest() {}

    /** A wrapped failure prints every distinct cause after its own message. */
    @Test
    public void printsEveryCause()
    {
        var root = new IOException("pipe disconnected");
        var failure = new IllegalStateException("Cargo producer service failed", new IOException("Windows Cargo token delivery failed", root));
        var bytes = new ByteArrayOutputStream();
        try (var err = new PrintStream(bytes, true, StandardCharsets.UTF_8)) { Scheduler.report(failure, err); }
        String text = bytes.toString(StandardCharsets.UTF_8);
        requireThat(text, "report").contains("ERROR: Cargo producer service failed");
        requireThat(text, "report").contains("Caused by: Windows Cargo token delivery failed");
        requireThat(text, "report").contains("Caused by: pipe disconnected");
    }
    /** Context attached to a cause as a suppressed failure is printed with that cause. */
    @Test
    public void printsSuppressedContext()
    {
        var attach = new IOException("frida_device_attach_sync failed");
        attach.addSuppressed(new IOException("Held image transition failed: pid=7, reportedPath=/usr/bin/cc"));
        var failure = new IllegalStateException("Action poll failed", attach);
        var bytes = new ByteArrayOutputStream();
        try (var err = new PrintStream(bytes, true, StandardCharsets.UTF_8)) { Scheduler.report(failure, err); }
        String text = bytes.toString(StandardCharsets.UTF_8);
        requireThat(text, "report").contains("Caused by: frida_device_attach_sync failed");
        requireThat(text, "report").contains("Suppressed: Held image transition failed: pid=7, reportedPath=/usr/bin/cc");
    }
}
