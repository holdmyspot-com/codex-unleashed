package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.PackageIdentity;
import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Checks source and package identity decisions through the command's in-memory process boundary. */
public final class PackageIdentityTest
{
	/** Expected upstream package version shared by independent test fixtures. */
	private static final String VERSION = "0.167.0";
	/** Exact upstream build number shared by independent test fixtures. */
	private static final String BUILD = "17";
	/** Version line the patched CLI prints first. */
	private static final String VERSION_LINE = "codex-cli 0.167.0+17\n";
	/** Second line the patched CLI prints after its version line. */
	private static final String SEE_BUILD_INFO_LINE = "(See --build-info for more information)\n";
	/** Exact valid version response of the patched CLI, including its final line feed. */
	private static final String BANNER = VERSION_LINE + SEE_BUILD_INFO_LINE;
	/** Valid required package build-info fields from the Python policy oracle. */
	private static final String INFO = "Codex CLI version: 0.167.0+17\nBuild number:      17\n" +
		"Upstream version:  0.167.0\nProvided by:       Codex Unleashed\n";

	/** Constructs independent source and package fixtures for each identity check. */
	public PackageIdentityTest()
	{
	}

	/**
	 * Rejects a source version mismatch without changing the original Cargo manifest.
	 *
	 * @throws IOException if fixture files cannot be read or written
	 */
	@Test
	public void rejectsSourceVersionMismatchWithoutWrite() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path manifest = fixture.writeManifest("0.0.0");
			String original = Files.readString(manifest, StandardCharsets.UTF_8);
			int status = PackageIdentity.run(fixture.scope, sourceArguments(fixture, BUILD),
				fixture.out, fixture.err);

			requireThat(status, "status").isEqualTo(1);
			requireThat(Files.readString(manifest, StandardCharsets.UTF_8), "originalManifest").isEqualTo(original);
			requireThat(fixture.errors.toString(StandardCharsets.UTF_8), "diagnostics").contains("workspace.package.version");
		}
	}

	/**
	 * Accepts the original workspace package version and leaves its manifest bytes unchanged.
	 *
	 * @throws IOException if fixture files cannot be read or written
	 */
	@Test
	public void acceptsSourceVersionWithoutWrite() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path manifest = fixture.writeManifest(VERSION);
			String original = Files.readString(manifest, StandardCharsets.UTF_8);
			int status = PackageIdentity.run(fixture.scope, sourceArguments(fixture, BUILD),
				fixture.out, fixture.err);

			requireThat(status, "status").isEqualTo(0);
			requireThat(Files.readString(manifest, StandardCharsets.UTF_8), "originalManifest").isEqualTo(original);
			requireThat(fixture.errors.toString(StandardCharsets.UTF_8), "diagnostics").isEmpty();
		}
	}

	/**
	 * Rejects non-ASCII build digits before reading or changing the original Cargo manifest.
	 *
	 * @throws IOException if fixture files cannot be read or written
	 */
	@Test
	public void rejectsNonAsciiBuildWithoutManifestWrite() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path manifest = fixture.writeManifest(VERSION);
			String original = Files.readString(manifest, StandardCharsets.UTF_8);
			int status = PackageIdentity.run(fixture.scope, sourceArguments(fixture, "١٧"),
				fixture.out, fixture.err);

			requireThat(status, "status").isEqualTo(1);
			requireThat(Files.readString(manifest, StandardCharsets.UTF_8), "originalManifest").isEqualTo(original);
			requireThat(fixture.errors.toString(StandardCharsets.UTF_8), "diagnostics").contains("ASCII decimal digits");
		}
	}

	/**
	 * Writes the exact captured version banner only after both package identity responses pass.
	 *
	 * @throws IOException if fixture files cannot be read or written
	 */
	@Test
	public void writesProofAfterBothPackageChecks() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path executable = fixture.createPackage();
			Path proof = fixture.root.resolve("proof.txt");
			List<List<String>> commands = new ArrayList<>();
			SystemCommands.Result versionResult = new SystemCommands.Result(0, BANNER, "");
			SystemCommands.Result infoResult = new SystemCommands.Result(0, INFO, "");
			SystemCommands.Capture capture = (command, workingDirectory, temporaryDirectory, environment) ->
			{
				commands.add(List.copyOf(command));
				if (command.getLast().equals("--version"))
					return versionResult;
				return infoResult;
			};
			int status = PackageIdentity.run(fixture.scope, binaryArguments(fixture, proof), fixture.out,
				fixture.err, capture);

			requireThat(status, "status").isEqualTo(0);
			requireThat(commands, "packageIdentityCommands").isEqualTo(List.of(
				List.of(executable.toString(), "--version"), List.of(executable.toString(), "--build-info")));
			requireThat(proof, "proof").isRegularFile();
			requireThat(Files.readString(proof, StandardCharsets.UTF_8), "proofText").isEqualTo(VERSION_LINE);
			requireThat(fixture.errors.toString(StandardCharsets.UTF_8), "diagnostics").isEmpty();
		}
	}

	/**
	 * Accepts each final newline form of the two-line banner and records only the version line in the proof file.
	 *
	 * @throws IOException if fixture files cannot be read or written
	 */
	@Test
	public void preservesAcceptedBannerNewlineForms() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
				fixture.createPackage();
			Path proof = fixture.root.resolve("proof.txt");
			for (String banner : List.of(BANNER.stripTrailing(), BANNER, BANNER.replace("\n", "\r\n")))
			{
				SystemCommands.Capture capture = (command, workingDirectory, temporaryDirectory, environment) ->
				{
					String stdout = INFO;
					if (command.getLast().equals("--version"))
						stdout = banner;
					return new SystemCommands.Result(0, stdout, "");
				};
				int status = PackageIdentity.run(fixture.scope, binaryArguments(fixture, proof), fixture.out,
					fixture.err, capture);

				requireThat(status, "status").isEqualTo(0);
				String expectedProof = VERSION_LINE;
				if (banner.contains("\r\n"))
					expectedProof = VERSION_LINE.replace("\n", "\r\n");
				requireThat(Files.readString(proof, StandardCharsets.UTF_8), "proofText").isEqualTo(expectedProof);
			}
		}
	}

	/**
	 * Retains an existing proof when either the package banner or a required build-info field is wrong.
	 *
	 * @throws IOException if fixture files cannot be read or written
	 */
	@Test
	public void preservesProofOnInvalidPackageResponses() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.createPackage();
			Path proof = fixture.root.resolve("proof.txt");
			String original = "previous proof";
			Files.writeString(proof, original, StandardCharsets.UTF_8);
			for (SystemCommands.Result[] results : List.<SystemCommands.Result[]>of(
				new SystemCommands.Result[]{new SystemCommands.Result(0, "codex-cli 0.167.0+18\n" + SEE_BUILD_INFO_LINE, ""),
					new SystemCommands.Result(0, INFO, "")},
				new SystemCommands.Result[]{new SystemCommands.Result(0, VERSION_LINE, ""),
					new SystemCommands.Result(0, INFO, "")},
				new SystemCommands.Result[]{new SystemCommands.Result(0, BANNER + "\n", ""),
					new SystemCommands.Result(0, INFO, "")},
				new SystemCommands.Result[]{new SystemCommands.Result(0, BANNER + "extra\n", ""),
					new SystemCommands.Result(0, INFO, "")},
				new SystemCommands.Result[]{new SystemCommands.Result(0, VERSION_LINE + "(See --help for more information)\n", ""),
					new SystemCommands.Result(0, INFO, "")},
				new SystemCommands.Result[]{new SystemCommands.Result(0, SEE_BUILD_INFO_LINE + VERSION_LINE, ""),
					new SystemCommands.Result(0, INFO, "")},
				new SystemCommands.Result[]{new SystemCommands.Result(0, BANNER.stripTrailing() + "\r", ""),
					new SystemCommands.Result(0, INFO, "")},
				new SystemCommands.Result[]{new SystemCommands.Result(0, BANNER, ""),
					new SystemCommands.Result(0, INFO.replace("Codex CLI version: 0.167.0+17",
						"Codex CLI version: 0.167.0+18"), "")},
				new SystemCommands.Result[]{new SystemCommands.Result(0, BANNER, ""),
					new SystemCommands.Result(0, INFO.replace("Build number:      17", "Build number:      18"), "")},
				new SystemCommands.Result[]{new SystemCommands.Result(0, BANNER, ""),
					new SystemCommands.Result(0, INFO.replace("Upstream version:  0.167.0",
						"Upstream version:  0.0.0"), "")},
				new SystemCommands.Result[]{new SystemCommands.Result(0, BANNER, ""),
					new SystemCommands.Result(0, INFO.replace("Provided by:       Codex Unleashed",
						"Provided by:       OpenAI"), "")},
				new SystemCommands.Result[]{new SystemCommands.Result(0, BANNER, ""),
					new SystemCommands.Result(0, INFO.replace("Build number:      17\n", ""), "")},
				new SystemCommands.Result[]{new SystemCommands.Result(0, BANNER, ""),
					new SystemCommands.Result(0, INFO + "Build number: 17\n", "")},
				new SystemCommands.Result[]{new SystemCommands.Result(1, BANNER, "failed"),
					new SystemCommands.Result(0, INFO, "")},
				new SystemCommands.Result[]{new SystemCommands.Result(0, BANNER, ""),
					new SystemCommands.Result(1, INFO, "failed")}))
			{
				AtomicInteger call = new AtomicInteger();
				SystemCommands.Capture capture = (command, workingDirectory, temporaryDirectory, environment) ->
					results[call.getAndIncrement()];
				int status = PackageIdentity.run(fixture.scope, binaryArguments(fixture, proof), fixture.out,
					fixture.err, capture);

				requireThat(status, "status").isEqualTo(1);
				requireThat(Files.readString(proof, StandardCharsets.UTF_8), "previousProof").isEqualTo(original);
			}
		}
	}

	/**
	 * Rejects noncanonical layout metadata before requesting either package identity command.
	 *
	 * @throws IOException if fixture files cannot be read or written
	 */
	@Test
	public void rejectsInvalidLayoutBeforeCapture() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.createPackage();
			Path proof = fixture.root.resolve("proof.txt");
			for (String metadata : List.of(
				"{\"layoutVersion\":2,\"entrypoint\":\"bin/codex\"}\n",
				"{\"layoutVersion\":1.0,\"entrypoint\":\"bin/codex\"}\n",
				"{\"layoutVersion\":true,\"entrypoint\":\"bin/codex\"}\n",
				"{\"layoutVersion\":1,\"entrypoint\":\"../codex\"}\n",
				"{\"layoutVersion\":1,\"entrypoint\":\"bin/codex-app-server\"}\n"))
			{
				Files.writeString(fixture.root.resolve("codex-package.json"), metadata, StandardCharsets.UTF_8);
				List<List<String>> commands = new ArrayList<>();
				SystemCommands.Capture capture = (command, workingDirectory, temporaryDirectory, environment) ->
				{
					commands.add(List.copyOf(command));
					return new SystemCommands.Result(0, "", "");
				};
				int status = PackageIdentity.run(fixture.scope, binaryArguments(fixture, proof), fixture.out,
					fixture.err, capture);

				requireThat(status, "status").isEqualTo(1);
				requireThat(commands, "identityCommands").isEmpty();
				requireThat(Files.exists(proof), "proofExists").isFalse();
			}
		}
	}

	/**
	 * Returns the source command arguments for one fixture and build value.
	 *
	 * @param fixture temporary files and command dependencies
	 * @param build exact build argument
	 * @return source operation arguments
	 */
	private static String[] sourceArguments(Fixture fixture, String build)
	{
		return new String[]{"source", "--source-root", fixture.root.toString(), "--version", VERSION,
			"--build", build};
	}

	/**
	 * Returns the binary command arguments for one fixture and proof path.
	 *
	 * @param fixture temporary files and command dependencies
	 * @param proof identity proof destination
	 * @return binary operation arguments
	 */
	private static String[] binaryArguments(Fixture fixture, Path proof)
	{
		return new String[]{"binary", "--package-dir", fixture.root.toString(), "--version", VERSION,
			"--build", BUILD, "--output", proof.toString()};
	}

	/** Owns source files, package metadata, process-response fixtures, streams, and the invocation scope. */
	private static final class Fixture implements AutoCloseable
	{
		/** Fixture root removed by this fixture's close operation. */
		private final Path root;
		/** Scope borrowed by the command for this fixture. */
		private final ToolingScope scope;
		/** Captured command output. */
		private final ByteArrayOutputStream output = new ByteArrayOutputStream();
		/** Captured command diagnostics. */
		private final ByteArrayOutputStream errors = new ByteArrayOutputStream();
		/** Command output stream borrowed by the operation. */
		private final PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
		/** Command diagnostic stream borrowed by the operation. */
		private final PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8);
		/**
		 * Allocates isolated files for one source or package identity check.
		 *
		 * @throws IOException if the temporary fixture cannot be created
		 */
		private Fixture() throws IOException
		{
			root = Files.createTempDirectory("package-identity-");
			scope = new DefaultToolingScope();
		}

		/**
		 * Writes a Cargo manifest with the selected workspace package version.
		 *
		 * @param manifestVersion workspace package version to write
		 * @return the created Cargo manifest
		 * @throws IOException if the manifest fixture cannot be written
		 */
		private Path writeManifest(String manifestVersion) throws IOException
		{
			Path directory = Files.createDirectory(root.resolve("codex-rs"));
			Path manifest = directory.resolve("Cargo.toml");
			Files.writeString(manifest, "[workspace.package]\nversion = \"" + manifestVersion + "\"\n",
				StandardCharsets.UTF_8);
			return manifest;
		}

		/**
		 * Writes layout-version-1 metadata and the existing package entrypoint file.
		 *
		 * @return the created package entrypoint
		 * @throws IOException if package fixture files cannot be written
		 */
		private Path createPackage() throws IOException
		{
			Files.writeString(root.resolve("codex-package.json"),
				"{\"layoutVersion\":1,\"entrypoint\":\"bin/codex\"}\n", StandardCharsets.UTF_8);
			Path executable = root.resolve("bin/codex");
			Files.createDirectory(executable.getParent());
			Files.writeString(executable, "captured in-memory process fixture", StandardCharsets.UTF_8);
			return executable;
		}

		/**
		 * Closes borrowed streams and scope, then removes only this fixture's temporary tree.
		 *
		 * @throws IOException if fixture cleanup fails
		 */
		@Override
		public void close() throws IOException
		{
			out.close();
			err.close();
			scope.close();
			try (Stream<Path> paths = Files.walk(root))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.deleteIfExists(path);
			}
		}
	}
}
