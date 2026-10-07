package com.holdmyspot.codexunleashed.tooling.release;

import java.util.Map;
import java.math.BigInteger;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Optional;

/**
 * Supplies resolved archive timestamp overrides while retaining member timestamp text.
 *
 * @param modificationTime the optional blanket tar modification time in epoch seconds
 * @param gzipModificationTime the optional gzip header time in epoch seconds
 * @param memberModificationTimes the member-specific PAX timestamp strings
 */
public record ArchiveOptions(OptionalLong modificationTime, OptionalLong gzipModificationTime,
	Map<String, String> memberModificationTimes) implements ArchiveTimestamps
{
	/**
	 * Validates containers and retains an immutable member timestamp mapping.
	 *
	 * @param modificationTime the optional blanket tar modification time in epoch seconds
	 * @param gzipModificationTime the optional gzip header time in epoch seconds
	 * @param memberModificationTimes the member-specific PAX timestamp strings
	 * @throws NullPointerException if a container, member name, or member timestamp is null
	 */
	public ArchiveOptions
	{
		Objects.requireNonNull(modificationTime, "modificationTime");
		Objects.requireNonNull(gzipModificationTime, "gzipModificationTime");
		memberModificationTimes = Map.copyOf(memberModificationTimes);
	}

	/**
	 * Exposes the resolved blanket tar timestamp without narrowing its representation.
	 *
	 * @return the optional epoch-second timestamp
	 */
	@Override
	public Optional<BigInteger> tarModificationTime()
	{
		if (modificationTime.isPresent())
			return Optional.of(BigInteger.valueOf(modificationTime.getAsLong()));
		return Optional.empty();
	}

	/**
	 * Exposes the resolved independent gzip header timestamp.
	 *
	 * @return the optional epoch-second timestamp
	 */
	@Override
	public Optional<BigInteger> gzipHeaderTime()
	{
		if (gzipModificationTime.isPresent())
			return Optional.of(BigInteger.valueOf(gzipModificationTime.getAsLong()));
		return Optional.empty();
	}

	/**
	 * Selects source member times and the supplied clock for the gzip header.
	 *
	 * @return the options without timestamp overrides
	 */
	public static ArchiveOptions defaults()
	{
		return new ArchiveOptions(OptionalLong.empty(), OptionalLong.empty(), Map.of());
	}
}
