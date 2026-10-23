package com.holdmyspot.codexunleashed.tooling;

import com.holdmyspot.codexunleashed.tooling.release.PackageVersions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

/** Verifies native TOML temporal values remain distinct from quoted strings at producer boundaries. */
public final class TomlDocumentsTest
{
	/** Creates TOML document tests. */
	public TomlDocumentsTest()
	{
	}

	/** Keeps all four native temporal types distinct and validates their calendar values. */
	@Test
	public void preservesTemporalTypes()
	{
		JsonNode document = TomlDocuments.parse("""
			date = 2000-01-01
			time = 12:34:56
			local = 2000-01-01T12:34:56
			offset = 2000-01-01T12:34:56Z
			quoted = ["2000-01-01", "12:34:56", "2000-01-01T12:34:56", "2000-01-01T12:34:56Z"]
			""");
		for (String name : new String[]{"date", "time", "local", "offset"})
			requireThat(document.path(name).isPojo(), "nativeTemporalType").isTrue();
		for (JsonNode quoted : document.path("quoted"))
			requireThat(quoted.isString(), "quotedString").isTrue();
		expectThrows(JacksonException.class, () -> TomlDocuments.parse("date = 2000-02-31\n"));
		requireThat(TomlDocuments.parse("date = \"2000-02-31\"\n").path("date").stringValue(), "quotedDate").
			isEqualTo("2000-02-31");
	}

	/** Rejects native dates as V8 versions while retaining identical quoted text. */
	@Test
	public void rejectsTemporalLockfileVersions()
	{
		String prefix = "[[package]]\nname = \"v8\"\nversion = ";
		expectThrows(IOException.class, () -> V8Versions.resolveLockfile(prefix + "2000-01-01\n"));
		requireThat(uncheckedVersion(prefix + "\"2000-01-01\"\n"), "quotedVersion").isEqualTo("2000-01-01");
	}

	/**
	 * Rejects native dates as package metadata versions while retaining an identical quoted string.
	 *
	 * @throws IOException if fixture access, parsing, or cleanup fails
	 */
	@Test
	public void rejectsTemporalPackageVersions() throws IOException
	{
		Path root = Files.createTempDirectory("toml-package-versions-");
		try
		{
			Files.createDirectory(root.resolve("codex-rs"));
			Path manifest = root.resolve("codex-rs/Cargo.toml");
			Files.writeString(manifest, "[workspace.package]\nversion = 2000-01-01\n");
			expectThrows(IOException.class, () -> PackageVersions.resolve(root, Map.of()));
			Files.writeString(manifest, "[workspace.package]\nversion = \"2000-01-01\"\n");
			requireThat(PackageVersions.resolve(root, Map.of()), "quotedPackageVersion").isEqualTo("2000-01-01+dev");
		}
		finally
		{
			try (Stream<Path> files = Files.walk(root))
			{
				for (Path file : files.sorted(Comparator.reverseOrder()).toList())
					Files.delete(file);
			}
		}
	}

	/**
	 * Converts an unexpected lockfile failure into an assertion failure.
	 *
	 * @param text the quoted-version lockfile
	 * @return the resolved version
	 */
	private static String uncheckedVersion(String text)
	{
		try
		{
			return V8Versions.resolveLockfile(text);
		}
		catch (IOException failure)
		{
			throw new AssertionError(failure);
		}
	}
}
