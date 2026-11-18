package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.ReleaseReproductionState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.io.file.PathUtils;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Exercises real ownership files and relocation without native processes. */
public final class ReleaseReproductionStateTest
{
	/** Creates isolated ownership checks. */
	public ReleaseReproductionStateTest()
	{
	}

	/**
	 * Resolves relocated state against the new root and keeps external output during cleanup.
	 *
	 * @throws IOException if fixture operations fail
	 */
	@Test
	public void relocatedRootAndOutput() throws IOException
	{
		Path fixture = Files.createTempDirectory("reproduction-relocation-");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			Path original = Files.createDirectory(fixture.resolve("original root"));
			ReleaseReproductionState state = ReleaseReproductionState.allocate(scope, original, "a".repeat(64),
				"x86_64-unknown-linux-musl");
			Path output = fixture.resolve("external output");
			state.output(scope, output, true);
			Path relocated = fixture.resolve("relocated root");
			Files.move(original, relocated);
			Path movedOutput = fixture.resolve("relocated output");
			Files.move(output, movedOutput);
			ReleaseReproductionState loaded = ReleaseReproductionState.load(scope, relocated, state.stateName());
			loaded.output(scope, movedOutput, false);
			requireThat(loaded.root().startsWith(relocated), "usesCurrentRoot").isTrue();
			loaded.cleanup(scope);
			requireThat(Files.exists(loaded.root()), "attemptExists").isFalse();
			requireThat(Files.exists(movedOutput.resolve(".reproduction_owner.json")), "externalOutputSurvives").isTrue();
		}
		finally
		{
			PathUtils.deleteDirectory(fixture);
		}
	}

	/**
	 * Rejects noncanonical state names before reading or deleting anything.
	 *
	 * @throws IOException if fixture operations fail
	 */
	@Test
	public void rejectsTraversalAndAbsoluteNames() throws IOException
	{
		Path root = Files.createTempDirectory("reproduction-state-path-");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			for (String name : new String[]{"", "/state.json", "C:/state.json", "\\\\server\\state.json",
				".cat/reproduction_attempts/../state.json", ".cat/reproduction_attempts/" + "a".repeat(32) + "/other.json"})
			{
				boolean rejected = false;
				try
				{
					ReleaseReproductionState.load(scope, root, name);
				}
				catch (IllegalArgumentException _)
				{
					rejected = true;
				}
				requireThat(rejected, "rejected:" + name).isTrue();
			}
		}
		finally
		{
			PathUtils.deleteDirectory(root);
		}
	}

	/**
	 * Unlinks nested symlinks without following them to external data.
	 *
	 * @throws IOException if fixture operations fail
	 */
	@Test
	public void cleanupDoesNotFollowNestedLink() throws IOException
	{
		Path root = Files.createTempDirectory("reproduction-state-link-");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			ReleaseReproductionState state = ReleaseReproductionState.allocate(scope, root, "b".repeat(64),
				"x86_64-unknown-linux-musl");
			Path external = Files.createDirectory(root.resolve("external"));
			Path sentinel = Files.writeString(external.resolve("sentinel"), "retained");
			Files.createSymbolicLink(state.root().resolve("scratch/link"), external);
			state.cleanup(scope);
			requireThat(Files.readString(sentinel), "externalSentinel").isEqualTo("retained");
		}
		finally
		{
			PathUtils.deleteDirectory(root);
		}
	}
	/**
	 * Refuses foreign output ownership and tampered state before granting cleanup authority.
	 *
	 * @throws IOException if fixture operations fail
	 */
	@Test
	public void rejectsForeignMarkersAndState() throws IOException
	{
		Path root = Files.createTempDirectory("reproduction-state-tamper-");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			ReleaseReproductionState first = ReleaseReproductionState.allocate(scope, root, "a".repeat(64),
				"x86_64-unknown-linux-musl");
			ReleaseReproductionState second = ReleaseReproductionState.allocate(scope, root, "a".repeat(64),
				"x86_64-unknown-linux-musl");
			Path output = root.resolve("external-output");
			first.output(scope, output, true);
			boolean rejected = false;
			try
			{
				second.output(scope, output, false);
			}
			catch (IOException _)
			{
				rejected = true;
			}
			requireThat(rejected, "foreignOutputRejected").isTrue();
			Path state = first.root().resolve("state.json");
			String original = Files.readString(state);
			Files.writeString(state, original.replace("project_root", "recorded_path"));
			rejected = false;
			try
			{
				first.cleanup(scope);
			}
			catch (IllegalArgumentException _)
			{
				rejected = true;
			}
			requireThat(rejected, "tamperedStateRejected").isTrue();
			requireThat(Files.exists(first.root()), "rejectedCleanupLeavesAttempt").isTrue();
			Files.writeString(state, original);
			first.cleanup(scope);
			second.cleanup(scope);
		}
		finally
		{
			PathUtils.deleteDirectory(root);
		}
	}
	/**
	 * Requires the fixed attempt layout before cleanup can delete its tree.
	 *
	 * @throws IOException if fixture operations fail
	 */
	@Test
	public void rejectsUnexpectedAttemptLayout() throws IOException
	{
		Path root = Files.createTempDirectory("reproduction-state-layout-");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			ReleaseReproductionState state = ReleaseReproductionState.allocate(scope, root, "a".repeat(64),
				"x86_64-unknown-linux-musl");
			Path unexpected = Files.writeString(state.root().resolve("unexpected"), "sentinel");
			boolean rejected = false;
			try
			{
				state.cleanup(scope);
			}
			catch (IOException _)
			{
				rejected = true;
			}
			requireThat(rejected, "unexpectedLayoutRejected").isTrue();
			requireThat(Files.exists(unexpected), "rejectedCleanupLeavesTree").isTrue();
			Files.delete(unexpected);
			state.cleanup(scope);
		}
		finally
		{
			PathUtils.deleteDirectory(root);
		}
	}
}
