package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds only missing package binaries and combines their paths with prebuilt overrides.
 */
public final class PackageSourceBuilds
{
	/**
	 * Prevents construction.
	 */
	private PackageSourceBuilds()
	{
	}

	/**
	 * Supplies optional prebuilt binaries instead of their corresponding Cargo targets.
	 *
	 * @param entrypoint the selected variant's entrypoint
	 * @param codeModeHost the code-mode host
	 * @param bwrap the Linux sandbox helper
	 * @param commandRunner the Windows command runner
	 * @param sandboxSetup the Windows sandbox setup helper
	 */
	public record Inputs(Optional<Path> entrypoint, Optional<Path> codeModeHost, Optional<Path> bwrap,
		Optional<Path> commandRunner, Optional<Path> sandboxSetup)
	{
		/**
		 * Validates explicit override containers.
		 *
		 * @param entrypoint the selected variant's entrypoint
		 * @param codeModeHost the code-mode host
		 * @param bwrap the Linux sandbox helper
		 * @param commandRunner the Windows command runner
		 * @param sandboxSetup the Windows sandbox setup helper
		 * @throws NullPointerException if any container is null
		 */
		public Inputs
		{
			Objects.requireNonNull(entrypoint, "entrypoint");
			Objects.requireNonNull(codeModeHost, "codeModeHost");
			Objects.requireNonNull(bwrap, "bwrap");
			Objects.requireNonNull(commandRunner, "commandRunner");
			Objects.requireNonNull(sandboxSetup, "sandboxSetup");
		}

		/**
		 * Requests source builds for every required binary.
		 *
		 * @return absent prebuilt overrides
		 */
		public static Inputs empty()
		{
			return new Inputs(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
		}
	}

	/**
	 * Defines the Cargo invocation and its explicit environment and cache storage.
	 *
	 * @param cargo the Cargo executable
	 * @param profile the requested Cargo profile
	 * @param cacheRoot the managed default target and V8 cache root
	 * @param environment the complete caller environment
	 * @param v8Releases the V8 release download base URI, ending in a slash
	 */
	public record Options(String cargo, String profile, Path cacheRoot, Map<String, String> environment, URI v8Releases)
	{
		/**
		 * Validates required options and captures environment entries.
		 *
		 * @param cargo the Cargo executable
		 * @param profile the requested Cargo profile
		 * @param cacheRoot the managed default target and V8 cache root
		 * @param environment the complete caller environment
		 * @param v8Releases the V8 release download base URI, ending in a slash
		 * @throws NullPointerException if any option or environment entry is null
		 */
		public Options
		{
			Objects.requireNonNull(cargo, "cargo");
			Objects.requireNonNull(profile, "profile");
			Objects.requireNonNull(cacheRoot, "cacheRoot");
			environment = Map.copyOf(environment);
			Objects.requireNonNull(v8Releases, "v8Releases");
		}
	}

	/**
	 * Selects source context, package identity, prebuilt binaries, and Cargo options.
	 *
	 * @param workspace the patched upstream checkout
	 * @param target the selected package target
	 * @param variant the selected package variant
	 * @param inputs the prebuilt overrides
	 * @param options the explicit build options
	 */
	public record Request(Path workspace, PackageTarget target, PackageVariant variant, Inputs inputs, Options options)
	{
		/**
		 * Validates the build context.
		 *
		 * @param workspace the patched upstream checkout
		 * @param target the selected package target
		 * @param variant the selected package variant
		 * @param inputs the prebuilt overrides
		 * @param options the explicit build options
		 * @throws NullPointerException if any argument is null
		 */
		public Request
		{
			Objects.requireNonNull(workspace, "workspace");
			Objects.requireNonNull(target, "target");
			Objects.requireNonNull(variant, "variant");
			Objects.requireNonNull(inputs, "inputs");
			Objects.requireNonNull(options, "options");
		}
	}

	/**
	 * Contains the resolved entrypoint, host, and target-specific resource binaries.
	 *
	 * @param entrypoint the selected variant executable
	 * @param codeModeHost the code-mode host executable
	 * @param bwrap the Linux sandbox helper
	 * @param commandRunner the Windows command runner
	 * @param sandboxSetup the Windows sandbox setup helper
	 */
	public record Outputs(Path entrypoint, Path codeModeHost, Optional<Path> bwrap,
		Optional<Path> commandRunner, Optional<Path> sandboxSetup)
	{
		/**
		 * Validates resolved output containers.
		 *
		 * @param entrypoint the selected variant executable
		 * @param codeModeHost the code-mode host executable
		 * @param bwrap the Linux sandbox helper
		 * @param commandRunner the Windows command runner
		 * @param sandboxSetup the Windows sandbox setup helper
		 * @throws NullPointerException if any path or container is null
		 */
		public Outputs
		{
			Objects.requireNonNull(entrypoint, "entrypoint");
			Objects.requireNonNull(codeModeHost, "codeModeHost");
			Objects.requireNonNull(bwrap, "bwrap");
			Objects.requireNonNull(commandRunner, "commandRunner");
			Objects.requireNonNull(sandboxSetup, "sandboxSetup");
		}
	}

	/**
	 * Builds requested binaries with live inherited streams and requires every selected output before returning.
	 *
	 * @param request the explicit source build request
	 * @param output the command-trace stream
	 * @return the validated prebuilt and source outputs
	 * @throws IOException if resources are unsupported, V8 or Cargo fails, or expected binaries are absent
	 * @throws NullPointerException if an argument is null
	 */
	public static Outputs build(Request request, PrintStream output) throws IOException
	{
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(output, "output");
		validateResources(request.target(), request.inputs());
		Path cargoRoot = request.workspace().toAbsolutePath().resolve("codex-rs");
		Map<String, String> environment = new HashMap<>(request.options().environment());
		environment.putIfAbsent("CARGO_TARGET_DIR", request.options().cacheRoot().toAbsolutePath().
			resolve("cargo-target").toString());
		Path targetRoot = Path.of(environment.get("CARGO_TARGET_DIR"));
		if (!targetRoot.isAbsolute())
			targetRoot = cargoRoot.resolve(targetRoot);
		List<String> binaries = binaries(request);
		if (!binaries.isEmpty())
		{
			List<String> command = new ArrayList<>(List.of(request.options().cargo(), "build", "--target",
				request.target().triple(), "--profile", request.options().profile()));
			for (String binary : binaries)
				command.addAll(List.of("--bin", binary));
			if (request.inputs().entrypoint().isEmpty() || request.inputs().codeModeHost().isEmpty())
				environment.putAll(V8Resources.resolve(new V8Resources.Request(request.workspace(), request.target(),
					request.options().cacheRoot(), request.options().v8Releases(), environment)));
			output.println("+ " + String.join(" ", command));
			int status = SystemCommands.execute(command, cargoRoot, environment);
			if (status != 0)
				throw new IOException("Cargo package build failed with status " + status);
		}

		String profile = switch (request.options().profile())
		{
			case "dev" -> "debug";
			default -> request.options().profile();
		};
		Path directory = targetRoot.resolve(request.target().triple()).resolve(profile);
		Inputs inputs = request.inputs();
		Outputs outputs = new Outputs(resolve(inputs.entrypoint(), directory.resolve(request.variant().
			entrypointName(request.target()))), resolve(inputs.codeModeHost(), directory.resolve("codex-code-mode-host" +
			request.target().executableSuffix())), resolveOptional(inputs.bwrap(), request.target().isLinux(),
			directory.resolve("bwrap")), resolveOptional(inputs.commandRunner(), request.target().isWindows(),
			directory.resolve("codex-command-runner.exe")), resolveOptional(inputs.sandboxSetup(),
			request.target().isWindows(), directory.resolve("codex-windows-sandbox-setup.exe")));
		List<Path> paths = new ArrayList<>(List.of(outputs.entrypoint(), outputs.codeModeHost()));
		outputs.bwrap().ifPresent(paths::add);
		outputs.commandRunner().ifPresent(paths::add);
		outputs.sandboxSetup().ifPresent(paths::add);
		for (Path path : paths)
		{
			if (!Files.isRegularFile(path))
				throw new IOException("Cargo build did not produce expected binary: " + path);
		}
		return outputs;
	}

	/**
	 * Rejects resource overrides for platforms that do not support them before starting Cargo.
	 *
	 * @param target the selected target
	 * @param inputs the prebuilt overrides
	 * @throws IOException if an override does not apply to the target
	 */
	private static void validateResources(PackageTarget target, Inputs inputs) throws IOException
	{
		if (inputs.bwrap().isPresent() && !target.isLinux())
			throw new IOException("--bwrap-bin is only supported for Linux targets");
		if (inputs.commandRunner().isPresent() && !target.isWindows())
			throw new IOException("--codex-command-runner-bin is only supported for Windows targets");
		if (inputs.sandboxSetup().isPresent() && !target.isWindows())
			throw new IOException("--codex-windows-sandbox-setup-bin is only supported for Windows targets");
	}

	/**
	 * Selects missing Cargo binary targets in the retained entrypoint, host, and helper order.
	 *
	 * @param request the package build request
	 * @return the selected Cargo binary names
	 */
	private static List<String> binaries(Request request)
	{
		Inputs inputs = request.inputs();
		List<String> binaries = new ArrayList<>();
		if (inputs.entrypoint().isEmpty())
			binaries.add(request.variant().cargoBinary());
		if (inputs.codeModeHost().isEmpty())
			binaries.add("codex-code-mode-host");
		if (request.target().isLinux() && inputs.bwrap().isEmpty())
			binaries.add("bwrap");
		if (request.target().isWindows())
		{
			if (inputs.commandRunner().isEmpty())
				binaries.add("codex-command-runner");
			if (inputs.sandboxSetup().isEmpty())
				binaries.add("codex-windows-sandbox-setup");
		}
		return binaries;
	}

	/**
	 * Canonicalizes a supplied prebuilt path or retains the selected Cargo output path.
	 *
	 * @param explicit the optional prebuilt path
	 * @param fallback the Cargo output path
	 * @return the resolved path
	 * @throws IOException if a supplied path cannot be resolved
	 */
	private static Path resolve(Optional<Path> explicit, Path fallback) throws IOException
	{
		if (explicit.isPresent())
			return explicit.orElseThrow().toRealPath();
		return fallback;
	}

	/**
	 * Resolves a resource path only when the target requires it or the caller supplies it.
	 *
	 * @param explicit the optional prebuilt path
	 * @param required whether the target requires the resource
	 * @param fallback the Cargo output path
	 * @return the selected path, or an absent resource
	 * @throws IOException if a supplied path cannot be resolved
	 */
	private static Optional<Path> resolveOptional(Optional<Path> explicit, boolean required, Path fallback)
		throws IOException
	{
		if (explicit.isPresent() || required)
			return Optional.of(resolve(explicit, fallback));
		return Optional.empty();
	}
}
