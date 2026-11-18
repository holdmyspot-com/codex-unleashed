package com.holdmyspot.codexunleashed.tooling;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;

/** Samples child resource counters at the watchdog's configured interval. */
public final class QuietBuildResourceSampler implements AutoCloseable
{
	/** Borrowed invocation resources. */
	private final ToolingScope scope;
	/** JSON-lines destination. */
	private final Path path;
	/** Delay between scheduled samples. */
	private final Duration interval;
	/** Lazy reader acquisition. */
	private final QuietBuildResources.ReaderFactory factory;
	/** Child execution context used for acquisition. */
	private final QuietBuildCapture.Context context;
	/** Next eligible sample deadline. */
	private Instant nextSample = Instant.EPOCH;
	/** Reader owned after successful acquisition. */
	private QuietBuildResources.Reader reader;
	/** Whether disposal has started. */
	private boolean closed;

	/**
	 * Creates a sampler that lazily acquires and owns one resource reader.
	 *
	 * @param scope borrowed invocation scope
	 * @param path JSON-lines output path
	 * @param interval delay after each scheduled sample
	 * @param factory reader acquisition factory
	 * @param context child execution context
	 */
	public QuietBuildResourceSampler(ToolingScope scope, Path path, Duration interval,
		QuietBuildResources.ReaderFactory factory, QuietBuildCapture.Context context)
	{
		this.scope = scope;
		this.path = path;
		this.interval = interval;
		this.factory = factory;
		this.context = context;
	}

	/**
	 * Samples if the supplied time has reached the current deadline.
	 *
	 * @param now current watchdog time
	 */
	public void poll(Instant now)
	{
		if (closed)
			throw new IllegalStateException("Sampler is closed");
		if (now.isBefore(nextSample))
			return;
		nextSample = now.plus(interval);

		QuietBuildResources.Outcome outcome;
		try
		{
			if (reader == null)
				reader = factory.open(context);
			outcome = reader.read();
		}
		catch (Exception e)
		{
			outcome = new QuietBuildResources.Failure(e.getClass().getSimpleName() + ": " + e.getMessage());
		}
		try
		{
			try (BufferedWriter output = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE))
			{
				QuietBuildResourceJson.write(scope, output, new QuietBuildResources.Sample(now, outcome));
			}
		}
		catch (IOException _)
		{
			// Resource observation continues even when its optional artifact cannot be appended.
		}
	}

	/** Releases the acquired reader once; the shared scope remains borrowed. */
	@Override
	@SuppressWarnings("PMD.CloseResource")
	public void close() throws IOException
	{
		if (closed)
			return;
		closed = true;
		QuietBuildResources.Reader owned = reader;
		reader = null;
		if (owned != null)
			owned.close();
	}
}
