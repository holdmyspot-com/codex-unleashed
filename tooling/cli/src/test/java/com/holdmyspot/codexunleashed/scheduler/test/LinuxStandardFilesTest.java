package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxProcessTree;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies standard-file validation, XML handoff and stream redirect configuration.
 * These tests do not acquire cgroups or prove supervisor containment and gate release.
 */
public final class LinuxStandardFilesTest
{
    /** Supervisor launch property names for the three workload endpoints. */
    private static final List<String> STREAM_KEYS = List.of("standard.input", "standard.output", "standard.error");

    /** Creates standard-stream contract tests. */
    public LinuxStandardFilesTest() { }

    /**
     * Points each standard stream at its own endpoint file, replacing earlier redirects and merged error output,
     * without opening or truncating any endpoint.
     * <p>
     * What a configured redirect does when the process starts is the JDK's documented behavior, so the test checks
     * the configuration rather than launching a process.
     * @throws IOException if fixture files cannot be written or removed
     */
    @Test
    public void redirectsEachStreamToItsOwnFile() throws IOException
    {
        Path scratch = Files.createTempDirectory("linux-standard-files-");
        try
        {
            Path input = scratch.resolve("input");
            Path output = Files.write(scratch.resolve("output"), new byte[700]);
            Path error = Files.write(scratch.resolve("error"), new byte[700]);
            var files = new LinuxProcessTree.StandardFiles(input, output, error);
            ProcessBuilder builder = new ProcessBuilder("unused");
            builder.redirectInput(scratch.resolve("stale-input").toFile());
            builder.redirectOutput(scratch.resolve("stale-output").toFile());
            builder.redirectError(scratch.resolve("stale-error").toFile());
            builder.redirectErrorStream(true);

            files.redirect(builder);
            requireThat(builder.redirectInput(), "redirectInput").isEqualTo(ProcessBuilder.Redirect.from(input.toFile()));
            requireThat(builder.redirectOutput(), "redirectOutput").isEqualTo(ProcessBuilder.Redirect.to(output.toFile()));
            requireThat(builder.redirectError(), "redirectError").isEqualTo(ProcessBuilder.Redirect.to(error.toFile()));
            requireThat(builder.redirectErrorStream(), "redirectErrorStream").isFalse();
            requireThat(Files.exists(input), "inputOpened").isFalse();
            requireThat(Files.size(output), "Files.size(output)").isEqualTo(700L);
            requireThat(Files.size(error), "Files.size(error)").isEqualTo(700L);
        }
        finally
        {
            try (var paths = Files.walk(scratch))
            {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    /**
     * Supplies unresolved endpoints with XML-sensitive characters and an aliased endpoint.
     * Unix filesystems also exercise angle brackets, tabs and newlines, which Windows paths reject.
     * @return endpoints valid on the host whose construction does not require filesystem inspection
     */
    @DataProvider
    public Object[][] endpointPaths()
    {
        Path parent = unresolvedEndpoint();
        var cases = new ArrayList<Object[]>();
        cases.add(new Object[] {new LinuxProcessTree.StandardFiles(parent.resolve("in &' Ω"),
            parent.resolve("output"), parent.resolve("error"))});
        cases.add(new Object[] {new LinuxProcessTree.StandardFiles(parent, parent, parent)});
        if (parent.getFileSystem().getSeparator().equals("/"))
            cases.add(new Object[] {new LinuxProcessTree.StandardFiles(parent.resolve("in &<>\t\nΩ"),
                parent.resolve("output"), parent.resolve("error"))});
        return cases.toArray(Object[][]::new);
    }

    /**
     * Retains each exact path through the production property mapping and the JDK XML parser.
     * @param files unresolved or aliased caller-owned endpoints
     * @throws IOException if the XML handoff or endpoint reconstruction fails
     */
    @Test(dataProvider = "endpointPaths")
    public void roundTripsExactPaths(LinuxProcessTree.StandardFiles files) throws IOException
    {
        Properties values = new Properties();
        files.writeTo(values);
        requireThat(values.stringPropertyNames(), "values.stringPropertyNames()").isEqualTo(Set.copyOf(STREAM_KEYS));

        var restored = LinuxProcessTree.StandardFiles.readFrom(xmlRoundTrip(values));
        requireThat(restored.input(), "restored.input()").isEqualTo(files.input());
        requireThat(restored.output(), "restored.output()").isEqualTo(files.output());
        requireThat(restored.error(), "restored.error()").isEqualTo(files.error());
    }

    /**
     * Retains legacy mode only when all three endpoint properties are absent.
     * @throws IOException if the XML handoff fails
     */
    @Test
    public void retainsLegacyModeWhenEndpointsAreAbsent() throws IOException
    {
        Properties values = new Properties();
        values.setProperty("uid", "1001");
        requireThat(LinuxProcessTree.StandardFiles.readFrom(xmlRoundTrip(values)), "LinuxProcessTree").isNull();
    }

    /**
     * Supplies every partial endpoint set and each empty or relative endpoint position.
     * @return malformed supervisor endpoint properties
     */
    @DataProvider
    public Object[][] invalidEndpointProperties()
    {
        var cases = new ArrayList<Object[]>();
        String endpoint = unresolvedEndpoint().toString();
        for (int mask = 1; mask < 7; mask++)
        {
            Properties values = new Properties();
            for (int index = 0; index < STREAM_KEYS.size(); index++)
                if ((mask & (1 << index)) != 0) values.setProperty(STREAM_KEYS.get(index), endpoint);
            cases.add(new Object[] {values});
        }
        for (String key : STREAM_KEYS)
            for (String invalid : List.of("", "relative-endpoint"))
            {
                Properties values = new Properties();
                for (String name : STREAM_KEYS) values.setProperty(name, endpoint);
                values.setProperty(key, invalid);
                cases.add(new Object[] {values});
            }
        return cases.toArray(Object[][]::new);
    }

    /**
     * Rejects malformed endpoint properties after the JDK XML parser preserves their values.
     * @param values partial, empty or relative endpoint properties
     * @throws IOException if the XML handoff fails
     */
    @Test(dataProvider = "invalidEndpointProperties")
    public void rejectsInvalidEndpointProperties(Properties values) throws IOException
    {
        Properties decoded = xmlRoundTrip(values);
        requireException(IOException.class, () -> LinuxProcessTree.StandardFiles.readFrom(decoded));
    }

    /**
     * Supplies null, relative and empty paths independently for each endpoint.
     * @return endpoint position and invalid path
     */
    @DataProvider
    public Object[][] invalidEndpointPaths()
    {
        var cases = new ArrayList<Object[]>();
        for (int index = 0; index < STREAM_KEYS.size(); index++)
            for (Path invalid : new Path[] {null, Path.of("relative-endpoint"), Path.of("")})
                cases.add(new Object[] {index, invalid});
        return cases.toArray(Object[][]::new);
    }

    /**
     * Validates path form without requiring the other endpoints to exist.
     * @param position endpoint position to replace
     * @param invalid null, relative or empty endpoint
     */
    @Test(dataProvider = "invalidEndpointPaths")
    public void rejectsInvalidEndpointPaths(int position, Path invalid)
    {
        Path endpoint = unresolvedEndpoint();
        Path[] paths = {endpoint, endpoint, endpoint};
        paths[position] = invalid;
        Class<? extends RuntimeException> failure = invalid == null ? NullPointerException.class : IllegalArgumentException.class;
        requireException(failure, () -> new LinuxProcessTree.StandardFiles(paths[0], paths[1], paths[2]));
    }

    /**
     * Creates an absolute unresolved endpoint without acquiring filesystem state.
     * @return an endpoint whose parents need not exist
     */
    private Path unresolvedEndpoint()
    {
        return Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("unresolved-standard-file-parent/endpoint");
    }

    /**
     * Uses the same XML Properties encoding and parser as supervisor launch handoff.
     * @param values launch properties to encode
     * @return decoded properties
     * @throws IOException if XML encoding or parsing fails
     */
    private Properties xmlRoundTrip(Properties values) throws IOException
    {
        var output = new ByteArrayOutputStream();
        values.storeToXML(output, "Dedicated supervisor handoff", StandardCharsets.UTF_8);
        Properties decoded = new Properties();
        decoded.loadFromXML(new ByteArrayInputStream(output.toByteArray()));
        return decoded;
    }
}
