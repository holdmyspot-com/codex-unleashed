package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Verifies semantic lockfile acceptance, typed refusal and source preservation through the public command. */
public final class VerifyCargoLockTest
{
    /** Actual source-less workspace and qualified dependent reference. */
    private static final String ORIGINAL = "version=4\n[[package]]\nname='workspace'\nversion='0.0.0'\n[[package]]\nname='external'\nversion='1.0.0'\nsource='registry+trusted'\ndependencies=['workspace 0.0.0']\n";
    /** Actual workspace metadata identity. */
    private static final String METADATA = "{\"workspace_members\":[\"path+workspace\"],\"packages\":[{\"id\":\"path+workspace\",\"name\":\"workspace\",\"version\":\"1.2.3\"}]}";
    /** Creates independent method fixtures. */ public VerifyCargoLockTest() {}

    /**
     * Accepts only workspace version updates and their exact version-qualified dependency references.
     * @throws IOException if fixture storage fails
     */
    @Test public void acceptsWorkspaceVersionSynchronization() throws IOException
    {
        try (Fixture f = new Fixture())
        {
            String observed = ORIGINAL.replace("0.0.0", "1.2.3");
            requireThat(f.run(ORIGINAL, observed, METADATA), "workspaceSynchronization").isEqualTo(0);
            requireThat(f.output.toString(), "exactReceipt").isEqualTo("{\"result\": \"verified\", \"workspace_version_updates\": {\"workspace\": {\"from\": \"0.0.0\", \"to\": \"1.2.3\"}}}\n");
            requireThat(Files.readString(f.original), "originalNeverWritten").isEqualTo(ORIGINAL);
        }
    }

    /**
     * Ignores only comments, legal quoting and map-key ordering after actual TOML parsing.
     * @throws IOException if fixture storage fails
     */
    @Test public void acceptsEquivalentTomlSpelling() throws IOException
    {
        try (Fixture f = new Fixture())
        {
            String original = "version=4\n[[package]]\nname='a'\nversion='1'\n";
            String equivalent = "# equivalent\nversion = 0x4\n[[package]]\nversion = \"1\"\nname = \"a\"\n";
            requireThat(f.run(original, equivalent, "{\"workspace_members\":[],\"packages\":[]}"), "equivalentParsedValues").isEqualTo(0);
        }
    }

    /**
     * Rejects external drift, incomplete metadata and malformed documents without success receipts.
     * @throws IOException if fixture storage fails
     */
    @Test public void refusesUnapprovedDriftAndMalformedInputs() throws IOException
    {
        try (Fixture f = new Fixture())
        {
            requireThat(f.run(ORIGINAL, ORIGINAL.replace("0.0.0", "1.2.3").replace("registry+trusted", "registry+other"), METADATA), "externalDrift").isEqualTo(1);
            requireThat(f.run(ORIGINAL, ORIGINAL, "{\"workspace_members\":[\"missing\"],\"packages\":[]}"), "incompleteWorkspace").isEqualTo(1);
            requireThat(f.run("version=[", ORIGINAL, METADATA), "malformedOriginal").isEqualTo(1);
            requireThat(f.run(ORIGINAL, "version=[", METADATA), "malformedCurrent").isEqualTo(1);
            requireThat(f.output.size(), "noSuccessReceipts").isEqualTo(0);
        }
    }

    /**
     * Retains integer/float, signed-zero and timestamp/string distinctions used by the former verifier.
     * @throws IOException if fixture storage fails
     */
    @Test public void preservesScalarTypeAndSignedZeroRefusals() throws IOException
    {
        try (Fixture f = new Fixture())
        {
            String base = "[[package]]\nname='a'\nversion='1'\n"; String metadata = "{\"workspace_members\":[],\"packages\":[]}";
            requireThat(f.run("number=4\n" + base, "number=4.0\n" + base, metadata), "integerVersusFloat").isEqualTo(1);
            requireThat(f.run("number=-0.0\n" + base, "number=0.0\n" + base, metadata), "negativeZero").isEqualTo(1);
            requireThat(f.run("date=1979-05-27\n" + base, "date=1979-05-27\n" + base, metadata), "temporalNotJson").isEqualTo(1);
            requireThat(f.run("number=nan\n" + base, "number=nan\n" + base, metadata), "nonfiniteNotJson").isEqualTo(1);
            requireThat(f.run("date='1979-05-27'\n" + base, "date='1979-05-27'\n" + base, metadata), "quotedDateIsText").isEqualTo(0);
        }
    }

    /** Owns immutable lockfile inputs for one method. */
    private static final class Fixture implements AutoCloseable
    {
        /** Owned root. */ private final Path root;
        /** Original input. */ private final Path original;
        /** Current input. */ private final Path current;
        /** Actual metadata input. */ private final Path metadata;
        /** Captured public receipt. */ private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        /** Borrowed output. */ private final PrintStream out = new PrintStream(output);
        /** Borrowed diagnostics. */ private final PrintStream err = new PrintStream(new ByteArrayOutputStream());
        /**
         * Allocates independent input paths.
         * @throws IOException if allocation fails
         */
        private Fixture() throws IOException
        {
            root=Files.createTempDirectory("lock-");original=root.resolve("original");current=root.resolve("current");metadata=root.resolve("metadata");
        }
        /**
         * Invokes the real public verifier with exact fixture bytes.
         * @param before original TOML
         * @param after observed TOML
         * @param workspace Cargo metadata JSON
         * @return public status
         * @throws IOException if input writes fail
         */
        private int run(String before, String after, String workspace) throws IOException
        {
            Files.writeString(original,before);Files.writeString(current,after);Files.writeString(metadata,workspace);
            return Main.run(new String[]{"verify-cargo-lock",original.toString(),current.toString(),metadata.toString()},new ByteArrayInputStream(new byte[0]),out,err);
        }
        /**
         * Deletes only method-owned paths.
         * @throws IOException if deletion fails
         */
        @Override public void close() throws IOException
        { out.close();err.close();Files.deleteIfExists(original);Files.deleteIfExists(current);Files.deleteIfExists(metadata);Files.delete(root); }
    }
}
