---
paths:
  - ".cat/rules/**"
---
# Rule Maintenance

## Design Goals

- Simplify the guidance readers receive before each decision while preserving obligations when they become necessary.
- Keep initial routes focused and deliver later decisions through their observable triggers.

## Guidance

When a request names a maintained rule, route, or workflow as the thing to simplify, inspect the reader-visible
guidance that it loads before proposing a change. Map its repeated passages to their triggers, decisions, and evidence;
merge only passages that make the same decision under the same condition. A status update, explanation, or summary of
that guidance is a different artifact and does not satisfy the request. For example, “simplify the loaded route” means
reduce redundant instructions in the files that route loads, not shorten the explanation of those files. A request that
explicitly names a sentence, report, or message remains a request about that named prose. Before changing maintained
guidance, lazy load the canonical contract-design rule for its preservation and compaction procedure.

For each passage in a route being simplified, also record the first reader decision and observable state that require
it. Compare the reported route with a later branch whose condition is not yet known and a route that never reaches that
branch. Guidance needed only after the later condition is established should be delivered by a narrower, observable
trigger or companion route rather than added to the initial route. Keep guidance in the initial route only when the
reader must apply it to make the first decision safely or to determine that later condition. Do not remove or defer a
passage merely because the reported case did not use it: retain it when the initial decision needs it, and do not create
a companion that hides such an initial obligation. Verify the simplified route with one case that needs the deferred
decision and one that does not, so both receive exactly the guidance their next decision requires.

Do not conclude that a loaded route is already irreducible merely because all of its files were selected together.
For every first decision, identify the exact obligation it needs and its authoritative owner, then compare: retaining
the current route, merging duplicate obligations into one owner, and delivering a focused companion at the first later
decision that needs it. A candidate route is smaller only when its reader still has every required obligation before
making that decision, and no file supplies an obligation already available from the retained owner. Keep an initial
file when its unique obligation is needed to select a branch or make the first safe action; otherwise move that unique
obligation behind its observable trigger. If the mapping does not establish which file supplies an obligation, report
the route as not yet analyzed instead of treating its current size as necessary.
