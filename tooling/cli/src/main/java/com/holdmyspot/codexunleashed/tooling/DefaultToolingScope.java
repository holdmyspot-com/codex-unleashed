package com.holdmyspot.codexunleashed.tooling;

import io.github.cowwoc.pouch10.core.AbstractScope;
import io.github.cowwoc.pouch10.core.ConcurrentLazyFactory;
import io.github.cowwoc.pouch10.core.Scopes;
import java.time.Duration;
import java.lang.foreign.Linker;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Owns lazily initialized shared values for one tooling invocation, independently of every other invocation.
 * Getters support concurrent borrowers. Close only after those borrowers finish; registered children close first.
 */
public final class DefaultToolingScope extends AbstractScope implements ToolingScope
{
	/** Platform service without independent native resource ownership. */
	private final ConcurrentLazyFactory<Linker> nativeLinker = ConcurrentLazyFactory.create(Linker::nativeLinker, value -> {});
	/** Immutable Unix write bindings, resolved only for timestamps needing native precision. */
	private final ConcurrentLazyFactory<UnixFileTimes> unixFileTimes = ConcurrentLazyFactory.create(
		() -> new UnixFileTimes(this), value -> {});
	/** Immutable Unix read bindings, resolved only for timestamps needing native precision. */
	private final ConcurrentLazyFactory<UnixFileMetadata> unixFileMetadata = ConcurrentLazyFactory.create(
		() -> new UnixFileMetadata(this), value -> {});
	/** Immutable Windows bindings; system library and per-operation file handle lifetimes are preserved. */
	private final ConcurrentLazyFactory<WindowsFileTimes> windowsFileTimes = ConcurrentLazyFactory.create(
		() -> new WindowsFileTimes(this), value -> {});
	/** Immutable parser with the existing single-document constraint. */
	private final ConcurrentLazyFactory<JsonMapper> retentionJson = ConcurrentLazyFactory.create(
		() -> JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build(), value -> {});
	/** Immutable ASCII serialization configuration; each call owns its printer and writer. */
	private final ConcurrentLazyFactory<JsonMapper> artifactJson = ConcurrentLazyFactory.create(
		() -> JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).
			disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE, JsonWriteFeature.ESCAPE_FORWARD_SLASHES).build(), value -> {});
	/** Immutable upstream YAML parser. */
	private final ConcurrentLazyFactory<YAMLMapper> yaml = ConcurrentLazyFactory.create(
		() -> YAMLMapper.builder().build(), value -> {});
	/** Immutable stable-tag selector shared by all retention records in this invocation. */
	private final ConcurrentLazyFactory<Pattern> stableReleaseTag = ConcurrentLazyFactory.create(
		() -> Pattern.compile("rust-v[0-9]+\\.[0-9]+\\.[0-9]+"), value -> {});
	/** Marks the start of idempotent scope disposal. */
	private final AtomicBoolean closed = new AtomicBoolean();

	/** Constructs an open scope without creating its shared values. */
	public DefaultToolingScope()
	{
	}

	@Override
	public Linker getNativeLinker()
	{
		ensureOpen();
		return nativeLinker.getValue();
	}

	@Override
	public UnixFileTimes getUnixFileTimes()
	{
		ensureOpen();
		return unixFileTimes.getValue();
	}

	@Override
	public UnixFileMetadata getUnixFileMetadata()
	{
		ensureOpen();
		return unixFileMetadata.getValue();
	}

	@Override
	public WindowsFileTimes getWindowsFileTimes()
	{
		ensureOpen();
		return windowsFileTimes.getValue();
	}

	@Override
	public JsonMapper getJsonMapper()
	{
		ensureOpen();
		return retentionJson.getValue();
	}

	@Override
	public JsonMapper getArtifactJsonMapper()
	{
		ensureOpen();
		return artifactJson.getValue();
	}

	@Override
	public YAMLMapper getYamlMapper()
	{
		ensureOpen();
		return yaml.getValue();
	}

	@Override
	public Pattern getStableReleaseTagPattern()
	{
		ensureOpen();
		return stableReleaseTag.getValue();
	}

	@Override
	public boolean isClosed()
	{
		return closed.get();
	}

	/** Releases children after borrowers finish and invalidates the immutable shared value factories. */
	@Override
	public void close()
	{
		if (closed.compareAndSet(false, true) == false)
			return;
		Scopes.runAll(() -> children.shutdown(Duration.ZERO), stableReleaseTag::close, yaml::close,
			artifactJson::close, retentionJson::close, windowsFileTimes::close, unixFileMetadata::close, unixFileTimes::close,
			nativeLinker::close);
	}
}
