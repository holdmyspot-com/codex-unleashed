package com.holdmyspot.codexunleashed.tooling;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

/** Checks Windows and Unix filename policies with explicit filesystem providers, without claiming native execution. */
public final class V8FilenamePolicyTest
{
	/** Creates filename policy tests. */
	public V8FilenamePolicyTest()
	{
	}

	/**
	 * Requires recursive Windows discovery to retain original filename case while accepting its case-insensitive name.
	 *
	 * @throws IOException if fixture or source discovery operations fail
	 */
	@Test
	public void acceptsWindowsCaseVariantPair() throws IOException
	{
		try (FileSystem filesystem = Jimfs.newFileSystem(Configuration.windows()))
		{
			Path root = filesystem.getPath("C:/cargo-output");
			Path directory = Files.createDirectories(root.resolve("x86_64-pc-windows-msvc/release/build/a/out"));
			Path library = Files.writeString(directory.resolve("RUSTY_V8.LIB"), "archive");
			Path binding = Files.writeString(directory.resolve("SRC_BINDING.RS"), "binding");
			assertEquals(library.getFileName(), filesystem.getPath("rusty_v8.lib"));
			V8Artifacts.Pair selected = V8Artifacts.upstreamPaths("x86_64-pc-windows-msvc", root);
			assertEquals(selected.library().toString(), library.toString());
			assertEquals(selected.binding().toString(), binding.toString());
		}
	}

	/**
	 * Keeps Unix matching case-sensitive and creates comparison paths in the caller's filesystem provider.
	 *
	 * @throws IOException if fixture or source discovery operations fail
	 */
	@Test
	public void retainsUnixCaseSensitivePolicy() throws IOException
	{
		try (FileSystem filesystem = Jimfs.newFileSystem(Configuration.unix()))
		{
			Path root = filesystem.getPath("/cargo-output");
			Path directory = Files.createDirectories(root.resolve("x86_64-unknown-linux-gnu/release/build/a/out"));
			Files.writeString(directory.resolve("LIBRUSTY_V8.A"), "archive");
			Files.writeString(directory.resolve("SRC_BINDING.RS"), "binding");
			V8Artifacts.Pair expected = new V8Artifacts.Pair(
				root.resolve("x86_64-unknown-linux-gnu/release/gn_out/obj/librusty_v8.a"),
				root.resolve("x86_64-unknown-linux-gnu/release/gn_out/src_binding.rs"));
			assertEquals(V8Artifacts.upstreamPaths("x86_64-unknown-linux-gnu", root), expected);
			Path library = Files.writeString(directory.resolve("librusty_v8.a"), "correct archive");
			Path binding = Files.writeString(directory.resolve("src_binding.rs"), "correct binding");
			assertEquals(V8Artifacts.upstreamPaths("x86_64-unknown-linux-gnu", root), new V8Artifacts.Pair(library, binding));
		}
	}
}
