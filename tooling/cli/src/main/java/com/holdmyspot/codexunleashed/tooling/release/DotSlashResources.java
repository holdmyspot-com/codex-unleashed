package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.PayloadFiles;
import com.holdmyspot.codexunleashed.tooling.Sha256;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves declared DotSlash resources through verified archives and raw executable extraction.
 */
public final class DotSlashResources
{
	private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);
	private static final Set<PosixFilePermission> EXECUTE_PERMISSIONS = PosixFilePermissions.fromString("--x--x--x");

	/**
	 * Prevents construction.
	 */
	private DotSlashResources()
	{
	}

	/**
	 * Describes a target-specific executable resource resolution operation.
	 *
	 * @param target the selected package target
	 * @param manifest the explicit DotSlash manifest
	 * @param label the resource name
	 * @param cacheKey the resource cache directory name
	 * @param executableName the extracted executable filename
	 * @param missingOk whether an absent platform is optional
	 * @param cacheRoot the explicit managed resource cache root
	 */
	public record Request(PackageTarget target, Path manifest, String label, String cacheKey, String executableName,
		boolean missingOk, Path cacheRoot)
	{
		/**
		 * Validates required resource paths and metadata.
		 *
		 * @param target the selected package target
		 * @param manifest the explicit DotSlash manifest
		 * @param label the resource name
		 * @param cacheKey the resource cache directory name
		 * @param executableName the extracted executable filename
		 * @param missingOk whether an absent platform is optional
		 * @param cacheRoot the explicit managed resource cache root
		 * @throws NullPointerException if any reference argument is null
		 */
		public Request
		{
			Objects.requireNonNull(target, "target");
			Objects.requireNonNull(manifest, "manifest");
			Objects.requireNonNull(label, "label");
			Objects.requireNonNull(cacheKey, "cacheKey");
			Objects.requireNonNull(executableName, "executableName");
			Objects.requireNonNull(cacheRoot, "cacheRoot");
		}
	}

	/**
	 * Reuses only size-and-digest-verified archives, downloads when needed, and refreshes the extracted executable.
	 *
	 * @param request the resource resolution inputs
	 * @return the executable, or permitted target platform absence
	 * @throws IOException if manifest selection, verification, download, extraction, or permission updates fail
	 * @throws NullPointerException if {@code request} is null
	 */
	public static Optional<Path> fetch(Request request) throws IOException
	{
		Objects.requireNonNull(request, "request");
		Optional<DotSlashManifest.Artifact> selected = DotSlashManifest.select(request.manifest(), request.target(),
			request.label(), request.missingOk());
		if (selected.isEmpty())
			return Optional.empty();
		DotSlashManifest.Artifact artifact = selected.orElseThrow();
		Path cache = request.cacheRoot().resolve(request.cacheKey());
		Path archive = cache.resolve(archiveFilename(artifact.url()));
		boolean valid = false;
		if (Files.isRegularFile(archive))
		{
			valid = mismatch(archive, artifact, request.label()).isEmpty();
			if (!valid)
				PayloadFiles.delete(archive);
		}
		if (!valid)
		{
			ArtifactDownloads.download(artifact.url(), archive, DOWNLOAD_TIMEOUT);
			Optional<String> failure = mismatch(archive, artifact, request.label());
			if (failure.isPresent())
			{
				PayloadFiles.delete(archive);
				throw new IOException(failure.orElseThrow());
			}
		}
		Path executable = cache.resolve(request.executableName());
		ArchiveMembers.copy(archive, artifact.format(), artifact.member(), executable, request.label());
		if (!request.target().isWindows())
			makeExecutable(executable);
		return Optional.of(executable);
	}

	/**
	 * Reports integrity mismatches while retaining ordinary filesystem read failures.
	 *
	 * @param archive the cached archive
	 * @param artifact the declared archive metadata
	 * @param label the resource name
	 * @return a mismatch description, or absence when verified
	 * @throws IOException if size or digest reading fails
	 */
	private static Optional<String> mismatch(Path archive, DotSlashManifest.Artifact artifact, String label)
		throws IOException
	{
		long size = Files.size(archive);
		if (!BigInteger.valueOf(size).equals(artifact.size()))
			return Optional.of(label + " archive " + archive + " has size " + size + ", expected " + artifact.size());
		String digest = Sha256.digest(archive);
		if (!digest.equals(artifact.digest()))
			return Optional.of(label + " archive " + archive + " has sha256 " + digest + ", expected " + artifact.digest());
		return Optional.empty();
	}

	/**
	 * Retains the URL path's encoded filename rather than decoding it as a filesystem path.
	 *
	 * @param url the provider URL
	 * @return the cache archive filename
	 * @throws IOException if the URL has no usable filename
	 */
	private static String archiveFilename(URI url) throws IOException
	{
		String path = url.getRawPath();
		if (path == null)
			path = url.getRawSchemeSpecificPart();
		Path filename = Path.of(path).getFileName();
		if (filename == null || filename.toString().isEmpty())
			throw new IOException("Unable to determine archive filename from " + url);
		return filename.toString();
	}

	/**
	 * Adds all POSIX execute bits without replacing other extracted file permissions.
	 *
	 * @param executable the extracted executable
	 * @throws IOException if POSIX permissions cannot be read or written
	 */
	private static void makeExecutable(Path executable) throws IOException
	{
		PosixFileAttributeView view = Files.getFileAttributeView(executable, PosixFileAttributeView.class);
		if (view == null)
			throw new IOException("Unix resource executable permissions require POSIX filesystem support: " + executable);
		Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
		permissions.addAll(view.readAttributes().permissions());
		permissions.addAll(EXECUTE_PERMISSIONS);
		view.setPermissions(permissions);
	}
}
