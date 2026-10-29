package com.holdmyspot.codexunleashed.scheduler;

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
import java.util.concurrent.TimeUnit;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies standard-file validation, XML handoff and actual JDK stream redirection.
 * These tests do not acquire cgroups or prove supervisor containment and gate release.
 */
public final class LinuxStandardFilesTest
{
    /** Supervisor launch property names for the three workload endpoints. */
    private static final List<String> STREAM_KEYS = List.of("standard.input", "standard.output", "standard.error");

    /** Creates standard-stream contract tests. */
    public LinuxStandardFilesTest() { }

    /**
     * Transfers binary stdin to stdout while keeping stderr separate and truncating both outputs.
     * @throws Exception if fixture launch, stream I/O, waiting or cleanup fails
     */
    @Test
    public void redirectsBinaryInputAndSeparateOutputs() throws Exception
    {
        Path scratch = Files.createTempDirectory("linux-standard-files-");
        try
        {
            byte[] payload = new byte[256];
            for (int index = 0; index < payload.length; index++) payload[index] = (byte) index;
            Path input = scratch.resolve("input");
            Path output = scratch.resolve("output");
            Path error = scratch.resolve("error");
            Path staleInput = scratch.resolve("stale-input");
            Files.write(input, payload);
            Files.write(output, new byte[700]);
            Files.write(error, new byte[700]);
            Files.writeString(staleInput, "wrong input");
            var files = new LinuxProcessTree.StandardFiles(input, output, error);
            Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
            Path fixtures = Path.of(StreamChild.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            ProcessBuilder builder = new ProcessBuilder(java.toString(), "-cp", fixtures.toString(),
                StreamChild.class.getName());
            builder.redirectInput(staleInput.toFile());
            builder.redirectOutput(scratch.resolve("stale-output").toFile());
            builder.redirectError(scratch.resolve("stale-error").toFile());
            builder.redirectErrorStream(true);

            files.redirect(builder);
            assertEquals(Files.size(output), 700L, "Configuring redirects must not open their endpoints");
            assertEquals(Files.size(error), 700L, "Configuring redirects must not truncate their endpoints");
            Process child = builder.start();
            try
            {
                child.getOutputStream().close();
                assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Stream fixture must finish");
                assertEquals(child.exitValue(), 7);
                assertEquals(Files.readAllBytes(error), new byte[] {0, 13, 10, (byte) 255, 42});
                assertEquals(Files.readAllBytes(output), payload);
                assertEquals(Files.readAllBytes(input), payload, "The input endpoint remains caller-owned");
            }
            finally
            {
                if (child.isAlive())
                {
                    child.destroyForcibly();
                    assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Stream fixture must be reaped");
                }
            }
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
     * @return valid endpoints whose construction does not require filesystem inspection
     */
    @DataProvider
    public Object[][] endpointPaths()
    {
        Path parent = unresolvedEndpoint();
        return new Object[][] {
            {new LinuxProcessTree.StandardFiles(parent.resolve("in &<>\t\nΩ"), parent.resolve("output"), parent.resolve("error"))},
            {new LinuxProcessTree.StandardFiles(parent, parent, parent)}
        };
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
        assertEquals(values.stringPropertyNames(), Set.copyOf(STREAM_KEYS));

        var restored = LinuxProcessTree.StandardFiles.readFrom(xmlRoundTrip(values));
        assertEquals(restored.input(), files.input());
        assertEquals(restored.output(), files.output());
        assertEquals(restored.error(), files.error());
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
        assertNull(LinuxProcessTree.StandardFiles.readFrom(xmlRoundTrip(values)));
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
        expectThrows(IOException.class, () -> LinuxProcessTree.StandardFiles.readFrom(decoded));
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
        expectThrows(failure, () -> new LinuxProcessTree.StandardFiles(paths[0], paths[1], paths[2]));
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

    /** Java child that echoes binary stdin, writes distinct stderr bytes and exits seven. */
    public static final class StreamChild
    {
        /** Prevents construction of the fixture entry point. */
        private StreamChild() { }

        /**
         * Transfers actual native standard streams for the redirection test.
         * @param args unused fixture arguments
         * @throws IOException if standard-stream transfer fails
         */
        public static void main(String[] args) throws IOException
        {
            System.in.transferTo(System.out);
            System.out.flush();
            System.err.write(new byte[] {0, 13, 10, (byte) 255, 42});
            System.err.flush();
            System.exit(7);
        }
    }
}
