package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.List;

/**
 * Admits a linux-build-hook.c client to one attempt from its {@code hello} frame and its kernel peer receipt.
 */
public final class LinuxBuildHookHandshake
{
	/**
	 * The protocol version that the library sends in {@code hello}.
	 */
	public static final String VERSION = "1";

	private LinuxBuildHookHandshake()
	{
	}

	/**
	 * Checks that a {@code hello} frame came from the kernel peer it names and that the peer belongs to the attempt.
	 * @param hello      the decoded fields: {@code hello}, version, pid and start ticks
	 * @param peer       the socket's kernel peer receipt
	 * @param membership the peer's {@code /proc/<pid>/cgroup} contents, read after the receipt
	 * @param group      the attempt's cgroup v2 path, such as {@code /scheduler/attempt-3}
	 * @return the client's process ID
	 * @throws IOException if the frame is not a version {@value #VERSION} hello, names another process, or the peer is
	 *   outside {@code group}
	 */
	public static long admit(List<String> hello, LinuxBorrowedStreams.Peer peer, String membership, String group)
		throws IOException
	{
		if (hello.size() != 4 || !hello.get(0).equals("hello"))
			throw new IOException("Expected a hello frame of 4 fields: " + hello);
		if (!hello.get(1).equals(VERSION))
			throw new IOException("Unsupported build hook protocol version: " + hello.get(1));
		long pid = canonicalDecimal(hello.get(2), "pid");
		long start = canonicalDecimal(hello.get(3), "start");
		if (pid != peer.pid() || start != peer.startTicks())
		{
			throw new IOException("hello names process " + pid + " started at " + start + ", but the socket peer is " +
				peer.pid() + " started at " + peer.startTicks());
		}
		String prefix = "0::";
		if (!membership.startsWith(prefix) || !membership.endsWith("\n") || membership.indexOf('\n') !=
			membership.length() - 1)
		{
			throw new IOException("Process " + pid + " has no single cgroup v2 membership: " + membership);
		}
		String path = membership.substring(prefix.length(), membership.length() - 1);
		if (!path.equals(group) && !path.startsWith(group + "/"))
			throw new IOException("Process " + pid + " is in cgroup " + path + ", outside the attempt's " + group);
		return pid;
	}

	/**
	 * Parses a decimal that the library writes: digits without a sign or leading zero.
	 * @param value the field
	 * @param name  the field's name
	 * @return the value
	 * @throws IOException if the field is not a canonical positive decimal
	 */
	private static long canonicalDecimal(String value, String name) throws IOException
	{
		if (value.isEmpty() || value.charAt(0) == '0' || !value.chars().allMatch(c -> c >= '0' && c <= '9'))
			throw new IOException("The " + name + " field is not a canonical decimal: " + value);
		try
		{
			return Long.parseLong(value);
		}
		catch (NumberFormatException e)
		{
			throw new IOException("The " + name + " field is not a canonical decimal: " + value, e);
		}
	}

	/**
	 * Encodes the reply that admits a client.
	 * @param installation the identifier assigned to the client's hook installation
	 * @return the {@code welcome} payload
	 */
	public static byte[] welcome(long installation)
	{
		return LinuxBuildHookFrames.encode("welcome", Long.toString(installation));
	}
}
