# Java Modules

## Design Goals

- Keep Java tooling dependencies explicit and implementation packages encapsulated under named modules.

## Guidance

Name a module after its implementation namespace. Declare direct module dependencies with `requires`. Export only
packages intended for use by other modules; keep implementation details unexported.

```java
module com.holdmyspot.codexunleashed.tooling
{
	requires io.github.cowwoc.pouch10.core;
	requires tools.jackson.databind;

	exports com.holdmyspot.codexunleashed.tooling;
}
```

Use `opens` only when a runtime library genuinely requires deep reflection. Do not widen exports to other modules or add
test-only production APIs merely to test implementation details; keep the existing Maven test-patching arrangement unless the task explicitly changes test-module architecture.
