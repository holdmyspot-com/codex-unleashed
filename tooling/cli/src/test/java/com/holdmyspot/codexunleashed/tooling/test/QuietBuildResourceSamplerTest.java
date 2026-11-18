package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.QuietBuildCapture;
import com.holdmyspot.codexunleashed.tooling.QuietBuildResourceSampler;
import com.holdmyspot.codexunleashed.tooling.QuietBuildResources;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Tests watchdog sampling deadlines, retries and ownership. */
public final class QuietBuildResourceSamplerTest
{
	/** Creates sampler tests. */
	public QuietBuildResourceSamplerTest()
	{
	}

	/**
	 * Advances deadlines from each due poll and does not sample early.
	 *
	 * @throws IOException if temporary output cannot be created or removed
	 */
	@Test
	public void advancesDeadlineFromPollTime() throws IOException
	{
		Path directory = Files.createTempDirectory("quiet-build-sampler");
		Path output = directory.resolve("resources.jsonl");
		AtomicInteger reads = new AtomicInteger();
		try (DefaultToolingScope scope = new DefaultToolingScope();
			QuietBuildResourceSampler sampler = new QuietBuildResourceSampler(scope, output, Duration.ofSeconds(2),
				context -> new QuietBuildResources.Reader()
				{
					@Override
					public QuietBuildResources.Counters read()
					{
						reads.incrementAndGet();
						return linux();
					}

					@Override
					public void close()
					{
					}
				}, context()))
		{
			sampler.poll(Instant.parse("1969-12-31T23:59:59Z"));
			requireThat(reads.get(), "reads").isEqualTo(0);
			sampler.poll(Instant.EPOCH);
			sampler.poll(Instant.ofEpochSecond(1));
			requireThat(reads.get(), "reads").isEqualTo(1);
			sampler.poll(Instant.ofEpochSecond(2));
			requireThat(reads.get(), "reads").isEqualTo(2);
		}
		requireThat(Files.readAllLines(output).size(), "sampleCount").isEqualTo(2);
		Files.delete(output);
		Files.delete(directory);
	}

	/**
	 * Retries acquisition but keeps the acquired reader after read errors, and closes it once.
	 *
	 * @throws IOException if temporary output cannot be created or removed
	 */
	@Test
	public void retriesOpenAndRetainsReader() throws IOException
	{
		Path directory = Files.createTempDirectory("quiet-build-sampler");
		Path output = directory.resolve("resources.jsonl");
		AtomicInteger opens = new AtomicInteger();
		AtomicInteger reads = new AtomicInteger();
		AtomicInteger closes = new AtomicInteger();
		QuietBuildResources.Reader reader = new QuietBuildResources.Reader()
		{
			@Override
			public QuietBuildResources.Counters read() throws IOException
			{
				if (reads.incrementAndGet() == 1)
					throw new IOException("temporary read failure");
				return linux();
			}

			@Override
			public void close()
			{
				closes.incrementAndGet();
			}
		};
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			try (QuietBuildResourceSampler sampler = new QuietBuildResourceSampler(scope, output, Duration.ZERO, context ->
			{
				if (opens.incrementAndGet() == 1)
					throw new IOException("temporary open failure");
				return reader;
			}, context()))
			{
				sampler.poll(Instant.EPOCH);
				sampler.poll(Instant.ofEpochSecond(1));
				sampler.poll(Instant.ofEpochSecond(2));
				sampler.close();
				sampler.close();
				requireThat(opens.get(), "opens").isEqualTo(2);
				requireThat(reads.get(), "reads").isEqualTo(2);
				requireThat(closes.get(), "closes").isEqualTo(1);
				requireThat(scope.getArtifactJsonMapper() != null, "borrowedScopeRemainsOpen").isTrue();
			}
		}
		List<String> records = Files.readAllLines(output);
		requireThat(records.get(0), "acquisitionFailure").isEqualTo(
			"{\"error\":\"IOException: temporary open failure\",\"time\":0.000}");
		requireThat(records.get(1), "readFailure").isEqualTo(
			"{\"error\":\"IOException: temporary read failure\",\"time\":1.000}");
		Files.delete(output);
		Files.delete(directory);
	}

	/**
	 * Preserves signed and fractional intervals and tolerates an unavailable artifact path.
	 *
	 * @throws IOException if temporary output cannot be created or removed
	 */
	@Test
	public void preservesIntervalsAndToleratesAppend() throws IOException
	{
		Path directory = Files.createTempDirectory("quiet-build-sampler");
		Path absentOutput = directory.resolve("absent").resolve("resources.jsonl");
		AtomicInteger reads = new AtomicInteger();
		try (DefaultToolingScope scope = new DefaultToolingScope();
			QuietBuildResourceSampler sampler = new QuietBuildResourceSampler(scope, absentOutput,
				Duration.ofMillis(-500), context -> new QuietBuildResources.Reader()
				{
					@Override
					public QuietBuildResources.Counters read()
					{
						reads.incrementAndGet();
						return linux();
					}

					@Override
					public void close()
					{
					}
				}, context()))
		{
			sampler.poll(Instant.parse("2026-01-01T00:00:00Z"));
			sampler.poll(Instant.parse("2026-01-01T00:00:00.250Z"));
			requireThat(reads.get(), "negativeIntervalRemainsDue").isEqualTo(2);
			sampler.poll(Instant.parse("2025-12-31T23:59:59.500Z"));
			requireThat(reads.get(), "rollbackRemainsBeforeDeadline").isEqualTo(2);
		}
		Files.delete(directory);
	}

	/**
	 * Returns a minimal Linux counter record.
	 *
	 * @return empty Linux counters
	 */
	private static QuietBuildResources.LinuxCounters linux()
	{
		return new QuietBuildResources.LinuxCounters(Map.of(), BigInteger.ONE, BigInteger.ONE, BigInteger.ONE,
			BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, Map.of(), Map.of());
	}

	/**
	 * Returns the invocation context required by reader acquisition.
	 *
	 * @return empty invocation context
	 */
	private static QuietBuildCapture.Context context()
	{
		return new QuietBuildCapture.Context(Path.of("."), Path.of("."), Map.of());
	}
}
