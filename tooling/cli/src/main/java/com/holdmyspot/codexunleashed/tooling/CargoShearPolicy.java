package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Accepts Cargo Shear warnings only when they match the verified upstream release and unchanged sources.
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
	 * @param baselineJson the verified upstream release baseline
	 * @param sourceSha the checked-out source commit
	 * @param sourcesChanged indicates whether the baseline's source paths differ from the checked-out commit
	 * @return true if the report is clean or contains only verified, unchanged release warnings
	 * @throws NullPointerException if {@code reportJson}, {@code baselineJson}, or {@code sourceSha} are null
	 * @throws IOException if a report or baseline field is malformed
	 */
	public static boolean accepts(String reportJson, String baselineJson, String sourceSha, boolean sourcesChanged)
		throws IOException
	{
		Objects.requireNonNull(reportJson, "reportJson");
		Objects.requireNonNull(baselineJson, "baselineJson");
		Objects.requireNonNull(sourceSha, "sourceSha");
		JsonNode report = parse(reportJson, "report");
		JsonNode baseline = parse(baselineJson, "baseline");
		JsonNode summary = report.get("summary");
		if (summary == null || !summary.isObject())
			throw new IOException("Cargo Shear report must contain a summary object");
		if (!isZeroCount(summary, "errors"))
			return false;
		JsonNode findings = report.get("findings");
		if (findings == null || !findings.isArray())
			throw new IOException("Cargo Shear report must contain a findings array");
		if (findings.isEmpty())
			return isZeroCount(summary, "warnings");

		JsonNode baselineSha = baseline.get("source_sha");
		if (baselineSha == null || !baselineSha.isString())
			throw new IOException("Cargo Shear baseline must contain a source_sha string");
		if (sourcesChanged || !sourceSha.equals(baselineSha.stringValue()))
			return false;
		JsonNode baselineFindings = baseline.get("findings");
		if (baselineFindings == null || !baselineFindings.isArray())
			throw new IOException("Cargo Shear baseline must contain a findings array");
		List<JsonNode> known = new ArrayList<>();
		for (JsonNode finding : baselineFindings)
			known.add(finding);
		for (JsonNode finding : findings)
		{
			JsonNode severity = finding.get("severity");
			if (severity == null || !severity.isString() || !"warning".equals(severity.stringValue()) ||
				!known.contains(finding))
				return false;
		}
		return true;
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
