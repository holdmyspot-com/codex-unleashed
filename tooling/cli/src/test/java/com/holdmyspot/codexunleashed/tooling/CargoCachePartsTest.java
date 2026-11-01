package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Verifies real grouped tar transfers against an isolated authenticated ORAS process fixture. */
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
            var first = f.scope.getJsonMapper().readTree(Files.readString(f.registry.resolve("last-manifest")));
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
     * Preserves missing, unsupported, authenticated subprocess and invalid CLI failure statuses.
     * @throws Exception if fixture or subprocess collection fails
     */
    @Test public void preservesFailuresWithoutPublication() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            requireThat(f.run("pull", "repo:missing", "repo:missing", "bootstrap"), "missingReference").isEqualTo(3);
            Files.writeString(f.registry.resolve("unsupported"), "{}");
            requireThat(f.run("pull", "repo:unsupported", "repo:unsupported", "bootstrap"), "unsupportedFormat").isEqualTo(4);
            f.environment.put("CACHE_FIXTURE_FAIL_BLOB", "23");
            Files.writeString(f.target.resolve("artifact"), "contents");
            requireThat(f.run("push", "repo:never", "repo:missing", "codex"), "uploadFailureStatus").isEqualTo(23);
            requireThat(Files.exists(f.registry.resolve("never")), "noManifestBeforeAllBlobs").isFalse();
            requireThat(f.temporaryChildren(), "failedTransferChildrenRemoved").isEqualTo(0L);
            f.environment.put("CACHE_FIXTURE_AUTH", "wrong");
            requireThat(f.run("pull", "repo:missing", "repo:missing", "bootstrap"), "authenticationNotAMiss").isEqualTo(19);
            f.environment.put("CARGO_CACHE_ORAS_COMMAND", "not json");
            requireThat(f.run("pull", "repo:missing", "repo:missing", "bootstrap"), "invalidLauncher").isEqualTo(2);
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
            requireThat(Files.exists(f.registry.resolve("never")), "noMalformedPublication").isFalse();
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
        /** Controlled fixture registry. */ private final Path registry;
        /** Actual inherited environment plus explicit fixture authentication. */ private final Map<String, String> environment = new HashMap<>(System.getenv());
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
         * Allocates only method-owned trees and exact JSON process argv, with no registry mutations outside the fixture.
         * @throws IOException if allocation fails
         */
        private Fixture() throws IOException
        {
            root=Files.createTempDirectory("parts-");target=Files.createDirectory(root.resolve("target"));temporary=Files.createDirectory(root.resolve("temporary"));registry=Files.createDirectory(root.resolve("registry"));
            String javaExecutable=Path.of(System.getProperty("java.home"),"bin",java.io.File.separatorChar=='\\'?"java.exe":"java").toString();
            String classPath = System.getProperty("java.class.path");
            if (Registry.class.getModule().isNamed())
                classPath += java.io.File.pathSeparator + System.getProperty("jdk.module.path");
            environment.put("CARGO_CACHE_ORAS_COMMAND", scope.getJsonMapper().writeValueAsString(List.of(javaExecutable,"-Xmx64m","-cp",classPath,Registry.class.getName())));
            environment.put("CACHE_FIXTURE_ROOT",registry.toString());environment.put("CACHE_FIXTURE_AUTH","original-auth");
        }
        /**
         * Invokes the actual production operation against an explicit external ORAS process boundary.
         * @param operation push or pull
         * @param reference publication or read reference
         * @param previous preceding snapshot
         * @param step current saving step
         * @return original production status
         */
        private int run(String operation,String reference,String previous,String step)
        {
            return CargoCacheParts.run(scope,new String[]{operation,"repo",reference,previous,target.toString(),temporary.toString(),"--step",step},environment,out,err);
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

    /** Controlled ORAS-compatible process fixture; authentication and all writes remain inside its supplied root. */
    public static final class Registry
    {
        /** Prevents construction. */ private Registry() {}
        /**
         * Implements only the production helper's documented ORAS operations using a local fixture directory.
         * @param arguments exact ORAS argv
         * @throws Exception if fixture input or storage fails
         */
        public static void main(String[] arguments) throws Exception
        {
            if (!"original-auth".equals(System.getenv("CACHE_FIXTURE_AUTH"))) { System.err.println("unauthorized");System.exit(19); }
            Path root=Path.of(System.getenv("CACHE_FIXTURE_ROOT"));
            try(var scope=new DefaultToolingScope())
            {
                if(arguments[0].equals("manifest")&&arguments[1].equals("fetch"))
                {
                    String reference=arguments[arguments.length-1];Path path=root.resolve(reference.substring(reference.lastIndexOf(':')+1));
                    if(!Files.exists(path)){System.err.println("MANIFEST_UNKNOWN");System.exit(1);}
                    var content=scope.getJsonMapper().readTree(Files.readString(path));
                    System.out.println(scope.getJsonMapper().writeValueAsString(Map.of("content",content,"digest","sha256:fixture")));return;
                }
                if(arguments[0].equals("blob")&&arguments[1].equals("push"))
                {
                    String fail=System.getenv("CACHE_FIXTURE_FAIL_BLOB");if(fail!=null){System.err.println("fixture blob failure");System.exit(Integer.parseInt(fail));}
                    Path input=Path.of(arguments[arguments.length-1]);byte[] bytes=Files.readAllBytes(input);
                    String digest=HexFormat.of().formatHex(CargoCacheKeys.digest().digest(bytes));
                    Files.copy(input,root.resolve("blob-"+digest),StandardCopyOption.REPLACE_EXISTING);
                    System.out.println(scope.getJsonMapper().writeValueAsString(Map.of("mediaType",arguments[4],"digest","sha256:"+digest,"size",bytes.length)));return;
                }
                if(arguments[0].equals("manifest")&&arguments[1].equals("push"))
                {
                    Path input=Path.of(arguments[3]);Files.copy(input,root.resolve("last-manifest"),StandardCopyOption.REPLACE_EXISTING);
                    String ref=arguments[2];String[] aliases=ref.substring(ref.lastIndexOf(':')+1).split(",");
                    for(String alias:aliases)Files.copy(input,root.resolve(alias),StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("published");return;
                }
                if(arguments[0].equals("blob")&&arguments[1].equals("fetch"))
                {
                    String digest=arguments[arguments.length-1];digest=digest.substring(digest.lastIndexOf(':')+1);
                    Files.copy(root.resolve("blob-"+digest),Path.of(arguments[3]),StandardCopyOption.REPLACE_EXISTING);return;
                }
                throw new IOException("Unexpected fixture ORAS operation");
            }
        }
    }
}
