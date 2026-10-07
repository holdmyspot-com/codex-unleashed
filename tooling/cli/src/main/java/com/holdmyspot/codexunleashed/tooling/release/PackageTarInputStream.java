package com.holdmyspot.codexunleashed.tooling.release;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * Uses the library's sparse decoder while preventing repeated consumption of old GNU auxiliary sparse records.
 * Commons Compress 1.28.0 recursively reads a long-name entry, then checks the completed sparse entry again.
 * Its unchanged extended flag otherwise causes file payload bytes to be read as another sparse metadata record.
 * Remove this adapter when the sparse archive tests pass with the unmodified reader.
 *
 * @see <a href="https://github.com/apache/commons-compress/blob/rel/commons-compress-1.28.0/src/main/java/org/apache/commons/compress/archivers/tar/TarArchiveInputStream.java">Pinned reader implementation</a>
 */
final class PackageTarInputStream extends TarArchiveInputStream
{
	/**
	 * Creates a reader that owns the supplied input stream through its inherited close operation.
	 *
	 * @param input the owned uncompressed tar input
	 */
	PackageTarInputStream(InputStream input)
	{
		super(input);
	}

	/**
	 * Exposes completed auxiliary records as consumed before an enclosing extended-name read resumes.
	 *
	 * @return the next entry, or null at the end of the archive
	 * @throws IOException if the library cannot parse the next entry
	 */
	@Override
	public TarArchiveEntry getNextEntry() throws IOException
	{
		TarArchiveEntry entry = super.getNextEntry();
		if (entry != null && entry.isOldGNUSparse() && entry.isExtended())
		{
			entry = new CompletedSparseEntry(entry);
			setCurrentEntry(entry);
		}
		return entry;
	}

	/**
	 * Retains completed sparse geometry and the attributes used by package extraction.
	 */
	private static final class CompletedSparseEntry extends TarArchiveEntry
	{
		private final long realSize;

		/**
		 * Copies the completed member's extraction attributes and sparse map without retaining an unread-record flag.
		 *
		 * @param original the library entry after all auxiliary sparse records are consumed
		 */
		private CompletedSparseEntry(TarArchiveEntry original)
		{
			super(original.getName(), LF_GNUTYPE_SPARSE, true);
			realSize = original.getRealSize();
			setSize(original.getSize());
			setMode(original.getMode());
			setLastModifiedTime(original.getLastModifiedTime());
			setSparseHeaders(new ArrayList<>(original.getSparseHeaders()));
		}

		/**
		 * Reports the original logical size, including holes in the sparse payload.
		 *
		 * @return the original logical byte count
		 */
		@Override
		public long getRealSize()
		{
			return realSize;
		}
	}
}
