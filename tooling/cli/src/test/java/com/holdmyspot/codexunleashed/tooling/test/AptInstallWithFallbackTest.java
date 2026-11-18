package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.AptInstallWithFallback;
import com.holdmyspot.codexunleashed.tooling.AptInstallWithFallback.AptProcessRequest;
import com.holdmyspot.codexunleashed.tooling.AptInstallWithFallback.AptProcessResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies apt command selection and source fallback using in-memory process results and owned apt source files.
 */
public final class AptInstallWithFallbackTest
{
	/**
	 * Creates the TestNG test fixture instance.
	 */
	public AptInstallWithFallbackTest()
	{
	}

	/**
	 * A healthy apt update runs one install command with the requested packages and noninteractive environment.
	 *
	 * @throws IOException if the in-memory process collaborator fails
	 */
	@Test
	public void healthyRunsUpdateThenInstall() throws IOException
	{
		Path aptDirectory = copyFixture("x64");
		List<AptProcessRequest> requests = new ArrayList<>();
		ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
		try
		{
			String ubuntuSources = Files.readString(aptDirectory.resolve("sources.list.d/ubuntu.sources"));
			String mirrorList = Files.readString(aptDirectory.resolve("apt-mirrors.txt"));
			String microsoftSources = Files.readString(aptDirectory.resolve("sources.list.d/microsoft.sources"));
			try (PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8))
			{
				int status = AptInstallWithFallback.run(new String[] { "--apt-dir", aptDirectory.toString(), "--apt-get",
					"apt-get-fixture", "curl", "git" }, err, request ->
				{
					requests.add(request);
					return new AptProcessResult(0, "", "");
				});
			requireThat(status, "status").isEqualTo(0);
		}

			requireThat(requests.stream().map(AptProcessRequest::command).toList(), "commands").isEqualTo(List.of(
				List.of("apt-get-fixture", "update", "-y"),
				List.of("apt-get-fixture", "install", "-y", "--no-install-recommends", "curl", "git")));
			requireThat(requests.stream().map(AptProcessRequest::outputHandling).toList(), "outputHandling").
				isEqualTo(List.of(AptInstallWithFallback.OutputHandling.INHERIT,
					AptInstallWithFallback.OutputHandling.INHERIT));
			requireThat(requests.get(0).environmentOverrides(), "updateEnvironment").
				isEqualTo(Map.of("DEBIAN_FRONTEND", "noninteractive"));
			requireThat(requests.get(1).environmentOverrides(), "installEnvironment").
				isEqualTo(Map.of("DEBIAN_FRONTEND", "noninteractive"));
			requireThat(Files.readString(aptDirectory.resolve("sources.list.d/ubuntu.sources")), "ubuntuSources").
				isEqualTo(ubuntuSources);
			requireThat(Files.readString(aptDirectory.resolve("apt-mirrors.txt")), "mirrorList").isEqualTo(mirrorList);
			requireThat(Files.readString(aptDirectory.resolve("sources.list.d/microsoft.sources")), "microsoftSources").
				isEqualTo(microsoftSources);
			requireThat(diagnostics.toString(StandardCharsets.UTF_8), "diagnostics").isEmpty();
		}
		finally
		{
			deleteFixture(aptDirectory);
		}
	}

	/**
	 * A failed x64 apt install changes Ubuntu source URIs, preserves other source content, then retries once.
	 *
	 * @throws IOException if an owned apt fixture cannot be read or cleaned up
	 */
	@Test
	public void x64FailureChangesUbuntuUrisAndRetries() throws IOException
	{
		Path aptDirectory = copyFixture("x64");
		List<AptProcessRequest> requests = new ArrayList<>();
		Deque<AptProcessResult> results = new ArrayDeque<>(List.of(
			new AptProcessResult(0, "", ""),
			new AptProcessResult(100, "", ""),
			new AptProcessResult(0, "", ""),
			new AptProcessResult(0, "", "")));
		String microsoftSources = Files.readString(aptDirectory.resolve("sources.list.d/microsoft.sources"));
		ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
		try
		{
			try (PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8))
			{
				int status = AptInstallWithFallback.run(new String[] { "--apt-dir", aptDirectory.toString(),
					"--architecture", "amd64", "--apt-get", "apt-get-fixture", "zsh", "bubblewrap" }, err,
					request -> runScriptedProcess(request, requests, results));
				requireThat(status, "status").isEqualTo(0);
			}

			requireThat(requests.stream().map(AptProcessRequest::command).toList(), "commands").isEqualTo(List.of(
				List.of("apt-get-fixture", "update", "-y"),
				List.of("apt-get-fixture", "install", "-y", "--no-install-recommends", "zsh", "bubblewrap"),
				List.of("apt-get-fixture", "update", "-y"),
				List.of("apt-get-fixture", "install", "-y", "--no-install-recommends", "zsh", "bubblewrap")));
			String changedSources = Files.readString(aptDirectory.resolve("sources.list.d/ubuntu.sources"));
			requireThat(changedSources, "ubuntuSources").contains(
				"URIs: https://mirrors.edge.kernel.org/ubuntu/");
			requireThat(changedSources, "ubuntuSources").contains(
				"Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg");
			requireThat(changedSources, "ubuntuSources").doesNotContain("mirror+file:");
			requireThat(Files.readString(aptDirectory.resolve("sources.list.d/microsoft.sources")), "microsoftSources").
				isEqualTo(microsoftSources);
			requireThat(diagnostics.toString(StandardCharsets.UTF_8), "diagnostics").contains(
				"apt-get failed with exit code 100; retrying from https://mirrors.edge.kernel.org/ubuntu/");
			requireThat(results.isEmpty(), "unconsumedResults").isTrue();
		}
		finally
		{
			deleteFixture(aptDirectory);
		}
	}

	/**
	 * A ports source uses the ports mirror, and the retry's failing apt status reaches the caller unchanged.
	 *
	 * @throws IOException if an owned apt fixture cannot be read or cleaned up
	 */
	@Test
	public void portsFailureRetriesAndReturnsStatus() throws IOException
	{
		Path aptDirectory = copyFixture("ports");
		List<AptProcessRequest> requests = new ArrayList<>();
		Deque<AptProcessResult> results = new ArrayDeque<>(List.of(
			new AptProcessResult(0, "", ""),
			new AptProcessResult(100, "", ""),
			new AptProcessResult(0, "", ""),
			new AptProcessResult(100, "", "")));
		try
		{
			int status = run(aptDirectory, new String[] { "--architecture", "arm64", "zsh" }, requests, results,
				null);
			requireThat(status, "status").isEqualTo(100);
			requireThat(Files.readString(aptDirectory.resolve("sources.list.d/ubuntu.sources")), "ubuntuSources").
				contains("URIs: https://mirrors.ocf.berkeley.edu/ubuntu-ports/");
			requireThat(requests.size(), "commandCount").isEqualTo(4);
			requireThat(results.isEmpty(), "unconsumedResults").isTrue();
		}
		finally
		{
			deleteFixture(aptDirectory);
		}
	}

	/**
	 * Unsupported Ubuntu source layouts preserve the first apt failure and do not issue a retry.
	 *
	 * @throws IOException if an owned apt fixture cannot be read, changed or cleaned up
	 */
	@Test
	public void unsupportedSourcesDoNotRetry() throws IOException
	{
		Path aptDirectory = copyFixture("ports");
		List<AptProcessRequest> requests = new ArrayList<>();
		Deque<AptProcessResult> results = new ArrayDeque<>(List.of(
			new AptProcessResult(0, "", ""), new AptProcessResult(100, "", "")));
		Path ubuntuSources = aptDirectory.resolve("sources.list.d/ubuntu.sources");
		String unsupported = Files.readString(ubuntuSources).replace("http://ports.ubuntu.com/ubuntu-ports",
			"http://example.com/ubuntu");
		Files.writeString(ubuntuSources, unsupported);
		ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
		try
		{
			int status;
			try (PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8))
			{
				status = AptInstallWithFallback.run(new String[] { "--apt-dir", aptDirectory.toString(), "--architecture",
					"arm64", "--apt-get", "apt-get-fixture", "zsh" }, err,
					request -> runScriptedProcess(request, requests, results));
			}
			requireThat(status, "status").isEqualTo(100);
			requireThat(requests.size(), "commandCount").isEqualTo(2);
			requireThat(diagnostics.toString(StandardCharsets.UTF_8), "diagnostics").contains(
				"no Ubuntu archive source was found");
			requireThat(Files.readString(ubuntuSources), "ubuntuSources").isEqualTo(unsupported);
			requireThat(results.isEmpty(), "unconsumedResults").isTrue();
		}
		finally
		{
			deleteFixture(aptDirectory);
		}
	}

	/**
	 * A failed update skips installation in that attempt before the source change and retry.
	 *
	 * @throws IOException if an owned apt fixture cannot be read or cleaned up
	 */
	@Test
	public void failedUpdateRetriesWithoutInstall() throws IOException
	{
		Path aptDirectory = copyFixture("x64");
		List<AptProcessRequest> requests = new ArrayList<>();
		Deque<AptProcessResult> results = new ArrayDeque<>(List.of(
			new AptProcessResult(100, "", ""), new AptProcessResult(100, "", "")));
		try
		{
			int status = run(aptDirectory, new String[] { "--architecture", "amd64", "zsh" }, requests, results,
				null);
			requireThat(status, "status").isEqualTo(100);
			requireThat(requests.stream().map(AptProcessRequest::command).toList(), "commands").isEqualTo(List.of(
				List.of("apt-get", "update", "-y"), List.of("apt-get", "update", "-y")));
			requireThat(results.isEmpty(), "unconsumedResults").isTrue();
		}
		finally
		{
			deleteFixture(aptDirectory);
		}
	}

	/**
	 * When architecture is omitted, apt failure causes dpkg to run before the source mirror is selected.
	 *
	 * @throws IOException if an owned apt fixture cannot be read or cleaned up
	 */
	@Test
	public void architectureIsReadAfterPrimaryFailure() throws IOException
	{
		Path aptDirectory = copyFixture("ports");
		List<AptProcessRequest> requests = new ArrayList<>();
		Deque<AptProcessResult> results = new ArrayDeque<>(List.of(
			new AptProcessResult(0, "", ""),
			new AptProcessResult(100, "", ""),
			new AptProcessResult(0, "arm64\n", ""),
			new AptProcessResult(0, "", ""),
			new AptProcessResult(0, "", "")));
		try
		{
			int status = run(aptDirectory, new String[] { "zsh" }, requests, results, null);
			requireThat(status, "status").isEqualTo(0);
			requireThat(requests.stream().map(AptProcessRequest::command).toList(), "commands").isEqualTo(List.of(
				List.of("apt-get", "update", "-y"),
				List.of("apt-get", "install", "-y", "--no-install-recommends", "zsh"),
				List.of("dpkg", "--print-architecture"),
				List.of("apt-get", "update", "-y"),
				List.of("apt-get", "install", "-y", "--no-install-recommends", "zsh")));
			requireThat(requests.get(2).outputHandling(), "architectureOutput").
				isEqualTo(AptInstallWithFallback.OutputHandling.CAPTURE);
			requireThat(Files.readString(aptDirectory.resolve("sources.list.d/ubuntu.sources")), "ubuntuSources").
				contains("URIs: https://mirrors.ocf.berkeley.edu/ubuntu-ports/");
			requireThat(results.isEmpty(), "unconsumedResults").isTrue();
		}
		finally
		{
			deleteFixture(aptDirectory);
		}
	}

	/**
	 * Runs one scripted process request and records the request that production sent.
	 *
	 * @param request the apt or dpkg request
	 * @param requests the requests made by production
	 * @param results expected apt or dpkg results in request order
	 * @return the next scripted process result
	 */
	private static AptProcessResult runScriptedProcess(AptProcessRequest request, List<AptProcessRequest> requests,
		Deque<AptProcessResult> results)
	{
		// This in-memory AptProcess represents apt-get and dpkg. Status 100 matches the Python compatibility test's
		// failed install; production reads it to decide whether to change sources and retry.
		requests.add(request);
		return results.removeFirst();
	}

	/**
	 * Runs the production apt command with a local source directory and scripted apt and dpkg results.
	 *
	 * @param aptDirectory the temporary source configuration root
	 * @param arguments command options followed by package names
	 * @param requests records the requests made by production
	 * @param results scripted apt and dpkg outcomes
	 * @param diagnostics optional diagnostic capture
	 * @return the command status
	 * @throws IOException if the apt command reports an IO failure
	 */
	private static int run(Path aptDirectory, String[] arguments, List<AptProcessRequest> requests,
		Deque<AptProcessResult> results, ByteArrayOutputStream diagnostics) throws IOException
	{
		ByteArrayOutputStream errBytes;
		if (diagnostics == null)
			errBytes = new ByteArrayOutputStream();
		else
			errBytes = diagnostics;
		try (PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8))
		{
			List<String> commandArguments = new ArrayList<>(List.of("--apt-dir", aptDirectory.toString()));
			commandArguments.addAll(List.of(arguments));
			return AptInstallWithFallback.run(commandArguments.toArray(String[]::new), err,
				request -> runScriptedProcess(request, requests, results));
		}
	}

	/**
	 * Copies an owned apt source fixture into a temporary apt directory and removes partial setup on failure.
	 *
	 * @param layout source layout fixture name
	 * @return the temporary apt directory owned by the calling test
	 * @throws IOException if a fixture cannot be copied
	 */
	private static Path copyFixture(String layout) throws IOException
	{
		Path aptDirectory = Files.createTempDirectory("apt-fallback-");
		try
		{
			copyResource("apt-fallback/" + layout + "/sources.list.d/ubuntu.sources",
				aptDirectory.resolve("sources.list.d/ubuntu.sources"));
			copyResource("apt-fallback/" + layout + "/sources.list.d/microsoft.sources",
				aptDirectory.resolve("sources.list.d/microsoft.sources"));
			if (layout.equals("x64"))
				copyResource("apt-fallback/x64/apt-mirrors.txt", aptDirectory.resolve("apt-mirrors.txt"));
			return aptDirectory;
		}
		catch (IOException | RuntimeException failure)
		{
			deleteFixture(aptDirectory);
			throw failure;
		}
	}

	/**
	 * Copies one fixture resource to its owned temporary path.
	 *
	 * @param resource fixture path relative to this test package
	 * @param destination path under the temporary apt directory
	 * @throws IOException if the resource is absent or cannot be copied
	 */
	private static void copyResource(String resource, Path destination) throws IOException
	{
		Files.createDirectories(destination.getParent());
		try (InputStream input = AptInstallWithFallbackTest.class.getResourceAsStream(resource))
		{
			if (input == null)
				throw new IOException("Missing apt fixture resource " + resource);
			Files.copy(input, destination);
		}
	}

	/**
	 * Removes only the temporary apt directory created by {@link #copyFixture}.
	 *
	 * @param aptDirectory owned temporary apt directory
	 * @throws IOException if an owned fixture cannot be deleted
	 */
	private static void deleteFixture(Path aptDirectory) throws IOException
	{
		if (Files.notExists(aptDirectory))
			return;
		Files.walkFileTree(aptDirectory, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException
			{
				if (failure != null)
					throw failure;
				Files.delete(directory);
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
