package com.holdmyspot.codexunleashed.tooling.release;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

/**
 * Stages public installer assets from a patched upstream checkout.
 */
public final class Installers
{
	private static final List<String> NAMES = List.of("install.sh", "install.ps1");
	private static final String RELEASE_REPOSITORY = "holdmyspot-com/codex-unleashed";
	/**
	 * Prevents construction.
	 */
	private Installers()
	{
	}

	/**
	 * Copies the patched installers into an existing release directory.
	 *
	 * @param upstream the patched upstream checkout
	 * @param release the existing release directory
	 * @throws NullPointerException if {@code upstream} or {@code release} are null
	 * @throws IOException if reading or copying an installer fails
	 * @throws IllegalArgumentException if an installer is missing or unpatched, or the release directory is absent
	 */
	public static void stage(Path upstream, Path release) throws IOException
	{
		Objects.requireNonNull(upstream, "upstream");
		Objects.requireNonNull(release, "release");
		List<Path> sources = NAMES.stream().map(upstream.resolve("scripts/install")::resolve).toList();
		List<String> missing = sources.stream().filter(source -> !Files.isRegularFile(source)).
			map(Path::toString).toList();
		if (!missing.isEmpty())
			throw new IllegalArgumentException("required installer file is missing: " + String.join(", ", missing));

		var unpatched = new java.util.ArrayList<String>();
		for (Path source : sources)
		{
			String bytes = new String(Files.readAllBytes(source), StandardCharsets.ISO_8859_1);
			if (!bytes.contains(RELEASE_REPOSITORY))
				unpatched.add(source.toString());
		}
		if (!unpatched.isEmpty())
			throw new IllegalArgumentException("installer does not point to " + RELEASE_REPOSITORY +
				" releases: " + String.join(", ", unpatched));
		if (!Files.isDirectory(release))
			throw new IllegalArgumentException("release directory does not exist: " + release);

		for (Path source : sources)
			Files.copy(source, release.resolve(source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
	}
}
