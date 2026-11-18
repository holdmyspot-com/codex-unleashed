package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** Implements the dependency-lints workflow command and its rustc wrapper mode. */
public final class DirectDependencyLints
{
	/** Listing mode. */
	private static final String LIST = "list";
	/** Build warning mode. */
	private static final String BUILD_WARNINGS = "build-warnings";
	/** Environment variable containing the selected package listing. */
	private static final String DIRECT_DEPENDENCIES = "CODEX_DIRECT_DEPENDENCIES";
	/** Prevents construction. */
	private DirectDependencyLints()
	{
	}

	/**
	 * Executes the command with the system compiler boundary.
	 *
	 * @param scope shared parser and tooling services
	 * @param arguments mode followed by literal arguments
	 * @param in caller-owned Cargo metadata input
	 * @param environment child process environment
	 * @param out command output; records end with a line feed on every host
	 * @param err diagnostics
	 * @return command status
	 */
	public static int run(ToolingScope scope, String[] arguments, InputStream in, Map<String, String> environment,
		PrintStream out, PrintStream err)
	{
		return run(scope, arguments, in, environment, out, err, SystemCommands::execute);
	}

	/**
	 * Executes the command with a narrow injectable compiler boundary.
	 *
	 * @param scope shared parser and tooling services
	 * @param arguments mode followed by literal arguments
	 * @param in caller-owned Cargo metadata input
	 * @param environment child process environment
	 * @param out command output; records end with a line feed on every host
	 * @param err diagnostics
	 * @param compiler external compiler process boundary
	 * @return command status
	 */
	public static int run(ToolingScope scope, String[] arguments, InputStream in, Map<String, String> environment,
		PrintStream out, PrintStream err, Compiler compiler)
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(in, "in");
		Objects.requireNonNull(environment, "environment");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		Objects.requireNonNull(compiler, "compiler");
		try
		{
			if (arguments.length == 1 && arguments[0].equals(LIST))
			{
				for (String dependency : directDependencies(scope, in))
					out.print(dependency + "\n");
				return 0;
			}
			if (arguments.length == 3 && arguments[0].equals(BUILD_WARNINGS))
				return buildWarnings(Path.of(arguments[1]), Path.of(arguments[2]), out);
			if (arguments.length == 0)
			{
				usage(err);
				return 2;
			}
			return compile(scope, arguments, environment, compiler);
		}
		catch (IOException | IllegalArgumentException failure)
		{
			err.println("direct-dependency-lints: " + failure.getMessage());
			return 1;
		}
	}

	/** Directly executes the compiler child with inherited process streams. */
	@FunctionalInterface
	public interface Compiler
	{
		/**
		 * Executes a child process.
		 *
		 * @param command executable followed by literal arguments
		 * @param workingDirectory child current directory
		 * @param environment complete child environment
		 * @return ordinary child status
		 * @throws IOException if process execution fails
		 */
		int execute(List<String> command, Path workingDirectory, Map<String, String> environment) throws IOException;
	}

	/**
	 * Rewrites the cap only for an exact selected package identity, then executes the compiler.
	 *
	 * @param scope shared parser services
	 * @param arguments compiler executable and literal arguments
	 * @param environment complete child environment
	 * @param compiler external process boundary
	 * @return ordinary compiler status
	 * @throws IOException if a listing, manifest, or child process cannot be read or executed
	 */
	private static int compile(ToolingScope scope, String[] arguments, Map<String, String> environment,
		Compiler compiler) throws IOException
	{
		String listingPath = environment.get(DIRECT_DEPENDENCIES);
		if (listingPath == null)
			throw new IllegalArgumentException("missing " + DIRECT_DEPENDENCIES);
		Set<String> direct = new HashSet<>();
		for (String line : Files.readAllLines(Path.of(listingPath), StandardCharsets.UTF_8))
			if (!line.isBlank())
				direct.add(line.trim());

		String name = environment.getOrDefault("CARGO_PKG_NAME", "");
		String version = environment.getOrDefault("CARGO_PKG_VERSION", "");
		String identity = name + " " + version;
		List<String> command = new ArrayList<>();
		command.add(arguments[0]);
		for (int index = 1; index < arguments.length; index = index + 1)
			command.add(arguments[index]);
		if (direct.contains(identity))
		{
			String manifestPath = environment.get("CARGO_MANIFEST_PATH");
			if (manifestPath == null)
				throw new IllegalArgumentException("missing CARGO_MANIFEST_PATH for " + identity);
			JsonNode manifest;
			try
			{
				manifest = scope.getTomlMapper().readTree(Files.readString(Path.of(manifestPath), StandardCharsets.UTF_8));
			}
			catch (JacksonException failure)
			{
				throw new IOException("failed to read Cargo manifest " + manifestPath + ": " + failure.getMessage(), failure);
			}
			List<String> flags = lintFlags(manifest);
			List<String> rewritten = replaceCap(command.subList(1, command.size()), flags);
			command = new ArrayList<>(List.of(arguments[0]));
			command.addAll(rewritten);
		}
		return compiler.execute(List.copyOf(command), Path.of("").toAbsolutePath(), Map.copyOf(environment));
	}

	/**
	 * Selects sorted unique external packages directly referenced by workspace members.
	 *
	 * @param scope shared JSON parser service
	 * @param input Cargo metadata JSON
	 * @return sorted exact package name and version lines
	 * @throws IOException if metadata cannot be parsed
	 */
	private static List<String> directDependencies(ToolingScope scope, InputStream input) throws IOException
	{
		JsonNode metadata;
		try
		{
			metadata = scope.getJsonMapper().readTree(new NonClosingInputStream(input));
		}
		catch (JacksonException failure)
		{
			throw new IOException("invalid Cargo metadata: " + failure.getMessage(), failure);
		}
		Set<String> members = new HashSet<>();
		for (JsonNode member : requiredArray(metadata, "workspace_members"))
			members.add(member.asString());
		Map<String, JsonNode> packages = new HashMap<>();
		for (JsonNode record : requiredArray(metadata, "packages"))
			packages.put(requiredText(record, "id"), record);
		Set<String> found = new TreeSet<>();
		for (JsonNode node : requiredArray(metadata.path("resolve"), "nodes"))
		{
			String nodeId = requiredText(node, "id");
			if (!members.contains(nodeId))
				continue;
			for (JsonNode dependency : requiredArray(node, "deps"))
			{
				String packageId = requiredText(dependency, "pkg");
				if (!members.contains(packageId))
				{
					JsonNode record = packages.get(packageId);
					if (record == null)
						throw new IllegalArgumentException("Cargo metadata dependency has no package record: " + packageId);
					found.add(requiredText(record, "name") + " " + requiredText(record, "version"));
				}
			}
		}
		return List.copyOf(found);
	}

	/**
	 * Converts the Cargo lint table to ordered rustc flags.
	 *
	 * @param manifest parsed Cargo manifest
	 * @return lint flags and declared check-cfg pairs
	 */
	private static List<String> lintFlags(JsonNode manifest)
	{
		JsonNode lints = manifest.path("lints");
		List<Lint> ordered = new ArrayList<>();
		if (lints.isObject())
			lints.properties().forEach(tool ->
			{
				if (tool.getKey().equals("cargo") || !tool.getValue().isObject())
					return;
				tool.getValue().properties().forEach(lint ->
				{
					JsonNode config = lint.getValue();
					String level;
					int priority;
					if (config.isTextual())
					{
						level = config.asString();
						priority = 0;
					}
					else
					{
						level = requiredText(config, "level");
						priority = config.path("priority").asInt(0);
					}
					String prefix = "";
					if (!tool.getKey().equals("rust"))
						prefix = tool.getKey() + "::";
					String flag = "--" + level + "=" + prefix +
						lint.getKey();
					ordered.add(new Lint(priority, lint.getKey(), flag));
				});
			});
		ordered.sort(Comparator.comparingInt(Lint::priority).thenComparing(Lint::name, Comparator.reverseOrder()).
			thenComparing(Lint::flag));
		List<String> flags = new ArrayList<>();
		for (Lint lint : ordered)
			flags.add(lint.flag());
		JsonNode unexpected = lints.path("rust").path("unexpected_cfgs");
		JsonNode checkCfg = unexpected.path("check-cfg");
		if (checkCfg.isArray())
			for (JsonNode value : checkCfg)
			{
				flags.add("--check-cfg");
				flags.add(value.asString());
			}
		return flags;
	}

	/**
	 * Replaces every cap argument with the flags at the first cap position, or appends them when no cap exists.
	 *
	 * @param arguments compiler arguments without the executable
	 * @param flags selected package lint flags
	 * @return rewritten compiler arguments
	 */
	private static List<String> replaceCap(List<String> arguments, List<String> flags)
	{
		List<String> result = new ArrayList<>();
		boolean skip = false;
		boolean inserted = false;
		for (String argument : arguments)
		{
			if (skip)
				skip = false;
			else if (argument.equals("--cap-lints") || argument.startsWith("--cap-lints="))
			{
				skip = argument.equals("--cap-lints");
				if (!inserted)
				{
					result.addAll(flags);
					inserted = true;
				}
			}
			else
				result.add(argument);
		}
		if (!inserted)
			result.addAll(flags);
		return result;
	}

	/**
	 * Reports build-script warnings for selected package names beneath Cargo's target directory.
	 *
	 * @param listing direct package name and version lines
	 * @param targetDirectory Cargo target directory
	 * @param out warning output
	 * @return one when warnings exist, otherwise zero
	 * @throws IOException if listing or build output files cannot be read
	 */
	private static int buildWarnings(Path listing, Path targetDirectory, PrintStream out) throws IOException
	{
		Set<String> names = new HashSet<>();
		for (String line : Files.readAllLines(listing, StandardCharsets.UTF_8))
			if (!line.isBlank())
				names.add(line.strip().split("\\s+", 2)[0]);
		List<Path> outputs;
		try (Stream<Path> paths = Files.walk(targetDirectory))
		{
			outputs = paths.filter(Files::isRegularFile).
				filter(path -> path.getFileName().toString().equals("output")).
				filter(path -> path.getParent() != null && path.getParent().getParent() != null &&
					path.getParent().getParent().getFileName().toString().equals("build")).
				sorted(Comparator.comparing(Path::toString)).toList();
		}
		boolean found = false;
		for (Path output : outputs)
		{
			String directory = output.getParent().getFileName().toString();
			int separator = directory.lastIndexOf('-');
			String name = directory;
			if (separator >= 0)
				name = directory.substring(0, separator);
			if (!names.contains(name))
				continue;
			String text = Files.readString(output, StandardCharsets.UTF_8);
			for (String line : text.split("\\R", -1))
			{
				String message = null;
				if (line.startsWith("cargo::warning="))
					message = line.substring("cargo::warning=".length());
				else if (line.startsWith("cargo:warning="))
					message = line.substring("cargo:warning=".length());
				if (message != null)
				{
					out.print(name + ": " + message + "\n");
					found = true;
				}
			}
		}
		if (found)
			return 1;
		return 0;
	}

	/**
	 * Reads a required JSON array field.
	 *
	 * @param parent object containing the field
	 * @param name field name
	 * @return field array
	 * @throws IllegalArgumentException if the field is not an array
	 */
	private static JsonNode requiredArray(JsonNode parent, String name)
	{
		JsonNode value = parent.path(name);
		if (!value.isArray())
			throw new IllegalArgumentException("Cargo metadata field must be an array: " + name);
		return value;
	}

	/**
	 * Reads a required textual JSON field.
	 *
	 * @param parent object containing the field
	 * @param name field name
	 * @return field text
	 * @throws IllegalArgumentException if the field is not text
	 */
	private static String requiredText(JsonNode parent, String name)
	{
		JsonNode value = parent.path(name);
		if (!value.isTextual())
			throw new IllegalArgumentException("Cargo metadata field must be text: " + name);
		return value.asString();
	}

	/**
	 * Writes command usage.
	 *
	 * @param err diagnostics
	 */
	private static void usage(PrintStream err)
	{
		err.println("Usage: direct-dependency-lints list | build-warnings LIST_FILE TARGET_DIR | RUSTC ARGUMENT...");
	}

	/**
	 * Holds one Cargo lint and its sort keys.
	 *
	 * @param priority Cargo lint priority
	 * @param name lint name
	 * @param flag rustc flag
	 */
	private record Lint(int priority, String name, String flag)
	{
	}

	/** Prevents the configured Jackson mapper from closing caller-owned stdin. */
	private static final class NonClosingInputStream extends InputStream
	{
		/** Caller-owned source stream. */
		private final InputStream input;

		/**
		 * Wraps a stream so JSON parser cleanup leaves its caller-owned source open.
		 *
		 * @param input caller-owned stream
		 */
		private NonClosingInputStream(InputStream input)
		{
			this.input = input;
		}

		@Override
		public int read() throws IOException
		{
			return input.read();
		}

		@Override
		public int read(byte[] bytes, int offset, int length) throws IOException
		{
			return input.read(bytes, offset, length);
		}

		@Override
		public void close()
		{
		}
	}
}
