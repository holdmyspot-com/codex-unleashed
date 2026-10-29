package com.holdmyspot.codexunleashed.tooling;

import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Extracts the unchanged Bash script from upstream's single-step V8 setup action.
 * This operation is safe for concurrent calls and does not execute the script.
 */
public final class UpstreamV8Script
{
	/**
	 * Parses YAML without replacing upstream's script or checksum verification.
	 */
	private static final YAMLMapper YAML = YAMLMapper.builder().build();

	/**
	 * Prevents construction.
	 */
	private UpstreamV8Script()
	{
	}

	/**
	 * Returns a composite action's sole Bash script without changing its bytes after YAML decoding.
	 * The caller supplies TARGET and the child process's workspace, temporary directory and environment file.
	 * Only an absent step environment or upstream's TARGET input binding is supported; other step semantics
	 * must be integrated explicitly before execution.
	 *
	 * @param action the YAML action document
	 * @return the decoded Bash script
	 * @throws NullPointerException if action is null
	 * @throws IllegalArgumentException if YAML is malformed or the action has unsupported execution semantics
	 */
	public static String extract(String action)
	{
		Objects.requireNonNull(action, "action");
		JsonNode document;
		try
		{
			document = YAML.readTree(action);
		}
		catch (JacksonException failure)
		{
			throw new IllegalArgumentException("Cannot parse upstream V8 action YAML", failure);
		}
		JsonNode using = document.at("/runs/using");
		boolean compositeAction = using.isString() && "composite".equals(using.stringValue());
		if (!compositeAction)
			throw new IllegalArgumentException("Upstream V8 setup must be a composite action");
		JsonNode steps = document.at("/runs/steps");
		boolean singleStep = steps.isArray() && steps.size() == 1;
		if (!singleStep)
			throw new IllegalArgumentException("Upstream V8 setup must contain exactly one step");
		JsonNode step = steps.get(0);
		JsonNode shell = step.path("shell");
		boolean bashStep = shell.isString() && "bash".equals(shell.stringValue());
		if (!bashStep)
			throw new IllegalArgumentException("Upstream V8 setup step must use Bash");
		for (String property : step.propertyNames())
		{
			boolean supportedProperty = switch (property)
			{
				case "name", "shell", "env", "run" -> true;
				default -> false;
			};
			if (!supportedProperty)
				throw new IllegalArgumentException("Unsupported upstream V8 step property: " + property);
		}
		JsonNode environment = step.path("env");
		JsonNode target = environment.path("TARGET");
		boolean targetBinding = environment.isObject() && environment.size() == 1 && target.isString() &&
			"${{ inputs.target }}".equals(target.stringValue());
		boolean supportedEnvironment = environment.isMissingNode() || targetBinding;
		if (!supportedEnvironment)
			throw new IllegalArgumentException("Unsupported upstream V8 step environment");
		JsonNode script = step.path("run");
		if (!script.isString())
			throw new IllegalArgumentException("Upstream V8 setup must contain a string run script");
		return script.stringValue();
	}
}
