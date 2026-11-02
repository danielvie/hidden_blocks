# Specification: model-contracts

Status: Approved in conversation on 2026-11-02.
Module ID: model-contracts.
Parent: [SPEC.md](SPEC.md). Common scope, commands, code style, and boundaries apply.

## Objective and ownership

Define the application-owned records exchanged between parser, engine, backend, TypeScript, and WASM. No consumer should need EMF object types, JVM handles, or parser-specific syntax classes.

Own contracts/ and shared schema fixtures. Do not own HTTP routes, persistence implementation, or UI state.

## Required records

| Record | Required information |
|---|---|
| Workspace snapshot | Workspace ID, monotonic workspace revision, accepted base revision, document versions, draft tip |
| Source document | Repository-relative document ID, exact text, version, encoding/range convention |
| Analysis | Workspace revision and document versions analyzed, completeness, diagnostics, normalized elements |
| Element | Persistent application ID, kind, name, qualified display name, source provenance |
| Attribute | Persistent ID, owner ID, name, declared/resolved type where available, authored expression, editable range, edit support |
| Scalar literal | Literal kind, original expression, canonical semantic value without lossy floating-point conversion |
| Recorded change | Stable change ID, immutable change-revision ID, parent revision, author label, description, authored edit delta |
| Conflict | Stable conflict ID, origin change, target IDs, kind, base and named alternatives with revision provenance |
| Resolution | Conflict and origin revision, selected alternative or custom expression, author label |
| Source edit | Target document/version, target IDs, expected expression, replacement expression |
| Diagnostic | Severity, category, message, optional document range, analyzed revision |
| Replay result | Proposed rewritten revisions, preserved edits, conflicts, diagnostics, or explicit unsupported result |

Fields and enum values become versioned schemas before implementations are split across workers. Fixtures are the authority for cross-language serialization.

The approved connected-diagram extension adds type/provenance metadata, ports, containment, and linked connections. See [diagram-metadata.md](contracts/diagram-metadata.md) for exact field meanings and legacy-load behavior. Inherited rows have usage-specific identities plus their authored definition reference; they are not new authored edits.

## Identity

Element and attribute IDs are application-owned and persisted alongside project state. Reparsing must not regenerate identities for unchanged elements. Names, qualified names, array positions, source offsets, and transient parser object addresses are not identities.

The identity map must handle whitespace/comment changes, scalar replacements, and attribute insertion. Renaming/moving existing elements is outside scope. If a text edit makes reconciliation ambiguous, report unsupported/ambiguous identity; do not guess and attach changes to the wrong element.

Concurrent additions of the same attribute name to the same owner are a logical collision even if their tentative IDs differ. Equal supported declarations can converge to one canonical identity. Different types or values must remain a conflict or explicit unsupported case, never duplicate members silently.

## Values and source ranges

- Ranges are half-open UTF-16 code-unit offsets into an exact versioned source document.
- Source newlines are preserved. CRLF and LF are not normalized behind the caller's back.
- Rust explicitly converts ranges before indexing UTF-8 strings.
- Authored expression text is distinct from normalized literal meaning. Read-only expressions retain authored text and ranges even when type or scalar interpretation is unavailable.
- Scalar kind follows the resolved supported property type, not the parser node class. An authored integer token assigned to ScalarValues::Real projects as kind REAL; derived or unresolved types are not guessed.
- Numeric canonicalization must not lose precision through JavaScript Number.
- Equal supported literals can compare equal despite harmless formatting differences. Type and dimension changes are not normalized into equality.
- Derived or implicit elements can lack authored ranges and are read-only.
- Incomplete or unknown semantic information is explicit, not encoded as a successful empty result.

## State rules

Syntax validity, semantic validation, supported-edit status, and conflicts are separate dimensions.

A conflicted property carries alternatives, not a single effective authoritative scalar. A provisional source value can be exposed separately with its conflict ID and provisional status.

A descendant references an inherited conflict rather than pretending to introduce the conflict itself. Origin and inheritance must be distinguishable so replay can remove the inherited conflict after ancestor resolution.

Every workspace mutation carries the expected workspace revision. Source-based mutations also carry a document version. Results identify the revision they describe. The disposable-demo Reset uses an expected demo generation plus operation ID instead, and creates fresh workspace IDs. See contracts/poc-demo.md.

## Provider contract

Expose schemas and fixtures for:
- Complete and partial analysis.
- Supported scalar replacement and addition.
- Three-way comparison input and result.
- Conflict inheritance and explicit resolution.
- Replay proposals and stale/unsupported diagnostics.
- Fixed-diagram projection input and advisory comparison output.

The authoritative change-engine accepts these records. The browser WASM consumes the relevant subset without knowing backend classes.

## Acceptance and verification

Run task test:model-contracts once implemented.

Required cases:
- Java, TypeScript, and Rust consume the same serialized fixtures without identity or precision loss.
- Boolean, string, large integer, and decimal literal cases round-trip.
- UTF-16 ranges select the expected text with non-BMP characters before the range and with CRLF.
- Unchanged IDs survive scalar edits, comments, and insertion of another attribute.
- Inherited conflict provenance survives serialization and is distinct from a new conflict.
- Stale and incomplete states cannot be mistaken for accepted model data.

## Open engineering question

The exact sidecar identity-map encoding is selected in the feasibility work. Its observable invariants above are mandatory.
