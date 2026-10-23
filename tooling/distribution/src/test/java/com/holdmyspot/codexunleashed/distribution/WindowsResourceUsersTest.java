package com.holdmyspot.codexunleashed.distribution;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies {@link WindowsResourceUsers} interpretation and its Windows exact-resource native boundary.
 */
public final class WindowsResourceUsersTest
{
	/**
	 * Creates resource-user tests.
	 */
	public WindowsResourceUsersTest()
	{
	}

	/**
	 * Keeps an empty successful observation distinct from proof that a resource is unlocked.
	 */
	@Test
	public void emptyResultLeavesOwnershipUnknown()
	{
		Instant time = Instant.now();
		WindowsResourceUsers.Observation observation = new WindowsResourceUsers.Observation(
			Path.of("unused.dll"), time, time, List.of("RmGetList(size) status=0"), List.of(), List.of());
		requireThat(observation.diagnostic().getMessage(), "diagnostic").contains("Ownership unknown");
		requireThat(observation.diagnostic().getMessage(), "diagnostic").contains("does not mean unlocked");
	}

	/**
	 * Retains each failed API operation as a suppressed exception with its original native status.
	 */
	@Test
	public void retainsExplicitNativeErrors()
	{
		Instant time = Instant.now();
		IOException nativeFailure = new IOException("RmRegisterResources status=5");
		WindowsResourceUsers.Observation observation = new WindowsResourceUsers.Observation(
			Path.of("denied.dll"), time, time, List.of("RmRegisterResources status=5"), List.of(), List.of(nativeFailure));
		IOException diagnostic = observation.diagnostic();
		requireThat(diagnostic.getMessage(), "diagnostic").contains("RmRegisterResources status=5");
		requireThat(diagnostic.getMessage(), "diagnostic").contains("Ownership unknown");
		requireThat(diagnostic.getSuppressed().length, "suppressedCount").isEqualTo(1);
		requireThat(diagnostic.getSuppressed()[0], "nativeFailure").isEqualTo(nativeFailure);
	}

	/**
	 * Identifies the current process using a copied JDK DLL without closing or modifying its owned mapping.
	 * An unused copied DLL is a negative control, and closing the owned arena removes this process's attribution.
	 * Other operating systems exercise explicit unavailability only; they do not verify the Windows native calls.
	 *
	 * @throws IOException if fixture copying, native observation, or cleanup fails
	 */
	@Test
	public void observesMappedDllWithoutChangingIt() throws IOException
	{
		if (File.separatorChar != '\\')
		{
			WindowsResourceUsers.Observation observation = WindowsResourceUsers.observe(Path.of("unused.dll"));
			requireThat(observation.calls(), "nativeCalls").isEmpty();
			requireThat(observation.errors().size(), "nativeErrors").isEqualTo(1);
			requireThat(observation.errors().getFirst().getMessage(), "nativeError").contains("requires Windows");
			return;
		}
		Path root = Files.createTempDirectory("restart-manager-");
		try (Closeable _ = () -> FixtureDirectories.deleteTree(root))
		{
			Path source = Path.of(System.getProperty("java.home")).resolve("bin/verify.dll");
			Path mapped = Files.copy(source, root.resolve("mapped verify.dll"));
			Path unused = Files.copy(source, root.resolve("unused verify.dll"));
			long pid = ProcessHandle.current().pid();
			try (Arena mapping = Arena.ofConfined())
			{
				SymbolLookup library = loadDll(mapped, mapping);
				MemorySegment symbol = library.find("VerifyClassForMajorVersion").orElseThrow(() ->
					new IOException("Copied JDK verify.dll does not export VerifyClassForMajorVersion"));
				WindowsResourceUsers.Observation first = WindowsResourceUsers.observe(mapped);
				requireThat(first.errors(), "firstErrors").isEmpty();
				WindowsResourceUsers.User owner = first.users().stream().filter(user -> user.pid() == pid).
					findFirst().orElseThrow(first::diagnostic);
				requireThat(owner.creationFiletime(), "creationFiletime").isGreaterThan(0L);
				requireThat(first.file(), "registeredFile").isEqualTo(mapped.toAbsolutePath());
				requireThat(first.calls().getFirst(), "startStatus").isEqualTo("RmStartSession status=0");
				requireThat(first.calls().getLast(), "endStatus").isEqualTo("RmEndSession status=0");
				requireThat(library.find("VerifyClassForMajorVersion").orElseThrow().address(), "liveSymbol").
					isEqualTo(symbol.address());
				WindowsResourceUsers.Observation second = WindowsResourceUsers.observe(mapped);
				requireThat(second.errors(), "secondErrors").isEmpty();
				requireThat(second.users().stream().filter(user -> user.pid() == pid).
					map(WindowsResourceUsers.User::creationFiletime).toList(), "continuedOwner").
					contains(owner.creationFiletime());
				WindowsResourceUsers.Observation control = WindowsResourceUsers.observe(unused);
				requireThat(control.errors(), "controlErrors").isEmpty();
				requireThat(control.users().stream().map(WindowsResourceUsers.User::pid).toList(), "unusedUsers").
					doesNotContain(pid);
				requireThat(Files.mismatch(source, mapped), "mappedContents").isEqualTo(-1L);
			}
			WindowsResourceUsers.Observation released = WindowsResourceUsers.observe(mapped);
			requireThat(released.errors(), "releasedErrors").isEmpty();
			requireThat(released.users().stream().map(WindowsResourceUsers.User::pid).toList(), "releasedUsers").
				doesNotContain(pid);
		}
	}

	/**
	 * Maps a copied DLL using the current JVM ABI for the controlled Windows integration test.
	 *
	 * @param file  the owned copy
	 * @param arena the mapping owner
	 * @return the live symbol lookup
	 */
	@SuppressWarnings("restricted")
	private static SymbolLookup loadDll(Path file, Arena arena)
	{
		return SymbolLookup.libraryLookup(file, arena);
	}
}
