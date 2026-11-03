package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxAttempts;
import com.holdmyspot.codexunleashed.scheduler.LinuxBorrowedStreams;
import com.holdmyspot.codexunleashed.scheduler.LinuxProcessTree;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Checks borrowed-attempt selection before native preparation; these cases grant no authentication authority. */
public final class LinuxBorrowedCommandTest
{
	/** Creates isolated receipt-selection checks. */
	public LinuxBorrowedCommandTest() {}

	/** Constructs an immutable receipt without accessing any endpoint.
	 * @param action registered action UUID
	 * @param localSequence positive action-local retry ordinal
	 * @param launch immutable approved execution
	 * @return controlled receipt for resource-free policy checks
	 */
	private static LinuxBorrowedStreams.Binding binding(UUID action, long localSequence, LinuxProcessTree.Launch launch)
	{
		var stream = new LinuxBorrowedStreams.Terminal(false, "", 0, 0, List.of());
		var terminal = new LinuxBorrowedStreams.TerminalContext(1, 1, List.of(stream, stream, stream));
		return new LinuxBorrowedStreams.Binding(action, localSequence, new LinuxBorrowedStreams.Peer(1, 1, 1000, 1000),
			Path.of("/nonexistent/streams.sock").toAbsolutePath(), new byte[32], terminal, launch);
	}

	/** Registration defers receipt selection and preserves independent global and action-local ordinals.
	 * @throws IOException if a valid controlled receipt is rejected
	 */
	@Test
	public void selectsEachRetryAfterRegistration() throws IOException
	{
		UUID action = new UUID(1, 2);
		var launch = AllocationHooksTest.launch(List.of("/nonexistent/compiler"), Map.of());
		var selected = new ArrayList<Long>();
		var first = binding(action, 1, launch);
		var second = binding(action, 2, launch);
		var command = new LinuxAttempts.BorrowedCommand(launch, sequence ->
		{
			selected.add(sequence);
			return selected.size() == 1 ? first : second;
		});
		var registry = new LinuxAttempts.Commands();
		registry.register(action.toString(), command);
		requireThat(selected.isEmpty(), "noSelectionDuringConstructionOrRegistration").isEqualTo(true, "expected");
		requireThat(registry.get(action.toString()), "registeredInput").isReferenceEqualTo(command, "expected");
		requireThat(command.binding(action.toString(), 41), "firstReceipt").isReferenceEqualTo(first, "expected");
		requireThat(command.binding(action.toString(), 48), "retryReceipt").isReferenceEqualTo(second, "expected");
		requireThat(selected, "issuedGlobalOrdinals").isEqualTo(List.of(41L, 48L), "expected");
		requireThat(second.attemptSequence(), "independentActionOrdinal").isEqualTo(2L, "expected");
	}

	/** Identical execution never authorizes another action, and matching action identity never authorizes changed execution. */
	@Test
	public void rejectsAnotherActionOrExecution()
	{
		UUID action = new UUID(1, 2);
		var launch = AllocationHooksTest.launch(List.of("/nonexistent/compiler"), Map.of());
		var otherAction = new LinuxAttempts.BorrowedCommand(launch, sequence -> binding(new UUID(3, 4), 1, launch));
		requireException(IOException.class, () -> otherAction.binding(action.toString(), 0));
		var changed = AllocationHooksTest.launch(List.of("/nonexistent/another-compiler"), Map.of());
		var otherExecution = new LinuxAttempts.BorrowedCommand(launch, sequence -> binding(action, 1, changed));
		requireException(IOException.class, () -> otherExecution.binding(action.toString(), 0));
	}

	/** Invalid inputs do not select a receipt, and provider failure remains the original error before native acquisition. */
	@Test
	public void preservesSelectionFailureWithoutAcquisition()
	{
		UUID action = new UUID(1, 2);
		var launch = AllocationHooksTest.launch(List.of("/nonexistent/compiler"), Map.of());
		var selected = new ArrayList<Long>();
		IOException original = new IOException("Authentication incomplete; invocation retains its listener");
		var command = new LinuxAttempts.BorrowedCommand(launch, sequence ->
		{
			selected.add(sequence);
			throw original;
		});
		requireException(IllegalArgumentException.class, () -> command.binding(action.toString(), -1));
		requireThat(selected.isEmpty(), "invalidOrdinalDoesNotSelect").isEqualTo(true, "expected");
		requireThat(requireException(IOException.class, () -> command.binding(action.toString(), 0)),
			"originalSelectionFailure").isReferenceEqualTo(original, "expected");
		var missing = new LinuxAttempts.BorrowedCommand(launch, sequence -> null);
		requireException(IOException.class, () -> missing.binding(action.toString(), 1));
		var direct = new LinuxProcessTree.Launch(launch.cgroupParent(), launch.scratchParent(), launch.javaExecutable(),
			launch.classPath(), launch.command(), launch.environment(), launch.directory(), launch.uid(), launch.gid());
		requireException(IllegalArgumentException.class, () -> new LinuxAttempts.BorrowedCommand(direct, sequence -> null));
		requireException(NullPointerException.class, () -> new LinuxAttempts.BorrowedCommand(launch, null));
	}
}
