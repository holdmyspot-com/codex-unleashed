package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Tests production identity/leader decisions and exact routing, without claiming native ownership acceptance. */
public final class AllocationHooksTest
{
	/** Native-protocol receipt correlation, reused for controlled real-codec envelopes. */
	static final UUID IDENTITY = UUID.fromString("8e24a611-ec3e-486e-b4d2-261f85599c47");
	/** Creates isolated decision tests. */
	public AllocationHooksTest() {}

	/** Missing, duplicate or partially different credentials cannot count as verified identity. */
	@Test
	public void requiresCompleteCredentials()
	{
		requireThat(AllocationHooks.credentialsMatch("Uid: 2 2 2 2\nGid: 3 3 3 3\n", 2, 3), "complete").isEqualTo(true, "expected");
		for (String invalid : List.of("", "Uid: 2 2 2 2\n", "Uid: 2 2 2\nGid: 3 3 3 3",
			"Uid: 2 2 2 9\nGid: 3 3 3 3", "Uid: 2 2 2 2\nUid: 2 2 2 2\nGid: 3 3 3 3"))
			requireThat(AllocationHooks.credentialsMatch(invalid, 2, 3), "incomplete").isEqualTo(false, "expected");
	}

	/** Root correlation is exact and unsupported fork inheritance stays held.
	 * @throws IOException if valid root/known origin is rejected
	 */
	@Test
	public void validatesRootAndRejectsForkInheritance() throws IOException
	{
		AllocationHooks.verifyRootEvent(new FridaRuntime.Child(5, 5, 1, "/worker"), 5, "/worker");
		for (var event : List.of(new FridaRuntime.Child(6, 5, 1, "/worker"),
			new FridaRuntime.Child(5, 6, 1, "/worker"), new FridaRuntime.Child(5, 5, 0, "/worker"),
			new FridaRuntime.Child(5, 5, 1, "/different")))
			requireException(IOException.class, () -> AllocationHooks.verifyRootEvent(event, 5, "/worker"));
		requireException(IOException.class, () -> AllocationHooks.verifyOrigin(0));
		requireException(IOException.class, () -> AllocationHooks.verifyOrigin(9));
		AllocationHooks.verifyOrigin(1);
		AllocationHooks.verifyOrigin(2);
	}

	/** Proc command parentheses/spaces cannot shift start-time identity.
	 * @throws IOException if valid stat is rejected
	 */
	@Test
	public void parsesStableStartIdentity() throws IOException
	{
		String stat = "5 (a ) name) S " + "0 ".repeat(18) + "42 0";
		requireThat(AllocationHooks.startTime(stat), "startTicks").isEqualTo(42L, "expected");
		requireException(IOException.class, () -> AllocationHooks.startTime("missing"));
		requireException(IOException.class, () -> AllocationHooks.startTime("5 (a) S 1"));
	}

	/** Embedding preserves argv/environment values without executing shell syntax.
	 * @throws IOException if valid marker/JSON is rejected
	 */
	@Test
	public void preservesLeaderArguments() throws IOException
	{
		var launch = launch(List.of("/worker", "a b", "quote\"", "é", ""), Map.of("VALUE", "$(x)\n'\""));
		String result = AllocationHooks.leaderProgram("__LAUNCH__", launch);
		JsonNode node = JsonMapper.builder().build().readTree(result);
		requireThat(node.path("command").get(1).asString(), "space").isEqualTo("a b", "expected");
		requireThat(node.path("command").get(4).asString(), "empty").isEqualTo("", "expected");
		requireThat(node.path("environment").path("VALUE").asString(), "literalEnvironment").isEqualTo("$(x)\n'\"", "expected");
		requireException(IOException.class, () -> AllocationHooks.leaderProgram("", launch));
		requireException(IOException.class, () -> AllocationHooks.leaderProgram("__LAUNCH____LAUNCH__", launch));
	}

	/** New routing never posts to another generation or resends an uncertain permission.
	 * @throws IOException if valid receipt transitions fail
	 */
	@Test
	public void routesExactGenerationWithoutResend() throws IOException
	{
		Connection connection = new Connection();
		AllocationWaits waits = new AllocationWaits();
		AllocationHook hook = ready(connection, waits);
		try
		{
			connection.frames.add(frame("blocked", 1));
			hook.pump();
			requireException(IOException.class, () -> AllocationHooks.installation(Map.of(1L, hook), new AllocationWaits.Wait(2, 1)));
			requireThat(connection.posts.size(), "wrongGenerationNoPost").isEqualTo(0, "expected");
			connection.postFailure = new IOException("Retained uncertain post");
			requireException(IOException.class, () -> AllocationHooks.installation(Map.of(1L, hook),
				new AllocationWaits.Wait(1, 1)).grant(new AllocationWaits.Wait(1, 1), () -> {}));
			requireException(IOException.class, () -> hook.grant(new AllocationWaits.Wait(1, 1), () -> {}));
			requireThat(connection.posts.size(), "noResend").isEqualTo(1, "expected");
		}
		finally { hook.close(() -> true); }
	}

	/** Creates immutable resource-free launch inputs.
	 * @param argv workload arguments
	 * @param environment workload environment
	 * @return configured attached launch
	 */
	static LinuxProcessTree.Launch launch(List<String> argv, Map<String, String> environment)
	{
		return new LinuxProcessTree.Launch(Path.of("/groups"), Path.of("/scratch"), Path.of("/java"),
			Path.of("/tool.jar"), argv, environment, Path.of("/work"), 2, 3,
			LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC);
	}

	/** Starts the real hook consumer over receipt-shaped copied frames.
	 * @param connection copied-frame transport
	 * @param waits actual batch owner
	 * @return ready hook owned by caller
	 * @throws IOException if real readiness fails
	 */
	static AllocationHook ready(Connection connection, AllocationWaits waits) throws IOException
	{
		AllocationHook hook = new AllocationHook(IDENTITY, 1, 3216, connection, waits, "", Duration.ofSeconds(1), System::nanoTime);
		connection.frames.add(frame("ready", 0));
		hook.start();
		hook.awaitReady(() -> {});
		return hook;
	}

	/** Produces accepted native-envelope-shaped messages for the actual codec.
	 * @param event ready/blocked/granted
	 * @param sequence native wait ordinal
	 * @return complete copied envelope
	 */
	static String frame(String event, long sequence)
	{
		String tail = switch (event)
		{
			case "blocked" -> ",\"wait\":\"" + sequence + "\",\"thread\":\"3262\",\"bytes\":\"268435456\",\"error\":\"12\"";
			case "granted" -> ",\"wait\":\"" + sequence + "\",\"status\":\"accepted\"";
			default -> "";
		};
		return "{\"type\":\"send\",\"payload\":{\"protocol\":\"codex-build-allocation\",\"version\":1,\"attempt\":\"" +
			IDENTITY + "\",\"installation\":\"1\",\"pid\":\"3216\",\"event\":\"" + event + "\"" + tail + "}}";
	}

	/** Copied-frame boundary only; never models actual process lifetime. */
	static final class Connection implements AllocationHook.Connection
	{
		/** Copied native messages. */
		final ArrayDeque<String> frames = new ArrayDeque<>();
		/** Actual generated controls. */
		final List<JsonNode> posts = new ArrayList<>();
		/** Receipt-derived acknowledgement reaction. */
		Consumer<JsonNode> onPost = _ -> {};
		/** Optional uncertain post after recording the attempted send. */
		IOException postFailure;
		/** Creates a resource-free transport. */
		Connection() {}
		@Override public void start(String source) {}
		@Override public Optional<String> pollMessage() { return Optional.ofNullable(frames.pollFirst()); }
		@Override public void post(JsonNode permission) throws IOException
		{
			posts.add(permission);
			if (postFailure != null) throw postFailure;
			onPost.accept(permission);
		}
		@Override public void close() {}
	}
}
