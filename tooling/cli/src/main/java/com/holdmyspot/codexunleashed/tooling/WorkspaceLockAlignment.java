package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Records the workspace package version of {@code codex-rs/Cargo.toml} in the {@code [[package]]} entries of
 * {@code codex-rs/Cargo.lock} that name workspace packages, and commits the result.
 * <p>
 * An upstream release commit changes the workspace version in the manifest but leaves the lockfile at the development
 * version {@code 0.0.0}. Cargo then rewrites the lockfile on its first run, which dirties a checkout whose identity a
 * release records, and a {@code --locked} run rejects it. This command makes that edit before Cargo runs, without
 * Cargo and without a network, and commits it so that the checkout is clean again. The commit is reproducible: its
 * author and committer, message and dates are fixed by the command and by the commit it follows, so independent runs
 * on the same checkout give the same commit and tree. No commit is made when the lockfile already matches.
 */
public final class WorkspaceLockAlignment
{
	/**
	 * The command name.
	 */
	public static final String NAME = "align-workspace-lock";

	/**
	 * Names the arguments that the command accepts.
	 */
	private static final String USAGE = "Usage: codex-tooling " + NAME + " --source-root ROOT";
	/**
	 * Names the argument that selects the upstream checkout.
	 */
	private static final String SOURCE_ROOT_OPTION = "--source-root";
	/**
	 * Starts each package entry of a lockfile.
	 */
	private static final String PACKAGE_HEADER = "[[package]]";
	/**
	 * Starts the line that names a lockfile package.
	 */
	private static final String NAME_PREFIX = "name = \"";
	/**
	 * Starts the line that gives a lockfile package's version.
	 */
	private static final String VERSION_PREFIX = "version = \"";
	/**
	 * Starts the line that gives a lockfile package's registry or Git origin, which a workspace package lacks.
	 */
	private static final String SOURCE_PREFIX = "source = ";
	/**
	 * Names the lockfile relative to the checkout, as Git addresses it. The only path that the alignment commit changes.
	 */
	static final String LOCK_PATH = "codex-rs/Cargo.lock";
	/**
	 * Names the author and committer of the alignment commit, which is the identity that applies the patch queue.
	 */
	static final String COMMIT_NAME = "Gili Tzabari";
	/**
	 * Gives the email address of the author and committer of the alignment commit.
	 */
	static final String COMMIT_EMAIL = "cowwoc2020@gmail.com";
	/**
	 * The message of the alignment commit.
	 */
	static final String COMMIT_MESSAGE = "Align workspace package versions in Cargo.lock with the release version";

	/**
	 * Prevents construction.
	 */
	private WorkspaceLockAlignment()
	{
	}

	/**
	 * Aligns and commits the lockfile of one upstream checkout and reports how many package versions changed.
	 *
	 * @param scope       shared tooling values borrowed until the command finishes
	 * @param arguments   {@code --source-root} followed by the checkout whose {@code codex-rs} directory holds the
	 *                    manifest and lockfile
	 * @param environment child environment overrides for Git
	 * @param out         the number of aligned package versions
	 * @param err         diagnostics
	 * @return zero on success, one when the manifest, a workspace package manifest, the lockfile or Git cannot be used,
	 *         or two for invalid command arguments
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(ToolingScope scope, String[] arguments, Map<String, String> environment, PrintStream out,
		PrintStream err)
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(environment, "environment");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");

		if (arguments.length != 2 || !arguments[0].equals(SOURCE_ROOT_OPTION))
		{
			err.println(USAGE);
			return 2;
		}

		try
		{
			int aligned = alignAndCommit(scope, SystemCommands::capture,
				Path.of(System.getProperty("java.io.tmpdir")), environment, Path.of(arguments[1]));
			out.println("Aligned " + aligned + " workspace package versions in " + LOCK_PATH);
			return 0;
		}
		catch (IOException failure)
		{
			err.println(NAME + ": " + failure.getMessage());
			return 1;
		}
	}

	/**
	 * Aligns the lockfile and, when that changed it, commits the lockfile on top of the checkout's HEAD. The commit's
	 * author and committer are the identity that applies the patch queue, its message is fixed, and both of its dates
	 * are the author date of HEAD, so that the same checkout always yields the same commit. Other working-tree changes
	 * are neither staged nor committed.
	 *
	 * @param scope              shared tooling values borrowed for the duration of the call
	 * @param capture            runs Git
	 * @param temporaryDirectory existing parent directory for the output captured from Git
	 * @param environment        child environment overrides for Git
	 * @param sourceRoot         checkout whose {@code codex-rs/Cargo.toml} and {@code codex-rs/Cargo.lock} are read
	 * @return the number of package versions that changed, zero when no commit was made
	 * @throws NullPointerException if any argument is null
	 * @throws IOException          if {@link #align} fails, or Git cannot read HEAD, stage the lockfile or commit it
	 */
	public static int alignAndCommit(ToolingScope scope, SystemCommands.Capture capture, Path temporaryDirectory,
		Map<String, String> environment, Path sourceRoot) throws IOException
	{
		Objects.requireNonNull(capture, "capture");
		Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
		Objects.requireNonNull(environment, "environment");

		int changed = align(scope, sourceRoot);
		if (changed == 0)
			return 0;

		String date = git(capture, temporaryDirectory, environment, sourceRoot, "log", "-1", "--format=%aI", "HEAD").
			strip();
		Map<String, String> commitEnvironment = new HashMap<>(environment);
		commitEnvironment.put("GIT_AUTHOR_NAME", COMMIT_NAME);
		commitEnvironment.put("GIT_AUTHOR_EMAIL", COMMIT_EMAIL);
		commitEnvironment.put("GIT_AUTHOR_DATE", date);
		commitEnvironment.put("GIT_COMMITTER_NAME", COMMIT_NAME);
		commitEnvironment.put("GIT_COMMITTER_EMAIL", COMMIT_EMAIL);
		commitEnvironment.put("GIT_COMMITTER_DATE", date);
		git(capture, temporaryDirectory, commitEnvironment, sourceRoot, "add", "--", LOCK_PATH);
		git(capture, temporaryDirectory, commitEnvironment, sourceRoot, "-c", "commit.gpgsign=false", "commit", "--quiet",
			"--no-verify", "--only", "-m", COMMIT_MESSAGE, "--", LOCK_PATH);
		return changed;
	}

	/**
	 * Runs Git in the checkout and requires success.
	 *
	 * @param capture            runs Git
	 * @param temporaryDirectory existing parent directory for the captured output
	 * @param environment        child environment overrides
	 * @param sourceRoot         working directory
	 * @param arguments          literal Git arguments
	 * @return the standard output
	 * @throws IOException if Git cannot run or exits unsuccessfully
	 */
	private static String git(SystemCommands.Capture capture, Path temporaryDirectory, Map<String, String> environment,
		Path sourceRoot, String... arguments) throws IOException
	{
		List<String> command = new ArrayList<>(List.of("git"));
		command.addAll(List.of(arguments));
		SystemCommands.Result result = capture.capture(command, sourceRoot, temporaryDirectory, environment);
		if (result.status() != 0)
			throw new IOException("git " + String.join(" ", arguments) + " failed: " + result.stderr().strip());
		return result.stdout();
	}

	/**
	 * Sets the version of each workspace package in the lockfile to the version that its manifest resolves to. A
	 * package whose lockfile version already matches is left byte for byte unchanged, so a lockfile that already
	 * records the release version stays as it is.
	 * <p>
	 * A workspace package is a member listed by {@code workspace.members} or a path dependency listed by
	 * {@code workspace.dependencies}. Cargo also treats a path dependency inside the workspace as a member, and the
	 * upstream workspace lists such packages only as dependencies. A lockfile package that has no {@code source} and
	 * names no workspace package is left unchanged.
	 *
	 * @param scope      shared tooling values borrowed for the duration of the call
	 * @param sourceRoot checkout whose {@code codex-rs/Cargo.toml} and {@code codex-rs/Cargo.lock} are read
	 * @return the number of package versions that changed
	 * @throws NullPointerException if any argument is null
	 * @throws IOException          if a manifest or the lockfile cannot be read or written, a manifest is malformed, or
	 *                              {@code workspace.members} contains a glob
	 */
	public static int align(ToolingScope scope, Path sourceRoot) throws IOException
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(sourceRoot, "sourceRoot");

		Path workspace = sourceRoot.resolve("codex-rs");
		Path lockFile = workspace.resolve("Cargo.lock");
		Map<String, String> versions = workspacePackageVersions(scope, workspace);
		String lock = Files.readString(lockFile);

		List<String> lines = new ArrayList<>(List.of(lock.split("(?<=\n)")));
		int changed = alignPackageVersions(lines, versions);
		if (changed == 0)
			return 0;
		Files.writeString(lockFile, String.join("", lines));
		return changed;
	}

	/**
	 * Resolves the version of each workspace package from its manifest.
	 *
	 * @param scope     borrowed TOML parser
	 * @param workspace the {@code codex-rs} directory
	 * @return the resolved version of each workspace package, by package name
	 * @throws IOException if a manifest cannot be read or lacks a required field, or a member is a glob
	 */
	private static Map<String, String> workspacePackageVersions(ToolingScope scope, Path workspace) throws IOException
	{
		JsonNode workspaceTable = readManifest(scope, workspace.resolve("Cargo.toml")).path("workspace");
		JsonNode workspaceVersion = workspaceTable.path("package").path("version");
		if (!workspaceVersion.isString())
			throw new IOException("codex-rs/Cargo.toml must define workspace.package.version");

		Set<String> directories = new LinkedHashSet<>();
		for (JsonNode member : workspaceTable.path("members"))
		{
			String path = member.asString();
			if (path.contains("*") || path.contains("?") || path.contains("["))
			{
				throw new IOException("workspace.members entry '" + path + "' is a glob; list each member explicitly so " +
					"that its package name can be read without Cargo");
			}
			directories.add(path);
		}
		for (JsonNode dependency : workspaceTable.path("dependencies"))
		{
			JsonNode path = dependency.path("path");
			if (path.isString())
				directories.add(path.asString());
		}

		Map<String, String> versions = new HashMap<>();
		for (String directory : directories)
		{
			Path manifestPath = workspace.resolve(directory).resolve("Cargo.toml");
			JsonNode packageTable = readManifest(scope, manifestPath).path("package");
			JsonNode name = packageTable.path("name");
			JsonNode version = packageTable.path("version");
			if (!name.isString())
				throw new IOException(directory + "/Cargo.toml must define package.name");

			String resolved;
			if (version.isString())
				resolved = version.asString();
			else if (version.path("workspace").isBoolean() && version.path("workspace").booleanValue())
				resolved = workspaceVersion.asString();
			else
				throw new IOException(directory + "/Cargo.toml must define package.version");
			String previous = versions.put(name.asString(), resolved);
			if (previous != null && !previous.equals(resolved))
			{
				throw new IOException("Workspace package " + name.asString() + " resolves to both " + previous +
					" and " + resolved);
			}
		}
		return versions;
	}

	/**
	 * Parses a manifest with the shared TOML mapper.
	 *
	 * @param scope        borrowed TOML parser
	 * @param manifestPath manifest to read
	 * @return the parsed manifest
	 * @throws IOException if the file cannot be read or is not valid TOML
	 */
	private static JsonNode readManifest(ToolingScope scope, Path manifestPath) throws IOException
	{
		try
		{
			return scope.getTomlMapper().readTree(Files.readString(manifestPath));
		}
		catch (JacksonException failure)
		{
			throw new IOException("Invalid " + manifestPath.getFileName() + " at " + manifestPath + ": " +
				failure.getOriginalMessage(), failure);
		}
	}

	/**
	 * Edits the lockfile lines in place: the version line of each source-less workspace package, then the
	 * dependency references that name its previous version.
	 *
	 * @param lines    lockfile lines, each with its line terminator
	 * @param versions the version that each workspace package must record, by package name
	 * @return the number of package versions that changed
	 */
	private static int alignPackageVersions(List<String> lines, Map<String, String> versions)
	{
		// Cargo writes a dependency as "name version" only when several packages share the name, so these references
		// must follow the version of the package they name.
		Map<String, String> renamedReferences = new HashMap<>();
		int changed = 0;
		int blockStart = 0;
		for (int index = 1; index <= lines.size(); ++index)
		{
			if (index < lines.size() && !lines.get(index).startsWith("["))
				continue;
			if (stripTerminator(lines.get(blockStart)).equals(PACKAGE_HEADER))
				changed += alignBlock(lines, blockStart, index, versions, renamedReferences);
			blockStart = index;
		}
		if (renamedReferences.isEmpty())
			return changed;

		for (int index = 0; index < lines.size(); ++index)
		{
			String line = lines.get(index);
			String content = stripTerminator(line);
			String trimmed = content.strip();
			if (!trimmed.startsWith("\""))
				continue;

			String suffix = "";
			if (trimmed.endsWith("\","))
				suffix = ",";
			else if (trimmed.length() < 2 || !trimmed.endsWith("\""))
				continue;
			String reference = trimmed.substring(1, trimmed.length() - suffix.length() - 1);
			String renamed = renamedReferences.get(reference);
			if (renamed != null)
			{
				String indentation = content.substring(0, content.indexOf('"'));
				lines.set(index, indentation + "\"" + renamed + "\"" + suffix + line.substring(content.length()));
			}
		}
		return changed;
	}

	/**
	 * Aligns one {@code [[package]]} entry.
	 *
	 * @param lines             lockfile lines, each with its line terminator
	 * @param start             index of the entry's {@code [[package]]} line
	 * @param end               index after the entry's last line
	 * @param versions          the version that each workspace package must record, by package name
	 * @param renamedReferences receives each replaced {@code "name version"} reference
	 * @return one when the entry's version changed, otherwise zero
	 */
	private static int alignBlock(List<String> lines, int start, int end, Map<String, String> versions,
		Map<String, String> renamedReferences)
	{
		String name = null;
		int versionIndex = -1;
		boolean hasSource = false;
		for (int index = start + 1; index < end; ++index)
		{
			String content = stripTerminator(lines.get(index));
			if (content.startsWith(NAME_PREFIX) && content.endsWith("\""))
				name = content.substring(NAME_PREFIX.length(), content.length() - 1);
			else if (content.startsWith(VERSION_PREFIX) && content.endsWith("\""))
				versionIndex = index;
			else if (content.startsWith(SOURCE_PREFIX))
				hasSource = true;
		}
		if (hasSource || name == null || versionIndex < 0 || !versions.containsKey(name))
			return 0;

		String line = lines.get(versionIndex);
		String content = stripTerminator(line);
		String previous = content.substring(VERSION_PREFIX.length(), content.length() - 1);
		String desired = versions.get(name);
		if (previous.equals(desired))
			return 0;

		lines.set(versionIndex, VERSION_PREFIX + desired + "\"" + line.substring(content.length()));
		renamedReferences.put(name + " " + previous, name + " " + desired);
		return 1;
	}

	/**
	 * Removes the line terminator so that a rewritten line can keep the original one.
	 *
	 * @param line lockfile line
	 * @return the line without its trailing {@code \n} or {@code \r\n}
	 */
	private static String stripTerminator(String line)
	{
		int end = line.length();
		while (end > 0 && (line.charAt(end - 1) == '\n' || line.charAt(end - 1) == '\r'))
			--end;
		return line.substring(0, end);
	}
}
