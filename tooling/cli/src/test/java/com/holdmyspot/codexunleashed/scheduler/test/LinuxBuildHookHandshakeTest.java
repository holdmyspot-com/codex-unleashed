package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxBorrowedStreams;
import com.holdmyspot.codexunleashed.scheduler.LinuxBuildHookFrames;
import com.holdmyspot.codexunleashed.scheduler.LinuxBuildHookHandshake;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.List;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies which build hook clients an attempt admits.
 */
public final class LinuxBuildHookHandshakeTest
{
	/**
	 * The attempt's cgroup.
	 */
	private static final String GROUP = "/scheduler/attempt-3";
	/**
	 * The client's kernel peer receipt.
	 */
	private static final LinuxBorrowedStreams.Peer PEER = new LinuxBorrowedStreams.Peer(42, 900, 1000, 1000);

	/**
	 * Creates the tests.
	 */
	public LinuxBuildHookHandshakeTest()
	{
	}

	/**
	 * A client in a leaf below the attempt's cgroup that names its own process is admitted.
	 * @throws IOException if the client is rejected
	 */
	@Test
	public void admitsClientInAttemptSubtree() throws IOException
	{
		requireThat(LinuxBuildHookHandshake.admit(List.of("hello", "1", "42", "900"), PEER,
			"0::/scheduler/attempt-3/w\n", GROUP), "pid").isEqualTo(42L);
	}

	/**
	 * A client in a sibling cgroup whose name extends the attempt's is rejected.
	 * @throws IOException always
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*cgroup.*")
	public void rejectsClientOutsideAttempt() throws IOException
	{
		LinuxBuildHookHandshake.admit(List.of("hello", "1", "42", "900"), PEER, "0::/scheduler/attempt-30/w\n", GROUP);
	}

	/**
	 * A hello that names another process than the socket's peer is rejected.
	 * @throws IOException always
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*peer.*")
	public void rejectsHelloForAnotherProcess() throws IOException
	{
		LinuxBuildHookHandshake.admit(List.of("hello", "1", "43", "900"), PEER, "0::/scheduler/attempt-3/w\n", GROUP);
	}

	/**
	 * A hello whose start ticks differ from the peer's names a reused process ID and is rejected.
	 * @throws IOException always
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*peer.*")
	public void rejectsHelloFromReusedPid() throws IOException
	{
		LinuxBuildHookHandshake.admit(List.of("hello", "1", "42", "901"), PEER, "0::/scheduler/attempt-3/w\n", GROUP);
	}

	/**
	 * A hello of another protocol version is rejected.
	 * @throws IOException always
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*version.*")
	public void rejectsOtherVersion() throws IOException
	{
		LinuxBuildHookHandshake.admit(List.of("hello", "2", "42", "900"), PEER, "0::/scheduler/attempt-3/w\n", GROUP);
	}

	/**
	 * A pid written in a non-canonical form, which the library never sends, is rejected.
	 * @throws IOException always
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*decimal.*")
	public void rejectsNonCanonicalPid() throws IOException
	{
		LinuxBuildHookHandshake.admit(List.of("hello", "1", "042", "900"), PEER, "0::/scheduler/attempt-3/w\n", GROUP);
	}

	/**
	 * The welcome reply carries the installation as decimal.
	 * @throws IOException if the reply does not decode
	 */
	@Test
	public void encodesWelcome() throws IOException
	{
		requireThat(LinuxBuildHookFrames.decode(LinuxBuildHookHandshake.welcome(7)), "welcome").
			isEqualTo(List.of("welcome", "7"));
	}
}
