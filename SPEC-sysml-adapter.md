# Specification: sysml-adapter

Status: Approved in conversation on 2026-11-02.
Module ID: sysml-adapter.
Depends on: model-contracts.
Parent: [SPEC.md](SPEC.md).

## Objective and ownership

Embed the official SysML v2 Pilot Implementation in the local Java backend. Convert versioned source documents into application models and diagnostics, and support source-preserving scalar edits.

Own backend/sysml-adapter. No Git operations, collaboration history, or HTTP lifecycle belongs here.

## Runtime and dependency policy

Pin an exact official implementation revision and its corresponding standard libraries. Record build steps, required Java version, dependency licenses, and library provenance. Current research points to Java 21 and Maven/Tycho; compatibility remains untested.

Initialize standalone EMF/Xtext services once. Use isolated per-workspace analysis resources and consistent document URIs. Initially serialize parser operations through one worker; separate resource sets alone do not establish thread safety.

Load matching standard libraries explicitly. Index all workspace documents before resolving their references. Do not fetch arbitrary resources from URLs embedded in user text.

## Provider operations

Analysis:
- Input: immutable workspace snapshot, project identity map, all documents and their versions.
- Output: Analysis records plus candidate identity-map updates.
- Stages: parse, index, link/resolve, validate, then extract authored and semantic information.
- Distinguish parsing failure, semantic diagnostics, unsupported edit constructs, and missing mappings.

Source edit:
- Input: current source snapshot, matching analysis revision, target identity, expected expression, replacement expression or supported new attribute.
- Output: narrowly patched source documents or a stale/unsupported diagnostic.
- The caller reanalyzes the result before recording or delivery.

The workspace service owns persistence and decides whether an analysis result is still current.

## Supported projection

Identify the fixed fixture's blocks and their scalar attributes from the official model, not from regular-expression extraction.

For editable attributes, expose:
- Owner and attribute identity.
- Name and authored literal expression.
- Literal kind and normalized value.
- Relevant type information.
- Exact authored expression range.
- Read-only or unsupported reasons where applicable.

For relationships in the fixed fixture, expose enough information to render the reference diagram. Relationship editing is not supported.

Parsing expressions, imports, inherited members, or units does not imply they are editable. Keep the authored expression distinct from optional evaluation results. Do not promise an effective scalar value for every legal SysML expression.

## Text preservation

Patch only the mapped expression for a value edit. Attribute addition inserts a supported declaration at a verified owner-body boundary.

Preserve comments, spacing outside the edit, source line endings, and all unrelated text. Never serialize the entire derived EMF model as the default editing path.

Mapping to a generated or ambiguous semantic element is not sufficient for editing. Missing exact ranges disables the operation rather than triggering a broad replacement.

Invalid input remains available to the user. The adapter may return partial analysis, but it must not delete the erroneous document or silently substitute an older source version.

## Feasibility gate

Before integrating collaboration:
1. Build and embed the chosen official revision without launching Eclipse.
2. Load its matching standard libraries.
3. Parse the exact pump fixture and resolve its relevant types and names.
4. Extract precise scalar ranges and stable identities.
5. Replace a value and add a Boolean attribute while preserving unrelated text.
6. Validate the results and return useful diagnostics for malformed input.
7. Verify isolated Alice/Bob analysis and measure analysis duration.

Document measured results. A blocked gate triggers a specification review rather than a substitute parser.

## Acceptance and tests

Run task test:sysml-adapter.

Real-parser fixtures cover:
- Initial pump model and all acceptance-scenario values.
- Supported literal kinds, escaped strings, and numeric precision.
- Adding alertEnabled without changing other declarations.
- Comments, CRLF, Unicode, and repeated attribute names in different owners.
- Cross-file reference resolution and standard-library availability.
- Malformed text, unknown references, and type-incompatible assignments.
- Read-only computed/inherited information without fabricated source ranges.
- Alice's analysis not contaminating Bob's snapshot.
- Stale range rejection and unchanged source outside the intended edit.

Mock analysis is allowed in other modules' tests, not as proof that this adapter works.

## Primary integration references

- [Official repository and build](https://github.com/Systems-Modeling/SysML-v2-Pilot-Implementation)
- [SysMLInteractive standalone usage](https://github.com/Systems-Modeling/SysML-v2-Pilot-Implementation/blob/master/org.omg.sysml.interactive/src/org/omg/sysml/interactive/SysMLInteractive.java)
- [SysMLUtil resources and indexing](https://github.com/Systems-Modeling/SysML-v2-Pilot-Implementation/blob/master/org.omg.sysml/src/org/omg/sysml/io/SysMLUtil.java)
- [Official release licensing](https://github.com/Systems-Modeling/SysML-v2-Release#licensing)

These links describe integration evidence, not a verified build. Record permanent revision links when pinning the dependency.
