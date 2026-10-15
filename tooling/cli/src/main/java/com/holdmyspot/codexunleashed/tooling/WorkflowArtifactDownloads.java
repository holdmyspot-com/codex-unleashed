package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MappingIterator;
import tools.jackson.databind.json.JsonMapper;

/** Downloads exact named workflow artifacts with authenticated transport and SHA-256 verification. */
final class WorkflowArtifactDownloads
{
	/** Prevents construction. */
	private WorkflowArtifactDownloads()
	{
	}

	/**
	 * Downloads, verifies, and extracts an unexpired artifact without replacing existing files.
	 *
	 * @param repository GitHub owner/name
	 * @param runId workflow run identifier
	 * @param name exact artifact name
	 * @param destination extraction directory
	 * @param runner metadata command boundary
	 * @throws IOException if transport, verification, extraction, or cleanup fails
	 * @throws IllegalArgumentException if an identifier is invalid or metadata is incomplete
	 */
	static void download(String repository, String runId, String name, Path destination, CommandRunner runner)
		throws IOException
	{
		GitHubRepositories.validate(repository);
		long run = Long.parseLong(runId);
		if (run <= 0 || name.isBlank())
			throw new IllegalArgumentException("A positive workflow run ID and nonempty artifact name are required");
		String base = "repos/" + repository + "/actions/";
		String response = runner.run(List.of("gh", "api",
			base + "runs/" + run + "/artifacts?per_page=100", "--paginate"));
		JsonNode artifact;
		try (MappingIterator<JsonNode> pages = JsonMapper.builder().build().readerFor(JsonNode.class).
			readValues(response))
		{
			artifact = select(pages.readAll(), name);
		}
		long id = Long.parseLong(artifact.required("id").asString());
		if (id <= 0)
			throw new IOException("Artifact has an invalid ID: " + name);
		String digest = artifact.required("digest").asString();
		try (ArtifactDownloadWorkspace scratch = ArtifactDownloadWorkspace.create())
		{
			Path archive = scratch.path().resolve("artifact.zip");
			downloadArchive(base + "artifacts/" + id + "/zip", archive);
			if (!digest.equalsIgnoreCase("sha256:" + Sha256.digest(archive)))
				throw new IOException("Artifact SHA-256 verification failed: " + name);
			Path extracted = Files.createDirectory(scratch.path().resolve("extracted"));
			extract(archive, extracted);
			publish(extracted, destination.toAbsolutePath().normalize());
		}
	}

	/**
	 * Selects one exact unexpired artifact across the CLI's paginated response.
	 *
	 * @param pages API response pages
	 * @param name exact artifact name
	 * @return selected metadata
	 * @throws IOException if the name is missing, expired, or ambiguous
	 */
	private static JsonNode select(Iterable<JsonNode> pages, String name) throws IOException
	{
		JsonNode selected = null;
		for (JsonNode page : pages)
		{
			for (JsonNode artifact : page.required("artifacts"))
			{
				if (name.equals(artifact.required("name").asString()))
				{
					if (selected != null || artifact.required("expired").asBoolean())
						throw new IOException("Artifact is expired or ambiguous: " + name);
					selected = artifact;
				}
			}
		}
		if (selected == null)
			throw new IOException("Workflow artifact was not found: " + name);
		return selected;
	}

	/**
	 * Streams authenticated ZIP bytes to owned storage without text decoding.
	 *
	 * @param endpoint immutable artifact ZIP endpoint
	 * @param archive output file
	 * @throws IOException if starting, waiting, or downloading fails
	 */
	private static void downloadArchive(String endpoint, Path archive) throws IOException
	{
		ProcessBuilder builder = SystemCommands.createBuilder(List.of("gh", "api", endpoint)).
			redirectOutput(archive.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT);
		try (Process process = builder.start())
		{
			process.getOutputStream().close();
			int status = process.waitFor();
			if (status != 0)
				throw new IOException("GitHub artifact download failed with status " + status);
		}
		catch (InterruptedException failure)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while downloading GitHub artifact " + endpoint, failure);
		}
	}

	/**
	 * Extracts into a new owned directory, rejecting traversal and duplicate files before publication.
	 *
	 * @param archive verified ZIP archive
	 * @param root new extraction root
	 * @throws IOException if archive contents or paths are invalid
	 */
	private static void extract(Path archive, Path root) throws IOException
	{
		try (ZipFile index = new ZipFile(archive.toFile()); InputStream input = Files.newInputStream(archive);
			ZipInputStream zip = new ZipInputStream(input))
		{
			int entries = 0;
			for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
			{
				Path target = root.resolve(entry.getName()).normalize();
				if (entry.getName().contains("\\") || !target.startsWith(root) || target.equals(root))
					throw new IOException("Artifact contains an unsafe ZIP path: " + entry.getName());
				if (entry.isDirectory())
					Files.createDirectories(target);
				else
				{
					Files.createDirectories(target.getParent());
					try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW))
					{
						zip.transferTo(output);
					}
				}
				zip.closeEntry();
				++entries;
			}
			if (entries != index.size())
				throw new IOException("Artifact ZIP entries do not match its directory");
		}
	}

	/**
	 * Copies verified members into the caller's destination without following existing child links.
	 *
	 * @param source validated extraction tree
	 * @param destination caller's output root
	 * @throws IOException if destination entries conflict or copying fails
	 */
	private static void publish(Path source, Path destination) throws IOException
	{
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) &&
			!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Artifact destination is not a directory: " + destination);
		Files.createDirectories(destination);
		try (Stream<Path> paths = Files.walk(source))
		{
			for (Path entry : paths.toList())
			{
				Path target = destination.resolve(source.relativize(entry));
				if (Files.isDirectory(entry))
				{
					if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) &&
						!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))
						throw new IOException("Artifact destination directory conflicts: " + target);
					Files.createDirectories(target);
				}
				else
					Files.copy(entry, target);
			}
		}
	}
}
