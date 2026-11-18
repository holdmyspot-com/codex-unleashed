package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.CleanupOwnedCacheFixtures;
import com.holdmyspot.codexunleashed.tooling.CleanupOwnedCacheFixtures.Version;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import static com.holdmyspot.codexunleashed.tooling.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies that only the exact recorded version, digest and aliases permit cleanup.
 */
public final class CleanupOwnedCacheFixturesTest
{
	/**
	 * The digest of the recorded manifest.
	 */
	private static final String DIGEST = "sha256:" + "a".repeat(64);
	/**
	 * The recorded fixture.
	 */
	private static final Version EXPECTED = new Version(7, DIGEST, List.of("fixture-partial", "fixture-release"));

	/**
	 * Creates the tests.
	 */
	public CleanupOwnedCacheFixturesTest()
	{
	}

	/**
	 * The recorded manifest and aliases pass the deletion prerequisite.
	 */
	@Test
	public void acceptsMatchingVersion()
	{
		CleanupOwnedCacheFixtures.verifyVersion(EXPECTED, new Version(7, DIGEST, EXPECTED.tags()));
	}

	/**
	 * The registry's alias ordering does not reject the same complete alias set.
	 */
	@Test
	public void ignoresAliasOrder()
	{
		CleanupOwnedCacheFixtures.verifyVersion(EXPECTED,
			new Version(7, DIGEST, List.of("fixture-release", "fixture-partial")));
	}

	/**
	 * Another version ID cannot inherit the recorded fixture's authorization.
	 */
	@Test
	public void refusesDifferentVersion()
	{
		requireException(IllegalArgumentException.class,
			() -> CleanupOwnedCacheFixtures.verifyVersion(EXPECTED, new Version(8, DIGEST, EXPECTED.tags())));
	}

	/**
	 * A changed digest cannot use the recorded version's cleanup request.
	 */
	@Test
	public void refusesChangedManifest()
	{
		requireException(IllegalArgumentException.class, () -> CleanupOwnedCacheFixtures.verifyVersion(EXPECTED,
			new Version(7, "sha256:" + "b".repeat(64), EXPECTED.tags())));
	}

	/**
	 * An additional alias prevents deleting a version that another cache may use.
	 */
	@Test
	public void refusesAdditionalAlias()
	{
		requireException(IllegalArgumentException.class, () -> CleanupOwnedCacheFixtures.verifyVersion(EXPECTED,
			new Version(7, DIGEST, List.of("fixture-partial", "fixture-release", "another-cache"))));
	}

	/**
	 * A version that lacks its recorded release alias requires investigation.
	 */
	@Test
	public void refusesMissingAlias()
	{
		requireException(IllegalArgumentException.class, () -> CleanupOwnedCacheFixtures.verifyVersion(EXPECTED,
			new Version(7, DIGEST, List.of("fixture-partial"))));
	}

	/**
	 * The listing yields only the matching recorded IDs, ignoring unrelated and absent versions.
	 */
	@Test
	public void approvesOnlyPresentOwnedVersions()
	{
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			JsonNode owned = scope.getJsonMapper().readTree(listed(7, DIGEST, "\"fixture-partial\",\"fixture-release\""));
			JsonNode unrelated = scope.getJsonMapper().readTree(listed(9, "unrelated", ""));
			requireThat(CleanupOwnedCacheFixtures.approvedVersions(List.of(EXPECTED), List.of(owned, unrelated)),
				"approved").isEqualTo(List.of(7L));
			requireThat(CleanupOwnedCacheFixtures.approvedVersions(List.of(EXPECTED), List.of(unrelated)), "absent").
				isEqualTo(List.of());
		}
	}

	/**
	 * A mismatched recorded manifest refuses the whole approval instead of returning the fixtures that matched.
	 */
	@Test
	public void mismatchRefusesEntireApproval()
	{
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			JsonNode changed = scope.getJsonMapper().readTree(
				listed(7, "changed", "\"fixture-partial\",\"fixture-release\""));
			requireException(IllegalArgumentException.class,
				() -> CleanupOwnedCacheFixtures.approvedVersions(List.of(EXPECTED), List.of(changed)));
		}
	}

	/**
	 * The command prints the approved IDs in the recorded order, whichever page lists them.
	 *
	 * @throws IOException if the fixture files cannot be created
	 */
	@Test
	public void commandPrintsApprovedIdsInRecordedOrder() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope(); TestDirectory directory = new TestDirectory("cleanup-"))
		{
			String request = "[" + recorded(7, DIGEST, "\"fixture-partial\"") + "," + recorded(5, DIGEST, "") + "," +
				recorded(6, DIGEST, "") + "]";
			String pages = "[[" + listed(5, DIGEST, "") + "," + listed(9, "unrelated", "") + "],[" +
				listed(7, DIGEST, "\"fixture-partial\"") + "]]";
			String[] arguments = {directory.write("request.json", request).toString(),
				directory.write("pages.json", pages).toString()};
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ByteArrayOutputStream err = new ByteArrayOutputStream();
			int status;
			try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
				PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8))
			{
				status = CleanupOwnedCacheFixtures.run(scope, arguments, outStream, errStream);
			}
			requireThat(status, "status").isEqualTo(0);
			requireThat(out.toString(StandardCharsets.UTF_8), "out").isEqualTo(
				"7" + System.lineSeparator() + "5" + System.lineSeparator());
			requireThat(err.toString(StandardCharsets.UTF_8), "err").isEqualTo("");
		}
	}

	/**
	 * The command prints nothing and fails when a present fixture no longer matches its record.
	 *
	 * @throws IOException if the fixture files cannot be created
	 */
	@Test
	public void commandPrintsNothingWhenAFixtureChanged() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope(); TestDirectory directory = new TestDirectory("cleanup-"))
		{
			String request = "[" + recorded(5, DIGEST, "") + "," + recorded(7, DIGEST, "\"fixture-partial\"") + "]";
			String pages = "[[" + listed(5, DIGEST, "") + "," + listed(7, "changed", "\"fixture-partial\"") + "]]";
			String[] arguments = {directory.write("request.json", request).toString(),
				directory.write("pages.json", pages).toString()};
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ByteArrayOutputStream err = new ByteArrayOutputStream();
			int status;
			try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
				PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8))
			{
				status = CleanupOwnedCacheFixtures.run(scope, arguments, outStream, errStream);
			}
			requireThat(status, "status").isEqualTo(1);
			requireThat(out.toString(StandardCharsets.UTF_8), "out").isEqualTo("");
			requireThat(err.toString(StandardCharsets.UTF_8), "err").contains("Package manifest differs: changed");
		}
	}

	/**
	 * The command fails instead of approving a listed fixture whose record lacks the alias list.
	 *
	 * @throws IOException if the fixture files cannot be created
	 */
	@Test
	public void failsWhenListedFixtureHasNoAliases() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope(); TestDirectory directory = new TestDirectory("cleanup-"))
		{
			String[] arguments = {directory.write("request.json", "[" + recorded(7, DIGEST, "") + "]").toString(),
				directory.write("pages.json", "[[{\"id\":7,\"name\":\"" + DIGEST + "\"}]]").toString()};
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ByteArrayOutputStream err = new ByteArrayOutputStream();
			int status;
			try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
				PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8))
			{
				status = CleanupOwnedCacheFixtures.run(scope, arguments, outStream, errStream);
			}
			requireThat(status, "status").isEqualTo(1);
			requireThat(out.toString(StandardCharsets.UTF_8), "out").isEqualTo("");
			requireThat(err.toString(StandardCharsets.UTF_8), "err").contains("tags of package version 7");
		}
	}

	/**
	 * The command rejects a wrong number of arguments.
	 */
	@Test
	public void commandRejectsWrongArgumentCount()
	{
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			ByteArrayOutputStream err = new ByteArrayOutputStream();
			int status;
			try (PrintStream outStream = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
				PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8))
			{
				status = CleanupOwnedCacheFixtures.run(scope, new String[]{"request.json"}, outStream, errStream);
			}
			requireThat(status, "status").isEqualTo(2);
			requireThat(err.toString(StandardCharsets.UTF_8), "err").contains("Usage: codex-tooling");
		}
	}

	/**
	 * Returns a recorded request entry.
	 *
	 * @param id   the version ID
	 * @param name the manifest digest
	 * @param tags the comma-separated JSON strings of the aliases
	 * @return the JSON
	 */
	private static String recorded(long id, String name, String tags)
	{
		return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"tags\":[" + tags + "]}";
	}

	/**
	 * Returns a version as the package listing reports it.
	 *
	 * @param id   the version ID
	 * @param name the manifest digest
	 * @param tags the comma-separated JSON strings of the aliases
	 * @return the JSON
	 */
	private static String listed(long id, String name, String tags)
	{
		return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"metadata\":{\"container\":{\"tags\":[" + tags + "]}}}";
	}
}
