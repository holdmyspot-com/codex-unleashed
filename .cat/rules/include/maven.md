# Project Maven Tooling

## Design Goals

- Verify the project's JDK 27 tooling through its complete maintained Maven reactor, including tests and static checks.

## Guidance

Use JDK 27 for the Java tooling. From the repository root, run `tooling/mvnw verify` for the complete quality gate.
The Maven reactor lives under `tooling/`; this repository has no root `mvnw` or root POM. Use
`tooling/mvnw -q verify` for routine verification; use normal output when required evidence or failure diagnosis needs it. Focused tests
supplement the full reactor gate. Keep tests, Checkstyle, and PMD enabled when claiming full verification passes.

Keep TestNG as the tooling test framework and named Java modules with a bundled runtime as its distribution model.
Use the maintained wrapper's cache and output configuration, governed by `../common/build-caches.md`. Read
`tooling/pom.xml` and the affected module POMs for dependency, plugin, and lifecycle configuration before changing them.
Do not assume a Maven result-cache extension is installed or substitute another project's build layout or dependencies.

Declare dependency versions in the tooling reactor's root POM's `<dependencyManagement>` section. Declare plugin
versions and plugin dependency versions in that POM's `<pluginManagement>` section.

Fix all known compiler and linter errors before repeating verification. If quiet output lacks the diagnostic needed
to choose the next action, repeat the same scope without `-q`, then apply the known fixes together before rebuilding.
