package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.FailureReport;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Checks that the failure report writes only the recorded failure. */
public final class FailureReportTest
{
    /** Creates failure report checks. */
    public FailureReportTest() {}

    /** A failure event is written with its message and stack trace; other events write nothing. */
    @Test
    public void writesOnlyTheFailure()
    {
        List<String> lines = new ArrayList<>();
        var report = new FailureReport(lines::add);
        report.record("decision", "ignored");
        report.record("terminal", 0);
        report.record("failure", new IOException("tree B unavailable"));
        requireThat(lines.size(), "reports").isEqualTo(1);
        requireThat(lines.getFirst(), "report").startsWith("Scheduler failed; cleaning up before reporting the failure:");
        requireThat(lines.getFirst(), "report").contains("java.io.IOException: tree B unavailable");
        requireThat(lines.getFirst(), "report").contains("FailureReportTest.writesOnlyTheFailure");
    }
}
