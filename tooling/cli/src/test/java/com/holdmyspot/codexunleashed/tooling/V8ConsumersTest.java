package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.util.List;
import org.testng.annotations.Test;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/** Preserves every consumer-selector assertion in the original V8 source policy test. */
public final class V8ConsumersTest
{
	/** Creates selector policy tests. */
	public V8ConsumersTest()
	{
	}

	/**
	 * Requires all ten library and ten binding selectors to carry the selected version.
	 *
	 * @throws IOException if the complete fixture is incorrectly refused
	 */
	@Test
	public void checksEveryResolvedSelector() throws IOException
	{
		List<String> selectors = List.of(
			":v8_146_4_0_aarch64_apple_darwin_bazel",
			":v8_146_4_0_aarch64_pc_windows_gnullvm",
			":v8_146_4_0_aarch64_pc_windows_msvc",
			":v8_146_4_0_aarch64_unknown_linux_gnu_bazel",
			":v8_146_4_0_aarch64_unknown_linux_musl_release_base",
			":v8_146_4_0_x86_64_apple_darwin_bazel",
			":v8_146_4_0_x86_64_pc_windows_gnullvm",
			":v8_146_4_0_x86_64_pc_windows_msvc",
			":v8_146_4_0_x86_64_unknown_linux_gnu_bazel",
			":v8_146_4_0_x86_64_unknown_linux_musl_release",
			":src_binding_release_aarch64_apple_darwin_146_4_0_release",
			":src_binding_release_aarch64_pc_windows_gnullvm_146_4_0_release",
			":src_binding_release_aarch64_pc_windows_msvc_146_4_0_release",
			":src_binding_release_aarch64_unknown_linux_gnu_146_4_0_release",
			":src_binding_release_aarch64_unknown_linux_musl_146_4_0_release",
			":src_binding_release_x86_64_apple_darwin_146_4_0_release",
			":src_binding_release_x86_64_pc_windows_gnullvm_146_4_0_release",
			":src_binding_release_x86_64_pc_windows_msvc_146_4_0_release",
			":src_binding_release_x86_64_unknown_linux_gnu_146_4_0_release",
			":src_binding_release_x86_64_unknown_linux_musl_146_4_0_release");
		String source = String.join("\n", selectors);
		V8Consumers.check(source, "146.4.0");
		for (String selector : selectors)
		{
			IOException failure = expectThrows(IOException.class,
				() -> V8Consumers.check(source.replace(selector, ""), "146.4.0"));
			assertTrue(failure.getMessage().contains(selector), failure.getMessage());
		}
		expectThrows(IOException.class, () -> V8Consumers.check(source, "147.0.0"));
	}
}
