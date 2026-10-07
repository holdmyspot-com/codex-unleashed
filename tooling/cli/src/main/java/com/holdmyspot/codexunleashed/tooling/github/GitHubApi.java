package com.holdmyspot.codexunleashed.tooling.github;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Preserves GitHub HTTP status separately from response parsing and transport failures.
 */
@FunctionalInterface
public interface GitHubApi
{
	/**
	 * Defines the HTTP methods used by release reservation.
	 */
	enum Method
	{
		/** Retrieves an existing resource. */
		GET,
		/** Creates a resource without replacing an existing reference. */
		POST
	}

	/**
	 * Retains a complete HTTP response before release policy interprets its status and body.
	 *
	 * @param statusCode the HTTP response status
	 * @param body the response body
	 */
	record Response(int statusCode, String body)
	{
		/**
		 * Creates a response with an explicit status and body.
		 *
		 * @param statusCode the HTTP response status
		 * @param body the response body
		 * @throws NullPointerException if {@code body} is null
		 * @throws IllegalArgumentException if the status is outside the HTTP status classes
		 */
		public Response
		{
			Objects.requireNonNull(body, "body");
			if (statusCode < 100 || statusCode > 599)
				throw new IllegalArgumentException("HTTP status must be between 100 and 599");
		}
	}

	/**
	 * Performs a GitHub request and retains transport failures for the caller.
	 *
	 * @param method the HTTP method
	 * @param path the repository-relative GitHub API path
	 * @param payload the creation fields, or an empty map for GET
	 * @return the complete response, including ordinary error statuses
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if the request fails before a complete response is available
	 */
	Response request(Method method, String path, Map<String, String> payload) throws IOException;
}
