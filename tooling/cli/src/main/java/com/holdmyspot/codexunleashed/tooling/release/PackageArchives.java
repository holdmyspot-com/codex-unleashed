package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.CommandRunner;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;

/**
 * Writes canonical package archives with retained member metadata and explicit compression boundaries.
 */
public final class PackageArchives
{
	private static final BigInteger MAX_GZIP_TIME = BigInteger.valueOf(4_294_967_295L);
	private static final BigInteger MAX_TAR_TIME = BigInteger.valueOf(TarConstants.MAXSIZE);

	/**
	 * Prevents construction.
	 */
	private PackageArchives()
	{
	}

	/**
	 * Describes an archive output and its resolved timestamp and compression inputs.
	 *
	 * @param directory the package input directory
	 * @param output the archive output path
	 * @param force whether an existing output may be replaced
	 * @param temporaryDirectory the owned parent for zstd temporary input
	 * @param options the resolved archive timestamps
	 * @param zstdCommand the resolved native or DotSlash zstd command prefix
	 */
	public record Request(Path directory, Path output, boolean force, Path temporaryDirectory, ArchiveTimestamps options,
		List<String> zstdCommand)
	{
		/**
		 * Validates required arguments and retains an immutable compression prefix.
		 *
		 * @param directory the package input directory
		 * @param output the archive output path
		 * @param force whether an existing output may be replaced
		 * @param temporaryDirectory the owned parent for zstd temporary input
		 * @param options the resolved archive timestamps
		 * @param zstdCommand the resolved native or DotSlash zstd command prefix
		 * @throws NullPointerException if any reference argument or command element is null
		 */
		public Request
		{
			Objects.requireNonNull(directory, "directory");
			Objects.requireNonNull(output, "output");
			Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
			Objects.requireNonNull(options, "options");
			zstdCommand = List.copyOf(zstdCommand);
		}
	}

	/**
	 * Writes tar.gz, tgz, tar.zst, or ZIP using the output suffix and explicit replacement policy.
	 *
	 * @param request the archive request
	 * @param clock the gzip header clock when no explicit gzip time is supplied
	 * @param compressor the zstd invocation boundary
	 * @throws IOException if output preparation, archiving, compression, or cleanup fails
	 * @throws NullPointerException if any argument is null
	 */
	public static void write(Request request, Clock clock, CommandRunner compressor) throws IOException
	{
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(clock, "clock");
		Objects.requireNonNull(compressor, "compressor");
		Path directory = request.directory().toFile().getCanonicalFile().toPath();
		Path output = request.output().toFile().getCanonicalFile().toPath();
		if (output.startsWith(directory))
			throw new IOException("Archive output must be outside the package directory: " + output);
		Files.createDirectories(output.getParent());
		if (Files.exists(output))
		{
			if (!request.force())
				throw new IOException("Archive output already exists: " + output);
			if (Files.isDirectory(output))
				throw new IOException("Archive output is a directory: " + output);
			Files.delete(output);
		}
		String name = output.getFileName().toString();
		if (name.endsWith(".tar.gz") || name.endsWith(".tgz"))
			writeGzip(directory, output, request.options(), clock);
		else if (name.endsWith(".tar.zst"))
			writeZstd(directory, output, request, compressor);
		else if (name.endsWith(".zip"))
			writeZip(directory, output);
		else
			throw new IOException("Unsupported archive suffix for " + output + ". Use .tar.gz, .tgz, .tar.zst, or .zip.");
	}

	/**
	 * Writes a gzip stream with the archive filename and independent header time.
	 *
	 * @param directory the package directory
	 * @param output the archive path
	 * @param options the tar and gzip timestamp policy
	 * @param clock the fallback gzip header clock
	 * @throws IOException if output or compression fails
	 */
	private static void writeGzip(Path directory, Path output, ArchiveTimestamps options, Clock clock) throws IOException
	{
		BigInteger timestamp = options.gzipHeaderTime().orElseGet(() ->
			BigInteger.valueOf(clock.instant().getEpochSecond()));
		if (timestamp.signum() < 0 || timestamp.compareTo(MAX_GZIP_TIME) > 0)
			throw new IOException("Gzip modification time must be between 0 and " + MAX_GZIP_TIME);
		GzipParameters parameters = new GzipParameters();
		parameters.setCompressionLevel(9);
		parameters.setModificationInstant(Instant.ofEpochSecond(timestamp.longValueExact()));
		String name = output.getFileName().toString();
		if (name.endsWith(".gz"))
			name = name.substring(0, name.length() - 3);
		if (java.nio.charset.StandardCharsets.ISO_8859_1.newEncoder().canEncode(name))
			parameters.setFileName(name);
		try (OutputStream file = Files.newOutputStream(output);
			GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(file, parameters))
		{
			writeTar(directory, gzip, options);
		}
	}

	/**
	 * Writes uncompressed tar input and removes the compressor's temporary tree on every outcome.
	 *
	 * @param directory the package directory
	 * @param output the archive path
	 * @param request the temporary storage, timestamp, and compression inputs
	 * @param compressor the zstd command boundary
	 * @throws IOException if archiving, compression, or cleanup fails
	 */
	private static void writeZstd(Path directory, Path output, Request request, CommandRunner compressor)
		throws IOException
	{
		if (request.zstdCommand().isEmpty())
			throw new IOException("zstd is required to write .tar.zst archives");
		Files.createDirectories(request.temporaryDirectory());
		try (TemporaryTar temporary = new TemporaryTar(Files.createTempDirectory(request.temporaryDirectory(),
			"codex-package-archive-")))
		{
			Path tar = temporary.directory().resolve("package.tar");
			try (OutputStream stream = Files.newOutputStream(tar))
			{
				writeTar(directory, stream, request.options());
			}
			List<String> command = new ArrayList<>(request.zstdCommand());
			command.addAll(List.of("-T0", "-19", "-f", tar.toString(), "-o", output.toString()));
			compressor.run(command);
		}
	}

	/**
	 * Writes ordered tar members with source attributes and explicit timestamp overrides.
	 *
	 * @param directory the package directory
	 * @param output the uncompressed output stream, closed by this operation
	 * @param options the timestamp policy
	 * @throws IOException if member traversal, reading, or serialization fails
	 */
	private static void writeTar(Path directory, OutputStream output, ArchiveTimestamps options) throws IOException
	{
		try (TarArchiveOutputStream archive = new TarArchiveOutputStream(output))
		{
			archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
			archive.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
			archive.setAddPaxHeadersForNonAsciiNames(true);
			for (Path file : entries(directory))
			{
				String name = relativeName(directory, file);
				TarArchiveEntry entry = tarEntry(file, name, options);
				archive.putArchiveEntry(entry);
				if (entry.isFile())
					copyPayload(file, archive);
				archive.closeArchiveEntry();
			}
		}
	}

	/**
	 * Selects member overrides before consuming the blanket time and retains PAX text outside ustar's range.
	 *
	 * @param file the member source
	 * @param name the portable member name
	 * @param options the lazily resolved timestamp inputs
	 * @return the member with retained source attributes and normalized ustar timestamp
	 * @throws IOException if timestamp resolution or source attribute reading fails
	 */
	private static TarArchiveEntry tarEntry(Path file, String name, ArchiveTimestamps options) throws IOException
	{
		String timestamp = options.memberModificationTimes().get(name);
		long headerTime;
		if (timestamp != null)
			headerTime = roundedHeaderTime(timestamp);
		else
		{
			Optional<BigInteger> blanket = options.tarModificationTime();
			if (blanket.isPresent())
			{
				BigInteger value = blanket.orElseThrow();
				if (value.signum() >= 0 && value.compareTo(MAX_TAR_TIME) <= 0)
					headerTime = value.longValueExact();
				else
				{
					headerTime = 0;
					timestamp = value.toString();
				}
			}
			else
			{
				timestamp = sourceTimestamp(file);
				headerTime = roundedHeaderTime(timestamp);
			}
		}
		TarArchiveEntry entry = new TimestampedEntry(file, name, timestamp);
		entry.setLastModifiedTime(FileTime.from(Instant.ofEpochSecond(headerTime)));
		return entry;
	}

	/**
	 * Rounds finite floating-point member times to even for ustar, using zero for values outside its range.
	 *
	 * @param timestamp the retained numeric timestamp spelling
	 * @return the ustar header timestamp
	 * @throws IOException if the value is invalid or non-finite
	 */
	private static long roundedHeaderTime(String timestamp) throws IOException
	{
		double value = ArchiveNumberText.parseDouble(timestamp);
		if (!Double.isFinite(value))
			throw new IOException("Tar member modification time must be finite: " + timestamp);
		double rounded = Math.rint(value);
		if (rounded < 0 || rounded > TarConstants.MAXSIZE)
			return 0;
		return (long) rounded;
	}

	/**
	 * Writes ZIP members with source file metadata, ignoring tar timestamp overrides.
	 *
	 * @param directory the package directory
	 * @param output the ZIP output path
	 * @throws IOException if member traversal, reading, or serialization fails
	 */
	private static void writeZip(Path directory, Path output) throws IOException
	{
		try (ZipArchiveOutputStream archive = new ZipArchiveOutputStream(output))
		{
			archive.setMethod(ZipArchiveOutputStream.DEFLATED);
			for (Path file : entries(directory))
			{
				StringBuilder name = new StringBuilder(relativeName(directory, file));
				if (Files.isDirectory(file))
					name.append('/');
				ZipArchiveEntry entry = new ZipArchiveEntry(file, name.toString());
				if (file.getFileSystem().supportedFileAttributeViews().contains("unix"))
					entry.setUnixMode((Integer) Files.getAttribute(file, "unix:mode"));
				archive.putArchiveEntry(entry);
				if (!entry.isDirectory())
					copyPayload(file, archive);
				archive.closeArchiveEntry();
			}
		}
	}

	/**
	 * Copies raw file bytes to an archive member stream.
	 *
	 * @param file the member source
	 * @param output the archive member stream
	 * @throws IOException if input reading or output writing fails
	 */
	private static void copyPayload(Path file, OutputStream output) throws IOException
	{
		try (InputStream input = Files.newInputStream(file))
		{
			input.transferTo(output);
		}
	}

	/**
	 * Collects recursive entries in slash-name codepoint order without following directory symlinks.
	 *
	 * @param directory the package directory
	 * @return the sorted entries
	 * @throws IOException if traversal fails
	 */
	private static List<Path> entries(Path directory) throws IOException
	{
		try (Stream<Path> paths = Files.walk(directory))
		{
			return paths.filter(file -> !file.equals(directory)).sorted((left, right) ->
				compareNames(relativeName(directory, left), relativeName(directory, right))).toList();
		}
		catch (UncheckedIOException failure)
		{
			throw failure.getCause();
		}
	}

	/**
	 * Converts filesystem components to a portable archive member name.
	 *
	 * @param directory the package directory
	 * @param file the member path
	 * @return the slash-separated name
	 */
	private static String relativeName(Path directory, Path file)
	{
		List<String> components = new ArrayList<>();
		for (Path component : directory.relativize(file))
			components.add(component.toString());
		return String.join("/", components);
	}

	/**
	 * Compares archive names by Unicode code points.
	 *
	 * @param left the first name
	 * @param right the second name
	 * @return the lexical comparison
	 */
	private static int compareNames(String left, String right)
	{
		return java.util.Arrays.compare(left.codePoints().toArray(), right.codePoints().toArray());
	}

	/**
	 * Converts the source timestamp through the retained binary floating-point boundary.
	 *
	 * @param file the member file
	 * @return the decimal source timestamp
	 * @throws IOException if attributes cannot be read
	 */
	private static String sourceTimestamp(Path file) throws IOException
	{
		Instant instant = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant();
		double timestamp = instant.getEpochSecond() + instant.getNano() / 1_000_000_000.0;
		return ArchiveNumberText.formatDouble(timestamp);
	}

	/**
	 * Deletes owned compressor temporary storage without following symlinks.
	 *
	 * @param directory the owned temporary directory
	 * @throws IOException if traversal or deletion fails
	 */
	private static void deleteTree(Path directory) throws IOException
	{
		try (Stream<Path> paths = Files.walk(directory))
		{
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.delete(path);
		}
		catch (UncheckedIOException failure)
		{
			throw failure.getCause();
		}
	}

	/**
	 * Owns the compressor's temporary input tree.
	 *
	 * @param directory the newly allocated temporary directory
	 */
	private record TemporaryTar(Path directory) implements AutoCloseable
	{
		/**
		 * Removes the temporary input tree, preserving any primary failure through resource cleanup.
		 *
		 * @throws IOException if traversal or deletion fails
		 */
		@Override
		public void close() throws IOException
		{
			deleteTree(directory);
		}
	}

	/**
	 * Supplies exact timestamp text to the archive library's PAX header writer.
	 */
	private static final class TimestampedEntry extends TarArchiveEntry
	{
		private final String timestamp;

		/**
		 * Retains source attributes and the optional exact PAX timestamp.
		 *
		 * @param file the member source
		 * @param name the archive member name
		 * @param timestamp the optional exact timestamp text, or null when no PAX override applies
		 * @throws IOException if source attributes cannot be read
		 */
		private TimestampedEntry(Path file, String name, String timestamp) throws IOException
		{
			super(file, name, LinkOption.NOFOLLOW_LINKS);
			if (file.getFileSystem().supportedFileAttributeViews().contains("unix"))
				setMode((Integer) Files.getAttribute(file, "unix:mode", LinkOption.NOFOLLOW_LINKS));
			setLastAccessTime(null);
			setStatusChangeTime(null);
			setCreationTime(null);
			this.timestamp = timestamp;
		}

		/**
		 * Retains library-supplied headers and the exact member timestamp text.
		 *
		 * @return the member's extra PAX headers
		 */
		@Override
		public Map<String, String> getExtraPaxHeaders()
		{
			Map<String, String> headers = new LinkedHashMap<>(super.getExtraPaxHeaders());
			if (timestamp != null)
				headers.put("mtime", timestamp);
			return headers;
		}
	}
}
