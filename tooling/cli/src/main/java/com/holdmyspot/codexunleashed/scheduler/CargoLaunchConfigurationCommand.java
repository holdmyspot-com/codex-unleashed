package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.Sha256;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Constructs one bounded launch document from the caller's current stage environment and actual installed bytes.
 * This command does not grant administrator authority, start a workload, or change installed files.
 * Unix administrators publish the generated document into a protected namespace before scheduling consumes it.
 */
public final class CargoLaunchConfigurationCommand
{
    /** Common explicit inputs; generated outputs and history are outside the pinned immutable toolchain. */
    private static final Set<String> COMMON = Set.of("--platform", "--runtime", "--toolchain", "--frida-library",
        "--workspace", "--output-root", "--history", "--output");
    /** Unix workload credentials do not inherit supplementary administrator groups. */
    private static final Set<String> UNIX = Set.of("--uid", "--gid");
    /** Linux authority inputs already provisioned by the administrator. */
    private static final Set<String> LINUX = Set.of("--unshare", "--libcgroup", "--delegation-parent");
    /** Linux inputs that may be omitted: without a build hook library, attempts are instrumented with Frida. */
    private static final Set<String> LINUX_OPTIONAL = Set.of("--build-hook");
    /** Authority entries constructed only by each native invocation, never copied from the caller. */
    private static final Set<String> OWNED_ENV = Set.of("RUSTC", "RUSTC_WRAPPER", "RUSTC_WORKSPACE_WRAPPER",
        "CARGO_MAKEFLAGS", "MAKEFLAGS", "MFLAGS", "CODEX_CARGO_SOCKET", "CODEX_LINUX_CARGO_WRAPPER_CONFIG");

    /** Prevents construction of this command namespace. */
    private CargoLaunchConfigurationCommand() {}

    /**
     * Creates a new launch document for an explicitly selected installed platform using the current environment.
     * All options require exactly one value; an existing destination is never overwritten.
     * Installed runtime and toolchain must already be complete. The real scheduling preflight retains authority checks.
     * @param tooling borrowed caller services and bounded platform parsers
     * @param arguments common construction options plus Unix credentials, Linux authority inputs and an optional Linux
     *     build hook where applicable
     * @param out borrowed progress stream; configuration contents and environment values are never printed
     * @return zero after complete publication
     * @throws IOException if an installed input, generated document or exclusive publication is invalid
     * @throws IllegalArgumentException if options, paths or platform selection are invalid
     */
    public static int run(ToolingScope tooling, String[] arguments, PrintStream out) throws IOException
    {
        return run(tooling, arguments, System.getenv(), out);
    }

    /**
     * Creates a new launch document like {@link #run(ToolingScope, String[], PrintStream)}, from an explicit caller
     * environment.
     * @param tooling borrowed caller services and bounded platform parsers
     * @param arguments common construction options plus Unix credentials, Linux authority inputs and an optional Linux
     *     build hook where applicable
     * @param callerEnvironment complete stage environment to copy into the document
     * @param out borrowed progress stream; configuration contents and environment values are never printed
     * @return zero after complete publication
     * @throws IOException if an installed input, generated document or exclusive publication is invalid
     * @throws IllegalArgumentException if options, paths or platform selection are invalid
     */
    public static int run(ToolingScope tooling, String[] arguments, Map<String, String> callerEnvironment, PrintStream out)
        throws IOException
    {
        Map<String, String> options = options(arguments);
        String platform = options.get("--platform");
        Path output = path(options, "--output");
        Map<String, Object> document = document(options, environment(platform, callerEnvironment));
        byte[] bytes = tooling.getJsonMapper().writeValueAsBytes(document);
        validate(tooling, platform, output, bytes);
        Files.write(output, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        out.println("Prepared a new launch document; administrator publication and native preflight remain required.");
        return 0;
    }

    /**
     * Parses exact platform-specific options before reading any files or environment entries.
     * @param arguments explicit option/value pairs
     * @return complete unique option values
     * @throws IllegalArgumentException if fields, platform or values are invalid
     */
    private static Map<String, String> options(String[] arguments)
    {
        var options = new HashMap<String, String>();
        if (arguments.length % 2 != 0) throw new IllegalArgumentException("Construction options require one value each");
        for (int index = 0; index < arguments.length; index += 2)
        {
            if (arguments[index + 1].isBlank() || options.putIfAbsent(arguments[index], arguments[index + 1]) != null)
                throw new IllegalArgumentException("Construction option is duplicate or empty: " + arguments[index]);
        }
        String platform = options.get("--platform");
        if (!Set.of("Linux", "Windows", "macOS").contains(platform == null ? "" : platform))
            throw new IllegalArgumentException("Construction platform must be Linux, Windows or macOS");
        var required = new java.util.HashSet<>(COMMON);
        if (!platform.equals("Windows")) required.addAll(UNIX);
        if (platform.equals("Linux")) required.addAll(LINUX);
        var permitted = new java.util.HashSet<>(required);
        if (platform.equals("Linux")) permitted.addAll(LINUX_OPTIONAL);
        if (!options.keySet().containsAll(required) || !permitted.containsAll(options.keySet()))
            throw new IllegalArgumentException("Construction options must be " + required + " plus any of " +
                permitted.stream().filter(option -> !required.contains(option)).sorted().toList());
        return Map.copyOf(options);
    }

    /**
     * Copies the complete admitted environment, excluding only native invocation or loader authority.
     * Empty ordinary values and stage-specific source/bwrap identities remain distinct from absence.
     * @param platform represented installed platform
     * @param original complete current caller environment
     * @return copied environment accepted by the matching native launch schema
     */
    static Map<String, String> environment(String platform, Map<String, String> original)
    {
        var environment = new HashMap<String, String>();
        original.forEach((key, value) ->
        {
            String normalized = platform.equals("Windows") ? key.toUpperCase(Locale.ROOT) : key;
            boolean owned = OWNED_ENV.contains(normalized) || normalized.startsWith("CODEX_CARGO_");
            boolean loader = platform.equals("macOS") && (key.startsWith("DYLD_") || key.equals("LD_PRELOAD") || key.equals("LD_LIBRARY_PATH"));
            if (!owned && !loader) environment.put(key, value);
        });
        return Map.copyOf(environment);
    }

    /**
     * Hashes actual pinned tools and emits the exact existing platform schema.
     * @param options complete validated construction inputs
     * @param environment current admitted environment with invocation-owned entries removed
     * @return resource-free configuration document
     * @throws IOException if an installed product or complete tree cannot be hashed
     */
    private static Map<String, Object> document(Map<String, String> options, Map<String, String> environment) throws IOException
    {
        String platform = options.get("--platform");
        Path runtime = directory(options, "--runtime");
        Path toolchain = directory(options, "--toolchain");
        String suffix = platform.equals("Windows") ? ".exe" : "";
        Path cargo = toolchain.resolve("bin/cargo" + suffix);
        Path rustc = toolchain.resolve("bin/rustc" + suffix);
        var identities = new HashMap<String, String>();
        identities.put("cargo", digest(cargo)); identities.put("rustc", digest(rustc));
        identities.put("toolchain", LinuxCargoLaunchConfiguration.toolchainDigest(toolchain));
        identities.put("frida", digest(path(options, "--frida-library")));
        identities.put("javaRuntime", platform.equals("Windows") ? LinuxCargoLaunchConfiguration.toolchainDigest(runtime) : digest(runtime.resolve("lib/modules")));
        var document = new HashMap<String, Object>();
        document.put("version", 1); document.put("cargo", cargo.toString()); document.put("rustc", rustc.toString());
        document.put("toolchainRoot", toolchain.toString()); document.put("environment", environment);
        document.put("identities", identities);
        document.put("memory", Map.of("historyDirectory", path(options, "--history").toString(),
            "inputRoot", path(options, "--workspace").toString(), "outputRoots", List.of(path(options, "--output-root").toString()),
            "toolchainRoot", toolchain.toString()));
        if (platform.equals("Windows"))
            identities.put("wrapper", digest(runtime.resolve("wrapper-image/codex-cargo-wrapper/codex-cargo-wrapper.exe")));
        else
        {
            document.put("uid", positive(options, "--uid")); document.put("gid", positive(options, "--gid"));
            document.put("supplementaryGroups", List.of());
            if (platform.equals("Linux"))
            {
                Path unshare = path(options, "--unshare"); Path libcgroup = path(options, "--libcgroup");
                document.put("unshare", unshare.toString()); document.put("libcgroup", libcgroup.toString());
                document.put("cgroupRoot", "/sys/fs/cgroup"); document.put("procRoot", "/proc");
                document.put("delegationParent", path(options, "--delegation-parent").toString());
                identities.put("unshare", digest(unshare)); identities.put("libcgroup", digest(libcgroup));
                if (options.containsKey("--build-hook"))
                {
                    Path buildHook = path(options, "--build-hook");
                    document.put("buildHook", buildHook.toString());
                    identities.put("buildHook", digest(buildHook));
                    identities.put("buildHookExec", digest(buildHook.resolveSibling(
                        LinuxCargoLaunchConfiguration.BUILD_HOOK_TRAMPOLINE)));
                }
            }
            else
            {
                for (Map.Entry<String, Path> entry : MacCargoLaunchConfiguration.installed(runtime, path(options, "--frida-library")).files().entrySet())
                    identities.put(entry.getKey(), digest(entry.getValue()));
            }
        }
        return document;
    }

    /**
     * Passes generated bytes through the actual matching bounded consumer before publication.
     * @param tooling borrowed services outliving the parser scope
     * @param platform selected strict schema
     * @param output represented new document location
     * @param bytes complete original generated document
     * @throws IOException if actual consumer schema, memory boundaries or values reject the bytes
     */
    private static void validate(ToolingScope tooling, String platform, Path output, byte[] bytes) throws IOException
    {
        switch (platform)
        {
            case "Linux" -> { try (var scope = new LinuxCargoScope(tooling)) { LinuxCargoLaunchConfiguration.decode(scope, output, bytes); } }
            case "Windows" -> { try (var scope = new WindowsCargoScope(tooling)) { WindowsCargoLaunchConfiguration.decode(scope, output, bytes); } }
            case "macOS" -> { try (var scope = new MacCargoScope(tooling)) { MacCargoLaunchConfiguration.decode(scope, output, bytes); } }
            default -> throw new IllegalArgumentException("Unsupported platform");
        }
    }

    /**
     * Reads a required positive Unix credential without accepting zero or overflow.
     * @param options complete explicit inputs
     * @param name credential option
     * @return positive credential
     * @throws IllegalArgumentException if the text is not a positive exact integer
     */
    private static int positive(Map<String, String> options, String name)
    {
        int value = Integer.parseInt(options.get(name));
        if (value <= 0) throw new IllegalArgumentException("Workload credential must be positive: " + name);
        return value;
    }

    /**
     * Requires an explicit absolute normalized path without consulting the caller's working directory.
     * @param options complete explicit construction inputs
     * @param name path option
     * @return original validated path
     * @throws IllegalArgumentException if the path is relative or not normalized
     */
    private static Path path(Map<String, String> options, String name)
    {
        Path path = Path.of(options.get(name));
        if (!path.isAbsolute() || !path.equals(path.normalize())) throw new IllegalArgumentException("Construction path must be absolute and normalized: " + name);
        return path;
    }

    /**
     * Rejects missing or final-symlink installed tree roots before hashing.
     * @param options complete explicit construction inputs
     * @param name tree option
     * @return existing regular directory
     * @throws IOException if the root is not a directory without a final symlink
     */
    private static Path directory(Map<String, String> options, String name) throws IOException
    {
        Path path = path(options, name);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Installed tree must be a nonsymlink directory: " + name);
        return path;
    }

    /**
     * Hashes one complete regular installed product without accepting a final symlink.
     * @param path exact installed product
     * @return lowercase SHA256 of actual bytes
     * @throws IOException if type or bytes are unavailable
     */
    private static String digest(Path path) throws IOException
    {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Pinned product is not a regular file: " + path);
        return Sha256.digest(path);
    }
}
