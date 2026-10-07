package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Requires explicit success from every dependency of a terminal CI job.
 */
public final class CiResults
{
	/**
	 * Prevents construction.
	 */
	private CiResults()
	{
	}

	/**
	 * Validates the serialized GitHub needs object and reports unsuccessful dependencies.
	 *
	 * @param inventory the serialized dependency results
	 * @param out the result destination
	 * @return zero if all dependencies succeed, or one if any dependency does not succeed
	 * @throws NullPointerException if {@code inventory} or {@code out} are null
	 * @throws IOException if the inventory is malformed or a dependency lacks a string result
	 */
	public static int check(String inventory, PrintStream out) throws IOException
	{
		Objects.requireNonNull(inventory, "inventory");
		Objects.requireNonNull(out, "out");
		Map<String, String> failures = new TreeMap<>();
		try
		{
			JsonNode needs = JsonMapper.builder().build().readTree(inventory);
			if (!needs.isObject())
				throw new IOException("CI needs must be a JSON object");
			for (Map.Entry<String, JsonNode> dependency : needs.properties())
			{
				JsonNode result = dependency.getValue().get("result");
				if (result == null || !result.isString())
					throw new IOException("CI dependency " + dependency.getKey() + " requires a string result");
				if (!"success".equals(result.asString()))
					failures.put(dependency.getKey(), result.asString());
			}
		}
		catch (JacksonException failure)
		{
			throw new IOException("Invalid JSON in CI dependency results: " + failure.getMessage(), failure);
		}

		if (failures.isEmpty())
		{
			out.println("All CI dependencies succeeded.");
			return 0;
		}
		out.println("CI dependencies did not succeed:");
		for (Map.Entry<String, String> failure : failures.entrySet())
			out.println(failure.getKey() + ": " + failure.getValue());
		return 1;
	}
}
