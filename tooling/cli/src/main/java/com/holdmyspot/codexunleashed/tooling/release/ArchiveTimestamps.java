package com.holdmyspot.codexunleashed.tooling.release;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves archive timestamp inputs only when the selected format consumes them.
 */
public interface ArchiveTimestamps
{
	/**
	 * Resolves the optional blanket tar timestamp.
	 *
	 * @return the timestamp in epoch seconds, or absence for source file times
	 * @throws IOException if a consumed timestamp input is invalid
	 */
	Optional<BigInteger> tarModificationTime() throws IOException;

	/**
	 * Resolves the independent optional gzip header timestamp.
	 *
	 * @return the timestamp in epoch seconds, or absence for the supplied clock
	 * @throws IOException if a consumed timestamp input is invalid
	 */
	Optional<BigInteger> gzipHeaderTime() throws IOException;

	/**
	 * Resolves member timestamp mappings while preserving explicit timestamp strings.
	 *
	 * @return the exact member timestamp strings
	 * @throws IOException if a consumed mapping is invalid
	 */
	Map<String, String> memberModificationTimes() throws IOException;
}
