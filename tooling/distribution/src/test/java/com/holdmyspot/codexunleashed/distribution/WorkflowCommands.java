package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Reads maintained workflow commands for execution at their actual shell boundary.
 */
final class WorkflowCommands
{
	/**
	 * Prevents construction.
	 */
	private WorkflowCommands()
	{
	}

	/**
	 * Reads the unquoted command scalar for a named step in the selected release workflow.
	 *
	 * @param job the workflow job identifier
	 * @param stepName the step's display name
	 * @return the step's shell command
	 * @throws IOException if the workflow cannot be read or the step is missing
	 */
	static String readStepCommand(String job, String stepName) throws IOException
	{
		Path workflow = Path.of(System.getProperty("tooling.release.workflow"));
		return readStepCommand(workflow, job, stepName);
	}

	/**
	 * Reads a named step from an explicitly selected maintained workflow.
	 *
	 * @param workflow the workflow path
	 * @param job the workflow job identifier
	 * @param stepName the step's display name
	 * @return the step's shell command
	 * @throws IOException if the workflow cannot be read or the step is missing
	 */
	static String readStepCommand(Path workflow, String job, String stepName) throws IOException
	{
		JsonNode document = YAMLMapper.builder().build().readTree(Files.readString(workflow));
		JsonNode steps = document.get("jobs").get(job).get("steps");
		for (int index = 0; index < steps.size(); ++index)
		{
			JsonNode step = steps.get(index);
			JsonNode name = step.get("name");
			if (name != null && stepName.equals(name.asString()))
				return step.get("run").asString();
		}
		throw new IOException("Workflow omits step " + stepName + " in job " + job + ": " + workflow);
	}
}
