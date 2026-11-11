# Patch Queue

## Design Goals

- Keep every downstream patch limited to changes that upstream does not already provide, so the queue carries no
	duplicate of upstream's fix or of the test upstream added with it.

## Guidance

When rebasing or editing a downstream patch and upstream already provides the patch's fix or functionality, remove that
fix or functionality together with the tests that cover it. Do not keep a patch, or part of one, that only adds a
regression test for behavior upstream now provides: assume upstream added its own test when it made the fix. If every
change in a patch is covered by upstream, remove the patch from the queue, delete its file and, when no other patch
shares it, its directory's README, and record the removal in `CHANGELOG.md` under the `## Upstream rust-vX.Y.Z` heading of
the upstream release the queue is based on, naming the upstream release that provides the change. A test stays only while the patch still carries the code it tests.
