package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies the actual control exchange using owned files. Population fixtures exercise parsing and permissions,
 * not kernel containment, process exit, or native hook cleanup.
 */
public final class LinuxSupervisorControlTest
{
	/**
	 * Owns ordinary files representing the exchange and kernel input documents.
	 */
	private static final class Fixture implements AutoCloseable
	{
		/**
		 * Exclusive exchange directory removed after each test.
		 */
		private final Path directory;
		/**
		 * Borrowed group identity represented by this fixture.
		 */
		private final Path group;
		/**
		 * Creates the owned fixture.
		 * @throws IOException if creation fails
		 */
		private Fixture() throws IOException
		{
			directory = Files.createTempDirectory("supervisor-control-");
			try { group = Files.createDirectory(directory.resolve("action")); }
			catch (IOException | RuntimeException | Error failure)
			{
				try { Files.delete(directory); }
				catch (IOException cleanup) { failure.addSuppressed(cleanup); }
				throw failure;
			}
		}
		/**
		 * Creates a participant for the same prepared action.
		 * @param managed indicates whether instrumentation owns the collection handoff
		 * @return a new protocol participant
		 */
		private LinuxSupervisor.Control control(boolean managed)
		{
			return new LinuxSupervisor.Control(directory, "attempt-one", 123, group, managed);
		}
		/**
		 * Writes the next population sample.
		 * @param text sample content
		 * @throws IOException if writing fails
		 */
		private void population(String text) throws IOException
		{
			Files.writeString(group.resolve("cgroup.events"), text);
		}
		/**
		 * Reads a published control document.
		 * @param name document filename
		 * @return decoded properties
		 * @throws IOException if reading fails
		 */
		private Properties read(String name) throws IOException
		{
			Properties values = new Properties();
			try (var input = Files.newInputStream(directory.resolve(name))) { values.loadFromXML(input); }
			return values;
		}
		/**
		 * Replaces a document to exercise rejection at the receiving boundary.
		 * @param name document filename
		 * @param values replacement properties
		 * @throws IOException if writing fails
		 */
		private void write(String name, Properties values) throws IOException
		{
			try (OutputStream output = Files.newOutputStream(directory.resolve(name)))
			{
				values.storeToXML(output, "Invalid receiver input");
			}
		}
		/**
		 * Removes only this fixture's files.
		 * @throws IOException if removal fails
		 */
		@Override
		public void close() throws IOException
		{
			try (var paths = Files.walk(directory))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
			}
		}
	}

	/**
	 * Preserves automatic collection for existing process owners.
	 * @throws IOException if fixture access fails
	 */
	@Test
	public void automaticOwnersCollectWithoutInstrumentationAcknowledgement() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			requireThat(fixture.control(false).collectionRequested(), "collectionPermitted").isTrue();
		}
	}

	/**
	 * Retains instrumented actions without acknowledgement.
	 * @throws IOException if fixture access fails
	 */
	@Test
	public void instrumentedOwnersRetainCollectionWithoutAcknowledgement() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			requireThat(fixture.control(true).collectionRequested(), "collectionPermitted").isFalse();
		}
	}

	/**
	 * Keeps kill intent separate from population, hook closure and collection permission.
	 * @throws IOException if fixture access fails
	 */
	@Test
	public void cancellationDoesNotAuthorizeCollection() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			var controller = fixture.control(true);
			var supervisor = fixture.control(true);
			fixture.population("populated 1\nfrozen 0\n");
			controller.requestTermination();
			byte[] published = Files.readAllBytes(fixture.directory.resolve("terminate.xml"));
			controller.requestTermination();
			requireThat(java.util.Arrays.equals(published,
				Files.readAllBytes(fixture.directory.resolve("terminate.xml"))), "unchangedRequest").isTrue();
			requireThat(supervisor.readTerminationRequest(), "cancellationRequested").isTrue();
			requireThat(controller.observeExecutionImpossible(), "executionImpossible").isFalse();
			requireException(IllegalStateException.class, controller::finishInstrumentation);
			requireThat(supervisor.collectionRequested(), "collectionPermitted").isFalse();
			fixture.population("populated 0\nfrozen 0\n");
			requireThat(controller.observeExecutionImpossible(), "executionImpossible").isTrue();
			requireThat(supervisor.collectionRequested(), "collectionPermittedBeforeHookClose").isFalse();
			controller.finishInstrumentation();
			controller.finishInstrumentation();
			requireThat(supervisor.collectionRequested(), "collectionPermittedAfterHookClose").isTrue();
			requireThat(supervisor.observeExecutionImpossible(), "independentPopulationCheck").isTrue();
		}
	}

	/**
	 * Rejects missing and malformed samples rather than inferring zero.
	 * @throws IOException if setup fails
	 */
	@Test
	public void missingGroupOrRootExitCannotProveZero() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			var controller = fixture.control(true);
			Files.writeString(fixture.directory.resolve("root-exited"), "true");
			requireException(IOException.class, controller::observeExecutionImpossible);
			fixture.population("frozen 0\n");
			requireException(IOException.class, controller::observeExecutionImpossible);
			fixture.population("populated invalid\n");
			requireException(IOException.class, controller::observeExecutionImpossible);
			Files.delete(fixture.group.resolve("cgroup.events"));
			Files.delete(fixture.group);
			requireException(IOException.class, controller::observeExecutionImpossible);
			requireException(IllegalStateException.class, controller::finishInstrumentation);
		}
	}

	/**
	 * Rejects forged action identities and conflicting immutable retries.
	 * @throws IOException if setup fails
	 */
	@Test
	public void rejectsChangedRequestUnderRetainedIdentity() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			var controller = fixture.control(true);
			controller.requestTermination();
			Properties request = fixture.read("terminate.xml");
			request.setProperty("root.pid", "124");
			fixture.write("terminate.xml", request);
			requireException(IOException.class, fixture.control(true)::readTerminationRequest);
			requireException(IOException.class, controller::requestTermination);
			request.setProperty("root.pid", "123");
			request.setProperty("attempt", "attempt-two");
			fixture.write("terminate.xml", request);
			requireException(IOException.class, fixture.control(true)::readTerminationRequest);
		}
	}

	/**
	 * Repairs an interrupted publication without changing the logical request.
	 * @throws IOException if setup fails
	 */
	@Test
	public void retriesUncertainPublication() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			var controller = fixture.control(true);
			Path pending = fixture.directory.resolve("terminate.xml.pending");
			Files.createDirectory(pending);
			Files.writeString(pending.resolve("blocker"), "owned failure input");
			requireException(IOException.class, controller::requestTermination);
			Files.delete(pending.resolve("blocker"));
			Files.delete(pending);
			Files.writeString(pending, "interrupted XML write");
			controller.requestTermination();
			requireThat(fixture.control(true).readTerminationRequest(), "retryPublished").isTrue();
		}
	}

	/**
	 * Retries the actual kill write after failure, independent of published intent.
	 * @throws IOException if setup fails
	 */
	@Test
	public void retriesFailedKernelKillOperation() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path target = fixture.group.resolve("cgroup.kill");
			Files.createDirectory(target);
			var kill = new LinuxProcessTree.KillRequest(fixture.group);
			requireException(IOException.class, kill::request);
			Files.delete(target);
			Files.createFile(target);
			kill.request();
			requireThat(Files.readString(target), "kernelWrite").isEqualTo("1\n");
			Files.writeString(target, "already written");
			kill.request();
			requireThat(Files.readString(target), "duplicateDoesNotWrite").isEqualTo("already written");
		}
	}

	/**
	 * Requires the exact hook-close and zero acknowledgement.
	 * @throws IOException if setup fails
	 */
	@Test
	public void rejectsIncompleteCollectionAcknowledgement() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			var controller = fixture.control(true);
			fixture.population("populated 0\n");
			controller.observeExecutionImpossible();
			controller.finishInstrumentation();
			Properties request = fixture.read("collect.xml");
			request.setProperty("instrumentation.closed", "false");
			fixture.write("collect.xml", request);
			requireException(IOException.class, fixture.control(true)::collectionRequested);
			request.setProperty("instrumentation.closed", "true");
			request.setProperty("populated", "1");
			fixture.write("collect.xml", request);
			requireException(IOException.class, fixture.control(true)::collectionRequested);
		}
	}

	/**
	 * Preserves absent legacy mode while rejecting invalid explicit mode.
	 * @throws IOException if decoding fails
	 */
	@Test
	public void collectionModePreservesLegacyDocuments() throws IOException
	{
		Properties values = new Properties();
		requireThat(LinuxSupervisor.readInstrumentationMode(values), "legacyMode").isFalse();
		values.setProperty("instrumentation.managed", "true");
		requireThat(LinuxSupervisor.readInstrumentationMode(values), "managedMode").isTrue();
		values.setProperty("instrumentation.managed", "false");
		requireThat(LinuxSupervisor.readInstrumentationMode(values), "automaticMode").isFalse();
		values.setProperty("instrumentation.managed", "");
		requireException(IOException.class, () -> LinuxSupervisor.readInstrumentationMode(values));
	}
	/**
	 * Preserves a cleanup-only failure as the actual failure returned to its caller.
	 */
	@Test
	public void reportsCleanupFailureWithoutEarlierFailure()
	{
		IOException cleanup = new IOException("cleanup");
		IOException actual = requireException(IOException.class,
			() -> LinuxSupervisor.closeOwned(() -> { throw cleanup; }, null));
		requireThat(actual == cleanup, "actualCleanupFailure").isTrue();
	}

	/**
	 * Keeps the primary supervision failure while retaining the independent cleanup diagnostic.
	 * @throws Exception if cleanup incorrectly replaces the primary failure
	 */
	@Test
	public void suppressesCleanupFailureOnPrimaryFailure() throws Exception
	{
		IOException primary = new IOException("supervision");
		IOException cleanup = new IOException("cleanup");
		LinuxSupervisor.closeOwned(() -> { throw cleanup; }, primary);
		requireThat(primary.getSuppressed().length, "suppressedCount").isEqualTo(1);
		requireThat(primary.getSuppressed()[0] == cleanup, "suppressedCleanup").isTrue();
	}

}
