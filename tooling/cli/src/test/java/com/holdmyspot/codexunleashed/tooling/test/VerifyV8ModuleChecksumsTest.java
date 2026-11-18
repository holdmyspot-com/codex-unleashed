package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.Main;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies the packaged {@code verify-v8-module-checksums} command through {@link Main#run}.
 */
public final class VerifyV8ModuleChecksumsTest
{
	/** Command name that selects the checker. */
	private static final String COMMAND = "verify-v8-module-checksums";
	/** Complete diagnostic for a wrong argument count. */
	private static final String USAGE = "Usage: codex-tooling " + COMMAND + " <repository-root>\n";
	/**
	 * Matches the text that stands for the fixture root in expected output, with the {@code /}-separated relative path
	 * that follows it, if any.
	 */
	private static final Pattern PLACEHOLDER = Pattern.compile("<fixture_root>(/[A-Za-z0-9_./-]*)?");
	/** Repository-relative module path. */
	private static final String MODULE_FILE = "MODULE.bazel";
	/** Repository-relative lock path. */
	private static final String LOCK_FILE = "codex-rs/Cargo.lock";
	/** Repository-relative manifest path for version 1.2.3. */
	private static final String MANIFEST_FILE = "third_party/v8/rusty_v8_1_2_3.sha256";
	/** Whether the default file system reports POSIX permissions. */
	private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
	/** Digest made of zeros. */
	private static final String ZERO = "0".repeat(64);
	/** Digest made of ones. */
	private static final String ONES = "1".repeat(64);
	/** Minimal module with one recognized artifact. */
	private static final String MODULE =
		"http_file(\n" +
		"    name = \"rusty_v8_1_2_3_demo\",\n" +
		"    downloaded_file_path = \"demo.a.gz\",\n" +
		"    sha256 = \"" +
		ZERO +
		"\",\n" +
		")\n";
	/** Manifest for the minimal module. */
	private static final String MANIFEST =
		ZERO +
		"  demo.a.gz\n";
	/** Cargo.lock whose v8 version conflicts with the module. */
	private static final String LOCK =
		"version = 4\n" +
		"[[package]]\n" +
		"name = \"v8\"\n" +
		"version = \"9.9.9\"\n";
	/** Module that only pins a v8 crate URL. */
	private static final String URL_MODULE =
		"https://static.crates.io/crates/v8/v8-1.2.3.crate\n";

	/** Creates independent method fixtures. */
	public VerifyV8ModuleChecksumsTest()
	{
	}

	/**
	 * Accepts a module whose checksums match its manifest even though Cargo.lock names another v8 version.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void acceptsModuleChecksums() throws IOException
	{
		try (Fixture fixture = new Fixture(baseFiles()))
		{
			requireThat(fixture.run(), "status").isEqualTo(0);
			requireThat(fixture.output(), "stdout").isEqualTo(success(fixture.root()));
			requireThat(fixture.errors(), "stderr").isEmpty();
		}
	}

	/**
	 * Builds the minimal recognized module, its manifest and a conflicting lock.
	 *
	 * @return the fixture files by relative path
	 */
	private static Map<String, String> baseFiles()
	{
		return files(MODULE_FILE, MODULE, LOCK_FILE, LOCK, MANIFEST_FILE, MANIFEST);
	}

	/**
	 * Builds a file map from alternating paths and contents.
	 *
	 * @param pairs path followed by content, repeated
	 * @return the files in argument order
	 */
	private static Map<String, String> files(String... pairs)
	{
		Map<String, String> result = new LinkedHashMap<>();
		for (int index = 0; index < pairs.length; index += 2)
			result.put(pairs[index], pairs[index + 1]);
		return result;
	}

	/**
	 * Builds the success line of the minimal fixture.
	 *
	 * @param root fixture root
	 * @return the expected standard output
	 */
	private static String success(Path root)
	{
		return root.resolve(MODULE_FILE) + " rusty_v8 1.2.3 checksums match " + root.resolve(MANIFEST_FILE) + "\n";
	}

	/**
	 * Selects the module version without reading Cargo.lock.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void ignoresUnusedCargoLock() throws IOException
	{
		try (Fixture fixture = new Fixture(files(MODULE_FILE, MODULE, MANIFEST_FILE, MANIFEST)))
		{
			requireThat(fixture.run(), "status").isEqualTo(0);
			requireThat(fixture.output(), "stdout").isEqualTo(success(fixture.root()));
			requireThat(fixture.errors(), "stderr").isEmpty();
		}
	}

	/**
	 * Supplies the drift inputs.
	 *
	 * @return case rows of identifier, files, status, output, diagnostics and failed input path
	 */
	@DataProvider
	public Object[][] driftRows()
	{
		return new Object[][]
		{
			{"missing_checksum",
				files(
					MODULE_FILE,
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_demo\",\n" +
						"    downloaded_file_path = \"demo.a.gz\",\n" +
						")\n",
					LOCK_FILE, LOCK,
					MANIFEST_FILE, MANIFEST),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel rusty_v8_1_2_3_demo is missing sha256\n",
				""},
			{"wrong_checksum",
				files(
					MODULE_FILE,
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_demo\",\n" +
						"    downloaded_file_path = \"demo.a.gz\",\n" +
						"    sha256 = \"" + ONES + "\",\n" +
						")\n",
					LOCK_FILE, LOCK,
					MANIFEST_FILE, MANIFEST),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel rusty_v8_1_2_3_demo has sha256 " + ONES + ", expected " + ZERO + "\n",
				""}
		};
	}

	/**
	 * Rejects missing and mismatched module checksums with the legacy diagnostics.
	 *
	 * @param id label of the case
	 * @param files fixture files by relative path
	 * @param status expected command status
	 * @param stdout expected standard output
	 * @param stderr expected diagnostics, or null when only the failed input path is required
	 * @param path input path that an operational diagnostic must name
	 * @throws IOException if fixture storage fails
	 */
	@Test(dataProvider = "driftRows")
	public void rejectsChecksumDrift(String id, Map<String, String> files, int status,
		String stdout, String stderr, String path) throws IOException
	{
		verifyRow(id, files, status, stdout, stderr, path);
	}

	/**
	 * Supplies the missing inputs.
	 *
	 * @return case rows of identifier, files, status, output, diagnostics and failed input path
	 */
	@DataProvider
	public Object[][] missingRows()
	{
		return new Object[][]
		{
			{"missing_manifest",
				files(
					MODULE_FILE, MODULE,
					LOCK_FILE, LOCK),
				1, "",
				"missing checksum manifest: <fixture_root>/third_party/v8/rusty_v8_1_2_3.sha256\n",
				""},
			{"missing_module",
				files(
					LOCK_FILE, LOCK,
					MANIFEST_FILE, MANIFEST),
				1, "",
				null,
				"MODULE.bazel"},
			{"missing_fallback_input",
				files(
					MODULE_FILE, "",
					MANIFEST_FILE, MANIFEST),
				1, "",
				null,
				"codex-rs/Cargo.lock"}
		};
	}

	/**
	 * Reports a missing selected manifest and unreadable module or fallback inputs.
	 *
	 * @param id label of the case
	 * @param files fixture files by relative path
	 * @param status expected command status
	 * @param stdout expected standard output
	 * @param stderr expected diagnostics, or null when only the failed input path is required
	 * @param path input path that an operational diagnostic must name
	 * @throws IOException if fixture storage fails
	 */
	@Test(dataProvider = "missingRows")
	public void rejectsMissingManifest(String id, Map<String, String> files, int status,
		String stdout, String stderr, String path) throws IOException
	{
		verifyRow(id, files, status, stdout, stderr, path);
	}

	/**
	 * Supplies the invalid inputs.
	 *
	 * @return case rows of identifier, files, status, output, diagnostics and failed input path
	 */
	@DataProvider
	public Object[][] invalidRows()
	{
		return new Object[][]
		{
			{"empty_manifest",
				files(
					MODULE_FILE, MODULE,
					LOCK_FILE, LOCK,
					MANIFEST_FILE,
						"\n" +
						"   \n"),
				1, "",
				"empty checksum manifest: <fixture_root>/third_party/v8/rusty_v8_1_2_3.sha256\n",
				""},
			{"uppercase_digest",
				files(
					MODULE_FILE, MODULE,
					LOCK_FILE, LOCK,
					MANIFEST_FILE, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA  demo.a.gz\n"),
				1, "",
				"<fixture_root>/third_party/v8/rusty_v8_1_2_3.sha256:1: invalid SHA-256 " + "digest for demo.a.gz\n",
				""},
			{"non_ascii_digit_digest",
				files(
					MODULE_FILE, MODULE,
					LOCK_FILE, LOCK,
					MANIFEST_FILE,
						"\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661" +
						"\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661" +
						"\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661" +
						"\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661\u0661" +
						"\u0661\u0661\u0661\u0661  demo.a.gz\n"),
				1, "",
				"<fixture_root>/third_party/v8/rusty_v8_1_2_3.sha256:1: invalid SHA-256 " + "digest for demo.a.gz\n",
				""},
			{"slash_filename",
				files(
					MODULE_FILE, MODULE,
					LOCK_FILE, LOCK,
					MANIFEST_FILE, ZERO + "  dir/demo.a.gz\n"),
				1, "",
				"<fixture_root>/third_party/v8/rusty_v8_1_2_3.sha256:1: expected a bare " + "artifact filename\n",
				""},
			{"dot_filename",
				files(
					MODULE_FILE, MODULE,
					LOCK_FILE, LOCK,
					MANIFEST_FILE, ZERO + "  .\n"),
				1, "",
				"<fixture_root>/third_party/v8/rusty_v8_1_2_3.sha256:1: expected a bare " + "artifact filename\n",
				""},
			{"dot_dot_filename",
				files(
					MODULE_FILE, MODULE,
					LOCK_FILE, LOCK,
					MANIFEST_FILE, ZERO + "  ..\n"),
				1, "",
				"<fixture_root>/third_party/v8/rusty_v8_1_2_3.sha256:1: expected a bare " + "artifact filename\n",
				""},
			{"one_token",
				files(
					MODULE_FILE, MODULE,
					LOCK_FILE, LOCK,
					MANIFEST_FILE, ZERO + "\n"),
				1, "",
				"<fixture_root>/third_party/v8/rusty_v8_1_2_3.sha256:1: expected '<sha256> " + " <filename>'\n",
				""},
			{"three_tokens",
				files(
					MODULE_FILE, MODULE,
					LOCK_FILE, LOCK,
					MANIFEST_FILE, ZERO + "  demo.a.gz  extra\n"),
				1, "",
				"<fixture_root>/third_party/v8/rusty_v8_1_2_3.sha256:1: expected '<sha256> " + " <filename>'\n",
				""},
			{"duplicate_manifest_line",
				files(
					MODULE_FILE, MODULE,
					LOCK_FILE, LOCK,
					MANIFEST_FILE,
						MANIFEST + "\n" +
						MANIFEST),
				1, "",
				"<fixture_root>/third_party/v8/rusty_v8_1_2_3.sha256:3: duplicate checksum " + "for demo.a.gz\n",
				""},
			{"invalid_utf8_manifest",
				files(
					MODULE_FILE, MODULE,
					LOCK_FILE, LOCK,
					"!third_party/v8/rusty_v8_1_2_3.sha256",
						"\u00ff\u00fe" + MANIFEST),
				1, "",
				null,
				"third_party/v8/rusty_v8_1_2_3.sha256"},
			{"manifest_is_directory",
				files(
					MODULE_FILE, MODULE,
					LOCK_FILE, LOCK,
					"third_party/v8/rusty_v8_1_2_3.sha256/inner",
						"x"),
				1, "",
				null,
				"third_party/v8/rusty_v8_1_2_3.sha256"}
		};
	}

	/**
	 * Rejects empty and malformed manifests and operational manifest read failures.
	 *
	 * @param id label of the case
	 * @param files fixture files by relative path
	 * @param status expected command status
	 * @param stdout expected standard output
	 * @param stderr expected diagnostics, or null when only the failed input path is required
	 * @param path input path that an operational diagnostic must name
	 * @throws IOException if fixture storage fails
	 */
	@Test(dataProvider = "invalidRows")
	public void rejectsInvalidManifest(String id, Map<String, String> files, int status,
		String stdout, String stderr, String path) throws IOException
	{
		verifyRow(id, files, status, stdout, stderr, path);
	}

	/**
	 * Supplies the module inputs.
	 *
	 * @return case rows of identifier, files, status, output, diagnostics and failed input path
	 */
	@DataProvider
	public Object[][] moduleRows()
	{
		return new Object[][]
		{
			{"indented_block",
				files(
					MODULE_FILE, "  " + MODULE,
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name = \"v8\"\n" +
						"version = \"1.2.3\"\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel has no rusty_v8 http_file entries for 1.2.3\n",
				""},
			{"name_without_comma",
				files(
					MODULE_FILE,
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_demo\"\n" +
						"    downloaded_file_path = \"demo.a.gz\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n",
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name = \"v8\"\n" +
						"version = \"1.2.3\"\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel has no rusty_v8 http_file entries for 1.2.3\n",
				""},
			{"path_without_comma",
				files(
					MODULE_FILE,
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_demo\",\n" +
						"    downloaded_file_path = \"demo.a.gz\"\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n",
					LOCK_FILE, LOCK,
					MANIFEST_FILE, MANIFEST),
				1, "",
				"MODULE.bazel rusty_v8_1_2_3_demo is missing downloaded_file_path\n",
				""},
			{"missing_downloaded_path",
				files(
					MODULE_FILE,
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_demo\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n",
					LOCK_FILE, LOCK,
					MANIFEST_FILE, MANIFEST),
				1, "",
				"MODULE.bazel rusty_v8_1_2_3_demo is missing downloaded_file_path\n",
				""},
			{"duplicate_module_artifact",
				files(
					MODULE_FILE,
						MODULE + "\n" +
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_other\",\n" +
						"    downloaded_file_path = \"demo.a.gz\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n",
					LOCK_FILE, LOCK,
					MANIFEST_FILE, MANIFEST),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel has duplicate http_file entries for demo.a.gz\n",
				""},
			{"multiple_versions",
				files(
					MODULE_FILE,
						MODULE + "http_file(\n" +
						"    name = \"rusty_v8_2_0_0_demo\",\n" +
						"    downloaded_file_path = \"demo.a.gz\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n",
					LOCK_FILE, LOCK,
					MANIFEST_FILE, MANIFEST),
				1, "",
				"expected at most one rusty_v8 http_file version in MODULE.bazel, found: " +
				"['1.2.3', '2.0.0']; pass --version explicitly\n",
				""},
			{"name_without_suffix_underscore",
				files(
					MODULE_FILE,
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3demo\",\n" +
						"    downloaded_file_path = \"demo.a.gz\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n",
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name = \"v8\"\n" +
						"version = \"1.2.3\"\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel has no rusty_v8 http_file entries for 1.2.3\n",
				""},
			{"fullwidth_digit_name",
				files(
					MODULE_FILE,
						"http_file(\n" +
						"    name = \"rusty_v8_\uff11_\uff12_\uff13_demo\",\n" +
						"    downloaded_file_path = \"demo.a.gz\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n",
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name = \"v8\"\n" +
						"version = \"1.2.3\"\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel has no rusty_v8 http_file entries for 1.2.3\n",
				""},
			{"bom_before_block",
				files(
					MODULE_FILE, "\ufeff" + MODULE,
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name = \"v8\"\n" +
						"version = \"1.2.3\"\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel has no rusty_v8 http_file entries for 1.2.3\n",
				""},
			{"unicode_whitespace_fields",
				files(
					MODULE_FILE,
						"http_file(\n" +
						"\u2003  name\u3000=\u00a0\"rusty_v8_1_2_3_demo\",\034\u3000\n" +
						"\013    downloaded_file_path = \"demo.a.gz\",\n" +
						"    sha256\u00a0=\u00a0\"" + ZERO + "\",\u00a0\n" +
						")\n",
					LOCK_FILE, LOCK,
					MANIFEST_FILE, MANIFEST),
				0,
				"<fixture_root>/MODULE.bazel rusty_v8 1.2.3 checksums match <fixture_root>/" +
				"third_party/v8/rusty_v8_1_2_3.sha256\n",
				"",
				""},
			{"embedded_newline_name",
				files(
					MODULE_FILE,
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_demo\n" +
						"suffix\",\n" +
						"    downloaded_file_path = \"demo.a.gz\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n",
					LOCK_FILE,
						"version=4\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=\"9.9.9\"\n",
					MANIFEST_FILE, MANIFEST),
				0,
				"<fixture_root>/MODULE.bazel rusty_v8 1.2.3 checksums match <fixture_root>/" +
				"third_party/v8/rusty_v8_1_2_3.sha256\n",
				"",
				""},
			{"block_without_trailing_newline",
				files(
					MODULE_FILE,
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_demo\",\n" +
						"    downloaded_file_path = \"demo.a.gz\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")",
					LOCK_FILE, LOCK,
					MANIFEST_FILE, MANIFEST),
				0,
				"<fixture_root>/MODULE.bazel rusty_v8 1.2.3 checksums match <fixture_root>/" +
				"third_party/v8/rusty_v8_1_2_3.sha256\n",
				"",
				""},
			{"unterminated_block",
				files(
					MODULE_FILE,
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_demo\",\n" +
						"    downloaded_file_path = \"demo.a.gz\",\n" +
						"    sha256 = \"" + ZERO + "\",\n",
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name = \"v8\"\n" +
						"version = \"1.2.3\"\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel has no rusty_v8 http_file entries for 1.2.3\n",
				""}
		};
	}

	/**
	 * Preserves the restricted module block and field recognizer, including its recognized versions.
	 *
	 * @param id label of the case
	 * @param files fixture files by relative path
	 * @param status expected command status
	 * @param stdout expected standard output
	 * @param stderr expected diagnostics, or null when only the failed input path is required
	 * @param path input path that an operational diagnostic must name
	 * @throws IOException if fixture storage fails
	 */
	@Test(dataProvider = "moduleRows")
	public void preservesModuleRecognition(String id, Map<String, String> files, int status,
		String stdout, String stderr, String path) throws IOException
	{
		verifyRow(id, files, status, stdout, stderr, path);
	}

	/**
	 * Supplies the fallback inputs.
	 *
	 * @return case rows of identifier, files, status, output, diagnostics and failed input path
	 */
	@DataProvider
	public Object[][] fallbackRows()
	{
		return new Object[][]
		{
			{"lock_fallback",
				files(
					MODULE_FILE, "",
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name = \"v8\"\n" +
						"version = \"1.2.3\"\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel has no rusty_v8 http_file entries for 1.2.3\n",
				""},
			{"distinct_lock_versions",
				files(
					MODULE_FILE, "",
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name = \"v8\"\n" +
						"version = \"1.2.3\"\n" +
						"[[package]]\n" +
						"name = \"v8\"\n" +
						"version = \"2.0.0\"\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"expected exactly one resolved v8 version, found: ['1.2.3', '2.0.0']\n",
				""},
			{"repeated_lock_version",
				files(
					MODULE_FILE, "",
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name = \"v8\"\n" +
						"version = \"1.2.3\"\n" +
						"[[package]]\n" +
						"name = \"v8\"\n" +
						"version = \"1.2.3\"\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel has no rusty_v8 http_file entries for 1.2.3\n",
				""},
			{"url_fallback",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name = \"serde\"\n" +
						"version = \"1.0.0\"\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel has no rusty_v8 http_file entries for 1.2.3\n",
				""},
			{"url_ambiguity",
				files(
					MODULE_FILE, URL_MODULE + "https://static.crates.io/crates/v8/v8-2.0.0.crate\n",
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name = \"serde\"\n" +
						"version = \"1.0.0\"\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"expected exactly one pinned v8 crate version in MODULE.bazel, found: ['1.2.3', " + "'2.0.0']\n",
				""},
			{"url_zero_matches",
				files(
					MODULE_FILE, "# nothing\n",
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name = \"serde\"\n" +
						"version = \"1.0.0\"\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"expected exactly one pinned v8 crate version in MODULE.bazel, found: []\n",
				""}
		};
	}

	/**
	 * Preserves the lock and URL version fallback decisions.
	 *
	 * @param id label of the case
	 * @param files fixture files by relative path
	 * @param status expected command status
	 * @param stdout expected standard output
	 * @param stderr expected diagnostics, or null when only the failed input path is required
	 * @param path input path that an operational diagnostic must name
	 * @throws IOException if fixture storage fails
	 */
	@Test(dataProvider = "fallbackRows")
	public void preservesFallbackDecisions(String id, Map<String, String> files, int status,
		String stdout, String stderr, String path) throws IOException
	{
		verifyRow(id, files, status, stdout, stderr, path);
	}

	/**
	 * Supplies the lock inputs.
	 *
	 * @return case rows of identifier, files, status, output, diagnostics and failed input path
	 */
	@DataProvider
	public Object[][] lockRows()
	{
		return new Object[][]
		{
			{"absent_package",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE, "version = 4\n"),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"empty_package_array",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE, "package = []\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel has no rusty_v8 http_file entries for 1.2.3\n",
				""},
			{"empty_package_mapping",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE, "package = {}\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel has no rusty_v8 http_file entries for 1.2.3\n",
				""},
			{"empty_package_string",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE, "package = \"\"\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel has no rusty_v8 http_file entries for 1.2.3\n",
				""},
			{"nonempty_package_mapping",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE, "package = { name = \"v8\", version = \"1.2.3\" }\n"),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"nonempty_package_string",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE, "package = \"v8\"\n"),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"nonempty_package_integer",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE, "package = 1\n"),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"nonempty_package_boolean",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE, "package = false\n"),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"missing_name",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"version=\"1.2.3\"\n"),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"missing_selected_version",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n"),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"ignored_missing_version",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"other\"\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel has no rusty_v8 http_file entries for 1.2.3\n",
				""},
			{"nonstring_name_integer",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=8\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel has no rusty_v8 http_file entries for 1.2.3\n",
				""},
			{"nonstring_name_boolean",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=true\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel has no rusty_v8 http_file entries for 1.2.3\n",
				""},
			{"nonstring_name_array",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=[\"v8\"]\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel has no rusty_v8 http_file entries for 1.2.3\n",
				""},
			{"nonstring_name_mapping",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name={ value=\"v8\" }\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel has no rusty_v8 http_file entries for 1.2.3\n",
				""},
			{"nonstring_name_date",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=1979-05-27\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel has no rusty_v8 http_file entries for 1.2.3\n",
				""},
			{"selected_string",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=\"1.2.3\"\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel has no rusty_v8 http_file entries for 1.2.3\n",
				""},
			{"selected_repeated_string",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=\"1.2.3\"\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=\"1.2.3\"\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel has no rusty_v8 http_file entries for 1.2.3\n",
				""},
			{"selected_distinct_strings",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=\"2.0.0\"\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=\"1.2.3\"\n"),
				1, "",
				"expected exactly one resolved v8 version, found: ['1.2.3', '2.0.0']\n",
				""},
			{"selected_string_date",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=\"1979-05-27\"\n",
					"third_party/v8/rusty_v8_1979-05-27.sha256",
						MANIFEST),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel has no rusty_v8 http_file entries for 1979-05-27\n",
				""},
			{"selected_integer",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1\n"),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"selected_float",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1.0\n"),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"selected_boolean",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=true\n"),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"selected_array",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=[\"1.2.3\"]\n"),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"selected_mapping",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version={ value=\"1.2.3\" }\n"),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"selected_date",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1979-05-27\n"),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"selected_local_time",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=07:32:00\n"),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"selected_local_datetime",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1979-05-27T07:32:00\n"),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"selected_offset_datetime",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1979-05-27T07:32:00Z\n"),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"selected_nan",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=nan\n"),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"selected_infinity",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=inf\n"),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"equal_numeric",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1.0\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=true\n"),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"distinct_numeric",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=2\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1\n"),
				1, "",
				"expected exactly one resolved v8 version, found: [1, 2]\n",
				""},
			{"equal_large_numeric",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=4611686018427387904\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=4611686018427387904.0\n"),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"distinct_large_numeric",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=9007199254740993\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=9007199254740993.0\n"),
				1, "",
				"expected exactly one resolved v8 version, found: [9007199254740992.0, " + "9007199254740993]\n",
				""},
			{"distinct_dates",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1979-05-28\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1979-05-27\n"),
				1, "",
				"expected exactly one resolved v8 version, found: [datetime.date(1979, " +
				"5, 27), datetime.date(1979, 5, 28)]\n",
				""},
			{"equal_offset_instants",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1979-05-27T07:32:00Z\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1979-05-27T08:32:00+01:00\n"),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"mixed_string_integer",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=\"1\"\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1\n"),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"mixed_string_date",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=\"1979-05-27\"\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1979-05-27\n"),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"repeated_nan",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=nan\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=nan\n"),
				1, "",
				"expected exactly one resolved v8 version, found: [nan, nan]\n",
				""},
			{"malformed_toml",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE, "[[package]\n"),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"selected_nonnumeric_string",
				files(
					MODULE_FILE,
						"http_file(\n" +
						"    name = \"rusty_v8_development_demo\",\n" +
						"    downloaded_file_path = \"demo.a.gz\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n",
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=\"development\"\n",
					"third_party/v8/rusty_v8_development.sha256",
						MANIFEST),
				0,
				"<fixture_root>/MODULE.bazel rusty_v8 development checksums match <fixture_root>/" +
				"third_party/v8/rusty_v8_development.sha256\n",
				"",
				""},
			{"selected_empty_string",
				files(
					MODULE_FILE,
						"http_file(\n" +
						"    name = \"rusty_v8__demo\",\n" +
						"    downloaded_file_path = \"demo.a.gz\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n",
					LOCK_FILE,
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=\"\"\n",
					"third_party/v8/rusty_v8_.sha256",
						MANIFEST),
				0,
				"<fixture_root>/MODULE.bazel rusty_v8  checksums match <fixture_root>/third_party/" + "v8/rusty_v8_.sha256\n",
				"",
				""},
			{"distinct_offset_datetimes",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1979-05-27T07:32:00Z\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1979-05-27T07:32:00+01:00\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1979-05-27T07:32:00-05:00\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"expected exactly one resolved v8 version, found: [datetime.datetime(1979, " +
				"5, 27, 7, 32, tzinfo=datetime.timezone(datetime.timedelta(seconds=3600))), " +
				"datetime.datetime(1979, 5, 27, 7, 32, tzinfo=datetime.timezone.utc), datetime.datetime(197" +
				"9, 5, 27, 7, 32, tzinfo=datetime.timezone(datetime.timedelta(days=-1, " + "seconds=68400)))]\n",
				""},
			{"distinct_local_times",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=07:32:01.5\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=07:32:00\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"expected exactly one resolved v8 version, found: [datetime.time(7, 32), " +
				"datetime.time(7, 32, 1, 500000)]\n",
				""},
			{"distinct_local_datetimes",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1979-05-27T07:32:00.000123\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1979-05-27T07:32:00\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"expected exactly one resolved v8 version, found: [datetime.datetime(1979, " +
				"5, 27, 7, 32), datetime.datetime(1979, 5, 27, 7, 32, 0, 123)]\n",
				""},
			{"beyond_signed64_integers",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=18446744073709551616\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=-9223372036854775809\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"expected exactly one resolved v8 version, found: [-9223372036854775809, " + "1, 18446744073709551616]\n",
				""},
			{"equal_beyond_signed64",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=18446744073709551616\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=18446744073709551616.0\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"distinct_floats",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1e22\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=100.0\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=0.1\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1.5e-7\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=123456789012345680.0\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1e16\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1e15\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=-2.5\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"expected exactly one resolved v8 version, found: [-2.5, 1.5e-07, 0.1, " +
				"100.0, 1000000000000000.0, 1e+16, 1.2345678901234568e+17, 1e+22]\n",
				""},
			{"bool_and_integer",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=true\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=2\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"expected exactly one resolved v8 version, found: [True, 2]\n",
				""},
			{"infinity_and_integer",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=inf\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=-inf\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"expected exactly one resolved v8 version, found: [-inf, 1, inf]\n",
				""},
			{"distinct_strings_repr",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=\"\\u00e9\"\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=\"a'b\"\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=\"a\\\"'b\"\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=\"back\\\\slash\"\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=\"\\U0001F600\"\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=\"\\u0007\"\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=\"\\u00a0\"\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=\"tab\\there\"\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=\"\\u007f\"\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=\"\\u2028\"\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				"expected exactly one resolved v8 version, found: ['\\x07', 'a\"\\'b', " +
				"\"a'b\", 'back\\\\slash', 'tab\\there', '\\x7f', '\\xa0', '\u00e9', '\\u2028', " + "'\ud83d\ude00']\n",
				""},
			{"package_array_of_integers",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"version = 4\n" +
						"package = [1]\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"package_array_of_strings",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"version = 4\n" +
						"package = [\"v8\"]\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"package_array_of_arrays",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"version = 4\n" +
						"package = [[1]]\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"mixed_date_datetime",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1979-05-27\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1979-05-27T07:32:00\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"mixed_naive_aware",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1979-05-27T07:32:00\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1979-05-27T07:32:00Z\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"mixed_time_date",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=07:32:00\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=1979-05-27\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				null,
				"codex-rs/Cargo.lock"},
			{"equal_local_times",
				files(
					MODULE_FILE, URL_MODULE,
					LOCK_FILE,
						"version = 4\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=07:32:00\n" +
						"[[package]]\n" +
						"name=\"v8\"\n" +
						"version=07:32:00.0\n",
					MANIFEST_FILE, MANIFEST),
				1, "",
				null,
				"codex-rs/Cargo.lock"}
		};
	}

	/**
	 * Preserves the oracle decision for every Cargo.lock value representation.
	 *
	 * @param id label of the case
	 * @param files fixture files by relative path
	 * @param status expected command status
	 * @param stdout expected standard output
	 * @param stderr expected diagnostics, or null when only the failed input path is required
	 * @param path input path that an operational diagnostic must name
	 * @throws IOException if fixture storage fails
	 */
	@Test(dataProvider = "lockRows")
	public void preservesCargoLockRepresentations(String id, Map<String, String> files, int status,
		String stdout, String stderr, String path) throws IOException
	{
		verifyRow(id, files, status, stdout, stderr, path);
	}

	/**
	 * Supplies the combined inputs.
	 *
	 * @return case rows of identifier, files, status, output, diagnostics and failed input path
	 */
	@DataProvider
	public Object[][] combinedRows()
	{
		return new Object[][]
		{
			{"combined_drift",
				files(
					MODULE_FILE,
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_known_a\",\n" +
						"    downloaded_file_path = \"known.a.gz\",\n" +
						")\n" +
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_known_b\",\n" +
						"    downloaded_file_path = \"known.b.gz\",\n" +
						"    sha256 = \"" + ONES + "\",\n" +
						")\n" +
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_known_c\",\n" +
						"    downloaded_file_path = \"known.c.gz\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n" +
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_dup_astral_1\",\n" +
						"    downloaded_file_path = \"\ud800\udc00.dup\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n" +
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_dup_bmp_1\",\n" +
						"    downloaded_file_path = \"\ue000.dup\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n" +
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_dup_astral_2\",\n" +
						"    downloaded_file_path = \"\ud800\udc00.dup\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n" +
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_dup_bmp_2\",\n" +
						"    downloaded_file_path = \"\ue000.dup\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n" +
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_only_module_astral\",\n" +
						"    downloaded_file_path = \"\ud800\udc00.m\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n" +
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_only_module_bmp\",\n" +
						"    downloaded_file_path = \"\ue000.m\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n" +
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_extra\",\n" +
						"    downloaded_file_path = \"extra.a.gz\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n",
					LOCK_FILE, LOCK,
					MANIFEST_FILE,
						ZERO + "  known.a.gz\n" +
						ZERO + "  known.b.gz\n" +
						ZERO + "  known.c.gz\n" +
						ZERO + "  \ud800\udc00.dup\n" +
						ZERO + "  \ue000.dup\n" +
						ZERO + "  \ud800\udc00.x\n" +
						ZERO + "  \ue000.x\n" +
						ZERO + "  missing.a.gz\n"),
				1, "",
				"rusty_v8 MODULE.bazel checksum drift:\n" +
				"- MODULE.bazel has duplicate http_file entries for \ue000.dup\n" +
				"- MODULE.bazel has duplicate http_file entries for \ud800\udc00.dup\n" +
				"- MODULE.bazel rusty_v8_1_2_3_extra has no checksum in the manifest\n" +
				"- MODULE.bazel rusty_v8_1_2_3_only_module_bmp has no checksum in the manifest\n" +
				"- MODULE.bazel rusty_v8_1_2_3_only_module_astral has no checksum in the " + "manifest\n" +
				"- manifest has missing.a.gz, but MODULE.bazel has no http_file\n" +
				"- manifest has \ue000.x, but MODULE.bazel has no http_file\n" +
				"- manifest has \ud800\udc00.x, but MODULE.bazel has no http_file\n" +
				"- MODULE.bazel rusty_v8_1_2_3_known_a is missing sha256\n" +
				"- MODULE.bazel rusty_v8_1_2_3_known_b has sha256 " + ONES + ", expected " + ZERO + "\n",
				""}
		};
	}

	/**
	 * Orders set diagnostics by code point and checksum diagnostics by declaration.
	 *
	 * @param id label of the case
	 * @param files fixture files by relative path
	 * @param status expected command status
	 * @param stdout expected standard output
	 * @param stderr expected diagnostics, or null when only the failed input path is required
	 * @param path input path that an operational diagnostic must name
	 * @throws IOException if fixture storage fails
	 */
	@Test(dataProvider = "combinedRows")
	public void ordersCombinedDrift(String id, Map<String, String> files, int status,
		String stdout, String stderr, String path) throws IOException
	{
		verifyRow(id, files, status, stdout, stderr, path);
	}

	/**
	 * Supplies the text inputs.
	 *
	 * @return case rows of identifier, files, status, output, diagnostics and failed input path
	 */
	@DataProvider
	public Object[][] textRows()
	{
		return new Object[][]
		{
			{"backslash_filename",
				files(
					MODULE_FILE,
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_demo\",\n" +
						"    downloaded_file_path = \"dir\\demo.a.gz\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n",
					LOCK_FILE, LOCK,
					MANIFEST_FILE, ZERO + "  dir\\demo.a.gz\n"),
				0,
				"<fixture_root>/MODULE.bazel rusty_v8 1.2.3 checksums match <fixture_root>/" +
				"third_party/v8/rusty_v8_1_2_3.sha256\n",
				"",
				""},
			{"nbsp_separator",
				files(
					MODULE_FILE, MODULE,
					LOCK_FILE, LOCK,
					MANIFEST_FILE, ZERO + "\u00a0demo.a.gz\n"),
				0,
				"<fixture_root>/MODULE.bazel rusty_v8 1.2.3 checksums match <fixture_root>/" +
				"third_party/v8/rusty_v8_1_2_3.sha256\n",
				"",
				""},
			{"nel_line_separator",
				files(
					MODULE_FILE, MODULE,
					LOCK_FILE, LOCK,
					MANIFEST_FILE,
						"\n" +
						"\u0085" + MANIFEST),
				0,
				"<fixture_root>/MODULE.bazel rusty_v8 1.2.3 checksums match <fixture_root>/" +
				"third_party/v8/rusty_v8_1_2_3.sha256\n",
				"",
				""},
			{"unicode_space_separators",
				files(
					MODULE_FILE, MODULE,
					LOCK_FILE, LOCK,
					MANIFEST_FILE, "\u2003" + ZERO + "\u3000\u202fdemo.a.gz\037\n"),
				0,
				"<fixture_root>/MODULE.bazel rusty_v8 1.2.3 checksums match <fixture_root>/" +
				"third_party/v8/rusty_v8_1_2_3.sha256\n",
				"",
				""},
			{"crlf",
				files(
					MODULE_FILE,
						"http_file(\r\n" +
						"    name = \"rusty_v8_1_2_3_demo\",\r\n" +
						"    downloaded_file_path = \"demo.a.gz\",\r\n" +
						"    sha256 = \"" + ZERO + "\",\r\n" +
						")\r\n",
					LOCK_FILE, LOCK,
					MANIFEST_FILE, ZERO + "  demo.a.gz\r\n"),
				0,
				"<fixture_root>/MODULE.bazel rusty_v8 1.2.3 checksums match <fixture_root>/" +
				"third_party/v8/rusty_v8_1_2_3.sha256\n",
				"",
				""},
			{"lone_cr",
				files(
					MODULE_FILE,
						"http_file(\r    name = \"rusty_v8_1_2_3_demo\",\r    downloaded_file_path " +
						"= \"demo.a.gz\",\r    sha256 = \"" + ZERO + "\",\r)\r",
					LOCK_FILE, LOCK,
					MANIFEST_FILE, ZERO + "  demo.a.gz\r"),
				0,
				"<fixture_root>/MODULE.bazel rusty_v8 1.2.3 checksums match <fixture_root>/" +
				"third_party/v8/rusty_v8_1_2_3.sha256\n",
				"",
				""},
			{"splitlines_separators",
				files(
					MODULE_FILE,
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_f0\",\n" +
						"    downloaded_file_path = \"f0.a.gz\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n" +
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_f1\",\n" +
						"    downloaded_file_path = \"f1.a.gz\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n" +
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_f2\",\n" +
						"    downloaded_file_path = \"f2.a.gz\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n" +
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_f3\",\n" +
						"    downloaded_file_path = \"f3.a.gz\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n" +
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_f4\",\n" +
						"    downloaded_file_path = \"f4.a.gz\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n" +
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_f5\",\n" +
						"    downloaded_file_path = \"f5.a.gz\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n" +
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_f6\",\n" +
						"    downloaded_file_path = \"f6.a.gz\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n" +
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_f7\",\n" +
						"    downloaded_file_path = \"f7.a.gz\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\n",
					LOCK_FILE, LOCK,
					MANIFEST_FILE,
						ZERO + "  f0.a.gz\013" + ZERO + "  f1.a.gz\f" + ZERO + "  f2.a.gz\034" + ZERO + "  f3.a.gz\035" + ZERO +
						"  f4.a.gz\036" + ZERO + "  f5.a.gz\u2028" + ZERO + "  f6.a.gz\u2029" + ZERO + "  f7.a.gz\n"),
				0,
				"<fixture_root>/MODULE.bazel rusty_v8 1.2.3 checksums match <fixture_root>/" +
				"third_party/v8/rusty_v8_1_2_3.sha256\n",
				"",
				""},
			{"separator_is_not_newline_in_module",
				files(
					MODULE_FILE,
						"http_file(\n" +
						"    name = \"rusty_v8_1_2_3_demo\",\n" +
						"    downloaded_file_path = \"demo.a.gz\",\n" +
						"    sha256 = \"" + ZERO + "\",\n" +
						")\u0085\n",
					LOCK_FILE, LOCK,
					MANIFEST_FILE, MANIFEST),
				0,
				"<fixture_root>/MODULE.bazel rusty_v8 1.2.3 checksums match <fixture_root>/" +
				"third_party/v8/rusty_v8_1_2_3.sha256\n",
				"",
				""},
			{"module_invalid_utf8",
				files(
					"!MODULE.bazel",
						"\u00c3(" + MODULE,
					LOCK_FILE, LOCK,
					MANIFEST_FILE, MANIFEST),
				1, "",
				null,
				"MODULE.bazel"}
		};
	}

	/**
	 * Preserves Python newline, line-separator and whitespace semantics for module and manifest text.
	 *
	 * @param id label of the case
	 * @param files fixture files by relative path
	 * @param status expected command status
	 * @param stdout expected standard output
	 * @param stderr expected diagnostics, or null when only the failed input path is required
	 * @param path input path that an operational diagnostic must name
	 * @throws IOException if fixture storage fails
	 */
	@Test(dataProvider = "textRows")
	public void preservesTextSemantics(String id, Map<String, String> files, int status,
		String stdout, String stderr, String path) throws IOException
	{
		verifyRow(id, files, status, stdout, stderr, path);
	}

	/**
	 * Replaces each fixture-root placeholder, with the relative path that follows it, by the native spelling of that
	 * path: the command prints paths with the platform's separator, while the expectations are written with {@code /}.
	 *
	 * @param template expected text that contains placeholders
	 * @param root     fixture root
	 * @return the expected text for this platform
	 */
	private static String expand(String template, Path root)
	{
		return PLACEHOLDER.matcher(template).replaceAll(match ->
		{
			String relative = match.group(1);
			if (relative == null)
				return Matcher.quoteReplacement(root.toString());
			return Matcher.quoteReplacement(root.resolve(relative.substring(1)).toString());
		});
	}

	/**
	 * Runs one data row and compares its status, output and diagnostics.
	 *
	 * @param id label of the case
	 * @param files fixture files by relative path
	 * @param status expected command status
	 * @param stdout expected standard output
	 * @param stderr expected diagnostics, or null when only the failed input path is required
	 * @param path input path that an operational diagnostic must name
	 * @throws IOException if fixture storage fails
	 */
	private static void verifyRow(String id, Map<String, String> files, int status, String stdout, String stderr,
		String path) throws IOException
	{
		try (Fixture fixture = new Fixture(files))
		{
			requireThat(fixture.run(), id + "Status").isEqualTo(status);
			requireThat(fixture.output(), id + "Output").isEqualTo(expand(stdout, fixture.root()));
			String errors = fixture.errors();
			if (stderr != null)
			{
				requireThat(errors, id + "Errors").isEqualTo(expand(stderr, fixture.root()));
				return;
			}
			requireThat(errors, id + "Path").contains(fixture.root().resolve(path).toString());
			requireThat(errors, id + "Traceback").doesNotContain("Traceback");
			requireThat(errors, id + "Frame").doesNotContain("\tat ");
			requireThat(errors, id + "ClassCast").doesNotContain("ClassCastException");
			requireThat(errors, id + "NullPointer").doesNotContain("NullPointerException");
		}
	}

	/**
	 * Rejects a missing or extra argument with only the command usage.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void rejectsArgumentCounts() throws IOException
	{
		try (Fixture fixture = new Fixture(baseFiles()))
		{
			requireThat(fixture.runWith(COMMAND), "missingStatus").isEqualTo(2);
			requireThat(fixture.output(), "missingOutput").isEmpty();
			requireThat(fixture.errors(), "missingErrors").isEqualTo(USAGE);
			requireThat(fixture.runWith(COMMAND, fixture.root().toString(), "extra"), "extraStatus").isEqualTo(2);
			requireThat(fixture.output(), "extraOutput").isEmpty();
			requireThat(fixture.errors(), "extraErrors").isEqualTo(USAGE);
		}
	}

	/**
	 * Leaves the supplied streams open and usable after success and failure, and does not replace the global ones.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	@SuppressWarnings("PMD.CloseResource")
	public void retainsBorrowedStreams() throws IOException
	{
		PrintStream standardOutput = System.out;
		PrintStream standardError = System.err;
		try (Fixture accepted = new Fixture(baseFiles());
			Fixture drifted = new Fixture(files(MODULE_FILE, MODULE.replace(ZERO, ONES), MANIFEST_FILE, MANIFEST)))
		{
			ClosingProbe output = new ClosingProbe();
			ClosingProbe errors = new ClosingProbe();
			PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8);
			ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
			int first = Main.run(new String[]
			{
				COMMAND, accepted.root().toString()
			}, in, out, err);
			int second = Main.run(new String[]
			{
				COMMAND, drifted.root().toString()
			}, in, out, err);
			out.print("after");
			err.print("after");
			requireThat(first, "firstStatus").isEqualTo(0);
			requireThat(second, "secondStatus").isEqualTo(1);
			requireThat(output.isClosed(), "outputClosed").isFalse();
			requireThat(errors.isClosed(), "errorsClosed").isFalse();
			requireThat(out.checkError(), "outputError").isFalse();
			requireThat(err.checkError(), "errorsError").isFalse();
			requireThat(output.toString(StandardCharsets.UTF_8), "output").
				isEqualTo(success(accepted.root()) + "after");
			requireThat(errors.toString(StandardCharsets.UTF_8), "errors").startsWith("rusty_v8 MODULE.bazel");
			requireThat(in.available(), "inputAvailable").isEqualTo(0);
			requireThat(System.out == standardOutput, "standardOutput").isTrue();
			requireThat(System.err == standardError, "standardError").isTrue();
		}
	}

	/**
	 * Keeps every fixture input byte, timestamp and permission set after success, drift and operational failure.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void preservesInputBytesAndModes() throws IOException
	{
		List<Map<String, String>> scenarios = List.of(baseFiles(),
			files(MODULE_FILE, MODULE.replace(ZERO, ONES), MANIFEST_FILE, MANIFEST),
			files(MODULE_FILE, "", LOCK_FILE, "[[package]\n"));
		List<Integer> statuses = List.of(0, 1, 1);
		for (int index = 0; index < scenarios.size(); ++index)
		{
			try (Fixture fixture = new Fixture(scenarios.get(index)))
			{
				fixture.freeze();
				Map<String, String> before = snapshot(fixture.root());
				requireThat(fixture.run(), "status" + index).isEqualTo(statuses.get(index));
				requireThat(snapshot(fixture.root()), "inputs" + index).isEqualTo(before);
			}
		}
	}

	/**
	 * Captures the identity of every regular file below a root.
	 *
	 * @param root directory to inspect
	 * @return permission text, modification time and encoded bytes by relative path
	 * @throws IOException if inspection fails
	 */
	private static Map<String, String> snapshot(Path root) throws IOException
	{
		Map<String, String> result = new TreeMap<>();
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.filter(Files::isRegularFile).toList())
			{
				String mode = "";
				if (POSIX)
					mode = PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
				result.put(root.relativize(path).toString(), mode + ":" + Files.getLastModifiedTime(path).toMillis() +
					":" + Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
			}
		}
		return result;
	}

	/**
	 * Resolves an absolute root independently of the process working directory.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void usesExplicitRepositoryRoot() throws IOException
	{
		String workingDirectory = System.getProperty("user.dir");
		try (Fixture fixture = new Fixture(baseFiles()))
		{
			requireThat(fixture.root().isAbsolute(), "absoluteRoot").isTrue();
			requireThat(fixture.run(), "status").isEqualTo(0);
			requireThat(fixture.output(), "stdout").isEqualTo(success(fixture.root()));
			requireThat(System.getProperty("user.dir"), "workingDirectory").isEqualTo(workingDirectory);
		}
	}

	/** Output buffer that records whether it was closed. */
	private static final class ClosingProbe extends ByteArrayOutputStream
	{
		/**
		 * Whether {@link #close()} was called.
		 */
		private boolean closed;

		/**
		 * Creates an open buffer.
		 */
		private ClosingProbe()
		{
		}

		/**
		 * Reports whether the stream was closed.
		 *
		 * @return true after the first close
		 */
		private boolean isClosed()
		{
			return closed;
		}

		/** Records the close request. */
		@Override
		public void close()
		{
			closed = true;
		}
	}

	/** Temporary repository whose lifetime is owned by the test method that creates it. */
	private static final class Fixture implements AutoCloseable
	{
		/** Fixture root, resolved through symbolic links. */
		private final Path root;
		/** Captured standard output of the last invocation. */
		private ByteArrayOutputStream output = new ByteArrayOutputStream();
		/** Captured diagnostics of the last invocation. */
		private ByteArrayOutputStream errors = new ByteArrayOutputStream();

		/**
		 * Creates a repository holding the given files.
		 *
		 * @param files content by relative path; a leading {@code !} writes ISO-8859-1 bytes
		 * @throws IOException if storage fails
		 */
		private Fixture(Map<String, String> files) throws IOException
		{
			root = Files.createTempDirectory("verify-v8-module-checksums").toRealPath();
			for (Map.Entry<String, String> file : files.entrySet())
				write(file.getKey(), file.getValue());
		}

		/**
		 * Returns the repository root.
		 *
		 * @return the fixture root
		 */
		private Path root()
		{
			return root;
		}

		/**
		 * Writes one file below the root.
		 *
		 * @param path relative path; a leading {@code !} writes ISO-8859-1 bytes
		 * @param text file content
		 * @throws IOException if storage fails
		 */
		private void write(String path, String text) throws IOException
		{
			Path target = root.resolve(path);
			byte[] content = text.getBytes(StandardCharsets.UTF_8);
			if (path.startsWith("!"))
			{
				target = root.resolve(path.substring(1));
				content = text.getBytes(StandardCharsets.ISO_8859_1);
			}
			Files.createDirectories(target.getParent());
			Files.write(target, content);
		}

		/**
		 * Gives every file an old timestamp and alternating restrictive permissions.
		 *
		 * @throws IOException if storage fails
		 */
		private void freeze() throws IOException
		{
			try (Stream<Path> paths = Files.walk(root))
			{
				int count = 0;
				for (Path path : paths.filter(Files::isRegularFile).sorted().toList())
				{
					Files.setLastModifiedTime(path, FileTime.fromMillis(1_000_000_000_000L));
					if (POSIX)
					{
						String permissions = "rw-r-----";
						if (count % 2 == 0)
							permissions = "r--------";
						Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
					}
					++count;
				}
			}
		}

		/**
		 * Runs the command against this repository.
		 *
		 * @return the command status
		 */
		private int run()
		{
			return runWith(COMMAND, root.toString());
		}

		/**
		 * Runs arbitrary command arguments with fresh captured streams.
		 *
		 * @param arguments command name followed by its arguments
		 * @return the command status
		 */
		private int runWith(String... arguments)
		{
			output = new ByteArrayOutputStream();
			errors = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
			{
				return Main.run(arguments, new ByteArrayInputStream(new byte[0]), out, err);
			}
		}

		/**
		 * Returns the captured standard output.
		 *
		 * @return the output text
		 */
		private String output()
		{
			return output.toString(StandardCharsets.UTF_8);
		}

		/**
		 * Returns the captured diagnostics.
		 *
		 * @return the diagnostic text
		 */
		private String errors()
		{
			return errors.toString(StandardCharsets.UTF_8);
		}

		/**
		 * Deletes the fixture.
		 *
		 * @throws IOException if deletion fails
		 */
		@Override
		public void close() throws IOException
		{
			try (Stream<Path> paths = Files.walk(root))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}
}
