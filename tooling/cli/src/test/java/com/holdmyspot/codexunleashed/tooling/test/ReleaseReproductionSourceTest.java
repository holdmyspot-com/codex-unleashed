package com.holdmyspot.codexunleashed.tooling.test;

import java.nio.charset.StandardCharsets;
import tools.jackson.databind.node.ArrayNode;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.ReleaseManifest;
import com.holdmyspot.codexunleashed.tooling.ReleaseManifestSchema;
import com.holdmyspot.codexunleashed.tooling.ReleaseReproductionSource;
import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.file.PathUtils;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Checks the independent authentication boundary before source reconstruction. */
public final class ReleaseReproductionSourceTest
{
	/** Creates authentication checks. */
	public ReleaseReproductionSourceTest()
	{
	}

	/**
	 * Uses fixed signer policy and preserves an arbitrary precision build number.
	 *
	 * @throws IOException if fixture creation or authentication fails
	 */
	@Test
	public void fixedPolicyBeforeAnyGitFetch() throws IOException
	{
		Path root = Files.createTempDirectory("reproduction-source-");
		try (DefaultToolingScope scope = new DefaultToolingScope();
			PrintStream diagnostics = new PrintStream(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8))
		{
			Path manifest = manifest(scope, root);
			List<List<String>> calls = new ArrayList<>();
			ReleaseReproductionSource source = new ReleaseReproductionSource(scope,
				(command, directory, temporary, environment) ->
				{
					calls.add(List.copyOf(command));
					return new SystemCommands.Result(0, "", "");
				}, new ReleaseManifest.Environment(root, root, "different observed host", Map.of()), diagnostics);
			ReleaseReproductionSource.Release release = source.authenticate(manifest, "x86_64-unknown-linux-musl");
			requireThat(release.buildNumber(), "buildNumber").
				isEqualTo(new BigInteger("999999999999999999999999999999"));
			requireThat(calls.size(), "callCount").isEqualTo(1);
			requireThat(calls.getFirst(), "fixedAttestation").isEqualTo(List.of("gh", "attestation", "verify",
				manifest.toString(), "--repo", ReleaseManifestSchema.REPOSITORY, "--signer-workflow",
				ReleaseManifestSchema.WORKFLOW));
		}
		finally
		{
			PathUtils.deleteDirectory(root);
		}
	}

	/**
	 * Rejects failed authentication without requesting any Git source operation.
	 *
	 * @throws IOException if fixture creation fails
	 */
	@Test
	public void rejectedAttestationStopsSource() throws IOException
	{
		Path root = Files.createTempDirectory("reproduction-rejected-source-");
		try (DefaultToolingScope scope = new DefaultToolingScope();
			PrintStream diagnostics = new PrintStream(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8))
		{
			Path manifest = manifest(scope, root);
			List<List<String>> calls = new ArrayList<>();
			ReleaseReproductionSource source = new ReleaseReproductionSource(scope,
				(command, directory, temporary, environment) ->
				{
					calls.add(List.copyOf(command));
					return new SystemCommands.Result(1, "", "attestation rejected");
				}, new ReleaseManifest.Environment(root, root, "host", Map.of()), diagnostics);
			boolean rejected = false;
			try
			{
				source.authenticate(manifest, "x86_64-unknown-linux-musl");
			}
			catch (IOException _)
			{
				rejected = true;
			}
			requireThat(rejected, "authenticationRejected").isTrue();
			requireThat(calls.size(), "onlyAuthenticationRequested").isEqualTo(1);
			requireThat(calls.getFirst().getFirst(), "authenticationExecutable").isEqualTo("gh");
		}
		finally
		{
			PathUtils.deleteDirectory(root);
		}
	}

	/**
	 * Classifies malformed schema as caller input before authentication or source operations.
	 *
	 * @throws IOException if the owned fixture cannot be written
	 */
	@Test
	public void malformedSchemaIsCallerInput() throws IOException
	{
		Path root = Files.createTempDirectory("reproduction-malformed-");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			Path manifest = root.resolve("release-manifest.json");
			Files.writeString(manifest, "{}");
			List<List<String>> calls = new ArrayList<>();
			ReleaseReproductionSource source = new ReleaseReproductionSource(scope,
				(command, directory, temporary, environment) ->
				{
					calls.add(command);
					return new SystemCommands.Result(0, "", "");
				}, new ReleaseManifest.Environment(root, root, "host", Map.of()), System.err);
			boolean malformed = false;
			try
			{
				source.authenticate(manifest, "x86_64-unknown-linux-musl");
			}
			catch (IllegalArgumentException _)
			{
				malformed = true;
			}
			requireThat(malformed, "malformedSchema").isTrue();
			requireThat(calls, "noExternalCommands").isEmpty();
		}
		finally
		{
			PathUtils.deleteDirectory(root);
		}
	}

	/**
	 * Rejects mutation of authenticated bytes even when the attestation subprocess reports success.
	 *
	 * @throws IOException if fixture operations fail
	 */
	@Test
	public void authenticationPinsExactBytes() throws IOException
	{
		Path root = Files.createTempDirectory("reproduction-manifest-race-");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			Path manifest = manifest(scope, root);
			ReleaseReproductionSource source = new ReleaseReproductionSource(scope,
				(command, directory, temporary, environment) ->
				{
					Files.writeString(manifest, Files.readString(manifest) + " ");
					return new SystemCommands.Result(0, "", "");
				}, new ReleaseManifest.Environment(root, root, "host", Map.of()), System.err);
			String detail = "";
			try
			{
				source.authenticate(manifest, "x86_64-unknown-linux-musl");
			}
			catch (IOException failure)
			{
				detail = failure.getMessage();
			}
			requireThat(detail, "changedBytesRejected").contains("changed during authentication");
		}
		finally
		{
			PathUtils.deleteDirectory(root);
		}
	}

	/**
	 * Stops the shared public composition before output binding or native work on rejected authentication.
	 *
	 * @throws IOException if fixture operations fail
	 */
	@Test
	public void publicAuthFailureStopsRecipe() throws IOException
	{
		Path root = Files.createTempDirectory("reproduction-public-auth-");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			Path manifest = manifest(scope, root);
			Path output = root.resolve("untouched-output");
			int status = com.holdmyspot.codexunleashed.tooling.ReleaseReproduction.run(scope,
				new String[]{manifest.toString(), "--project-root", root.toString(), "--target",
					"x86_64-unknown-linux-musl", "--output-dir", output.toString()}, Map.of(), System.out,
				System.err, (command, directory, temporary, environment) ->
				{
					requireThat(command.getFirst(), "onlyAuthenticationBeforeFailure").isEqualTo("gh");
					return new SystemCommands.Result(1, "", "rejected authentication");
				}, (command, directory, environment, log) ->
				{
					throw new AssertionError("Rejected release reached native execution");
				});
			requireThat(status, "authenticationFailureStatus").isEqualTo(1);
			requireThat(Files.exists(output), "outputUnboundBeforeAuthentication").isFalse();
		}
		finally
		{
			PathUtils.deleteDirectory(root);
		}
	}

	/**
	 * Constructs a complete schema-valid manifest without native artifacts or hosted calls.
	 *
	 * @param scope borrowed JSON services
	 * @param root owned tree root
	 * @return operation result
	 * @throws IOException if the required operation cannot complete
	 */
	private static Path manifest(DefaultToolingScope scope, Path root) throws IOException
	{
		ObjectNode value = scope.getJsonMapper().createObjectNode();
		value.put("format_version", 1);
		value.put("repository", ReleaseManifestSchema.REPOSITORY);
		value.put("signer_workflow", ReleaseManifestSchema.WORKFLOW);
		ObjectNode release = value.putObject("release");
		release.put("tag", "rust-v1.2.3+999999999999999999999999999999");
		release.put("version", "1.2.3+999999999999999999999999999999");
		release.put("build_number", new BigInteger("999999999999999999999999999999"));
		ObjectNode upstream = value.putObject("upstream");
		upstream.put("repository", "openai/codex");
		upstream.put("tag", "rust-v1.2.3");
		upstream.put("commit", "b".repeat(40));
		value.put("fork_commit", "c".repeat(40));
		ObjectNode source = value.putObject("source");
		source.put("commit", "d".repeat(40));
		source.put("tree", "e".repeat(40));
		source.put("cargo_lock_sha256", "f".repeat(64));
		ObjectNode patch = value.putArray("patches").addObject();
		patch.put("path", "patches/1.patch");
		patch.put("sha256", "a".repeat(64));
		ArrayNode receipts = value.putArray("receipts");
		ArrayNode artifacts = value.putArray("artifacts");
		for (String target : ReleaseManifestSchema.TARGETS)
		{
			receipts.add(ReleaseManifestSchema.receiptName(target));
			List<String> names = new ArrayList<>(List.of(ReleaseManifestSchema.receiptName(target),
				ReleaseManifestSchema.packageName(target), ReleaseManifestSchema.symbolsName(target),
				ReleaseManifestSchema.proofName(target)));
			if (target.equals("x86_64-unknown-linux-musl"))
				names.addAll(List.of("install.sh", "install.ps1"));
			for (String name : names)
			{
				ObjectNode artifact = artifacts.addObject();
				artifact.put("name", name);
				String kind = "package_receipt";
				if (!name.equals(ReleaseManifestSchema.receiptName(target)))
					kind = ReleaseManifestSchema.payloadKind(name, target);
				artifact.put("kind", kind);
				artifact.put("target", target);
				artifact.put("size", 0);
				artifact.put("sha256", "a".repeat(64));
			}
		}
		Path manifest = root.resolve("release-manifest.json");
		ReleaseManifestSchema.write(scope, manifest, value);
		return manifest;
	}
}
