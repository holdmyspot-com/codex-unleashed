package com.holdmyspot.codexunleashed.tooling.release;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies real offline Cargo builds and prebuilt package source selection with isolated storage.
 */
public final class PackageSourceBuildsTest
{
	/**
	 * Creates source build tests.
	 */
	public PackageSourceBuildsTest()
	{
	}

	/**
	 * Builds the selected entrypoint, code-mode host, and Linux helper with real offline Cargo.
	 *
	 * @throws IOException if fixture access or building fails
	 */
	@Test
	public void buildsRequestedSourceBinaries() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.prepareCargo();
			try (PrintStream output = new PrintStream(fixture.trace, true, StandardCharsets.UTF_8))
			{
				PackageSourceBuilds.Outputs built = PackageSourceBuilds.build(fixture.request(PackageVariant.APP_SERVER,
					PackageSourceBuilds.Inputs.empty(), "dev", Map.of("V8_FROM_SOURCE", "1")), output);
				Path expected = fixture.cache.resolve("cargo-target/x86_64-unknown-linux-gnu/debug");
				assertEquals(built.entrypoint(), expected.resolve("codex-app-server"));
				assertEquals(built.codeModeHost(), expected.resolve("codex-code-mode-host"));
				assertEquals(built.bwrap(), Optional.of(expected.resolve("bwrap")));
				assertTrue(Files.isRegularFile(built.entrypoint()));
				assertTrue(Files.isRegularFile(built.codeModeHost()));
				assertTrue(Files.isRegularFile(built.bwrap().orElseThrow()));
				assertTrue(built.commandRunner().isEmpty());
				assertTrue(built.sandboxSetup().isEmpty());
				assertEquals(fixture.trace.toString(StandardCharsets.UTF_8),
					"+ cargo build --target x86_64-unknown-linux-gnu --profile dev --bin codex-app-server " +
						"--bin codex-code-mode-host --bin bwrap\n");
				assertFalse(Files.exists(fixture.workspace.resolve("codex-rs/target")));
				assertFalse(Files.exists(fixture.cache.resolve("rusty-v8")));
			}
		}
	}

	/**
	 * Builds only a missing helper without downloading V8 and respects relative and absolute Cargo target paths.
	 *
	 * @throws IOException if fixture access or building fails
	 */
	@Test
	public void buildsOnlyMissingHelper() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.prepareCargo();
			Path entrypoint = Files.write(fixture.root.resolve("prebuilt-entrypoint"), new byte[] {1});
			Path host = Files.write(fixture.root.resolve("prebuilt-host"), new byte[] {2});
			PackageSourceBuilds.Inputs inputs = new PackageSourceBuilds.Inputs(Optional.of(entrypoint), Optional.of(host),
				Optional.empty(), Optional.empty(), Optional.empty());
			for (String target : List.of("../../relative-target", fixture.root.resolve("absolute-target").toString()))
			{
				fixture.trace.reset();
				try (PrintStream output = new PrintStream(fixture.trace, true, StandardCharsets.UTF_8))
				{
					PackageSourceBuilds.Outputs built = PackageSourceBuilds.build(fixture.request(PackageVariant.CODEX,
						inputs, "dev-small", Map.of("CARGO_TARGET_DIR", target, "RUSTY_V8_ARCHIVE", "only-one-override")), output);
					assertEquals(built.entrypoint(), entrypoint.toRealPath());
					assertEquals(built.codeModeHost(), host.toRealPath());
					Path selected = Path.of(target);
					if (!selected.isAbsolute())
						selected = fixture.workspace.resolve("codex-rs").resolve(selected);
					assertEquals(built.bwrap().orElseThrow().toRealPath(),
						selected.resolve("x86_64-unknown-linux-gnu/dev-small/bwrap").toRealPath());
					assertTrue(fixture.trace.toString(StandardCharsets.UTF_8).endsWith("--profile dev-small --bin bwrap\n"));
				}
			}
		}
	}

	/**
	 * Resolves all prebuilt inputs without Cargo and rejects unsupported resources and failed source builds.
	 *
	 * @throws IOException if fixture access fails
	 */
	@Test
	public void validatesPrebuiltAndFailedBuilds() throws IOException
	{
		try (Fixture fixture = Fixture.create(); PrintStream output =
			new PrintStream(fixture.trace, true, StandardCharsets.UTF_8))
		{
			Path binary = Files.write(fixture.root.resolve("binary"), new byte[] {1});
			PackageSourceBuilds.Inputs complete = new PackageSourceBuilds.Inputs(Optional.of(binary), Optional.of(binary),
				Optional.of(binary), Optional.empty(), Optional.empty());
			PackageSourceBuilds.Outputs built = PackageSourceBuilds.build(
				fixture.request(PackageVariant.CODEX, complete, "release", Map.of()), output);
			assertEquals(built.entrypoint(), binary.toRealPath());
			assertEquals(fixture.trace.size(), 0);
			assertFalse(Files.exists(fixture.cache));
			PackageSourceBuilds.Inputs unsupported = new PackageSourceBuilds.Inputs(Optional.of(binary), Optional.of(binary),
				Optional.of(binary), Optional.of(binary), Optional.empty());
			expectThrows(IOException.class, () -> PackageSourceBuilds.build(
				fixture.request(PackageVariant.CODEX, unsupported, "release", Map.of()), output));
			fixture.prepareCargo();
			Files.writeString(fixture.workspace.resolve("codex-rs/src/bin/codex.rs"), "invalid Rust source");
			expectThrows(IOException.class, () -> PackageSourceBuilds.build(fixture.request(PackageVariant.CODEX,
				PackageSourceBuilds.Inputs.empty(), "dev", Map.of("V8_FROM_SOURCE", "1")), output));
		}
	}

	/**
	 * Resolves Windows helper overrides without source execution and rejects Linux-only resource overrides.
	 *
	 * @throws IOException if fixture access or source resolution fails
	 */
	@Test
	public void resolvesWindowsPrebuiltResources() throws IOException
	{
		try (Fixture fixture = Fixture.create(); PrintStream output =
			new PrintStream(fixture.trace, true, StandardCharsets.UTF_8))
		{
			Path binary = Files.write(fixture.root.resolve("binary.exe"), new byte[] {1});
			PackageSourceBuilds.Inputs inputs = new PackageSourceBuilds.Inputs(Optional.of(binary), Optional.of(binary),
				Optional.empty(), Optional.of(binary), Optional.of(binary));
			PackageSourceBuilds.Request initial = fixture.request(PackageVariant.APP_SERVER, inputs, "release", Map.of());
			PackageSourceBuilds.Outputs built = PackageSourceBuilds.build(new PackageSourceBuilds.Request(fixture.workspace,
				PackageTarget.WINDOWS_X86, initial.variant(), inputs, initial.options()), output);
			assertEquals(built.entrypoint(), binary.toRealPath());
			assertEquals(built.commandRunner(), Optional.of(binary.toRealPath()));
			assertEquals(built.sandboxSetup(), Optional.of(binary.toRealPath()));
			assertTrue(built.bwrap().isEmpty());
			assertEquals(fixture.trace.size(), 0);
			PackageSourceBuilds.Inputs unsupported = new PackageSourceBuilds.Inputs(Optional.of(binary), Optional.of(binary),
				Optional.of(binary), Optional.empty(), Optional.empty());
			expectThrows(IOException.class, () -> PackageSourceBuilds.build(new PackageSourceBuilds.Request(fixture.workspace,
				PackageTarget.WINDOWS_X86, initial.variant(), unsupported, initial.options()), output));
			PackageSourceBuilds.Inputs directory = new PackageSourceBuilds.Inputs(Optional.of(fixture.root),
				Optional.of(binary), Optional.empty(), Optional.of(binary), Optional.of(binary));
			expectThrows(IOException.class, () -> PackageSourceBuilds.build(new PackageSourceBuilds.Request(fixture.workspace,
				PackageTarget.WINDOWS_X86, initial.variant(), directory, initial.options()), output));
			assertFalse(Files.exists(fixture.cache));
		}
	}

	/**
	 * Owns a minimal real Cargo checkout and every build cache.
	 */
	private static final class Fixture implements AutoCloseable
	{
		private final Path root;
		private final Path workspace;
		private final Path cache;
		private final ByteArrayOutputStream trace = new ByteArrayOutputStream();

		/**
		 * Defines fixture paths without creating source inputs or build caches.
		 *
		 * @param root the owned fixture directory
		 */
		private Fixture(Path root)
		{
			this.root = root;
			workspace = root.resolve("workspace");
			cache = root.resolve("cache");
		}

		/**
		 * Allocates fixture storage.
		 *
		 * @return the fixture
		 * @throws IOException if allocation fails
		 */
		private static Fixture create() throws IOException
		{
			return new Fixture(Files.createTempDirectory("package-source-builds-"));
		}

		/**
		 * Writes a dependency-free Cargo package with the retained binary names and development profiles.
		 *
		 * @throws IOException if fixture writing fails
		 */
		private void prepareCargo() throws IOException
		{
			Path binaries = Files.createDirectories(workspace.resolve("codex-rs/src/bin"));
			Files.writeString(workspace.resolve("codex-rs/Cargo.toml"), """
				[package]
				name = "source-fixture"
				version = "0.160.0"
				edition = "2024"
				[workspace]
				[profile.dev-small]
				inherits = "dev"
				""");
			for (String name : List.of("codex", "codex-app-server", "codex-code-mode-host", "bwrap"))
				Files.writeString(binaries.resolve(name + ".rs"), "fn main() { println!(\"source fixture\"); }\n");
		}

		/**
		 * Supplies a complete caller environment with isolated Cargo, XDG, and temporary storage.
		 *
		 * @param variant the package variant
		 * @param inputs the prebuilt overrides
		 * @param profile the Cargo profile
		 * @param overrides the explicit caller overrides
		 * @return the build request
		 */
		private PackageSourceBuilds.Request request(PackageVariant variant, PackageSourceBuilds.Inputs inputs,
			String profile, Map<String, String> overrides)
		{
			Map<String, String> environment = new HashMap<>(System.getenv());
			environment.remove("CARGO_TARGET_DIR");
			environment.remove("RUSTY_V8_ARCHIVE");
			environment.remove("RUSTY_V8_SRC_BINDING_PATH");
			environment.remove("V8_FROM_SOURCE");
			environment.put("CARGO_HOME", root.resolve("cargo-home").toString());
			environment.put("CARGO_NET_OFFLINE", "true");
			environment.put("XDG_CACHE_HOME", root.resolve("xdg").toString());
			environment.put("TMPDIR", System.getProperty("java.io.tmpdir"));
			environment.putAll(overrides);
			PackageSourceBuilds.Options options = new PackageSourceBuilds.Options("cargo", profile, cache, environment,
				URI.create("https://github.com/openai/codex/releases/download/"));
			return new PackageSourceBuilds.Request(workspace, PackageTarget.LINUX_X86_GNU, variant, inputs, options);
		}

		/**
		 * Removes every owned source, dependency cache, and build output.
		 *
		 * @throws IOException if cleanup fails
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
