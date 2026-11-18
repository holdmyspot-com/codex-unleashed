package com.holdmyspot.codexunleashed.tooling;

import java.util.stream.Stream;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Authenticates published evidence before fetching and executing the recorded recipe. */
public final class ReleaseReproductionSource
{
	/** Borrowed JSON services. */
	private final ToolingScope scope;
	/** Explicit small-process boundary. */
	private final SystemCommands.Capture capture;
	/** Explicit current process settings. */
	private final ReleaseManifest.Environment environment;
	/** Caller-owned diagnostics. */
	private final PrintStream diagnostics;

	/**
	 * Contains authenticated immutable recipe inputs, without operational recorded paths.
	 *
	 * @param manifestDigest manifest byte digest
	 * @param target selected target
	 * @param forkCommit recorded fork commit
	 * @param upstreamCommit upstream base commit
	 * @param upstreamTag upstream release tag
	 * @param sourceCommit resulting source commit
	 * @param version numeric release version
	 * @param buildNumber arbitrary precision numeric build number
	 * @param archiveDigest expected compressed archive SHA256
	 * @param patches ordered raw patch paths
	 */
	public record Release(String manifestDigest, String target, String forkCommit, String upstreamCommit,
		String upstreamTag, String sourceCommit, String version, BigInteger buildNumber, String archiveDigest,
		List<String> patches)
	{
		/**
		 * Retains the patch order immutably.
		 *
		 * @param manifestDigest authenticated digest
		 * @param target native target
		 * @param forkCommit recorded fork commit
		 * @param upstreamCommit recorded upstream commit
		 * @param upstreamTag recorded upstream tag
		 * @param sourceCommit recorded patched commit
		 * @param version release version
		 * @param buildNumber decimal build number
		 * @param archiveDigest expected archive digest
		 * @param patches ordered patch paths
		 */
		public Release
		{
			patches = List.copyOf(patches);
		}
	}

	/**
	 * Retains explicit verification services.
	 *
	 * @param scope borrowed JSON services
	 * @param capture small process boundary
	 * @param environment current settings
	 * @param diagnostics caller-owned diagnostics
	 */
	public ReleaseReproductionSource(ToolingScope scope, SystemCommands.Capture capture,
		ReleaseManifest.Environment environment, PrintStream diagnostics)
	{
		this.scope = scope;
		this.capture = capture;
		this.environment = environment;
		this.diagnostics = diagnostics;
	}

	/**
	 * Authenticates raw manifest bytes before reading any recorded ref.
	 *
	 * @param manifest published manifest
	 * @param target selected supported target
	 * @return authenticated immutable inputs
	 * @throws IOException if fixed-policy authentication fails
	 */
	public Release authenticate(Path manifest, String target) throws IOException
	{
		if (!ReleaseManifestSchema.TARGETS.contains(target))
			throw new IllegalArgumentException("Unsupported reproduction target: " + target);
		ReleaseManifestSchema.manifest(ReleaseManifestSchema.read(scope, manifest));
		String before = Sha256.digest(manifest);
		verifyCommand(List.of("authenticate", "--manifest", manifest.toString()));
		if (!before.equals(Sha256.digest(manifest)))
			throw new IOException("Manifest changed during authentication");
		JsonNode value = ReleaseManifestSchema.read(scope, manifest);
		ReleaseManifestSchema.manifest(value);
		String expected = "";
		for (JsonNode artifact : value.path("artifacts"))
			if (ReleaseManifestSchema.packageName(target).equals(text(artifact, "name")))
				expected = text(artifact, "sha256");
		List<String> patches = new ArrayList<>();
		for (JsonNode patch : value.path("patches"))
			patches.add(text(patch, "path"));
		return new Release(before, target, text(value, "fork_commit"), text(value.path("upstream"), "commit"),
			text(value.path("upstream"), "tag"), text(value.path("source"), "commit"),
			text(value.path("release"), "version"), value.path("release").path("build_number").bigIntegerValue(),
			expected, patches);
	}

	/**
	 * Fetches fixed repositories and replays the authenticated ordered queue.
	 *
	 * @param release authenticated recipe
	 * @param state owned attempt
	 * @param manifest published manifest
	 * @param workspace live fresh source directory
	 * @throws IOException if fetching or exact source verification fails
	 */
	public void reconstruct(Release release, ReleaseReproductionState state, Path manifest, Path workspace)
		throws IOException
	{
		Path fork = state.root().resolve("fork_checkout");
		git(fork, "init");
		git(fork, "fetch", "--no-tags", "https://github.com/holdmyspot-com/codex-unleashed.git",
			release.forkCommit());
		git(fork, "checkout", "--detach", release.forkCommit());
		JsonNode value = ReleaseManifestSchema.read(scope, manifest);
		ReleaseManifestSource verifier = new ReleaseManifestSource(scope, capture, environment);
		if (!ReleaseManifestSchema.same(value.path("patches"), verifier.patches(fork, release.forkCommit())))
			throw new IOException("Recorded raw patch inventory differs");
		Files.createDirectories(workspace);
		try (Stream<Path> children = Files.list(workspace))
		{
			if (children.findAny().isPresent())
				throw new IOException("Prepared workspace must be empty");
		}
		git(workspace, "init");
		git(workspace, "fetch", "--no-tags", "https://github.com/openai/codex.git", release.upstreamCommit(),
			"refs/tags/" + release.upstreamTag() + ":refs/tags/" + release.upstreamTag());
		git(workspace, "checkout", "--detach", release.upstreamCommit());
		Files.writeString(workspace.resolve(".git/info/attributes"), "*.snap -whitespace\n*.patch -whitespace\n");
		for (String patch : release.patches())
			git(workspace, "-c", "core.longpaths=true", "-c", "user.name=Gili Tzabari", "-c",
				"user.email=cowwoc2020@gmail.com", "am", "--committer-date-is-author-date",
				fork.resolve(patch).toString());
		// The release committed the lockfile's workspace package versions after replaying the queue; the recorded
		// commit, tree and digest only match the same alignment commit.
		WorkspaceLockAlignment.alignAndCommit(scope, capture, environment.temporaryDirectory(), environment.variables(),
			workspace);
		verifier.validate(value, fork, workspace);
		state.writeMarker(scope, workspace.resolve(".git/reproduction_owner.json"));
	}

	/**
	 * Reauthenticates the release, workspace and all assets before fetched code may execute.
	 *
	 * @param state owned attempt
	 * @param releaseDirectory current complete release directory
	 * @param workspace current source directory
	 * @return authenticated recipe inputs
	 * @throws IOException if authentication, identity or ownership differs
	 */
	public Release verifyPrepared(ReleaseReproductionState state, Path releaseDirectory, Path workspace)
		throws IOException
	{
		Path manifest = releaseDirectory.resolve("release-manifest.json");
		Release release = authenticate(manifest, state.target());
		if (!release.manifestDigest().equals(state.manifestDigest()))
			throw new IOException("Prepared manifest digest differs");
		state.checkMarker(scope, workspace.resolve(".git/reproduction_owner.json"));
		try
		{
			new ReleaseManifestSource(scope, capture, environment).validate(
				ReleaseManifestSchema.read(scope, manifest), state.root().resolve("fork_checkout"), workspace);
		}
		catch (IOException | IllegalArgumentException failure)
		{
			throw new IOException("source_mismatch: " + failure.getMessage(), failure);
		}
		verifyCommand(List.of("verify", "--release-dir", releaseDirectory.toString(), "--patch-repo",
			state.root().resolve("fork_checkout").toString(), "--upstream-source", workspace.toString(),
			"--verify-attestations"));
		return release;
	}

	/**
	 * Executes fixed-policy verification without mixing success output into prepare's state output.
	 *
	 * @param arguments literal operation arguments
	 * @throws IOException if the required operation cannot complete
	 */
	private void verifyCommand(List<String> arguments) throws IOException
	{
		try (PrintStream discarded = new PrintStream(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8))
		{
			int status = ReleaseManifest.run(scope, arguments.toArray(String[]::new), discarded, diagnostics,
				capture, environment);
			if (status != 0)
				throw new IOException("Independent release verification rejected " + arguments.getFirst());
		}
	}

	/**
	 * Executes a bounded Git identity or replay operation using literal arguments.
	 *
	 * @param directory current process working directory
	 * @param arguments literal operation arguments
	 * @throws IOException if the required operation cannot complete
	 */
	private void git(Path directory, String... arguments) throws IOException
	{
		List<String> command = new ArrayList<>(List.of("git"));
		command.addAll(List.of(arguments));
		SystemCommands.Result result = capture.capture(command, directory, environment.temporaryDirectory(),
			environment.variables());
		if (result.status() != 0)
			throw new IOException("Source Git operation failed: " + arguments[0] + ": " + result.stderr());
	}

	/**
	 * Reads validated JSON string data without coercion.
	 *
	 * @param value JSON value to inspect
	 * @param field required string field name
	 * @return required string value
	 */
	private static String text(JsonNode value, String field)
	{
		return ReleaseManifestSchema.text(value.path(field));
	}
}
