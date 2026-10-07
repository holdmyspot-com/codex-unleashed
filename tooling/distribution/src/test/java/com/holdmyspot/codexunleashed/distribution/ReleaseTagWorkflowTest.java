package com.holdmyspot.codexunleashed.distribution;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Executes the maintained release source reservation step against a local HTTP service.
 */
public final class ReleaseTagWorkflowTest
{
	private static final String SOURCE_SHA = "a".repeat(40);
	private static final String VENDOR_TAG = "rust-v0.159.1+23";

	/**
	 * Creates the workflow tests.
	 */
	public ReleaseTagWorkflowTest()
	{
	}

	/**
	 * Verifies immutable publication handoff, failure output, concurrency, and lost-response retry.
	 * The maintained step receives its supported API-base override for the local service.
	 *
	 * @throws IOException if runtime linking, fixture access, HTTP serving, or startup fails
	 * @throws InterruptedException if the workflow wait is interrupted
	 * @throws URISyntaxException if a fixture class or API location is invalid
	 */
	@Test
	public void reservesPublicationSource() throws IOException, InterruptedException, URISyntaxException
	{
		Path root = Files.createTempDirectory("release-tag-workflow-");
		try
		{
			Path image = root.resolve("runtime with spaces");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), image.toString()});
			Path adapters = Files.createDirectory(root.resolve("adapters"));
			JavaCommandFixtures.writeLauncher(adapters.resolve("python3"), RejectedCommandFixture.class);
			String command = WorkflowCommands.readStepCommand("prepare", "Reserve and verify release source tag").
				replace(" >> \"$GITHUB_OUTPUT\"", " --api-base \"$RELEASE_API_TEST_BASE\" >> \"$GITHUB_OUTPUT\"");
			AtomicReference<String> mode = new AtomicReference<>();
			AtomicBoolean stored = new AtomicBoolean();
			AtomicInteger posts = new AtomicInteger();
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			server.createContext("/", exchange -> respond(exchange, mode.get(), stored, posts));
			server.start();
			try
			{
				URI endpoint = new URI("http", null, server.getAddress().getAddress().getHostAddress(),
					server.getAddress().getPort(), "/", null, null);
				Path output = root.resolve("workflow output");
				Path log = root.resolve("workflow.log");
				ProcessBuilder builder = NativeCommands.createBuilder("bash", "-eu", "-c", command);
				builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
				builder.environment().put("PATH", adapters.toString());
				builder.environment().put("CODEX_UNLEASHED_TOOLING", image.resolve("bin/codex-tooling").toString());
				builder.environment().put("GH_TOKEN", "fixture-token");
				builder.environment().put("GITHUB_REPOSITORY", "owner/repo");
				builder.environment().put("RELEASE_TAG", VENDOR_TAG);
				builder.environment().put("ARTIFACT_RUN_ID", "7");
				builder.environment().put("GITHUB_OUTPUT", output.toString());
				builder.environment().put("RELEASE_API_TEST_BASE", endpoint.toString());
				builder.environment().put("TMPDIR", root.toString());
				for (String scenario : new String[]{"create", "current", "race", "conflict", "forbidden",
					"wrong-workflow", "lost"})
				{
					mode.set(scenario);
					stored.set("current".equals(scenario) || "conflict".equals(scenario));
					posts.set(0);
					Files.writeString(output, "existing=true\n");
					int expectedStatus = 1;
					if ("create".equals(scenario) || "current".equals(scenario) || "race".equals(scenario))
						expectedStatus = 0;
					assertEquals(run(builder), expectedStatus, scenario + ": " + Files.readString(log));
					String expectedOutput = "existing=true\n";
					if (expectedStatus == 0)
						expectedOutput = "existing=true\n" + provenance();
					assertEquals(Files.readString(output), expectedOutput, scenario);
					if ("create".equals(scenario) || "race".equals(scenario))
						assertEquals(posts.get(), 1);
					else if (!"lost".equals(scenario))
						assertEquals(posts.get(), 0);
					if ("lost".equals(scenario))
					{
						assertTrue(stored.get(), "The failed response must follow durable tag creation");
						int attempts = posts.get();
						assertEquals(run(builder), 0, Files.readString(log));
						assertEquals(Files.readString(output), "existing=true\n" + provenance());
						assertEquals(posts.get(), attempts, "Retry must verify the stored tag without another creation");
					}
				}
			}
			finally
			{
				server.stop(0);
			}
		}
		finally
		{
			try (Stream<Path> files = Files.walk(root))
			{
				for (Path file : files.sorted(Comparator.reverseOrder()).toList())
					Files.delete(file);
			}
		}
	}

	/**
	 * Supplies controlled Actions metadata and immutable Git reference responses.
	 *
	 * @param exchange the current HTTP exchange
	 * @param mode the scenario
	 * @param stored whether the immutable reference exists
	 * @param posts the number of creation requests
	 * @throws IOException if the HTTP response cannot be written
	 */
	private static void respond(HttpExchange exchange, String mode, AtomicBoolean stored, AtomicInteger posts)
		throws IOException
	{
		try (exchange)
		{
			String path = exchange.getRequestURI().getPath();
			String sha = SOURCE_SHA;
			if ("conflict".equals(mode))
				sha = "b".repeat(40);
			String response = "{\"object\":{\"type\":\"commit\",\"sha\":\"" + sha + "\"}}";
			int status = 200;
			if (path.equals("/repos/owner/repo/actions/runs/7"))
			{
				String workflow = ".github/workflows/build-release.yml";
				if ("wrong-workflow".equals(mode))
					workflow = "other.yml";
				response = "{\"repository\":{\"full_name\":\"owner/repo\"},\"path\":\"" + workflow +
					"\",\"head_sha\":\"" + SOURCE_SHA + "\",\"head_branch\":\"release/topic\",\"run_attempt\":3}";
			}
			else if ("POST".equals(exchange.getRequestMethod()) && path.equals("/repos/owner/repo/git/refs"))
			{
				posts.incrementAndGet();
				exchange.getRequestBody().readAllBytes();
				boolean created = stored.compareAndSet(false, true);
				status = 422;
				if (created && !"race".equals(mode))
					status = 201;
				if (created && "lost".equals(mode))
					return;
			}
			else if (path.equals("/repos/owner/repo/git/ref/tags/" + VENDOR_TAG))
			{
				if ("forbidden".equals(mode))
					status = 403;
				else if (!stored.get())
					status = 404;
			}
			else
				status = 404;
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
		}
	}

	/**
	 * Formats the expected original build's provenance.
	 *
	 * @return the exact handoff lines
	 */
	private static String provenance()
	{
		return ("source_sha=" + SOURCE_SHA + "\nsource_ref=refs/heads/release/topic\nsource_run_attempt=3\n").
			replace("\n", System.lineSeparator());
	}

	/**
	 * Captures the maintained command's terminal outcome and closes its process resources.
	 *
	 * @param builder the configured workflow command
	 * @return the completed exit status
	 * @throws IOException if startup fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static int run(ProcessBuilder builder) throws IOException, InterruptedException
	{
		try (Process process = builder.start())
		{
			assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Release tag workflow timed out");
			return process.exitValue();
		}
	}
}
