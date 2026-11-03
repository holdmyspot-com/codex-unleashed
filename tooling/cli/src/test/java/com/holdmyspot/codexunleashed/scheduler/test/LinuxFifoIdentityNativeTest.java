package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxCargoJobserver;
import com.holdmyspot.codexunleashed.scheduler.LinuxFifoBindings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies actual descriptor identity, alias and replacement behavior through scoped native bindings and live leases.
 * No compiler or native hook is started by these fixtures.
 */
public final class LinuxFifoIdentityNativeTest
{
	/**
	 * Creates isolated native identity checks.
	 */
	public LinuxFifoIdentityNativeTest() {}

	/**
	 * Obtains the same kernel identity through two aliases and after both pathnames are unlinked.
	 * @throws Exception if native setup, metadata observation or owned cleanup fails
	 */
	@Test
	public void identifiesAliasedAndUnlinkedFifo() throws Exception
	{
		LinuxCargoJobserverTest.requireLinux();
		try (var fixture = new LinuxCargoJobserverTest.Fixture())
		{
			var calls = fixture.scope.getLinuxFifoBindings();
			Path fifo = fixture.directory.resolve("identity.fifo");
			Path alias = fixture.directory.resolve("alias.fifo");
			calls.createFifo(fifo,0600);
			int first = calls.open(fifo);
			try
			{
				var expected = calls.identity(first);
				requireThat(expected.inode(), "actualInodeBits").isEqualTo((long) Files.getAttribute(fifo,"unix:ino"), "jdkInode");
				Files.createLink(alias,fifo);
				int second = calls.open(alias);
				try
				{
					requireThat(calls.identity(second), "aliasIdentity").isEqualTo(expected, "original");
					Files.delete(alias); Files.delete(fifo);
					requireThat(calls.identity(first), "unlinkedIdentity").isEqualTo(expected, "original");
					requireThat(calls.identity(second), "unlinkedPeerIdentity").isEqualTo(expected, "original");
				}
				finally { calls.close(second); }
			}
			finally { calls.close(first); Files.deleteIfExists(alias); Files.deleteIfExists(fifo); }
		}
	}

	/**
	 * Rejects ordinary files and invalid descriptor numbers, preserving native errno for a failed observation.
	 * @throws Exception if native fixture setup or owned cleanup fails
	 */
	@Test
	public void rejectsOrdinaryAndInvalidDescriptors() throws Exception
	{
		LinuxCargoJobserverTest.requireLinux();
		try (var fixture = new LinuxCargoJobserverTest.Fixture())
		{
			var calls = fixture.scope.getLinuxFifoBindings();
			Path ordinary = Files.writeString(fixture.directory.resolve("ordinary"),"owned fixture");
			int descriptor = calls.open(ordinary);
			try { requireException(IOException.class, () -> calls.identity(descriptor)); }
			finally { calls.close(descriptor); }
			IOException failure = requireException(IOException.class, () -> calls.identity(Integer.MAX_VALUE));
			requireThat(failure.getMessage().contains("errno 9"), "nativeBadDescriptor").isTrue();
			requireException(IllegalArgumentException.class, () -> calls.identity(-1));
			Files.delete(ordinary);
		}
	}

	/**
	 * A live lease identifies its retained endpoint after another FIFO replaces the original pathname.
	 * Restores the owner-produced pathname before normal owner cleanup.
	 * @throws Exception if native setup, metadata observation or cleanup fails
	 */
	@Test
	public void leaseIdentityIgnoresPathReplacement() throws Exception
	{
		LinuxCargoJobserverTest.requireLinux();
		try (var fixture = new LinuxCargoJobserverTest.Fixture();
			var owner = LinuxCargoJobserver.create(fixture.scope, fixture.directory, fixture.group,1))
		{
			var lease = owner.retainCargo(Map.of());
			try
			{
				var calls = fixture.scope.getLinuxFifoBindings();
				var original = lease.identity();
				Path fifo = LinuxCargoJobserverTest.fifo(lease);
				Path retained = fifo.resolveSibling("original.fifo");
				Files.move(fifo,retained);
				try
				{
					calls.createFifo(fifo,0600);
					int replacement = calls.open(fifo);
					try
					{
						requireThat(calls.identity(replacement).equals(original), "differentKernelObject").isFalse();
						requireThat(lease.identity(), "retainedKernelObject").isEqualTo(original, "original");
					}
					finally { calls.close(replacement); }
				}
				finally { Files.deleteIfExists(fifo); Files.move(retained,fifo); }
				lease.close();
				requireException(IllegalStateException.class, lease::identity);
			}
			finally { lease.close(); }
		}
	}

	/**
	 * Keeps unsigned device fields and inode bits without narrowing or admitting invalid device magnitudes.
	 */
	@Test
	public void preservesUnsignedIdentityFields()
	{
		var identity = new LinuxFifoBindings.Identity(0xffff_ffffL,0xffff_ffffL,-1L);
		requireThat(identity.major(), "unsignedMajor").isEqualTo(4294967295L,"expected");
		requireThat(Long.toUnsignedString(identity.inode()), "unsignedInode").isEqualTo("18446744073709551615","expected");
		for (long invalid : new long[]{-1,4294967296L})
		{
			requireException(IllegalArgumentException.class, () -> new LinuxFifoBindings.Identity(invalid,0,0));
			requireException(IllegalArgumentException.class, () -> new LinuxFifoBindings.Identity(0,invalid,0));
		}
	}
}
