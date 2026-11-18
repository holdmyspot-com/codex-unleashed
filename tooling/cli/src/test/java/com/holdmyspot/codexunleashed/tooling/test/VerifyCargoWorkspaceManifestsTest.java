package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.Main;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies the packaged Cargo workspace manifest command through {@link Main#run}.
 */
public final class VerifyCargoWorkspaceManifestsTest
{
	/** Exact accepted exception consumed by every clean fixture. */
	private static final String EXCEPTION_MANIFEST = "[package]\nname = \"codex-v8-poc\"\n" +
		"version.workspace = true\nedition.workspace = true\nlicense.workspace = true\n" +
		"[lints]\nworkspace = true\n[features]\nsandbox = [\"v8/v8_enable_sandbox\"]\n";
	/** Prevents construction by callers. */
	public VerifyCargoWorkspaceManifestsTest()
	{
	}

	/**
	 * The smallest accepted fixture enters Main through the public command boundary.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void acceptsWorkspacePolicy() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Result result = fixture.run();
			requireThat(result.status(), "status").isEqualTo(0);
			requireThat(result.out(), "stdout").isEmpty();
			requireThat(result.err(), "stderr").isEmpty();
		}
	}

	/**
	 * Requires all three metadata inheritance tables and the literal workspace lint opt-in.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void requiresBooleanWorkspaceInheritance() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.write("codex-rs/sample/Cargo.toml", "[package]\nname = \"codex-sample\"\n" +
				"version = 1\nedition = \"true\"\nlicense.workspace = false\n[lints]\nworkspace = 1\n");
			Result result = fixture.run();
			requireThat(result.status(), "status").isEqualTo(1);
			requireThat(result.out(), "field_order").contains("set `version.workspace = true` in `[package]`\n" +
				"  - set `edition.workspace = true` in `[package]`\n" +
				"  - set `license.workspace = true` in `[package]`\n" +
				"  - add `[lints]` with `workspace = true`\n");
			requireThat(result.err(), "stderr").isEmpty();
			fixture.write("codex-rs/sample/Cargo.toml", "[package]\nname = \"codex-sample\"\n" +
				"version.workspace = true\nedition.workspace = true\nlicense.workspace = true\n" +
				"[lints]\nworkspace = true\n");
			requireThat(fixture.run().status(), "literal_booleans_accepted").isEqualTo(0);
		}
	}

	/**
	 * Applies name policy only to the established top-level and utility directory depths.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void checksNamesByDirectoryDepth() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.write("codex-rs/sample/Cargo.toml", packageManifest("codex-sample"));
			fixture.write("codex-rs/codex-kept/Cargo.toml", packageManifest("codex-kept"));
			fixture.write("codex-rs/windows-sandbox-rs/Cargo.toml", packageManifest("codex-windows-sandbox"));
			fixture.write("codex-rs/utils/format/Cargo.toml", packageManifest("codex-utils-format"));
			fixture.write("codex-rs/utils/path-utils/Cargo.toml", packageManifest("codex-utils-path"));
			fixture.write("codex-rs/deep/inner/Cargo.toml", packageManifest("anything"));
			fixture.write("codex-rs/Cargo.toml", "[workspace]\nmembers = [\"v8-poc\"]\n" + packageManifest("workspace-root"));
			Result result = fixture.run();
			requireThat(result.status(), "status").isEqualTo(0);
			requireThat(result.out(), "output").isEmpty();
			requireThat(result.err(), "errors").isEmpty();
			fixture.write("codex-rs/sample/Cargo.toml", packageManifest("wrong-name"));
			Result wrongName = fixture.run();
			requireThat(wrongName.status(), "wrong_name_status").isEqualTo(1);
			requireThat(wrongName.out(), "actual_name_diagnostic").contains(
				"set `[package].name` to `codex-sample` (found `wrong-name`)");
			fixture.write("codex-rs/sample/Cargo.toml", "[package]\nversion.workspace = true\nedition.workspace = true\n" +
				"license.workspace = true\n[lints]\nworkspace = true\n");
			requireThat(fixture.run().out(), "absent_actual_name").contains(
				"set `[package].name` to `codex-sample` (found `None`)");
			fixture.write("codex-rs/sample/Cargo.toml", "[package]\nname = 1\nversion.workspace = true\n" +
				"edition.workspace = true\n" +
				"license.workspace = true\n[lints]\nworkspace = true\n");
			requireThat(fixture.run().out(), "non-string_actual_name").contains(
				"set `[package].name` to `codex-sample` (found `1`)");
		}
	}

	/**
	 * Preserves only the exact existing v8-poc feature table and reports stale exception use.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void keepsOnlyExistingFeatureException() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.write("codex-rs/sample/Cargo.toml", packageManifest("codex-sample") +
				"[features]\ncode-mode = []\n");
			Result extraFeature = fixture.run();
			requireThat(extraFeature.status(), "extra_feature_status").isEqualTo(1);
			requireThat(extraFeature.out(), "new_feature_finding").contains(
				"remove `[features]`; new workspace crate features are not allowed");
			fixture.write("codex-rs/v8-poc/Cargo.toml", packageManifest("codex-v8-poc") +
				"[features]\nsandbox = [\"v8/v8_enable_sandbox\"]\nother = []\n");
			Result wrongException = fixture.run();
			requireThat(wrongException.status(), "wrong_exception_status").isEqualTo(1);
			requireThat(wrongException.out(), "exception-content_finding").contains(
				"limit `[features]` to the existing exception list");
			requireThat(wrongException.out(), "exception_is_still_used").doesNotContain("stale `[features]` exception");
			fixture.write("codex-rs/v8-poc/Cargo.toml", packageManifest("codex-v8-poc"));
			Result staleException = fixture.run();
			requireThat(staleException.status(), "stale_exception_status").isEqualTo(1);
			requireThat(staleException.out(), "stale_exception_finding").contains(
				"remove the stale `[features]` exception from `MANIFEST_FEATURE_EXCEPTIONS`");
		}
	}

	/**
	 * Checks root, workspace, and all target dependency tables in declaration and section order.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void checksEveryDependencySection() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.write("codex-rs/Cargo.toml", "[workspace]\n[dependencies]\n" +
				"core = { package = \"codex-core\", features = [\"x\"] }\n" +
				"[dev-dependencies]\ncore-dev = { package = \"codex-core\", features = [\"y\"] }\n" +
				"[build-dependencies]\ncore-build = { package = \"codex-core\", features = [\"z\"] }\n" +
				"[workspace.dependencies]\ncore-workspace = { package = \"codex-core\", features = [\"w\"] }\n" +
				"[target.'cfg(unix)'.dependencies]\ncore-target = { package = \"codex-core\", features = [\"t\"] }\n" +
				"[target.'cfg(unix)'.dev-dependencies]\nignored = \"1\"\n" +
				"[target.'cfg(unix)'.build-dependencies]\n" +
				"core-target-build = { package = \"codex-core\", features = [\"b\"] }\n");
			fixture.write("codex-rs/core/Cargo.toml", packageManifest("codex-core"));
			Result result = fixture.run();
			requireThat(result.status(), "status").isEqualTo(1);
			int rootIndex = result.out().indexOf("[dependencies].core");
			int devIndex = result.out().indexOf("[dev-dependencies].core-dev");
			int buildIndex = result.out().indexOf("[build-dependencies].core-build");
			int workspaceIndex = result.out().indexOf("[workspace.dependencies].core-workspace");
			int targetIndex = result.out().indexOf("[target.cfg(unix).dependencies].core-target");
			int targetBuildIndex = result.out().indexOf("[target.cfg(unix).build-dependencies].core-target-build");
			requireThat(rootIndex < devIndex && devIndex < buildIndex && buildIndex < workspaceIndex &&
				targetIndex < targetBuildIndex, "dependency_section_order").isEqualTo(true);
			requireThat(result.out(), "non-table_dependency_ignored").doesNotContain("ignored");
		}
	}

	/**
	 * Rejects feature toggles for internal dependencies resolved by package identity or path.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void rejectsInternalFeatureToggles() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.write("codex-rs/app/Cargo.toml", packageManifest("codex-app") +
				"[dependencies]\nrenamed = { package = \"codex-core\", features = [\"x\"] }\n" +
				"by_path = { path = \"../core/missing\", default-features = false }\n" +
				"external = { path = \"../../outside\", features = [\"ok\"], default-features = false }\n" +
				"nonstr_package = { package = 1, features = [\"not-internal\"] }\n");
			fixture.write("codex-rs/core/Cargo.toml", packageManifest("codex-core"));
			Result result = fixture.run();
			requireThat(result.status(), "status").isEqualTo(1);
			requireThat(result.out(), "name-based_internal_features").contains(
				"remove `features = [...]` from workspace dependency `[dependencies].renamed`");
			requireThat(result.out(), "dangling_internal_path_toggle").contains(
				"remove `default-features = false` from workspace dependency `[dependencies].by_path`");
			requireThat(result.out(), "external_path_allowed").doesNotContain("[dependencies].external");
			requireThat(result.out(), "non-string_package_does_not_fall_back_to_key").doesNotContain("nonstr_package");
		}
	}

	/**
	 * Resolves both outward and inward symbolic-link aliases and never traverses linked directories.
	 *
	 * @throws IOException if fixture storage or symbolic links fail
	 */
	@Test
	public void resolvesDependencyAliases() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path cargoRoot = fixture.root().resolve("codex-rs");
			Path external = fixture.root().resolve("outside");
			Files.createDirectories(cargoRoot.resolve("core"));
			Files.createDirectories(external);
			Files.writeString(external.resolve("Cargo.toml"), "not valid TOML [", StandardCharsets.UTF_8);
			Files.createSymbolicLink(cargoRoot.resolve("core-alias"), Path.of("core"));
			Files.createSymbolicLink(fixture.root().resolve("outside-alias"), cargoRoot.resolve("core"));
			Files.createSymbolicLink(cargoRoot.resolve("external-alias"), external);
			fixture.write("codex-rs/app/Cargo.toml", packageManifest("codex-app") +
				"[dependencies]\ninward = { path = \"../core-alias/missing\", features = [\"x\"] }\n" +
				"outward = { path = \"../../outside-alias/missing\", features = [\"x\"] }\n" +
				"external = { path = \"../external-alias\", features = [\"x\"] }\n");
			Result result = fixture.run();
			requireThat(result.status(), "status").isEqualTo(1);
			requireThat(result.out(), "inward_alias_internal").contains("[dependencies].inward");
			requireThat(result.out(), "outward_alias_resolves_inside").contains("[dependencies].outward");
			requireThat(result.out(), "external_alias_resolves_outside").doesNotContain("[dependencies].external");
			requireThat(result.err(), "linked_directory_is_not_traversed").isEmpty();
		}
	}

	/**
	 * Rejects literal optional=true for internal and external dependencies without coercion.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void rejectsOptionalDependencies() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.write("codex-rs/sample/Cargo.toml", packageManifest("codex-sample") +
				"[dependencies]\nexternal = { optional = true }\ninternal = { package = \"codex-core\", optional = true }\n" +
				"false_value = { optional = false }\ninteger_value = { optional = 1 }\n" +
				"string_value = { optional = \"true\" }\n");
			fixture.write("codex-rs/core/Cargo.toml", packageManifest("codex-core"));
			Result result = fixture.run();
			requireThat(result.status(), "status").isEqualTo(1);
			int literalTrueCount = count(result.out(), "remove `optional = true`");
			requireThat(literalTrueCount, "literal_true_count").isEqualTo(2);
			requireThat(result.out(), "false_optional_ignored").doesNotContain("false_value");
			requireThat(result.out(), "integer_optional_ignored").doesNotContain("integer_value");
			requireThat(result.out(), "string_optional_ignored").doesNotContain("string_value");
		}
	}

	/**
	 * Includes hidden and nonmember manifests in identity collection while skipping package-less descendants.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void validatesNonmemberManifests() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.write("codex-rs/.hidden/Cargo.toml", packageManifest("codex-hidden"));
			fixture.write("codex-rs/deep/nonmember/Cargo.toml", packageManifest("codex-nonmember"));
			fixture.write("codex-rs/package-less/Cargo.toml", "[features]\nallowed-to-skip = []\n");
			fixture.write("codex-rs/app/Cargo.toml", packageManifest("codex-app") +
				"[dependencies]\nhidden = { package = \"codex-hidden\", features = [\"x\"] }\n" +
				"nonmember = { package = \"codex-nonmember\", default-features = false }\n");
			Result result = fixture.run();
			requireThat(result.status(), "status").isEqualTo(1);
			requireThat(result.out(), "hidden_manifest_identity").contains("[dependencies].hidden");
			requireThat(result.out(), "deep_nonmember_identity").contains("[dependencies].nonmember");
			requireThat(result.out(), "package-less_manifest_skips_checks").doesNotContain("package-less/Cargo.toml");
		}
	}

	/**
	 * Preserves complete file and field order, Unicode path sorting, and the final stale-exception finding.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void ordersCompletePolicyReport() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.write("codex-rs/\uE000/Cargo.toml", "[package]\nname = 1\nversion = 1\n" +
				"edition = \"local\"\nlicense.workspace = false\n[features]\nx = []\n" +
				"[dependencies]\noutside = { optional = true }\n");
			fixture.write("codex-rs/\uD800\uDC00/Cargo.toml", "[package]\nname = false\n" +
				"[dependencies]\noutside = { optional = true }\n");
			fixture.write("codex-rs/v8-poc/Cargo.toml", packageManifest("codex-v8-poc") +
				"[dependencies]\nexternal = { optional = true }\n");
			Result result = fixture.run();
			requireThat(result.status(), "status").isEqualTo(1);
			int bmp = result.out().indexOf("codex-rs/\uE000/Cargo.toml:");
			int supplementary = result.out().indexOf("codex-rs/\uD800\uDC00/Cargo.toml:");
			requireThat(bmp < supplementary, "code_point_path_order").isEqualTo(true);
			int fields = result.out().indexOf("set `version.workspace = true`", bmp);
			int lints = result.out().indexOf("add `[lints]`", bmp);
			int name = result.out().indexOf("set `[package].name`", bmp);
			int feature = result.out().indexOf("remove `[features]`", bmp);
			int dependency = result.out().indexOf("remove `optional = true`", bmp);
			requireThat(fields < lints && lints < name && name < feature && feature < dependency,
				"within-file_policy_order").isEqualTo(true);
			int v8Header = result.out().indexOf("codex-rs/v8-poc/Cargo.toml:");
			int v8Optional = result.out().indexOf("remove `optional = true`", v8Header);
			requireThat(result.out().indexOf("remove the stale `[features]` exception") > v8Optional,
				"stale_exception_appended_to_v8_path").isEqualTo(true);
		}
	}

	/**
	 * Rejects missing, malformed, duplicate, and unreadable manifests without a partial policy report.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void rejectsIncompleteInputEvidence() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Result missingRootArgument = run(new String[] {"verify-cargo-workspace-manifests"});
			requireThat(missingRootArgument.status(), "missing_argument_status").isEqualTo(2);
			requireThat(missingRootArgument.err(), "missing_argument_usage").contains("Usage: codex-tooling");
			Result extraArgument = run(new String[] {"verify-cargo-workspace-manifests",
				fixture.root().toString(), "--help"});
			requireThat(extraArgument.status(), "extra_argument_status").isEqualTo(2);
			Result absentWorkspace = run(new String[] {"verify-cargo-workspace-manifests",
				fixture.root().resolve("absent").toString()});
			requireThat(absentWorkspace.status(), "missing_root_status").isEqualTo(1);
			requireThat(absentWorkspace.out(), "missing_root_report").isEmpty();
			fixture.write("codex-rs/broken/Cargo.toml", "[package\n");
			Result malformed = fixture.run();
			requireThat(malformed.status(), "malformed_status").isEqualTo(1);
			requireThat(malformed.out(), "malformed_report").isEmpty();
			requireThat(malformed.err(), "malformed_diagnostic").contains("codex-rs/broken/Cargo.toml: invalid TOML");
			fixture.write("codex-rs/broken/Cargo.toml", "value = 1\nvalue = 2\n");
			Result duplicate = fixture.run();
			requireThat(duplicate.status(), "duplicate_status").isEqualTo(1);
			requireThat(duplicate.out(), "duplicate_report").isEmpty();
			Files.createDirectories(fixture.root().resolve("codex-rs/unreadable/Cargo.toml"));
			Result unreadable = fixture.run();
			requireThat(unreadable.status(), "unreadable_path_status").isEqualTo(1);
			requireThat(unreadable.out(), "unreadable_path_report").isEmpty();
		}
	}

	/**
	 * Creates a fully inherited package table.
	 *
	 * @param name input used by this helper
	 *
	 * @return result of the helper operation
	 */
	private static String packageManifest(String name)
	{
		return "[package]\nname = \"" + name + "\"\nversion.workspace = true\nedition.workspace = true\n" +
			"license.workspace = true\n[lints]\nworkspace = true\n";
	}

	/**
	 * Counts non-overlapping occurrences.
	 *
	 * @param text input used by this helper
	 *
	 * @param needle input used by this helper
	 *
	 * @return result of the helper operation
	 */
	private static int count(String text, String needle)
	{
		int result = 0;
		int offset = 0;
		while (true)
		{
			offset = text.indexOf(needle, offset);
			if (offset < 0)
				break;
			result += 1;
			offset += needle.length();
		}
		return result;
	}

	/**
	 * Invokes the exact public dispatcher contract with caller-owned streams.
	 *
	 * @param arguments input used by this helper
	 *
	 * @return result of the helper operation
	 */
	private static Result run(String[] arguments)
	{
		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(arguments, new ByteArrayInputStream(new byte[0]), out, err, Map.of());
			return new Result(status, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
		}
	}

	/**
	 * Public command invocation rooted at one isolated temporary directory.
	 *
	 * @param root temporary directory used for command invocation
	 */
	private record Fixture(Path root) implements AutoCloseable
	{
		/**
		 * Creates an isolated fixture containing the workspace and allowed exception manifests.
		 *
		 * @throws IOException if fixture storage fails
		 */
		private Fixture() throws IOException
		{
			this(Files.createTempDirectory("cargo-manifest-policy"));
			write("codex-rs/Cargo.toml", "[workspace]\nmembers = [\"v8-poc\"]\n");
			write("codex-rs/v8-poc/Cargo.toml", EXCEPTION_MANIFEST);
		}

		/**
		 * Writes one UTF-8 fixture file and creates its parent directories.
		 *
		 * @param relative path below the fixture root
		 * @param content UTF-8 file contents
		 * @throws IOException if fixture storage fails
		 */
		private void write(String relative, String content) throws IOException
		{
			Path path = root.resolve(relative);
			Files.createDirectories(path.getParent());
			Files.writeString(path, content, StandardCharsets.UTF_8);
		}

		/**
		 * Runs the public command with this fixture root.
		 *
		 * @return captured command status and streams
		 */
		private Result run()
		{
			return VerifyCargoWorkspaceManifestsTest.run(new String[] {"verify-cargo-workspace-manifests", root.toString()});
		}

		@Override
		public void close() throws IOException
		{
			try (Stream<Path> paths = Files.walk(root))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.deleteIfExists(path);
			}
		}
	}

	/**
	 * Captured public command result.
	 *
	 * @param status command exit status
	 *
	 * @param out standard output
	 *
	 * @param err standard error
	 */
	private record Result(int status, String out, String err)
	{
	}
}
