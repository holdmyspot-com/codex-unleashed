package com.holdmyspot.codexunleashed.tooling.release;

import java.io.IOException;
import java.math.BigInteger;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies archive environment precedence, numeric forms, timestamp text, and deferred consumption.
 */
public final class ArchiveEnvironmentTest
{
	/**
	 * Creates archive environment tests.
	 */
	public ArchiveEnvironmentTest()
	{
	}

	/**
	 * Distinguishes absence from an empty override and keeps the gzip clock independent.
	 *
	 * @throws IOException if valid timestamp resolution fails
	 */
	@Test
	public void preservesOverridePrecedence() throws IOException
	{
		ArchiveEnvironment absent = new ArchiveEnvironment(Map.of());
		assertEquals(absent.tarModificationTime(), Optional.empty());
		assertEquals(absent.gzipHeaderTime(), Optional.empty());
		ArchiveEnvironment fallback = new ArchiveEnvironment(Map.of("SOURCE_DATE_EPOCH", "17"));
		assertEquals(fallback.tarModificationTime(), Optional.of(BigInteger.valueOf(17)));
		assertEquals(fallback.gzipHeaderTime(), Optional.empty());
		ArchiveEnvironment override = new ArchiveEnvironment(Map.of("SOURCE_DATE_EPOCH", "invalid",
			"CODEX_PACKAGE_ARCHIVE_MTIME", "19", "CODEX_PACKAGE_ARCHIVE_GZIP_MTIME", "23"));
		assertEquals(override.tarModificationTime(), Optional.of(BigInteger.valueOf(19)));
		assertEquals(override.gzipHeaderTime(), Optional.of(BigInteger.valueOf(23)));
		ArchiveEnvironment empty = new ArchiveEnvironment(Map.of("SOURCE_DATE_EPOCH", "17",
			"CODEX_PACKAGE_ARCHIVE_MTIME", ""));
		expectThrows(IOException.class, empty::tarModificationTime);
	}

	/**
	 * Accepts retained decimal integer spellings without truncating large values.
	 *
	 * @throws IOException if valid timestamp resolution fails
	 */
	@Test
	public void retainsIntegerForms() throws IOException
	{
		String large = "1234567890123456789012345678901234567890";
		assertEquals(new ArchiveEnvironment(Map.of("SOURCE_DATE_EPOCH", large)).tarModificationTime(),
			Optional.of(new BigInteger(large)));
		assertEquals(new ArchiveEnvironment(Map.of("SOURCE_DATE_EPOCH", "\u00a0+١_٢٣\u0085")).
			tarModificationTime(), Optional.of(BigInteger.valueOf(123)));
		for (String invalid : List.of("1.0", "0x10", "1__2", "_12", "12_", "\u001c12"))
			expectThrows(IOException.class, () -> new ArchiveEnvironment(Map.of("SOURCE_DATE_EPOCH", invalid)).
				tarModificationTime());
	}

	/**
	 * Preserves supplied timestamp strings and rejects JSON shapes that are not member time mappings.
	 *
	 * @throws IOException if valid member timestamp resolution fails
	 */
	@Test
	public void retainsMemberTimestampStrings() throws IOException
	{
		String timestamp = "1234567890.12345678901234567890";
		ArchiveEnvironment environment = new ArchiveEnvironment(Map.of("CODEX_PACKAGE_ARCHIVE_MEMBER_MTIMES",
			"{\"file\":\"" + timestamp + "\",\"integer\":13,\"decimal\":1.0}"));
		assertEquals(environment.memberModificationTimes(), Map.of("file", timestamp, "integer", "13", "decimal", "1.0"));
		for (String invalid : List.of("[]", "null", "{\"file\":true}", "{\"file\":null}",
			"{\"file\":[]}", "{\"file\":\"0x1.0p2\"}", "{\"file\":\"1.0d\"}",
			"{\"file\":+Infinity}", "{\"file\":INF}", "{\"file\":-INF}",
			"{\"file\":1}{\"file\":2}", "{\"file\":1} 0"))
			expectThrows(IOException.class, () -> new ArchiveEnvironment(Map.of("CODEX_PACKAGE_ARCHIVE_MEMBER_MTIMES",
				invalid)).memberModificationTimes());
		ArchiveEnvironment spellings = new ArchiveEnvironment(Map.of("CODEX_PACKAGE_ARCHIVE_MEMBER_MTIMES",
			"{\"unicode\":\" +١_٢.٥e-١ \",\"infinity\":\"iNfINity\",\"nan\":\"-NaN\"}"));
		assertEquals(spellings.memberModificationTimes(), Map.of("unicode", " +١_٢.٥e-١ ",
			"infinity", "iNfINity", "nan", "-NaN"));
		assertEquals(new ArchiveEnvironment(Map.of("CODEX_PACKAGE_ARCHIVE_MEMBER_MTIMES",
			"{\"file\":true,\"file\":1.0}")).memberModificationTimes(), Map.of("file", "1.0"));
	}

	/**
	 * Uses retained shortest round-trip number text and its fixed-versus-scientific thresholds.
	 *
	 * @throws IOException if numeric member timestamp resolution fails
	 */
	@Test
	public void formatsNumericMemberTimestamps() throws IOException
	{
		ArchiveEnvironment environment = new ArchiveEnvironment(Map.of("CODEX_PACKAGE_ARCHIVE_MEMBER_MTIMES",
			"{\"large\":1e23,\"smallest\":5e-324,\"small\":1e-5,\"threshold\":1e16," +
				"\"decimal\":1.20,\"negativeZero\":-0.0,\"overflow\":1e400,\"nan\":NaN," +
				"\"boundary\":5.960464477539063e-8}"));
		assertEquals(environment.memberModificationTimes(), Map.of("large", "1e+23", "smallest", "5e-324",
			"small", "1e-05", "threshold", "1e+16", "decimal", "1.2", "negativeZero", "-0.0",
			"overflow", "inf", "nan", "nan", "boundary", "5.960464477539063e-08"));
	}

	/**
	 * Ignores unused options for ZIP, empty tar, and members whose own timestamps override the blanket value.
	 *
	 * @throws IOException if fixture access or valid archive writing fails
	 */
	@Test
	public void defersUnusedOptions() throws IOException
	{
		Path root = Files.createTempDirectory("archive-environment-");
		try
		{
			Path directory = Files.createDirectory(root.resolve("package"));
			Path temporary = Files.createDirectory(root.resolve("temporary"));
			Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
			ArchiveEnvironment invalid = new ArchiveEnvironment(Map.of("CODEX_PACKAGE_ARCHIVE_MTIME", "invalid",
				"CODEX_PACKAGE_ARCHIVE_MEMBER_MTIMES", "invalid", "CODEX_PACKAGE_ARCHIVE_GZIP_MTIME", "invalid"));
			PackageArchives.write(new PackageArchives.Request(directory, root.resolve("package.zip"), false,
				temporary, invalid, List.of()), clock, command -> "");
			ArchiveEnvironment invalidTarOnly = new ArchiveEnvironment(Map.of("CODEX_PACKAGE_ARCHIVE_MTIME", "invalid",
				"CODEX_PACKAGE_ARCHIVE_MEMBER_MTIMES", "invalid"));
			PackageArchives.write(new PackageArchives.Request(directory, root.resolve("empty.tgz"), false,
				temporary, invalidTarOnly, List.of()), clock, command -> "");
			Files.writeString(directory.resolve("file"), "payload");
			ArchiveEnvironment overridden = new ArchiveEnvironment(Map.of("CODEX_PACKAGE_ARCHIVE_MTIME", "invalid",
				"CODEX_PACKAGE_ARCHIVE_MEMBER_MTIMES", "{\"file\":\"9.5\"}"));
			PackageArchives.write(new PackageArchives.Request(directory, root.resolve("overridden.tgz"), false,
				temporary, overridden, List.of()), clock, command -> "");
			IOException failure = expectThrows(IOException.class, () -> PackageArchives.write(new PackageArchives.Request(
				directory, root.resolve("invalid.tgz"), false, temporary, invalidTarOnly, List.of()), clock, command -> ""));
			assertTrue(failure.getMessage().contains("JSON object"));
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Retains large tar timestamps through PAX while enforcing the gzip header's unsigned 32-bit range.
	 *
	 * @throws IOException if fixture access or valid archive writing fails
	 */
	@Test
	public void preservesTimestampRanges() throws IOException
	{
		Path root = Files.createTempDirectory("archive-timestamp-ranges-");
		try
		{
			Path directory = Files.createDirectory(root.resolve("package"));
			Path temporary = Files.createDirectory(root.resolve("temporary"));
			Files.writeString(directory.resolve("file"), "payload");
			Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
			String large = "1234567890123456789012345678901234567890";
			Path output = root.resolve("large.tgz");
			ArchiveEnvironment valid = new ArchiveEnvironment(Map.of("SOURCE_DATE_EPOCH", large,
				"CODEX_PACKAGE_ARCHIVE_GZIP_MTIME", "4294967295"));
			PackageArchives.write(new PackageArchives.Request(directory, output, false, temporary, valid,
				List.of()), clock, command -> "");
			byte[] gzip = Files.readAllBytes(output);
			assertEquals(Integer.toUnsignedLong(ByteBuffer.wrap(gzip, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()),
				4_294_967_295L);
			try (GZIPInputStream archive = new GZIPInputStream(new ByteArrayInputStream(gzip)))
			{
				assertTrue(new String(archive.readAllBytes(), StandardCharsets.ISO_8859_1).
					contains("mtime=" + large + "\n"));
			}
			for (String invalid : List.of("-1", "4294967296"))
			{
				ArchiveEnvironment environment = new ArchiveEnvironment(Map.of("CODEX_PACKAGE_ARCHIVE_GZIP_MTIME", invalid));
				expectThrows(IOException.class, () -> PackageArchives.write(new PackageArchives.Request(directory,
					root.resolve("invalid-" + invalid + ".tgz"), false, temporary, environment, List.of()), clock,
					command -> ""));
			}
		}
		finally
		{
			delete(root);
		}
	}

	/**
	 * Removes owned environment-test files without following directory symlinks.
	 *
	 * @param root the owned fixture root
	 * @throws IOException if cleanup fails
	 */
	private static void delete(Path root) throws IOException
	{
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path file : paths.sorted(Comparator.reverseOrder()).toList())
				Files.delete(file);
		}
	}
}
