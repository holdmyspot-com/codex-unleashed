package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Classifies skip messages found in nextest and Bazel reports. */
public final class CheckTestSkips
{
	/** A known policy outcome. */
	public enum Verdict
	{
		/** Skip is expected for this execution scope. */
		ALLOWED,
		/** Skip indicates a missing test prerequisite. */
		MISSING_SETUP,
		/** No policy rule describes this skip. */
		UNCLASSIFIED
	}
	/** Runner identities accepted by the gate. */
	public enum Runner
	{
		/** nextest report conventions. */
		NEXTEST,
		/** Bazel report conventions. */
		BAZEL
	}
	/** Execution environments accepted by the gate. */
	public enum Environment
	{
		/** Tests run without the remote test environment. */
		LOCAL,
		/** Tests run with the remote test environment. */
		REMOTE
	}
	/** Platform identities accepted by the gate. */
	public enum Platform
	{
		/** Linux host. */
		LINUX,
		/** macOS host. */
		MACOS,
		/** Windows host. */
		WINDOWS
	}
	/**
	 * One skip message and the report location that supplied it.
	 *
	 * @param source report file containing the skip
	 * @param test testcase name, or empty for a report-level line
	 * @param line original ANSI-stripped skip message
	 */
	public record Skip(Path source, String test, String line)
	{
	}
	/**
	 * A classified skip with its policy explanation.
	 *
	 * @param skip source skip message
	 * @param verdict policy classification
	 * @param reason matching rule explanation
	 */
	public record ClassifiedSkip(Skip skip, Verdict verdict, String reason)
	{
	}
	/**
	 * Collected report files and classified skips.
	 *
	 * @param files discovered report files
	 * @param skips distinct classified skip messages
	 */
	public record Result(List<Path> files, List<ClassifiedSkip> skips)
	{
		/**
		 * Copies values at the result boundary.
		 *
		 * @param files discovered report files
		 * @param skips distinct classified skips
		 */
		public Result(List<Path> files, List<ClassifiedSkip> skips)
		{
			this.files = List.copyOf(files);
			this.skips = List.copyOf(skips);
		}
	}
	/**
	 * One ordered skip-policy rule with optional execution-scope restrictions.
	 *
	 * @param pattern skip-message matcher
	 * @param verdict result when the matcher applies
	 * @param reason explanation shown in the report
	 * @param platforms allowed platforms, or {@code null} for every platform
	 * @param runners allowed runners, or {@code null} for every runner
	 * @param environments allowed environments, or {@code null} for both
	 */
	private record Rule(Pattern pattern, Verdict verdict, String reason, List<Platform> platforms,
		List<Runner> runners, List<Environment> environments)
	{
		/**
		 * Checks whether a rule applies to the selected execution scope.
		 *
		 * @param platform selected platform
		 * @param runner selected runner
		 * @param environment selected environment
		 * @return whether all configured scope restrictions match
		 */
		private boolean applies(Platform platform, Runner runner, Environment environment)
		{
			return (platforms == null || platforms.contains(platform)) && (runners == null || runners.contains(runner)) &&
				(environments == null || environments.contains(environment));
		}
	}
	/**
	 * Rendering group keyed by verdict, normalized message, and explanation.
	 *
	 * @param verdict classification displayed for the group
	 * @param message digit-normalized skip text
	 * @param reason explanation shared by the group
	 */
	private record Group(Verdict verdict, String message, String reason)
	{
	}

	/** Display text for the allowed verdict. */
	private static final String ALLOW = "allowed";
	/** Display text for the missing-setup verdict. */
	private static final String MISSING = "missing setup";
	/** Display text for the unclassified verdict. */
	private static final String UNKNOWN = "unclassified";
	/** Removes supported terminal color escape sequences. */
	private static final Pattern ANSI_ESCAPE = Pattern.compile("[\\x1b\\ufffd?]\\[[0-9;]*[A-Za-z]");
	/** Finds skip wording in runner output. */
	private static final Pattern SKIP_LINE = Pattern.compile(
		"^\\s*skipp(?:ing|ed)\\b|[;,]\\s+skipping\\b", Pattern.CASE_INSENSITIVE);
	/** Identifies diagnostic tracing output excluded from skip messages. */
	private static final Pattern TRACING_LINE = Pattern.compile(
		"\\s(?:ERROR|WARN|INFO|DEBUG|TRACE)\\s|^\\s*(?:ERROR|WARN|INFO|DEBUG|TRACE)\\b");
	/** Identifies libtest status lines excluded from skip messages. */
	private static final Pattern LIBTEST_LINE = Pattern.compile("^test \\S+ \\.\\.\\. ");
	/** Replaces digits when grouping skip text. */
	private static final Pattern DIGITS = Pattern.compile("\\d+");
	/** Ordered snapshot of the accepted skip policy. */
	private static final List<Rule> RULES = createRules();

	/** Prevents construction. */
	private CheckTestSkips()
	{
	}

	/**
	 * Classifies a message using the first applicable snapshot rule.
	 *
	 * @param skip skip message and source
	 * @param platform selected platform
	 * @param runner selected report runner
	 * @param environment selected execution environment
	 * @return classification and matching rule explanation
	 */
	public static ClassifiedSkip classify(Skip skip, Platform platform, Runner runner, Environment environment)
	{
		Objects.requireNonNull(skip, "skip");
		for (Rule rule : RULES)
			if (rule.applies(platform, runner, environment) && rule.pattern().matcher(skip.line()).find())
				return new ClassifiedSkip(skip, rule.verdict(), rule.reason());
		return new ClassifiedSkip(skip, Verdict.UNCLASSIFIED, "no allowlist or missing-setup rule matches this skip");
	}

	/**
	 * Collects and classifies all skip messages from reports.
	 *
	 * @param paths report paths or directories to scan
	 * @param reportRunner report collection convention
	 * @param platform selected platform
	 * @param runner selected runner policy
	 * @param environment selected execution environment
	 * @return discovered files and distinct classified skips
	 * @throws IOException if a report is missing, unreadable, or malformed
	 */
	public static Result evaluate(List<Path> paths, TestReports.Runner reportRunner, Platform platform,
		Runner runner, Environment environment) throws IOException
	{
		Objects.requireNonNull(paths, "paths");
		TestReports.Report reports = TestReports.collect(paths, reportRunner);
		List<Skip> skips = new ArrayList<>();
		Map<String, Skip> distinct = new LinkedHashMap<>();
		for (TestReports.Stream stream : reports.streams())
			for (String line : skipLines(stream.text()))
				putDistinct(distinct, new Skip(stream.source(), stream.test(), line));
		for (Path path : reports.files())
			if (!path.toString().endsWith(".xml"))
				for (String line : skipLines(Files.readString(path, StandardCharsets.UTF_8)))
					putDistinct(distinct, new Skip(path, "", line));
		for (Skip skip : distinct.values()) skips.add(skip);
		List<ClassifiedSkip> classified = skips.stream().map(skip ->
			classify(skip, platform, runner, environment)).toList();
		return new Result(reports.files(), classified);
	}

	/**
	 * Preserves the Bazel test.log/test.xml target-level deduplication protocol.
	 *
	 * @param values distinct skips collected so far
	 * @param skip candidate skip
	 */
	private static void putDistinct(Map<String, Skip> values, Skip skip)
	{
		String file = skip.source().getFileName().toString();
		String key;
		if (file.equals("test.log") || file.equals("test.xml"))
			key = skip.source().getParent() + "\0" + skip.line();
		else
			key = skip.source() + "\0" + skip.test() + "\0" + skip.line();
		values.putIfAbsent(key, skip);
	}

	/**
	 * Extracts skip lines while ignoring tracing and libtest status messages.
	 *
	 * @param text report stream text
	 * @return recognized skip lines in source order
	 */
	private static List<String> skipLines(String text)
	{
		List<String> result = new ArrayList<>();
		for (String raw : text.split("\\R", -1))
		{
			String line = ANSI_ESCAPE.matcher(raw).replaceAll("").strip();
			if (!line.isEmpty() && !LIBTEST_LINE.matcher(line).find() && !TRACING_LINE.matcher(line).find() &&
				SKIP_LINE.matcher(line).find())
				result.add(line);
		}
		return result;
	}

	/**
	 * Renders missing, unknown, then allowed skips, retaining first-occurrence group order.
	 *
	 * @param result classified report result
	 * @return human-readable lines in the command's output order
	 */
	public static List<String> render(Result result)
	{
		Map<Group, List<ClassifiedSkip>> groups = new LinkedHashMap<>();
		for (ClassifiedSkip skip : result.skips())
		{
			Group key = new Group(skip.verdict(), DIGITS.matcher(skip.skip().line()).replaceAll("N"), skip.reason());
			groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(skip);
		}
		List<String> lines = new ArrayList<>();
		lines.add("Scanned " + result.files().size() + " report file(s); found " + result.skips().size() +
			" skip message(s).");
		for (Verdict verdict : List.of(Verdict.MISSING_SETUP, Verdict.UNCLASSIFIED, Verdict.ALLOWED))
			for (Map.Entry<Group, List<ClassifiedSkip>> entry : groups.entrySet())
				if (entry.getKey().verdict() == verdict)
				{
					lines.add("[" + label(verdict) + "] x" + entry.getValue().size() + " " +
						entry.getKey().message());
					lines.add("    reason: " + entry.getKey().reason());
					for (ClassifiedSkip member : entry.getValue().subList(0, Math.min(3, entry.getValue().size())))
					{
						String example = member.skip().test();
						if (example.isEmpty())
							example = member.skip().source().toString();
						lines.add("    e.g. " + example);
					}
				}
		long failing = result.skips().stream().filter(skip -> skip.verdict() != Verdict.ALLOWED).count();
		lines.add(failing + " skip message(s) point to missing setup or are unclassified.");
		return List.copyOf(lines);
	}

	/**
	 * Runs the command protocol using an explicit summary path supplied by the composition boundary.
	 *
	 * @param arguments command arguments
	 * @param summary optional workflow summary file
	 * @param out standard output stream
	 * @param err standard error stream
	 * @return command status
	 * @throws IOException if a report or summary file cannot be read or written
	 */
	public static int run(String[] arguments, Path summary, PrintStream out, PrintStream err) throws IOException
	{
		String platformValue = null;
		String runnerValue = "nextest";
		String environmentValue = "remote";
		List<String> paths = new ArrayList<>();
		boolean positional = false;
		for (int index = 0; index < arguments.length; ++index)
		{
			String argument = arguments[index];
			if (!positional && argument.equals("--"))
			{
				positional = true;
				continue;
			}
			if (!positional && argument.startsWith("--"))
			{
				if (index + 1 == arguments.length)
					return usage(err);
				++index;
				String value = arguments[index];
				switch (argument)
				{
					case "--platform" -> platformValue = value;
					case "--runner" -> runnerValue = value;
					case "--environment" -> environmentValue = value;
				default ->
				{
					return usage(err);
				}
				}
			}
			else paths.add(argument);
		}
		if (platformValue == null || paths.isEmpty())
			return usage(err);
		Platform platform = switch (platformValue)
		{
			case "linux" -> Platform.LINUX;
			case "macos" -> Platform.MACOS;
			case "windows" -> Platform.WINDOWS;
			default -> null;
		};
		Runner runner = switch (runnerValue)
		{
			case "nextest" -> Runner.NEXTEST;
			case "bazel" -> Runner.BAZEL;
			default -> null;
		};
		Environment environment = switch (environmentValue)
		{
			case "local" -> Environment.LOCAL;
			case "remote" -> Environment.REMOTE;
			default -> null;
		};
		if (platform == null || runner == null || environment == null)
			return usage(err);
		TestReports.Runner reportRunner;
		if (runner == Runner.BAZEL)
			reportRunner = TestReports.Runner.BAZEL;
		else
			reportRunner = TestReports.Runner.NEXTEST;
		Result result = evaluate(paths.stream().map(Path::of).toList(), reportRunner, platform, runner, environment);
		if (result.files().isEmpty())
		{
			err.println("No test output was found to scan; the tests must keep their output.");
			return 1;
		}
		List<String> lines = render(result);
		lines.forEach(out::println);
		if (summary != null)
			Files.writeString(summary, "### Test skip check\n\n```\n" + String.join("\n", lines) + "\n```\n",
				java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		if (result.skips().stream().anyMatch(skip -> skip.verdict() != Verdict.ALLOWED))
			return 1;
		return 0;
	}

	/**
	 * Prints command usage and returns the syntax-error status.
	 *
	 * @param err diagnostic output stream
	 * @return syntax-error status
	 */
	private static int usage(PrintStream err)
	{
		err.println("Usage: check-test-skips --platform {linux,macos,windows} " +
			"[--runner {nextest,bazel}] [--environment {local,remote}] PATH...");
		return 2;
	}

	/**
	 * Returns the human-readable label for a verdict.
	 *
	 * @param verdict classified result
	 * @return legacy verdict label
	 */
	private static String label(Verdict verdict)
	{
		return switch (verdict)
		{
			case ALLOWED -> ALLOW;
			case MISSING_SETUP -> MISSING;
			case UNCLASSIFIED -> UNKNOWN;
		};
	}

	/**
	 * Creates an unrestricted policy rule.
	 *
	 * @param expression case-insensitive skip expression
	 * @param verdict result when matched
	 * @param reason explanation displayed to users
	 * @return policy rule without scope restrictions
	 */
	private static Rule rule(String expression, Verdict verdict, String reason)
	{
		return new Rule(Pattern.compile(expression, Pattern.CASE_INSENSITIVE), verdict, reason, null, null, null);
	}
	/**
	 * Creates a policy rule with selected execution scope restrictions.
	 *
	 * @param expression case-insensitive skip expression
	 * @param verdict result when matched
	 * @param reason explanation displayed to users
	 * @param platforms accepted platforms, or {@code null} for all
	 * @param runners accepted runners, or {@code null} for all
	 * @param environments accepted environments, or {@code null} for all
	 * @return scoped policy rule
	 */
	private static Rule rule(String expression, Verdict verdict, String reason, List<Platform> platforms,
		List<Runner> runners, List<Environment> environments)
	{
		return new Rule(Pattern.compile(expression, Pattern.CASE_INSENSITIVE), verdict, reason, platforms,
			runners, environments);
	}
	/**
	 * Builds an immutable platform scope.
	 *
	 * @param values accepted platforms
	 * @return immutable platform list
	 */
	private static List<Platform> platforms(Platform... values)
	{
		return List.of(values);
	}
	/**
	 * Builds an immutable runner scope.
	 *
	 * @param values accepted runners
	 * @return immutable runner list
	 */
	private static List<Runner> runners(Runner... values)
	{
		return List.of(values);
	}
	/**
	 * Builds an immutable environment scope.
	 *
	 * @param values accepted environments
	 * @return immutable environment list
	 */
	private static List<Environment> environments(Environment... values)
	{
		return List.of(values);
	}

	/**
	 * Snapshot RULES in original order, including overlapping scopes and exact reason text.
	 *
	 * @return immutable policy table
	 */
	private static List<Rule> createRules()
	{
		List<Rule> rules = new ArrayList<>();
		rules.add(rule("address (?:is )?already in use|port (?:is )?already in use|failed to bind unix socket",
			Verdict.MISSING_SETUP, "a required address, port or Unix socket could not be bound"));
		rules.add(rule("python(?:3)? (?:not found|not installed)|PowerShell 7 is not installed", Verdict.MISSING_SETUP,
			"a required Windows test tool is not installed", platforms(Platform.WINDOWS), null, null));
		rules.add(rule("WSL|WSLg", Verdict.ALLOWED, "WSL-only test; the runner is not WSL"));
		rules.add(rule("root metadata test as namespace UID 0: nested UID map refused", Verdict.ALLOWED,
			"the host refuses a nested UID map for namespace UID 0; the test's caller-identity pass still runs"));
		rules.add(rule("requires a remote test environment", Verdict.ALLOWED,
			"upstream's Bazel jobs run no remote test environment either; only its x64 nextest job does", null,
			runners(Runner.BAZEL), null));
		rules.add(rule("requires a remote test environment", Verdict.ALLOWED,
			"upstream starts the Docker remote environment only in its Linux nextest jobs, never on Windows or macOS",
			platforms(Platform.WINDOWS, Platform.MACOS), null, null));
		rules.add(rule("requires a remote test environment", Verdict.ALLOWED,
			"this run selected no remote environment, as upstream's local-environment test runs do; " +
				"the remote run of the same archive executes these tests and fails on this skip",
			null, runners(Runner.NEXTEST), environments(Environment.LOCAL)));
		rules.add(rule("requires a remote test environment", Verdict.MISSING_SETUP,
			"remote test environment (Docker exec-server) was not set up"));
		rules.add(rule("no protected rg is available", Verdict.MISSING_SETUP, "ripgrep is not installed"));
		rules.add(rule("zsh", Verdict.MISSING_SETUP, "zsh is not installed"));
		rules.add(rule("Bash or Zsh executor", Verdict.MISSING_SETUP, "zsh is not installed"));
		rules.add(rule("python3? (?:not found|is unavailable)|python not found", Verdict.MISSING_SETUP,
			"python is not installed", platforms(Platform.LINUX, Platform.MACOS), null, null));
		rules.add(rule("git or python3 is unavailable", Verdict.MISSING_SETUP, "git or python3 is not installed"));
		// Listed before the generic bubblewrap rule below, which would classify this deliberate skip as missing setup.
		rules.add(rule("skipping system bwrap fallback test: bundled binaries require a release digest",
			Verdict.ALLOWED, "upstream's managed_proxy test skips whenever CODEX_BWRAP_SHA256 is a real nonzero digest, " +
			"which Bazel builds provide; such a build bundles bwrap and never takes the system-bwrap fallback"));
		rules.add(rule("bwrap|bubblewrap|user/mount namespaces|unprivileged user namespaces|/dev/shm|" +
			"unshare is unavailable|namespaced proc mount|sandbox proc is unavailable|skipping sandbox test",
			Verdict.MISSING_SETUP, "bubblewrap, user namespaces or another Linux sandbox prerequisite is missing",
			platforms(Platform.LINUX), null, null));
		rules.add(rule("managed proxy", Verdict.MISSING_SETUP,
			"managed-proxy prerequisite (bubblewrap, python3 or a socket) is missing",
			platforms(Platform.LINUX), null, null));
		rules.add(rule("(?:binary|server|helper)[^,;]* (?:not available|not found|unavailable)|could not locate binary",
			Verdict.MISSING_SETUP, "a helper binary or test server was not built or exported"));
		rules.add(rule("native spawning is unavailable", Verdict.MISSING_SETUP, "native process spawning is unavailable"));
		rules.add(rule("setsid not available", Verdict.ALLOWED,
			"setsid does not exist on Windows; the test reports itself unavailable",
			platforms(Platform.WINDOWS), null, null));
		rules.add(rule("setsid not available", Verdict.MISSING_SETUP, "setsid is not installed"));
		rules.add(rule("Skipping test in a remote test environment", Verdict.ALLOWED,
			"skip_if_remote: the test cannot run against the remote environment and is deliberately skipped there"));
		rules.add(rule("Skipping test in the Wine-exec test environment", Verdict.ALLOWED,
			"skip_if_wine_exec: Wine-exec exists only under Bazel and the test is excluded there by design"));
		rules.add(rule("Skipping test in a Windows target environment", Verdict.ALLOWED,
			"skip_if_target_windows: the test does not apply to a Windows target"));
		rules.add(rule("network is disabled", Verdict.ALLOWED,
			"skip_if_no_network: set only when Codex sandboxes itself, which CI does not do"));
		rules.add(rule("is set to 'seatbelt'", Verdict.ALLOWED,
			"skip_if_sandbox: set only when Codex sandboxes itself, which CI does not do"));
		rules.add(rule("OPENAI_API_KEY not set", Verdict.ALLOWED, "live-API tests need a key that CI never has"));
		rules.add(rule("tmux is unavailable", Verdict.ALLOWED, "the resize smoke test is #[ignore]d upstream"));
		rules.add(rule("Skipping test because it cannot execute on Windows", Verdict.ALLOWED,
			"test does not apply to Windows"));
		rules.add(rule("Linux symlink grant regression on another host OS", Verdict.ALLOWED,
			"test applies only to a Linux host"));
		rules.add(rule("no alternate supplementary group", Verdict.ALLOWED,
			"the host has no alternate supplementary group for this test"));
		rules.add(rule("Landlock is not enforceable|the Linux sandbox could not run the read-only probe", Verdict.ALLOWED,
			"the runner kernel does not enforce Landlock; no installable package changes that"));
		rules.add(rule("procfs is unavailable|pidfd_open is unavailable", Verdict.ALLOWED,
			"host kernel feature is unavailable; the skip is logged for review"));
		rules.add(rule("nested Seatbelt (?:is )?unavailable", Verdict.ALLOWED,
			"the test runs inside an outer Seatbelt sandbox and cannot nest another", platforms(Platform.MACOS), null, null));
		rules.add(rule("Windows symlink privilege unavailable|Windows symlink apply_patch sandbox test|" +
			"cannot create restricted tokens|native MXC is unavailable",
			Verdict.ALLOWED, "the hosted Windows runner lacks the privilege or component the test needs",
			platforms(Platform.WINDOWS), null, null));
		return List.copyOf(rules);
	}
}
