# Maintained repository checks

Contributors can run the blob policy from a Git checkout with Bash, Git and JDK 27. The Maven-built launcher bundles its
Java runtime. From the repository root, run the focused policy tests, build the runtime, and check the proposed range:

```bash
tooling_build="$PWD/.cat/work/temp/blob-size-policy"
mkdir -p "$tooling_build/build" "$tooling_build/tmp"
export TMPDIR="$tooling_build/tmp" TMP="$tooling_build/tmp" TEMP="$tooling_build/tmp"
mvn -q -B -ntp -f tooling/cli/pom.xml \
  "-Dtooling.build.directory=$tooling_build/build" \
  "-Djava.io.tmpdir=$tooling_build/tmp" \
  -Dtest=BlobSizeCommandTest,BlobSizePolicyTest test
mvn -q -B -ntp -f tooling/cli/pom.xml -Pruntime -DskipTests \
  "-Dtooling.build.directory=$tooling_build/build" \
  "-Djava.io.tmpdir=$tooling_build/tmp" package
export CODEX_TOOLING="$tooling_build/build/runtime/bin/codex-tooling"
bash "$CODEX_TOOLING" check-blob-size --base origin/main --head HEAD
```

Remove the local `blob-size-policy` build directory after checking.

The command rejects each Git blob larger than **512000 bytes** that is reachable
from the proposed head and absent from the base's reachable history. The exact
limit passes. Commits and trees have no blob-size limit. Existing base history is
grandfathered; adding a large file and deleting it in a later proposed commit
still fails. The check reads committed objects, so stage and commit the files
before checking them. No allowlist applies to this overlay.

Exit zero means acceptance, one reports oversized blob IDs and byte counts, and
two means the revisions or repository objects could not be read. On rejection,
use the printed `git log --find-object=<id>` command to find the introducing
commit and remove the large content from the proposed history. Deleting only the
current worktree file does not remove the committed blob. Fetch missing commits
before retrying an operational failure.

The `blob-size-policy` workflow runs on pushes and pull requests and can be
called by other workflows. Manual execution accepts a base revision and defaults
to the selected commit's parent. The first push of a new branch has an all-zero
base, so the workflow uses the default branch's tip instead and checks only the
blobs the new branch adds to it. Called directly, the tool still treats an
all-zero base as the entire reachable history.

`CodeQL` analyzes the maintained Java tooling, Python scripts and JavaScript
actions under `tooling/cli` and `.github`. It runs on main pushes, pull requests,
weekly scheduled scans and manual requests, and uploads security findings.
Analysis uses source extraction without a native Codex build. It does not check
out upstream source or modify repository issues.

`tooling-ci` runs `mvn -q -B -ntp -f tooling/cli/pom.xml -Pruntime verify` with the
project's JDK 27.0.1 and native Cargo fixture toolchain on Linux, macOS and Windows,
each on x64 and ARM64. Maven runs the module's configured compiler and test gates
and assembles its bundled runtime. The workflow then executes the assembled
launcher and requires its documented no-argument usage response and exit status.
Build, dependency and temporary directories are isolated per job and removed at
job completion. These hosted runs establish platform acceptance; a workflow
syntax check alone does not establish that the Maven build or CodeQL scan ran.
