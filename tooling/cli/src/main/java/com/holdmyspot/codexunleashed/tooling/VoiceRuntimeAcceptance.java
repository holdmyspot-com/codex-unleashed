package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Validates voice target mappings and physical package paths. */
final class VoiceRuntimeAcceptance
{
	/** Application targets mapped to native runtime targets. */
	private static final Map<String, String> TARGETS = Map.of(
		"x86_64-unknown-linux-musl", "x86_64-unknown-linux-gnu",
		"aarch64-unknown-linux-musl", "aarch64-unknown-linux-gnu",
		"x86_64-apple-darwin", "x86_64-apple-darwin",
		"aarch64-apple-darwin", "aarch64-apple-darwin",
		"x86_64-pc-windows-msvc", "x86_64-pc-windows-msvc",
		"aarch64-pc-windows-msvc", "aarch64-pc-windows-msvc");

	/** Prevents construction of the acceptance utility. */
	private VoiceRuntimeAcceptance()
	{
	}

	/**
	 * Performs voiceTarget for the voice runtime.
	 *
	 * @param appTarget supplied appTarget value
	 * @return the voiceTarget result
	 * @throws IOException if the operation fails
	 */
	static String voiceTarget(String appTarget) throws IOException
	{
		String target = TARGETS.get(appTarget);
		if (target == null)
			throw new IOException("unsupported target " + appTarget);
		return target;
	}

	/**
	 * Performs physicalDirectory for the voice runtime.
	 *
	 * @param input supplied input value
	 * @param label supplied label value
	 * @return the physicalDirectory result
	 * @throws IOException if the operation fails
	 */
	static Path physicalDirectory(Path input, String label) throws IOException
	{
		Path path = input.toRealPath();
		if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(input))
			throw new IOException(label + " must be an existing physical directory: " + input);
		return path;
	}

	/**
	 * Performs physicalRegularFile for the voice runtime.
	 *
	 * @param root supplied root value
	 * @param relative supplied relative value
	 * @param label supplied label value
	 * @return the physicalRegularFile result
	 * @throws IOException if the operation fails
	 */
	static Path physicalRegularFile(Path root, Path relative, String label) throws IOException
	{
		Path candidate = root.resolve(relative).normalize();
		if (!candidate.startsWith(root))
			throw new IOException(label + " escapes its root");
		Path cursor = root;
		for (Path part : root.relativize(candidate))
		{
			cursor = cursor.resolve(part);
			if (Files.isSymbolicLink(cursor))
				throw new IOException(label + " contains a symbolic link: " + cursor);
		}
		if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
			throw new IOException(label + " must be a regular file: " + candidate);
		return candidate;
	}

	/**
	 * Performs packageEntrypoint for the voice runtime.
	 *
	 * @param scope supplied scope value
	 * @param packageRoot supplied packageRoot value
	 * @param target supplied target value
	 * @param version supplied version value
	 * @return the packageEntrypoint result
	 * @throws IOException if the operation fails
	 */
	static Path packageEntrypoint(ToolingScope scope, Path packageRoot, String target, String version) throws IOException
	{
		Path metadataPath = physicalRegularFile(packageRoot, Path.of("codex-package.json"), "package metadata");
		JsonNode metadata = VoiceRuntimeReceipt.parse(scope, Files.readAllBytes(metadataPath), metadataPath.toString());
		VoiceRuntimeReceipt.objectFields(metadata,
			Set.of("layoutVersion", "version", "target", "variant", "entrypoint", "execServer", "resourcesDir",
				"pathDir"),
			metadataPath.toString());
		if (VoiceRuntimeReceipt.integer(metadata, "layoutVersion", metadataPath.toString()) != 1)
			throw new IOException("package layoutVersion must be 1");
		if (!VoiceRuntimeReceipt.string(metadata, "target", metadataPath.toString()).equals(target) ||
			!VoiceRuntimeReceipt.string(metadata, "version", metadataPath.toString()).equals(version))
			throw new IOException("package target/version does not match requested identity");
		if (!VoiceRuntimeReceipt.string(metadata, "variant", metadataPath.toString()).equals("codex"))
			throw new IOException("package variant must be codex");
		// Matches TargetSpec.exe_suffix in scripts/codex_package
		String executableSuffix;
		if (target.contains("windows"))
			executableSuffix = ".exe";
		else
			executableSuffix = "";
		String expectedEntrypoint = "bin/codex" + executableSuffix;
		String expectedExecServer = "bin/codex-exec-server" + executableSuffix;
		String entrypoint = VoiceRuntimeReceipt.string(metadata, "entrypoint", metadataPath.toString());
		String execServer = VoiceRuntimeReceipt.string(metadata, "execServer", metadataPath.toString());
		if (!entrypoint.equals(expectedEntrypoint) || !execServer.equals(expectedExecServer))
			throw new IOException("package entrypoint and execServer must be " + expectedEntrypoint + " and " +
				expectedExecServer + " for target " + target);
		String resourcesDir = VoiceRuntimeReceipt.string(metadata, "resourcesDir", metadataPath.toString());
		String pathDir = VoiceRuntimeReceipt.string(metadata, "pathDir", metadataPath.toString());
		if (!resourcesDir.equals("codex-resources") || !pathDir.equals("codex-path"))
			throw new IOException("package metadata must identify the canonical resourcesDir and pathDir");
		physicalRegularFile(packageRoot, Path.of(execServer), "package execServer");
		return physicalRegularFile(packageRoot, Path.of(entrypoint), "package entrypoint");
	}
}
