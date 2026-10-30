package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.Main;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Verifies explicit scheduling selection without launching a native adapter. */
public final class SchedulerTest
{
    /** Creates command-boundary tests. */
    public SchedulerTest() {}

    /** A well-formed scheduled request reports unavailable integration, rather than unknown-command usage. */
    @Test
    public void explicitSelectionReportsUnavailableAdapter()
    {
        var errors = new ByteArrayOutputStream();
        int result = Main.run(new String[]{"schedule", "--tool", "cargo", "--workspace", ".",
            "--scratch", ".", "--frida-library", "frida.so", "--headroom-bytes", "0",
            "--max-jobs", "2", "--control-timeout-ms", "1000", "--", "build"},
            new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream()),
            new PrintStream(errors));
        requireThat(result, "status").isEqualTo(1);
        requireThat(errors.toString(), "diagnostic").contains("No accepted cargo scheduler adapter");
    }

    /** Tool arguments are copied unchanged after the separator; zero remains a known reserve. */
    @Test
    public void preservesToolArgumentsAndKnownZero()
    {
        String[] arguments = valid();
        var settings = Scheduler.parse(arguments);
        requireThat(settings.arguments(), "arguments").isEqualTo(List.of("build", "--jobs=7", "a b", ""));
        requireThat(settings.budget().headroomBytes(), "headroomBytes").isEqualTo(0L);
        arguments[arguments.length - 1] = "changed";
        requireThat(settings.arguments().getLast(), "copiedArgument").isEqualTo("");
    }

    /** Missing, duplicate, overflowing and unapproved estimate-format options fail before integration selection. */
    @Test
    public void rejectsInvalidConfiguration()
    {
        for (String option : List.of("--max-jobs", "--control-timeout-ms", "--headroom-bytes"))
        {
            var invalid = valid();
            int index = Arrays.asList(invalid).indexOf(option);
            invalid[index + 1] = "-1";
            requireException(IllegalArgumentException.class, () -> Scheduler.parse(invalid));
        }
        var overflow = valid();
        overflow[Arrays.asList(overflow).indexOf("--control-timeout-ms") + 1] = Long.toString(Long.MAX_VALUE);
        requireException(IllegalArgumentException.class, () -> Scheduler.parse(overflow));
        var duplicate = new ArrayList<>(List.of(valid()));
        duplicate.addFirst("cargo");
        duplicate.addFirst("--tool");
        requireException(IllegalArgumentException.class, () -> Scheduler.parse(duplicate.toArray(String[]::new)));
        var estimates = valid();
        estimates[0] = "--estimates";
        requireException(IllegalArgumentException.class, () -> Scheduler.parse(estimates));
        requireException(IllegalArgumentException.class, () -> Scheduler.parse(new String[0]));
    }

    /** Existing unscheduled command selection retains its status and does not select a native scheduler. */
    @Test
    public void preservesDefaultDispatch()
    {
        var errors = new ByteArrayOutputStream();
        int result = Main.run(new String[0], new ByteArrayInputStream(new byte[0]),
            new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        requireThat(result, "status").isEqualTo(2);
        requireThat(errors.toString().contains("No accepted"), "schedulerSelected").isFalse();
        errors.reset();
        result = Scheduler.run(new String[0], new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        requireThat(result, "invalidStatus").isEqualTo(2);
    }

    /** @return a complete invocation containing quoted and empty tool arguments */
    private static String[] valid()
    {
        return new String[]{"--tool", "bazel", "--workspace", ".", "--scratch", ".", "--frida-library",
            "frida.so", "--headroom-bytes", "0", "--max-jobs", "2", "--control-timeout-ms", "1000",
            "--", "build", "--jobs=7", "a b", ""};
    }
}
