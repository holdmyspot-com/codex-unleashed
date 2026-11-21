package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.Main;
import com.holdmyspot.codexunleashed.tooling.ReleaseManifest;
import com.holdmyspot.codexunleashed.tooling.ReleaseManifestSource;
import com.holdmyspot.codexunleashed.tooling.Sha256;
import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks that the recorded source identity requires a clean checkout and is reproducible after the release aligns and commits the lockfile, using real Git checkouts.
 */
public final class ReleaseManifestSourceIdentityTest
{
	/** Constructs independent checkouts for each identity check. */
	public ReleaseManifestSourceIdentityTest()
	{
	}

	/**
	 * Rejects a checkout whose lockfile was modified but not committed, which is what an unaligned or hand-edited
	 * lockfile looks like.
	 *
	 * @throws IOException if a fixture file or Git process fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Source checkout must be clean.*")
	public void rejectsUncommittedLockfile() throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("source-identity-");
			DefaultToolingScope scope = new DefaultToolingScope())
		{
			Path checkout = committedCheckout(fixture);
			Files.writeString(checkout.resolve("codex-rs/Cargo.lock"), "name = \"alpha\"\nversion = \"0.167.0\"\n");

			identity(fixture, scope, checkout);
		}
	}

	/**
	 * Rejects a checkout in which a file other than the lockfile differs from HEAD.
	 *
	 * @throws IOException if a fixture file or Git process fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Source checkout must be clean.*")
	public void rejectsOtherModifiedFile() throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("source-identity-");
			DefaultToolingScope scope = new DefaultToolingScope())
		{
			Path checkout = committedCheckout(fixture);
			Files.writeString(checkout.resolve("codex-rs/Cargo.toml"), "changed\n");

			identity(fixture, scope, checkout);
		}
	}

	/**
	 * Gives two independent checkouts of one HEAD the same recorded identity after each aligns and commits the
	 * lockfile, even when a checkout converts line endings.
	 *
	 * @throws IOException if a fixture file or Git process fails
	 */
	@Test
	public void independentAlignmentsRecordTheSameIdentity() throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("source-identity-");
			DefaultToolingScope scope = new DefaultToolingScope())
		{
			Path first = committedCheckout(fixture);
			String queueHead = git(fixture, first, "rev-parse", "HEAD");
			Path second = fixture.getPath().resolve("second");
			git(fixture, fixture.getPath(), "clone", "-q", "-c", "core.autocrlf=true", first.toString(),
				second.toString());

			align(first);
			align(second);

			ObjectNode firstIdentity = identity(fixture, scope, first);
			ObjectNode secondIdentity = identity(fixture, scope, second);
			requireThat(secondIdentity, "secondIdentity").isEqualTo(firstIdentity);
			requireThat(firstIdentity.get("commit").asString(), "commit").isNotEqualTo(queueHead);
			requireThat(firstIdentity.get("cargo_lock_sha256").asString(), "cargo_lock_sha256").
				isEqualTo(Sha256.digest(first.resolve("codex-rs/Cargo.lock")));
			requireThat(Sha256.digest(second.resolve("codex-rs/Cargo.lock")), "crlfLockfileDigest").
				isNotEqualTo(firstIdentity.get("cargo_lock_sha256").asString());
			requireThat(Files.readString(first.resolve("codex-rs/Cargo.lock")), "lock").contains("version = \"0.167.0\"");
		}
	}

	/**
	 * Creates a checkout whose HEAD holds a stale lockfile and the workspace manifests that name its package.
	 *
	 * @param fixture fixture root
	 * @return the checkout
	 * @throws IOException if a fixture file or Git process fails
	 */
	private static Path committedCheckout(TestDirectory fixture) throws IOException
	{
		Path checkout = fixture.createDirectories("checkout");
		fixture.write("checkout/codex-rs/Cargo.toml",
			"[workspace]\nmembers = [\"alpha\"]\n\n[workspace.package]\nversion = \"0.167.0\"\n");
		fixture.write("checkout/codex-rs/alpha/Cargo.toml", "[package]\nname = \"alpha\"\nversion.workspace = true\n");
		fixture.write("checkout/codex-rs/Cargo.lock", "[[package]]\nname = \"alpha\"\nversion = \"0.0.0\"\n");
		git(fixture, checkout, "init", "-q");
		git(fixture, checkout, "config", "core.autocrlf", "false");
		git(fixture, checkout, "add", "-A");
		git(fixture, checkout, "-c", "user.name=Test", "-c", "user.email=test@example.com", "-c",
			"commit.gpgsign=false", "commit", "-q", "-m", "fixture");
		return checkout;
	}

	/**
	 * Reads the recorded identity of a checkout through the real process boundary.
	 *
	 * @param fixture  fixture root, used as the working and temporary directory
	 * @param scope    borrowed JSON services
	 * @param checkout checkout to read
	 * @return the recorded identity
	 * @throws IOException if a Git process fails
	 */
	private static ObjectNode identity(TestDirectory fixture, DefaultToolingScope scope, Path checkout)
		throws IOException
	{
		ReleaseManifest.Environment environment = new ReleaseManifest.Environment(fixture.getPath(), fixture.getPath(),
			"test host", Map.of());
		return new ReleaseManifestSource(scope, SystemCommands::capture, environment).identity(checkout);
	}

	/**
	 * Runs Git in a checkout and requires success.
	 *
	 * @param fixture   fixture root, used as the temporary directory
	 * @param directory working directory
	 * @param arguments Git arguments
	 * @return standard output without its final line terminator
	 * @throws IOException if Git cannot run or fails
	 */
	private static String git(TestDirectory fixture, Path directory, String... arguments) throws IOException
	{
		List<String> command = new ArrayList<>(List.of("git"));
		command.addAll(List.of(arguments));
		SystemCommands.Result result = SystemCommands.capture(command, directory, fixture.getPath());
		if (result.status() != 0)
			throw new IOException("git " + String.join(" ", arguments) + " failed: " + result.stderr());
		return result.stdout().strip();
	}

	/**
	 * Aligns and commits the lockfile of a checkout through the packaged command entry point.
	 *
	 * @param checkout checkout whose lockfile is aligned
	 */
	private static void align(Path checkout)
	{
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[]{"align-workspace-lock", "--source-root", checkout.toString()},
				new ByteArrayInputStream(new byte[0]), out, err, Map.of());
			requireThat(status, "status").withContext(errors.toString(StandardCharsets.UTF_8), "diagnostics").isEqualTo(0);
		}
	}
}
