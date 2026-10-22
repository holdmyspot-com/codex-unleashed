---
paths:
  - "tooling/**/*.java"
  - "tooling/pom.xml"
  - "tooling/**/pom.xml"
---
# Java Convention Loading Index

## Design Goals

- Load the focused Java tooling conventions required by the current decision without applying them to upstream Rust.

## Guidance

When editing Java source style, imports, naming, layout, control flow, or comments, lazy load
`../include/java-style.md`.

When writing or editing Javadoc, lazy load `../include/java-javadoc.md`.

When handling strings, text blocks, external string checks, or return-value validation, lazy load
`../include/java-strings.md`.

When validating Java parameters or using Requirements.java validators, lazy load `../include/requirements-api.md`.

When designing classes, interfaces, constructors, visibility, entry points, or test-support boundaries, lazy load
`../include/java-class-design.md`.

When defining or changing a Java type's thread-safety contract, shared mutable state, or synchronization, lazy load
`../include/java-class-design/thread-safety.md`.

When throwing, catching, declaring, or wrapping Java exceptions, lazy load `../include/java-exceptions.md`.

When writing or changing Java tests or assertions, lazy load `../include/java-testing.md`.

When adding or changing Java logging, lazy load `../include/logging.md`.

When designing a Pouch scope, its lifetime, owned dependencies, child scopes, or resource ownership, lazy load
`../include/pouch.md`.

When changing `module-info.java`, module dependencies, exports, or reflective access, lazy load
`../include/java-modules.md`.
