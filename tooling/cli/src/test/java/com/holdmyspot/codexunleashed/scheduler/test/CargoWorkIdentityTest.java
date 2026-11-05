package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.CargoCompilerRequest;
import com.holdmyspot.codexunleashed.scheduler.CargoWorkIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks the stable per-crate work key and the measured input size.
 */
public final class CargoWorkIdentityTest
{
    /**
     * Creates independent work-identity checks.
     */
    public CargoWorkIdentityTest() {}

    /**
     * The key survives source edits, new paths, new hashes and new environments, and changes with the crate, its type,
     * target, profile or features.
     * @throws IOException if fixture files cannot be used or cleaned
     */
    @Test
    public void keyFollowsCrateAndProfileOnly() throws IOException
    {
        Path root = Files.createTempDirectory("cargo-work-key-");
        try
        {
            Path source = Files.createDirectories(root.resolve("first/src"));
            Path input = Files.writeString(source.resolve("lib.rs"), "pub fn f() {}\n");
            CargoWorkIdentity identities = new CargoWorkIdentity();
            List<String> options = List.of("--crate-name", "codex_core", "--edition=2021", "src/lib.rs",
                "--crate-type", "lib", "-C", "opt-level=3", "-C", "metadata=aaaa", "-C", "extra-filename=-aaaa",
                "--cfg", "feature=\"default\"", "--target", "x86_64-unknown-linux-musl",
                "--extern", "serde=" + root.resolve("first/libserde-aaaa.rmeta"), "-L", "dependency=" + root);
            String original = identities.identify(request(root.resolve("first"), root.resolve("one/bin/rustc").toString(), options,
                Map.of("LANG", "C")));

            Files.writeString(input, "pub fn f() { println!(\"changed\"); }\n");
            List<String> moved = new ArrayList<>(options);
            moved.replaceAll(option -> option.replace("aaaa", "bbbb"));
            requireThat(identities.identify(request(root.resolve("second"), root.resolve("two/bin/rustc").toString(), moved,
                Map.of("LANG", "C.UTF-8"))), "sameCrateAfterChanges").isEqualTo(original);

            for (List<String> change : List.of(List.of("codex_core", "codex_cli"), List.of("lib", "rlib"),
                List.of("opt-level=3", "opt-level=0"), List.of("feature=\"default\"", "feature=\"extra\""),
                List.of("x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl")))
            {
                List<String> changed = new ArrayList<>(options);
                changed.replaceAll(option -> option.equals(change.getFirst()) ? change.getLast() : option);
                requireThat(identities.identify(request(root.resolve("first"), root.resolve("one/bin/rustc").toString(), changed,
                    Map.of("LANG", "C"))), "changed:" + change.getLast()).isNotEqualTo(original);
            }
            List<String> test = new ArrayList<>(options);
            test.add("--test");
            requireThat(identities.identify(request(root.resolve("first"), root.resolve("one/bin/rustc").toString(), test, Map.of("LANG", "C"))),
                "testHarness").isNotEqualTo(original);
        }
        finally { remove(root); }
    }

    /**
     * Input size counts the crate's Rust sources below its root file's directory, excluding nested packages and other
     * files, plus every external crate file and the generated Rust sources in {@code OUT_DIR}.
     * @throws IOException if fixture files cannot be used or cleaned
     */
    @Test
    public void measuresSourcesExternalCratesAndGeneratedSources() throws IOException
    {
        Path root = Files.createTempDirectory("cargo-work-size-");
        try
        {
            Path crate = Files.createDirectories(root.resolve("crate"));
            Files.writeString(crate.resolve("lib.rs"), "a".repeat(10));
            Files.writeString(Files.createDirectory(crate.resolve("module")).resolve("part.rs"), "b".repeat(20));
            Files.writeString(crate.resolve("notes.md"), "c".repeat(40));
            Path nested = Files.createDirectory(crate.resolve("nested"));
            Files.writeString(nested.resolve("Cargo.toml"), "[package]\n");
            Files.writeString(nested.resolve("other.rs"), "d".repeat(80));
            Path external = Files.writeString(root.resolve("libdep.rmeta"), "e".repeat(160));
            Path generated = Files.createDirectory(root.resolve("out"));
            Files.writeString(generated.resolve("bindings.rs"), "f".repeat(320));
            Files.writeString(generated.resolve("object.o"), "g".repeat(640));
            List<String> options = List.of("--crate-name", "fixture", "crate/lib.rs", "--extern",
                "dep=" + external, "--extern", "std", "--extern", "missing=" + root.resolve("absent.rmeta"));

            CargoWorkIdentity identities = new CargoWorkIdentity();
            requireThat(identities.inputBytes(request(root, root.resolve("bin/rustc").toString(), options, Map.of("OUT_DIR", generated.toString()))),
                "inputBytes").isEqualTo(10L + 20 + 160 + 320);
            requireThat(identities.inputBytes(request(root, root.resolve("bin/rustc").toString(), List.of("-", "--crate-name", "___"), Map.of())),
                "standardInputHasNoFiles").isEqualTo(0L);
        }
        finally { remove(root); }
    }

    /**
     * Creates an authenticated-data model with a fresh action identity.
     * @param directory compiler working directory
     * @param compiler compiler executable path
     * @param options compiler arguments after the executable
     * @param environment compiler environment
     * @return immutable compiler request model
     */
    private static CargoCompilerRequest request(Path directory, String compiler, List<String> options,
        Map<String, String> environment)
    {
        List<String> command = new ArrayList<>(List.of(compiler));
        command.addAll(options);
        return new CargoCompilerRequest(UUID.randomUUID(), new CargoCompilerRequest.Wrapper(71, 81, 61, 91),
            command, directory, environment, null);
    }

    /**
     * Removes only one test's owned fixture root.
     * @param root owned fixture root
     * @throws IOException if cleanup fails
     */
    private static void remove(Path root) throws IOException
    {
        try (Stream<Path> paths = Files.walk(root))
        {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
