package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.UpstreamAutoRelease;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies release selection and publication policy through the Java command boundary.
 */
public final class UpstreamAutoReleaseTest
{
	/**
	 * Creates the workflow policy test suite.
	 */
	public UpstreamAutoReleaseTest()
	{
	}
	/**
	 * A release-list file and package file represent GitHub's release snapshot and the workflow's built asset. The Java
	 * command appends the original publication marker to the release-notes file.
	 *
	 * @throws IOException if the isolated fixture cannot be accessed
	 */
	@Test
	public void preservesOriginalPublicationMarker() throws IOException
	{
		Path root = Files.createTempDirectory("upstream-auto-release-");
		try
		{
			Path assets = Files.createDirectory(root.resolve("assets"));
			Files.writeString(assets.resolve("codex-package-é.tar.gz"), "asset bytes\n");
			Path releases = Files.writeString(root.resolve("releases.json"), "[]");
			Path notes = Files.writeString(root.resolve("notes.md"), "Release notes\n");
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
			try (DefaultToolingScope scope = new DefaultToolingScope();
				PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8))
			{
				String[] arguments = {"publication", "--fork-releases", releases.toString(), "--tag", "rust-v0.167.0+1",
					"--fork-commit", "a".repeat(40), "--upstream-tag", "rust-v0.167.0", "--upstream-commit",
					"b".repeat(40), "--tag-commit", "", "--assets-directory", assets.toString(), "--notes", notes.toString()};
				int status = UpstreamAutoRelease.run(scope, arguments, out, err);
				String marker;
				try (InputStream fixture = UpstreamAutoReleaseTest.class.getResourceAsStream(
					"/com/holdmyspot/codexunleashed/tooling/test/upstream-auto-release/publication-marker.txt"))
				{
					requireThat(fixture, "fixture").isNotNull();
					marker = new String(fixture.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
				}
				requireThat(Files.readString(notes), "notes").isEqualTo("Release notes\n\n" + marker + "\n");
				requireThat(status, "status").isEqualTo(0);
				requireThat(output.toString(StandardCharsets.UTF_8), "output").contains("phase=create");
				requireThat(diagnostics.toString(StandardCharsets.UTF_8), "diagnostics").isEmpty();
			}
		}
		finally
		{
			deleteTree(root);
		}
	}

		/**
		 * Python code-point ordering is retained for asset names beyond the basic multilingual plane.
		 *
		 * @throws IOException if the fixture cannot be accessed
		 */
	@Test
	public void preservesUnicodeAssetMarker() throws IOException
	{
		Path root = Files.createTempDirectory("upstream-auto-release-unicode-");
		try
		{
			Path assets = Files.createDirectory(root.resolve("assets"));
			Files.writeString(assets.resolve("\ue000"), "bmp asset\n");
			Files.writeString(assets.resolve(new String(Character.toChars(0x10000))), "supplementary asset\n");
			Path releases = Files.writeString(root.resolve("releases.json"), "[]");
			Path notes = Files.writeString(root.resolve("notes.md"), "Release notes\n");
			Result result = invoke(publicationArguments(releases, assets, notes, "a".repeat(40), "b".repeat(40), ""));
			try (InputStream fixture = UpstreamAutoReleaseTest.class.getResourceAsStream(
				"/com/holdmyspot/codexunleashed/tooling/test/upstream-auto-release/unicode-publication-marker.txt"))
			{
				requireThat(fixture, "unicode_marker_fixture").isNotNull();
				String marker = new String(fixture.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
				requireThat(Files.readString(notes), "unicode_marker_notes").isEqualTo("Release notes\n\n" + marker + "\n");
			}
			requireThat(result.status, "unicode_marker_status").isEqualTo(0);
			requireThat(result.output, "unicode_marker_phase").contains("phase=create");
		}
		finally
		{
			deleteTree(root);
		}
	}

		/**
		 * Published candidates are no-ops, while drafts reserve their build number.
		 *
		 * @throws IOException if the release fixtures cannot be accessed
		 */
	@Test
	public void selectsPublishedCandidateAndDraftBuild() throws IOException
	{
		Path root = Files.createTempDirectory("upstream-auto-release-selection-");
		try
		{
			Path upstream = Files.writeString(root.resolve("upstream.json"), "[{\"tag_name\":\"rust-v0.167.0\"}]");
			Path fork = Files.writeString(root.resolve("fork.json"),
				"[{\"tag_name\":\"rust-v0.167.0+4\",\"draft\":true}] ");
			Result draft = invoke("detect", "--upstream-releases", upstream.toString(), "--fork-releases", fork.toString());
			requireThat(draft.status, "draft_status").isEqualTo(0);
			requireThat(draft.output, "draft_selection").contains(
				"build=5" + System.lineSeparator() + "tag=rust-v0.167.0+5");
			Files.writeString(fork, "[{\"tag_name\":\"rust-v0.167.0+4\",\"draft\":false}]");
			Result published = invoke("detect", "--upstream-releases", upstream.toString(),
				"--fork-releases", fork.toString());
			requireThat(published.status, "published_status").isEqualTo(0);
			requireThat(published.output, "published_selection").contains("action=noop");
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Existing refs reserve build numbers and a workflow retry keeps its original selection.
	 * @throws IOException if the release fixtures cannot be accessed
	 */
	@Test
	public void reservesOrphanTagsAndPreservesRetryIdentity() throws IOException
	{
		Path root = Files.createTempDirectory("upstream-auto-release-orphans-");
		try
		{
			Path upstream = Files.writeString(root.resolve("upstream.json"), "[{\"tag_name\":\"rust-v0.167.0\"}]");
			Path fork = Files.writeString(root.resolve("fork.json"), "[]");
			Path tags = Files.writeString(root.resolve("tags.json"),
				"[\"rust-v0.167.0+1\",\"rust-v0.167.0+3\",\"rust-v0.167.0+23-beta\",\"rust-v0.167.1+99\"]");
			String[] detect = {"detect", "--upstream-releases", upstream.toString(), "--fork-releases", fork.toString(),
				"--fork-tags", tags.toString()};
			Result selected = invoke(detect);
			requireThat(selected.status, "orphan_detection_status").isEqualTo(0);
			requireThat(selected.output, "orphan_detection_action").contains("action=release");
			requireThat(selected.output, "orphan_detection_tag").contains("tag=rust-v0.167.0+4");

			Path decision = Files.writeString(root.resolve("decision.txt"), selected.output);
			Path identity = root.resolve("identity.json");
			String forkCommit = "a".repeat(40);
			Result saved = invoke("save-identity", "--decision-output", decision.toString(), "--run-id", "123",
				"--fork-commit", forkCommit, "--upstream-commit", "b".repeat(40), "--identity", identity.toString());
			requireThat(saved.status, "orphan_identity_save_status").isEqualTo(0);

			Files.writeString(tags, "[\"rust-v0.167.0+4\"]");
			Files.writeString(fork, "[{\"tag_name\":\"rust-v0.167.0+4\",\"draft\":true}]");
			Result restored = invoke("restore-identity", "--identity", identity.toString(), "--run-id", "123",
				"--fork-commit", forkCommit);
			requireThat(restored.status, "retry_restore_status").isEqualTo(0);
			requireThat(restored.output, "retry_tag").contains("tag=rust-v0.167.0+4");

			Files.writeString(fork, "[{\"tag_name\":\"rust-v0.167.0+6\",\"draft\":true}]");
			Result draft = invoke(detect);
			requireThat(draft.status, "tag_and_draft_status").isEqualTo(0);
			requireThat(draft.output, "tag_and_draft_selection").contains("tag=rust-v0.167.0+7");
			Files.writeString(fork, "[{\"tag_name\":\"rust-v0.167.0+6\",\"draft\":false}]");
			Result published = invoke(detect);
			requireThat(published.status, "tag_and_published_status").isEqualTo(0);
			requireThat(published.output, "tag_and_published_action").contains("action=noop");
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Build selection and identity persistence retain decimal precision above primitive integer ranges.
	 *
	 * @throws IOException if the release fixtures cannot be accessed
	 */
	@Test
	public void preservesArbitrarySizeBuildNumbers() throws IOException
	{
		Path root = Files.createTempDirectory("upstream-auto-release-big-build-");
		try
		{
			Path upstream = Files.writeString(root.resolve("upstream.json"), "[{\"tag_name\":\"rust-v0.167.0\"}]");
			Path fork = root.resolve("fork.json");
			for (String existing : new String[] {"2147483647", "2147483648",
				"123456789012345678901234567890123456789012345678901234567890"})
			{
				String next = new BigInteger(existing).add(BigInteger.ONE).toString();
				Files.writeString(fork, "[{\"tag_name\":\"rust-v0.167.0+" + existing + "\",\"draft\":true}]");
				Result selected = invoke("detect", "--upstream-releases", upstream.toString(),
					"--fork-releases", fork.toString());
				requireThat(selected.status, "large_build_status").isEqualTo(0);
				requireThat(selected.output, "large_build_selection").contains("build=" + next + System.lineSeparator() +
					"tag=rust-v0.167.0+" + next + System.lineSeparator() + "release_name=0.167.0+" + next);
			}
			String build = "123456789012345678901234567890123456789012345678901234567890";
			String decision = "action=release\nreason=selected\nversion=0.167.0\nbuild=" + build +
				"\ntag=rust-v0.167.0+" + build + "\nrelease_name=0.167.0+" + build +
				"\nupstream_tag=rust-v0.167.0\n";
			Path decisionFile = Files.writeString(root.resolve("decision.txt"), decision);
			Path identity = root.resolve("identity.json");
			Result saved = invoke("save-identity", "--decision-output", decisionFile.toString(), "--run-id", "123",
				"--fork-commit", "a".repeat(40), "--upstream-commit", "b".repeat(40), "--identity", identity.toString());
			requireThat(saved.status, "large_identity_save_status").isEqualTo(0);
			requireThat(Files.readString(identity), "large_identity_numeric_value").contains("\"build\":" + build);
			Result restored = invoke("restore-identity", "--identity", identity.toString(), "--run-id", "123",
				"--fork-commit", "a".repeat(40));
			requireThat(restored.status, "large_identity_restore_status").isEqualTo(0);
			requireThat(restored.output, "large_identity_restore_build").contains("build=" + build + System.lineSeparator() +
				"tag=rust-v0.167.0+" + build + System.lineSeparator() + "release_name=0.167.0+" + build);
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Identity validation rejects nonpositive, nonintegral, textual, and narrowed build values.
	 *
	 * @throws IOException if the identity fixture cannot be accessed
	 */
	@Test
	public void rejectsInvalidBuildIdentityNumbers() throws IOException
	{
		Path root = Files.createTempDirectory("upstream-auto-release-invalid-build-");
		try
		{
			for (String buildNode : new String[] {"0", "-1", "1.5", "\"1\"", "4294967297"})
			{
				Path identity = Files.writeString(root.resolve("identity.json"), identityJson(buildNode, "1"));
				Result restored = invoke("restore-identity", "--identity", identity.toString(), "--run-id", "123",
					"--fork-commit", "a".repeat(40));
				requireThat(restored.status, "invalid_build_status").isEqualTo(2);
			}
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Publication retry and release notes retain a very large build tag and URL.
	 *
	 * @throws IOException if the publication fixtures cannot be accessed
	 */
	@Test
	public void preservesBigBuildPublicationNotes() throws IOException
	{
		Path root = Files.createTempDirectory("upstream-auto-release-big-publication-");
		try
		{
			String build = "123456789012345678901234567890123456789012345678901234567890";
			String tag = "rust-v0.167.0+" + build;
			Path assets = Files.createDirectory(root.resolve("assets"));
			Files.writeString(assets.resolve("codex-package-test.tar.gz"), "original package");
			Path releases = Files.writeString(root.resolve("releases.json"), "[]");
			Path notes = Files.writeString(root.resolve("notes.md"), "Release notes\n");
			String[] args = publicationArguments(releases, assets, notes, "a".repeat(40), "b".repeat(40), "", tag);
			Result created = invoke(args);
			requireThat(created.status, "large_publication_create_status").isEqualTo(0);
			requireThat(created.output, "large_publication_create_tag").contains("tag=" + tag);
			String body = Files.readString(notes).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
			Files.writeString(releases, "[{\"tag_name\":\"" + tag + "\",\"target_commitish\":\"" + "a".repeat(40) +
				"\",\"draft\":true,\"prerelease\":true,\"body\":\"" + body + "\",\"assets\":[]}]");
			Result resumed = invoke(args);
			requireThat(resumed.status, "large_publication_resume_status").isEqualTo(0);
			requireThat(resumed.output, "large_publication_resume_tag").contains("tag=" + tag);
			Path readme = Files.writeString(root.resolve("README.md"), "# Queue\n\n1. [one.patch](vendor/one.patch)\n");
			Result rendered = invoke("notes", "--version", "0.167.0", "--build", build, "--upstream-tag", "rust-v0.167.0",
				"--upstream-commit", "b".repeat(40), "--fork-commit", "a".repeat(40), "--readme", readme.toString());
			requireThat(rendered.status, "large_notes_status").isEqualTo(0);
			requireThat(rendered.output, "large_notes_heading").contains("# Codex Unleashed 0.167.0+" + build);
			requireThat(rendered.output, "large_notes_tag_url").contains("download/rust-v0.167.0%2B" + build + "/install.sh");
			Result badPatch = invoke("patches", "--readme", Files.writeString(root.resolve("bad-readme.md"),
				"# Queue\n\n2147483648. [bad.patch](vendor/bad.patch)\n").toString());
			requireThat(badPatch.status, "bounded_patch_index_status").isEqualTo(2);
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Builds a release identity JSON document with the supplied numeric representation.
	 *
	 * @param buildNode JSON source for the build value
	 * @param tagBuild build value used in the matching tag fields
	 * @return identity document
	 */
	private static String identityJson(String buildNode, String tagBuild)
	{
		return "{\"format_version\":1,\"run_id\":\"123\",\"fork_commit\":\"" + "a".repeat(40) +
			"\",\"upstream_commit\":\"" + "b".repeat(40) + "\",\"decision\":{\"action\":\"release\"," +
			"\"reason\":\"selected\",\"version\":\"0.167.0\",\"build\":" + buildNode +
			",\"tag\":\"rust-v0.167.0+" + tagBuild + "\",\"release_name\":\"0.167.0+" + tagBuild +
			"\",\"upstream_tag\":\"rust-v0.167.0\"}}";
	}

		/**
		 * Saved release identity is pinned to both the workflow run and fork commit and rejects schema drift.
		 *
		 * @throws IOException if the identity fixture cannot be accessed
		 */
	@Test
	public void restoresOnlyTheOriginalIdentity() throws IOException
	{
		Path root = Files.createTempDirectory("upstream-auto-release-identity-");
		try
		{
			Path identity = Files.writeString(root.resolve("identity.json"),
				"{\"format_version\":1,\"run_id\":\"123\",\"fork_commit\":\"" + "a".repeat(40) +
				"\",\"upstream_commit\":\"" + "b".repeat(40) + "\",\"decision\":{\"action\":\"release\"," +
				"\"reason\":\"selected\",\"version\":\"0.167.0\",\"build\":1,\"tag\":\"rust-v0.167.0+1\"," +
				"\"release_name\":\"0.167.0+1\",\"upstream_tag\":\"rust-v0.167.0\"}}");
			Result restored = invoke("restore-identity", "--identity", identity.toString(), "--run-id", "123",
				"--fork-commit", "a".repeat(40));
			requireThat(restored.status, "identity_status").isEqualTo(0);
			requireThat(restored.output, "pinned_upstream_commit").contains("upstream_commit=" + "b".repeat(40));
			Result changedRun = invoke("restore-identity", "--identity", identity.toString(), "--run-id", "124",
				"--fork-commit", "a".repeat(40));
			requireThat(changedRun.status, "changed_run_status").isEqualTo(1);
			Result changedCommit = invoke("restore-identity", "--identity", identity.toString(), "--run-id", "123",
				"--fork-commit", "c".repeat(40));
			requireThat(changedCommit.status, "changed_fork_commit_status").isEqualTo(1);
			String driftedIdentity = Files.readString(identity).
				replace("\"format_version\":1", "\"extra\":true,\"format_version\":1");
			Files.writeString(identity, driftedIdentity);
			Result changedSchema = invoke("restore-identity", "--identity", identity.toString(), "--run-id", "123",
				"--fork-commit", "a".repeat(40));
			requireThat(changedSchema.status, "changed_schema_status").isEqualTo(2);
		}
		finally
		{
			deleteTree(root);
		}
	}

		/**
		 * Patch listing, changelog notes, and failure summaries keep their workflow protocol bytes.
		 *
		 * @throws IOException if the rendering fixtures cannot be accessed
		 */
	@Test
	public void rendersPatchQueueNotesAndFailureSummary() throws IOException
	{
		Path root = Files.createTempDirectory("upstream-auto-release-output-");
		try
		{
			Path readme = Files.writeString(root.resolve("README.md"),
				"# Queue\n\n1. [one.patch](vendor/one.patch)\n2. [two.patch](vendor/two.patch)\n");
			Result patches = invoke("patches", "--readme", readme.toString());
			requireThat(patches.status, "patches_status").isEqualTo(0);
			requireThat(patches.output, "patches_output").isEqualTo(
				"patches/vendor/one.patch patches/vendor/two.patch" + System.lineSeparator());
			Path changelog = Files.writeString(root.resolve("CHANGELOG.md"),
				"## Upstream rust-v0.167.0\n\n- changed one thing\n\n## Upstream rust-v0.166.0\n- older\n");
			Result notes = invoke("notes", "--version", "0.167.0", "--build", "2", "--upstream-tag", "rust-v0.167.0",
				"--upstream-commit", "a".repeat(40), "--fork-commit", "b".repeat(40), "--readme", readme.toString(),
				"--changelog", changelog.toString());
			requireThat(notes.status, "notes_status").isEqualTo(0);
			requireThat(notes.output, "notes_output").contains("## Changes\n\n- changed one thing\n\n## Patches applied");
			requireThat(notes.output, "notes_patch_order").contains("1. [`vendor/one.patch`]");
			Result summary = invoke("summary", "--upstream-tag", "rust-v0.167.0", "--failing", "patch 2",
				"--log-url", "https://example.invalid/run/1");
			requireThat(summary.status, "summary_status").isEqualTo(0);
			requireThat(summary.output, "summary_output").contains("patch 2**\n\nLog: https://example.invalid/run/1");
		}
		finally
		{
			deleteTree(root);
		}
	}

		/**
		 * A draft with the original marker resumes upload, and published incomplete assets fail closed.
		 *
		 * @throws IOException if the publication fixtures cannot be accessed
		 */
	@Test
	public void resumesDraftAndRejectsIncompleteRelease() throws IOException
	{
		Path root = Files.createTempDirectory("upstream-auto-release-retry-");
		try
		{
			Path assets = Files.createDirectory(root.resolve("assets"));
			Files.writeString(assets.resolve("codex-package-test.tar.gz"), "original package");
			Path releases = Files.writeString(root.resolve("releases.json"), "[]");
			Path notes = Files.writeString(root.resolve("notes.md"), "Release notes\n");
			String[] args = publicationArguments(releases, assets, notes, "a".repeat(40),
				"b".repeat(40), "");
			Result created = invoke(args);
			requireThat(created.status, "create_status").isEqualTo(0);
			requireThat(created.output, "create_phase").contains("phase=create");
			String body = Files.readString(notes).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
			Files.writeString(releases, "[{\"tag_name\":\"rust-v0.167.0+1\",\"target_commitish\":\"" + "a".repeat(40) +
				"\",\"draft\":true,\"prerelease\":true,\"body\":\"" + body + "\",\"assets\":[]}]");
			Result resumed = invoke(args);
			requireThat(resumed.status, "resume_status").isEqualTo(0);
			requireThat(resumed.output, "resume_phase").contains("phase=upload");
			Files.writeString(releases, "[{\"tag_name\":\"rust-v0.167.0+1\",\"target_commitish\":\"" + "a".repeat(40) +
			"\",\"draft\":false,\"prerelease\":false,\"body\":\"" + body + "\",\"assets\":[]}]");
			String[] incomplete = publicationArguments(releases, assets, notes, "a".repeat(40),
				"b".repeat(40), "a".repeat(40));
			Result rejected = invoke(incomplete);
			requireThat(rejected.status, "incomplete_published_status").isEqualTo(1);
			requireThat(rejected.diagnostics, "incomplete_published_diagnostic").contains("assets are incomplete");
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Builds command arguments for a publication scenario.
	 *
	 * @param releases operation input
	 * @param assets operation input
	 * @param notes operation input
	 * @param forkCommit operation input
	 * @param upstreamCommit operation input
	 * @param tagCommit operation input
	 * @return operation result
	 */
	private static String[] publicationArguments(Path releases, Path assets, Path notes, String forkCommit,
		String upstreamCommit, String tagCommit)
	{
		return publicationArguments(releases, assets, notes, forkCommit, upstreamCommit, tagCommit, "rust-v0.167.0+1");
	}

	/**
	 * Builds publication arguments with the exact supplied fork release tag.
	 *
	 * @param releases release-list file
	 * @param assets release asset directory
	 * @param notes release-notes file
	 * @param forkCommit target fork commit
	 * @param upstreamCommit source upstream commit
	 * @param tagCommit existing tag target
	 * @param tag fork release tag
	 * @return publication arguments
	 */
	private static String[] publicationArguments(Path releases, Path assets, Path notes, String forkCommit,
		String upstreamCommit, String tagCommit, String tag)
	{
		return new String[] {"publication", "--fork-releases", releases.toString(), "--tag", tag,
			"--fork-commit", forkCommit, "--upstream-tag", "rust-v0.167.0", "--upstream-commit", upstreamCommit,
			"--tag-commit", tagCommit, "--assets-directory", assets.toString(), "--notes", notes.toString()};
	}

	/**
	 * Runs the command with isolated streams and shared tooling services.
	 *
	 * @param arguments operation input
	 * @return operation result
	 * @throws IOException if the operation fails
	 */
	private static Result invoke(String... arguments) throws IOException
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
		try (DefaultToolingScope scope = new DefaultToolingScope();
			PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8))
		{
			int status = UpstreamAutoRelease.run(scope, arguments, out, err);
			return new Result(status, output.toString(StandardCharsets.UTF_8), diagnostics.toString(StandardCharsets.UTF_8));
		}
	}

	/**
	 * Captured process-like result of one command invocation.
	 *
	 * @param status record component
	 * @param output record component
	 * @param diagnostics record component
	 */
	private record Result(int status, String output, String diagnostics)
	{
	}

	/**
	 * Deletes only the temporary directory owned by the calling test.
	 *
	 * @param root operation input
	 * @throws IOException if the operation fails
	 */
	private static void deleteTree(Path root) throws IOException
	{
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.delete(path);
		}
	}
}
