package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.node.ObjectNode;

/** Transfers build-step output groups through the authenticated inherited ORAS environment and GNU tar; {@link CargoCacheKeys} names them. */
public final class CargoCacheParts
{
    /** Current multipart artifact format. */
    public static final String ARTIFACT = "application/vnd.codex-unleashed.cargo-target.v2";
    /** Compressed tar layer format. */
    private static final String LAYER = "application/vnd.codex-unleashed.cargo-target.tar+zstd";
    /** Durable target-relative ownership record. */
    public static final String OWNERS = ".codex-cache-groups.json";
    /** Target-relative index of earlier checkpoints' file digests, which is never collected. */
    public static final String DIGESTS = CargoOutputDigests.INDEX;
    /** Target-relative directory of archived part entry lists, which is never collected. */
    public static final String PART_ENTRIES = CargoOutputDigests.PART_ENTRIES;
    /** Ownership map field. */
    private static final String PATHS = "target_relative_owners";
    /** Layer annotation identifying the saving step. */
    private static final String GROUP = "io.holdmyspot.cargo.group";
    /** Layer annotation identifying exact content and metadata entries. */
    private static final String INPUTS = "io.holdmyspot.cargo.inputs";
    /** Supported release steps and imported/final/metadata ownership. */
    private static final Set<String> STEPS = Set.of("bootstrap", "bwrap", "codex-responses-api-proxy", "codex-code-mode-host",
        "codex", "codex-app-server", "codex-windows-sandbox-setup", "codex-windows-sandbox-service", "codex-command-runner", "final", "metadata");
    /** Prevents construction. */
    private CargoCacheParts() {}

    /**
     * Validates the original helper interface and transfers groups without evaluating command strings in a shell.
     * @param scope borrowed shared JSON values
     * @param arguments operation, repository, reference, previous reference, target, temporary and optional reuse/step pairs
     * @param environment actual inherited environment, including authenticated ORAS launcher configuration
     * @param out borrowed progress output
     * @param err borrowed diagnostics
     * @return original subprocess status, three for missing, four for unsupported format, two for invalid CLI, zero on success
     */
    public static int run(ToolingScope scope, String[] arguments, Map<String, String> environment, PrintStream out, PrintStream err)
    {
        List<String> launcher = new ArrayList<>();
        try
        {
            JsonNode command;
            try { command = scope.getJsonMapper().readTree(environment.getOrDefault("CARGO_CACHE_ORAS_COMMAND", "[\"oras\"]")); }
            catch (tools.jackson.core.JacksonException invalid) { throw new IllegalArgumentException("Invalid ORAS command JSON", invalid); }
            if (!command.isArray() || command.isEmpty()) throw new IllegalArgumentException("CARGO_CACHE_ORAS_COMMAND must be a nonempty JSON array of strings");
            for (JsonNode part : command)
            {
                if (!part.isString()) throw new IllegalArgumentException("ORAS command parts must be strings");
                launcher.add(part.asString());
            }
        }
        catch (IllegalArgumentException failure) { err.println(failure.getMessage()); return 2; }
        return run(scope, arguments, environment, oras(launcher, environment), out, err);
    }

    /**
     * Transfers groups through the given ORAS, ignoring {@code CARGO_CACHE_ORAS_COMMAND}.
     * @param scope borrowed shared JSON values
     * @param arguments operation, repository, reference, previous reference, target, temporary and optional reuse/step pairs
     * @param environment inherited environment for tar and the saving-step default
     * @param oras executes one ORAS command
     * @param out borrowed progress output
     * @param err borrowed diagnostics
     * @return zero on success, three for a missing reference, four for an unsupported format, two for invalid arguments,
     *   or the failed command's status
     */
    public static int run(ToolingScope scope, String[] arguments, Map<String, String> environment, Oras oras, PrintStream out, PrintStream err)
    {
        if (arguments.length < 6 || (!arguments[0].equals("push") && !arguments[0].equals("pull")))
        { err.println("Expected push|pull repository reference previous-reference target temporary [--step STEP] [--reuse-reference REF]"); return 2; }
        try
        {
            String step = environment.getOrDefault("CARGO_CACHE_STAGE", "bootstrap");
            List<String> reuse = new ArrayList<>();
            for (int i = 6; i < arguments.length; i += 2)
            {
                if (i + 1 == arguments.length) throw new IllegalArgumentException("Missing cache option value");
                if (arguments[i].equals("--step")) step = arguments[i + 1];
                else if (arguments[i].equals("--reuse-reference")) reuse.add(arguments[i + 1]);
                else throw new IllegalArgumentException("Unknown cache option: " + arguments[i]);
            }
            if (!STEPS.contains(step)) throw new IllegalArgumentException("Unsupported saving step: " + step);
            Path target = Path.of(arguments[4]).toAbsolutePath().normalize();
            Path temporary = Path.of(arguments[5]).toAbsolutePath().normalize();
            String tar = executable(environment, "gtar", "tar");
            if (arguments[0].equals("push"))
            {
                push(scope, oras, tar, arguments[1], arguments[2], arguments[3], target, temporary, step, reuse, environment, out, err);
                return 0;
            }
            return pull(scope, oras, tar, arguments[1], arguments[2], target, temporary, environment, out, err);
        }
        catch (CommandFailure failure) { if (!failure.diagnostic.isEmpty()) err.print(failure.diagnostic); return failure.status; }
        catch (IllegalArgumentException failure) { err.println(failure.getMessage()); return 2; }
        catch (IOException failure) { err.println(failure.getMessage()); return 1; }
        catch (RuntimeException failure) { err.println(failure.getMessage()); return 1; }
    }

    /**
     * Serializes Python-compatible ASCII JSON, retaining exact signature bytes and ownership-record spacing.
     * @param scope borrowed configured ASCII mapper
     * @param value JSON-compatible document
     * @param spaced true for default comma/colon spaces, false for compact group signatures
     * @param sorted true for recursively sorted map keys
     * @return JSON with no trailing newline
     */
    static String json(ToolingScope scope, Object value, boolean spaced, boolean sorted)
    {
        var writer = scope.getArtifactJsonMapper().writer();
        writer = writer.without(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        if (sorted) value = sorted(value);
        if (spaced)
        {
            Separators separators = Separators.createDefaultInstance().withObjectNameValueSpacing(Separators.Spacing.AFTER).
                withObjectEntrySpacing(Separators.Spacing.AFTER).withArrayElementSpacing(Separators.Spacing.AFTER).
                withObjectEmptySeparator("").withArrayEmptySeparator("");
            writer = writer.with(new DefaultPrettyPrinter(separators).
                withObjectIndenter(DefaultPrettyPrinter.NopIndenter.instance()).
                withArrayIndenter(DefaultPrettyPrinter.NopIndenter.instance()));
        }
        return writer.writeValueAsString(value);
    }

    /**
     * Sorts mapping keys by Unicode code points, matching Python even for supplementary filenames.
     * @param value JSON-compatible maps, lists and scalars
     * @return recursively ordered maps retaining the original scalar values
     */
    private static Object sorted(Object value)
    {
        if (value instanceof Map<?, ?> map)
        {
            Map<String, Object> ordered = new TreeMap<>(CargoCacheParts::compareNames);
            for (var entry : map.entrySet()) ordered.put((String) entry.getKey(), sorted(entry.getValue()));
            return ordered;
        }
        if (value instanceof List<?> list) return list.stream().map(CargoCacheParts::sorted).toList();
        return value;
    }

    /**
     * Compares path names by code points rather than UTF-16 surrogate ordering.
     * @param left first exact filename
     * @param right second exact filename
     * @return lexical Unicode code-point order
     */
    private static int compareNames(String left, String right)
    {
        int a = 0; int b = 0;
        while (a < left.length() && b < right.length())
        {
            int x = left.codePointAt(a); int y = right.codePointAt(b);
            if (x != y) return Integer.compare(x, y);
            a += Character.charCount(x); b += Character.charCount(y);
        }
        return Integer.compare(left.length() - a, right.length() - b);
    }

    /**
     * Describes all owned paths using the original level-by-level sorted traversal and exact metadata.
     * @param scope borrowed serialization and timestamp services
     * @param target idle existing output directory
     * @param step saving step for previously unseen files
     * @return sorted groups of sorted content/metadata entries
     * @throws IOException if stored owners, path types or filesystem operations are invalid
     */
    public static Map<String, List<List<Object>>> collect(ToolingScope scope, Path target, String step) throws IOException
    {
        Path ownership = target.resolve(OWNERS);
        Map<String, String> previous = new HashMap<>();
        if (Files.exists(ownership))
        {
            JsonNode stored = scope.getJsonMapper().readTree(Files.readString(ownership)).get(PATHS);
            if (stored == null || !stored.isObject()) throw new IOException("Invalid stored ownership map");
            for (var entry : stored.properties())
            {
                if (!entry.getValue().isString() || !STEPS.contains(entry.getValue().asString()))
                    throw new IOException("Invalid stored cache owner: " + entry.getKey());
                previous.put(entry.getKey(), entry.getValue().asString());
            }
        }
        CargoOutputDigests digests = new CargoOutputDigests(scope, target);
        Path index = target.resolve(DIGESTS);
        Path lists = target.resolve(PART_ENTRIES);
        List<Path> traversal = new ArrayList<>(); walk(target, traversal);
        List<Callable<Observation>> observations = new ArrayList<>(traversal.size());
        for (Path path : traversal)
        {
            if (path.equals(ownership) || path.equals(index) || path.startsWith(lists)) continue;
            observations.add(() ->
            {
                BasicFileAttributes status = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                return new Observation(path, status, describe(scope, target, path, status, digests));
            });
        }
        // Hashing every output dominates a checkpoint, so read files on every processor; results keep traversal order.
        List<Observation> observed = completeAll(observations, Runtime.getRuntime().availableProcessors(), "cache output inspection");
        Map<String, List<Object>> entries = new TreeMap<>(CargoCacheParts::compareNames);
        Map<String, String> owners = new TreeMap<>(CargoCacheParts::compareNames);
        Map<Object, List<String>> hardlinks = new LinkedHashMap<>();
        digests.write(scope);
        for (Observation observation : observed)
        {
            Path path = observation.path(); BasicFileAttributes status = observation.status();
            List<Object> entry = observation.entry(); String relative = (String) entry.getFirst();
            boolean metadata = status.isDirectory() || path.getFileName().toString().endsWith(".d");
            for (Path component : target.relativize(path))
                metadata |= Set.of(".codex-source-inputs", "cargo-timings", ".rustc_info.json").contains(component.toString());
            entries.put(relative, entry); owners.put(relative, metadata ? "metadata" : previous.getOrDefault(relative, step));
            if (status.isRegularFile() && status.fileKey() != null)
                hardlinks.computeIfAbsent(status.fileKey(), ignored -> new ArrayList<>()).add(relative);
        }
        for (List<String> paths : hardlinks.values())
        {
            String owner = paths.stream().filter(previous::containsKey).map(previous::get).findFirst().orElse(owners.get(paths.getFirst()));
            for (String path : paths) owners.put(path, owner);
        }
        byte[] record = json(scope, Map.of(PATHS, owners), true, true).getBytes(StandardCharsets.UTF_8);
        if (!Files.exists(ownership) || !java.util.Arrays.equals(Files.readAllBytes(ownership), record))
        {
            try (OwnedDirectory scratch = new OwnedDirectory(target, ".cache-owners-"))
            {
                Path pending = scratch.path.resolve("owners.json"); Files.write(pending, record);
                Files.move(pending, ownership, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        }
        entries.put(OWNERS, describe(scope, target, ownership, Files.readAttributes(ownership, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS), null));
        owners.put(OWNERS, "metadata");
        Map<String, List<List<Object>>> groups = new TreeMap<>();
        for (var entry : entries.entrySet()) groups.computeIfAbsent(owners.get(entry.getKey()), ignored -> new ArrayList<>()).add(entry.getValue());
        return groups;
    }

    /**
     * Visits one directory's sorted entries before its sorted real subdirectories, never following directory symlinks.
     * @param directory current owned directory
     * @param result original traversal order
     * @throws IOException if enumeration fails
     */
    private static void walk(Path directory, List<Path> result) throws IOException
    {
        List<Path> children;
        try (var stream = Files.list(directory)) { children = stream.sorted((left, right) -> compareNames(left.getFileName().toString(), right.getFileName().toString())).toList(); }
        result.addAll(children);
        for (Path child : children) if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) walk(child, result);
    }

    /**
     * Reads lstat-like identity without following symlinks and rejects special files before tar can see them.
     * @param scope borrowed exact timestamp services
     * @param target owning root
     * @param path owned child
     * @param attributes no-follow filesystem observation
     * @param digests checkpoint digest index that may reuse an unchanged file's digest, or null to hash every file
     * @return relative path, original mode, exact epoch nanoseconds, size and digest/link/directory content
     * @throws IOException if a special file or unavailable exact metadata is encountered
     */
    private static List<Object> describe(ToolingScope scope, Path target, Path path, BasicFileAttributes attributes,
        CargoOutputDigests digests) throws IOException
    {
        String relative = target.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
        Instant modified = SourceInputTimes.lastModified(scope, path, LinkOption.NOFOLLOW_LINKS);
        Object content;
        if (attributes.isRegularFile())
            content = digests == null ? CargoOutputDigests.hash(path) : digests.digest(path, relative, attributes, modified);
        else if (attributes.isSymbolicLink())
        {
            // Record link targets with "/" so an entry has one identity on every platform.
            content = Files.readSymbolicLink(path).toString().replace(path.getFileSystem().getSeparator(), "/");
        }
        else if (attributes.isDirectory()) content = "directory";
        else throw new IOException("Unsupported cache output type: " + relative);
        int mode;
        if (path.getFileSystem().supportedFileAttributeViews().contains("unix"))
            mode = ((Number) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue();
        else
        {
            DosFileAttributes dos = Files.readAttributes(path, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            int permissions = dos.isReadOnly() ? 0444 : 0666;
            String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (attributes.isDirectory() || name.matches(".*\\.(exe|bat|cmd|com)$")) permissions |= 0111;
            mode = (attributes.isSymbolicLink() ? 0120000 : attributes.isDirectory() ? 0040000 : 0100000) | permissions;
        }
        return List.of(relative, mode, SourceInputTimes.nanos(modified), attributes.isRegularFile() ? attributes.size() : 0L, content);
    }

    /**
     * Uploads missing groups concurrently and publishes aliases only after every required blob succeeds.
     * @param scope borrowed JSON services
     * @param command executes one ORAS command
     * @param tar resolved GNU tar executable
     * @param repository blob repository
     * @param reference publication aliases
     * @param previous original previous reference
     * @param target idle existing output tree
     * @param temporary owned scratch parent
     * @param step current saving step
     * @param reuse additional compatible references
     * @param environment inherited authenticated environment
     * @param out progress output
     * @param err subprocess diagnostics
     * @throws IOException if any collection, upload or publication fails
     */
    private static void push(ToolingScope scope, Oras command, String tar, String repository, String reference,
        String previous, Path target, Path temporary, String step, List<String> reuse, Map<String, String> environment,
        PrintStream out, PrintStream err) throws IOException
    {
        if (!Files.isDirectory(target)) throw new IOException("Cargo output directory does not exist: " + target);
        Map<String, JsonNode> reusable = new HashMap<>();
        var candidates = new java.util.LinkedHashSet<String>();
        for (String prior : java.util.stream.Stream.concat(java.util.stream.Stream.of(previous), reuse.stream()).toList())
        {
            String verified = prior.endsWith("-partial") ? prior.substring(0, prior.length() - 8) : prior;
            candidates.add(verified + "-partial"); candidates.add(verified);
        }
        for (String candidate : candidates)
        {
            Manifest fetched = fetch(scope, command, candidate);
            if (fetched != null && ARTIFACT.equals(fetched.content.path("artifactType").asString()))
                for (JsonNode layer : fetched.content.path("layers"))
                    reusable.put(layer.path("annotations").path(GROUP).asString() + ":" + layer.path("annotations").path(INPUTS).asString(), layer);
        }
        Map<String, List<List<Object>>> groups = collect(scope, target, step);
        Path lists = target.resolve(PART_ENTRIES);
        List<Callable<Uploaded>> tasks = new ArrayList<>();
        for (var group : groups.entrySet()) tasks.add(() ->
        {
            byte[] entries = json(scope, group.getValue(), false, false).getBytes(StandardCharsets.UTF_8);
            String signature = HexFormat.of().formatHex(CargoCacheKeys.digest().digest(entries));
            JsonNode retained = reusable.get(group.getKey() + ":" + signature);
            if (retained != null) return new Uploaded(retained, 0);
            try (OwnedDirectory scratch = new OwnedDirectory(temporary, "cargo-part-"))
            {
                // Archive the part's entry list, digests included, so a restore can index the files without hashing.
                String list = PART_ENTRIES + "/" + group.getKey() + ".json";
                Files.write(target.resolve(list), entries);
                Path paths = scratch.path.resolve("paths"); StringBuilder names = new StringBuilder();
                for (List<Object> entry : group.getValue()) names.append(entry.getFirst()).append('\0');
                names.append(list).append('\0');
                Files.writeString(paths, names, StandardCharsets.UTF_8);
                Path archive = scratch.path.resolve("part.tar.zst");
                checked(List.of(tar, "--format=pax", "--zstd", "--no-recursion", "--null", "-T", paths.toString(), "-cf", "-"),
                    target, environment, null, archive, err);
                ObjectNode layer = (ObjectNode) scope.getJsonMapper().readTree(oras(command, err, "blob", "push", "--descriptor", "--media-type", LAYER, repository, archive.toString()));
                layer.set("annotations", scope.getJsonMapper().valueToTree(Map.of(GROUP, group.getKey(), INPUTS, signature,
                    "org.opencontainers.image.title", signature + ".tar.zst")));
                return new Uploaded(layer, layer.path("size").longValue());
            }
        });
        List<Uploaded> uploaded;
        Files.createDirectories(lists);
        try
        {
            uploaded = completeAll(tasks, 5, "cache upload");
        }
        finally
        {
            try (var paths = Files.walk(lists))
            {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        try (OwnedDirectory scratch = new OwnedDirectory(temporary, "cargo-manifest-"))
        {
            Path config = scratch.path.resolve("config.json"); Files.writeString(config, "{}");
            JsonNode descriptor = scope.getJsonMapper().readTree(oras(command, err, "blob", "push", "--descriptor", "--media-type", "application/vnd.oci.empty.v1+json", repository, config.toString()));
            Path manifest = scratch.path.resolve("manifest.json");
            var document = new LinkedHashMap<String, Object>(); document.put("schemaVersion", 2);
            document.put("mediaType", "application/vnd.oci.image.manifest.v1+json"); document.put("artifactType", ARTIFACT);
            document.put("config", descriptor); document.put("layers", uploaded.stream().map(Uploaded::layer).toList());
            Files.writeString(manifest, json(scope, document, true, false));
            out.print(oras(command, err, "manifest", "push", reference, manifest.toString()));
        }
        out.println("cache_parts=" + uploaded.size() + " uploaded_archive_bytes=" + uploaded.stream().mapToLong(Uploaded::bytes).sum() +
            " reused_parts=" + uploaded.stream().filter(value -> value.bytes == 0).count());
    }

    /**
     * Runs independent tasks on a bounded pool and waits for all of them.
     * @param <T> task result type
     * @param tasks tasks in result order
     * @param threads maximum concurrently running tasks
     * @param operation operation name for interruption and checked-failure diagnostics
     * @return each task's result, in the order of {@code tasks}
     * @throws IOException if the wait is interrupted or a task fails; a task's own {@link IOException} or
     *     {@link RuntimeException} propagates unchanged
     */
    private static <T> List<T> completeAll(List<Callable<T>> tasks, int threads, String operation) throws IOException
    {
        List<T> results = new ArrayList<>(tasks.size());
        try (var workers = Executors.newFixedThreadPool(threads))
        {
            for (var future : workers.invokeAll(tasks)) results.add(future.get());
        }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("Interrupted " + operation, failure); }
        catch (ExecutionException failure)
        {
            if (failure.getCause() instanceof IOException original) throw original;
            if (failure.getCause() instanceof RuntimeException original) throw original;
            throw new IOException("Failed " + operation, failure.getCause());
        }
        return results;
    }

    /**
     * Downloads and extracts layers in manifest order, preserving unrelated existing target files.
     * @param scope borrowed JSON services
     * @param command executes one ORAS command
     * @param tar resolved GNU tar
     * @param repository blob repository
     * @param reference selected artifact
     * @param target output tree
     * @param temporary owned scratch parent
     * @param environment inherited authentication
     * @param out borrowed progress
     * @param err borrowed diagnostics
     * @return zero for restored, three for missing, four for unsupported artifact format
     * @throws IOException if download, parse or extraction fails
     */
    private static int pull(ToolingScope scope, Oras command, String tar, String repository, String reference,
        Path target, Path temporary, Map<String, String> environment, PrintStream out, PrintStream err) throws IOException
    {
        Manifest manifest = fetch(scope, command, reference);
        if (manifest == null) return 3;
        if (!Set.of(ARTIFACT, "application/vnd.codex-unleashed.cargo-target.v1").contains(manifest.content.path("artifactType").asString())) return 4;
        Files.createDirectories(target);
        Map<String, String> signatures = new HashMap<>();
        for (JsonNode layer : manifest.content.path("layers"))
        {
            String group = layer.path("annotations").path(GROUP).asString();
            if (STEPS.contains(group)) signatures.put(group, layer.path("annotations").path(INPUTS).asString());
            try (OwnedDirectory scratch = new OwnedDirectory(temporary, "cargo-part-"))
            {
                Path archive = scratch.path.resolve("part.tar.zst");
                oras(command, err, "blob", "fetch", "--output", archive.toString(), repository + "@" + layer.path("digest").asString());
                checked(List.of(tar, "--zstd", "-xf", "-"), target, environment, archive, null, err);
            }
        }
        new CargoOutputDigests(scope, target).restore(scope, target, signatures);
        if (!Files.exists(target.resolve(OWNERS))) collect(scope, target, "bootstrap");
        out.println("cache_result=hit digest=" + manifest.digest + " cache_parts=" + manifest.content.path("layers").size());
        return 0;
    }

    /**
     * Fetches an authenticated manifest and distinguishes only the original supported missing-reference diagnostics.
     * @param scope borrowed JSON parser
     * @param command executes one ORAS command
     * @param reference registry reference
     * @return parsed manifest or null for a genuine missing reference
     * @throws IOException if ORAS fails otherwise or its response is invalid
     */
    private static Manifest fetch(ToolingScope scope, Oras command, String reference) throws IOException
    {
        Result result = command.run(List.of("manifest", "fetch", "--format", "json", reference));
        if (result.status != 0)
        {
            if (java.util.regex.Pattern.compile("not found|MANIFEST_UNKNOWN|NAME_UNKNOWN|404 Not Found").matcher(result.error).find()) return null;
            throw new CommandFailure(result.status, result.error);
        }
        JsonNode resolved = scope.getJsonMapper().readTree(result.output);
        JsonNode content = resolved.get("content"); JsonNode digest = resolved.get("digest");
        if (content == null || !content.isObject() || digest == null || !digest.isString()) throw new IOException("Invalid ORAS resolved manifest response");
        return new Manifest(content, digest.asString());
    }

    /**
     * Returns an ORAS that runs the launcher as a child process with the inherited environment.
     * @param launcher exact launcher argv prefix
     * @param environment inherited authenticated environment
     * @return process-backed ORAS
     */
    private static Oras oras(List<String> launcher, Map<String, String> environment)
    {
        List<String> prefix = List.copyOf(launcher);
        return arguments ->
        {
            List<String> argv = new ArrayList<>(prefix); argv.addAll(arguments);
            return execute(argv, null, environment, null, null);
        };
    }

    /**
     * Invokes ORAS and returns its stdout, failing with its original status.
     * @param oras executes one ORAS command
     * @param err borrowed diagnostic output
     * @param arguments literal ORAS arguments
     * @return original stdout text
     * @throws IOException if ORAS fails
     */
    private static String oras(Oras oras, PrintStream err, String... arguments) throws IOException
    {
        Result result = oras.run(List.of(arguments));
        if (result.status != 0) throw new CommandFailure(result.status, result.error);
        err.print(result.error); return result.output;
    }

    /**
     * Retains subprocess failures without swallowing or rewriting their status.
     * @param argv exact executable and arguments
     * @param directory optional actual working directory
     * @param environment authenticated inherited values
     * @param input optional archive input
     * @param output optional archive output
     * @param err borrowed diagnostics
     * @return stdout when not redirected
     * @throws IOException if subprocess acquisition or completion fails
     */
    private static String checked(List<String> argv, Path directory, Map<String, String> environment,
        Path input, Path output, PrintStream err) throws IOException
    {
        Result result = execute(argv, directory, environment, input, output);
        if (result.status != 0) throw new CommandFailure(result.status, result.error);
        err.print(result.error); return result.output;
    }

    /**
     * Owns one child process and drains both pipes concurrently to avoid diagnostic backpressure deadlocks.
     * @param argv literal process argv
     * @param directory optional working directory
     * @param environment inherited environment additions
     * @param input optional redirected input
     * @param output optional redirected output
     * @return actual status and complete stdout/stderr
     * @throws IOException if process creation, collection or pipe reading fails
     */
    private static Result execute(List<String> argv, Path directory, Map<String, String> environment, Path input, Path output) throws IOException
    {
        ProcessBuilder builder = new ProcessBuilder(argv); builder.environment().putAll(environment);
        if (directory != null) builder.directory(directory.toFile());
        if (input != null) builder.redirectInput(input.toFile());
        if (output != null) builder.redirectOutput(output.toFile());
        try (Process child = builder.start(); var drains = Executors.newFixedThreadPool(2))
        {
            var stdout = drains.submit(() -> child.getInputStream().readAllBytes());
            var stderr = drains.submit(() -> child.getErrorStream().readAllBytes());
            if (input == null) child.getOutputStream().close();
            int status = child.waitFor();
            return new Result(status, new String(stdout.get(), StandardCharsets.UTF_8), new String(stderr.get(), StandardCharsets.UTF_8));
        }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("Interrupted cache child process", failure); }
        catch (ExecutionException failure) { throw new IOException("Cannot read cache child process output", failure.getCause()); }
    }

    /**
     * Resolves gtar before tar using the actual process PATH, including executable suffixes on Windows.
     * @param environment inherited executable search path
     * @param names preferred executable names
     * @return resolved absolute executable, falling back to the final bare name for its original launch failure
     */
    private static String executable(Map<String, String> environment, String... names)
    {
        boolean windows = java.io.File.separatorChar == '\\';
        List<String> suffixes = windows ? List.of("", ".exe", ".cmd", ".bat") : List.of("");
        for (String name : names)
            for (String directory : environment.getOrDefault("PATH", "").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                for (String suffix : suffixes)
                {
                    Path candidate = Path.of(directory).resolve(name + suffix);
                    if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) return candidate.toAbsolutePath().toString();
                }
        return names[names.length - 1];
    }

    /**
     * Retains a resolved manifest.
     * @param content actual artifact document
     * @param digest original resolved digest
     */
    private record Manifest(JsonNode content, String digest) {}

    /**
     * One output path's no-follow attributes and recorded entry.
     * @param path observed output path
     * @param status attributes read without following symbolic links
     * @param entry relative path, mode, modification nanoseconds, size and content identity
     */
    private record Observation(Path path, BasicFileAttributes status, List<Object> entry) {}
    /**
     * Retains one uploaded or reused layer.
     * @param layer actual ORAS descriptor
     * @param bytes newly uploaded archive bytes, zero for reused
     */
    private record Uploaded(JsonNode layer, long bytes) {}
    /**
     * Retains one command's completion and pipe text.
     * @param status original exit status
     * @param output complete stdout
     * @param error complete stderr
     */
    public record Result(int status, String output, String error) {}

    /** Executes one ORAS command. */
    @FunctionalInterface
    public interface Oras
    {
        /**
         * Executes ORAS with the given arguments.
         * @param arguments literal ORAS arguments, without the executable
         * @return the command's status, stdout and stderr
         * @throws IOException if the command cannot run
         */
        Result run(List<String> arguments) throws IOException;
    }

    /** Preserves a failed child's original status and diagnostic bytes. */
    private static final class CommandFailure extends IOException
    {
        /** Serialization identifier; no native authority is serialized. */
        private static final long serialVersionUID = 1L;
        /** Original exit status. */
        private final int status;
        /** Original stderr text. */
        private final String diagnostic;
        /**
         * Retains original subprocess failure.
         * @param status original status
         * @param diagnostic original stderr
         */
        private CommandFailure(int status, String diagnostic) { super("Cache subprocess failed with status " + status); this.status = status; this.diagnostic = diagnostic; }
    }

    /** Owns only one exclusively created temporary child and its no-follow deletion. */
    private static final class OwnedDirectory implements AutoCloseable
    {
        /** Exact exclusively created path. */
        private final Path path;
        /**
         * Allocates the owner before subprocesses receive its paths.
         * @param parent existing owned temporary parent
         * @param prefix exclusive name prefix
         * @throws IOException if allocation fails
         */
        private OwnedDirectory(Path parent, String prefix) throws IOException { path = Files.createTempDirectory(parent, prefix); }
        /**
         * Removes only this owner's tree, including failure leftovers, without following symlinks.
         * @throws IOException if owned deletion fails
         */
        @Override public void close() throws IOException
        {
            try (var paths = Files.walk(path))
            {
                for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
            }
        }
    }
}
