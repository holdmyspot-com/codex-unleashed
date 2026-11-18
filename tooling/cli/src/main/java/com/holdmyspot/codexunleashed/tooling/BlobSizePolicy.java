package com.holdmyspot.codexunleashed.tooling;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Objects;

/**
 * Applies the newly reachable Git blob size limit to streamed metadata records.
 */
public final class BlobSizePolicy
{
	/**
	 * Largest accepted Git blob size in bytes.
	 */
	public static final long MAX_BLOB_BYTES = 512_000;

	/**
	 * Prevents construction.
	 */
	private BlobSizePolicy()
	{
	}

	/**
	 * Reports each blob larger than the byte limit and returns the policy status.
	 *
	 * @param records one Git batch-check record per line
	 * @param err diagnostics for rejected blobs
	 * @return zero when every blob fits, or one when at least one blob is too large
	 * @throws NullPointerException if an argument is null
	 * @throws IOException if a metadata record is malformed
	 */
	public static int check(BufferedReader records, PrintStream err) throws IOException
	{
		Objects.requireNonNull(records, "records");
		Objects.requireNonNull(err, "err");
		boolean hasOversizedBlob = false;
		while (true)
		{
			String record = records.readLine();
			if (record == null)
				break;
			String[] fields = record.strip().split("\\s+");
			if (fields.length != 3 || fields[0].isBlank() || fields[1].isBlank())
				throw new IOException("Malformed Git batch-check record: " + record);
			long size;
			try
			{
				size = Long.parseLong(fields[2]);
			}
			catch (NumberFormatException failure)
			{
				throw new IOException("Malformed Git batch-check record: " + record, failure);
			}
			if (size < 0)
				throw new IOException("Malformed Git batch-check record: " + record);
			if (fields[1].equals("blob") && size > MAX_BLOB_BYTES)
			{
				err.printf("Blob %s is %d bytes; maximum is %d. Remove it from the proposed commits " +
					"(git log --find-object=%s).%n", fields[0], size, MAX_BLOB_BYTES, fields[0]);
				hasOversizedBlob = true;
			}
		}
		if (hasOversizedBlob)
			return 1;
		return 0;
	}
}
