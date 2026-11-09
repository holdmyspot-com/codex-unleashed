package com.holdmyspot.codexunleashed.tooling;

import io.github.cowwoc.pouch10.core.Scope;
import java.lang.foreign.Linker;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.toml.TomlMapper;
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
	 * Returns this scope's immutable upstream-action YAML parser.
	 * @return the same mapper on repeated calls within this scope
	 * @throws IllegalStateException if the scope is closed
	 */
	YAMLMapper getYamlMapper();

	/**
	 * Returns this scope's immutable TOML parser.
	 * @return the same mapper on repeated calls within this scope
	 * @throws IllegalStateException if the scope is closed
	 */
	TomlMapper getTomlMapper();

	/**
	 * Returns the pattern selecting upstream stable release tags with three decimal version components.
	 * @return this scope's shared immutable pattern
	 * @throws IllegalStateException if the scope is closed
	 */
	Pattern getStableReleaseTagPattern();
}
