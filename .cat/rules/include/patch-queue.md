# Patch Queue

## Design Goals

- Keep every downstream patch limited to changes that upstream does not already provide, so the queue carries no
	duplicate of upstream's fix or of the test upstream added with it.

## Guidance

When rebasing or editing a downstream patch and upstream already provides the patch's fix or functionality, remove that
fix or functionality together with the tests that cover it. Do not keep a patch, or part of one, that only adds a
regression test for behavior upstream now provides: assume upstream added its own test when it made the fix. If every
change in a patch is covered by upstream, drop the whole patch from the queue and record in its directory's README which
upstream release provides it. A test stays only while the patch still carries the code it tests.
