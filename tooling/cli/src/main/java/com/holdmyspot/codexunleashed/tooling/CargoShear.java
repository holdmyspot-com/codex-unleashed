package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs Cargo Shear and requires a report without warnings or errors.
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
	 * @param shearCommand the Cargo Shear executable and any launcher arguments
	 * @param out the destination for tool diagnostics and reports
	 * @param temporaryDirectory the existing directory for owned process-capture files
	 * @return zero on acceptance, one on rejected findings, or the tool's other failure status
	 * @throws NullPointerException if any argument or command element is null
	 * @throws IllegalArgumentException if {@code shearCommand} is empty
	 * @throws IOException if the process capture or report is invalid
	 */
	public static int check(Path checkout, List<String> shearCommand, PrintStream out,
		Path temporaryDirectory) throws IOException
	{
		Objects.requireNonNull(checkout, "checkout");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
		List<String> command = new ArrayList<>(List.copyOf(shearCommand));
		if (command.isEmpty())
			throw new IllegalArgumentException("Cargo Shear requires an executable");
		command.add("--deny-warnings");
		command.add("--format=json");

		Path root = checkout.toAbsolutePath().normalize();
		SystemCommands.Result result = SystemCommands.capture(command, root.resolve("codex-rs"), temporaryDirectory);
		out.print(result.stderr());
		out.print(result.stdout());
		if (result.status() != 0 && result.status() != 1)
			return result.status();
		if (result.status() == 1 || !CargoShearPolicy.accepts(result.stdout()))
			return 1;
		return 0;
	}
}
