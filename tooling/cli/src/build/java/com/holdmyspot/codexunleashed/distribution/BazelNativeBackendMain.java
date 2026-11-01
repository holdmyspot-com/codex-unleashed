package com.holdmyspot.codexunleashed.distribution;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.scheduler.BazelNativeBackend.NativeTarget;
import static com.holdmyspot.codexunleashed.scheduler.BazelNativeBackend.EXECUTION_COMMIT;
import static com.holdmyspot.codexunleashed.scheduler.BazelNativeBackend.STORAGE_COMMIT;
import static com.holdmyspot.codexunleashed.scheduler.BazelNativeBackend.GO_VERSION;
import static com.holdmyspot.codexunleashed.scheduler.BazelNativeBackend.STORAGE_BAZEL_VERSION;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the pinned private Buildbarn backend on a matching native Linux, macOS or Windows worker.
 * Invoke this driver with the final tooling/dependency classpath so the maintained C resource and scoped mapper
 * come from the exact packaged source being accepted. Ordinary Maven builds never invoke this driver.
 */
public final class BazelNativeBackendMain
{
    /**
     * Maintained action-owner source consumed from the final application artifact.
     */
    private static final String OWNER_RESOURCE = "/com/holdmyspot/codexunleashed/scheduler/linux-bazel-action-owner.c";

    /**
     * Maintained Java launcher binding consumed from the final application artifact.
     */
    private static final String LAUNCHER_RESOURCE = "/com/holdmyspot/codexunleashed/scheduler/linux-java-launcher.c";

    /**
     * Prevents construction of the build entry point.
     */
    private BazelNativeBackendMain() {}

    /**
     * Creates owned source/tool/cache roots, builds exact source revisions and stages hashed native artifacts.
     * Successful intermediate outputs remain in the owned build root for incremental repair. The driver rejects
     * pre-existing staging roots unless explicit resume is selected. Resume preserves completed command receipts and
     * never installs binaries globally or edits an existing upstream checkout.
     * @param args target triple, owned build root, target-specific staging root, and optional {@code --resume}
     * @throws IOException if acquisition, a build or identity validation fails
     * @throws InterruptedException if an original child command is interrupted after being reaped
     * @throws IllegalArgumentException if arguments or root relationships are invalid
     * @throws UnsupportedOperationException if the host or target is unsupported
     */
    public static void main(String[] args) throws IOException, InterruptedException
    {
        boolean resume = args.length == 4 && args[3].equals("--resume");
        if (args.length != 3 && !resume)
            throw new IllegalArgumentException("Expected target triple, owned build root, staging root and optional --resume");
        NativeTarget target = NativeTarget.forHost(System.getProperty("os.name"), System.getProperty("os.arch"));
        if (!args[0].equals(target.triple()))
            throw new UnsupportedOperationException("Native Bazel backend build target must match this host: " + target.triple());
        Path build = Path.of(args[1]).toAbsolutePath().normalize();
        Path staging = Path.of(args[2]).toAbsolutePath().normalize();
        if (build.startsWith(staging) || staging.startsWith(build))
            throw new IllegalArgumentException("Build and staging roots must be disjoint");
        if (resume)
        {
            if (!Files.isRegularFile(build.resolve("source-before.json"), LinkOption.NOFOLLOW_LINKS) ||
                !Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Resume requires the original owned source receipt and staging directory");
        }
        else
        {
            Files.createDirectory(build);
            Files.createDirectory(staging);
        }
        for (String name : List.of("tmp", "go-cache", "go-modules", "go-path", "toolchain", "commands", "licenses"))
            Files.createDirectories(build.resolve(name));
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("TMPDIR", build.resolve("tmp").toString());
        environment.put("TMP", build.resolve("tmp").toString());
        environment.put("TEMP", build.resolve("tmp").toString());
        environment.put("GOTMPDIR", build.resolve("tmp").toString());
        environment.put("GOCACHE", build.resolve("go-cache").toString());
        environment.put("GOMODCACHE", build.resolve("go-modules").toString());
        environment.put("GOPATH", build.resolve("go-path").toString());
        environment.put("GOTOOLCHAIN", "local");
        environment.put("CGO_ENABLED", "0");
        environment.put("GOOS", target.goOperatingSystem());
        environment.put("GOARCH", target.goArchitecture());
        try (DefaultToolingScope scope = new DefaultToolingScope())
        {
            JsonMapper mapper = scope.getJsonMapper();
            Path go = acquireGo(mapper, build, environment, target);
            Path execution = checkout(mapper, build, environment, "bb-remote-execution", EXECUTION_COMMIT);
            Path storage = checkout(mapper, build, environment, "bb-storage", STORAGE_COMMIT);
            Map<String, Object> sourceBefore = sourceIdentity(execution, storage);
            Path originalSource = build.resolve("source-before.json");
            if (resume)
            {
                JsonNode previous = mapper.readTree(Files.readString(originalSource));
                for (Map.Entry<String, Object> entry : sourceBefore.entrySet())
                    if (previous.has(entry.getKey()) && !previous.path(entry.getKey()).asString().equals(entry.getValue()))
                        throw new IOException("Original dependency input changed before resume: " + entry.getKey());
                Files.writeString(build.resolve("source-resume-before.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sourceBefore));
            }
            else Files.writeString(originalSource, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sourceBefore));
            Path storageBazel = acquireStorageBazel(build, storage, target);
            Map<String, Object> executables = new LinkedHashMap<>();
            for (String name : List.of("bb_worker", "bb_scheduler", "bb_storage"))
            {
                Path source = name.equals("bb_storage") ? storage : execution;
                String filename = name + target.suffix();
                Path binary = staging.resolve(filename);
                // Darwin filesystem code uses unix.O_SEARCH, supplied by the upstream MODULE.bazel x/sys patch.
                // Direct Go compilation omits that patch and fails with an undefined constant; native Bazel
                // applies the pinned upstream graph. Linux and Windows retain their accepted Go builds.
                boolean useBazel = name.equals("bb_storage") || target.goOperatingSystem().equals("darwin");
                if (useBazel)
                {
                    if (!Files.readString(source.resolve(".bazelversion")).strip().equals(STORAGE_BAZEL_VERSION))
                        throw new IOException("Pinned native source Bazel declaration differs: " + name);
                    run(mapper, build, source, environment, "build-" + name + "-bazel",
                        List.of(storageBazel.toString(), "--batch", "--output_user_root=" + build.resolve("bazel-user"),
                            "build", "--lockfile_mode=error", "--noshow_progress", "--jobs=2", "//cmd/" + name + ":" + name));
                    copyProduct(source.resolve("bazel-bin/cmd/" + name + "/" + name + "_/" + filename), binary);
                }
                else run(mapper, build, source, environment, "build-" + name,
                    List.of(go.toString(), "build", "-mod=readonly", "-trimpath", "-o", binary.toString(), "./cmd/" + name));
                executables.put(name, Map.of("path", filename, "sha256", sha256(binary)));
                run(mapper, build, source, environment, "identity-" + name, List.of(go.toString(), "version", "-m", binary.toString()));
            }
            Map<String, Object> linuxMetadata = new LinkedHashMap<>();
            if (target.isLinux())
            {
                Path ownerSource = build.resolve("linux-bazel-action-owner.c");
                try (InputStream input = BazelNativeBackendMain.class.getResourceAsStream(OWNER_RESOURCE))
                {
                    if (input == null) throw new IOException("Final application artifact lacks maintained action-owner source");
                    byte[] bytes = input.readAllBytes();
                    if (Files.isRegularFile(ownerSource, LinkOption.NOFOLLOW_LINKS) &&
                        !java.util.Arrays.equals(Files.readAllBytes(ownerSource), bytes))
                        throw new IOException("Resume action-owner source differs from its completed native build inputs");
                    Files.write(ownerSource, bytes);
                }
                run(mapper, build, build, environment, "compiler-identity", List.of("musl-gcc", "--version"));
                run(mapper, build, build, environment, "libc-identity", List.of("musl-gcc", "-print-file-name=libc.a"));
                Path helper = staging.resolve("linux-bazel-action-owner");
                run(mapper, build, build, environment, "build-action-owner", List.of("musl-gcc", "-std=c11", "-O2", "-Wall",
                    "-Wextra", "-Werror", "-static", "-idirafter", "/usr/include", "-idirafter", target.systemHeaders().toString(),
                    "-MD", "-MF", build.resolve("action-owner-headers.d").toString(), ownerSource.toString(), "-o", helper.toString()));
                executables.put("linux-bazel-action-owner", Map.of("path", "linux-bazel-action-owner", "sha256", sha256(helper)));
                Path waiter = staging.resolve("linux-bazel-action-waiter");
                run(mapper, build, build, environment, "waiter-compiler-identity", List.of("gcc", "--version"));
                run(mapper, build, build, environment, "build-action-waiter", List.of("gcc", "-std=c11", "-O2", "-Wall",
                    "-Wextra", "-Werror", "-MD", "-MF", build.resolve("action-waiter-headers.d").toString(),
                    ownerSource.toString(), "-o", waiter.toString()));
                executables.put("linux-bazel-action-waiter", Map.of("path", "linux-bazel-action-waiter", "sha256", sha256(waiter)));
                Path launcherSource = build.resolve("linux-java-launcher.c");
                try (InputStream input = BazelNativeBackendMain.class.getResourceAsStream(LAUNCHER_RESOURCE))
                {
                    if (input == null) throw new IOException("Final application artifact lacks maintained Java launcher source");
                    byte[] bytes = input.readAllBytes();
                    if (Files.isRegularFile(launcherSource, LinkOption.NOFOLLOW_LINKS) &&
                        !java.util.Arrays.equals(Files.readAllBytes(launcherSource), bytes))
                        throw new IOException("Resume Java launcher source differs from its completed native build inputs");
                    Files.write(launcherSource, bytes);
                }
                for (String name : List.of("codex-tooling", "codex-cargo-wrapper-linux"))
                {
                    Path launcher = staging.resolve(name);
                    String wrapper = name.equals("codex-cargo-wrapper-linux") ? "1" : "0";
                    run(mapper, build, build, environment, "build-" + name, List.of("gcc", "-std=c11", "-O2",
                        "-Wall", "-Wextra", "-Werror", "-pedantic", "-DCARGO_WRAPPER=" + wrapper,
                        "-MD", "-MF", build.resolve(name + "-headers.d").toString(),
                        launcherSource.toString(), "-o", launcher.toString()));
                    Files.setPosixFilePermissions(launcher, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
                    executables.put(name, Map.of("path", name, "sha256", sha256(launcher)));
                }
                for (String executable : executables.keySet())
                    run(mapper, build, build, environment, "elf-" + executable,
                        List.of("readelf", "-h", "-l", "-d", staging.resolve(executable).toString()));
                linuxMetadata.put("helperSourceSha256", sha256(ownerSource));
                Map<String, String> headerInputs = new LinkedHashMap<>();
                String dependencies = Files.readString(build.resolve("action-owner-headers.d")).replace("\\\n", " ");
                for (String dependency : dependencies.substring(dependencies.indexOf(':') + 1).strip().split("\\s+"))
                {
                    Path header = Path.of(dependency).toRealPath();
                    headerInputs.put(header.toString(), sha256(header));
                }
                linuxMetadata.put("actionOwnerHeaderInputs", headerInputs);
                Map<String, String> waiterHeaders = new LinkedHashMap<>();
                String waiterDependencies = Files.readString(build.resolve("action-waiter-headers.d")).replace("\\\n", " ");
                for (String dependency : waiterDependencies.substring(waiterDependencies.indexOf(':') + 1).strip().split("\\s+"))
                {
                    Path header = Path.of(dependency).toRealPath();
                    waiterHeaders.put(header.toString(), sha256(header));
                }
                linuxMetadata.put("actionWaiterHeaderInputs", waiterHeaders);
                linuxMetadata.put("launcherSourceSha256", sha256(launcherSource));
                Map<String, String> launcherHeaders = new LinkedHashMap<>();
                for (String name : List.of("codex-tooling", "codex-cargo-wrapper-linux"))
                {
                    String launcherDependencies = Files.readString(build.resolve(name + "-headers.d")).replace("\\\n", " ");
                    for (String dependency : launcherDependencies.substring(launcherDependencies.indexOf(':') + 1).strip().split("\\s+"))
                    {
                        Path header = Path.of(dependency).toRealPath();
                        launcherHeaders.put(header.toString(), sha256(header));
                    }
                }
                linuxMetadata.put("launcherHeaderInputs", launcherHeaders);
                linuxMetadata.put("parkedEnvironment", "empty; original action environment is installed only by authenticated manager execve");
            }
            for (Path source : List.of(execution, storage))
            {
                run(mapper, build, source, environment, source.getFileName() + "-modules",
                    List.of(go.toString(), "list", "-mod=readonly", "-m", "-json", "all"));
                run(mapper, build, source, environment, source.getFileName() + "-guard", List.of("git", "diff", "--exit-code", "--", "."));
                Path license = source.resolve("LICENSE");
                copyProduct(license, build.resolve("licenses").resolve(source.getFileName() + "-LICENSE"));
            }
            Map<String, String> dependencyLicenses = new LinkedHashMap<>();
            try (java.util.stream.Stream<Path> paths = Files.walk(build.resolve("go-modules")))
            {
                for (Path path : paths.filter(value -> Files.isRegularFile(value, LinkOption.NOFOLLOW_LINKS)).sorted().toList())
                {
                    String name = path.getFileName().toString().toUpperCase(java.util.Locale.ROOT);
                    if (!name.startsWith("LICENSE") && !name.startsWith("NOTICE") && !name.startsWith("COPYING")) continue;
                    String digest = sha256(path);
                    copyProduct(path, build.resolve("licenses").resolve(digest));
                    dependencyLicenses.put(build.resolve("go-modules").relativize(path).toString().replace('\\', '/'), digest);
                }
            }
            Files.writeString(build.resolve("licenses/dependency-index.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(dependencyLicenses));
            Map<String, Object> after = sourceIdentity(execution, storage);
            if (!after.equals(sourceBefore)) throw new IOException("Pinned source dependency identities changed during native compilation");
            Files.writeString(build.resolve("source-after.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(after));
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schema", 1);
            manifest.put("target", target.triple());
            manifest.put("executionCommit", EXECUTION_COMMIT);
            manifest.put("storageCommit", STORAGE_COMMIT);
            manifest.put("goVersion", GO_VERSION);
            manifest.put("storageBazelVersion", STORAGE_BAZEL_VERSION);
            manifest.put("storageBazelSha256", sha256(storageBazel));
            manifest.put("storageBuildTarget", "//cmd/bb_storage:bb_storage");
            manifest.put("sourceInputHashes", after);
            manifest.put("goArchiveSha256", Files.readString(build.resolve("toolchain-sha256")));
            manifest.putAll(linuxMetadata);
            manifest.put("executables", executables);
            Files.createDirectories(staging.resolve("licenses"));
            try (java.util.stream.Stream<Path> licenses = Files.list(build.resolve("licenses")))
            {
                for (Path path : licenses.toList()) copyProduct(path, staging.resolve("licenses").resolve(path.getFileName()));
            }
            Files.writeString(staging.resolve("backend-manifest.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
        }
    }

    /**
     * Downloads the exact official native toolchain and verifies its vendor-published digest before extraction.
     * @param mapper borrowed scope mapper
     * @param build owned build directory
     * @param environment owned tool/cache settings
     * @param target actual supported native host target
     * @return pinned Go compiler executable
     * @throws IOException if metadata, digest or extraction fails
     * @throws InterruptedException if acquisition is interrupted
     */
    private static Path acquireGo(JsonMapper mapper, Path build, Map<String, String> environment, NativeTarget target)
        throws IOException, InterruptedException
    {
        boolean windows = target.goOperatingSystem().equals("windows");
        String archiveSuffix = windows ? ".zip" : ".tar.gz";
        String filename = GO_VERSION + "." + target.goOperatingSystem() + "-" + target.goArchitecture() + archiveSuffix;
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<String> metadata = client.send(HttpRequest.newBuilder(URI.create("https://go.dev/dl/?mode=json&include=all")).build(),
            HttpResponse.BodyHandlers.ofString());
        if (metadata.statusCode() != 200) throw new IOException("Official Go release metadata returned HTTP " + metadata.statusCode());
        String expected = null;
        for (JsonNode release : mapper.readTree(metadata.body()))
            if (GO_VERSION.equals(release.path("version").asString()))
                for (JsonNode file : release.path("files"))
                    if (filename.equals(file.path("filename").asString())) expected = file.path("sha256").asString();
        if (expected == null) throw new IOException("Official Go release metadata lacks " + filename);
        Path archive = build.resolve(filename);
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS) || !expected.equals(sha256(archive)))
        {
            HttpResponse<Path> download = client.send(HttpRequest.newBuilder(URI.create("https://go.dev/dl/" + filename)).build(),
                HttpResponse.BodyHandlers.ofFile(archive));
            if (download.statusCode() != 200 || !expected.equals(sha256(archive)))
                throw new IOException("Official Go archive HTTP status or SHA-256 differs: " + filename);
        }
        Files.writeString(build.resolve("toolchain-sha256"), expected);
        if (windows)
            extractZip(archive, build.resolve("toolchain"));
        else
        {
            run(mapper, build, build, environment, "go-archive-members", List.of("tar", "-tzf", archive.toString()));
            String members = Files.readString(build.resolve("commands/go-archive-members.log"));
            for (String member : members.lines().toList())
            {
                Path path = Path.of(member).normalize();
                if (path.isAbsolute() || !path.startsWith("go") || member.contains("../"))
                    throw new IOException("Official Go archive member escapes its owned extraction root: " + member);
            }
            run(mapper, build, build, environment, "extract-go", List.of("tar", "--no-same-owner", "-xzf", archive.toString(),
                "-C", build.resolve("toolchain").toString()));
        }
        Path go = build.resolve("toolchain/go/bin/go" + target.suffix());
        run(mapper, build, build, environment, "go-version", List.of(go.toString(), "version"));
        if (!Files.readString(build.resolve("commands/go-version.log")).strip().equals("go version " + GO_VERSION + " " + target.goOperatingSystem() + "/" + target.goArchitecture()))
            throw new IOException("Downloaded Go executable reports a different compiler identity");
        return go;
    }

    /**
     * Extracts the vendor-verified Windows Go ZIP inside its owned toolchain directory.
     * Existing regular files may be replaced during resume; symbolic links and escaping entries are rejected.
     * @param archive official ZIP whose digest has already been verified
     * @param directory owned extraction directory
     * @throws IOException if an entry escapes the Go directory, encounters a symbolic link, or cannot be extracted
     */
    private static void extractZip(Path archive, Path directory) throws IOException
    {
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive, LinkOption.NOFOLLOW_LINKS)))
        {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null)
            {
                String member = entry.getName();
                Path relative = Path.of(member).normalize();
                if (relative.isAbsolute() || !relative.startsWith("go") || member.contains("../") || member.contains("\\"))
                    throw new IOException("Official Go archive member escapes its owned extraction root: " + member);
                Path destination = directory.resolve(relative);
                Path current = directory;
                for (Path component : relative)
                {
                    current = current.resolve(component);
                    if (Files.isSymbolicLink(current))
                        throw new IOException("Go archive extraction encounters symbolic link: " + current);
                }
                if (entry.isDirectory()) Files.createDirectories(destination);
                else
                {
                    Files.createDirectories(destination.getParent());
                    Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                input.closeEntry();
            }
        }
    }

    /**
     * Acquires the storage project's declared Bazel release without changing its dependency graph.
     * Upstream's MODULE.bazel owns the generated ByteStream compatibility transformation; plain Go
     * module compilation omits it. The official binary is checked against its vendor release digest.
     * @param build invocation-owned tool root
     * @param storage pristine pinned storage checkout
     * @param target actual supported native host target
     * @return exact native Bazel executable
     * @throws IOException if the source/tool declaration or downloaded bytes differ
     * @throws InterruptedException if official acquisition is interrupted
     */
    private static Path acquireStorageBazel(Path build, Path storage, NativeTarget target) throws IOException, InterruptedException
    {
        if (!Files.readString(storage.resolve(".bazelversion")).strip().equals(STORAGE_BAZEL_VERSION))
            throw new IOException("Pinned storage Bazel declaration differs");
        String url = "https://releases.bazel.build/" + STORAGE_BAZEL_VERSION + "/release/bazel-" +
            STORAGE_BAZEL_VERSION + "-" + target.bazelOperatingSystem() + "-" + target.bazelArchitecture() + target.suffix();
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<String> metadata = client.send(HttpRequest.newBuilder(URI.create(url + ".sha256")).build(),
            HttpResponse.BodyHandlers.ofString());
        String expected = metadata.body().strip().split("\\s+")[0];
        if (metadata.statusCode() != 200 || !expected.matches("[a-f0-9]{64}"))
            throw new IOException("Official storage Bazel digest metadata differs");
        Path binary = build.resolve("bazel-" + STORAGE_BAZEL_VERSION + target.suffix());
        if (!Files.isRegularFile(binary, LinkOption.NOFOLLOW_LINKS) || !sha256(binary).equals(expected))
        {
            HttpResponse<Path> download = client.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofFile(binary));
            if (download.statusCode() != 200 || !sha256(binary).equals(expected))
                throw new IOException("Official storage Bazel executable differs");
        }
        if (!target.goOperatingSystem().equals("windows") && !binary.toFile().setExecutable(true, true)) throw new IOException("Cannot mark storage Bazel executable");
        Files.writeString(build.resolve("storage-bazel-sha256"), expected);
        return binary;
    }

    /**
     * Copies a native product or license while retaining an identical completed destination.
     * Windows preserves read-only metadata on module licenses and Bazel products, so replacing an
     * identical destination can fail even though its determining bytes are already complete.
     * @param source authenticated completed input
     * @param destination invocation-owned output or content-addressed license
     * @throws IOException if copying fails or an existing destination is not a direct regular file
     */
    private static void copyProduct(Path source, Path destination) throws IOException
    {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
        {
            if (Files.isSymbolicLink(destination) || !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Native product destination is not a direct regular file: " + destination);
            if (sha256(source).equals(sha256(destination))) return;
        }
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Retrieves an immutable public upstream revision into a new owned checkout.
     * @param mapper borrowed scope mapper
     * @param build owned build root
     * @param environment owned cache settings
     * @param repository public Buildbarn repository name
     * @param commit exact pinned commit or pinned unambiguous module revision
     * @return newly checked-out source directory
     * @throws IOException if Git cannot retrieve or verify the source
     * @throws InterruptedException if a Git command is interrupted
     */
    private static Path checkout(JsonMapper mapper, Path build, Map<String, String> environment,
        String repository, String commit) throws IOException, InterruptedException
    {
        Path source = build.resolve(repository);
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS))
        {
            run(mapper, build, build, environment, repository + "-clone", List.of("git", "clone", "--no-checkout",
                "--filter=blob:none", "https://github.com/buildbarn/" + repository + ".git", source.toString()));
            run(mapper, build, source, environment, repository + "-checkout", List.of("git", "checkout", "--detach", commit));
        }
        else if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Pinned checkout is not an owned directory: " + source);
        run(mapper, build, source, environment, repository + "-commit", List.of("git", "rev-parse", "HEAD"));
        String actual = Files.readString(build.resolve("commands").resolve(repository + "-commit.log")).strip();
        if (!actual.startsWith(commit)) throw new IOException("Retrieved Buildbarn source commit differs: " + repository);
        run(mapper, build, source, environment, repository + "-source-guard", List.of("git", "diff", "--exit-code", "--", "."));
        return source;
    }

    /**
     * Hashes the Go dependency and compiler declarations guarded against build-time mutation.
     * @param execution pinned executor source
     * @param storage pinned storage source
     * @return exact determining source-file identities
     * @throws IOException if a source file cannot be read
     */
    private static Map<String, Object> sourceIdentity(Path execution, Path storage) throws IOException
    {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Path source : List.of(execution, storage))
            for (String name : List.of("go.mod", "go.sum", "MODULE.bazel", "MODULE.bazel.lock", ".bazelversion", ".bazelrc"))
                if (Files.isRegularFile(source.resolve(name), LinkOption.NOFOLLOW_LINKS))
                    result.put(source.getFileName() + "/" + name, sha256(source.resolve(name)));
        for (Path source : List.of(execution, storage))
            try (java.util.stream.Stream<Path> paths = Files.walk(source.resolve("patches")))
            {
                for (Path patch : paths.filter(value -> Files.isRegularFile(value, LinkOption.NOFOLLOW_LINKS)).sorted().toList())
                    result.put(source.getFileName() + "/" + source.relativize(patch).toString().replace('\\', '/'), sha256(patch));
            }
        return result;
    }

    /**
     * Runs and reaps one original tool command with retained exact argv and its terminal receipt.
     * @param mapper borrowed scope mapper
     * @param build invocation-owned command receipt root
     * @param cwd exact source/build working directory
     * @param environment owned cache/tool settings layered on the worker preparation
     * @param name unique command receipt name
     * @param arguments exact command argument elements
     * @throws IOException if launch or the original command fails
     * @throws InterruptedException if interrupted after the original command has been terminated and reaped
     */
    private static void run(JsonMapper mapper, Path build, Path cwd, Map<String, String> environment,
        String name, List<String> arguments) throws IOException, InterruptedException
    {
        Path log = build.resolve("commands").resolve(name + ".log");
        Path receipt = build.resolve("commands").resolve(name + ".json");
        String workingDirectory = build.relativize(cwd).toString().replace('\\', '/');
        if (Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS))
        {
            JsonNode previous = mapper.readTree(Files.readString(receipt));
            boolean reusable = !name.endsWith("-commit") && !name.endsWith("-guard");
            if (reusable && previous.path("exit").asInt(-1) == 0 &&
                previous.path("argv").equals(mapper.valueToTree(arguments)) &&
                previous.path("workingDirectoryFromBuildRoot").asString().equals(workingDirectory)) return;
            int attempt = 1;
            while (Files.exists(build.resolve("commands").resolve(name + ".attempt-" + attempt + ".json"))) ++attempt;
            Files.move(receipt, build.resolve("commands").resolve(name + ".attempt-" + attempt + ".json"));
            if (Files.exists(log)) Files.move(log, build.resolve("commands").resolve(name + ".attempt-" + attempt + ".log"));
        }
        ProcessBuilder builder = new ProcessBuilder(arguments).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        int result;
        boolean interrupted = false;
        while (true)
        {
            try { result = process.waitFor(); break; }
            catch (InterruptedException failure) { interrupted = true; process.destroy(); }
        }
        Files.writeString(build.resolve("commands").resolve(name + ".json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
            Map.of("argv", arguments, "workingDirectoryFromBuildRoot", build.relativize(cwd).toString().replace('\\', '/'),
                "exit", result, "logFromBuildRoot", "commands/" + name + ".log")));
        if (interrupted) throw new InterruptedException("Native build command interrupted after original process reaping: " + name);
        if (result != 0) throw new IOException("Native build command " + name + " failed with status " + result + "; inspect " + log);
    }

    /**
     * Computes the platform SHA-256 of exact file bytes.
     * @param path file to hash
     * @return lowercase digest
     * @throws IOException if reading fails
     */
    private static String sha256(Path path) throws IOException
    {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException failure) { throw new AssertionError("JDK lacks SHA-256", failure); }
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS))
        {
            byte[] bytes = new byte[65536];
            int length;
            while ((length = input.read(bytes)) >= 0) digest.update(bytes, 0, length);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
