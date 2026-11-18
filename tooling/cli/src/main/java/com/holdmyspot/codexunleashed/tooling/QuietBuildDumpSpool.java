package com.holdmyspot.codexunleashed.tooling;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.PriorityQueue;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Spools one process report's bounded text and externally sorts numeric process identifiers.
 */
public final class QuietBuildDumpSpool implements AutoCloseable
{
	/**
	 * Maximum process identifiers retained while creating one sorted run.
	 */
	private static final int SORT_CHUNK_SIZE = 4096;
	/**
	 * Maximum sorted runs opened by one merge operation.
	 */
	private static final int MERGE_FAN_IN = 64;
	/**
	 * Character buffer used to copy captured process output.
	 */
	private static final int COPY_BUFFER_SIZE = 8192;
	/**
	 * Directory containing only this invocation's dump spool files.
	 */
	private final Path directory;
	/**
	 * Number of independent directory snapshots sorted by this invocation.
	 */
	private int snapshotCount;
	/**
	 * Whether this invocation's spool directory has been removed.
	 */
	private boolean closed;

	/**
	 * Creates a private child directory beneath the caller's temporary directory.
	 *
	 * @param temporaryDirectory existing directory for this report's scratch files
	 * @throws NullPointerException if {@code temporaryDirectory} is null
	 * @throws IOException if the report's private directory cannot be created
	 */
	public QuietBuildDumpSpool(Path temporaryDirectory) throws IOException
	{
		requireThat(temporaryDirectory, "temporaryDirectory").isNotNull();
		directory = Files.createTempDirectory(temporaryDirectory, "quiet-build-dump-");
	}

	/**
	 * Copies captured text to an owned file without retaining the complete output in memory.
	 *
	 * @param input captured text reader, consumed from its current position
	 * @return the copied file, borrowed until this spool is closed
	 * @throws NullPointerException if {@code input} is null
	 * @throws IllegalStateException if this spool is closed
	 * @throws IOException if the input cannot be read or the copy cannot be written
	 */
	public Path copy(Reader input) throws IOException
	{
		requireOpen();
		requireThat(input, "input").isNotNull();
		Path copy = Files.createTempFile(directory, "captured-", ".txt");
		try (BufferedWriter writer = Files.newBufferedWriter(copy, StandardCharsets.UTF_8))
		{
			char[] buffer = new char[COPY_BUFFER_SIZE];
			for (int count = input.read(buffer); count != -1; count = input.read(buffer))
			{
				writer.write(buffer, 0, count);
			}
		}
		return copy;
	}

	/**
	 * Returns a file of the directory's numeric entry names in ascending numeric order.
	 *
	 * @param sourceDirectory directory whose numeric entry names are sorted
	 * @return sorted identifier lines, borrowed until this spool is closed
	 * @throws NullPointerException if {@code sourceDirectory} is null
	 * @throws IllegalStateException if this spool is closed
	 * @throws IOException if the directory cannot be read or sorted runs cannot be written
	 */
	public Path sortedIds(Path sourceDirectory) throws IOException
	{
		requireOpen();
		requireThat(sourceDirectory, "sourceDirectory").isNotNull();
		List<Integer> identifiers = new ArrayList<>(SORT_CHUNK_SIZE);
		String snapshotPrefix = "snapshot-%08d-".formatted(snapshotCount);
		snapshotCount = snapshotCount + 1;
		int runCount = 0;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(sourceDirectory))
		{
			for (Path entry : entries)
			{
				Integer identifier = numericName(entry.getFileName().toString());
				if (identifier == null)
					continue;
				identifiers.add(identifier);
				if (identifiers.size() == SORT_CHUNK_SIZE)
				{
					writeRun(identifiers, initialRun(snapshotPrefix, runCount));
					identifiers.clear();
					runCount = runCount + 1;
				}
			}
		}
		if (!identifiers.isEmpty())
		{
			writeRun(identifiers, initialRun(snapshotPrefix, runCount));
			runCount = runCount + 1;
		}
		if (runCount == 0)
			return Files.createFile(directory.resolve(snapshotPrefix + "sorted-ids.txt"));

		String currentPrefix = snapshotPrefix + "initial-";
		int pass = 0;
		while (runCount > 1)
		{
			String nextPrefix = snapshotPrefix + "pass-%03d-".formatted(pass);
			int nextRun = mergePass(currentPrefix, nextPrefix);
			deleteRuns(currentPrefix);
			currentPrefix = nextPrefix;
			runCount = nextRun;
			pass = pass + 1;
		}
		Path finalRun = findOnlyRun(currentPrefix);
		Path sortedIds = directory.resolve(snapshotPrefix + "sorted-ids.txt");
		Files.move(finalRun, sortedIds);
		return sortedIds;
	}

	/**
	 * Removes only this invocation's spool directory and attempts every child deletion.
	 *
	 * @throws IOException if one or more owned files cannot be removed
	 */
	@Override
	public void close() throws IOException
	{
		if (closed)
			return;
		closed = true;
		IOException failure = null;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory))
		{
			for (Path entry : entries)
			{
				try
				{
					Files.deleteIfExists(entry);
				}
				catch (IOException deleteFailure)
				{
					failure = retain(failure, deleteFailure);
				}
			}
		}
		catch (IOException listingFailure)
		{
			failure = retain(failure, listingFailure);
		}
		try
		{
			Files.deleteIfExists(directory);
		}
		catch (IOException deleteFailure)
		{
			failure = retain(failure, deleteFailure);
		}
		if (failure != null)
			throw failure;
	}

	/**
	 * Creates data for initialRun.
	 *
	 * @param snapshotPrefix supplied snapshotPrefix
	 * @param run supplied run
	  *
	  * @return the requested result
	 */
	private Path initialRun(String snapshotPrefix, int run)
	{
		return directory.resolve("%sinitial-%08d.run".formatted(snapshotPrefix, run));
	}

	/**
	 * Writes data for writeRun.
	 *
	 * @param identifiers supplied identifiers
	 * @param output supplied output
	 * @throws IOException when the operation fails
	 */
	private void writeRun(List<Integer> identifiers, Path output) throws IOException
	{
		identifiers.sort(Integer::compareTo);
		try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8))
		{
			for (Integer identifier : identifiers)
			{
				writer.write(identifier.toString());
				writer.newLine();
			}
		}
	}

	/**
	 * Merges data for mergePass.
	 *
	 * @param currentPrefix supplied currentPrefix
	 * @param nextPrefix supplied nextPrefix
	  *
	  * @return the requested result
	 * @throws IOException when the operation fails
	 */
	private int mergePass(String currentPrefix, String nextPrefix) throws IOException
	{
		List<Path> batch = new ArrayList<>(MERGE_FAN_IN);
		int runCount = 0;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, currentPrefix + "*.run"))
		{
			for (Path entry : entries)
			{
				batch.add(entry);
				if (batch.size() == MERGE_FAN_IN)
				{
					mergeRuns(batch, directory.resolve("%s%08d.run".formatted(nextPrefix, runCount)));
					batch.clear();
					runCount = runCount + 1;
				}
			}
		}
		if (!batch.isEmpty())
		{
			mergeRuns(batch, directory.resolve("%s%08d.run".formatted(nextPrefix, runCount)));
			runCount = runCount + 1;
		}
		return runCount;
	}

	/**
	 * Merges data for mergeRuns.
	 *
	 * @param runs supplied runs
	 * @param output supplied output
	 * @throws IOException when the operation fails
	 */
	private void mergeRuns(List<Path> runs, Path output) throws IOException
	{
		Queue<RunHead> pending = new PriorityQueue<>();
		try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8))
		{
			mergeReaders(runs, 0, pending, writer);
		}
	}

	/**
	 * Opens a bounded merge batch with scoped readers and writes its sorted rows.
	 *
	 * @param runs sorted input run paths
	 * @param index next run index to open
	 * @param pending current queue of run heads
	 * @param writer destination writer
	 * @throws IOException if a run cannot be read or the output cannot be written
	 */
	private void mergeReaders(List<Path> runs, int index, Queue<RunHead> pending, BufferedWriter writer)
		throws IOException
	{
		if (index == runs.size())
		{
			writeMergedRows(pending, writer);
			return;
		}
		try (BufferedReader reader = Files.newBufferedReader(runs.get(index), StandardCharsets.UTF_8))
		{
			Integer identifier = readIdentifier(reader);
			if (identifier != null)
				pending.add(new RunHead(identifier, reader));
			mergeReaders(runs, index + 1, pending, writer);
		}
	}

	/**
	 * Writes the smallest run head and advances its source until all rows are consumed.
	 *
	 * @param pending queue of current run heads
	 * @param writer destination writer
	 * @throws IOException if an input run or destination cannot be written
	 */
	private void writeMergedRows(Queue<RunHead> pending, BufferedWriter writer) throws IOException
	{
		while (!pending.isEmpty())
		{
			RunHead head = pending.remove();
			writer.write(Integer.toString(head.identifier()));
			writer.newLine();
			Integer identifier = readIdentifier(head.reader());
			if (identifier != null)
				pending.add(new RunHead(identifier, head.reader()));
		}
	}

	/**
	 * Reads data for readIdentifier.
	 *
	 * @param reader supplied reader
	  *
	  * @return the requested result
	 * @throws IOException when the operation fails
	 */
	private Integer readIdentifier(BufferedReader reader) throws IOException
	{
		String line = reader.readLine();
		if (line == null)
			return null;
		return Integer.valueOf(line);
	}

	/**
	 * Finds data for findOnlyRun.
	 *
	 * @param prefix supplied prefix
	  *
	  * @return the requested result
	 * @throws IOException when the operation fails
	 */
	private Path findOnlyRun(String prefix) throws IOException
	{
		Path result = null;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, prefix + "*.run"))
		{
			for (Path entry : entries)
			{
				if (result != null)
					throw new IOException("Expected one remaining sorted identifier run");
				result = entry;
			}
		}
		if (result == null)
			throw new IOException("No sorted identifier run remains");
		return result;
	}

	/**
	 * Deletes data for deleteRuns.
	 *
	 * @param prefix supplied prefix
	 * @throws IOException when the operation fails
	 */
	private void deleteRuns(String prefix) throws IOException
	{
		IOException failure = null;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, prefix + "*.run"))
		{
			for (Path entry : entries)
			{
				try
				{
					Files.deleteIfExists(entry);
				}
				catch (IOException deleteFailure)
				{
					failure = retain(failure, deleteFailure);
				}
			}
		}
		catch (IOException listingFailure)
		{
			failure = retain(failure, listingFailure);
		}
		if (failure != null)
			throw failure;
	}

	/**
	 * Checks data for requireOpen.
	 */
	private void requireOpen()
	{
		if (closed)
			throw new IllegalStateException("The dump spool is closed");
	}

	/**
	 * Parses data for numericName.
	 *
	 * @param name supplied name
	  *
	  * @return the requested result
	 */
	private static Integer numericName(String name)
	{
		try
		{
			return Integer.valueOf(name);
		}
		catch (NumberFormatException _)
		{
			return null;
		}
	}

	/**
	 * Retains data for retain.
	 *
	 * @param failure supplied failure
	 * @param next supplied next
	  *
	  * @return the requested result
	 */
	private static IOException retain(IOException failure, IOException next)
	{
		if (failure == null)
			return next;
		failure.addSuppressed(next);
		return failure;
	}

	/**
	 * One pending identifier and the reader that owns its remaining run rows.
	 *
	 * @param identifier numeric process identifier
	 * @param reader reader owning the remaining run rows
	 */
	private record RunHead(Integer identifier, BufferedReader reader) implements Comparable<RunHead>
	{
		@Override
		public int compareTo(RunHead other)
		{
			return identifier.compareTo(other.identifier);
		}
	}
}
