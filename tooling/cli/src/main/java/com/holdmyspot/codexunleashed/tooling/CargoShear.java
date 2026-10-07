package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks Cargo Shear output against the verified upstream release's unchanged-source baseline.
 */
public final class CargoShear
{
	/**
	 * Prevents construction.
	 */
	private CargoShear()
	{
	}

	/**
	 * Runs Cargo Shear and applies the release warning policy to ordinary tool results.
	 * Exit statuses other than zero and one propagate without interpreting the output as a report.
	 *
	 * @param checkout the upstream checkout
	 * @param baselinePath the verified baseline JSON file
	 * @param shearCommand the Cargo Shear executable and any launcher arguments
	 * @param out the destination for tool diagnostics, reports, and accepted-warning information
	 * @param git the Git command boundary
	 * @param temporaryDirectory the existing directory for owned process-capture files
	 * @return zero on acceptance, one on rejected findings, or the tool's other failure status
	 * @throws NullPointerException if any argument or command element is null
	 * @throws IllegalArgumentException if {@code shearCommand} is empty
	 * @throws IOException if the baseline, Git command, process capture, or report is invalid
	 */
	public static int check(Path checkout, Path baselinePath, List<String> shearCommand, PrintStream out,
		CommandRunner git, Path temporaryDirectory) throws IOException
	{
		Objects.requireNonNull(checkout, "checkout");
		Objects.requireNonNull(baselinePath, "baselinePath");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(git, "git");
		Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
		List<String> command = new ArrayList<>(List.copyOf(shearCommand));
		if (command.isEmpty())
			throw new IllegalArgumentException("Cargo Shear requires an executable");
		command.add("--deny-warnings");
		command.add("--format=json");

		String baselineJson;
		try
		{
			baselineJson = Files.readString(baselinePath);
		}
		catch (CharacterCodingException failure)
		{
			throw new IOException("Cargo Shear baseline is not valid UTF-8 at " + baselinePath, failure);
		}
		JsonNode baseline = parse(baselineJson, "baseline");
		JsonNode sourcePaths = baseline.get("source_paths");
		if (sourcePaths == null || !sourcePaths.isArray())
			throw new IOException("Cargo Shear baseline must contain a source_paths array");
		Path root = checkout.toAbsolutePath().normalize();
		List<String> diff = new ArrayList<>(List.of("git", "-C", root.toString(), "diff", "HEAD", "--name-only", "--"));
		for (JsonNode path : sourcePaths)
		{
			if (!path.isString())
				throw new IOException("Cargo Shear baseline source_paths must contain strings");
			diff.add(path.stringValue());
		}
		String sourceSha = git.run(List.of("git", "-C", root.toString(), "rev-parse", "HEAD")).strip();
		boolean sourcesChanged = !git.run(List.copyOf(diff)).isEmpty();
		SystemCommands.Result result = SystemCommands.capture(command, root.resolve("codex-rs"), temporaryDirectory);
		out.print(result.stderr());
		out.print(result.stdout());
		if (result.status() != 0 && result.status() != 1)
			return result.status();
		if (!CargoShearPolicy.accepts(result.stdout(), baselineJson, sourceSha, sourcesChanged))
			return 1;
		if (!parse(result.stdout(), "report").get("findings").isEmpty())
			out.println("Only verified, unchanged upstream release warnings remain.");
		return 0;
	}

	/**
	 * Reads a JSON object needed for command orchestration.
	 *
	 * @param json the serialized object
	 * @param label the diagnostic input label
	 * @return the parsed object
	 * @throws IOException if the JSON is malformed or is not an object
	 */
	private static JsonNode parse(String json, String label) throws IOException
	{
		try
		{
			JsonNode value = JsonMapper.builder().build().readTree(json);
			if (value == null || !value.isObject())
				throw new IOException("Cargo Shear " + label + " must be a JSON object");
			return value;
		}
		catch (JacksonException failure)
		{
			throw new IOException("Cannot parse Cargo Shear " + label + ": " + failure.getMessage(), failure);
		}
	}
}
