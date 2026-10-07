package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.commons.io.file.PathUtils;
import org.apache.commons.io.file.StandardDeleteOption;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies source audit decisions against real Git trees, patches, and independent indexes.
 */
public final class SourceProvenanceTest
{
	/**
	 * Creates the source audit tests.
	 */
	public SourceProvenanceTest()
	{
	}

	/**
	 * Accepts exact patch application, ignored patch additions, and excluded untracked build data.
	 * Preserves the user's index and compares the producer's tree with a committed reference tree.
	 *
	 * @throws IOException if fixture setup or the audit fails
	 */
	@Test
	public void verifiesExactPatchedSource() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			for (String path : new String[]{"nested/target/cache", ".cargo-home/cache", "nested/.zig-cache/cache",
				"source-provenance.json", "scratch.index"})
			{
				Path file = fixture.checkout.resolve(path);
				Files.createDirectories(file.getParent());
				Files.writeString(file, "excluded build data");
			}
			String status = fixture.git("status", "--porcelain");
			byte[] index = Files.readAllBytes(fixture.checkout.resolve(".git/index"));
			SourceProvenance.generate(fixture.request(), fixture.temporary, fixture.environment);
			assertEquals(Files.readAllBytes(fixture.checkout.resolve(".git/index")), index);
			assertEquals(fixture.git("status", "--porcelain"), status);
			String serialized = Files.readString(fixture.output);
			assertTrue(serialized.chars().allMatch(value -> value < 128));
			assertTrue(serialized.endsWith("\n"));
			JsonNode document = JsonMapper.builder().build().readTree(serialized);
			assertTrue(document.get("source_verified").booleanValue());
			assertEquals(document.get("schema_version").intValue(), 2);
			assertEquals(document.get("upstream").get("commit").stringValue(), fixture.baseline);
			assertEquals(document.get("patched_tree").stringValue(), fixture.patchedTree);
			assertEquals(document.get("changed_paths").size(), 2);
			assertEquals(document.get("changed_paths").get(0).stringValue(), "ignored.txt");
			assertEquals(document.get("changed_paths").get(1).stringValue(), "source.txt");
			assertEquals(document.get("application_order").get(0).stringValue(), "patches/é.patch");
			assertEquals(document.get("patches").get(0).get("sha256").stringValue(), fixture.patchDigest);
			assertEquals(document.get("workflow").get("run_id").stringValue(), "7");
			assertEquals(document.get("applied_diff_sha256").stringValue(), fixture.diffDigest);
			fixture.assertTemporaryEmpty();
		}
	}

	/**
	 * Replays dependent patches in filename order even when their directory entries have another order.
	 *
	 * @throws IOException if fixture setup or auditing fails
	 */
	@Test
	public void replaysDependentPatchesInOrder() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.git("add", "--force", "ignored.txt");
			fixture.commit("first source state");
			Files.writeString(fixture.checkout.resolve("source.txt"), "second source state\n");
			fixture.commit("second source state");
			fixture.patchedTree = fixture.git("rev-parse", "HEAD^{tree}").strip();
			Path second = fixture.patchRepo.resolve("patches/order.patch");
			Files.writeString(second, fixture.git("format-patch", "-1", "--stdout", "--binary"));
			Path first = Files.createDirectories(fixture.patchRepo.resolve("patches/order")).resolve("first.patch");
			Files.move(fixture.patchRepo.resolve("patches/é.patch"), first);
			fixture.git("reset", "--hard", fixture.baseline);
			fixture.git("apply", first.toString());
			fixture.git("apply", second.toString());
			SourceProvenance.generate(fixture.request(), fixture.temporary, fixture.environment);
			JsonNode document = JsonMapper.builder().build().readTree(Files.readString(fixture.output));
			assertEquals(document.get("patched_tree").stringValue(), fixture.patchedTree);
			assertEquals(document.get("application_order").get(0).stringValue(), "patches/order/first.patch");
			assertEquals(document.get("application_order").get(1).stringValue(), "patches/order.patch");
			fixture.assertTemporaryEmpty();
		}
	}

	/**
	 * Accepts a clean baseline without a patch directory and emits empty patch and changed-path arrays.
	 *
	 * @throws IOException if fixture setup or auditing fails
	 */
	@Test
	public void verifiesUnpatchedBaseline() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.git("reset", "--hard", fixture.baseline);
			Files.delete(fixture.checkout.resolve("ignored.txt"));
			Files.delete(fixture.patchRepo.resolve("patches/é.patch"));
			Files.delete(fixture.patchRepo.resolve("patches"));
			SourceProvenance.generate(fixture.request(), fixture.temporary, fixture.environment);
			JsonNode document = JsonMapper.builder().build().readTree(Files.readString(fixture.output));
			assertEquals(document.get("patched_tree").stringValue(), fixture.git("rev-parse", "HEAD^{tree}").strip());
			assertEquals(document.get("application_order").size(), 0);
			assertEquals(document.get("patches").size(), 0);
			assertEquals(document.get("changed_paths").size(), 0);
			fixture.assertTemporaryEmpty();
		}
	}

	/**
	 * Rejects an extra source edit, preserves a previous report, and cleans the independent indexes.
	 *
	 * @throws IOException if fixture access fails
	 */
	@Test
	public void rejectsUnexplainedChanges() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			Files.writeString(fixture.checkout.resolve("unexplained.txt"), "unexpected source");
			Files.writeString(fixture.output, "previous report");
			byte[] index = Files.readAllBytes(fixture.checkout.resolve(".git/index"));
			IOException failure = expectThrows(IOException.class, () ->
				SourceProvenance.generate(fixture.request(), fixture.temporary, fixture.environment));
			assertTrue(failure.getMessage().contains("Source audit failed"));
			assertTrue(failure.getMessage().contains("unexplained.txt"));
			assertEquals(Files.readString(fixture.output), "previous report");
			assertEquals(Files.readAllBytes(fixture.checkout.resolve(".git/index")), index);
			fixture.assertTemporaryEmpty();
		}
	}

	/**
	 * Rejects a symlink patch before accepting its content as an in-tree patch.
	 *
	 * @throws IOException if fixture access or symbolic link creation fails
	 */
	@Test
	public void rejectsEscapingPatches() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			Path outside = Files.writeString(fixture.root.resolve("outside.patch"), "untrusted patch");
			Files.createSymbolicLink(fixture.patchRepo.resolve("patches/escape.patch"), outside);
			IOException failure = expectThrows(IOException.class, () ->
				SourceProvenance.generate(fixture.request(), fixture.temporary, fixture.environment));
			assertTrue(failure.getMessage().contains("Patch escapes patches/"));
			assertFalse(Files.exists(fixture.output));
			fixture.assertTemporaryEmpty();
		}
	}

	/**
	 * Owns a real isolated repository, reference patch commit, and audit output paths.
	 */
	private static final class Fixture implements AutoCloseable
	{
		private final Path root;
		private final Path checkout;
		private final Path patchRepo;
		private final Path output;
		private final Path temporary;
		private final Map<String, String> environment;
		private String baseline;
		private String patchedTree;
		private String patchDigest;
		private String diffDigest;

		/**
		 * Assigns paths and isolates Git's configuration and templates.
		 *
		 * @param root the owned fixture root
		 * @throws IOException if fixture directories cannot be created
		 */
		private Fixture(Path root) throws IOException
		{
			this.root = root;
			checkout = Files.createDirectory(root.resolve("checkout"));
			patchRepo = Files.createDirectory(root.resolve("patch repo"));
			output = root.resolve("provenance.json");
			temporary = Files.createDirectory(root.resolve("temporary"));
			Path config = Files.writeString(root.resolve("empty-config"), "");
			Path template = Files.createDirectory(root.resolve("empty-template"));
			environment = Map.of("GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", config.toString(),
				"GIT_TEMPLATE_DIR", template.toString());
		}

		/**
		 * Constructs baseline and patch commits, then applies the patch without changing the real index.
		 *
		 * @return the complete fixture
		 * @throws IOException if Git or fixture access fails
		 */
		private static Fixture create() throws IOException
		{
			Path root = Files.createTempDirectory("source-audit-fixture-");
			try
			{
				Fixture fixture = new Fixture(root);
				fixture.git("init", "--initial-branch=main");
				Files.writeString(fixture.checkout.resolve("source.txt"), "before\n");
				Files.writeString(fixture.checkout.resolve(".gitignore"), "ignored.txt\ntarget/\n");
				fixture.commit("baseline");
				fixture.baseline = fixture.git("rev-parse", "HEAD").strip();
				Files.writeString(fixture.checkout.resolve("source.txt"), "after\n");
				Files.writeString(fixture.checkout.resolve("ignored.txt"), "added by patch\n");
				fixture.git("add", "--force", "ignored.txt");
				fixture.commit("patched");
				fixture.patchedTree = fixture.git("rev-parse", "HEAD^{tree}").strip();
				Path patch = Files.createDirectories(fixture.patchRepo.resolve("patches")).resolve("é.patch");
				Files.writeString(patch, fixture.git("format-patch", "-1", "--stdout", "--binary"));
				fixture.patchDigest = fixture.hashFile(patch);
				SystemCommands.DigestResult diff = SystemCommands.digest(List.of("git", "-C", fixture.checkout.toString(),
					"diff", "--binary", "--no-ext-diff", fixture.baseline, "HEAD"), fixture.root, fixture.temporary,
					fixture.environment);
				assertEquals(diff.status(), 0, diff.stderr());
				fixture.diffDigest = diff.stdoutSha256();
				fixture.git("reset", "--hard", fixture.baseline);
				fixture.git("apply", patch.toString());
				return fixture;
			}
			catch (IOException | RuntimeException | AssertionError failure)
			{
				try
				{
					delete(root);
				}
				catch (IOException cleanupFailure)
				{
					failure.addSuppressed(cleanupFailure);
				}
				throw failure;
			}
		}

		/**
		 * Independently measures the patch's raw bytes with the standard digest API.
		 *
		 * @param patch the patch path
		 * @return the independent SHA-256
		 * @throws IOException if the patch cannot be read
		 */
		private String hashFile(Path patch) throws IOException
		{
			try
			{
				return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(patch)));
			}
			catch (NoSuchAlgorithmException failure)
			{
				throw new AssertionError("Java platform must supply SHA-256", failure);
			}
		}

		/**
		 * Creates the production request with explicit metadata.
		 *
		 * @return the source audit request
		 */
		private SourceProvenance.Request request()
		{
			return new SourceProvenance.Request(checkout, patchRepo, output,
				new SourceProvenance.Metadata("openai/codex", "rust-v0.160.0", "build-release.yml", "workflow-sha", "7"));
		}

		/**
		 * Runs Git with direct arguments and fixture-owned configuration.
		 *
		 * @param arguments the Git arguments
		 * @return the unmodified stdout
		 * @throws IOException if Git fails
		 */
		private String git(String... arguments) throws IOException
		{
			List<String> command = new ArrayList<>(List.of("git", "-C", checkout.toString()));
			command.addAll(Arrays.asList(arguments));
			SystemCommands.Result result = SystemCommands.capture(command, root, temporary, environment);
			if (result.status() != 0)
				throw new IOException("Git fixture failed: " + result.stderr());
			return result.stdout();
		}

		/**
		 * Commits the current fixture with explicit identity and signing policy.
		 *
		 * @param message the commit message
		 * @throws IOException if Git fails
		 */
		private void commit(String message) throws IOException
		{
			git("add", ".");
			git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "-c", "commit.gpgsign=false",
				"commit", "-qm", message);
		}

		/**
		 * Requires all source audit and command captures to have been removed.
		 *
		 * @throws IOException if the temporary directory cannot be inspected
		 */
		private void assertTemporaryEmpty() throws IOException
		{
			try (Stream<Path> paths = Files.list(temporary))
			{
				assertEquals(paths.count(), 0L);
			}
		}

		/**
		 * Removes the owned fixture tree.
		 *
		 * @throws IOException if cleanup fails
		 */
		@Override
		public void close() throws IOException
		{
			delete(root);
		}

		/**
		 * Deletes a fixture without following symbolic links.
		 *
		 * @param root the owned root
		 * @throws IOException if deletion fails
		 */
		private static void delete(Path root) throws IOException
		{
			PathUtils.deleteDirectory(root, new LinkOption[]{LinkOption.NOFOLLOW_LINKS},
				StandardDeleteOption.OVERRIDE_READ_ONLY);
		}
	}
}
