# Specification: change-engine

Status: Approved in conversation on 2026-11-02.
Module ID: change-engine.
Depends on: model-contracts.
Parent: [SPEC.md](SPEC.md).

## Objective and ownership

Provide deterministic, authoritative comparison and replay rules inspired by jj. Conflicts are recorded model states, not a stopped rebase process.

Own backend/change-engine. Implement pure operations over application records. Do not call the official parser, Git, HTTP, or browser APIs. workspace-service applies proposals and invokes validation.

## Supported history

A workspace has a linear chain of recorded changes based on an accepted revision. Different workspaces can start from the same accepted revision.

A change has a stable logical ID and immutable revisions. Rewriting an ancestor creates new revisions for that change and affected descendants. Old revisions remain retrievable. Accepted history is not rewritten.

An autosaved buffer is not itself a recorded change. Recording identifies the delta from its parent, including the distinction between inherited conflicts and newly authored edits.

## Three-way comparison

For each supported authored property, compare base, incoming accepted state, and local edit:

| Condition | Result |
|---|---|
| Only incoming differs from base | Keep incoming |
| Only local differs from base | Keep local |
| Both sides reach the same supported semantic literal | Converge without conflict |
| Both sides differ and disagree | Record conflict with all three values and provenance |
| Edits affect different properties | Preserve both |
| Distinct supported attributes are added | Preserve both |
| Same attribute name is added incompatibly on both sides | Record an addition conflict or explicit unsupported-type diagnostic |

Formatting-only differences must not create a scalar-value conflict. Use model-contracts normalization, not evaluated expressions or unit conversions.

Text and comments outside semantic changes must not disappear. The service combines disjoint source edits where safe. Overlapping nonsemantic source edits that cannot be preserved are explicit source conflicts, not a license to regenerate the document or discard one side. The first demo need not provide a general source-conflict editor, but delivery must remain blocked with the text retained.

Structural edits outside scope produce an unsupported result rather than a guessed semantic merge.

## Structured conflict behavior

A conflict identifies its origin change, target element/property, base, alternatives, and revisions. Descendants inherit it without duplicating its origin.

A parseable provisional source uses the local alternative for an unresolved scalar conflict. The conflict record, not that provisional text, is authoritative about the property's unresolved state.

Comparisons and replay must never treat a provisional materialization as an accepted scalar. Unchanged inherited conflicts remain the same logical conflict when later unrelated edits are recorded.

The engine supports explicit resolution to incoming, local, or a custom expression. Resolving at a descendant does not implicitly resolve an ancestor. The normal UI action navigates to the conflict's origin.

## Ancestor resolution and propagation

1. Verify the origin change revision and conflict still match the resolution request.
2. Produce a replacement origin revision with the explicit resolution.
3. Replay descendants in parent-to-child order, preserving each descendant's authored delta.
4. Carry unrelated changes forward and substitute the new resolved parent state for inherited conflict state.
5. When a descendant independently changes the same property, perform comparison rather than blindly overwriting it.
6. Record any new conflict and continue replaying later unrelated changes.
7. Return the complete proposed history rewrite and old-to-new revision mapping.

The service validates and persists this proposal atomically. No intermediate half-rewritten history becomes visible.

Example:
- B1 conflicts over flowRate, 12 versus 15.
- B2 adds alertEnabled = true and inherits B1's conflict.
- Resolve B1 to 14.
- B2's new revision has flowRate = 14 and alertEnabled = true.

Counterexample:
- B2 contains an independent choice or edit of flowRate.
- Resolving B1 must not erase that choice. Replay either preserves a compatible change or records a new conflict.

This is property-aware replay, not a claim to reproduce jj's complete algebra of conflicted file trees.

## Provider operations

- Compare normalized snapshots and supported edit deltas.
- Record a delta relative to a parent containing resolved or conflicted properties.
- Rebase a draft chain onto a new accepted snapshot.
- Resolve a conflict at its origin and replay descendants.
- Return explicit unsupported/source-conflict outcomes when preservation is uncertain.

Inputs and outputs use model-contracts. Operations are deterministic for the same inputs. The browser's WASM comparison must agree on the supported shared fixture subset, but browser output is never authoritative.

## Validation boundaries

Conflict freedom does not imply semantic validity. The engine returns proposals; the parser checks resulting source.

When a property is conflicted, any candidate-document validation is provisional. Do not invent a complete semantic dependency graph. Where the adapter cannot establish affected dependencies, mark validation pending rather than claiming unaffected validity.

## Acceptance and tests

Run task test:change-engine.

Required cases:
- 10 to 12 versus 10 to 15 creates the expected conflict.
- 10 to 12 on both sides converges.
- Different-property edits survive.
- B2 retains its added attribute after B1 resolves to 14.
- Multiple descendants replay in order without losing their deltas.
- Inherited conflicts do not become new local edits.
- An independent later resolution/edit can create a new conflict.
- Resolving the tip does not change its ancestor.
- Incompatible same-name additions never silently duplicate or overwrite.
- Unsupported structural edits and overlapping source changes are explicit.
- Repeated identical inputs produce identical results.
- Comparison and replay do not mutate inputs or accepted snapshots.
