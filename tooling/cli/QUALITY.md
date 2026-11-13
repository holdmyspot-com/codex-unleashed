# Developer Java quality preflight

From the repository root, list the exact Java source paths in a UTF-8 file, one path per line:

```text
tooling/cli/src/main/java/com/holdmyspot/codexunleashed/tooling/Main.java
tooling/cli/src/test/java/com/holdmyspot/codexunleashed/tooling/test/MainTest.java
```

Run the focused quality gate with JDK 27:

```sh
mvn -q -B -ntp -f tooling/cli/pom.xml -Pquality-preflight -Dquality.paths=owned-java.txt verify
```

The manifest must contain at least one existing regular `.java` file under `tooling/cli/src/main/java/`,
`tooling/cli/src/test/java/`, or `tooling/cli/src/build/java/`. Blank entries, duplicate paths, absolute paths,
parent traversal, and symbolic-link components fail before analyzer execution. Relative manifest filenames resolve
from the repository root.

The profile preserves the ordinary Maven lifecycle, including compilation and tests. It adds Checkstyle, PMD,
and JDK 27 private Javadoc with every doclint category and warnings treated as errors. Analyzer source selection
uses only the manifest's exact files. Maven supplies the compiled classes and resolved test dependencies for type
resolution; Javadoc reads the named source files directly with an empty implicit source path.

Reports are retained under `${tooling.build.directory}/quality-preflight/run-*/`. Each invocation keeps its admitted
`paths.txt`, exact source copies, Checkstyle XML and log, PMD XML/HTML and log, Javadoc output and log, and
`outcomes.properties`. The `current.properties` file identifies the current invocation. A source finding is recorded
as `findings`; an analyzer that fails before writing its completion receipt is recorded as `tool-error`. Either
outcome fails the final Maven gate, after the other analyzers have had an opportunity to write their reports.

Use a distinct `-Dtooling.build.directory=...` and temporary directory for concurrent Maven builds. Each build owns
its reports; retain them through review, then remove the build output with the ordinary Maven clean command or the
owning build-directory cleanup. Running another preflight preserves earlier invocation directories.

Independent source examples for clean input, Checkstyle findings, PMD findings, and doclint findings are under
`src/test/resources/com/holdmyspot/codexunleashed/tooling/test/quality-preflight/`. Copy one into a temporary
checkout's supported source root and select it in that checkout's manifest to exercise the actual Maven gate.
