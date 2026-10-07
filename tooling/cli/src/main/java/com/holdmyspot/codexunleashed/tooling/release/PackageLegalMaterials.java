package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.CommandRunner;
import com.holdmyspot.codexunleashed.tooling.PayloadFiles;
import com.holdmyspot.codexunleashed.tooling.PayloadDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bundles the supplied offline product documents and resolved third-party Cargo payloads.
 */
public final class PackageLegalMaterials
{
	private static final List<String> DOCUMENTS = List.of("LICENSE.md", "licenses/openai-codex/LICENSE-APACHE-2.0",
		"docs/LICENSE.html", "docs/terms.html", "docs/privacy.html");

	/**
	 * Prevents construction.
	 */
	private PackageLegalMaterials()
	{
	}

	/**
	 * Copies offline documents and prioritizes prepared licenses over an optional workspace metadata fallback.
	 * A supplied prepared directory must contain THIRD_PARTY_NOTICES.md; invalid prepared input causes failure.
	 *
	 * @param repository the project repository containing the supplied documents
	 * @param packageDirectory the package output directory
	 * @param preparedLicenses the optional prepared Cargo license directory
	 * @param workspace the optional upstream workspace root
	 * @param metadata the locked Cargo metadata command boundary
	 * @throws IOException if documents, prepared files, Cargo metadata, or license collection fail
	 * @throws NullPointerException if any argument is null
	 */
	public static void copy(Path repository, Path packageDirectory, Optional<Path> preparedLicenses,
		Optional<Path> workspace, CommandRunner metadata) throws IOException
	{
		Objects.requireNonNull(repository, "repository");
		Objects.requireNonNull(packageDirectory, "packageDirectory");
		Objects.requireNonNull(preparedLicenses, "preparedLicenses");
		Objects.requireNonNull(workspace, "workspace");
		Objects.requireNonNull(metadata, "metadata");
		for (String document : DOCUMENTS)
		{
			Path source = repository.resolve(document);
			if (!Files.isRegularFile(source))
				throw new IOException("Missing legal material: " + source);
			Path destination = packageDirectory.resolve(document);
			Files.createDirectories(destination.getParent());
			PayloadFiles.copy(source, destination);
		}
		Path output = packageDirectory.resolve("licenses/rust");
		if (preparedLicenses.isPresent())
		{
			Path prepared = preparedLicenses.orElseThrow();
			if (!Files.isDirectory(prepared))
				throw new IOException("Prepared Cargo license directory does not exist: " + prepared);
			Path notices = prepared.resolve("THIRD_PARTY_NOTICES.md");
			if (!Files.isRegularFile(notices))
				throw new IOException("Prepared Cargo license notices do not exist: " + notices);
			PayloadDirectories.copy(prepared, output);
			return;
		}
		if (workspace.isPresent())
		{
			Path manifest = workspace.orElseThrow().resolve("codex-rs/Cargo.toml");
			if (Files.isRegularFile(manifest))
				LicensePayloads.collect(metadata.run(List.of("cargo", "metadata", "--format-version", "1", "--locked",
					"--manifest-path", manifest.toString())), output, true);
		}
	}
}
