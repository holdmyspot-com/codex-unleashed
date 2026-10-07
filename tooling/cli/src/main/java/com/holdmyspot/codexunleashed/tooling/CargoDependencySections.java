package com.holdmyspot.codexunleashed.tooling;

import java.util.List;

/** Defines the standard Cargo crate dependency tables in their retained traversal order. */
final class CargoDependencySections
{
	/** Names normal, development, and build dependency tables. */
	static final List<String> NAMES = List.of("dependencies", "dev-dependencies", "build-dependencies");

	/** Prevents construction. */
	private CargoDependencySections()
	{
	}
}
