package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.CargoCompilerRequest;
import com.holdmyspot.codexunleashed.scheduler.CargoWorkIdentity;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks work reuse and invalidation from actual owned input files.
 */
public final class CargoWorkIdentityTest
{
    /**
     * Creates independent work-identity checks.
     */
    public CargoWorkIdentityTest() {}

    /**
     * Retains opaque compiler options when the working directory rejects their path syntax, while hashing files.
     * The directory proxy models the observed Windows rejection of quoted cfg values during path resolution;
     * actual temporary files supply all content observations. This exercises identity handling, not Windows parsing.
     * @throws IOException if fixture files cannot be used or cleaned
     */
    @Test
    public void retainsOptionsRejectedAsPaths() throws IOException
    {
        Path root = Files.createTempDirectory("cargo-option-identity-");
        try
        {
            Path source = Files.createDirectory(root.resolve("source"));
            Path target = Files.createDirectory(source.resolve("target"));
            Path toolchain = Files.createDirectory(root.resolve("toolchain"));
            Path compiler = Files.writeString(toolchain.resolve("rustc"), "compiler");
            Files.writeString(source.resolve("main.rs"), "fn main() {}\n");
            Path external = Files.writeString(root.resolve("external.rmeta"), "external1");
            Path directory = (Path) Proxy.newProxyInstance(Path.class.getClassLoader(), new Class<?>[]{Path.class},
                (proxy, method, arguments) ->
                {
                    if (method.getName().equals("resolve") && arguments[0] instanceof String value &&
                        value.indexOf('"') >= 0)
                        throw new InvalidPathException(value, "Quoted option is not a Windows path");
                    return method.invoke(source, arguments);
                });
            CargoWorkIdentity identities = new CargoWorkIdentity(source, List.of(target), toolchain);
            CargoCompilerRequest original = request(source, compiler, "fifo:first",
                List.of("--cfg", "codex_fixture=\"quoted value\"", external.toString()));
            CargoCompilerRequest first = new CargoCompilerRequest(original.identity(), original.wrapper(),
                original.arguments(), directory, original.environment(), null);

            String identity = identities.identify(first);
            requireThat(identity, "sameOptionsReuseWork").isEqualTo(identities.identify(first));
            CargoCompilerRequest changed = request(source, compiler, "fifo:first",
                List.of("--cfg", "codex_fixture=\"different value\"", external.toString()));
            CargoCompilerRequest changedOptions = new CargoCompilerRequest(changed.identity(), changed.wrapper(),
                changed.arguments(), directory, changed.environment(), null);
            requireThat(identities.identify(changedOptions), "opaqueOptionChangesInvalidateWork")
                .isNotEqualTo(identity);
            Files.writeString(external, "external2");
            requireThat(identities.identify(first), "regularArgumentFilesStillInvalidateWork")
                .isNotEqualTo(identity);
        }
        finally
        {
            try (Stream<Path> paths = Files.walk(root))
            {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    /**
     * Reuses compiler work across fresh wrapper authority locations while retaining compiler environment changes.
     * The request models inputs already authenticated by native registration; this check exercises only work identity.
     * @throws IOException if fixture files cannot be used or cleaned
     */
    @Test
    public void reusesWorkAcrossWrapperAuthorityLocations() throws IOException
    {
        Path root = Files.createTempDirectory("cargo-wrapper-identity-");
        try
        {
            Path source = Files.createDirectory(root.resolve("source"));
            Path target = Files.createDirectory(source.resolve("target"));
            Path toolchain = Files.createDirectory(root.resolve("toolchain"));
            Path compiler = Files.writeString(toolchain.resolve("rustc"), "compiler");
            Files.writeString(source.resolve("main.rs"), "fn main() {}\n");
            CargoWorkIdentity identities = new CargoWorkIdentity(source, List.of(target), toolchain);
            CargoCompilerRequest baseline = request(source, compiler, "fifo:first", List.of());
            String original = identities.identify(baseline);
            var authorityValues = Map.of(
                "CODEX_LINUX_CARGO_WRAPPER_CONFIG", List.of(root.resolve("first/w.json").toString(),
                    root.resolve("second/w.json").toString()),
                "CODEX_CARGO_SOCKET", List.of(root.resolve("first/socket").toString(),
                    root.resolve("second/socket").toString()),
                "CODEX_CARGO_PIPE", List.of("\\\\.\\pipe\\first", "\\\\.\\pipe\\second"),
                "CODEX_CARGO_CONTROLLER_PID", List.of("71", "72"),
                "CODEX_CARGO_CONTROLLER_BIRTH", List.of("81", "82"));
            for (var authority : authorityValues.entrySet())
            {
                Map<String, String> environment = new java.util.HashMap<>(baseline.environment());
                environment.put(authority.getKey(), authority.getValue().getFirst());
                CargoCompilerRequest first = new CargoCompilerRequest(baseline.identity(), baseline.wrapper(),
                    baseline.arguments(), source, environment, null);

                requireThat(identities.identify(first), "authorityDoesNotChangeWork:" + authority.getKey())
                    .isEqualTo(original);
                environment.put(authority.getKey(), authority.getValue().getLast());
                CargoCompilerRequest second = new CargoCompilerRequest(baseline.identity(), baseline.wrapper(),
                    baseline.arguments(), source, environment, null);
                requireThat(identities.identify(second), "freshAuthorityReusesWork:" + authority.getKey())
                    .isEqualTo(original);
                requireThat(first.environment().get(authority.getKey()), "authenticationKeepsOriginalAuthority")
                    .isEqualTo(authority.getValue().getFirst());

                environment.put("LANG", "C.UTF-8");
                CargoCompilerRequest changedLocale = new CargoCompilerRequest(baseline.identity(), baseline.wrapper(),
                    baseline.arguments(), source, environment, null);
                requireThat(identities.identify(changedLocale), "compilerEnvironmentStillInvalidates")
                    .isNotEqualTo(original);
                environment.put("LANG", "C");
                environment.put("CODEX_COMPILER_INPUT", "changed");
                CargoCompilerRequest changedInput = new CargoCompilerRequest(baseline.identity(), baseline.wrapper(),
                    baseline.arguments(), source, environment, null);
                requireThat(identities.identify(changedInput), "unrecognizedCodexEnvironmentStillInvalidates")
                    .isNotEqualTo(original);
            }
        }
        finally
        {
            try (Stream<Path> paths = Files.walk(root))
            {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    /**
     * Different native wrapper identities and FIFO capability paths preserve equivalent compiler work.
     * Source, toolchain and options changes invalidate that work; uncaptured standard input stays inapplicable.
     * @throws IOException if fixture files cannot be used or cleaned
     */
    @Test
    public void distinguishesChangedInputsFromNewWrapper() throws IOException
    {
        Path root = Files.createTempDirectory("cargo-work-");
        try
        {
            Path source = Files.createDirectory(root.resolve("source"));
            Path target = Files.createDirectory(source.resolve("target"));
            Path toolchain = Files.createDirectory(root.resolve("toolchain"));
            Path compiler = Files.writeString(toolchain.resolve("rustc"), "compiler1");
            Path input = Files.writeString(source.resolve("main.rs"), "fn main() {}\n");
            CargoWorkIdentity identities = new CargoWorkIdentity(source, List.of(target), toolchain);
            CargoCompilerRequest first = request(source, compiler, "fifo:first", List.of());
            String original = identities.identify(first);
            requireThat(identities.identify(request(source, compiler, "fifo:second", List.of())), "newWrapperSameWork").isEqualTo(original);
            Files.writeString(target.resolve("binary"), "generated");
            requireThat(identities.identify(first), "generatedOutputDoesNotInvalidate").isEqualTo(original);
            Files.writeString(input, "fn main() { println!(\"changed\"); }\n");
            requireThat(identities.identify(first).equals(original), "changedSourceInvalidates").isFalse();
            Files.writeString(input, "fn main() {}\n");
            Files.writeString(compiler, "compiler2");
            requireThat(identities.identify(first).equals(original), "changedToolchainInvalidates").isFalse();
            Files.writeString(compiler, "compiler1");
            requireThat(identities.identify(request(source, compiler, "fifo:first", List.of("-C", "opt-level=2"))).equals(original), "changedOptionsInvalidate").isFalse();
            boolean standardInputRejected = false;
            try { identities.identify(request(source, compiler, "fifo:first", List.of("-"))); }
            catch (IOException expected) { standardInputRejected = true; }
            requireThat(standardInputRejected, "uncapturedStandardInputIsInapplicable").isTrue();
        }
        finally
        {
            try (Stream<Path> paths = Files.walk(root))
            {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    /**
     * Creates an authenticated-data model with deliberately fresh action and native identities.
     * @param source source working directory
     * @param compiler compiler executable
     * @param fifo invocation-specific jobserver capability
     * @param options compiler options
     * @return immutable compiler request model
     */
    private static CargoCompilerRequest request(Path source, Path compiler, String fifo, List<String> options)
    {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(List.of(compiler.toString(), "main.rs"));
        command.addAll(options);
        return new CargoCompilerRequest(UUID.randomUUID(), new CargoCompilerRequest.Wrapper(71, 81, 61, 91),
            command, source, Map.of("CARGO_MAKEFLAGS", fifo, "LANG", "C"), null);
    }
}
