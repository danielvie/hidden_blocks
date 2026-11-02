# Change engine

Pure Java 21 comparison and replay over `app.ModelContracts`. No parser, Git, network, storage, or source-text merging is used.

## Run

With `model-contracts:0.1.0-SNAPSHOT` available in the Maven reactor or local repository:

```sh
mvn -f backend/change-engine/pom.xml test
mvn -f backend/change-engine/pom.xml package
```

The module Taskfile also exposes `test`, `build`, `clear`, and `run`. Because this is a library, `run` executes its regression suite.

## API and integration

- `ChangeEngine.diff(before, after)` returns original authored scalar replacements and additions. Both analyses must be valid and versioned. Existing element and attribute identities, names, kinds, types, structural members, and read-only metadata must remain intact. Source offsets may shift. Supported scalar equality uses resolved type, scalar kind, and canonical value, not expression spelling.
- `ChangeEngine.apply(edits, current, inherited, changeId, baseRevision, incomingRevision, localRevision, actor)` returns immutable materialization patches, conflicts, and identity aliases. Patches use the current property's value as their before-value. They are not replacements for the original authored delta.
- `ChangeEngine.resolveEdits(original, conflict, resolution)` replaces only the origin edit with an edit from the conflict's incoming value to the chosen value. It preserves unrelated original edits. The service verifies the expected immutable origin revision before calling it.

For same-owner, same-name additions, the incoming current identity is canonical. Equal typed values converge. Different values produce an addition conflict with an absent base. Different types are explicitly unsupported. Patch and conflict targets use the canonical ID. The service must apply `identityRemap` to the original authored deltas before storing replacement revisions or calling `resolveEdits`, and propagate aliases into descendant deltas.

Untouched inherited conflicts retain the original objects and provenance. An independent same-target descendant edit creates a descendant-origin conflict, even when provisional source would otherwise appear compatible. The latest state may replace the inherited same-target conflict; the service must retain the older origin frame in immutable history. Replaying the same origin with its inherited conflict does not duplicate it.

Conflict IDs are deterministic name UUIDs derived from the logical origin change and canonical property target. Revision provenance comes directly from the supplied IDs. The single supplied actor labels new alternatives; the service enriches their actual authors from Git and stored history. Existing inherited alternatives remain unchanged.

## Boundaries

A scalar edit cannot supersede an inherited source conflict on the same target. Untouched source conflicts are retained, and source resolution remains the service's responsibility.

Unsupported structure, incompatible types, ambiguous identities, incomplete analyses, or stale comparison versions throw `ChangeEngine.UnsupportedEditException`, an `IllegalArgumentException` subtype. No unsupported change becomes a successful empty delta.

These records cannot establish external freshness or expose source changes absent from the analysis projection. The service must verify workspace and document versions against its stored snapshot, retain textual residual edits, detect overlapping source conflicts, reanalyze proposed source, persist immutable revisions, and replay each original descendant delta in order. Conflicted provisional source is not an accepted semantic value.

Regression fixtures are synthetic contract models, including a fresh equivalent analysis with shifted source ranges. Parser integration and source preservation belong to the adapter and service suites.
