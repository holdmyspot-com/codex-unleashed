package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

/**
 * Verifies unchanged V8 script extraction through the public tooling command.
 */
public final class UpstreamV8ScriptTest
{
	/**
	 * Creates command tests.
	 */
	public UpstreamV8ScriptTest()
	{
	}

	/**
	 * Accepts equivalent YAML forms while preserving shell quoting and trailing newlines.
	 */
	@Test
	public void acceptsEquivalentRepresentations()
	{
		String block = "runs:\n  using: composite\n  steps:\n    - shell: bash\n      run: |\n        echo ready\n";
		String flow = "{runs: {using: composite, steps: [{shell: bash, run: \"echo ready\\n\"}]}}";
		assertEquals(UpstreamV8Script.extract(block), "echo ready\n");
		assertEquals(UpstreamV8Script.extract(flow), "echo ready\n");
	}

	/**
	 * Supplies malformed documents and execution semantics the child Bash invocation cannot reproduce.
	 *
	 * @return unsupported action documents
	 */
	@DataProvider
	public Object[][] unsupportedActions()
	{
		return new Object[][]
		{
			{"runs: ["},
			{""},
			{"null"},
			{"runs: {using: node24, steps: [{shell: bash, run: echo}]}"},
			{"runs: {using: composite, steps: []}"},
			{"runs: {using: composite, steps: [{shell: bash, run: echo}, {shell: bash, run: echo}]}"},
			{"runs: {using: composite, steps: [{shell: pwsh, run: echo}]}"},
			{"runs: {using: composite, steps: [{shell: bash}]}"},
			{"runs: {using: composite, steps: [{shell: bash, run: null}]}"},
			{"runs: {using: composite, steps: [{shell: bash, run: true}]}"},
			{"runs: {using: composite, steps: [{shell: bash, run: echo, if: false}]}"},
			{"runs: {using: composite, steps: [{shell: bash, run: echo, working-directory: elsewhere}]}"},
			{"runs: {using: composite, steps: [{shell: bash, run: echo, env: {TARGET: other}}]}"}
		};
	}

	/**
	 * Refuses unsupported actions before a caller can execute a partial interpretation.
	 *
	 * @param action an unsupported action document
	 */
	@Test(dataProvider = "unsupportedActions", expectedExceptions = IllegalArgumentException.class)
	public void rejectsUnsupportedActions(String action)
	{
		UpstreamV8Script.extract(action);
	}

	/**
	 * Preserves upstream's target input binding while the caller supplies the selected target.
	 */
	@Test
	public void acceptsUpstreamTargetBinding()
	{
		assertEquals(UpstreamV8Script.extract("runs: {using: composite, steps: [{shell: bash, " +
			"env: {TARGET: '${{ inputs.target }}'}, run: echo}]}"), "echo");
	}

	/**
	 * Preserves the decoded Bash script exactly, including its final newline.
	 *
	 * @throws Exception if fixture creation or removal fails
	 */
	@Test
	public void extractsScriptThroughCommand() throws Exception
	{
		Path action = Files.createTempFile("v8-action-", ".yml");
		try
		{
			Files.writeString(action, "runs: {using: composite, steps: [{shell: bash, run: \"echo ready\\n\"}]}");
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8))
			{
				int status = Main.run(new String[]{"upstream-v8-script", action.toString()},
					InputStream.nullInputStream(), out, err);
				assertEquals(status, 0, diagnostics.toString(StandardCharsets.UTF_8));
				assertEquals(output.toString(StandardCharsets.UTF_8), "echo ready\n");
			}
		}
		finally
		{
			Files.deleteIfExists(action);
		}
	}
}
