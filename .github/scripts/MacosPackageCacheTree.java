import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/** Inventories sealed voice files and restricts the macOS Cargo dependency cache. */
final class MacosPackageCacheTree {
  private static final List<String> BINARIES =
    List.of("codex", "codex-app-server", "codex-code-mode-host", "codex-exec-server");
  private static final List<String> DEPENDENCY_DIRECTORIES = List.of("build", "deps", ".fingerprint");
  private static final Pattern PACKAGE_EXECUTABLE =
    Pattern.compile("codex(?:-app-server|-code-mode-host|-exec-server|_app_server|_code_mode_host|_exec_server)?(?:-[0-9a-f]+)?");
  private static final Comparator<Path> BY_NAME = Comparator.comparing(
    path -> path.getFileName().toString().getBytes(UTF_8), Arrays::compareUnsigned);

  private record Entry(Path path, BasicFileAttributes attributes) {
    boolean isDebugBundle() {
      return path.getFileName().toString().endsWith(".dSYM");
    }
  }

  private record Outputs(List<Path> bundles, List<Path> executables) {
    boolean isEmpty() {
      return bundles.isEmpty() && executables.isEmpty();
    }
  }

  @FunctionalInterface
  private interface EntryConsumer {
    void accept(Entry entry) throws IOException;
  }

  private static void require(boolean condition, String message) throws IOException {
    if (!condition) {
      throw new IOException(message);
    }
  }

  private static BasicFileAttributes attributes(Path path) throws IOException {
    return Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
  }

  private static void visit(Path root, boolean skipDebugBundles, EntryConsumer consumer) throws IOException {
    require(attributes(root).isDirectory(), "Expected a real directory at " + root);
    visitChildren(root, skipDebugBundles, consumer);
  }

  private static void visitChildren(Path directory, boolean skipDebugBundles, EntryConsumer consumer)
    throws IOException {
    List<Path> children;
    try (var stream = Files.list(directory)) {
      children = stream.sorted(BY_NAME).toList();
    }

    var directories = new ArrayList<Path>();
    for (Path child : children) {
      var entry = new Entry(child, attributes(child));
      boolean opaque = skipDebugBundles && entry.isDebugBundle();
      require(opaque || entry.attributes().isDirectory() || entry.attributes().isRegularFile(),
        "Unsupported entry in cache input tree: " + child);
      consumer.accept(entry);
      if (entry.attributes().isDirectory() && !opaque) {
        directories.add(child);
      }
    }

    for (Path child : directories) {
      visitChildren(child, skipDebugBundles, consumer);
    }
  }

  private static void voiceInventory(Path root) throws Exception {
    require(Files.isRegularFile(root.resolve("runtime/runtime.json"), NOFOLLOW_LINKS)
      && Files.isRegularFile(root.resolve("codex-voice-host"), NOFOLLOW_LINKS),
      "Missing sealed voice runtime or helper");
    var digest = MessageDigest.getInstance("SHA-256");
    visit(root, false, entry -> {
      if (entry.attributes().isRegularFile()) {
        Path path = entry.path();
        try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
          input.transferTo(OutputStream.nullOutputStream());
        }
        int mode = (int) Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS) & 0777;
        String record = root.relativize(path) + "\0" + mode + "\0" + HexFormat.of().formatHex(digest.digest()) + "\0";
        System.out.write(record.getBytes(UTF_8));
      }
    });
    require(!System.out.checkError(), "Could not write the voice inventory");
  }

  private static Outputs packageOutputs(Path root) throws IOException {
    var knownOutputs = new HashSet<Object>();
    var regularFiles = new ArrayList<Entry>();
    var bundles = new ArrayList<Path>();
    visit(root, true, entry -> {
      if (entry.isDebugBundle()) {
        bundles.add(entry.path());
      } else if (entry.attributes().isRegularFile()) {
        var identity = entry.attributes().fileKey();
        require(Objects.nonNull(identity), "File identity unavailable for " + entry.path());
        regularFiles.add(entry);
        if (PACKAGE_EXECUTABLE.matcher(entry.path().getFileName().toString()).matches()) {
          knownOutputs.add(identity);
        }
      }
    });

    var aliases = regularFiles.stream()
      .filter(entry -> knownOutputs.contains(entry.attributes().fileKey()))
      .map(Entry::path)
      .toList();
    return new Outputs(bundles, aliases);
  }

  private static void prune(Path root, String target) throws IOException {
    Path release = root.resolve(target).resolve("release");
    for (String name : BINARIES) {
      require(Files.isRegularFile(release.resolve(name), NOFOLLOW_LINKS),
        "Expected all shipped macOS package binaries");
    }

    var outputs = packageOutputs(root);
    for (Path executable : outputs.executables()) {
      Files.delete(executable);
    }
    for (Path bundle : outputs.bundles()) {
      Files.walkFileTree(bundle, new SimpleFileVisitor<Path>() {
        @Override
        public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
          Files.delete(file);
          return CONTINUE;
        }

        @Override
        public java.nio.file.FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
          if (failure != null) {
            throw failure;
          }
          Files.delete(directory);
          return CONTINUE;
        }
      });
    }
    require(packageOutputs(root).isEmpty(), "Package outputs remain after pruning");
  }

  private static boolean isNonemptyDirectory(Path path) throws IOException {
    if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
      return false;
    }
    try (var entries = Files.newDirectoryStream(path)) {
      return entries.iterator().hasNext();
    }
  }

  private static void install(Path stage, Path destination, String target) throws IOException {
    require(packageOutputs(stage).isEmpty(), "Cache contains macOS package outputs");
    Path release = stage.resolve(target).resolve("release");
    for (String name : DEPENDENCY_DIRECTORIES) {
      require(isNonemptyDirectory(release.resolve(name)), "Cache lacks Cargo dependencies in " + name);
    }

    require(Files.isDirectory(destination, NOFOLLOW_LINKS) && !isNonemptyDirectory(destination),
      "Cargo target directory is not empty");
    // The atomic rename refuses to replace a directory that gains content after the check.
    Files.move(stage, destination, ATOMIC_MOVE, REPLACE_EXISTING);
  }

  public static void main(String[] arguments) throws Exception {
    require(arguments.length >= 2, "Expected an operation and cache tree path");
    String operation = arguments[0];
    Path root = Path.of(arguments[1]);
    switch (operation) {
      case "voice-inventory" -> {
        require(arguments.length == 2, "Expected voice-inventory ROOT");
        voiceInventory(root);
      }
      case "prune" -> {
        require(arguments.length == 3, "Expected prune ROOT TARGET");
        prune(root, arguments[2]);
      }
      case "install" -> {
        require(arguments.length == 4, "Expected install STAGE DESTINATION TARGET");
        install(root, Path.of(arguments[2]), arguments[3]);
      }
      default -> throw new IllegalArgumentException("Unknown cache tree operation: " + operation);
    }
  }
}
