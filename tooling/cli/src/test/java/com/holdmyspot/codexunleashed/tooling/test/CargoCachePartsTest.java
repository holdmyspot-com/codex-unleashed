package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.CargoCacheKeys;
import com.holdmyspot.codexunleashed.tooling.CargoCacheParts;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Verifies real grouped tar transfers against an in-process registry that answers ORAS commands. */
public final class CargoCachePartsTest
{
    /** Creates independent method fixtures. */ public CargoCachePartsTest() {}

    /**
     * Archives exact groups, reuses unchanged layers, restores real files and retains unrelated target contents.
     * @throws Exception if fixture allocation or real archive operations fail
     */
    @Test public void groupedPushReuseAndOverlayPull() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            Files.createDirectories(f.target.resolve("release/deps"));
            Files.writeString(f.target.resolve("release/deps/library"), "compiled λ");
            Files.writeString(f.target.resolve("release/deps/library.d"), "dependency report");
            requireThat(f.run("push", "repo:verified", "repo:previous", "codex"), "firstPush").isEqualTo(0);
            var first = f.scope.getJsonMapper().readTree(Files.readString(f.registry.root.resolve("last-manifest")));
            requireThat(first.path("layers").size(), "separateMetadataAndBuildGroups").isEqualTo(2);
            f.output.reset();
            requireThat(f.run("push", "repo:next", "repo:verified", "final"), "reusedPush").isEqualTo(0);
            requireThat(f.output.toString(), "unchangedArchivesReused").contains("reused_parts=2");
            Files.delete(f.target.resolve("release/deps/library"));
            Files.writeString(f.target.resolve("unrelated"), "retain me");
            requireThat(f.run("pull", "repo:next", "repo:next", "bootstrap"), "overlayRestore").isEqualTo(0);
            requireThat(Files.readString(f.target.resolve("release/deps/library")), "actualRestoredBytes").isEqualTo("compiled λ");
            requireThat(Files.readString(f.target.resolve("unrelated")), "unrelatedRetained").isEqualTo("retain me");
            requireThat(f.temporaryChildren(), "ownedTransferChildrenRemoved").isEqualTo(0L);
        }
    }

    /**
     * Carries prior hardlink owners across changed bytes, keeps symlinks opaque and uses Unicode code-point ordering.
     * @throws IOException if supported fixture filesystem operations fail
     */
    @Test public void ownershipHardlinksMetadataAndUnicode() throws IOException
    {
        try (Fixture f = new Fixture())
        {
            Path first = f.target.resolve("first"); Files.writeString(first, "one");
            Files.createLink(f.target.resolve("linked"), first);
            Files.createSymbolicLink(f.target.resolve("link"), Path.of("../outside"));
            Files.writeString(f.target.resolve("\ue000"), "bmp"); Files.writeString(f.target.resolve("😀"), "supplementary");
            Map<String, List<List<Object>>> initial = CargoCacheParts.collect(f.scope, f.target, "bwrap");
            Files.writeString(first, "changed");
            Map<String, List<List<Object>>> changed = CargoCacheParts.collect(f.scope, f.target, "codex");
            requireThat(changed.get("bwrap").stream().map(List::getFirst).toList(), "retainedPathOwners")
                .isEqualTo(initial.get("bwrap").stream().map(List::getFirst).toList());
            requireThat(changed.get("bwrap").getLast().getFirst(), "pythonCodePointOrder").isEqualTo("😀");
            requireThat(changed.get("bwrap").stream().filter(x -> x.getFirst().equals("link")).findFirst().orElseThrow().getLast(), "opaqueLinkTarget")
                .isEqualTo("../outside");
        }
    }

    /**
     * Preserves missing, unsupported, failed-command and invalid launcher statuses.
     * @throws Exception if fixture allocation or archive operations fail
     */
    @Test public void preservesFailuresWithoutPublication() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            requireThat(f.run("pull", "repo:missing", "repo:missing", "bootstrap"), "missingReference").isEqualTo(3);
            Files.writeString(f.registry.root.resolve("unsupported"), "{}");
            requireThat(f.run("pull", "repo:unsupported", "repo:unsupported", "bootstrap"), "unsupportedFormat").isEqualTo(4);
            f.registry.failBlobPush = 23;
            Files.writeString(f.target.resolve("artifact"), "contents");
            requireThat(f.run("push", "repo:never", "repo:missing", "codex"), "uploadFailureStatus").isEqualTo(23);
            requireThat(Files.exists(f.registry.root.resolve("never")), "noManifestBeforeAllBlobs").isFalse();
            requireThat(f.temporaryChildren(), "failedTransferChildrenRemoved").isEqualTo(0L);
            f.registry.failAll = 19;
            requireThat(f.run("pull", "repo:missing", "repo:missing", "bootstrap"), "authenticationNotAMiss").isEqualTo(19);
            f.environment.put("CARGO_CACHE_ORAS_COMMAND", "not json");
            requireThat(CargoCacheParts.run(f.scope, new String[]{"pull", "repo", "repo:missing", "repo:missing", f.target.toString(),
                f.temporary.toString()}, f.environment, f.out, f.err), "invalidLauncher").isEqualTo(2);
        }
    }

    /**
     * Refuses malformed stored ownership without publishing a manifest or escaping the command status boundary.
     * @throws IOException if independent fixture allocation or writes fail
     */
    @Test public void malformedOwnershipReturnsFailureWithoutPublication() throws IOException
    {
        try (Fixture f = new Fixture())
        {
            Files.writeString(f.target.resolve(CargoCacheParts.OWNERS), "{broken");
            requireThat(f.run("push", "repo:never", "repo:missing", "codex"), "malformedOwnership").isEqualTo(1);
            requireThat(Files.exists(f.registry.root.resolve("never")), "noMalformedPublication").isFalse();
            requireThat(f.temporaryChildren(), "noRetainedTransfer").isEqualTo(0L);
        }
    }

    /** Owns one actual filesystem registry fixture and independent transfer inputs. */
    private static final class Fixture implements AutoCloseable
    {
        /** Shared services owned by this method. */ private final DefaultToolingScope scope = new DefaultToolingScope();
        /** Owned fixture root. */ private final Path root;
        /** Idle Cargo output directory. */ private final Path target;
        /** Owned temporary transfer parent. */ private final Path temporary;
        /** In-process registry. */ private final Registry registry;
        /** Actual inherited environment, which supplies the tar search path. */ private final Map<String, String> environment = new HashMap<>(System.getenv());
        /** Captured progress. */ private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        /** Borrowed progress stream. */ private final PrintStream out = new PrintStream(output);
        /** Borrowed diagnostic stream. */ private final PrintStream err = new PrintStream(new ByteArrayOutputStream());
        /**
         * Counts retained transfers without retaining a directory descriptor.
         * @return transfer children
         * @throws IOException if enumeration fails
         */
        private long temporaryChildren() throws IOException
        {
            try (var children = Files.list(temporary)) { return children.count(); }
        }
        /**
         * Allocates only method-owned trees.
         * @throws IOException if allocation fails
         */
        private Fixture() throws IOException
        {
            root=Files.createTempDirectory("parts-");target=Files.createDirectory(root.resolve("target"));temporary=Files.createDirectory(root.resolve("temporary"));
            registry=new Registry(Files.createDirectory(root.resolve("registry")));
        }
        /**
         * Invokes the production operation with the in-process registry.
         * @param operation push or pull
         * @param reference publication or read reference
         * @param previous preceding snapshot
         * @param step current saving step
         * @return original production status
         */
        private int run(String operation,String reference,String previous,String step)
        {
            return CargoCacheParts.run(scope,new String[]{operation,"repo",reference,previous,target.toString(),temporary.toString(),"--step",step},environment,registry,out,err);
        }
        /**
         * Deletes only this method's registry, target and transient trees.
         * @throws IOException if owned cleanup fails
         */
        @Override public void close() throws IOException
        {
            scope.close();out.close();err.close();try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
        }
    }

    /** Answers the ORAS commands that the production helper issues, storing everything under one owned root. */
    private static final class Registry implements CargoCacheParts.Oras
    {
        /** Owned storage root. */ private final Path root;
        /** Status that every command returns instead of running, or zero. */ private int failAll;
        /** Status that blob pushes return instead of storing, or zero. */ private int failBlobPush;
        /**
         * Creates a registry over an owned root.
         * @param root owned storage root
         */
        private Registry(Path root) { this.root = root; }
        @Override public CargoCacheParts.Result run(List<String> arguments) throws IOException
        {
            if (failAll != 0) return new CargoCacheParts.Result(failAll, "", "unauthorized\n");
            String verb = arguments.get(0) + " " + arguments.get(1);
            String last = arguments.getLast();
            switch (verb)
            {
                case "manifest fetch" ->
                {
                    Path path = root.resolve(last.substring(last.lastIndexOf(':') + 1));
                    if (!Files.exists(path)) return new CargoCacheParts.Result(1, "", "MANIFEST_UNKNOWN\n");
                    return new CargoCacheParts.Result(0, "{\"content\":" + Files.readString(path) + ",\"digest\":\"sha256:fixture\"}", "");
                }
                case "blob push" ->
                {
                    if (failBlobPush != 0) return new CargoCacheParts.Result(failBlobPush, "", "fixture blob failure\n");
                    byte[] bytes = Files.readAllBytes(Path.of(last));
                    String digest = HexFormat.of().formatHex(CargoCacheKeys.digest().digest(bytes));
                    Files.write(root.resolve("blob-" + digest), bytes);
                    return new CargoCacheParts.Result(0, "{\"mediaType\":\"" + arguments.get(4) + "\",\"digest\":\"sha256:" + digest +
                        "\",\"size\":" + bytes.length + "}", "");
                }
                case "manifest push" ->
                {
                    Path input = Path.of(arguments.get(3));
                    Files.copy(input, root.resolve("last-manifest"), StandardCopyOption.REPLACE_EXISTING);
                    String reference = arguments.get(2);
                    for (String alias : reference.substring(reference.lastIndexOf(':') + 1).split(","))
                        Files.copy(input, root.resolve(alias), StandardCopyOption.REPLACE_EXISTING);
                    return new CargoCacheParts.Result(0, "published\n", "");
                }
                case "blob fetch" ->
                {
                    Files.copy(root.resolve("blob-" + last.substring(last.lastIndexOf(':') + 1)), Path.of(arguments.get(3)),
                        StandardCopyOption.REPLACE_EXISTING);
                    return new CargoCacheParts.Result(0, "", "");
                }
                default -> throw new IOException("Unexpected ORAS command: " + arguments);
            }
        }
    }
}
