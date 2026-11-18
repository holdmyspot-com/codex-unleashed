package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.DirectDependencyLints;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Checks the Java command's caller and compiler boundaries. */
public final class DirectDependencyLintsBoundaryTest
{
	/** Creates compiler and stream boundary checks. */
	public DirectDependencyLintsBoundaryTest()
	{
	}

	/**
	 * Preserves the compiler command vector, working directory, explicit environment and ordinary child status.
	 *
	 * @throws IOException if the listing fixture cannot be created
	 */
	@Test
	public void compilerBoundaryKeepsInvocation() throws IOException
	{
		Path current = Path.of("").toAbsolutePath();
		Path listing = Files.createTempFile("direct-dependency-list-", ".txt");
		List<String> captured = new ArrayList<>();
		Map<String, String> environment = Map.of("CODEX_DIRECT_DEPENDENCIES", listing.toString(),
			"CARGO_PKG_NAME", "probe");
		DirectDependencyLints.Compiler compiler = (command, workingDirectory, childEnvironment) ->
		{
			captured.addAll(command);
			requireThat(workingDirectory, "workingDirectory").isEqualTo(current);
			requireThat(childEnvironment, "environment").isEqualTo(environment);
			return 19;
		};
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			String[] arguments = {"rustc with spaces", "--crate-name", "literal value", "-vV"};
			PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
			int status = DirectDependencyLints.run(scope, arguments, new ByteArrayInputStream(new byte[0]),
				environment, out, err, compiler);

			requireThat(status, "childStatus").isEqualTo(19);
			requireThat(captured, "literalCommand").isEqualTo(List.of("rustc with spaces", "--crate-name",
				"literal value", "-vV"));
		}
		finally
		{
			Files.deleteIfExists(listing);
		}
	}

	/**
	 * Keeps the caller's metadata stream open after consuming it in list mode.
	 */
	@Test
	public void listModeLeavesCallerInputOpen()
	{
		String metadata = "{\"workspace_members\":[],\"packages\":[],\"resolve\":{\"nodes\":[]}}";
		ByteArrayInputStream input = new ByteArrayInputStream(metadata.getBytes(StandardCharsets.UTF_8));
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (DefaultToolingScope scope = new DefaultToolingScope();
			PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			int status = DirectDependencyLints.run(scope, new String[]{"list"}, input, Map.of(), out,
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
			requireThat(status, "status").isEqualTo(0);
			requireThat(input.available(), "remainingInput").isEqualTo(0);
			requireThat(output.toString(StandardCharsets.UTF_8), "output").isEmpty();
		}
	}
}
