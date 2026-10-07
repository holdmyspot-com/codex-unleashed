package com.holdmyspot.codexunleashed.tooling.github;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends GitHub REST requests with explicit credentials, bounded requests, and preserved HTTP status.
 */
public final class GitHubHttpClient implements GitHubApi, AutoCloseable
{
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
	private final URI apiBase;
	private final Optional<String> token;
	private final HttpClient client;

	/**
	 * Creates an owned client for the explicit API endpoint and credentials.
	 *
	 * @param apiBase the absolute HTTP or HTTPS API base ending with a slash
	 * @param token the bearer credential
	 * @throws NullPointerException if either argument is null
	 * @throws IllegalArgumentException if the endpoint or credential is invalid
	 */
	public GitHubHttpClient(URI apiBase, String token)
	{
		this(apiBase, requiredCredential(token));
	}

	/**
	 * Creates an owned client without an authorization header for public GitHub resources.
	 *
	 * @param apiBase the absolute HTTP or HTTPS API base ending with a slash
	 * @return the anonymous client whose resources the caller closes
	 * @throws NullPointerException if {@code apiBase} is null
	 * @throws IllegalArgumentException if the endpoint is invalid
	 */
	public static GitHubHttpClient anonymous(URI apiBase)
	{
		return new GitHubHttpClient(apiBase, Optional.empty());
	}

	/**
	 * Creates the common transport with an explicit credential-presence decision.
	 *
	 * @param apiBase the API base
	 * @param token the validated credential or explicit anonymous choice
	 * @throws NullPointerException if an argument is null
	 * @throws IllegalArgumentException if the endpoint is invalid
	 */
	private GitHubHttpClient(URI apiBase, Optional<String> token)
	{
		Objects.requireNonNull(apiBase, "apiBase");
		Objects.requireNonNull(token, "token");
		if ((!"http".equalsIgnoreCase(apiBase.getScheme()) && !"https".equalsIgnoreCase(apiBase.getScheme())) ||
			apiBase.getHost() == null || apiBase.getUserInfo() != null || apiBase.getQuery() != null ||
			apiBase.getFragment() != null || !apiBase.getPath().endsWith("/"))
			throw new IllegalArgumentException("GitHub API base must be an absolute HTTP URL ending with a slash");
		this.apiBase = apiBase;
		this.token = token;
		client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).
			followRedirects(HttpClient.Redirect.NORMAL).build();
	}

	/**
	 * Validates the existing authenticated construction contract without treating a blank token as anonymous.
	 *
	 * @param token the required bearer credential
	 * @return the present validated credential
	 * @throws NullPointerException if {@code token} is null
	 * @throws IllegalArgumentException if the credential is blank
	 */
	private static Optional<String> requiredCredential(String token)
	{
		Objects.requireNonNull(token, "token");
		if (token.isBlank())
			throw new IllegalArgumentException("GitHub bearer credential must not be blank");
		return Optional.of(token);
	}

	/**
	 * Sends a request and returns ordinary HTTP error responses for release policy to evaluate.
	 *
	 * @param method the HTTP method
	 * @param path the relative API resource path
	 * @param payload the POST fields, or an empty map for GET
	 * @return the status and strict UTF-8 response body
	 * @throws NullPointerException if any argument, payload key, or payload value is null
	 * @throws IllegalArgumentException if the path or GET payload is invalid
	 * @throws IOException if transport, encoding, or waiting fails
	 */
	@Override
	public Response request(Method method, String path, Map<String, String> payload) throws IOException
	{
		Objects.requireNonNull(method, "method");
		Objects.requireNonNull(path, "path");
		Map<String, String> fields = Map.copyOf(payload);
		if (path.isEmpty() || path.startsWith("/"))
			throw new IllegalArgumentException("GitHub API path must be relative and nonempty");
		if (method == Method.GET && !fields.isEmpty())
			throw new IllegalArgumentException("GitHub GET request must not contain creation fields");
		URI resource;
		try
		{
			resource = apiBase.resolve(new URI(null, null, path, null));
		}
		catch (URISyntaxException failure)
		{
			throw new IllegalArgumentException("Invalid GitHub API path", failure);
		}
		HttpRequest.Builder request = HttpRequest.newBuilder(resource).timeout(REQUEST_TIMEOUT).
			header("Accept", "application/vnd.github+json").
			header("Content-Type", "application/json").header("X-GitHub-Api-Version", "2022-11-28");
		token.ifPresent(value -> request.header("Authorization", "Bearer " + value));
		switch (method)
		{
			case GET -> request.GET();
			case POST -> request.POST(HttpRequest.BodyPublishers.ofString(
				JsonMapper.builder().build().writeValueAsString(fields), StandardCharsets.UTF_8));
		}
		try
		{
			HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
			String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).
				onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString();
			return new Response(response.statusCode(), body);
		}
		catch (CharacterCodingException failure)
		{
			throw new IOException("GitHub response is not valid UTF-8", failure);
		}
		catch (InterruptedException failure)
		{
			Thread.currentThread().interrupt();
			throw new IOException("GitHub request was interrupted", failure);
		}
	}

	/**
	 * Releases the owned HTTP client's connections and execution resources.
	 */
	@Override
	public void close()
	{
		client.close();
	}
}
