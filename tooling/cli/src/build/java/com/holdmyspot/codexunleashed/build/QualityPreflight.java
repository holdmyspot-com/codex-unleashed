package com.holdmyspot.codexunleashed.build;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.spi.ToolProvider;

/**
 * Selects exact Java inputs and aggregates the focused analyzer results.
 * Callers retain the invocation reports until they remove the Maven build output.
 */
public final class QualityPreflight
{
	/**
	 * The source roots admitted by the focused Java quality contract.
	 */
	private static final List<Path> SOURCE_ROOTS = List.of(Path.of("tooling/cli/src/main/java"),
		Path.of("tooling/cli/src/test/java"), Path.of("tooling/cli/src/build/java"));
	/**
	 * The pointer to the current invocation within one Maven build directory.
	 */
	private static final String CURRENT_RUN = "current.properties";
	/**
	 * The Ant property locating this invocation's retained reports.
	 */
	private static final String RUN_ID = "quality.run.id";
	/**
	 * The retained exact-input copies used by Ant's presence selector.
	 */
	private static final String SOURCES = "sources";
	/**
	 * The retained manifest containing the admitted source paths.
	 */
	private static final String MANIFEST = "paths.txt";
	/**
	 * The Javadoc diagnostics produced for the selected sources.
	 */
	private static final String JAVADOC_LOG = "javadoc.log";

	/**
	 * Prepares exact inputs, or checks the retained analyzer outcomes and Javadoc.
	 *
	 * @param args operation, repository, manifest, and report base
	 * @throws IllegalArgumentException if the operation or manifest is invalid
	 * @throws IOException              if input, reports, or a required analyzer cannot be used
	 */
	public static void main(String[] args) throws IOException
	{
		if (args.length != 4)
			throw new IllegalArgumentException("Expected operation, repository, manifest, and report base");
		Path repository = Path.of(args[1]).toAbsolutePath().normalize();
		Path manifest = repository.resolve(Path.of(args[2]));
		Path reports = Path.of(args[3]);
		switch (args[0])
		{
			case "prepare" -> prepare(repository, manifest, reports);
			case "verify" -> verify(repository, reports);
			default -> throw new IllegalArgumentException("Unknown quality operation: " + args[0]);
		}
	}
	/**
	 * Prevents construction.
	 */
	private QualityPreflight()
	{
	}

	/**
	 * Reads source paths from a UTF-8 manifest.
	 *
	 * @param repository checkout containing the selected sources
	 * @param manifest   UTF-8 file containing repository-relative source paths
	 * @return selected absolute source paths in manifest order
	 * @throws IllegalArgumentException if the manifest is empty, repeats a source, or names an invalid source
	 * @throws IOException              if the manifest cannot be read
	 */
	public static List<Path> readPaths(Path repository, Path manifest) throws IOException
	{
		repository = repository.toAbsolutePath().normalize();
		List<Path> paths = new ArrayList<>();
		Set<Path> selected = new HashSet<>();
		try (BufferedReader reader = Files.newBufferedReader(manifest))
		{
			for (String entry = reader.readLine(); entry != null; entry = reader.readLine())
			{
				if (entry.isBlank())
					throw new IllegalArgumentException("Quality manifest contains a blank entry");
				Path relative = Path.of(entry);
				if (relative.isAbsolute())
					throw new IllegalArgumentException("Quality manifest requires repository-relative paths: " + entry);
				for (Path component : relative)
				{
					if (component.toString().equals(".."))
						throw new IllegalArgumentException("Quality manifest forbids parent traversal: " + entry);
				}
				relative = relative.normalize();
				Path source = repository.resolve(relative);
				boolean allowedRoot = SOURCE_ROOTS.stream().anyMatch(relative::startsWith);
				if (!allowedRoot || !source.getFileName().toString().endsWith(".java"))
					throw new IllegalArgumentException("Quality source must be a Java file in main, test, or build: " + entry);
				if (!selected.add(source))
					throw new IllegalArgumentException("Quality manifest repeats a source: " + entry);
				Path componentPath = source.getRoot();
				for (Path component : source)
				{
					componentPath = componentPath.resolve(component);
					if (Files.isSymbolicLink(componentPath))
						throw new IllegalArgumentException("Quality source contains a symbolic-link component: " + entry);
				}
				if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
					throw new IllegalArgumentException("Quality source is missing or is not a regular file: " + entry);
				paths.add(source);
			}
		}
		if (paths.isEmpty())
			throw new IllegalArgumentException("Quality manifest must contain at least one Java source path");
		return List.copyOf(paths);
	}

	/**
	 * Validates all inputs before publishing the source selection for Ant.
	 *
	 * @param repository checkout containing the sources
	 * @param manifest   UTF-8 source manifest
	 * @param reports    Maven-owned report base, whose invocation directories the caller retains
	 * @throws IllegalArgumentException if the source selection is invalid
	 * @throws IOException              if retained inputs or the invocation pointer cannot be written
	 */
	public static void prepare(Path repository, Path manifest, Path reports) throws IOException
	{
		List<Path> paths = readPaths(repository, manifest);
		Files.createDirectories(reports);
		String runId = "run-" + UUID.randomUUID();
		Path invocation = Files.createDirectory(reports.resolve(runId));
		Path sources = invocation.resolve(SOURCES);
		List<String> admitted = new ArrayList<>();
		for (Path source : paths)
		{
			Path relative = repository.toAbsolutePath().normalize().relativize(source);
			Path copy = sources.resolve(relative);
			Files.createDirectories(copy.getParent());
			Files.copy(source, copy);
			admitted.add(relative.toString().replace('\\', '/'));
		}
		Files.write(invocation.resolve(MANIFEST), admitted);
		Files.writeString(reports.resolve(CURRENT_RUN), RUN_ID + "=" + runId + "\n");
	}

	/**
	 * Runs private Javadoc and fails when any analyzer reports findings or tool failure.
	 *
	 * @param repository checkout containing the selected sources
	 * @param reports    report base prepared by this invocation
	 * @throws IOException if a tool fails, findings exist, or retained reports cannot be read or written
	 */
	private static void verify(Path repository, Path reports) throws IOException
	{
		Properties current = new Properties();
		try (Reader reader = Files.newBufferedReader(reports.resolve(CURRENT_RUN)))
		{
			current.load(reader);
		}
		Path invocation = reports.resolve(current.getProperty(RUN_ID));
		List<Path> paths = readPaths(repository, invocation.resolve(MANIFEST));
		Path classpath = invocation.resolve("classpath.txt");
		String doclint;
		if (Files.isRegularFile(classpath))
			doclint = runJavadoc(invocation, paths, Files.readString(classpath).strip());
		else
		{
			Files.writeString(invocation.resolve(JAVADOC_LOG), "Maven did not publish the resolved test classpath\n");
			doclint = "tool-error";
		}
		List<String> outcomes = new ArrayList<>();
		outcomes.add("checkstyle=" + readOutcome(invocation, "checkstyle"));
		outcomes.add("pmd=" + readOutcome(invocation, "pmd"));
		outcomes.add("javadoc=" + doclint);
		Files.write(invocation.resolve("outcomes.properties"), outcomes);
		if (outcomes.stream().anyMatch(outcome -> !outcome.endsWith("=passed")))
		{
			throw new IOException("Java quality preflight failed: " + String.join(", ", outcomes) +
				"; reports: " + invocation);
		}
	}

	/**
	 * Runs the installed JDK's private Javadoc/doclint against the exact named sources.
	 *
	 * @param invocation retained report directory
	 * @param sources    exact selected source files
	 * @param classpath  compiled main/test/build classes and resolved Maven dependencies
	 * @return passed, findings, or tool-error
	 * @throws IOException if Javadoc diagnostics cannot be written
	 */
	private static String runJavadoc(Path invocation, List<Path> sources, String classpath) throws IOException
	{
		if (Runtime.version().feature() != 27)
		{
			Files.writeString(invocation.resolve(JAVADOC_LOG), "JDK 27 is required for Java quality preflight\n");
			return "tool-error";
		}
		ToolProvider javadoc = ToolProvider.findFirst("javadoc").orElse(null);
		if (javadoc == null)
		{
			Files.writeString(invocation.resolve(JAVADOC_LOG), "JDK 27 Javadoc tool provider is unavailable\n");
			return "tool-error";
		}
		List<String> arguments = new ArrayList<>(List.of("-private", "-Xdoclint:all", "-Werror", "-quiet",
			"-encoding", "UTF-8", "-classpath", classpath, "--module-path", classpath, "-sourcepath", "", "-d",
			invocation.resolve("javadoc").toString()));
		for (Path source : sources)
			arguments.add(source.toString());
		try (PrintWriter diagnostics = new PrintWriter(Files.newBufferedWriter(invocation.resolve(JAVADOC_LOG))))
		{
			int status = javadoc.run(diagnostics, diagnostics, arguments.toArray(String[]::new));
			return switch (status)
			{
				case 0 -> "passed";
				case 1 -> "findings";
				default -> "tool-error";
			};
		}
	}

	/**
	 * Reads an Ant analyzer receipt, preserving failure to reach its completion boundary.
	 *
	 * @param invocation retained report directory
	 * @param analyzer   analyzer's report basename
	 * @return passed, findings, or tool-error when the task never completed
	 * @throws IOException if an existing receipt cannot be read
	 */
	private static String readOutcome(Path invocation, String analyzer) throws IOException
	{
		Path receipt = invocation.resolve(analyzer + ".result");
		if (!Files.isRegularFile(receipt))
			return "tool-error";
		String outcome = Files.readString(receipt).strip();
		return switch (outcome)
		{
			case "passed", "findings" -> outcome;
			default -> "tool-error";
		};
	}
}
