package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Requires Cargo Shear reports to contain no errors, warnings, or findings.
 */
public final class CargoShearPolicy
{
	/**
	 * Prevents construction.
	 */
	private CargoShearPolicy()
	{
	}

	/**
	 * Determines whether a Cargo Shear report satisfies the release warning policy.
	 *
	 * @param reportJson the Cargo Shear JSON report
	 * @return true if the report is clean
	 * @throws NullPointerException if {@code reportJson} is null
	 * @throws IOException if a report field is malformed
	 */
	public static boolean accepts(String reportJson)
		throws IOException
	{
		Objects.requireNonNull(reportJson, "reportJson");
		JsonNode report = parse(reportJson, "report");
		JsonNode summary = report.get("summary");
		if (summary == null || !summary.isObject())
			throw new IOException("Cargo Shear report must contain a summary object");
		if (!isZeroCount(summary, "errors"))
			return false;
		JsonNode findings = report.get("findings");
		if (findings == null || !findings.isArray())
			throw new IOException("Cargo Shear report must contain a findings array");
		return isZeroCount(summary, "warnings") && findings.isEmpty();
	}

	/**
	 * Parses one JSON object without replacing malformed input with a default.
	 *
	 * @param json the serialized object
	 * @param label the diagnostic input label
	 * @return the object tree
	 * @throws IOException if the input is malformed or is not an object
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

	/**
	 * Determines whether a required nonnegative integral diagnostic count is zero.
	 *
	 * @param summary the report summary
	 * @param name the count field name
	 * @return true if the count is zero
	 * @throws IOException if the count is absent or malformed
	 */
	private static boolean isZeroCount(JsonNode summary, String name) throws IOException
	{
		JsonNode count = summary.get(name);
		if (count == null || !count.isIntegralNumber() || count.bigIntegerValue().signum() < 0)
			throw new IOException("Cargo Shear summary " + name + " must be a nonnegative integer");
		return count.bigIntegerValue().signum() == 0;
	}
}
