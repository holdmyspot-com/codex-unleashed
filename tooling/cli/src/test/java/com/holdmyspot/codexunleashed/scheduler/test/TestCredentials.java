package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxBazelBackend;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/**
 * Copies fixed, long-lived mTLS credentials into a caller-owned directory.
 * <p>
 * The files are test resources produced once by {@code bazel-credentials/generate.sh}. Tests that need valid
 * credentials, but make no claim about how they were issued, copy them instead of running keytool, so no test
 * shares files or state with another.
 */
final class TestCredentials
{
	/** The resource names that make up one complete set of credentials. */
	private static final List<String> FILES = List.of("ca.pem", "server-cert.pem", "server-key.pem",
		"worker-cert.pem", "worker-key.pem", "other-worker-cert.pem", "other-worker-key.pem");

	/**
	 * Prevents construction.
	 */
	private TestCredentials()
	{
	}

	/**
	 * Copies the credentials into a new owner-only directory.
	 *
	 * @param directory an absent directory whose parent exists
	 * @return the copied credentials
	 * @throws IOException if a resource is missing or cannot be copied
	 */
	static LinuxBazelBackend.Credentials copy(Path directory) throws IOException
	{
		Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(
			PosixFilePermissions.fromString("rwx------")));
		for (String name : FILES)
		{
			Path target = directory.resolve(name);
			try (InputStream input = TestCredentials.class.getResourceAsStream("bazel-credentials/" + name))
			{
				if (input == null)
					throw new IOException("Missing test credential resource: " + name);
				Files.copy(input, target);
			}
			Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
		}
		return new LinuxBazelBackend.Credentials(directory.resolve("ca.pem"), directory.resolve("server-cert.pem"),
			directory.resolve("server-key.pem"), directory.resolve("worker-cert.pem"),
			directory.resolve("worker-key.pem"));
	}

	/**
	 * Returns a second valid clientAuth leaf, signed by the same authority, among credentials copied by
	 * {@link #copy(Path)}.
	 *
	 * @param credentials credentials returned by {@link #copy(Path)}
	 * @return the certificate followed by its PKCS8 key
	 */
	static List<Path> otherWorker(LinuxBazelBackend.Credentials credentials)
	{
		Path directory = credentials.certificateAuthority().getParent();
		return List.of(directory.resolve("other-worker-cert.pem"), directory.resolve("other-worker-key.pem"));
	}
}
