package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.ArtifactJson;
import com.holdmyspot.codexunleashed.tooling.ArtifactPaths;
import com.holdmyspot.codexunleashed.tooling.Sha256;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Describes release artifacts and the active patch queue without requiring the builder's upstream checkout.
 */
public final class ReleaseManifest
{
	private static final Pattern TARGET_WHITESPACE = Pattern.compile(
		"\\A[\\s\\x{001c}-\\x{001f}]+|[\\s\\x{001c}-\\x{001f}]+\\z", Pattern.UNICODE_CHARACTER_CLASS);

	/**
	 * Prevents construction.
	 */
	private ReleaseManifest()
	{
	}

	/**
	 * Holds release identity and build metadata without changing supplied spellings.
	 *
	 * @param patchRepository the downstream repository
	 * @param patchedTag the release tag
	 * @param buildNumber the vendor build number
	 * @param buildDate the supplied date, or empty for the clock's current UTC second
	 * @param builderType the builder identity
	 * @param supportedTargets the comma-separated target inventory
	 */
	public record Release(String patchRepository, String patchedTag, String buildNumber, String buildDate,
		String builderType, String supportedTargets)
	{
		/**
		 * Requires explicit nonnull metadata, allowing empty optional values.
		 *
		 * @param patchRepository the downstream repository
		 * @param patchedTag the release tag
		 * @param buildNumber the vendor build number
		 * @param buildDate the supplied date
		 * @param builderType the builder identity
		 * @param supportedTargets the comma-separated target inventory
		 * @throws NullPointerException if any argument is null
		 */
		public Release
		{
			Objects.requireNonNull(patchRepository, "patchRepository");
			Objects.requireNonNull(patchedTag, "patchedTag");
			Objects.requireNonNull(buildNumber, "buildNumber");
			Objects.requireNonNull(buildDate, "buildDate");
			Objects.requireNonNull(builderType, "builderType");
			Objects.requireNonNull(supportedTargets, "supportedTargets");
		}
	}

	/**
	 * Holds the upstream identity supplied by the caller.
	 *
	 * @param repository the upstream repository
	 * @param tag the upstream tag
	 * @param commit the source commit metadata
	 */
	public record Upstream(String repository, String tag, String commit)
	{
		/**
		 * Requires explicit upstream metadata.
		 *
		 * @param repository the upstream repository
		 * @param tag the upstream tag
		 * @param commit the source commit metadata
		 * @throws NullPointerException if any argument is null
		 */
		public Upstream
		{
			Objects.requireNonNull(repository, "repository");
			Objects.requireNonNull(tag, "tag");
			Objects.requireNonNull(commit, "commit");
		}
	}

	/**
	 * Holds publishing workflow metadata independently of artifact source paths.
	 *
	 * @param path the workflow path
	 * @param ref the source reference
	 * @param sha the source commit
	 * @param runId the build run identity
	 * @param runAttempt the build run attempt
	 */
	public record Workflow(String path, String ref, String sha, String runId, String runAttempt)
	{
		/**
		 * Requires explicit workflow metadata, allowing empty local-build values.
		 *
		 * @param path the workflow path
		 * @param ref the source reference
		 * @param sha the source commit
		 * @param runId the build run identity
		 * @param runAttempt the build run attempt
		 * @throws NullPointerException if any argument is null
		 */
		public Workflow
		{
			Objects.requireNonNull(path, "path");
			Objects.requireNonNull(ref, "ref");
			Objects.requireNonNull(sha, "sha");
			Objects.requireNonNull(runId, "runId");
			Objects.requireNonNull(runAttempt, "runAttempt");
		}
	}

	/**
	 * Names the release's checksum and attestation artifacts.
	 *
	 * @param consolidatedChecksums the checksum artifact path
	 * @param attestationBundle the attestation artifact path, or empty when absent
	 */
	public record Verification(String consolidatedChecksums, String attestationBundle)
	{
		/**
		 * Requires explicit verification artifact names.
		 *
		 * @param consolidatedChecksums the checksum artifact path
		 * @param attestationBundle the attestation artifact path
		 * @throws NullPointerException if either argument is null
		 */
		public Verification
		{
			Objects.requireNonNull(consolidatedChecksums, "consolidatedChecksums");
			Objects.requireNonNull(attestationBundle, "attestationBundle");
		}
	}

	/**
	 * Assigns source directories, output, and metadata for one manifest.
	 *
	 * @param releaseDirectory the release artifact directory
	 * @param patchRepository the repository containing patches/
	 * @param output the manifest output
	 * @param release the release metadata
	 * @param upstream the upstream metadata
	 * @param workflow the workflow metadata
	 * @param verification the verification artifact names
	 */
	public record Request(Path releaseDirectory, Path patchRepository, Path output, Release release,
		Upstream upstream, Workflow workflow, Verification verification)
	{
		/**
		 * Requires explicit paths and metadata before any file access.
		 *
		 * @param releaseDirectory the release artifact directory
		 * @param patchRepository the patch repository
		 * @param output the manifest output
		 * @param release the release metadata
		 * @param upstream the upstream metadata
		 * @param workflow the workflow metadata
		 * @param verification the verification artifact names
		 * @throws NullPointerException if any argument is null
		 */
		public Request
		{
			Objects.requireNonNull(releaseDirectory, "releaseDirectory");
			Objects.requireNonNull(patchRepository, "patchRepository");
			Objects.requireNonNull(output, "output");
			Objects.requireNonNull(release, "release");
			Objects.requireNonNull(upstream, "upstream");
			Objects.requireNonNull(workflow, "workflow");
			Objects.requireNonNull(verification, "verification");
		}
	}

	/**
	 * Hashes artifacts and active patches, then writes a schema-two ASCII manifest.
	 * The output itself is excluded even when a previous manifest already exists.
	 *
	 * @param request the explicit manifest inputs
	 * @param clock the build-date clock, used only when no date is supplied
	 * @throws IOException if artifact access, hashing, or manifest writing fails
	 * @throws NullPointerException if either argument is null
	 */
	public static void generate(Request request, Clock clock) throws IOException
	{
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(clock, "clock");
		Path releaseDirectory = request.releaseDirectory().toFile().getCanonicalFile().toPath();
		Path patchRepository = request.patchRepository().toFile().getCanonicalFile().toPath();
		Path output = request.output().toFile().getCanonicalFile().toPath();
		Release release = request.release();
		String buildDate = release.buildDate();
		if (buildDate.isEmpty())
			buildDate = clock.instant().truncatedTo(ChronoUnit.SECONDS).toString();
		List<Map<String, Object>> artifacts = new ArrayList<>();
		for (Path file : files(releaseDirectory, false))
		{
			if (!Files.isRegularFile(file) || file.toRealPath().equals(output))
				continue;
			artifacts.add(Map.of("path", ArtifactPaths.relativeName(releaseDirectory, file), "sha256", Sha256.digest(file),
				"size_bytes", Files.size(file)));
		}
		List<Map<String, String>> patches = new ArrayList<>();
		for (Path patch : files(patchRepository.resolve("patches"), true))
			patches.add(Map.of("path", ArtifactPaths.relativeName(patchRepository, patch), "sha256", Sha256.digest(patch)));
		List<String> targets = Arrays.stream(release.supportedTargets().split(",", -1)).
			map(value -> TARGET_WHITESPACE.matcher(value).replaceAll("")).filter(value -> !value.isEmpty()).toList();
		Upstream upstream = request.upstream();
		Workflow workflow = request.workflow();
		Verification verification = request.verification();
		Map<String, Object> manifest = Map.of("schema_version", 2,
			"release", Map.of("patch_repository", release.patchRepository(), "patched_tag", release.patchedTag(),
				"build_number", release.buildNumber(), "build_date", buildDate, "builder_type", release.builderType(),
				"supported_targets", targets),
			"upstream", Map.of("repository", upstream.repository(), "tag", upstream.tag(), "commit", upstream.commit()),
			"workflow", Map.of("path", workflow.path(), "ref", workflow.ref(), "sha", workflow.sha(),
				"run_id", workflow.runId(), "run_attempt", workflow.runAttempt()),
			"patches", patches, "artifacts", artifacts,
			"verification", Map.of("consolidated_checksums", verification.consolidatedChecksums(),
				"attestation_bundle", verification.attestationBundle()));
		String serialized = ArtifactJson.format(manifest);
		Files.createDirectories(output.getParent());
		Files.writeString(output, serialized, StandardCharsets.US_ASCII);
	}

	/**
	 * Enumerates descendants without following directory symlinks and sorts their filesystem components.
	 *
	 * @param root the artifact or patch directory
	 * @param patches indicates whether only patch names are selected
	 * @return the sorted descendants, or an empty list for an absent directory
	 * @throws IOException if enumeration fails
	 */
	private static List<Path> files(Path root, boolean patches) throws IOException
	{
		if (!Files.isDirectory(root))
			return List.of();
		try (Stream<Path> files = Files.walk(root))
		{
			return files.filter(path -> !path.equals(root)).
				filter(path -> !patches || path.getFileName().toString().endsWith(".patch")).
				sorted(ArtifactPaths.comparator(root)).toList();
		}
	}
}
