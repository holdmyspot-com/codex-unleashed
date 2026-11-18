package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.PruneWorkspaceOutputs;
import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks that {@link PruneWorkspaceOutputs} removes workspace outputs only from its owned hard-linked target copy.
 */
public final class PruneWorkspaceOutputsTest
{
	/**
	 * Creates the TestNG test container.
	 */
	public PruneWorkspaceOutputsTest()
	{
	}

	/**
	 * Removes matching workspace outputs from the owned copy while preserving linked live files and unrelated outputs.
	 *
	 * @throws IOException if creating or removing the test's owned files fails
	 */
	@Test
	public void pruneHardLinkedCopy() throws IOException
	{
		Path root = Files.createTempDirectory("prune-workspace-outputs-");
		try
		{
			Path workspace = Files.createDirectory(root.resolve("workspace"));
			Path liveTarget = Files.createDirectory(root.resolve("live-target"));
			Path targetCopy = Files.createDirectory(root.resolve("target-copy"));
			Path liveProfile = liveTarget.resolve("x86_64-unknown-linux-gnu/ci-test");
			Path copiedProfile = targetCopy.resolve("x86_64-unknown-linux-gnu/ci-test");
			Path liveWorkspaceLibrary = linkedFile(liveProfile.resolve("deps/libfoo_bar-0123456789abcdef.rlib"),
				copiedProfile.resolve("deps/libfoo_bar-0123456789abcdef.rlib"), "workspace library");
			Path copiedWorkspaceLibrary = copiedProfile.resolve("deps/libfoo_bar-0123456789abcdef.rlib");
			Path thirdPartyLibrary = linkedFile(liveProfile.resolve("deps/libserde-0123456789abcdef.rlib"),
				copiedProfile.resolve("deps/libserde-0123456789abcdef.rlib"), "third-party library");
			Path liveWorkspaceTarget = linkedFile(
				liveProfile.resolve("deps/widget_bin-1111111111111111.rmeta"),
				copiedProfile.resolve("deps/widget_bin-1111111111111111.rmeta"), "workspace target");
			Path copiedWorkspaceTarget = copiedProfile.resolve("deps/widget_bin-1111111111111111.rmeta");
			Path malformedHashLibrary = linkedFile(liveProfile.resolve("deps/libfoo_bar-0123456789abcdeg.rlib"),
				copiedProfile.resolve("deps/libfoo_bar-0123456789abcdeg.rlib"), "malformed hash");
			Path liveFingerprint = liveProfile.resolve(".fingerprint/foo_bar-0123456789abcdef");
			Path copiedFingerprint = copiedProfile.resolve(".fingerprint/foo_bar-0123456789abcdef");
			Path fingerprintSentinel = linkedFile(liveFingerprint.resolve("lib-foo_bar"),
				copiedFingerprint.resolve("lib-foo_bar"), "workspace fingerprint");
			Path liveBuild = liveProfile.resolve("build/foo_bar-0123456789abcdef");
			Path copiedBuild = copiedProfile.resolve("build/foo_bar-0123456789abcdef");
			Path buildSentinel = linkedFile(liveBuild.resolve("output"), copiedBuild.resolve("output"),
				"workspace build output");
			Path liveExecutable = linkedFile(liveProfile.resolve("foo_bar"), copiedProfile.resolve("foo_bar"),
				"workspace executable");
			Path examples = copiedProfile.resolve("examples");
			Files.createDirectories(examples);
			Files.writeString(examples.resolve("foo_bar"), "workspace example");
			Path incremental = copiedProfile.resolve("incremental");
			Files.createDirectories(incremental);
			Files.writeString(incremental.resolve("session"), "workspace incremental output");
			Path hiddenProfileFile = copiedProfile.resolve(".retained");
			Files.writeString(hiddenProfileFile, "hidden profile file");
			ByteArrayOutputStream stdoutBytes = new ByteArrayOutputStream();
			ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream();
			PrintStream out = new PrintStream(stdoutBytes, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(stderrBytes, true, StandardCharsets.UTF_8);
			String metadata = """
				{"packages":[{"name":"foo-bar","targets":[{"name":"foo-bar"},
				{"name":"widget-bin"}]}]}
				""";
			SystemCommands.Capture metadataCommands = (command, workingDirectory, temporaryDirectory, environment) ->
			{
				requireThat(command, "command").isEqualTo(List.of("cargo", "metadata", "--no-deps", "--format-version", "1"));
				requireThat(workingDirectory, "workingDirectory").isEqualTo(workspace);
				requireThat(environment, "environment").isEqualTo(Map.of());
				return new SystemCommands.Result(0, metadata, "");
			};
			String[] arguments = {"--workspace-manifest-dir", workspace.toString(), "--target-copy", targetCopy.toString(),
				"--live-target", liveTarget.toString()};

			try (ToolingScope scope = new DefaultToolingScope())
			{
				int status = PruneWorkspaceOutputs.run(scope, arguments, out, err, metadataCommands);
				requireThat(status, "status").isEqualTo(0);
			}

			requireThat(Files.exists(copiedWorkspaceLibrary), "workspaceLibraryExistsInCopy").isFalse();
			requireThat(Files.exists(copiedWorkspaceTarget), "workspaceTargetExistsInCopy").isFalse();
			requireThat(Files.exists(copiedFingerprint), "workspaceFingerprintExistsInCopy").isFalse();
			requireThat(Files.exists(copiedBuild), "workspaceBuildOutputExistsInCopy").isFalse();
			requireThat(Files.exists(copiedProfile.resolve("examples")), "examplesExistsInCopy").isFalse();
			requireThat(Files.exists(copiedProfile.resolve("incremental")), "incrementalExistsInCopy").isFalse();
			requireThat(Files.exists(copiedProfile.resolve("foo_bar")), "workspaceExecutableExistsInCopy").isFalse();
			requireThat(Files.readString(thirdPartyLibrary), "thirdPartyLibrary").isEqualTo("third-party library");
			requireThat(Files.readString(malformedHashLibrary), "malformedHashLibrary").isEqualTo("malformed hash");
			requireThat(Files.readString(hiddenProfileFile), "hiddenProfileFile").isEqualTo("hidden profile file");
			requireThat(Files.readString(liveWorkspaceLibrary), "liveWorkspaceLibrary").
				isEqualTo("workspace library");
			requireThat(Files.readString(liveWorkspaceTarget), "liveWorkspaceTarget").isEqualTo("workspace target");
			requireThat(Files.readString(fingerprintSentinel), "fingerprintSentinel").isEqualTo("workspace fingerprint");
			requireThat(Files.readString(buildSentinel), "buildSentinel").
				isEqualTo("workspace build output");
			requireThat(Files.readString(liveExecutable), "liveExecutable").isEqualTo("workspace executable");
			requireThat(stderrBytes.toString(StandardCharsets.UTF_8), "stderr").isEmpty();
			requireThat(stdoutBytes.toString(StandardCharsets.UTF_8), "stdout").
				isEqualTo(copiedProfile + ": removed 7 workspace entries" + System.lineSeparator() +
					"Pruned 7 entries for 2 workspace names." + System.lineSeparator());
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Leaves every target-copy entry intact when Cargo exits unsuccessfully or returns malformed metadata.
	 *
	 * @throws IOException if creating or removing the test's owned files fails
	 */
	@Test
	public void metadataFailureNoMutation() throws IOException
	{
		Path root = Files.createTempDirectory("prune-workspace-metadata-failure-");
		try
		{
			Path workspace = Files.createDirectory(root.resolve("workspace"));
			Path liveTarget = Files.createDirectory(root.resolve("live-target"));
			Path targetCopy = Files.createDirectory(root.resolve("target-copy"));
			Path liveProfile = liveTarget.resolve("debug");
			Path copiedProfile = targetCopy.resolve("debug");
			Path liveOutput = linkedFile(liveProfile.resolve("deps/libfoo_bar-0123456789abcdef.rlib"),
				copiedProfile.resolve("deps/libfoo_bar-0123456789abcdef.rlib"), "workspace library");
			ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream();
			PrintStream err = new PrintStream(stderrBytes, true, StandardCharsets.UTF_8);
			ByteArrayOutputStream stdoutBytes = new ByteArrayOutputStream();
			PrintStream out = new PrintStream(stdoutBytes, true, StandardCharsets.UTF_8);
			String[] arguments = {"--workspace-manifest-dir", workspace.toString(), "--target-copy", targetCopy.toString(),
				"--live-target", liveTarget.toString()};
			List<SystemCommands.Result> failedMetadata = List.of(
				new SystemCommands.Result(101, "cargo output", "cargo failed"),
				new SystemCommands.Result(0, "{malformed metadata", ""));

			for (SystemCommands.Result metadata : failedMetadata)
			{
				SystemCommands.Capture metadataCommands =
					(command, workingDirectory, temporaryDirectory, environment) -> metadata;
				try (ToolingScope scope = new DefaultToolingScope())
				{
					int status = PruneWorkspaceOutputs.run(scope, arguments, out, err, metadataCommands);
					requireThat(status, "status").isEqualTo(1);
				}

				String expectedStdout = "";
				if (metadata.status() != 0)
					expectedStdout = metadata.stdout();
				requireThat(stdoutBytes.toString(StandardCharsets.UTF_8), "stdout").
					isEqualTo(expectedStdout);
				stdoutBytes.reset();
				requireThat(Files.readString(liveOutput), "liveOutput").isEqualTo("workspace library");
				requireThat(Files.readString(copiedProfile.resolve("deps/libfoo_bar-0123456789abcdef.rlib")),
					"copiedOutput").isEqualTo("workspace library");
			}
			requireThat(stderrBytes.toString(StandardCharsets.UTF_8), "stderr").contains("ERROR:");
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Rejects a copy that aliases the live target before requesting Cargo metadata or deleting an entry.
	 *
	 * @throws IOException if creating or removing the test's owned files fails
	 */
	@Test
	public void rejectsLiveAliasBeforeCargo() throws IOException
	{
		Path root = Files.createTempDirectory("prune-workspace-live-alias-");
		try
		{
			Path workspace = Files.createDirectory(root.resolve("workspace"));
			Path liveTarget = Files.createDirectory(root.resolve("live-target"));
			Path sentinel = Files.writeString(liveTarget.resolve("sentinel"), "live target");
			List<List<String>> commands = new ArrayList<>();
			SystemCommands.Capture metadataCommands = (command, workingDirectory, temporaryDirectory, environment) ->
			{
				commands.add(command);
				return new SystemCommands.Result(0, "{\"packages\":[]}", "");
			};
			String[] arguments = {"--workspace-manifest-dir", workspace.toString(), "--target-copy", liveTarget.toString(),
				"--live-target", liveTarget.toString()};
			ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream();

			try (ToolingScope scope = new DefaultToolingScope())
			{
				int status = PruneWorkspaceOutputs.run(scope, arguments,
					new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
					new PrintStream(stderrBytes, true, StandardCharsets.UTF_8), metadataCommands);
				requireThat(status, "status").isEqualTo(1);
			}

			requireThat(commands, "metadataCommands").isEmpty();
			requireThat(Files.readString(sentinel), "liveSentinel").isEqualTo("live target");
			requireThat(stderrBytes.toString(StandardCharsets.UTF_8), "stderr").contains("ERROR:");
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Rejects copies that overlap the live target or contain the source, including a path through a linked ancestor.
	 *
	 * @throws IOException if creating or removing the test's owned files fails
	 */
	@Test
	public void rejectsOverlappingAndLinkedCopyPaths() throws IOException
	{
		Path root = Files.createTempDirectory("prune-workspace-copy-ownership-");
		try
		{
			Path workspace = Files.createDirectory(root.resolve("workspace"));
			Path liveTarget = Files.createDirectory(root.resolve("live-target"));
			Path liveNestedCopy = Files.createDirectory(liveTarget.resolve("nested-copy"));
			Path container = Files.createDirectory(root.resolve("container"));
			Path nestedLiveTarget = Files.createDirectory(container.resolve("live-target"));
			Path sourceContainer = Files.createDirectory(root.resolve("source-container"));
			Path nestedWorkspace = Files.createDirectory(sourceContainer.resolve("workspace"));
			Path linkedParent = Files.createDirectory(root.resolve("linked-parent"));
			Path linkedCopy = Files.createDirectory(linkedParent.resolve("copy"));
			Path linkedAncestor = root.resolve("linked-ancestor");
			Files.createSymbolicLink(linkedAncestor, linkedParent);
			List<List<String>> commands = new ArrayList<>();
			SystemCommands.Capture metadataCommands = (command, workingDirectory, temporaryDirectory, environment) ->
			{
				commands.add(command);
				return new SystemCommands.Result(0, "{\"packages\":[]}", "");
			};
			List<String[]> unsafeArguments = List.of(
				arguments(workspace, liveTarget, liveTarget),
				arguments(workspace, liveTarget.resolve("."), liveTarget),
				arguments(workspace, liveNestedCopy, liveTarget),
				arguments(workspace, container, nestedLiveTarget),
				arguments(nestedWorkspace, sourceContainer, liveTarget),
				arguments(workspace, linkedAncestor.resolve("copy"), liveTarget));

			for (String[] arguments : unsafeArguments)
			{
				try (ToolingScope scope = new DefaultToolingScope())
				{
					int status = PruneWorkspaceOutputs.run(scope, arguments,
						new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
						new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), metadataCommands);
					requireThat(status, "status").isEqualTo(1);
				}
			}

			requireThat(commands, "metadataCommands").isEmpty();
			requireThat(Files.isDirectory(linkedCopy), "linkedCopyExists").isTrue();
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Rejects an option set with no live target before invoking Cargo or changing the target copy.
	 *
	 * @throws IOException if creating or removing the test's owned files fails
	 */
	@Test
	public void invalidArgumentsSkipCargo() throws IOException
	{
		Path root = Files.createTempDirectory("prune-workspace-invalid-arguments-");
		try
		{
			Path workspace = Files.createDirectory(root.resolve("workspace"));
			Path targetCopy = Files.createDirectory(root.resolve("target-copy"));
			Path sentinel = Files.writeString(targetCopy.resolve("sentinel"), "copy contents");
			List<List<String>> commands = new ArrayList<>();
			SystemCommands.Capture metadataCommands = (command, workingDirectory, temporaryDirectory, environment) ->
			{
				commands.add(command);
				return new SystemCommands.Result(0, "{\"packages\":[]}", "");
			};
			String[] arguments = {"--workspace-manifest-dir", workspace.toString(), "--target-copy", targetCopy.toString()};
			ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream();

			try (ToolingScope scope = new DefaultToolingScope())
			{
				int status = PruneWorkspaceOutputs.run(scope, arguments,
					new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
					new PrintStream(stderrBytes, true, StandardCharsets.UTF_8), metadataCommands);
				requireThat(status, "status").isEqualTo(2);
			}

			requireThat(commands, "metadataCommands").isEmpty();
			requireThat(Files.readString(sentinel), "copySentinel").isEqualTo("copy contents");
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Unlinks selected symlink entries without following a linked profile into files outside the target copy.
	 *
	 * @throws IOException if creating or removing the test's owned files fails
	 */
	@Test
	public void pruneDoesNotFollowLinks() throws IOException
	{
		Path root = Files.createTempDirectory("prune-workspace-symlinks-");
		try
		{
			Path workspace = Files.createDirectory(root.resolve("workspace"));
			Path liveTarget = Files.createDirectory(root.resolve("live-target"));
			Path targetCopy = Files.createDirectory(root.resolve("target-copy"));
			Path copiedProfile = targetCopy.resolve("debug");
			Path liveSentinel = Files.writeString(root.resolve("live-sentinel"), "symlink target");
			Path selectedLink = copiedProfile.resolve("deps/libfoo_bar-0123456789abcdef.rlib");
			Files.createDirectories(selectedLink.getParent());
			Files.createSymbolicLink(selectedLink, liveSentinel);
			Files.createDirectories(copiedProfile.resolve(".fingerprint"));
			Path outsideProfile = Files.createDirectories(root.resolve("outside/debug"));
			Files.createDirectories(outsideProfile.resolve("deps"));
			Files.createDirectories(outsideProfile.resolve(".fingerprint"));
			Path outsideWorkspaceOutput = Files.writeString(outsideProfile.resolve("deps/foo_bar-0123456789abcdef.rlib"),
				"outside profile");
			Path linkedProfile = targetCopy.resolve("linked-profile");
			Files.createSymbolicLink(linkedProfile, outsideProfile);
			SystemCommands.Capture metadataCommands = (command, workingDirectory, temporaryDirectory, environment) ->
				new SystemCommands.Result(0, "{\"packages\":[{\"name\":\"foo-bar\",\"targets\":[]}]}", "");
			String[] arguments = {"--workspace-manifest-dir", workspace.toString(), "--target-copy", targetCopy.toString(),
				"--live-target", liveTarget.toString()};

			try (ToolingScope scope = new DefaultToolingScope())
			{
				int status = PruneWorkspaceOutputs.run(scope, arguments,
					new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
					new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), metadataCommands);
				requireThat(status, "status").isEqualTo(0);
			}

			requireThat(Files.isSymbolicLink(selectedLink), "selectedLinkIsSymlink").isFalse();
			requireThat(Files.readString(liveSentinel), "symlinkTarget").isEqualTo("symlink target");
			requireThat(Files.isSymbolicLink(linkedProfile), "linkedProfileExists").isTrue();
			requireThat(Files.readString(outsideWorkspaceOutput), "outsideWorkspaceOutput").
				isEqualTo("outside profile");
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Creates a live output file and its hard-linked entry in the owned copy.
	 *
	 * @param liveFile file in the live target tree
	 * @param copiedFile corresponding entry in the owned copy
	 * @param contents file contents
	 * @return the live file whose link is placed in the copy
	 * @throws IOException if the file or hard link cannot be created
	 */
	private static Path linkedFile(Path liveFile, Path copiedFile, String contents) throws IOException
	{
		Files.createDirectories(liveFile.getParent());
		Files.createDirectories(copiedFile.getParent());
		Files.writeString(liveFile, contents);
		Files.createLink(copiedFile, liveFile);
		return liveFile;
	}

	/**
	 * Builds arguments for an ownership validation case.
	 *
	 * @param workspace workspace source directory
	 * @param targetCopy target copy candidate
	 * @param liveTarget live target directory
	 * @return complete command arguments
	 */
	private static String[] arguments(Path workspace, Path targetCopy, Path liveTarget)
	{
		return new String[] {"--workspace-manifest-dir", workspace.toString(), "--target-copy", targetCopy.toString(),
			"--live-target", liveTarget.toString()};
	}

	/**
	 * Removes only the temporary tree created by this test.
	 *
	 * @param root test-owned temporary directory
	 * @throws IOException if an entry cannot be removed
	 */
	private static void deleteTree(Path root) throws IOException
	{
		Files.walkFileTree(root, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException
			{
				if (failure != null)
					throw failure;
				Files.delete(directory);
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
