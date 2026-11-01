package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Exercises scoped native listener acquisition and cleanup on Linux x64 glibc without importing descriptors. */
public final class LinuxBorrowedStreamsScopeProbe
{
	/** Prevents construction of this standalone native check. */
	private LinuxBorrowedStreamsScopeProbe()
	{
	}

	/**
	 * Checks independent listener lifetimes and partial native acquisition against actual socket descriptors.
	 * @param arguments one owned scratch directory
	 * @throws IOException if native acquisition, descriptor inspection or owned cleanup fails
	 */
	public static void main(String[] arguments) throws IOException
	{
		Path firstDirectory = Files.createTempDirectory(Path.of(arguments[0]), "streams-first-",
			PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
		Path secondDirectory = Files.createTempDirectory(Path.of(arguments[0]), "streams-second-",
			PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
		// Held directory descriptors give the fixture short socket names without moving storage outside scratch.
		try (var firstHandle = FileChannel.open(firstDirectory, StandardOpenOption.READ);
			var secondHandle = FileChannel.open(secondDirectory, StandardOpenOption.READ);
			var scope = new DefaultToolingScope(); var independentScope = new DefaultToolingScope())
		{
			Path firstAlias = descriptorPath(firstDirectory);
			Path secondAlias = descriptorPath(secondDirectory);
			var bindings = scope.getLinuxBorrowedStreamBindings();
			requireThat(bindings, "bindings").isReferenceEqualTo(scope.getLinuxBorrowedStreamBindings(), "reacquiredBindings");
			requireThat(bindings == independentScope.getLinuxBorrowedStreamBindings(), "crossScopeSharing").isFalse();
			Set<String> before = socketDescriptors();
			try (var first = LinuxBorrowedStreams.listen(scope, firstAlias, UUID.randomUUID()))
			{
				requireThat(socketDescriptors().size(), "firstSocketCount").isEqualTo(before.size() + 1);
				requireException(IOException.class, () -> LinuxBorrowedStreams.listen(scope, firstAlias, UUID.randomUUID()));
				requireThat(Files.exists(first.endpoint()), "originalEndpointRetained").isTrue();
				requireThat(socketDescriptors().size(), "failedAcquisitionSocketCount").isEqualTo(before.size() + 1);
				try (var second = LinuxBorrowedStreams.listen(scope, secondAlias, UUID.randomUUID()))
				{
					requireThat(socketDescriptors().size(), "peerSocketCount").isEqualTo(before.size() + 2);
					requireThat(Files.exists(second.endpoint()), "peerEndpoint").isTrue();
				}
				requireThat(Files.exists(first.endpoint()), "survivingEndpoint").isTrue();
				requireThat(socketDescriptors().size(), "survivingSocketCount").isEqualTo(before.size() + 1);
			}
			requireThat(socketDescriptors(), "terminalSocketDescriptors").isEqualTo(before);
			requireThat(Files.exists(firstDirectory.resolve("streams.sock")), "firstEndpointAfterClose").isFalse();
			requireThat(Files.exists(secondDirectory.resolve("streams.sock")), "secondEndpointAfterClose").isFalse();
			System.out.println("scoped_bindings=shared_and_isolated; native_listeners=independent; failed_bind=cleaned; owned_sockets_after=0");
		}
		finally
		{
			Files.delete(firstDirectory);
			Files.delete(secondDirectory);
		}
	}

	/**
	 * Finds the unique held directory descriptor used as this fixture's short pathname alias.
	 * @param directory exclusively owned directory with one retained channel
	 * @return absolute descriptor pathname retained until listener cleanup completes
	 * @throws IOException if descriptor observation fails or the retained directory is ambiguous
	 */
	private static Path descriptorPath(Path directory) throws IOException
	{
		Path result = null;
		try (var descriptors = Files.newDirectoryStream(Path.of("/proc/self/fd")))
		{
			for (Path descriptor : descriptors)
			{
				try
				{
					if (Files.readSymbolicLink(descriptor).equals(directory))
					{
						if (result != null)
							throw new IOException("Multiple descriptors retain the fixture directory");
						result = descriptor;
					}
				}
				catch (NoSuchFileException disappeared)
				{
					// Unrelated descriptor closure does not end either retained directory channel.
				}
			}
		}
		if (result == null)
			throw new IOException("Fixture directory descriptor is absent");
		return result;
	}

	/**
	 * Returns socket identities currently retained by this JVM, excluding transient unrelated descriptor closure.
	 * @return live socket identities from the process descriptor view
	 * @throws IOException if descriptor enumeration fails
	 */
	private static Set<String> socketDescriptors() throws IOException
	{
		Set<String> result = new HashSet<>();
		try (var descriptors = Files.newDirectoryStream(Path.of("/proc/self/fd")))
		{
			for (Path descriptor : descriptors)
			{
				try
				{
					String target = Files.readSymbolicLink(descriptor).toString();
					if (target.startsWith("socket:["))
						result.add(target);
				}
				catch (NoSuchFileException disappeared)
				{
					// Unrelated JVM descriptors may close during enumeration; listener ownership stays on this thread.
				}
			}
		}
		return result;
	}
}
