package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.ArtifactJson;
import com.holdmyspot.codexunleashed.tooling.CommandRunner;
import com.holdmyspot.codexunleashed.tooling.PayloadFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Assembles and validates the canonical executable package directory.
 */
public final class PackageLayout
{
	private static final String BIN_DIRECTORY = "bin";
	private static final String RESOURCES_DIRECTORY = "codex-resources";
	private static final String PATH_DIRECTORY = "codex-path";
	private static final String METADATA_FILE = "codex-package.json";
	private static final String ZSH_RESOURCE = "zsh/bin/zsh";
	private static final String BWRAP_RESOURCE = "bwrap";
	private static final String WINDOWS_RUNNER_RESOURCE = "codex-command-runner.exe";
	private static final String WINDOWS_SETUP_RESOURCE = "codex-windows-sandbox-setup.exe";
	private static final Set<PosixFilePermission> EXECUTE_PERMISSIONS =
		PosixFilePermissions.fromString("--x--x--x");
	private static final List<String> LEGAL_DOCUMENTS = List.of("LICENSE.md",
		"licenses/openai-codex/LICENSE-APACHE-2.0", "docs/LICENSE.html", "docs/terms.html", "docs/privacy.html");

	/**
	 * Prevents construction.
	 */
	private PackageLayout()
	{
	}

	/**
	 * Describes a resolved package assembly operation.
	 *
	 * @param directory the prepared package output directory
	 * @param version the package version
	 * @param variant the executable variant
	 * @param target the release target
	 * @param inputs the resolved binary inputs
	 * @param repository the project repository containing offline legal documents
	 * @param preparedLicenses the optional prepared third-party license directory
	 * @param workspace the optional upstream workspace for license metadata
	 */
	public record Request(Path directory, String version, PackageVariant variant, PackageTarget target,
		PackageInputs inputs, Path repository, Optional<Path> preparedLicenses, Optional<Path> workspace)
	{
		/**
		 * Validates required paths, values, and optional containers.
		 *
		 * @param directory the prepared package output directory
		 * @param version the package version
		 * @param variant the executable variant
		 * @param target the release target
		 * @param inputs the resolved binary inputs
		 * @param repository the project repository containing offline legal documents
		 * @param preparedLicenses the optional prepared third-party license directory
		 * @param workspace the optional upstream workspace for license metadata
		 * @throws NullPointerException if any argument is null
		 */
		public Request
		{
			Objects.requireNonNull(directory, "directory");
			Objects.requireNonNull(version, "version");
			Objects.requireNonNull(variant, "variant");
			Objects.requireNonNull(target, "target");
			Objects.requireNonNull(inputs, "inputs");
			Objects.requireNonNull(repository, "repository");
			Objects.requireNonNull(preparedLicenses, "preparedLicenses");
			Objects.requireNonNull(workspace, "workspace");
		}
	}

	/**
	 * Prepares an empty output directory, replacing existing contents only when requested.
	 *
	 * @param directory the output directory
	 * @param force whether existing directory contents may be deleted
	 * @throws IOException if the output is a file, replacement is not authorized, or preparation fails
	 * @throws NullPointerException if {@code directory} is null
	 */
	public static void prepare(Path directory, boolean force) throws IOException
	{
		Objects.requireNonNull(directory, "directory");
		if (Files.exists(directory))
		{
			if (!Files.isDirectory(directory))
				throw new IOException("Package output is not a directory: " + directory);
			boolean populated;
			try (Stream<Path> children = Files.list(directory))
			{
				populated = children.findAny().isPresent();
			}
			if (populated)
			{
				if (!force)
					throw new IOException("Package output is not empty; use --force to replace it: " + directory);
				if (Files.isSymbolicLink(directory))
					throw new IOException("Cannot replace a symbolic-link package directory: " + directory);
				deleteTree(directory);
			}
		}
		Files.createDirectories(directory);
	}

	/**
	 * Copies resolved executables, resources, and legal materials and writes package metadata.
	 *
	 * @param request the resolved assembly request
	 * @param metadata the locked Cargo metadata command boundary
	 * @throws IOException if directory creation, input copying, license collection, or metadata writing fails
	 * @throws NullPointerException if any argument is null
	 */
	public static void build(Request request, CommandRunner metadata) throws IOException
	{
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(metadata, "metadata");
		Path directory = request.directory();
		Path bin = Files.createDirectory(directory.resolve(BIN_DIRECTORY));
		Path resources = Files.createDirectory(directory.resolve(RESOURCES_DIRECTORY));
		Path path = Files.createDirectory(directory.resolve(PATH_DIRECTORY));
		PackageLegalMaterials.copy(request.repository(), directory, request.preparedLicenses(), request.workspace(),
			metadata);
		PackageTarget target = request.target();
		PackageInputs inputs = request.inputs();
		copyExecutable(inputs.entrypoint(), bin.resolve(request.variant().entrypointName(target)), target.isWindows());
		copyExecutable(inputs.codeModeHost(), bin.resolve("codex-code-mode-host" + target.executableSuffix()),
			target.isWindows());
		copyExecutable(inputs.ripgrep(), path.resolve(target.ripgrepName()), target.isWindows());
		copyOptional(inputs.zsh(), resources.resolve(ZSH_RESOURCE), false);
		copyOptional(inputs.bwrap(), resources.resolve(BWRAP_RESOURCE), false);
		copyOptional(inputs.windowsCommandRunner(), resources.resolve(WINDOWS_RUNNER_RESOURCE), true);
		copyOptional(inputs.windowsSandboxSetup(), resources.resolve(WINDOWS_SETUP_RESOURCE), true);

		Map<String, Object> document = new LinkedHashMap<>();
		document.put("layoutVersion", 1);
		document.put("version", request.version());
		document.put("target", target.triple());
		document.put("variant", request.variant().variantName());
		document.put("entrypoint", BIN_DIRECTORY + "/" + request.variant().entrypointName(target));
		document.put("resourcesDir", RESOURCES_DIRECTORY);
		document.put("pathDir", PATH_DIRECTORY);
		Files.writeString(directory.resolve(METADATA_FILE), ArtifactJson.formatPreservingKeyOrder(document));
	}

	/**
	 * Validates package metadata, offline documents, executables, and target-specific resources.
	 *
	 * @param directory the package directory
	 * @param variant the expected executable variant
	 * @param target the expected release target
	 * @param includeZsh whether the package must include patched zsh
	 * @throws IOException if metadata, directories, required files, or executable permissions are invalid
	 * @throws NullPointerException if any argument is null
	 */
	public static void validate(Path directory, PackageVariant variant, PackageTarget target, boolean includeZsh)
		throws IOException
	{
		Objects.requireNonNull(directory, "directory");
		Objects.requireNonNull(variant, "variant");
		Objects.requireNonNull(target, "target");
		for (String name : List.of(BIN_DIRECTORY, RESOURCES_DIRECTORY, PATH_DIRECTORY))
			if (!Files.isDirectory(directory.resolve(name)))
				throw new IOException("Missing package directory: " + directory.resolve(name));
		Path metadata = directory.resolve(METADATA_FILE);
		if (!Files.isRegularFile(metadata))
			throw new IOException("Missing package file: " + metadata);
		JsonNode document;
		try
		{
			document = JsonMapper.builder().build().readTree(Files.readString(metadata));
		}
		catch (tools.jackson.core.JacksonException failure)
		{
			throw new IOException("Invalid package metadata: " + metadata, failure);
		}
		Map<String, Object> expected = Map.of("layoutVersion", 1, "target", target.triple(),
			"variant", variant.variantName(), "entrypoint", BIN_DIRECTORY + "/" + variant.entrypointName(target),
			"resourcesDir", RESOURCES_DIRECTORY, "pathDir", PATH_DIRECTORY);
		for (Map.Entry<String, Object> entry : expected.entrySet())
		{
			if (document == null || !matchesMetadata(document.get(entry.getKey()), entry.getValue()))
				throw new IOException("Invalid package metadata field: " + entry.getKey());
		}
		List<String> executables = new ArrayList<>(List.of(BIN_DIRECTORY + "/" + variant.entrypointName(target),
			BIN_DIRECTORY + "/codex-code-mode-host" + target.executableSuffix(), PATH_DIRECTORY + "/" +
				target.ripgrepName()));
		if (includeZsh)
			executables.add(RESOURCES_DIRECTORY + "/" + ZSH_RESOURCE);
		if (target.isLinux())
			executables.add(RESOURCES_DIRECTORY + "/" + BWRAP_RESOURCE);
		if (target.isWindows())
		{
			executables.add(RESOURCES_DIRECTORY + "/" + WINDOWS_RUNNER_RESOURCE);
			executables.add(RESOURCES_DIRECTORY + "/" + WINDOWS_SETUP_RESOURCE);
		}
		for (String name : LEGAL_DOCUMENTS)
			requireFile(directory.resolve(name));
		for (String name : executables)
		{
			Path file = directory.resolve(name);
			requireFile(file);
			if (!target.isWindows() && !isExecutable(file))
				throw new IOException("Package file is not executable: " + file);
		}
	}

	/**
	 * Compares metadata using the retained JSON scalar equality rules.
	 *
	 * @param actual the parsed field, or null if absent
	 * @param expected the required scalar value
	 * @return whether the actual value matches
	 */
	private static boolean matchesMetadata(JsonNode actual, Object expected)
	{
		if (actual == null)
			return false;
		if (expected instanceof Integer number)
			return actual.isNumber() && actual.doubleValue() == number ||
				actual.isBoolean() && actual.booleanValue();
		return actual.isString() && actual.stringValue().equals(expected);
	}

	/**
	 * Copies a supplied optional executable.
	 *
	 * @param source the optional source executable
	 * @param destination the resource destination
	 * @param windows whether the executable uses Windows permissions
	 * @throws IOException if copying or setting permissions fails
	 */
	private static void copyOptional(Optional<Path> source, Path destination, boolean windows) throws IOException
	{
		if (source.isPresent())
			copyExecutable(source.orElseThrow(), destination, windows);
	}

	/**
	 * Copies executable bytes and adds all POSIX execute bits for Unix packages.
	 *
	 * @param source the input executable
	 * @param destination the package destination
	 * @param windows whether the executable uses Windows permissions
	 * @throws IOException if copying or permission updates fail
	 */
	private static void copyExecutable(Path source, Path destination, boolean windows) throws IOException
	{
		Files.createDirectories(destination.getParent());
		PayloadFiles.copy(source, destination);
		if (!windows)
		{
			PosixFileAttributeView view = Files.getFileAttributeView(destination, PosixFileAttributeView.class);
			if (view == null)
				throw new IOException("Unix package executable permissions require POSIX filesystem support: " + destination);
			Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
			permissions.addAll(view.readAttributes().permissions());
			permissions.addAll(EXECUTE_PERMISSIONS);
			view.setPermissions(permissions);
		}
	}

	/**
	 * Requires a regular package file.
	 *
	 * @param file the required file
	 * @throws IOException if the file is absent or is not regular
	 */
	private static void requireFile(Path file) throws IOException
	{
		if (!Files.isRegularFile(file))
			throw new IOException("Missing package file: " + file);
	}

	/**
	 * Tests whether the POSIX owner execute bit is present.
	 *
	 * @param file the package executable
	 * @return whether the file has the owner execute bit
	 * @throws IOException if permissions cannot be read
	 */
	private static boolean isExecutable(Path file) throws IOException
	{
		PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
		if (view == null)
			throw new IOException("Unix package executable permissions require POSIX filesystem support: " + file);
		Set<PosixFilePermission> permissions = view.readAttributes().permissions();
		return permissions.contains(PosixFilePermission.OWNER_EXECUTE);
	}

	/**
	 * Deletes a package tree without following directory symlinks.
	 *
	 * @param directory the output tree
	 * @throws IOException if traversal or deletion fails
	 */
	private static void deleteTree(Path directory) throws IOException
	{
		try (Stream<Path> paths = Files.walk(directory))
		{
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.delete(path);
		}
		catch (UncheckedIOException failure)
		{
			throw failure.getCause();
		}
	}
}
