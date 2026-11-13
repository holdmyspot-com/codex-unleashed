# Maintained repository checks

Contributors can run the blob policy from a Git checkout with Python 3:

```sh
python3 .github/scripts/test-check-blob-size.py
python3 .github/scripts/check-blob-size.py --base origin/main --head HEAD
```

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
to the selected commit's parent. An all-zero base checks the entire new branch's
reachable history.

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
