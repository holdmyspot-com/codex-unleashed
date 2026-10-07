package com.holdmyspot.codexunleashed.tooling;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validates repository identifiers used in the project's GitHub API paths without changing their spelling.
 */
public final class GitHubRepositories
{
	private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

	/**
	 * Prevents construction.
	 */
	private GitHubRepositories()
	{
	}

	/**
	 * Validates an owner/name repository identifier.
	 *
	 * @param repository the raw repository identifier
	 * @throws NullPointerException if {@code repository} is null
	 * @throws IllegalArgumentException if the identifier has an invalid form
	 */
	public static void validate(String repository)
	{
		Objects.requireNonNull(repository, "repository");
		if (!REPOSITORY.matcher(repository).matches())
			throw new IllegalArgumentException("Invalid repository; expected owner/name: " + repository);
	}
}
