package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.BazelTestEvidence;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Checks the Bazel test evidence command boundary and its fixed report. */
public final class BazelTestEvidenceTest
{
	/** Sanitized invocation identifier used in fixtures. */
	private static final String UUID = "123e4567-e89b-12d3-a456-426614174000";
	/** Complete passing fixture invocation. */
	private static final String BASE = String.join("\n", List.of(
			event("""
			{
			  "id": {
			    "started": {}
			  },
			  "started": {
			    "uuid": "123e4567-e89b-12d3-a456-426614174000"
			  },
			  "children": [
			    {
			      "structuredCommandLine": {
			        "commandLineLabel": "canonical"
			      }
			    },
			    {
			      "pattern": {
			        "pattern": [
			          "//:pass"
			        ]
			      }
			    },
			    {
			      "buildFinished": {}
			    }
			  ]
			}
			"""),
			event("""
			{
			  "id": {
			    "structuredCommandLine": {
			      "commandLineLabel": "canonical"
			    }
			  },
			  "structuredCommandLine": {
			    "commandLineLabel": "canonical",
			    "sections": [
			      {
			        "sectionLabel": "command",
			        "chunkList": {
			          "chunk": [
			            "test"
			          ]
			        }
			      },
			      {
			        "sectionLabel": "command options",
			        "optionList": {
			          "option": [
			            {
			              "optionName": "test_tag_filters",
			              "optionValue": "-argument-comment-lint"
			            }
			          ]
			        }
			      },
			      {
			        "sectionLabel": "residual",
			        "chunkList": {
			          "chunk": [
			            "//:pass"
			          ]
			        }
			      }
			    ]
			  }
			}
			"""),
			event("""
			{
			  "id": {
			    "pattern": {
			      "pattern": [
			        "//:pass"
			      ]
			    }
			  },
			  "expanded": [
			    "//:pass"
			  ],
			  "children": [
			    {
			      "targetConfigured": {
			        "label": "//:pass"
			      }
			    }
			  ]
			}
			"""),
			event("""
			{
			  "id": {
			    "targetConfigured": {
			      "label": "//:pass"
			    }
			  },
			  "configured": {
			    "targetKind": "cc_test rule",
			    "testSize": "SMALL"
			  },
			  "children": [
			    {
			      "targetCompleted": {
			        "label": "//:pass",
			        "configuration": {
			          "id": "configuration-1"
			        }
			      }
			    }
			  ]
			}
			"""),
			event("""
			{
			  "id": {
			    "targetCompleted": {
			      "label": "//:pass",
			      "configuration": {
			        "id": "configuration-1"
			      }
			    }
			  },
			  "completed": {
			    "success": true
			  },
			  "children": [
			    {
			      "testResult": {
			        "label": "//:pass",
			        "configuration": {
			          "id": "configuration-1"
			        },
			        "run": 1,
			        "shard": 1,
			        "attempt": 1
			      }
			    },
			    {
			      "testSummary": {
			        "label": "//:pass",
			        "configuration": {
			          "id": "configuration-1"
			        }
			      }
			    }
			  ]
			}
			"""),
			event("""
			{
			  "id": {
			    "testResult": {
			      "label": "//:pass",
			      "configuration": {
			        "id": "configuration-1"
			      },
			      "run": 1,
			      "shard": 1,
			      "attempt": 1
			    }
			  },
			  "testResult": {
			    "status": "PASSED"
			  }
			}
			"""),
			event("""
			{
			  "id": {
			    "testSummary": {
			      "label": "//:pass",
			      "configuration": {
			        "id": "configuration-1"
			      }
			    }
			  },
			  "testSummary": {
			    "overallStatus": "PASSED",
			    "runCount": 1,
			    "totalRunCount": 1,
			    "attemptCount": 1,
			    "shardCount": 1,
			    "passed": [
			      {
			        "uri": "file:///sanitized/test.log"
			      }
			    ]
			  }
			}
			"""),
			event("""
			{
			  "id": {
			    "buildFinished": {}
			  },
			  "finished": {
			    "exitCode": {
			      "code": 0,
			      "name": "SUCCESS"
			    }
			  }
			}
			"""))) + "\n";
	/** Creates independent command-boundary checks. */
	public BazelTestEvidenceTest()
	{
	}

	/**
	 * Accepts complete test evidence and preserves ordered fixed-schema output.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void acceptsCompleteEvidence() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			String omittedZero = BASE.replace("\"exitCode\":{\"code\":0,", "\"overallSuccess\":true,\"exitCode\":{");
			Result result = invoke(fixture, omittedZero, "//:pass\n", "0");
			requireThat(result.status, "status").isEqualTo(0);
			requireThat(result.output, "report").
				contains("\"coverage\":\"complete\"").
				contains("\"invocation_uuid\":\"" + UUID + "\"").
				contains("\"completed_test_count\":1");
			requireThat(result.error, "diagnostic").isEmpty();
		}
	}

	/**
	 * Accepts the absent protobuf default shard count when evidence accounts for a single unsharded test.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void acceptsUnshardedSummary() throws IOException
	{
		String unsharded = BASE.replace(",\"shardCount\":1", "");
		try (Fixture fixture = new Fixture())
		{
			Result result = invoke(fixture, unsharded, "//:pass\n", "0");
			requireThat(result.output, "report").contains("\"completed_test_count\":1");
			requireThat(result.status, "status").isEqualTo(0);
		}
	}

	/**
	 * Rejects a string shard count that would otherwise be coerced into an unsharded value.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void rejectsStringShardCount() throws IOException
	{
		String malformed = BASE.replace("\"shardCount\":1", "\"shardCount\":\"0\"");
		try (Fixture fixture = new Fixture())
		{
			Result result = invoke(fixture, malformed, "//:pass\n", "0");
			requireThat(result.output, "report").contains("\"failure_code\":\"incomplete_execution\"");
			requireThat(result.status, "status").isEqualTo(1);
		}
	}

	/**
	 * Rejects a claimed retry that follows an already passing attempt.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void rejectsRetryAfterPass() throws IOException
	{
		String malformed = addRetryAttempt(BASE, "PASSED").replace("\"status\":\"FAILED\"", "\"status\":\"PASSED\"");
		try (Fixture fixture = new Fixture())
		{
			Result result = invoke(fixture, malformed, "//:pass\n", "0");
			requireThat(result.output, "report").contains("\"failure_code\":\"incomplete_execution\"");
			requireThat(result.status, "status").isEqualTo(1);
		}
	}

	/**
	 * Allows repeatable environment and toolchain options while requiring an unambiguous test selection.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void acceptsRepeatedCommandOptions() throws IOException
	{
		String tagFilter = "{\"optionName\":\"test_tag_filters\",\"optionValue\":\"-argument-comment-lint\"}";
		String twoVariables = "{\"optionName\":\"test_env\",\"optionValue\":\"ONE=1\"}," +
			"{\"optionName\":\"test_env\",\"optionValue\":\"TWO=2\"}";
		String twoToolchains = "{\"optionName\":\"extra_toolchains\",\"optionValue\":\"//:toolchain_one\"}," +
			"{\"optionName\":\"extra_toolchains\",\"optionValue\":\"//:toolchain_two\"}";
		String twoStrategies = "{\"optionName\":\"strategy\",\"optionValue\":\"CppCompile=local\"}," +
			"{\"optionName\":\"strategy\",\"optionValue\":\"CppLink=local\"}";
		String invocation = BASE.replace(tagFilter, tagFilter + "," + twoVariables + "," + twoToolchains + "," +
			twoStrategies);
		try (Fixture fixture = new Fixture())
		{
			Result result = invoke(fixture, invocation, "//:pass\n", "0");
			requireThat(result.output, "report").contains("\"coverage\":\"complete\"");
			requireThat(result.status, "status").isEqualTo(0);
		}
	}

	/**
	 * Accepts the repeated toolchain-feature and repository-injection options of the upstream {@code .bazelrc} and
	 * Windows CI invocation in their canonical command-line form.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void acceptsRepeatedFeatureAndRepositoryOptions() throws IOException
	{
		String tagFilter = "{\"optionName\":\"test_tag_filters\",\"optionValue\":\"-argument-comment-lint\"}";
		StringBuilder repeated = new StringBuilder();
		for (String name : List.of("features", "host_features"))
			for (String value : List.of("-dynamic_link_msvcrt", "static_link_msvcrt"))
				repeated.append(",{\"optionName\":\"").append(name).append("\",\"optionValue\":\"").append(value).
					append("\",\"combinedForm\":\"--").append(name).append('=').append(value).append("\"}");
		String injection = ",{\"optionName\":\"inject_repository\"," +
			"\"optionValue\":\"voice_windows_tools=D:\\\\a\\\\_temp\\\\voice-windows-build-tools\"}";
		repeated.append(injection).append(injection);
		String invocation = BASE.replace(tagFilter, tagFilter + repeated);
		try (Fixture fixture = new Fixture())
		{
			Result result = invoke(fixture, invocation, "//:pass\n", "0");
			requireThat(result.output, "report").contains("\"coverage\":\"complete\"");
			requireThat(result.status, "status").isEqualTo(0);
		}
	}

	/**
	 * Rejects a repeated selection option whose second value the profile check would not read.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void rejectsRepeatedSelectionOption() throws IOException
	{
		String tagFilter = "{\"optionName\":\"test_tag_filters\",\"optionValue\":\"-argument-comment-lint\"}";
		String invocation = BASE.replace(tagFilter, tagFilter + "," +
			"{\"optionName\":\"test_tag_filters\",\"optionValue\":\"-manual\"}");
		try (Fixture fixture = new Fixture())
		{
			Result result = invoke(fixture, invocation, "//:pass\n", "0");
			requireThat(result.status, "status").isEqualTo(1);
			requireThat(result.output, "report").contains("\"failure_code\":\"unsupported_selection\"");
		}
	}

	/**
	 * Counts a repeated, identical fetch event once in the evidence graph.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void acceptsIdenticalFetchRepetition() throws IOException
	{
		String fetch = event("""
			{"id":{"fetch":{"url":"https://example.com/config.json","downloader":"HTTP"}},
			 "fetch":{"success":true}}
			""");
		String invocation = BASE.replaceFirst("\"children\":\\[",
			"\"children\":[{\"fetch\":{\"url\":\"https://example.com/config.json\",\"downloader\":\"HTTP\"}},") +
			fetch + "\n" + fetch + "\n";
		try (Fixture fixture = new Fixture())
		{
			Result result = invoke(fixture, invocation, "//:pass\n", "0");
			requireThat(result.status, "status").isEqualTo(0);
			requireThat(result.output, "report").contains("\"coverage\":\"complete\"");
		}
	}

	/**
	 * Keeps completed failing tests in complete coverage for baseline comparison.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void preservesCompletedFailure() throws IOException
	{
		String failed = BASE.
			replace("\"status\":\"PASSED\"", "\"status\":\"FAILED\"").
			replace("\"overallStatus\":\"PASSED\"", "\"overallStatus\":\"FAILED\"").
			replace("\"code\":0", "\"code\":3");
		try (Fixture fixture = new Fixture())
		{
			Result result = invoke(fixture, failed, "//:pass\n", "3");
			requireThat(result.status, "status").isEqualTo(0);
			requireThat(result.output, "report").
				contains("\"coverage\":\"complete\"").
				contains("\"nonpassing_test_count\":1");
		}
	}

	/**
	 * Keeps a terminal shard failure visible when another shard of the same test passed.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void preservesTerminalShardFailure() throws IOException
	{
		String firstShard = testResultId("//:pass", "configuration-1", 1, 1, 1);
		String secondShard = testResultId("//:pass", "configuration-1", 1, 2, 1);
		String firstResult = "{\"id\":" + firstShard + ",\"testResult\":{\"status\":\"PASSED\"}}";
		String secondResult = "{\"id\":" + secondShard + ",\"testResult\":{\"status\":\"FAILED\"}}";
		String failed = BASE.replace("\"children\":[" + firstShard,
			"\"children\":[" + firstShard + "," + secondShard).
			replace(firstResult, firstResult + "\n" + secondResult).
			replace("\"shardCount\":1", "\"shardCount\":2").
			replace("\"totalRunCount\":1", "\"totalRunCount\":2").
			replace("\"overallStatus\":\"PASSED\"", "\"overallStatus\":\"FAILED\"").
			replace("\"code\":0", "\"code\":3");
		try (Fixture fixture = new Fixture())
		{
			Result result = invoke(fixture, failed, "//:pass\n", "3");
			requireThat(result.output, "report").
				contains("\"coverage\":\"complete\"").
				contains("\"nonpassing_test_count\":1");
			requireThat(result.status, "status").isEqualTo(0);
		}
	}

	/**
	 * Treats explicit controller interruption as authoritative rejection evidence.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void rejectsInterruptedExecution() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Result result = invoke(fixture, BASE, "//:pass\n", "0", "--interrupted");
			requireThat(result.status, "status").isEqualTo(1);
			requireThat(result.output, "report").contains("\"failure_code\":\"interrupted_execution\"");
		}
	}

	/**
	 * Rejects target-pattern binding that differs from canonical invocation arguments.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void rejectsMismatchedInvocationPatterns() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Result result = invoke(fixture, BASE, "//:other\n", "0");
			requireThat(result.status, "status").isEqualTo(1);
			requireThat(result.output, "report").contains("\"failure_code\":\"selection_mismatch\"");
		}
	}

	/**
	 * Rejects missing test-result scheduling children instead of treating a closed target graph as coverage.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void rejectsMissingSchedulingChildren() throws IOException
	{
		String incomplete = removeSchedulingChildren(BASE);
		try (Fixture fixture = new Fixture())
		{
			Result result = invoke(fixture, incomplete, "//:pass\n", "0");
			requireThat(result.status, "status").isEqualTo(1);
			requireThat(result.output, "report").contains("\"failure_code\":\"incomplete_graph\"");
		}
	}

	/**
	 * Rejects a configured manual test when the invocation graph explicitly selected it but omitted its execution.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void requiresExplicitManualTestExecution() throws IOException
	{
		String manualWithoutExecution = addConfiguredTest(BASE, "//:manual", "manual");
		try (Fixture fixture = new Fixture())
		{
			Result result = invoke(fixture, manualWithoutExecution, "//:pass\n//:manual\n", "0");
			requireThat(result.status, "status").isEqualTo(1);
			requireThat(result.output, "report").contains("\"failure_code\":\"incomplete_graph\"");
		}
	}

	/**
	 * Leaves a manual target absent from wildcard expansion outside the expected test set.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void acceptsWildcardManualOmission() throws IOException
	{
		String wildcard = BASE.
			replace("\"pattern\":[\"//:pass\"]", "\"pattern\":[\"//...\"]").
			replace("\"chunk\":[\"//:pass\"]", "\"chunk\":[\"//...\"]");
		try (Fixture fixture = new Fixture())
		{
			Result result = invoke(fixture, wildcard, "//...\n", "0");
			requireThat(result.status, "status").isEqualTo(0);
			requireThat(result.output, "report").
				contains("\"expected_test_count\":1").
				contains("\"completed_test_count\":1");
		}
	}

	/**
	 * Excludes a test carrying the exact supported tag while retaining another selected test's coverage.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void acceptsExactTagExclusion() throws IOException
	{
		String excluded = addConfiguredTest(BASE, "//:excluded", "argument-comment-lint");
		try (Fixture fixture = new Fixture())
		{
			Result result = invoke(fixture, excluded, "//:pass\n//:excluded\n", "0");
			requireThat(result.status, "status").isEqualTo(0);
			requireThat(result.output, "report").
				contains("\"expected_test_count\":1").
				contains("\"filtered_test_count\":1");
		}
	}

	/**
	 * Counts an explicitly reported incompatible target as filtered while requiring other selected tests to run.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void acceptsIncompatibleTargetSkip() throws IOException
	{
		String selected = addConfiguredTest(BASE, "//:cross", "no-sandbox");
		String configured = event("""
			{"id":{"targetConfigured":{"label":"//:cross"}},"configured":{"targetKind":"cc_test rule",
			 "testSize":"SMALL","tag":["no-sandbox"]}}
			""");
		String completedId = event("""
			{"targetCompleted":{"label":"//:cross","configuration":{"id":"cfg"}}}
			""");
		String completion = "{\"id\":" + completedId + ",\"aborted\":{\"reason\":\"SKIPPED\"," +
			"\"description\":\"Target //:cross build was skipped.\"}}";
		String invocation = selected.replace(configured, configured.substring(0, configured.length() - 1) +
			",\"children\":[" + completedId + "]}\n" + completion);
		String incompatibleOption = "{\"optionName\":\"skip_incompatible_explicit_targets\",\"optionValue\":\"1\"},";
		String enabled = invocation.replace("{\"optionName\":\"test_tag_filters\"",
			incompatibleOption + "{\"optionName\":\"test_tag_filters\"");
		try (Fixture fixture = new Fixture())
		{
			Result result = invoke(fixture, enabled, "//:pass\n//:cross\n", "0");
			requireThat(result.output, "report").
				contains("\"coverage\":\"complete\"").
				contains("\"completed_test_count\":1").
				contains("\"filtered_test_count\":1");
			requireThat(result.status, "status").isEqualTo(0);
		}
		try (Fixture fixture = new Fixture())
		{
			Result result = invoke(fixture, invocation, "//:pass\n//:cross\n", "0");
			requireThat(result.status, "status").isEqualTo(1);
			requireThat(result.output, "report").contains("\"failure_code\":\"incomplete_graph\"");
		}
	}

	/**
	 * Counts Bazel's compatibility skip for a target selected by a wildcard.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void acceptsWildcardIncompatibleTargetSkip() throws IOException
	{
		String selected = addConfiguredTest(BASE, "//:cross", "no-sandbox");
		String configured = event("""
			{"id":{"targetConfigured":{"label":"//:cross"}},"configured":{"targetKind":"cc_test rule",
			 "testSize":"SMALL","tag":["no-sandbox"]}}
			""");
		String completedId = event("""
			{"targetCompleted":{"label":"//:cross","configuration":{"id":"cfg"}}}
			""");
		String completion = "{\"id\":" + completedId + ",\"aborted\":{\"reason\":\"SKIPPED\"," +
			"\"description\":\"Target //:cross build was skipped.\"}}";
		String invocation = selected.replace(configured, configured.substring(0, configured.length() - 1) +
			",\"children\":[" + completedId + "]}\n" + completion).
			replace("\"pattern\":[\"//:pass\",\"//:cross\"]", "\"pattern\":[\"//...\"]").
			replace("\"chunk\":[\"//:pass\",\"//:cross\"]", "\"chunk\":[\"//...\"]");
		try (Fixture fixture = new Fixture())
		{
			Result result = invoke(fixture, invocation, "//...\n", "0");
			requireThat(result.output, "report").
				contains("\"completed_test_count\":1").
				contains("\"filtered_test_count\":1");
			requireThat(result.status, "status").isEqualTo(0);
		}
	}

	/**
	 * Requires exact tag membership so a similar but different tag cannot suppress execution evidence.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void rejectsNonExactTagExclusion() throws IOException
	{
		String notExcluded = addConfiguredTest(BASE, "//:similar-tag", "prefix-argument-comment-lint");
		try (Fixture fixture = new Fixture())
		{
			Result result = invoke(fixture, notExcluded, "//:pass\n//:similar-tag\n", "0");
			requireThat(result.status, "status").isEqualTo(1);
			requireThat(result.output, "report").contains("\"failure_code\":\"incomplete_graph\"");
		}
	}

	/**
	 * Counts a timed-out attempt when Bazel announces and records its retry.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void preservesTimeoutFollowedByRetry() throws IOException
	{
		String retry = addRetryAttempt(BASE, "PASSED").replace("\"status\":\"FAILED\"", "\"status\":\"TIMEOUT\"");
		try (Fixture fixture = new Fixture())
		{
			Result result = invoke(fixture, retry, "//:pass\n", "0");
			requireThat(result.status, "status").isEqualTo(0);
			requireThat(result.output, "report").
				contains("\"completed_test_count\":1").
				contains("\"nonpassing_test_count\":1");
		}
	}

	/**
	 * Rejects a terminal timeout reported as an ordinary failure.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void rejectsInconsistentRetryCoverage() throws IOException
	{
		String retry = addRetryAttempt(BASE);
		try (Fixture fixture = new Fixture())
		{
			Result result = invoke(fixture, retry, "//:pass\n", "0");
			requireThat(result.status, "status").isEqualTo(1);
			requireThat(result.output, "report").contains("\"failure_code\":\"incomplete_execution\"");
		}
	}

	/**
	 * Rejects a result event whose label differs from the selected test's identity.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void rejectsMismatchedResultIdentity() throws IOException
	{
		List<String> mismatchedIds = List.of(
			testResultId("//:other", "configuration-1", 1, 1, 1),
			testResultId("//:pass", "configuration-other", 1, 1, 1),
			testResultId("//:pass", "configuration-1", 2, 1, 1),
			testResultId("//:pass", "configuration-1", 1, 2, 1),
			testResultId("//:pass", "configuration-1", 1, 1, 2));
		int identityCase = 0;
		for (String mismatchedId : mismatchedIds)
		{
			identityCase += 1;
			String mismatched = BASE.replace(testResultId("//:pass", "configuration-1", 1, 1, 1), mismatchedId);
			try (Fixture fixture = new Fixture())
			{
				Result result = invoke(fixture, mismatched, "//:pass\n", "0");
				requireThat(result.status, "statusForCase" + identityCase).isEqualTo(1);
				requireThat(result.output, "report").contains("\"failure_code\":\"incomplete_execution\"");
			}
		}
	}

	/**
	 * Rejects a summary event whose label differs from the selected test's identity.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void rejectsMismatchedSummaryIdentity() throws IOException
	{
		List<String> mismatchedIds = List.of(
			testSummaryId("//:other", "configuration-1"),
			testSummaryId("//:pass", "configuration-other"));
		for (String mismatchedId : mismatchedIds)
		{
			String mismatched = BASE.replace(testSummaryId("//:pass", "configuration-1"), mismatchedId);
			try (Fixture fixture = new Fixture())
			{
				Result result = invoke(fixture, mismatched, "//:pass\n", "0");
				requireThat(result.status, "status").isEqualTo(1);
				requireThat(result.output, "report").contains("\"failure_code\":\"incomplete_execution\"");
			}
		}
	}

	/**
	 * Accepts a valid retry chain and reports its aggregate flaky result as complete nonpassing coverage.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void acceptsConsistentRetryCoverage() throws IOException
	{
		String retry = addRetryAttempt(BASE, "PASSED");
		try (Fixture fixture = new Fixture())
		{
			Result result = invoke(fixture, retry, "//:pass\n", "0");
			requireThat(result.status, "status").isEqualTo(0);
			requireThat(result.output, "report").
				contains("\"coverage\":\"complete\"").
				contains("\"nonpassing_test_count\":1");
		}
	}

	/**
	 * Rejects an unsupported canonical selection filter.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void rejectsUnsupportedCanonicalFilter() throws IOException
	{
		String unsupported = BASE.replace("-argument-comment-lint", "-manual");
		try (Fixture fixture = new Fixture())
		{
			Result result = invoke(fixture, unsupported, "//:pass\n", "0");
			requireThat(result.status, "status").isEqualTo(1);
			requireThat(result.output, "report").contains("\"failure_code\":\"unsupported_selection\"");
		}
	}

	/** Help and invalid syntax avoid reading evidence and keep stdout empty on invalid arguments. */
	@Test
	public void handlesHelpAndInvalidArguments()
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		try (DefaultToolingScope scope = new DefaultToolingScope();
			PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(error, true, StandardCharsets.UTF_8))
		{
			int help = BazelTestEvidence.run(scope, new String[]{"--help"}, out, err);
			requireThat(help, "helpStatus").isEqualTo(0);
			requireThat(output.toString(StandardCharsets.UTF_8), "help").contains("--bep PATH");
			output.reset();
			int invalid = BazelTestEvidence.run(scope, new String[]{"--bep"}, out, err);
			requireThat(invalid, "invalidStatus").isEqualTo(2);
			requireThat(output.size(), "invalidStdout").isEqualTo(0);
			requireThat(error.toString(StandardCharsets.UTF_8), "invalidUsage").contains("Usage:");
			requireThat(scope.getJsonMapper(), "borrowedScope").isNotNull();
		}
	}

	/**
	 * Reports readable bounded input failure output for a missing BEP input.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void rejectsMissingInput() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path patterns = fixture.write("patterns.txt", "//:pass\n");
			Result result = invoke(new String[]{"--bep", fixture.root.resolve("missing.bep").toString(),
				"--expected-patterns", patterns.toString(), "--bazel-exit-code", "0"});
			requireThat(result.status, "status").isEqualTo(1);
			requireThat(result.output, "report").contains("\"failure_code\":\"input_error\"");
		}
	}

	/**
	 * Verifies the supplied original process status matches the terminal BEP event.
	 *
	 * @throws IOException if the fixture files cannot be created or removed
	 */
	@Test
	public void rejectsProcessStatusMismatch() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Result result = invoke(fixture, BASE, "//:pass\n", "3");
			requireThat(result.status, "status").isEqualTo(1);
			requireThat(result.output, "report").contains("\"failure_code\":\"command_failed\"");
		}
	}

	/**
	 * Writes an evidence fixture and invokes the command.
	 *
	 * @param fixture isolated filesystem owner
	 * @param evidence BEP JSONL content
	 * @param patterns expected-pattern content
	 * @param exitCode original process status
	 * @param extra optional trailing flags
	 * @return command status and output streams
	 * @throws IOException if fixture input cannot be written
	 */
	private static Result invoke(Fixture fixture, String evidence, String patterns, String exitCode, String... extra)
		throws IOException
	{
		Path bep = fixture.write("evidence.bep.json", evidence);
		Path expected = fixture.write("patterns.txt", patterns);
		return invoke(arguments(bep, expected, exitCode, extra));
	}

	/**
	 * Builds the exact validator argument sequence.
	 *
	 * @param bep BEP path
	 * @param expected expected-pattern path
	 * @param exitCode original process status
	 * @param extra optional trailing flags
	 * @return CLI arguments
	 */
	private static String[] arguments(Path bep, Path expected, String exitCode, String... extra)
	{
		String[] arguments = new String[6 + extra.length];
		arguments[0] = "--bep";
		arguments[1] = bep.toString();
		arguments[2] = "--expected-patterns";
		arguments[3] = expected.toString();
		arguments[4] = "--bazel-exit-code";
		arguments[5] = exitCode;
		System.arraycopy(extra, 0, arguments, 6, extra.length);
		return arguments;
	}

	/**
	 * Runs the public command API with borrowed output streams.
	 *
	 * @param arguments CLI arguments
	 * @return status, stdout and stderr text
	 * @throws IOException if the scope cannot be closed
	 */
	private static Result invoke(String[] arguments) throws IOException
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		try (DefaultToolingScope scope = new DefaultToolingScope();
			PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(error, true, StandardCharsets.UTF_8))
		{
			int status = BazelTestEvidence.run(scope, arguments, out, err);
			return new Result(status, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
		}
	}

	/**
	 * Removes all target-completion scheduling children from the fixture.
	 *
	 * @param evidence complete invocation fixture
	 * @return invocation without selected-test scheduling edges
	 */
	private static String removeSchedulingChildren(String evidence)
	{
		return evidence.replace(
			"\"children\":[{\"testResult\":{\"label\":\"//:pass\",\"configuration\":{" +
				"\"id\":\"configuration-1\"},\"run\":1,\"shard\":1,\"attempt\":1}}," +
				"{\"testSummary\":{\"label\":\"//:pass\",\"configuration\":{" +
				"\"id\":\"configuration-1\"}}}]", "\"children\":[]");
	}

	/**
	 * Adds a reachable configured test that has the specified tag but no completion event.
	 *
	 * @param evidence complete invocation fixture
	 * @param label selected test label
	 * @param tag configured tag
	 * @return invocation with the added configured target
	 */
	private static String addConfiguredTest(String evidence, String label, String tag)
	{
		String originalPatternId = event("""
			{"pattern":{"pattern":["//:pass"]}}
			""");
		String extendedPatternId = event("""
			{"pattern":{"pattern":["//:pass","%s"]}}
			""".formatted(label));
		String patternChildren = "\"children\":[{\"targetConfigured\":{\"label\":\"//:pass\"}}]";
		String extendedChildren = "\"children\":[{\"targetConfigured\":{\"label\":\"//:pass\"}},{" +
			"\"targetConfigured\":{\"label\":\"" + label + "\"}}]";
		String configuredEvent = event("""
			{"id":{"targetConfigured":{"label":"%s"}},"configured":{"targetKind":"cc_test rule",
			"testSize":"SMALL","tag":["%s"]}}
			""".formatted(label, tag));
		String terminal = event("""
			{"id":{"buildFinished":{}},"finished":{"exitCode":{"code":0,"name":"SUCCESS"}}}
			""");
		return evidence.replace(originalPatternId, extendedPatternId).
			replace("\"chunk\":[\"//:pass\"]", "\"chunk\":[\"//:pass\",\"" + label + "\"]").
			replace("\"expanded\":[\"//:pass\"]", "\"expanded\":[\"//:pass\",\"" + label + "\"]").
			replace(patternChildren, extendedChildren).
			replace(terminal, configuredEvent + "\n" + terminal);
	}

	/**
	 * Announces a second attempt and includes its event while retaining the original one-run summary.
	 *
	 * @param evidence complete invocation fixture
	 * @return invocation with inconsistent announced retry coverage
	 */
	private static String addRetryAttempt(String evidence)
	{
		return addRetryAttempt(evidence, "TIMEOUT");
	}

	/**
	 * Builds a retry chain and updates its summary counts and final status.
	 *
	 * @param evidence complete invocation fixture
	 * @param retryStatus second attempt result status
	 * @return invocation with the requested second attempt result
	 */
	private static String addRetryAttempt(String evidence, String retryStatus)
	{
		String originalResult = event("""
			{"id":{"testResult":{"label":"//:pass","configuration":{"id":"configuration-1"},"run":1,
			"shard":1,"attempt":1}},"testResult":{"status":"PASSED"}}
			""");
		String retryId = testResultId("//:pass", "configuration-1", 1, 1, 2);
		String firstAttempt = event("""
			{"id":{"testResult":{"label":"//:pass","configuration":{"id":"configuration-1"},"run":1,
			"shard":1,"attempt":1}},"testResult":{"status":"FAILED"},"children":[%s]}
			""".formatted(retryId));
		String retryEvent = event("""
			{"id":{"testResult":{"label":"//:pass","configuration":{"id":"configuration-1"},"run":1,
			"shard":1,"attempt":2}},"testResult":{"status":"%s"}}
			""".formatted(retryStatus));
		String terminal = event("""
			{"id":{"buildFinished":{}},"finished":{"exitCode":{"code":0,"name":"SUCCESS"}}}
			""");
		String summaryStatus = "FAILED";
		if (retryStatus.equals("PASSED"))
			summaryStatus = "FLAKY";
		String result = evidence.replace(originalResult, firstAttempt).
			replace("\"totalRunCount\":1", "\"totalRunCount\":2").
			replace("\"overallStatus\":\"PASSED\"", "\"overallStatus\":\"" + summaryStatus + "\"").
			replace("\"attemptCount\":1", "\"attemptCount\":2").
			replace(terminal, retryEvent + "\n" + terminal);
		if (retryStatus.equals("PASSED"))
			return result.replace("\"passed\":[{\"uri\":\"file:///sanitized/test.log\"}]",
				"\"passed\":[{\"uri\":\"file:///sanitized/pass.log\"}]," +
				"\"failed\":[{\"uri\":\"file:///sanitized/fail.log\"}]");
		return result.
			replace("\"passed\":[{\"uri\":\"file:///sanitized/test.log\"}]",
				"\"failed\":[{\"uri\":\"file:///sanitized/fail.log\"}]");
	}

	/**
	 * Creates one canonical test-result child identity for its target, configuration, run, shard, and attempt.
	 *
	 * @param label test label
	 * @param configuration test configuration ID
	 * @param run run number
	 * @param shard shard number
	 * @param attempt test attempt number
	 * @return compact typed test-result identity
	 */
	private static String testResultId(String label, String configuration, int run, int shard, int attempt)
	{
		return event("""
			{"testResult":{"label":"%s","configuration":{"id":"%s"},"run":%d,
			"shard":%d,"attempt":%d}}
			""".formatted(label, configuration, run, shard, attempt));
	}

	/**
	 * Creates one canonical test-summary child identity for its target and configuration.
	 *
	 * @param label test label
	 * @param configuration test configuration ID
	 * @return compact typed test-summary identity
	 */
	private static String testSummaryId(String label, String configuration)
	{
		return event("""
			{"testSummary":{"label":"%s","configuration":{"id":"%s"}}}
			""".formatted(label, configuration));
	}

	/**
	 * Compacts one multiline event fixture to its JSONL representation.
	 *
	 * @param json formatted event document
	 * @return compact event JSON
	 */
	private static String event(String json)
	{
		JsonMapper mapper = JsonMapper.builder().build();
		return mapper.writeValueAsString(mapper.readTree(json));
	}

	/**
	 * Captured public command result.
	 *
	 * @param status process status
	 * @param output stdout text
	 * @param error stderr text
	 */
	private record Result(int status, String output, String error)
	{
	}

	/** Owns independent input files for one test. */
	private static final class Fixture implements AutoCloseable
	{
		/** Test-owned fixture directory. */
		private final Path root;

		/**
		 * Creates the isolated fixture directory.
		 *
		 * @throws IOException if creation fails
		 */
		private Fixture() throws IOException
		{
			root = Files.createTempDirectory("bazel-test-evidence-");
		}

		/**
		 * Writes one fixture file.
		 *
		 * @param relative relative path below the fixture directory
		 * @param content UTF-8 file content
		 * @return created path
		 * @throws IOException if the file cannot be written
		 */
		private Path write(String relative, String content) throws IOException
		{
			Path path = root.resolve(relative);
			Files.createDirectories(path.getParent());
			return Files.writeString(path, content, StandardCharsets.UTF_8);
		}

		/**
		 * Removes this test's files.
		 *
		 * @throws IOException if cleanup fails
		 */
		@Override
		public void close() throws IOException
		{
			try (Stream<Path> paths = Files.walk(root))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}
}
