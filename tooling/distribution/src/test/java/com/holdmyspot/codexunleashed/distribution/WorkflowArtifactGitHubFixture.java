package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Supplies paginated metadata and raw authenticated artifact bytes at the GitHub CLI process boundary. */
public final class WorkflowArtifactGitHubFixture
{
	/** Prevents construction. */
	private WorkflowArtifactGitHubFixture()
	{
	}

	/**
	 * Serves the fixture's exact API requests and rejects unexpected routes or arguments.
	 *
	 * @param args literal GitHub CLI arguments
	 * @throws IOException if the fixture archive cannot be read
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	public static void main(String[] args) throws IOException, NoSuchAlgorithmException
	{
		Path archive = Path.of(System.getenv("ARTIFACT_FIXTURE_ARCHIVE"));
		String mode = System.getenv("ARTIFACT_FIXTURE_MODE");
		if (List.of(args).equals(List.of("api",
			"repos/example/project/actions/runs/123/artifacts?per_page=100", "--paginate")))
		{
			String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").
				digest(Files.readAllBytes(archive)));
			if (mode.equals("digest"))
				digest = "0".repeat(64);
			System.out.print("{\"artifacts\":[]}\n{\"artifacts\":[{\"id\":456,\"name\":\"build bundle\", " +
				"\"expired\":false,\"digest\":\"sha256:" + digest + "\"}]}");
		}
		else if (List.of(args).equals(List.of("api", "repos/example/project/actions/artifacts/456/zip")))
		{
			if (mode.equals("download-failure"))
			{
				System.err.println("Artifact fixture download failed");
				System.exit(37);
			}
			Files.copy(archive, System.out);
		}
		else
		{
			System.err.println("Unexpected artifact API request: " + List.of(args));
			System.exit(91);
		}
	}
}
