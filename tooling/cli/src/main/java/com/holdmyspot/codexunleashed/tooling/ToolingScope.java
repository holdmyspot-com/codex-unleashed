package com.holdmyspot.codexunleashed.tooling;

import io.github.cowwoc.pouch10.core.Scope;
import com.holdmyspot.codexunleashed.scheduler.LinuxBorrowedStreams;
import com.holdmyspot.codexunleashed.scheduler.LinuxFifoBindings;
import java.lang.foreign.Linker;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Shared values borrowed during one tooling invocation. The composition boundary closes the scope after its borrowers
 * finish. Getters may be called concurrently while the scope is open; borrowers must not close the returned values.
 */
public interface ToolingScope extends Scope
{
	/**
	 * Returns the platform native linker; arenas and native owners remain the borrower's responsibility.
	 * @return the shared linker
	 * @throws IllegalStateException if the scope is closed
	 */
	Linker getNativeLinker();

	/**
	 * Returns immutable Linux LP64 FIFO functions; jobserver owners retain descriptor and path lifetimes.
	 * @return this scope's shared native bindings
	 * @throws IllegalStateException if the scope is closed
	 * @throws UnsupportedOperationException if Linux LP64 or a required libc function is unavailable
	 */
	LinuxFifoBindings getLinuxFifoBindings();

	/**
	 * Returns immutable Linux borrowed-stream functions; listeners and imported descriptors remain borrower-owned.
	 * @return this scope's shared native bindings
	 * @throws IllegalStateException if the scope is closed
	 * @throws UnsupportedOperationException if the platform or libc ABI is unverified
	 */
	LinuxBorrowedStreams.NativeBindings getLinuxBorrowedStreamBindings();

	/**
	 * Returns immutable Unix timestamp write bindings; each operation owns its temporary native arguments.
	 * @return this scope's shared Unix timestamp writer
	 * @throws IllegalStateException if the scope is closed
	 * @throws UnsupportedOperationException if the native ABI or function is unavailable
	 */
	UnixFileTimes getUnixFileTimes();

	/**
	 * Returns immutable Unix metadata bindings for the current operating system.
	 * @return this scope's shared Unix timestamp reader
	 * @throws IllegalStateException if the scope is closed
	 * @throws UnsupportedOperationException if the required native function is unavailable
	 */
	UnixFileMetadata getUnixFileMetadata();

	/**
	 * Returns immutable Windows timestamp bindings, preserving the system library's existing process lifetime.
	 * @return this scope's shared Windows timestamp adapter
	 * @throws IllegalStateException if the scope is closed
	 * @throws UnsupportedOperationException if the native ABI or a required function is unavailable
	 */
	WindowsFileTimes getWindowsFileTimes();

	/**
	 * Returns this scope's immutable JSON parser, which rejects trailing JSON values.
	 * @return the same mapper on repeated calls within this scope
	 * @throws IllegalStateException if the scope is closed
	 */
	JsonMapper getJsonMapper();

	/**
	 * Returns the immutable ASCII artifact mapper; writers select ordering per document.
	 * @return this scope's shared mapper
	 * @throws IllegalStateException if the scope is closed
	 */
	JsonMapper getArtifactJsonMapper();

	/**
	 * Returns the bounded allocation wire parser, including duplicate and trailing-token rejection.
	 * @return this scope's shared immutable mapper
	 * @throws IllegalStateException if the scope is closed
	 */
	JsonMapper getAllocationMessageMapper();

	/**
	 * Returns the bounded parser for Frida error envelopes and generated allocation controls.
	 * @return this scope's shared immutable mapper
	 * @throws IllegalStateException if the scope is closed
	 */
	JsonMapper getAllocationEnvelopeMapper();

	/**
	 * Returns the default JSON mapper for leader configuration and copied leader envelopes.
	 * @return this scope's shared immutable mapper
	 * @throws IllegalStateException if the scope is closed
	 */
	JsonMapper getLeaderJsonMapper();

	/**
	 * Returns this scope's immutable upstream-action YAML parser.
	 * @return the same mapper on repeated calls within this scope
	 * @throws IllegalStateException if the scope is closed
	 */
	YAMLMapper getYamlMapper();

	/**
	 * Returns the pattern selecting upstream stable release tags with three decimal version components.
	 * @return this scope's shared immutable pattern
	 * @throws IllegalStateException if the scope is closed
	 */
	Pattern getStableReleaseTagPattern();
}
