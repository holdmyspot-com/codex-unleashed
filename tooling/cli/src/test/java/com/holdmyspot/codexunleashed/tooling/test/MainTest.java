package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.Main;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks command dispatch and caller-owned stream lifetime.
 */
public final class MainTest
{
	/**
	 * Creates independent command-boundary checks.
	 */
	public MainTest()
	{
	}

	/** Dispatches reproduction without closing caller streams. */
	@Test
	public void dispatchesReleaseReproduction()
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[]{"release-reproduction", "cleanup"},
				new ByteArrayInputStream(new byte[0]), stream, stream);
			stream.print("retained");
			requireThat(status, "status").isEqualTo(2);
			requireThat(bytes.toString(StandardCharsets.UTF_8), "diagnostic").contains("release-reproduction:").
				contains("--state").contains("retained").doesNotContain("Usage: codex-tooling");
		}
	}

	/**
	 * Reports an unknown command as invalid arguments and lists the supported commands.
	 */
	@Test
	public void unknownCommandReportsUsage()
	{
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[]{"schedule"}, new ByteArrayInputStream(new byte[0]), out, err);
            requireThat(status, "status").isEqualTo(2);
			requireThat(errors.toString(StandardCharsets.UTF_8), "usage").contains("Usage: codex-tooling").
				doesNotContain("schedule");
        }
	}

	/**
	 * Dispatches voice-runtime with the supplied environment and preserves caller-owned streams.
	 *
	 * @throws IOException if the missing package path cannot be represented
	 */
	@Test
	public void dispatchesVoiceRuntimeWithStreams() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
			ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(outputBytes, true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(errorBytes, true, StandardCharsets.UTF_8))
			{
				Path missingPackage = fixture.root.resolve("caller-owned missing package");
				int status = Main.run(new String[]{"voice-runtime", "check", "--package-dir", missingPackage.toString(),
					"--upstream-root", fixture.root.toString(), "--target", "x86_64-unknown-linux-musl",
					"--build-commit", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "--package-version", "1.2.3"},
					new ByteArrayInputStream(new byte[0]), out, err, Map.of("VOICE_TEST_ENV", "supplied"));
				out.print("retained");

				requireThat(status, "status").isEqualTo(1);
				requireThat(errorBytes.toString(StandardCharsets.UTF_8), "diagnostic").contains("voice-runtime:").
					contains(missingPackage.toString()).doesNotContain("Usage: codex-tooling");
				requireThat(outputBytes.toString(StandardCharsets.UTF_8), "callerOutput").isEqualTo("retained");
			}
		}
	}

	/**
	 * Rejects a watcher invocation without packaged launcher identity before creating its report.
	 *
	 * @throws IOException if fixture creation or cleanup fails
	 */
	@Test
	public void watchRequiresPackagedLauncher() throws IOException
	{
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		try (Fixture fixture = new Fixture();
			PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			Path report = fixture.root.resolve("watch-report.txt");
			int status = Main.run(new String[]{"watch-quiet-build", "--watch", fixture.root.toString(),
				"--report", report.toString(), "--", "must-not-start"},
				new ByteArrayInputStream(new byte[0]), out, err, Map.of(), null);

			requireThat(status, "status").isEqualTo(1);
			requireThat(errors.toString(StandardCharsets.UTF_8), "diagnostic").contains("codex.tooling.launcher").
				contains("packaged");
			requireThat(fixture.root, "watchDirectory").isEmpty();
		}
	}

	/**
	 * Rejects a relative packaged launcher before creating the watcher report.
	 *
	 * @throws IOException if fixture creation or cleanup fails
	 */
	@Test
	public void watchRejectsRelativeLauncher() throws IOException
	{
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		try (Fixture fixture = new Fixture();
			PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			Path report = fixture.root.resolve("watch-report.txt");
			int status = Main.run(new String[]{"watch-quiet-build", "--watch", fixture.root.toString(),
				"--report", report.toString(), "--", "must-not-start"},
				new ByteArrayInputStream(new byte[0]), out, err, Map.of(), Path.of("codex-tooling"));

			requireThat(status, "status").isEqualTo(1);
			requireThat(errors.toString(StandardCharsets.UTF_8), "diagnostic").contains("codex.tooling.launcher").
				contains("absolute");
			requireThat(fixture.root, "watchDirectory").isEmpty();
		}
	}

	/**
	 * Rejects missing launcher files and launcher directories before creating the watcher report.
	 *
	 * @throws IOException if fixture creation or cleanup fails
	 */
	@Test
	public void watchRejectsNonFileLauncher() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			for (Path launcher : List.of(fixture.root, fixture.root.resolve("missing-launcher")))
			{
				ByteArrayOutputStream errors = new ByteArrayOutputStream();
				try (PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
					PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
				{
					Path report = fixture.root.resolve("watch-report.txt");
					int status = Main.run(new String[]{"watch-quiet-build", "--watch", fixture.root.toString(),
						"--report", report.toString(), "--", "must-not-start"},
						new ByteArrayInputStream(new byte[0]), out, err, Map.of(), launcher);

					requireThat(status, "status").isEqualTo(1);
					requireThat(errors.toString(StandardCharsets.UTF_8), "diagnostic").contains("codex.tooling.launcher").
						contains("regular file");
					requireThat(fixture.root, "watchDirectory").isEmpty();
				}
			}
		}
	}

	/**
	 * Checks direct-dump input before launcher identity and malformed workflow-summary state.
	 */
	@Test
	public void directDumpValidatesBeforeLauncher()
	{
		String invalidPath = String.valueOf((char) 0);
		Map<String, String> environment = Map.of("GITHUB_STEP_SUMMARY", invalidPath);
		for (Path launcher : new Path[]{null, Path.of("codex-tooling")})
		{
			ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
			ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(outputBytes, true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(errorBytes, true, StandardCharsets.UTF_8))
			{
				int status = Main.run(new String[]{"watch-quiet-build", "--dump", "--jcmd"},
					new ByteArrayInputStream(new byte[0]), out, err, environment, launcher);

				requireThat(status, "status").isEqualTo(1);
				String diagnostic = errorBytes.toString(StandardCharsets.UTF_8);
				requireThat(diagnostic, "diagnostic").contains("--jcmd").contains("PATH").
					doesNotContain("codex.tooling.launcher");
				requireThat(outputBytes.size(), "outputBytes").isEqualTo(0);
			}
		}
	}

	/**
	 * Keeps a dump-like token after the watcher separator in the child command.
	 */
	@Test
	public void dumpAfterSeparatorNeedsLauncher()
	{
		ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(outputBytes, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errorBytes, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[]{"watch-quiet-build", "--", "--dump", "--jcmd"},
				new ByteArrayInputStream(new byte[0]), out, err, Map.of(), null);

			requireThat(status, "status").isEqualTo(1);
			String diagnostic = errorBytes.toString(StandardCharsets.UTF_8);
			requireThat(diagnostic, "diagnostic").contains("codex.tooling.launcher").
				doesNotContain("--jcmd");
			requireThat(outputBytes.size(), "outputBytes").isEqualTo(0);
		}
	}

	/**
	 * Parses normal watch options before starting a process or creating watcher output.
	 *
	 * @throws IOException if the launcher fixture cannot be created
	 */
	@Test
	public void packagedWatchParsesArguments() throws IOException
	{
		try (Fixture fixture = new Fixture();
			PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
			ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
			PrintStream err = new PrintStream(errorBytes, true, StandardCharsets.UTF_8))
		{
			Path launcher = fixture.write("codex-tooling", "launcher fixture");
			int status = Main.runPackaged(new String[]{"watch-quiet-build"},
				new ByteArrayInputStream(new byte[0]), out, err, Map.of(), launcher.toString());

			requireThat(status, "status").isEqualTo(1);
			requireThat(errorBytes.toString(StandardCharsets.UTF_8), "diagnostic").contains("--watch").
				doesNotContain("Usage:");
		}
	}

	/**
	 * Reports malformed packaged launcher text without echoing it.
	 */
	@Test
	public void packagedWatchRejectsMalformedIdentity()
	{
		ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errorBytes, true, StandardCharsets.UTF_8))
		{
			String malformedIdentity = String.valueOf((char) 0);
			int status = Main.runPackaged(new String[]{"watch-quiet-build"},
				new ByteArrayInputStream(new byte[0]), out, err, Map.of(), malformedIdentity);

			requireThat(status, "status").isEqualTo(1);
			String diagnostic = errorBytes.toString(StandardCharsets.UTF_8);
			requireThat(diagnostic, "diagnostic").contains("codex.tooling.launcher").contains("valid").
				doesNotContain(malformedIdentity);
		}
	}

	/**
	 * Ignores malformed launcher identity text for unrelated commands.
	 */
	@Test
	public void unrelatedCommandIgnoresMalformedIdentity()
	{
		ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errorBytes, true, StandardCharsets.UTF_8))
		{
			int status = Main.runPackaged(new String[]{"schedule"}, new ByteArrayInputStream(new byte[0]), out, err,
				Map.of(), String.valueOf((char) 0));

			requireThat(status, "status").isEqualTo(2);
			requireThat(errorBytes.toString(StandardCharsets.UTF_8), "diagnostic").contains("Usage:").
				doesNotContain("codex.tooling.launcher");
		}
	}

	/**
	 * Parses malformed direct-dump options before interpreting packaged launcher identity.
	 */
	@Test
	public void directDumpIgnoresMalformedIdentity()
	{
		ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errorBytes, true, StandardCharsets.UTF_8))
		{
			int status = Main.runPackaged(new String[]{"watch-quiet-build", "--dump", "--jcmd"},
				new ByteArrayInputStream(new byte[0]), out, err, Map.of(), String.valueOf((char) 0));

			requireThat(status, "status").isEqualTo(1);
			requireThat(errorBytes.toString(StandardCharsets.UTF_8), "diagnostic").contains("--jcmd").
				contains("PATH").doesNotContain("codex.tooling.launcher");
		}
	}

	/**
	 * Routes a complete sanitized Bazel invocation through the command dispatcher.
	 *
	 * @throws IOException if the BEP or expected-pattern fixture cannot be written
	 */
	@Test
	public void routesBazelEvidence() throws IOException
	{
		String invocation = String.join("\n", List.of(
				compactEvent("""
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
				compactEvent("""
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
				compactEvent("""
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
				compactEvent("""
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
				compactEvent("""
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
				compactEvent("""
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
				compactEvent("""
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
				compactEvent("""
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
		try (Fixture fixture = new Fixture())
		{
			Path bep = fixture.write("evidence.bep.json", invocation);
			Path patterns = fixture.write("patterns.txt", "//:pass\n");
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ByteArrayOutputStream errors = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
			{
				int status = Main.run(new String[]{"validate-bazel-test-evidence", "--bep", bep.toString(),
					"--expected-patterns", patterns.toString(), "--bazel-exit-code", "0"},
					new ByteArrayInputStream(new byte[0]), out, err, Map.of());
				requireThat(status, "status").isEqualTo(0);
				requireThat(output.toString(StandardCharsets.UTF_8), "evidence").contains("\"coverage\":\"complete\"");
				requireThat(errors.toString(StandardCharsets.UTF_8), "diagnostics").isEmpty();
			}
		}
	}

	/**
	 * Exposes blob policy help through the Java command dispatcher without starting Git.
	 */
	@Test
	public void blobPolicyHelp()
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[]{"check-blob-size", "--help"},
				new ByteArrayInputStream(new byte[0]), out, err);
			requireThat(status, "status").isEqualTo(0);
			requireThat(output.toString(StandardCharsets.UTF_8), "help").contains("--base").
				contains("--head");
			requireThat(errors.toString(StandardCharsets.UTF_8), "diagnostics").isEmpty();
		}
	}

	/**
	 * Runs an existing command through the environment-injecting entry point.
	 */
	@Test
	public void blobPolicyHelpWithExplicitEnvironment()
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[]{"check-blob-size", "--help"},
				new ByteArrayInputStream(new byte[0]), out, err, Map.of());
			requireThat(status, "status").isEqualTo(0);
			requireThat(output.toString(StandardCharsets.UTF_8), "help").contains("--base").
				contains("--head");
			requireThat(errors.toString(StandardCharsets.UTF_8), "diagnostics").isEmpty();
		}
	}

	/**
	 * Rejects harmless invalid arguments for apt and workspace-output pruning without starting their tools.
	 *
	 * @throws IOException if a tool's output cannot be captured
	 */
	@Test
	public void rejectsInvalidAptAndPruneArguments() throws IOException
	{
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			int aptStatus = Main.run(new String[]{"apt-install-with-fallback", "--unknown"},
				new ByteArrayInputStream(new byte[0]), out, err, Map.of());
			requireThat(aptStatus, "aptStatus").isEqualTo(2);
			requireThat(errors.toString(StandardCharsets.UTF_8), "aptUsage").contains("Usage: apt-install-with-fallback");

			errors.reset();
			int pruneStatus = Main.run(new String[]{"prune-workspace-outputs", "--unknown"},
				new ByteArrayInputStream(new byte[0]), out, err, Map.of());
			requireThat(pruneStatus, "pruneStatus").isEqualTo(2);
			requireThat(errors.toString(StandardCharsets.UTF_8), "pruneUsage").contains("Usage: prune-workspace-outputs");
		}
	}

	/**
	 * Prints package-license help through the command dispatcher without reading package files or starting a process.
	 *
	 * @throws IOException if a tool's output cannot be captured
	 */
	@Test
	public void packageLicenseHelp() throws IOException
	{
		TrackingInput input = new TrackingInput(new byte[0]);
		TrackingOutput output = new TrackingOutput();
		TrackingOutput errors = new TrackingOutput();
		try (input;
			PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[]{"package-license", "--help"},
				input, out, err, Map.of());
			requireThat(status, "status").isEqualTo(0);
			requireThat(output.toString(StandardCharsets.UTF_8), "help").contains("Usage: package-license");
			requireThat(errors.toString(StandardCharsets.UTF_8), "diagnostics").isEmpty();
			requireThat(input.closed, "inputClosed").isFalse();
			requireThat(output.closed, "outputClosed").isFalse();
			requireThat(errors.closed, "errorsClosed").isFalse();
		}
	}

	/**
	 * Routes manifest help and preserves the caller's streams.
	 *
	 * @throws IOException if a tool's output cannot be captured
	 */
	@Test
	public void releaseManifestHelp() throws IOException
	{
		TrackingInput input = new TrackingInput(new byte[]{42});
		TrackingOutput output = new TrackingOutput();
		TrackingOutput errors = new TrackingOutput();
		try (input;
			PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[]{"release-manifest", "--help"}, input, out, err, Map.of());
			requireThat(status, "status").isEqualTo(0);
			requireThat(output.toString(StandardCharsets.UTF_8), "help").
				contains("release-manifest {receipt,assemble,verify,authenticate}");
			requireThat(errors.toString(StandardCharsets.UTF_8), "diagnostics").isEmpty();
			requireThat(input.closed, "inputClosed").isFalse();
			requireThat(output.closed, "outputClosed").isFalse();
			requireThat(errors.closed, "errorsClosed").isFalse();
			requireThat(input.read(), "remainingInput").isEqualTo(42);
			out.print("still-open");
			err.print("still-open");
			requireThat(output.toString(StandardCharsets.UTF_8), "usableOutput").contains("still-open");
			requireThat(errors.toString(StandardCharsets.UTF_8), "usableErrors").contains("still-open");
		}
	}

	/**
	 * Routes native-input collection help without requiring build records.
	 */
	@Test
	public void releaseNativeInputsHelp()
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[]{"release-native-inputs", "--help"},
				new ByteArrayInputStream(new byte[0]), out, err, Map.of());
			requireThat(status, "status").isEqualTo(0);
			requireThat(output.toString(StandardCharsets.UTF_8), "help").contains("release-native-inputs");
			requireThat(errors.toString(StandardCharsets.UTF_8), "diagnostics").isEmpty();
		}
	}

	/**
	 * Routes native input statuses while retaining caller-owned streams.
	 *
	 * @throws IOException if stream cleanup fails
	 */
	@Test
	public void routesNativeInputsWithBorrowedStreams() throws IOException
	{
		TrackingInput input = new TrackingInput(new byte[]{42});
		TrackingOutput output = new TrackingOutput();
		TrackingOutput errors = new TrackingOutput();
		try (input;
			PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			int help = Main.run(new String[]{"release-native-inputs", "--help"}, input, out, err, Map.of());
			requireThat(help, "helpStatus").isEqualTo(0);
			int invalid = Main.run(new String[]{"release-native-inputs", "--unknown"}, input, out, err, Map.of());
			requireThat(invalid, "invalidStatus").isEqualTo(2);
			requireThat(errors.toString(StandardCharsets.UTF_8), "invalidDiagnostic").contains("Unknown option");
			int rejected = Main.run(new String[]{"release-native-inputs", "--build-result", "missing-build",
				"--archive-result", "missing-archive", "--target", "x86_64-unknown-linux-musl", "--package-root",
				"missing-packages", "--output", "unused-output.json"}, input, out, err, Map.of());
			requireThat(rejected, "contentStatus").isEqualTo(1);
			requireThat(errors.toString(StandardCharsets.UTF_8), "contentDiagnostic").contains("Operation root");
			requireThat(input.closed, "inputClosed").isFalse();
			requireThat(output.closed, "outputClosed").isFalse();
			requireThat(errors.closed, "errorsClosed").isFalse();
			requireThat(input.read(), "remainingInput").isEqualTo(42);
			out.print("still-open");
			err.print("still-open");
			requireThat(output.toString(StandardCharsets.UTF_8), "outputUsable").contains("still-open");
			requireThat(errors.toString(StandardCharsets.UTF_8), "errorsUsable").contains("still-open");
		}
	}

	/**
	 * Preserves the producer's invalid-argument status and diagnostic.
	 */
	@Test
	public void releaseManifestInvalidArguments()
	{
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[]{"release-manifest", "authenticate"},
				new ByteArrayInputStream(new byte[0]), out, err, Map.of());
			requireThat(status, "status").isEqualTo(2);
			requireThat(errors.toString(StandardCharsets.UTF_8), "diagnostic").
				contains("release-manifest: error:").contains("manifest");
		}
	}

	/**
	 * Rejects malformed manifest data before attempting source reconstruction.
	 *
	 * @throws IOException if fixture files cannot be created
	 */
	@Test
	public void releaseManifestRejectsInvalidManifest() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path manifest = fixture.write("release/release-manifest.json", "{}");
			ByteArrayOutputStream errors = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
			{
				int status = Main.run(new String[]{"release-manifest", "verify", "--release-dir",
					manifest.getParent().toString(), "--patch-repo", fixture.root.resolve("missing-fork").toString(),
					"--upstream-source", fixture.root.resolve("missing-source").toString()},
					new ByteArrayInputStream(new byte[0]), out, err, Map.of());
				requireThat(status, "status").isEqualTo(1);
				requireThat(errors.toString(StandardCharsets.UTF_8), "diagnostic").
					contains("Release verification failed: Invalid manifest fields");
			}
		}
	}

	/**
	 * Includes the manifest command in top-level usage.
	 */
	@Test
	public void releaseManifestAppearsInUsage()
	{
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[0], new ByteArrayInputStream(new byte[0]), out, err, Map.of());
			requireThat(status, "status").isEqualTo(2);
			requireThat(errors.toString(StandardCharsets.UTF_8), "usage").contains("codex-tooling release-manifest");
		}
	}

	/**
	 * Verifies source identity and reads the release patch order from caller-owned files.
	 *
	 * @throws IOException if fixture files cannot be created
	 */
	@Test
	public void routesIdentityAndReleasePatches() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path manifest = fixture.write("codex-rs/Cargo.toml", "[workspace.package]\nversion = \"0.167.0\"\n");
			Path sourceRoot = manifest.getParent().getParent();
			Path readme = fixture.write("README.md",
				"# Queue\n\n1. [one.patch](vendor/one.patch)\n2. [two.patch](vendor/two.patch)\n");
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ByteArrayOutputStream errors = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
			{
				int identityStatus = Main.run(new String[]{"package-identity", "source", "--source-root",
					sourceRoot.toString(), "--version", "0.167.0", "--build", "17"},
					new ByteArrayInputStream(new byte[0]), out, err, Map.of());
				requireThat(identityStatus, "identityStatus").isEqualTo(0);
				requireThat(errors.toString(StandardCharsets.UTF_8), "identityDiagnostics").isEmpty();

				int releaseStatus = Main.run(new String[]{"upstream-auto-release", "patches", "--readme",
					readme.toString()}, new ByteArrayInputStream(new byte[0]), out, err, Map.of());
				requireThat(releaseStatus, "releaseStatus").isEqualTo(0);
				requireThat(output.toString(StandardCharsets.UTF_8), "patchOrder").isEqualTo(
					"patches/vendor/one.patch patches/vendor/two.patch" + System.lineSeparator());
				requireThat(errors.toString(StandardCharsets.UTF_8), "releaseDiagnostics").isEmpty();
			}
		}
	}

	/**
	 * Preserves missing-baseline and new-failure statuses and appends report summaries to the selected file.
	 *
	 * @throws IOException if fixture reports or summary cannot be created
	 */
	@Test
	public void routesReportCommandsAndAppendsSummary() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path baseline = fixture.write("baseline.xml",
				"<testsuites><testcase name='old' classname='bin'><failure type='test failure'/></testcase></testsuites>");
			Path patched = fixture.write("patched.xml",
				"<testsuites><testcase name='old' classname='bin'/>" +
				"<testcase name='new' classname='bin'><failure/></testcase></testsuites>");
			Path baselines = fixture.root.resolve("baselines");
			Path skips = fixture.write("skips.xml", "<testsuites><testsuite><testcase name='a' classname='bin'>" +
				"<system-out>skipping zsh test: /bin/zsh is unavailable</system-out>" +
				"</testcase></testsuite></testsuites>");
			Path summary = fixture.write("summary.md", "existing summary\n");
			Map<String, String> environment = Map.of("GITHUB_STEP_SUMMARY", summary.toString());
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ByteArrayOutputStream errors = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
			{
				String[] compare = {"compare-test-baseline", "--platform", "linux", "--upstream-commit", "commit",
					"--baseline-dir", baselines.toString(), baseline.toString()};
				int missingBaselineStatus = Main.run(compare, new ByteArrayInputStream(new byte[0]), out, err, environment);
				requireThat(missingBaselineStatus, "missingBaselineStatus").isEqualTo(2);
				requireThat(Files.readString(summary), "summaryAfterMissingBaseline").isEqualTo("existing summary\n");

				String[] recordBaseline = {"compare-test-baseline", "--platform", "linux", "--upstream-commit", "commit",
					"--baseline-dir", baselines.toString(), "--write-baseline", baseline.toString()};
				requireThat(Main.run(recordBaseline, new ByteArrayInputStream(new byte[0]), out, err, environment),
					"recordBaselineStatus").isEqualTo(0);

				String[] comparePatched = {"compare-test-baseline", "--platform", "linux", "--upstream-commit", "commit",
					"--baseline-dir", baselines.toString(), patched.toString()};
				requireThat(Main.run(comparePatched, new ByteArrayInputStream(new byte[0]), out, err, environment),
					"newFailureStatus").isEqualTo(1);
				requireThat(output.toString(StandardCharsets.UTF_8), "newFailures").
					contains("[new (fail the run)] 1" + System.lineSeparator() + "    bin new");

				int skipStatus = Main.run(new String[]{"check-test-skips", "--platform", "linux", skips.toString()},
					new ByteArrayInputStream(new byte[0]), out, err, environment);
				requireThat(skipStatus, "skipStatus").isEqualTo(1);
				requireThat(output.toString(StandardCharsets.UTF_8), "skipOutput").contains("[missing setup] x1");
			}
			String summaryContent = Files.readString(summary);
			requireThat(summaryContent, "appendedSummary").contains("existing summary\n").
				contains("### Test baseline comparison").contains("### Test skip check");
			requireThat(errors.toString(StandardCharsets.UTF_8), "reportDiagnostics").contains("No baseline at ");
		}
	}

	/**
	 * Shows the blob policy's required revision arguments when the command receives none.
	 */
	@Test
	public void blobPolicyMissingArgumentsShowsUsage()
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[]{"check-blob-size"},
				new ByteArrayInputStream(new byte[0]), out, err);
			requireThat(status, "status").isEqualTo(2);
			requireThat(output.toString(StandardCharsets.UTF_8), "output").isEmpty();
				requireThat(errors.toString(StandardCharsets.UTF_8), "usage").
				contains("check-blob-size --base BASE --head HEAD").contains("[--repo PATH]");
		}
	}

	/**
	 * Leaves borrowed input and output streams open and usable after dispatch reports an argument error.
	 *
	 * @throws IOException if test-owned input cleanup fails
	 */
	@Test
	public void preservesBorrowedStreams() throws IOException
	{
		TrackingInput input = new TrackingInput(new byte[]{42});
		TrackingOutput output = new TrackingOutput();
		TrackingOutput errors = new TrackingOutput();
		try (input;
			PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			requireThat(Main.run(new String[]{"upstream-v8-script"}, input, out, err), "status").isEqualTo(2);
			requireThat(input.closed, "inputClosed").isFalse();
			requireThat(output.closed, "outputClosed").isFalse();
			requireThat(errors.closed, "errorClosed").isFalse();
			requireThat(input.read(), "remainingInput").isEqualTo(42);
			out.print("still-open");
			requireThat(output.toString(StandardCharsets.UTF_8), "usableOutput").contains("still-open");
		}
	}

	/**
	 * Serializes one formatted sanitized BEP event as one JSONL record.
	 *
	 * @param event formatted JSON event
	 * @return compact JSON event
	 */
	private static String compactEvent(String event)
	{
		JsonMapper mapper = JsonMapper.builder().build();
		return mapper.writeValueAsString(mapper.readTree(event));
	}

	/** Models caller-owned input and records any attempted ownership violation. */
	private static final class TrackingInput extends ByteArrayInputStream
	{
		/** Indicates whether an owner has closed this input. */
		private boolean closed;
		/**
		 * Creates test-owned input bytes.
		 *
		 * @param bytes bytes available to the command caller
		 */
		private TrackingInput(byte[] bytes)
		{
			super(bytes);
		}
		@Override
		public void close() throws IOException
		{
			closed = true;
			super.close();
		}
	}

	/** Owns isolated filesystem inputs for command-dispatch tests. */
	private static final class Fixture implements AutoCloseable
	{
		/** Test files removed when this fixture closes. */
		private final Path root;

		/**
		 * Creates a new directory for this test's files.
		 *
		 * @throws IOException if the test directory cannot be created
		 */
		private Fixture() throws IOException
		{
			root = Files.createTempDirectory("main-dispatch-");
		}

		/**
		 * Writes one fixture file beneath this test's directory.
		 *
		 * @param relativePath path below this fixture's directory
		 * @param content file contents
		 * @return the created file path
		 * @throws IOException if the fixture file cannot be written
		 */
		private Path write(String relativePath, String content) throws IOException
		{
			Path path = root.resolve(relativePath);
			Files.createDirectories(path.getParent());
			return Files.writeString(path, content, StandardCharsets.UTF_8);
		}

		/**
		 * Removes this test's files and directory.
		 *
		 * @throws IOException if a fixture file cannot be removed
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

	/**
	 * Models caller-owned output and records any attempted ownership violation.
	 */
    private static final class TrackingOutput extends ByteArrayOutputStream
	{
		/** Indicates whether an owner has closed this output. */
		private boolean closed;
		/**
		 * Creates empty test-owned output.
		 */
		private TrackingOutput()
		{
		}
		@Override
		public void close() throws IOException
		{
			closed = true;
			super.close();
		}
	}
}
