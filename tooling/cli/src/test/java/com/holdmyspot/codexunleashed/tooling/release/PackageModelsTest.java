package com.holdmyspot.codexunleashed.tooling.release;

import java.util.Arrays;
import java.util.List;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies the retained target inventory, variants, and host-release defaults.
 */
public final class PackageModelsTest
{
	/**
	 * Creates the package-model tests.
	 */
	public PackageModelsTest()
	{
	}

	/**
	 * Preserves all eight target triples and their platform-specific resource names.
	 */
	@Test
	public void preservesTargets()
	{
		assertEquals(Arrays.stream(PackageTarget.values()).map(PackageTarget::triple).sorted().toList(), List.of(
			"aarch64-apple-darwin", "aarch64-pc-windows-msvc", "aarch64-unknown-linux-gnu",
			"aarch64-unknown-linux-musl", "x86_64-apple-darwin", "x86_64-pc-windows-msvc",
			"x86_64-unknown-linux-gnu", "x86_64-unknown-linux-musl"));
		PackageTarget linux = PackageTarget.fromTriple("x86_64-unknown-linux-musl");
		assertTrue(linux.isLinux());
		assertFalse(linux.isWindows());
		assertEquals(linux.ripgrepName(), "rg");
		assertEquals(linux.dotslashPlatform(), "linux-x86_64");
		PackageTarget windows = PackageTarget.fromTriple("aarch64-pc-windows-msvc");
		assertEquals(windows.executableSuffix(), ".exe");
		assertEquals(windows.ripgrepName(), "rg.exe");
		assertEquals(windows.dotslashPlatform(), "windows-aarch64");
		expectThrows(IllegalArgumentException.class, () -> PackageTarget.fromTriple("X86_64-UNKNOWN-LINUX-MUSL"));
	}

	/**
	 * Keeps Codex and app-server identities separate and applies only the target's executable suffix.
	 */
	@Test
	public void preservesVariants()
	{
		assertEquals(PackageVariant.values().length, 2);
		PackageVariant server = PackageVariant.fromName("codex-app-server");
		assertEquals(server.cargoBinary(), "codex-app-server");
		assertEquals(server.entrypointName(PackageTarget.fromTriple("x86_64-pc-windows-msvc")), "codex-app-server.exe");
		assertEquals(PackageVariant.fromName("codex").entrypointName(
			PackageTarget.fromTriple("aarch64-apple-darwin")), "codex");
		expectThrows(IllegalArgumentException.class, () -> PackageVariant.fromName("Codex"));
	}

	/**
	 * Uses musl on Linux and preserves architecture aliases while rejecting unsupported hosts.
	 */
	@Test
	public void selectsHostDefaults()
	{
		assertEquals(PackageTarget.forHost("Linux", "AMD64").triple(), "x86_64-unknown-linux-musl");
		assertEquals(PackageTarget.forHost("darwin", "ARM64").triple(), "aarch64-apple-darwin");
		assertEquals(PackageTarget.forHost("Windows", "x86_64").triple(), "x86_64-pc-windows-msvc");
		IllegalArgumentException failure = expectThrows(IllegalArgumentException.class, () ->
			PackageTarget.forHost("freebsd", "riscv64"));
		assertTrue(failure.getMessage().contains("Pass --target explicitly"));
		assertTrue(failure.getMessage().contains("aarch64-apple-darwin"));
	}
}
