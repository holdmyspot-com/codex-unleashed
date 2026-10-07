package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Verifies source timestamp normalization without modifying Git metadata.
 */
public final class SourceTimestampsTest
{
	/**
	 * Creates the normalization tests.
	 */
	public SourceTimestampsTest()
	{
	}

	/**
	 * Verifies that normalization preserves Cargo's compiled library cache after source times change.
	 * Requires a locally installed Cargo toolchain; the fixture has no network dependencies.
	 *
	 * @throws IOException if fixture access or the Cargo command fails
	 * @throws InterruptedException if the Cargo wait is interrupted
	 */
	@Test
	public void preservesCargoLibraryCache() throws IOException, InterruptedException
	{
		Path root = Files.createTempDirectory("source-times-cargo-");
		try
		{
			Path source = Files.createDirectories(root.resolve("src")).resolve("lib.rs");
			Files.writeString(source, "pub fn value() -> u8 { 7 }\n");
			Files.writeString(root.resolve("Cargo.toml"),
				"[package]\nname='timestamp-demo'\nversion='0.1.0'\nedition='2021'\n");
			SourceTimestamps.normalize(root, 1_000_000_000);
			buildCargo(root);
			Files.setLastModifiedTime(source, FileTime.from(Instant.now().plusSeconds(10)));
			SourceTimestamps.normalize(root, 1_000_000_000);
			List<String> messages = buildCargo(root).lines().toList();
			int artifacts = 0;
			JsonMapper mapper = JsonMapper.builder().build();
			for (String message : messages)
			{
				JsonNode value = mapper.readTree(message);
				if ("compiler-artifact".equals(value.get("reason").asString()))
				{
					assertTrue(value.get("fresh").booleanValue(), message);
					++artifacts;
				}
			}
			assertTrue(artifacts > 0, "Cargo did not report a library artifact");
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Verifies files, parent directories, access times, and both Git metadata layouts.
	 *
	 * @throws IOException if fixture creation, inspection, or cleanup fails
	 */
	@Test
	public void normalizesFilesAndDirectories() throws IOException
	{
		Path root = Files.createTempDirectory("source-times-");
		try
		{
			Path source = Files.createDirectories(root.resolve("source/nested"));
			Path code = Files.writeString(source.resolve("code.txt"), "fixture");
			Path metadata = Files.createDirectories(root.resolve(".git/objects"));
			Path object = Files.writeString(metadata.resolve("object"), "retained");
			Path worktree = Files.writeString(source.resolve(".git"), "gitdir: metadata");
			FileTime untouched = FileTime.from(Instant.ofEpochSecond(100));
			Files.setLastModifiedTime(object, untouched);
			Files.setLastModifiedTime(worktree, untouched);
			Files.setLastModifiedTime(metadata, untouched);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ByteArrayOutputStream errors = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
			{
				assertEquals(Main.run(new String[]{"normalize-source-timestamps", root.toString(), "1600000000"},
					new ByteArrayInputStream(new byte[0]), out, err), 0, errors.toString(StandardCharsets.UTF_8));
			}
			FileTime expected = FileTime.from(Instant.ofEpochSecond(1_600_000_000));
			for (Path path : new Path[]{root, root.resolve("source"), source, code})
			{
				BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
				assertEquals(attributes.lastModifiedTime(), expected, path.toString());
				assertEquals(attributes.lastAccessTime(), expected, path.toString());
			}
			assertEquals(Files.getLastModifiedTime(object), untouched);
			assertEquals(Files.getLastModifiedTime(worktree), untouched);
			assertEquals(Files.getLastModifiedTime(metadata), untouched);
			assertEquals(output.toString(StandardCharsets.UTF_8), "");
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Builds the dependency-free fixture with explicitly isolated Cargo storage.
	 *
	 * @param root the fixture directory
	 * @return Cargo's JSON event stream
	 * @throws IOException if command startup or output access fails
	 * @throws InterruptedException if the command wait is interrupted
	 */
	private static String buildCargo(Path root) throws IOException, InterruptedException
	{
		Path output = root.resolve("cargo-output");
		Path errors = root.resolve("cargo-errors");
		ProcessBuilder builder = new ProcessBuilder("cargo", "build", "--offline", "--release", "--message-format=json");
		builder.directory(root.toFile()).redirectOutput(output.toFile()).redirectError(errors.toFile());
		builder.environment().put("CARGO_HOME", root.resolve("cargo-home").toString());
		builder.environment().put("CARGO_TARGET_DIR", root.resolve("target").toString());
		builder.environment().put("XDG_CACHE_HOME", root.resolve("xdg").toString());
		try (Process process = builder.start())
		{
			try
			{
				assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Cargo fixture build timed out");
				assertEquals(process.exitValue(), 0, Files.readString(errors));
			}
			finally
			{
				if (process.isAlive())
					process.destroyForcibly().waitFor();
			}
		}
		return Files.readString(output);
	}

	/**
	 * Removes one owned fixture tree without following symbolic links.
	 *
	 * @param root the owned fixture directory
	 * @throws IOException if fixture cleanup fails
	 */
	private static void deleteTree(Path root) throws IOException
	{
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.delete(path);
		}
	}
}
