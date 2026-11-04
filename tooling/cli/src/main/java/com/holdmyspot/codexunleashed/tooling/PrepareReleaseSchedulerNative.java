package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Produces authenticated Frida 17.24.3 and Linux libcgroup and build hook inputs on a matching native runner.
 * The caller owns the fresh output directory, including failed inputs and command logs, until job cleanup.
 * Successful production establishes setup only; native scheduler acceptance remains unrun.
 */
public final class PrepareReleaseSchedulerNative
{
    /** Maximum compressed bytes accepted from one pinned download, in bytes. */
    private static final long MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024;
    /** Native producer command completion bound, in seconds. */
    private static final long COMMAND_TIMEOUT_SECONDS = 600;
    /** Official release identity shared by all supported native devkits. */
    private static final String FRIDA_VERSION = "17.24.3";
    /** Receipt filename read by the job owner; paths in it are relative to the output directory. */
    private static final String RECEIPT = "native-receipt.properties.xml";
    /** Linux architectures mapped to the official gum devkit identities that the build hook links. */
    private static final Map<String, String> GUM_HASHES = Map.of(
        "x86_64", "d5ffd59ebc5f50535e5f5e9c04cd968e2876eb91aff40872fe5afaf56d02a886",
        "arm64", "e73029dc3552cbcd370fbf251125bec7e4f6c56c3663c28251fa14bec59efb19");
    /** The build hook's C sources, as resources of the scheduler package. */
    private static final List<String> HOOK_SOURCES = List.of("linux-build-hook.c", "linux-build-hook-exec.c");
    /** The functions the build hook library exports; it hides every other symbol, including gum's. */
    private static final List<String> HOOK_EXPORTS = List.of("execve", "execv", "execvp", "execvpe", "execveat",
        "fexecve", "execl", "execlp", "execle", "posix_spawn", "posix_spawnp", "codex_build_hook_exec_static");
    /** Supported host and architecture pairs mapped to accepted official archive identities. */
    private static final Map<String, String> FRIDA_HASHES = Map.of(
        "Linux/x86_64", "9a11abf474899a33af6dd9af8a064448b23947a29a3af747eb3c7851591ffec2",
        "Linux/arm64", "925bda0e1628b7b282dda370e293ab84aedaacfc11d259378ee6a0885a9b99f7",
        "macOS/x86_64", "0a23703642392e5a6db8343ba069dd6bf226a8822dd0f6e6828512d522edd3c8",
        "macOS/arm64", "ff3c0bef5acf991bbe14f01c51249a93ef852e4ade41816e4e2636c4197611a2",
        "Windows/x86_64", "42af54ded8be200b461d6e75066bc2c781878a6afd249c940dd8888158a9bf3a",
        "Windows/arm64", "7a735990d33b272e5e5eee82d5b312bc0d3c11281a5277a14ca2651a1076a669");

    /** Prevents constructing a command that has no instance state. */
    private PrepareReleaseSchedulerNative()
    {
    }

    /**
     * Produces native inputs from one fresh absolute output path and the producer-support directory.
     * RUNNER_TEMP must be the existing output parent; RUNNER_OS must match the actual supported host.
     * @param args output path followed by the directory containing the accepted Windows producer and pins
     * @throws IllegalArgumentException if arguments or the output namespace are invalid
     * @throws UnsupportedOperationException if the actual host or runner identity is unsupported
     * @throws IOException if acquisition, authentication, archive validation or native production fails
     * @throws InterruptedException if the owning thread is interrupted while a native command runs
     */
    public static void main(String[] args) throws IOException, InterruptedException
    {
        if (args.length != 2)
            throw new IllegalArgumentException("Expected fresh absolute output and producer-support directory");
        String runnerTemp = System.getenv("RUNNER_TEMP");
        if (runnerTemp == null)
            throw new IllegalArgumentException("RUNNER_TEMP must identify the owned runner temporary directory");
        produce(Path.of(args[0]), Path.of(runnerTemp), System.getenv("RUNNER_OS"),
            System.getProperty("os.name"), System.getProperty("os.arch"), Path.of(args[1]));
    }

    /**
     * Creates authenticated products in an exclusive runner-owned output directory.
     * All commands retain literal arguments, output and status. Failures retain the caller-owned directory.
     * @param output fresh absolute direct child of runnerTemp
     * @param runnerTemp existing temporary directory owned by the invoking job
     * @param runnerOs declared GitHub runner operating system
     * @param host actual Java operating-system name
     * @param machine actual Java architecture name
     * @param support directory containing unchanged Windows producer and identity files
     * @throws IllegalArgumentException if output namespace is invalid
     * @throws UnsupportedOperationException if host identities disagree or are unsupported
     * @throws IOException if native inputs cannot be authenticated or produced
     * @throws InterruptedException if a command wait is interrupted
     */
    public static void produce(Path output, Path runnerTemp, String runnerOs, String host, String machine, Path support)
        throws IOException, InterruptedException
    {
        String platform = selectPlatform(runnerOs, host, machine);
        String architecture = normalizeArchitecture(machine);
        validateOutput(output, runnerTemp);
        Files.createDirectory(output);
        Files.createDirectory(output.resolve("tmp"));
        Properties receipt = inputReceipt(platform, architecture, support);
        writeProperties(output.resolve(RECEIPT), receipt);
        Commands commands = new Commands(output);

        String digest = receipt.getProperty("frida.archive.sha256");
        URI uri = URI.create(receipt.getProperty("frida.archive.url"));
        Path archive = output.resolve("devkit.tar.xz");
        download(uri, digest, archive);
        receipt.setProperty("frida.archive.url", uri.toString());
        receipt.setProperty("frida.archive.sha256", digest);
        writeProperties(output.resolve(RECEIPT), receipt);

        if (platform.equals("Windows"))
        {
            validateArchive(archive, commands);
            Path windows = Files.createDirectory(output.resolve("windows"));
            Files.createDirectory(windows.resolve("tmp"));
            Path scripts = Files.createDirectory(windows.resolve("scripts"));
            Files.copy(support.resolve("windows-producer.ps1"), windows.resolve("producer.ps1"));
            for (String name : List.of("exports.txt", "devkit-x64.sha256", "devkit-arm64.sha256"))
                Files.copy(support.resolve(name), scripts.resolve(name));
            Files.copy(archive, windows.resolve("devkit.tar.xz"));
            String producerArchitecture = architecture.equals("x86_64") ? "x64" : "arm64";
            commands.run(List.of("pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-File",
                windows.resolve("producer.ps1").toString(), "-Architecture", producerArchitecture));
            Files.copy(windows.resolve("producer/frida-core.dll"), output.resolve("frida.dll"));
        }
        else
        {
            extract(archive, output.resolve("devkit"), commands);
            Path library = output.resolve("devkit/libfrida-core.a");
            if (platform.equals("Linux"))
                produceLinux(output, architecture, library, receipt, commands);
            else
                produceMac(output, architecture, library, commands);
        }
        completeReceipt(output, receipt);
    }

    /**
     * Describes current native recipe inputs independently of supplied product receipts.
     * The executing producer and its nested command implementation determine the recipe identity;
     * unrelated application classes and distribution packaging are intentionally excluded. Windows
     * support files are caller-supplied trusted release inputs, not authenticated by their own receipt.
     * This binds reuse to accepted production evidence; it does not authenticate a hostile receipt.
     * @param platform canonical supported host
     * @param machine native architecture or its JVM alias
     * @param support current trusted producer-support directory; only Windows consumes its files
     * @return current expected receipt fields, excluding product bytes and completion
     * @throws IOException if producer or determining support bytes cannot be read
     * @throws UnsupportedOperationException if the host or architecture is unsupported
     */
    public static Properties inputReceipt(String platform, String machine, Path support) throws IOException
    {
        selectPlatform(platform, platform, machine);
        String architecture = normalizeArchitecture(machine);
        Properties receipt = new Properties();
        receipt.setProperty("applicability.version", "1");
        receipt.setProperty("host", platform);
        receipt.setProperty("architecture", architecture);
        receipt.setProperty("pathBase", "output-directory");
        receipt.setProperty("nativeSchedulerAcceptance", "unrun");
        StringBuilder producer = new StringBuilder();
        for (Class<?> type : Arrays.stream(PrepareReleaseSchedulerNative.class.getNestMembers())
            .sorted(Comparator.comparing(Class::getName)).toList())
        {
            String resource = "/" + type.getName().replace('.', '/') + ".class";
            try (InputStream bytes = type.getResourceAsStream(resource))
            {
                if (bytes == null) throw new IOException("Current native producer class is unavailable: " + resource);
                producer.append(type.getName()).append('=').append(sha256(bytes)).append('\n');
            }
        }
        receipt.setProperty("producer.sha256", sha256(new ByteArrayInputStream(
            producer.toString().getBytes(StandardCharsets.UTF_8))));
        String assetPlatform = platform.equals("macOS") ? "macos" : platform.toLowerCase(Locale.ROOT);
        String asset = "frida-core-devkit-" + FRIDA_VERSION + "-" + assetPlatform + "-" + architecture + ".tar.xz";
        receipt.setProperty("frida.archive.url", "https://github.com/frida/frida/releases/download/" + FRIDA_VERSION + "/" + asset);
        receipt.setProperty("frida.archive.sha256", FRIDA_HASHES.get(platform + "/" + architecture));
        if (platform.equals("Linux"))
        {
            boolean x64 = architecture.equals("x86_64");
            receipt.setProperty("libcgroup.package.url", "https://deb.debian.org/debian/pool/main/libc/libcgroup/libcgroup3_3.2.0-1_" +
                (x64 ? "amd64" : "arm64") + ".deb");
            receipt.setProperty("libcgroup.package.sha256", x64 ?
                "bd0938fc5ed7aaff58aae3d2dad717378b942be9906a9606e08f88078279697d" :
                "f1bba53be5471643b488cec603b10fac8d351f411154c538d5e8f458318ba0d1");
            receipt.setProperty("gum.archive.url", "https://github.com/frida/frida/releases/download/" + FRIDA_VERSION +
                "/frida-gum-devkit-" + FRIDA_VERSION + "-linux-" + architecture + ".tar.xz");
            receipt.setProperty("gum.archive.sha256", GUM_HASHES.get(architecture));
            for (String name : HOOK_SOURCES)
            {
                try (InputStream input = hookSource(name))
                {
                    receipt.setProperty("hook." + name + ".sha256", sha256(input));
                }
            }
        }
        if (platform.equals("Windows"))
        {
            for (String name : List.of("windows-producer.ps1", "exports.txt", "devkit-x64.sha256", "devkit-arm64.sha256"))
            {
                Path input = support.resolve(name);
                requireRegular(input);
                receipt.setProperty("support." + name + ".sha256", sha256(input));
            }
        }
        return receipt;
    }

    /**
     * Records the actual regular product bytes after native commands complete.
     * @param output caller-owned production directory
     * @param receipt current native inputs from {@link #inputReceipt(String, String, Path)}
     * @throws IOException if a required product is missing, linked or unreadable, or publication fails
     */
    public static void completeReceipt(Path output, Properties receipt) throws IOException
    {
        for (String name : products(receipt.getProperty("host")))
        {
            Path product = output.resolve(name);
            requireRegular(product);
            receipt.setProperty("product." + name + ".sha256", sha256(product));
        }
        receipt.setProperty("production", "complete");
        writeProperties(output.resolve(RECEIPT), receipt);
    }

    /**
     * Lists required products for the already validated canonical host.
     * @param platform supported canonical host
     * @return exact required native product names
     * @throws UnsupportedOperationException if the host is unsupported
     */
    private static List<String> products(String platform)
    {
        return switch (platform)
        {
            case "Linux" -> List.of("frida", "libcgroup.so", "libbuildhook.so", "codex-build-hook-exec");
            case "Windows" -> List.of("frida.dll");
            case "macOS" -> List.of("frida");
            default -> throw new UnsupportedOperationException("Unsupported platform: " + platform);
        };
    }

    /**
     * Validates accepted supplied native products without executing, mutating or acquiring anything.
     * The caller supplies the intended supported target and current trusted support inputs. Current
     * producer bytes and retained dependency pins are independently derived by this running command.
     * Product digests are compared with the existing trusted production receipt, not a new authority.
     * Old or failed receipts remain intact for recovery and require fresh accepted production.
     * This establishes recipe applicability and integrity, not native execution acceptance.
     * @param output existing absolute nonsymlink product directory
     * @param support current trusted producer-support inputs
     * @param platform canonical intended target host: Linux, macOS or Windows
     * @param machine intended target architecture or JVM alias
     * @throws IOException if the receipt, determining inputs or actual product bytes disagree
     * @throws UnsupportedOperationException if the requested target is unsupported
     */
    public static void validateReuse(Path output, Path support, String platform, String machine) throws IOException
    {
        if (!output.isAbsolute() || !Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Native reuse requires an existing absolute nonsymlink output directory: " + output);
        Path receiptPath = output.resolve(RECEIPT);
        requireRegular(receiptPath);
        Properties actual = new Properties();
        try (InputStream bytes = Files.newInputStream(receiptPath)) { actual.loadFromXML(bytes); }
        Properties expected = inputReceipt(platform, machine, support);
        expected.setProperty("production", "complete");
        for (String name : expected.stringPropertyNames().stream().sorted().toList())
            matchProperty(actual, name, expected.getProperty(name));
        for (String name : products(platform))
        {
            Path product = output.resolve(name);
            requireRegular(product);
            matchProperty(actual, "product." + name + ".sha256", sha256(product));
        }
    }

    /**
     * Requires one producer-derived identity without mutating the retained production evidence.
     * @param receipt accepted prior production record
     * @param name determining field
     * @param expected current producer or actual product identity
     * @throws IOException if the field is absent or different
     */
    private static void matchProperty(Properties receipt, String name, String expected) throws IOException
    {
        if (!expected.equals(receipt.getProperty(name)))
            throw new IOException("Native reuse receipt mismatch or missing applicability data: " + name +
                "; retain supplied products for recovery");
    }

    /**
     * Rejects a missing, linked or nonregular determining input before reading it.
     * @param input path whose own bytes are required
     * @throws IOException if input is not a regular nonsymlink file
     */
    private static void requireRegular(Path input) throws IOException
    {
        if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Native producer requires a regular nonsymlink input or product: " + input);
    }

    /**
     * Checks the actual host and runner declaration against the supported native matrix.
     * @param runnerOs declared runner system
     * @param host actual Java system name
     * @param machine actual Java architecture
     * @return Linux, macOS or Windows for the matching supported native runner
     * @throws UnsupportedOperationException if identities disagree or the pair is unsupported
     */
    public static String selectPlatform(String runnerOs, String host, String machine)
    {
        String platform;
        if (host.startsWith("Windows"))
            platform = "Windows";
        else
            platform = switch (host)
            {
                case "Linux" -> "Linux";
                case "Mac OS X", "Darwin", "macOS" -> "macOS";
                default -> throw new UnsupportedOperationException("Unsupported native host: " + host);
            };
        String identity = platform + "/" + normalizeArchitecture(machine);
        if (!platform.equals(runnerOs) || !FRIDA_HASHES.containsKey(identity))
            throw new UnsupportedOperationException("A supported matching native runner is required: " + identity);
        return platform;
    }

    /**
     * Converts actual JVM architecture aliases to the release asset architecture.
     * @param machine actual JVM architecture
     * @return x86_64 or arm64
     * @throws UnsupportedOperationException if the architecture is unsupported
     */
    private static String normalizeArchitecture(String machine)
    {
        return switch (machine.toLowerCase(Locale.ROOT))
        {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "arm64";
            default -> throw new UnsupportedOperationException("Unsupported native architecture: " + machine);
        };
    }

    /**
     * Rejects existing paths and paths outside the runner-owned direct-child namespace.
     * @param output proposed fresh absolute directory
     * @param runnerTemp actual existing runner temporary root
     * @throws IllegalArgumentException if output is relative, existing or outside that namespace
     * @throws IOException if the parent cannot be inspected
     */
    public static void validateOutput(Path output, Path runnerTemp) throws IOException
    {
        if (!output.isAbsolute() || output.getParent() == null ||
            !output.equals(output.normalize()) || !output.getParent().toRealPath().equals(runnerTemp.toRealPath()) ||
            Files.exists(output, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Output must be a fresh absolute direct child of RUNNER_TEMP: " + output);
    }

    /**
     * Acquires at most 512 MiB into a new file and authenticates it before any archive operation.
     * A failed acquisition retains its bytes for job-owned diagnosis and cleanup.
     * @param uri official pinned HTTPS download
     * @param expected exact lowercase SHA-256 identity
     * @param destination new file receiving compressed bytes
     * @throws IOException if HTTP, size or authentication validation fails
     */
    static void download(URI uri, String expected, Path destination) throws IOException
    {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(120_000);
        connection.setReadTimeout(120_000);
        try
        {
            if (connection.getResponseCode() != 200)
                throw new IOException("Pinned native download returned HTTP " + connection.getResponseCode() + ": " + uri);
            try (InputStream input = connection.getInputStream();
                 OutputStream target = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW))
            {
                copyBounded(input, target, MAX_DOWNLOAD_BYTES);
            }
            authenticate(destination, expected);
        }
        finally
        {
            connection.disconnect();
        }
    }

    /**
     * Copies input while enforcing an acquisition-size bound before writing an excess chunk.
     * @param input bytes supplied by the remote download
     * @param output new owned compressed file
     * @param maximum largest accepted byte count
     * @throws IOException if reading, writing or the byte bound fails
     */
    public static void copyBounded(InputStream input, OutputStream output, long maximum) throws IOException
    {
        byte[] buffer = new byte[1024 * 1024];
        long count = 0;
        for (int length; (length = input.read(buffer)) >= 0; )
        {
            count += length;
            if (count > maximum)
                throw new IOException("Pinned native input exceeded its acquisition bound: " + maximum);
            output.write(buffer, 0, length);
        }
    }

    /**
     * Authenticates a downloaded file before its contents may be consumed.
     * @param input downloaded compressed file
     * @param expected exact lowercase SHA-256 identity
     * @throws IOException if reading fails or bytes have a different identity
     */
    public static void authenticate(Path input, String expected) throws IOException
    {
        String actual = sha256(input);
        if (!actual.equals(expected))
            throw new IOException("Pinned native input digest mismatch: expected " + expected + ", actual " + actual);
    }

    /**
     * Computes the SHA-256 identity of a regular file without loading it into memory.
     * @param input file to read
     * @return lowercase hexadecimal SHA-256
     * @throws IOException if bytes cannot be read or SHA-256 is unavailable
     */
    static String sha256(Path input) throws IOException
    {
        try (InputStream bytes = Files.newInputStream(input)) { return sha256(bytes); }
    }

    /**
     * Computes one SHA-256 identity without taking ownership of the supplied stream.
     * @param bytes caller-owned bytes; remains open
     * @return lowercase hexadecimal SHA-256
     * @throws IOException if reading fails or SHA-256 is unavailable
     */
    private static String sha256(InputStream bytes) throws IOException
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            for (int length; (length = bytes.read(buffer)) >= 0; )
                digest.update(buffer, 0, length);
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IOException("JVM does not provide required SHA-256", e);
        }
    }

    /**
     * Uses the installed tar parser to list names and types before extraction of authenticated bytes.
     * GNU tar and BSD tar are supported; ambiguous or unsupported listing forms are rejected.
     * @param archive authenticated tar.xz file
     * @param commands command owner retaining exact listings and statuses
     * @throws IOException if tar fails or a member is unsafe
     * @throws InterruptedException if a listing wait is interrupted
     */
    public static void validateArchive(Path archive, Commands commands) throws IOException, InterruptedException
    {
        Path names = commands.run(List.of("tar", "-tf", archive.toString()));
        Path verbose = commands.run(List.of("tar", "-tvf", archive.toString()));
        validateMembers(Files.readAllLines(names, StandardCharsets.UTF_8),
            Files.readAllLines(verbose, StandardCharsets.UTF_8));
    }

    /**
     * Rejects non-simple names, traversal, repeated normalized names and non-regular member types.
     * Only tar-parsed names and type listings for the same authenticated archive are accepted.
     * Directory-only root metadata such as {@code ./} is allowed; meaningful member targets remain unique.
     * @param names tar's complete name listing in archive order
     * @param verbose tar's complete verbose listing in the same order
     * @throws IOException if a name, type or listing pairing is unsafe or ambiguous
     */
    public static void validateMembers(List<String> names, List<String> verbose) throws IOException
    {
        if (names.isEmpty() || names.size() != verbose.size())
            throw new IOException("Native archive listings must contain the same nonempty member sequence");
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < names.size(); ++index)
        {
            String name = names.get(index);
            String detail = verbose.get(index);
            boolean simpleName = name.matches("[A-Za-z0-9_./+\\-]+") && !name.startsWith("/");
            if (!simpleName)
                throw new IOException("Native archive name is not a simple relative path: " + name);
            List<String> segments = new ArrayList<>();
            for (String segment : name.split("/", -1))
            {
                if (segment.equals(".."))
                    throw new IOException("Native archive member traverses its output root: " + name);
                if (!segment.isEmpty() && !segment.equals("."))
                    segments.add(segment);
            }
            boolean regularType = detail.startsWith("-") || detail.startsWith("d");
            if (!regularType || !detail.endsWith(" " + name))
                throw new IOException("Native archive member is not a unique regular file/directory: " + name);
            String canonical = String.join("/", segments);
            if (canonical.isEmpty())
            {
                if (!detail.startsWith("d"))
                    throw new IOException("Native archive root metadata must describe a directory: " + name);
                continue;
            }
            if (!seen.add(canonical))
                throw new IOException("Native archive has a duplicate resolved name: " + name);
        }
    }

    /**
     * Extracts a validated authenticated archive into an exclusive owned directory.
     * @param archive authenticated tar.xz file
     * @param destination fresh directory receiving regular members
     * @param commands owner of the bounded installed tar operation
     * @throws IOException if listing, validation, directory creation or extraction fails
     * @throws InterruptedException if a command wait is interrupted
     */
    public static void extract(Path archive, Path destination, Commands commands) throws IOException, InterruptedException
    {
        validateArchive(archive, commands);
        Files.createDirectory(destination);
        commands.run(List.of("tar", "-xf", archive.toString(), "-C", destination.toString()));
    }

    /**
     * Links the accepted Linux Frida recipe and authenticates the exact libcgroup3 shared library.
     * @param output owned output root
     * @param architecture normalized release architecture
     * @param library authenticated Frida static library
     * @param receipt production receipt receiving libcgroup input identities
     * @param commands bounded native command owner
     * @throws IOException if linking, acquisition or library authentication fails
     * @throws InterruptedException if a command wait is interrupted
     */
    private static void produceLinux(Path output, String architecture, Path library, Properties receipt, Commands commands)
        throws IOException, InterruptedException
    {
        commands.run(List.of("cc", "-shared", "-fPIC", "-Wl,--whole-archive", library.toString(),
            "-Wl,--no-whole-archive", "-ldl", "-lrt", "-lm", "-lpthread", "-Wl,-z,noexecstack", "-o",
            output.resolve("frida").toString()));
        boolean x64 = architecture.equals("x86_64");
        String packageHash = receipt.getProperty("libcgroup.package.sha256");
        String libraryHash = x64 ? "225437e52c9e87e977f645fe639439782252952dbb7c9896f3df3f02bc5d0aab" :
            "abe212dc85aa2c9008296a2a042270793bea52a94846b5ce6a907cd085d0f8d7";
        URI uri = URI.create(receipt.getProperty("libcgroup.package.url"));
        Path deb = output.resolve("libcgroup.deb");
        download(uri, packageHash, deb);
        receipt.setProperty("libcgroup.package.url", uri.toString());
        receipt.setProperty("libcgroup.package.sha256", packageHash);
        writeProperties(output.resolve(RECEIPT), receipt);
        Path unpacked = output.resolve("libcgroup-package");
        commands.run(List.of("dpkg-deb", "-x", deb.toString(), unpacked.toString()));
        List<Path> matches = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(unpacked))
        {
            for (Path path : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList())
            {
                if (path.getFileName().toString().startsWith("libcgroup.so.") && sha256(path).equals(libraryHash))
                    matches.add(path);
            }
        }
        if (matches.size() != 1)
            throw new IOException("Expected exactly one authenticated regular libcgroup3 product; found " + matches.size());
        Files.copy(matches.getFirst(), output.resolve("libcgroup.so"));
        produceBuildHook(output, receipt, commands);
    }

    /**
     * Opens one of the build hook's C sources.
     * @param name the source's file name
     * @return its bytes
     * @throws IOException if the resource is absent
     */
    private static InputStream hookSource(String name) throws IOException
    {
        String resource = "/com/holdmyspot/codexunleashed/scheduler/" + name;
        InputStream input = PrepareReleaseSchedulerNative.class.getResourceAsStream(resource);
        if (input == null)
            throw new IOException("The build hook source is unavailable: " + resource);
        return input;
    }

    /**
     * Compiles the build hook library against the authenticated gum devkit, and its static-executable trampoline.
     * The library exports only the exec and spawn entry points it replaces.
     * @param output owned output root
     * @param receipt production receipt naming the gum devkit
     * @param commands bounded native command owner
     * @throws IOException if acquisition, extraction or compilation fails
     * @throws InterruptedException if a command wait is interrupted
     */
    private static void produceBuildHook(Path output, Properties receipt, Commands commands)
        throws IOException, InterruptedException
    {
        Path archive = output.resolve("gum.tar.xz");
        download(URI.create(receipt.getProperty("gum.archive.url")), receipt.getProperty("gum.archive.sha256"), archive);
        Path devkit = output.resolve("gum");
        extract(archive, devkit, commands);
        Path sources = Files.createDirectory(output.resolve("hook-sources"));
        for (String name : HOOK_SOURCES)
        {
            try (InputStream input = hookSource(name))
            {
                Files.copy(input, sources.resolve(name));
            }
        }
        Path exports = sources.resolve("exports.map");
        Files.writeString(exports, "{\n  global:\n" + HOOK_EXPORTS.stream().map(name -> "    " + name + ";\n").
            collect(java.util.stream.Collectors.joining()) + "  local: *;\n};\n");
        commands.run(List.of("cc", "-Wall", "-Wextra", "-Werror", "-O2", "-fPIC", "-shared", "-I" + devkit,
            "-ffunction-sections", "-fdata-sections", sources.resolve("linux-build-hook.c").toString(), "-o",
            output.resolve("libbuildhook.so").toString(), devkit.resolve("libfrida-gum.a").toString(),
            "-Wl,--gc-sections", "-Wl,--version-script=" + exports, "-Wl,-z,noexecstack", "-lm", "-lresolv",
            "-latomic", "-ldl", "-lrt", "-pthread"));
        commands.run(List.of("cc", "-Wall", "-Wextra", "-Werror", "-O2", "-o",
            output.resolve("codex-build-hook-exec").toString(), sources.resolve("linux-build-hook-exec.c").toString(),
            "-ldl"));
    }

    /**
     * Links and signs the accepted macOS library with the native Apple SDK.
     * @param output owned output root
     * @param architecture normalized native release architecture
     * @param library authenticated static Frida library
     * @param commands bounded native command owner
     * @throws IOException if SDK discovery, linking or signing fails
     * @throws InterruptedException if a command wait is interrupted
     */
    private static void produceMac(Path output, String architecture, Path library, Commands commands)
        throws IOException, InterruptedException
    {
        String clang = Files.readString(commands.run(List.of("xcrun", "--find", "clang"))).strip();
        String sdk = Files.readString(commands.run(List.of("xcrun", "--show-sdk-path"))).strip();
        List<String> arguments = new ArrayList<>(List.of(clang, "-isysroot", sdk, "-shared", "-arch", architecture,
            "-O2", "-DNDEBUG", "-Wl,-dead_strip", "-Wl,-x"));
        if (architecture.equals("x86_64"))
            arguments.add("-Wl,-segalign,0x4000");
        arguments.addAll(List.of("-o", output.resolve("frida").toString(),
            "-Wl,-install_name,@rpath/libfrida-core.dylib", "-Wl,-force_load," + library,
            "-framework", "CoreFoundation", "-framework", "Foundation", "-framework", "AppKit",
            "-framework", "IOKit", "-framework", "Security", "-lbsm", "-ldl", "-lm", "-lresolv"));
        commands.run(arguments);
        commands.run(List.of("codesign", "--force", "--sign", "-", output.resolve("frida").toString()));
        commands.run(List.of("codesign", "--verify", "--strict", output.resolve("frida").toString()));
    }

    /**
     * Stores a standard XML Properties receipt without introducing a JSON serializer or timestamp.
     * @param destination job-owned receipt path
     * @param properties literal arguments, status or authenticated product identities
     * @throws IOException if receipt publication fails
     */
    private static void writeProperties(Path destination, Properties properties) throws IOException
    {
        try (OutputStream output = Files.newOutputStream(destination))
        {
            properties.storeToXML(output, "Native producer evidence; paths are relative to output-directory", StandardCharsets.UTF_8);
        }
    }

    /** Owns the request, environment, logs and lifecycle of producer subprocesses. */
    public static final class Commands
    {
        /** Exclusive directory receiving command receipts and logs. */
        private final Path output;
        /** Number of commands recorded by this one sequential producer. */
        private int sequence;

        /**
         * Creates a command owner for an existing owned directory.
         * @param output exclusive existing producer output directory
         */
        public Commands(Path output)
        {
            this.output = output;
        }

        /**
         * Records and runs one literal command with a ten-minute completion bound.
         * A timeout or interruption destroys and waits for all observed descendants before returning failure.
         * Separate standard-output/error files preserve the complete native diagnostic streams.
         * @param arguments executable followed by literal arguments
         * @return owned standard-output log for a successful command
         * @throws IOException if launch fails, a command fails, or its completion bound expires
         * @throws InterruptedException if the owner is interrupted while waiting
         */
        Path run(List<String> arguments) throws IOException, InterruptedException
        {
            ++sequence;
            String basename = "command-" + sequence;
            Properties record = new Properties();
            for (int index = 0; index < arguments.size(); ++index)
                record.setProperty("argument." + index, arguments.get(index));
            record.setProperty("argument.count", Integer.toString(arguments.size()));
            record.setProperty("status", "unrun");
            Path receipt = output.resolve(basename + ".properties.xml");
            writeProperties(receipt, record);
            Path stdout = output.resolve(basename + ".stdout.log");
            Path stderr = output.resolve(basename + ".stderr.log");
            ProcessBuilder builder = new ProcessBuilder(arguments).directory(output.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
            builder.environment().put("TMPDIR", output.resolve("tmp").toString());
            builder.environment().put("LC_ALL", "C");
            Process process;
            try
            {
                process = builder.start();
            }
            catch (IOException e)
            {
                record.setProperty("status", "launch-failed");
                record.setProperty("diagnostic", e.toString());
                writeProperties(receipt, record);
                throw e;
            }
            try
            {
                if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                {
                    stop(process);
                    record.setProperty("status", "timeout");
                    writeProperties(receipt, record);
                    throw new IOException("Native producer exceeded 600 seconds; retain " + stderr);
                }
            }
            catch (InterruptedException e)
            {
                stop(process);
                record.setProperty("status", "interrupted");
                writeProperties(receipt, record);
                throw e;
            }
            record.setProperty("status", Integer.toString(process.exitValue()));
            writeProperties(receipt, record);
            if (process.exitValue() != 0)
                throw new IOException("Native producer exited " + process.exitValue() + "; retain " + stderr);
            return stdout;
        }

        /**
         * Terminates and waits for the observed process tree after timeout or interruption.
         * @param process producer whose ownership remains with this command
         */
        private static void stop(Process process)
        {
            List<ProcessHandle> descendants = process.descendants().toList();
            for (ProcessHandle descendant : descendants.reversed())
                descendant.destroyForcibly();
            process.destroyForcibly();
            for (ProcessHandle descendant : descendants)
                descendant.onExit().join();
            process.toHandle().onExit().join();
        }
    }
}
