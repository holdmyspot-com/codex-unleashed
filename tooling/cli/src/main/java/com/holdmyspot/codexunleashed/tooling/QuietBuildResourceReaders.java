package com.holdmyspot.codexunleashed.tooling;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Selects the host-counter reader factory for one quiet-build invocation.
 */
public final class QuietBuildResourceReaders
{
	/**
	 * Selects a factory for the supplied platform without acquiring native resources. The caller keeps the borrowed
	 * scope and capture access available until every reader opened by the factory has been closed. Each opened reader
	 * belongs to its caller, normally the resource sampler.
	 *
	 * @param scope    invocation scope supplying shared native and JSON access
	 * @param capture  borrowed diagnostic command access used by Darwin readers
	 * @param platform platform whose host counters are required
	 * @return factory for Linux procfs, Darwin host counters, or Windows host counters
	 * @throws NullPointerException if any argument is null
	 */
	public static QuietBuildResources.ReaderFactory factory(ToolingScope scope, QuietBuildCapture.Access capture,
		QuietBuildDump.Platform platform)
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(capture, "capture");

		return switch (platform)
		{
			case LINUX -> LinuxQuietBuildResources.factory(Path.of("/proc"));
			case DARWIN -> DarwinQuietBuildResources.factory(scope, capture);
			case WINDOWS -> WindowsQuietBuildResources.factory(scope);
		};
	}

	/**
	 * Prevents construction of this factory-selection utility.
	 */
	private QuietBuildResourceReaders()
	{
	}
}
