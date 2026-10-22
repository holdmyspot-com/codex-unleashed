package com.holdmyspot.codexunleashed.tooling;

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
import static org.testng.Assert.expectThrows;

/** Verifies workspace manifest decisions using real TOML files and isolated filesystem inputs. */
public final class CargoManifestPolicyTest
{
	/** Creates manifest policy tests. */
	public CargoManifestPolicyTest()
	{
	}

	/**
	 * Accepts inherited metadata, exact naming exceptions, virtual manifests, and the retained feature exception.
	 *
	 * @throws IOException if fixture access, validation, or cleanup fails
	 */
	@Test
	public void acceptsWorkspaceManifests() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.write("windows-sandbox-rs", packageText("codex-windows-sandbox"));
			fixture.write("utils/path-utils", packageText("codex-utils-path"));
			fixture.write("codex-helper", packageText("codex-helper"));
			fixture.write("nested/virtual", "[workspace]\n");
			requireThat(fixture.check(Map.of()), "status").isEqualTo(0);
			requireThat(fixture.output(), "output").isEmpty();
		}
	}

	/**
	 * Rejects concrete or string-valued inheritance and lints, and preserves case-sensitive crate names.
	 *
	 * @throws IOException if fixture access, validation, or cleanup fails
	 */
	@Test
	public void rejectsMetadataAndNaming() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.write("example", """
				[package]
				name = "Codex-example"
				version = "1.0.0"
				edition.workspace = "true"
				license.workspace = true
				[lints]
				workspace = "true"
				""");
			requireThat(fixture.check(Map.of()), "status").isEqualTo(1);
			String output = fixture.output();
			requireThat(output, "output").contains("set `version.workspace = true` in `[package]`");
			requireThat(output, "output").contains("set `edition.workspace = true` in `[package]`");
			requireThat(output, "output").doesNotContain("set `license.workspace = true`");
			requireThat(output, "output").contains("add `[lints]` with `workspace = true`");
			requireThat(output, "output").contains("set `[package].name` to `codex-example` (found `Codex-example`)");
		}
	}

	/**
	 * Detects feature toggles for renamed internal packages, in-tree paths, and target-specific dependency tables.
	 *
	 * @throws IOException if fixture access, validation, or cleanup fails
	 */
	@Test
	public void checksAllDependencySections() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.write("example", packageText("codex-example") + """
				[dependencies]
				renamed = { package = "codex-v8-poc", features = [] }
				external = { version = "1", features = ["allowed"], default-features = false }
				optional = { version = "1", optional = true }
				stringOptional = { version = "1", optional = "true" }
				[dev-dependencies]
				local = { path = "../missing", default-features = false }
				[build-dependencies]
				local = { path = "../v8-poc", features = ["x"] }
				[target.'cfg(unix)'.dependencies]
				local = { path = "../v8-poc", optional = true }
				""");
			fixture.writeRoot("""
				[workspace]
				[workspace.dependencies]
				local = { package = "codex-example", features = [] }
				""");
			requireThat(fixture.check(Map.of()), "status").isEqualTo(1);
			String output = fixture.output();
			for (String label : new String[]{"[dependencies].renamed", "[dependencies].optional",
				"[dev-dependencies].local", "[build-dependencies].local", "[target.cfg(unix).dependencies].local",
				"[workspace.dependencies].local"})
				requireThat(output, "output").contains(label);
			requireThat(output, "output").doesNotContain("[dependencies].external");
			requireThat(output, "output").doesNotContain("[dependencies].stringOptional");
		}
	}

	/**
	 * Requires exact exception mappings, including array order, and rejects stale or new feature tables.
	 *
	 * @throws IOException if fixture access, validation, or cleanup fails
	 */
	@Test
	public void checksFeatureExceptionCoverage() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.write("v8-poc", packageText("codex-v8-poc") + "[features]\nsandbox = [\"different\"]\n");
			fixture.write("example", packageText("codex-example") + "[features]\n");
			requireThat(fixture.check(Map.of()), "status").isEqualTo(1);
			requireThat(fixture.output(), "output").contains("expected sandbox = [\"v8/v8_enable_sandbox\"]");
			requireThat(fixture.output(), "output").contains(
				"remove `[features]`; new workspace crate features are not allowed");
			fixture.write("example", packageText("codex-example"));
			fixture.write("v8-poc", packageText("codex-v8-poc"));
			requireThat(fixture.check(Map.of("ALLOW_STALE_CODE_MODE_FEATURE_EXCEPTION", "1")), "status").isEqualTo(1);
			requireThat(fixture.output(), "output").contains("remove the stale `[features]` exception");
		}
	}

	/**
	 * Rejects malformed TOML as an operational failure without writing a partial policy report.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void rejectsMalformedManifest() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.write("example", "[package\n");
			expectThrows(IOException.class, () -> fixture.check(Map.of()));
			requireThat(fixture.output(), "output").isEmpty();
		}
	}

	/**
	 * Accepts empty virtual and root manifests when the independent retained feature exception is satisfied.
	 *
	 * @throws IOException if fixture access, validation, or cleanup fails
	 */
	@Test
	public void acceptsEmptyManifests() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.writeRoot("");
			fixture.write("empty", "");
			requireThat(fixture.check(Map.of()), "status").isEqualTo(0);
			requireThat(fixture.output(), "output").isEmpty();
		}
	}

	/**
	 * Resolves dangling symlink targets before deciding whether nonexistent dependency paths are internal.
	 *
	 * @throws IOException if fixture access, validation, or cleanup fails
	 */
	@Test
	public void resolvesDanglingDependencyLinks() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Files.createSymbolicLink(fixture.root.resolve("codex-rs/external-link"), fixture.root.resolve("outside-missing"));
			fixture.write("example", packageText("codex-example") + """
				[dependencies]
				external = { path = "../external-link/member", features = ["permitted"], default-features = false }
				""");
			requireThat(fixture.check(Map.of()), "status").isEqualTo(0);
		}
	}

	/**
	 * Identifies internal dependencies when the caller supplies a symbolic alias for the repository root.
	 *
	 * @throws IOException if fixture access, validation, or cleanup fails
	 */
	@Test
	public void resolvesRepositoryAliases() throws IOException
	{
		try (Fixture fixture = new Fixture(); PrintStream out = new PrintStream(fixture.output, true,
			StandardCharsets.UTF_8))
		{
			fixture.write("example", packageText("codex-example") +
				"[dependencies]\nlocal = { path = \"../missing\", default-features = false }\n");
			Path alias = Files.createSymbolicLink(fixture.root.resolve("alias"), fixture.root);
			requireThat(CargoManifestPolicy.check(alias, out, Map.of()), "status").isEqualTo(1);
			requireThat(fixture.output(), "output").contains(
				"remove `default-features = false` from workspace dependency");
		}
	}

	/**
	 * Keeps native TOML dates distinct from quoted package-name strings when finding internal dependencies.
	 *
	 * @throws IOException if fixture access, validation, or cleanup fails
	 */
	@Test
	public void distinguishesTemporalPackageNames() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.write("invalid", packageText("codex-invalid").replace("\"codex-invalid\"", "2000-01-01"));
			fixture.write("consumer", packageText("codex-consumer") +
				"[dependencies]\nexternal = { package = \"2000-01-01\", features = [\"allowed\"] }\n");
			requireThat(fixture.check(Map.of()), "status").isEqualTo(1);
			requireThat(fixture.output(), "output").contains("codex-rs/invalid/Cargo.toml:");
			requireThat(fixture.output(), "output").doesNotContain("codex-rs/consumer/Cargo.toml:");
		}
	}

	/**
	 * Preserves upstream's additional code-mode exception without relaxing the project policy.
	 *
	 * @throws IOException if fixture access, validation, or cleanup fails
	 */
	@Test
	public void preservesUpstreamExceptionPolicy() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.write("code-mode", packageText("codex-code-mode") +
				"[features]\nsandbox = [\"v8/v8_enable_sandbox\"]\n");
			requireThat(fixture.check(Map.of()), "status").isEqualTo(1);
			requireThat(fixture.checkUpstream(Map.of()), "upstreamStatus").isEqualTo(0);
			fixture.write("code-mode", packageText("codex-code-mode"));
			requireThat(fixture.checkUpstream(Map.of()), "upstreamStatus").isEqualTo(1);
			requireThat(fixture.output(), "output").contains("codex-rs/code-mode/Cargo.toml:");
			requireThat(fixture.checkUpstream(Map.of("ALLOW_STALE_CODE_MODE_FEATURE_EXCEPTION", "true")),
				"upstreamStatus").isEqualTo(1);
			requireThat(fixture.checkUpstream(Map.of("ALLOW_STALE_CODE_MODE_FEATURE_EXCEPTION", "1")),
				"upstreamStatus").isEqualTo(0);
			fixture.write("code-mode", packageText("codex-code-mode") + "[features]\nsandbox = [\"incorrect\"]\n");
			requireThat(fixture.checkUpstream(Map.of("ALLOW_STALE_CODE_MODE_FEATURE_EXCEPTION", "1")),
				"upstreamStatus").isEqualTo(1);
			requireThat(fixture.output(), "output").contains("limit `[features]` to the existing exception list");
		}
	}

	/**
	 * Executes the project manifest policy through the public CLI dispatcher.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void dispatchesManifestPolicy() throws IOException
	{
		try (Fixture fixture = new Fixture(); PrintStream out = new PrintStream(fixture.output, true,
			StandardCharsets.UTF_8))
		{
			requireThat(Main.run(new String[]{"verify-cargo-workspace-manifests", fixture.root.toString()},
				java.io.InputStream.nullInputStream(), out, out), "status").isEqualTo(0);
			requireThat(fixture.output(), "output").isEmpty();
		}
	}

	/**
	 * Returns a policy-compliant crate definition.
	 *
	 * @param name the exact crate name
	 * @return the TOML definition
	 */
	private static String packageText(String name)
	{
		return "[package]\nname = \"" + name + "\"\nversion.workspace = true\nedition.workspace = true\n" +
			"license.workspace = true\n[lints]\nworkspace = true\n";
	}

	/** Owns all manifest files and captured policy output. */
	private static final class Fixture implements AutoCloseable
	{
		private final Path root = Files.createTempDirectory("cargo-manifest-policy-");
		private final ByteArrayOutputStream output = new ByteArrayOutputStream();

		/**
		 * Initializes the workspace and its required feature exception.
		 *
		 * @throws IOException if fixture creation fails
		 */
		private Fixture() throws IOException
		{
			writeRoot("[workspace]\n");
			write("v8-poc", packageText("codex-v8-poc") + "[features]\nsandbox = [\"v8/v8_enable_sandbox\"]\n");
		}

		/**
		 * Writes the root manifest.
		 *
		 * @param text the literal TOML
		 * @throws IOException if writing fails
		 */
		private void writeRoot(String text) throws IOException
		{
			Files.createDirectories(root.resolve("codex-rs"));
			Files.writeString(root.resolve("codex-rs/Cargo.toml"), text);
		}

		/**
		 * Writes one crate manifest.
		 *
		 * @param directory the crate path beneath codex-rs
		 * @param text the literal TOML
		 * @throws IOException if writing fails
		 */
		private void write(String directory, String text) throws IOException
		{
			Path parent = Files.createDirectories(root.resolve("codex-rs").resolve(directory));
			Files.writeString(parent.resolve("Cargo.toml"), text);
		}

		/**
		 * Checks the fixture with an explicit environment and fresh output capture.
		 *
		 * @param environment the caller environment
		 * @return the policy status
		 * @throws IOException if validation fails operationally
		 */
		private int check(Map<String, String> environment) throws IOException
		{
			output.reset();
			try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8))
			{
				return CargoManifestPolicy.check(root, stream, environment);
			}
		}

		/**
		 * Checks the upstream exception policy with a fresh report.
		 *
		 * @param environment the explicit caller environment
		 * @return the policy status
		 * @throws IOException if validation fails operationally
		 */
		private int checkUpstream(Map<String, String> environment) throws IOException
		{
			output.reset();
			try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8))
			{
				return CargoManifestPolicy.checkUpstream(root, stream, environment);
			}
		}

		/**
		 * Returns the captured UTF-8 report.
		 *
		 * @return the report text
		 */
		private String output()
		{
			return output.toString(StandardCharsets.UTF_8);
		}

		/**
		 * Deletes owned fixtures without following directory links.
		 *
		 * @throws IOException if cleanup fails
		 */
		@Override
		public void close() throws IOException
		{
			try (Stream<Path> files = Files.walk(root))
			{
				for (Path path : files.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}
}
