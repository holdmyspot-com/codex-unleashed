package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.PayloadFiles;
import com.holdmyspot.codexunleashed.tooling.Sha256;
import com.holdmyspot.codexunleashed.tooling.V8Versions;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves checksum-verified V8 archive and binding overrides for package Cargo builds.
 */
public final class V8Resources
{
	private static final String ARCHIVE_VARIABLE = "RUSTY_V8_ARCHIVE";
	private static final String BINDING_VARIABLE = "RUSTY_V8_SRC_BINDING_PATH";
	private static final String PROFILE = "ptrcomp_sandbox_release";
	private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(120);

	/**
	 * Prevents construction.
	 */
	private V8Resources()
	{
	}

	/**
	 * Supplies explicit inputs for platform policy and artifact resolution.
	 *
	 * @param workspace the patched upstream checkout
	 * @param target the package target
	 * @param cacheRoot the managed artifact cache
	 * @param releases the release download base URI, ending in a slash
	 * @param environment the caller environment
	 */
	public record Request(Path workspace, PackageTarget target, Path cacheRoot, URI releases,
		Map<String, String> environment)
	{
		/**
		 * Validates required inputs and captures the supplied environment.
		 *
		 * @param workspace the patched upstream checkout
		 * @param target the package target
		 * @param cacheRoot the managed artifact cache
		 * @param releases the release download base URI, ending in a slash
		 * @param environment the caller environment
		 * @throws NullPointerException if any input or environment entry is null
		 */
		public Request
		{
			Objects.requireNonNull(workspace, "workspace");
			Objects.requireNonNull(target, "target");
			Objects.requireNonNull(cacheRoot, "cacheRoot");
			Objects.requireNonNull(releases, "releases");
			environment = Map.copyOf(environment);
		}
	}

	/**
	 * Retains complete caller overrides or source builds, otherwise downloads the declared artifact pair.
	 *
	 * @param request the explicit resource request
	 * @return environment additions, or an empty map when no generated overrides are needed
	 * @throws IOException if only one override is supplied or lockfile, download, or checksum validation fails
	 * @throws NullPointerException if {@code request} is null
	 */
	public static Map<String, String> resolve(Request request) throws IOException
	{
		Objects.requireNonNull(request, "request");
		if (request.target().isWindows() || Set.of("true", "1", "yes").
			contains(request.environment().getOrDefault("V8_FROM_SOURCE", "")))
			return Map.of();
		boolean archiveOverride = !request.environment().getOrDefault(ARCHIVE_VARIABLE, "").isEmpty();
		boolean bindingOverride = !request.environment().getOrDefault(BINDING_VARIABLE, "").isEmpty();
		if (archiveOverride && bindingOverride)
			return Map.of();
		if (archiveOverride || bindingOverride)
			throw new IOException("Cargo package builds need " + ARCHIVE_VARIABLE + " and " + BINDING_VARIABLE +
				" set together");

		String version = V8Versions.resolveLockfile(Files.readString(request.workspace().resolve("codex-rs/Cargo.lock")));
		String target = request.target().triple();
		Path cache = request.cacheRoot().toAbsolutePath().resolve("rusty-v8-" + version + "-" + target);
		Path archive = cache.resolve("librusty_v8_" + PROFILE + "_" + target + ".a.gz");
		Path binding = cache.resolve("src_binding_" + PROFILE + "_" + target + ".rs");
		Path checksums = cache.resolve("rusty_v8_" + PROFILE + "_" + target + ".sha256");
		URI release = request.releases().resolve("rusty-v8-v" + version + "/");
		ArtifactDownloads.download(release.resolve(checksums.getFileName().toString()), checksums, DOWNLOAD_TIMEOUT);
		Map<String, String> expected = V8ArtifactChecksums.read(checksums,
			Set.of(archive.getFileName().toString(), binding.getFileName().toString()));
		for (Path artifact : new Path[] {archive, binding})
			ensureArtifact(artifact, expected.get(artifact.getFileName().toString()),
				release.resolve(artifact.getFileName().toString()));
		return Map.of(ARCHIVE_VARIABLE, archive.toString(), BINDING_VARIABLE, binding.toString());
	}

	/**
	 * Reuses only matching cached bytes and deletes a failed replacement before reporting validation failure.
	 *
	 * @param artifact the cached artifact path
	 * @param expected the declared digest
	 * @param source the artifact provider URI
	 * @throws IOException if cache access, download, checksum validation, or invalid-file removal fails
	 */
	private static void ensureArtifact(Path artifact, String expected, URI source) throws IOException
	{
		if (Files.isRegularFile(artifact) && Sha256.digest(artifact).equals(expected))
			return;
		PayloadFiles.delete(artifact);
		ArtifactDownloads.download(source, artifact, DOWNLOAD_TIMEOUT);
		if (Files.isRegularFile(artifact) && Sha256.digest(artifact).equals(expected))
			return;
		PayloadFiles.delete(artifact);
		throw new IOException("Codex-built V8 artifact " + artifact + " failed checksum validation");
	}
}
