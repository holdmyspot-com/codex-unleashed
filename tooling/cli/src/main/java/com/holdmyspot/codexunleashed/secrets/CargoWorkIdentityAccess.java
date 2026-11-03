package com.holdmyspot.codexunleashed.secrets;

import com.holdmyspot.codexunleashed.scheduler.CargoWorkIdentity;
import java.nio.file.Path;
import java.util.List;

/**
 * Creates compiler-work identities with a controlled argument resolver.
 * The class that owns the behavior registers the implementation through {@link CargoWorkIdentitySecrets}.
 */
public interface CargoWorkIdentityAccess
{
	/**
	 * Creates an identity that resolves argument values through the given resolver.
	 *
	 * @param inputRoot absolute normalized source/configuration root
	 * @param outputRoots absolute normalized generated output trees, strictly below the input root
	 * @param toolchainRoot absolute normalized immutable compiler/sysroot root
	 * @param resolver resolves an argument value against the compiler's working directory
	 * @return the identity
	 * @throws NullPointerException if a required value or collection member is null
	 * @throws IllegalArgumentException if a path is relative, not normalized or an output is outside the input root
	 */
	CargoWorkIdentity withResolver(Path inputRoot, List<Path> outputRoots, Path toolchainRoot,
		CargoWorkIdentity.Resolver resolver);
}
