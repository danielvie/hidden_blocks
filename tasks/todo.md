# Implementation task list

Status: Implementation and automated gates verified on 2026-11-02. Final user acceptance is pending. See ../docs/verification.md for commands, counts, and limits.
Date: 2026-11-02
Plan: [plan.md](plan.md)
Specification: [../SPEC.md](../SPEC.md)

This file is the task-list authority. Original per-task file lists describe planned ownership; some helpers were consolidated. Actual modules and test locations are indexed in ../docs/verification.md.

## Execution rules

- Dependencies, not numerical order alone, determine what can start. Independent later-numbered tasks may run early after their listed prerequisites.
- Every task is a small or medium slice with at most five expected files, including tests. If implementation needs more, split the task before proceeding.
- Shared manifests, schemas, fixtures, and Taskfile.yml are integration-owned. Workers request changes; they do not race to edit them.
- Taskfile commands are implemented. Build, test, lint, and actual application startup have been exercised; no placeholder success remains.
- The user waived intermediate human gates and requested final review only. Automated tests remained mandatory at each implemented boundary.
- The early parser/build and persistence tasks are prerequisite proofs, not an excuse to build every backend feature before showing a usable text/diagram slice.
- No destructive reset, dependency installation, or application execution was performed while writing this plan.

## T01: Make the selected toolchain reproducible

- [x] Complete T01

Module: integration
Dependencies: None
Estimated scope: Split into T01a tooling, 5 files, and T01b cleanup tests/line-ending policy, 2 files.

Introduce the root task interface and a project-local setup script. Resolve Java through JAVA_HOME rather than the inconsistent shell PATH. Do not install or change global tools without explicit approval.

Acceptance criteria:
- [x] Select the installed full JDK 21.0.6; java, javac, and Maven agree on Java 21. Record exact tool and dependency candidates.
- [x] task lists described tasks; setup checks prerequisites; clear is restricted to build outputs. Unimplemented task targets fail clearly instead of reporting success.
- [x] Reference projects and runtime data are excluded from cleanup. Source-control ignore rules exclude .local and build outputs.

Verification:
- [x] Run task and task setup CHECK_ONLY=1; inspect resolved java/javac/Maven paths.
- [x] Exercise clear in a temporary layout containing sentinel workspace/repository files; sentinels survive.

Files likely touched:
- `Taskfile.yml`
- `scripts/toolchain.mjs`
- `tools/versions.json`
- `.gitignore`
- `docs/build.md`

Implementation evidence: JDK 21 setup check passed; 4 Node safety tests passed, including explicit rejection of unsupported Maven configuration without printing its values. Added scripts/toolchain.test.mjs and .gitattributes as the separate T01b verification increment. Unimplemented tasks fail explicitly.

## T02: Freeze the shared model and revision contracts

- [x] Complete T02

Module: model-contracts
Dependencies: T01
Estimated scope: Medium, 5 expected files.

Define application records and a small manually specified pump fixture before parallel consumers start. Java records represent API data, not parser internals.

Acceptance criteria:
- [x] Schemas cover exact source, UTF-16 ranges, canonical scalar strings, stable identities, revisions, authored deltas, and conflict provenance.
- [x] Java contract types round-trip the pump fixture with large integers, decimals, and explicit pending/invalid/conflicted states.
- [x] Fixtures distinguish inherited conflicts from authored edits; no EMF objects or JavaScript numeric coercion enters the wire contract.

Verification:
- [x] Run task test:model-contracts against the Java fixture consumer.
- [x] Review field meanings and examples before releasing the schema to other work lanes.

Files likely touched:
- `contracts/model.schema.json`
- `contracts/golden/model-cases.json`
- `contracts/java/pom.xml`
- `contracts/java/src/main/java/app/ModelContracts.java`
- `contracts/java/src/test/java/app/ModelContractsTest.java`

Implementation evidence: Maven build passed with 6 JUnit tests, including preservation of read-only expressions without fabricated scalar values. Shared records cover exact scalars, UTF-16 ranges, analysis states, authored deltas, and inherited conflicts. Schema fields are checked against every Java record.

## T03: Prove standalone official-parser startup

- [x] Complete T03

Module: sysml-adapter
Dependencies: T01, T02
Estimated scope: Split into T03a pinned distribution bootstrap, 3 files, and T03b Maven integration/fixture, 4 files. Task runner wiring is integration-owned.

Build a pinned official Pilot revision with matching model libraries and call it from a headless test. This is a production-adapter feasibility probe, not a replacement parser.

Acceptance criteria:
- [x] The selected official revision and matching libraries build or load using the documented standalone route, without launching Eclipse.
- [x] The pump fixture parses, resolves relevant types, and validates; malformed text produces a real parser diagnostic.
- [x] Record exact pins, licenses, commands, cold/warm timing, and any packaging blocker. A failed probe stops dependent implementation.

Verification:
- [x] Run task setup, then task test:sysml-adapter with ParserSmokeTest.
- [x] Repeat in a fresh process and confirm library loading does not depend on an Eclipse workspace.

Files implemented:
- `scripts/setup-pilot.mjs`
- `tools/pilot-lock.json`
- `docs/parser-feasibility.md`
- `backend/pom.xml`
- `backend/sysml-adapter/pom.xml`
- `backend/sysml-adapter/src/test/java/app/ParserSmokeTest.java`
- `fixtures/pump.sysml`

Implementation evidence: matched official release 2026-09.1, 97 pinned file hashes, 94 libraries; task setup and task test passed. Five Maven parser integration tests verify actual linking, syntax/linker diagnostics, reparse, and Gson classpath compatibility. The adapter module is intentionally test-only until T04.

### Checkpoint 1: Contract and parser startup

Review the frozen contracts and evidence that the pinned official parser starts. If T03 fails, stop parser-dependent work; do not substitute a parser.

- [x] Required task tests and the current slice build pass.
- [x] Evidence and limits are recorded.
- [x] Intermediate human review waived by the user's implement-all instruction; automated gate passed.

## T04: Extract versioned models and precise source mappings

- [x] Complete T04

Module: sysml-adapter
Dependencies: T03
Estimated scope: Medium, 5 expected files.

Wrap analysis behind application contracts. Build isolated resource sets, load/index all documents, resolve and validate, then extract authored property ranges.

Acceptance criteria:
- [x] Return model data and diagnostics tagged with the input versions; expose authored versus derived/read-only information separately.
- [x] Unicode before a value, CRLF, duplicate member names in different owners, and cross-file references map to the correct source.
- [x] Serialized parser execution and isolated workspace resources prevent Alice/Bob analysis contamination.

Verification:
- [x] Run task test:sysml-adapter including ParserAdapterTest and SourceMappingsTest.
- [x] Compare extracted literals and ranges with hand-specified expectations, not snapshots generated solely by the adapter.

Files likely touched:
- `backend/sysml-adapter/src/main/java/app/ParserAdapter.java`
- `backend/sysml-adapter/src/test/java/app/ParserAdapterTest.java`
- `backend/sysml-adapter/src/main/java/app/SourceMappings.java`
- `backend/sysml-adapter/src/test/java/app/SourceMappingsTest.java`
- `fixtures/source-mapping.sysml`

## T05: Round-trip graphical edits without losing source

- [x] Complete T05

Module: sysml-adapter
Dependencies: T04
Estimated scope: Medium, 5 expected files.

Implement source-preserving scalar replacement, scalar insertion, and persistent identity reconciliation for the approved subset.

Acceptance criteria:
- [x] Replace one mapped expression or insert one verified declaration; preserve unrelated comments, whitespace, and line endings.
- [x] IDs survive reparsing and adding an attribute. Ambiguous reconciliation and unsupported structural edits are reported, not guessed.
- [x] Stale expressions/versions are rejected; the modified fixture reanalyzes successfully and contains alertEnabled = true.

Verification:
- [x] Run task test:sysml-adapter with edit, identity, invalid-input, and Unicode/CRLF cases.
- [x] Update docs/parser-feasibility.md with measured round-trip results and the selected identity-map format.

Files likely touched:
- `backend/sysml-adapter/src/main/java/app/SourceEdits.java`
- `backend/sysml-adapter/src/test/java/app/SourceEditsTest.java`
- `backend/sysml-adapter/src/main/java/app/IdentityMap.java`
- `backend/sysml-adapter/src/test/java/app/IdentityMapTest.java`
- `docs/parser-feasibility.md`

## T06: Start the local application HTTP boundary

- [x] Complete T06

Module: workspace-service
Dependencies: T02, T03
Estimated scope: Medium, 5 expected files.

Create the one Java application using the JDK HTTP server and define request/response schemas for all approved operations. Add a minimal status page before frontend assets exist.

Acceptance criteria:
- [x] The server binds to loopback, serves status/static content, and uses a single versioned JSON API prefix.
- [x] Responses distinguish success, conflicts, stale state, unsupported input, and validation failures; mutation inputs carry expected revisions.
- [x] Host/origin checks and argument-safe request parsing are present before mutation routes are exposed.

Verification:
- [x] Run task test:workspace-service with LocalServerTest.
- [x] Run task run and verify loopback health/status; no second backend or jj process starts.

Files likely touched:
- `backend/workspace-service/pom.xml`
- `backend/workspace-service/src/main/java/app/LocalServer.java`
- `backend/workspace-service/src/test/java/app/LocalServerTest.java`
- `contracts/api.schema.json`
- `backend/pom.xml`

### Checkpoint 2: Official parser round trip

Review exact ranges, identity preservation, library pins, source-preserving edits, and timing from T03-T05. Approve this feasibility gate before integrating collaboration.

- [x] Required task tests and the current slice build pass.
- [x] Evidence and limits are recorded.
- [x] Intermediate human review waived by the user's implement-all instruction; automated gate passed.

## T07: Persist complete workspace snapshots transactionally

- [x] Complete T07

Module: workspace-service
Dependencies: T02, T06
Estimated scope: Medium, 4 expected files.

Add embedded SQLite storage for versioned JSON snapshots and immutable recorded revisions. Create the small publication-intent table for later Git integration.

Acceptance criteria:
- [x] An acknowledged save survives reopen, including invalid text, versions, identity metadata, conflicts, selected revision, and last-valid projection.
- [x] Replacing a workspace history is one database transaction; old recorded revisions remain retrievable.
- [x] Expected revision checks reject stale writers. Fault injection before/after commit yields entirely old or new state.

Verification:
- [x] Run task test:workspace-service with WorkspaceStoreTest against real temporary SQLite files.
- [x] Reopen each fixture twice to verify recovery and idempotent reads.

Files likely touched:
- `backend/workspace-service/src/main/java/app/WorkspaceStore.java`
- `backend/workspace-service/src/test/java/app/WorkspaceStoreTest.java`
- `backend/workspace-service/src/main/resources/schema.sql`
- `backend/workspace-service/pom.xml`

## T08: Store accepted snapshots in a dedicated Git repository

- [x] Complete T08

Module: workspace-service
Dependencies: T03, T07
Estimated scope: Medium, 4 expected files.

Implement Git object/ref operations without a shared mutable checkout. Initialize the accepted pump system and preserve identity metadata.

Acceptance criteria:
- [x] A dedicated repository under .local stores R0 and later immutable source/identity snapshots.
- [x] Accepted ref advancement uses expected-old-value checks; argument-safe invocation and backend-owned paths are enforced.
- [x] Shared-draft and pending-publication refs are separate from accepted history; reference projects are never touched.

Verification:
- [x] Run task test:workspace-service with GitStoreTest in temporary repositories.
- [x] Use git show and git log to inspect real source objects and parent relationships.

Files likely touched:
- `backend/workspace-service/src/main/java/app/GitStore.java`
- `backend/workspace-service/src/test/java/app/GitStoreTest.java`
- `backend/workspace-service/src/main/java/app/ProjectInitializer.java`
- `backend/workspace-service/src/test/java/app/ProjectInitializerTest.java`

### Checkpoint 3: Local persistence checkpoint

Verify SQLite snapshots and a real dedicated Git R0 before exposing workspace creation. No temporary in-memory accepted-state substitute.

- [x] Required task tests and the current slice build pass.
- [x] Evidence and limits are recorded.
- [x] Intermediate human review waived by the user's implement-all instruction; automated gate passed.

## T09: Open and autosave independent workspaces

- [x] Complete T09

Module: workspace-service
Dependencies: T04, T06, T07, T08
Estimated scope: Medium, 5 expected files.

Connect create/read/save/analyze operations to durable storage and the real parser. Autosave is not Record change.

Acceptance criteria:
- [x] Alice and Bob open separate snapshots of the same initial fixture and never share a mutable source buffer.
- [x] Saved text gets a new document/workspace revision; analysis results install only if their input versions still match.
- [x] Invalid text is retained with diagnostics and a last-valid projection; missing or stale analysis never looks current.

Verification:
- [x] Run task test:workspace-service with WorkspaceControllerTest and a real-parser integration case.
- [x] Create two workspaces, save different text, restart the backend, and read both exact buffers.

Files likely touched:
- `backend/workspace-service/src/main/java/app/WorkspaceController.java`
- `backend/workspace-service/src/test/java/app/WorkspaceControllerTest.java`
- `backend/workspace-service/src/main/java/app/AnalysisQueue.java`
- `backend/workspace-service/src/test/java/app/AnalysisQueueTest.java`
- `backend/workspace-service/src/main/java/app/LocalServer.java`

## T10: Bootstrap the browser build and test toolchain

- [x] Complete T10

Module: web-editor
Dependencies: T01, T02
Estimated scope: Medium, 5 expected files.

Set up vanilla TypeScript, Vite, CodeMirror 6, Vitest, and Playwright with exact resolved versions. Avoid a UI framework or diagram-layout dependency for this limited screen.

Acceptance criteria:
- [x] The package manifest and lockfile pin the reviewed candidates; TypeScript and the browser test runner execute.
- [x] A minimal static page builds without backend mocks pretending to be the finished app.
- [x] Development uses a same-origin API proxy; production assets are suitable for serving from the Java process.

Verification:
- [x] Run npm --prefix web ci, npm --prefix web run build, and npm --prefix web run typecheck.
- [x] Check Node engine requirements against the selected Node 24.15.0.

Files likely touched:
- `web/package.json`
- `web/package-lock.json`
- `web/index.html`
- `web/tsconfig.json`
- `web/vite.config.ts`

### Checkpoint 4: Independent persistent workspaces

Verify separate saved buffers, restart behavior, stale-analysis handling, and a working frontend build.

- [x] Required task tests and the current slice build pass.
- [x] Evidence and limits are recorded.
- [x] Intermediate human review waived by the user's implement-all instruction; automated gate passed.

## T11: Edit and save real SysML text in the browser

- [x] Complete T11

Module: web-editor
Dependencies: T09, T10
Estimated scope: Medium, 5 expected files.

Create the toolbar/text/status shell and connect CodeMirror to versioned workspace APIs. Use explicit workspace selection rather than a global actor in localStorage.

Acceptance criteria:
- [x] Typing saves exact text and shows save/analysis status; diagnostics map to the current editor document.
- [x] Refresh restores the selected workspace and an invalid draft; another window can use a different actor in the same profile.
- [x] Older responses cannot overwrite newer typed text. Failed saves remain visibly unsaved/failed.

Verification:
- [x] Run task test:web-editor for text state transitions.
- [x] Run task run; edit and refresh Alice and Bob independently against the real backend.

Files likely touched:
- `web/src/main.ts`
- `web/src/api.ts`
- `web/src/text-editor.ts`
- `web/src/style.css`
- `web/src/text-editor.test.ts`

## T12: Build real WASM projection and comparison

- [x] Complete T12

Module: web-editor
Dependencies: T02, T10
Estimated scope: Medium, 5 expected files.

Add a small Rust crate that projects fixed diagram records and performs advisory scalar comparison. Consume canonical scalar strings rather than floating-point JS values.

Acceptance criteria:
- [x] The crate builds for wasm32-unknown-unknown with a pinned wasm-bindgen dependency and lockfile.
- [x] Native Rust tests consume the shared contract fixtures, including equal decimals and large integers.
- [x] Projection uses supplied fixed coordinates and contains no drag/layout mutation or SysML parsing.

Verification:
- [x] Run cargo test --manifest-path web/wasm/Cargo.toml.
- [x] Run wasm-pack build web/wasm --target web and verify the actual .wasm artifact is produced.

Files likely touched:
- `web/wasm/Cargo.toml`
- `web/wasm/Cargo.lock`
- `web/wasm/src/lib.rs`
- `web/wasm/tests/contracts.rs`
- `web/wasm/README.md`

## T13: Render the live fixed diagram through a WASM worker

- [x] Complete T13

Module: web-editor
Dependencies: T04, T11, T12
Estimated scope: Medium, 5 expected files.

Load the WASM module in a worker and render its records as positioned HTML blocks. Connect selection and status to the current analyzed workspace revision.

Acceptance criteria:
- [x] Real browser WASM loads and produces the pump diagram; worker/module failure has a visible error state.
- [x] Fixed coordinates are unchanged across edits and refresh; zoom/pan alter only the viewport.
- [x] Text edits update values after real analysis; stale responses cannot restore an older diagram.

Verification:
- [x] Run task test:web-editor including a browser WASM-load test.
- [x] Run task run and compare initial/text-edited diagram positions and values.

Files likely touched:
- `web/src/diagram-worker.ts`
- `web/src/diagram.ts`
- `web/src/wasm-client.ts`
- `web/src/diagram.test.ts`
- `web/src/main.ts`

### Checkpoint 5: Text and diagram are connected

Demonstrate real parser analysis feeding a real WASM worker and fixed block view. No fixture-only success claim.

- [x] Required task tests and the current slice build pass.
- [x] Evidence and limits are recorded.
- [x] Intermediate human review waived by the user's implement-all instruction; automated gate passed.

## T14: Expose validated property-edit operations

- [x] Complete T14

Module: workspace-service
Dependencies: T05, T09
Estimated scope: Medium, 3 expected files.

Accept property replacements and supported attribute additions through the versioned API, applying precise parser-backed source patches.

Acceptance criteria:
- [x] Matching expected revision/document/expression is required; clients cannot supply unchecked filesystem paths or arbitrary authoritative source ranges.
- [x] Supported replacements/additions update exact source, reanalyze, and return current status.
- [x] Invalid/outdated analysis, unsupported targets, and stale requests fail without losing the current buffer.

Verification:
- [x] Run task test:workspace-service with AttributeControllerTest.
- [x] Verify unchanged source outside the intended patch and persistent IDs after reanalysis.

Files likely touched:
- `backend/workspace-service/src/main/java/app/AttributeController.java`
- `backend/workspace-service/src/test/java/app/AttributeControllerTest.java`
- `backend/workspace-service/src/main/java/app/LocalServer.java`

## T15: Edit scalar values from fixed blocks

- [x] Complete T15

Module: web-editor
Dependencies: T13, T14
Estimated scope: Medium, 5 expected files.

Add accessible inline/property-panel editing to the fixed diagram. A successful mutation refreshes both editor and graph from the backend result.

Acceptance criteria:
- [x] Editing Boolean, string, integer, and real values changes the corresponding authored source without rounding.
- [x] Read-only/derived properties show reasons; current-source selection highlights the matching editor range.
- [x] Controls are disabled for invalid/outdated analysis and support Enter/Escape without moving blocks.

Verification:
- [x] Run task test:web-editor for graph-to-text updates and keyboard interactions.
- [x] Manually change flowRate from the diagram and confirm comments and coordinates are unchanged.

Files likely touched:
- `web/src/property-editor.ts`
- `web/src/property-editor.test.ts`
- `web/src/diagram.ts`
- `web/src/text-editor.ts`
- `web/src/main.ts`

## T16: Verify stale and invalid single-user round trips

- [x] Complete T16

Module: integration
Dependencies: T15
Estimated scope: Medium, 3 expected files.

Close the first usable vertical slice with browser tests against the real parser and storage before adding collaboration history.

Acceptance criteria:
- [x] Text-to-diagram and diagram-to-text both work against a real backend.
- [x] Invalid source survives refresh and disables graphical edits while the last valid diagram is marked outdated.
- [x] Delayed responses, non-BMP characters, CRLF, and overlapping save requests do not corrupt ranges or newer edits.

Verification:
- [x] Run task test:e2e for roundtrip.spec.ts and invalid-source.spec.ts.
- [x] Run task build and the existing focused suites.

Files likely touched:
- `tests/e2e/playwright.config.ts`
- `tests/e2e/roundtrip.spec.ts`
- `tests/e2e/invalid-source.spec.ts`

### Checkpoint 6: Single-user vertical slice

Text and graphical edits round-trip correctly; invalid text and Unicode/CRLF behavior are verified before collaboration expands.

- [x] Required task tests and the current slice build pass.
- [x] Evidence and limits are recorded.
- [x] Intermediate human review waived by the user's implement-all instruction; automated gate passed.

## T17: Record authored deltas without inheriting provisional edits

- [x] Complete T17

Module: change-engine
Dependencies: T02, T03
Estimated scope: Medium, 5 expected files.

Introduce the pure change-engine module and derive supported authored deltas from parent/current model and source records.

Acceptance criteria:
- [x] Scalar edits and additions become explicit operations with stable change IDs and immutable revision IDs.
- [x] Unchanged inherited conflicts are not recorded as assignments to their provisional value.
- [x] Formatting changes retain source provenance; unsupported structural edits and ambiguous identities return explicit blocked results.

Verification:
- [x] Run task test:change-engine with ChangeRecorderTest.
- [x] Verify deterministic output using supplied ID allocation and no mutation of input snapshots.

Files likely touched:
- `backend/change-engine/pom.xml`
- `backend/change-engine/src/main/java/app/ChangeRecorder.java`
- `backend/change-engine/src/test/java/app/ChangeRecorderTest.java`
- `backend/change-engine/src/main/java/app/SourceDelta.java`
- `backend/pom.xml`

## T18: Persist recorded changes and expose history

- [x] Complete T18

Module: workspace-service
Dependencies: T09, T17
Estimated scope: Medium, 4 expected files.

Add Record change and read-history operations backed by immutable revision records, while keeping autosaved buffers distinct.

Acceptance criteria:
- [x] Record captures a description and supported delta; invalid/unsupported text cannot become a replayable change.
- [x] Recorded history survives restart and distinguishes logical IDs from revision IDs and workspace buffers.
- [x] Historical navigation refuses unrecorded buffers without silently overwriting them.

Verification:
- [x] Run task test:workspace-service with HistoryControllerTest.
- [x] Create two recorded changes, restart, and inspect their parent/delta relationships.

Files likely touched:
- `backend/workspace-service/src/main/java/app/HistoryController.java`
- `backend/workspace-service/src/test/java/app/HistoryControllerTest.java`
- `backend/workspace-service/src/main/java/app/WorkspaceStore.java`
- `backend/workspace-service/src/main/java/app/LocalServer.java`

## T19: Inspect and record workspace history in the UI

- [x] Complete T19

Module: web-editor
Dependencies: T11, T18
Estimated scope: Medium, 4 expected files.

Add Record change and a history panel that selects earlier recorded revisions without losing dirty text.

Acceptance criteria:
- [x] The UI shows descriptions, parent relationships, selected revision, and recorded versus unrecorded state.
- [x] Record change uses the current expected revision and shows validation/unsupported reasons.
- [x] Navigation is blocked until unrecorded edits are recorded; no implicit discard or autosave-as-record behavior.

Verification:
- [x] Run task test:web-editor for history interaction and dirty navigation.
- [x] Record and inspect Alice's A1 and Bob's B1 in separate windows.

Files likely touched:
- `web/src/history.ts`
- `web/src/history.test.ts`
- `web/src/api.ts`
- `web/src/main.ts`

### Checkpoint 7: Recorded history

Verify recorded changes are distinct from autosave and historical navigation never discards unrecorded work.

- [x] Required task tests and the current slice build pass.
- [x] Evidence and limits are recorded.
- [x] Intermediate human review waived by the user's implement-all instruction; automated gate passed.

## T20: Compare three-way scalar edits into structured conflicts

- [x] Complete T20

Module: change-engine
Dependencies: T02, T17
Estimated scope: Medium, 4 expected files.

Implement the approved comparison table independently of parser and storage. Return property conflicts and explicit unsupported/source-conflict results.

Acceptance criteria:
- [x] 10 to 12 versus 10 to 15 conflicts; equal semantic literals converge; independent properties combine.
- [x] Same-name additions reconcile identities if equal and remain explicit conflicts/unsupported if incompatible.
- [x] Conflict provenance retains base, incoming, local, and origin change; no arbitrary winner becomes authoritative.

Verification:
- [x] Run task test:change-engine with ThreeWayMergeTest.
- [x] Run canonical scalar fixture cases without floating-point conversion or evaluated-expression equivalence.

Files likely touched:
- `backend/change-engine/src/main/java/app/ThreeWayMerge.java`
- `backend/change-engine/src/test/java/app/ThreeWayMergeTest.java`
- `backend/change-engine/src/main/java/app/LiteralComparison.java`
- `backend/change-engine/src/test/java/app/LiteralComparisonTest.java`

## T21: Make Git publication recoverable and idempotent

- [x] Complete T21

Module: workspace-service
Dependencies: T07, T08
Estimated scope: Medium, 5 expected files.

Implement the durable publication-intent protocol described in plan.md. Git CAS and SQLite finalization are separate commits and require recovery.

Acceptance criteria:
- [x] Persist candidate commit, expected head/workspace revision, operation ID, and replacement snapshot before moving the accepted ref.
- [x] Finalize the workspace and operation result transactionally; retry returns the prior result instead of creating another accepted commit.
- [x] Recovery reconciles pending intents before mutations resume and never rolls accepted history backward.

Verification:
- [x] Run task test:workspace-service with PublicationCoordinatorTest and startup recovery cases.
- [x] Inject failures before CAS, after CAS, and before final acknowledgement; reopen twice.

Files likely touched:
- `backend/workspace-service/src/main/java/app/PublicationCoordinator.java`
- `backend/workspace-service/src/test/java/app/PublicationCoordinatorTest.java`
- `backend/workspace-service/src/main/java/app/PublicationRecovery.java`
- `backend/workspace-service/src/test/java/app/PublicationRecoveryTest.java`
- `backend/workspace-service/src/main/java/app/WorkspaceStore.java`

### Checkpoint 8: Durable publication primitive

Review SQLite/Git intent recovery and expected-head checks before wiring accepted delivery.

- [x] Required task tests and the current slice build pass.
- [x] Evidence and limits are recorded.
- [x] Intermediate human review waived by the user's implement-all instruction; automated gate passed.

## T22: Replay a draft chain onto a new accepted model

- [x] Complete T22

Module: change-engine + workspace-service
Dependencies: T05, T17, T20
Estimated scope: Medium, 4 expected files.

Connect pure chain replay to a source materializer that remaps each operation against the current candidate document. Preserve source provenance rather than dumping EMF.

Acceptance criteria:
- [x] Replay continues through structured scalar conflicts using labeled local provisional materializations.
- [x] Independent semantic and disjoint source edits survive; overlapping unmergeable source edits block delivery with all source retained.
- [x] Every step remaps current ranges and preserves the original authored delta, rather than comparing only materialized provisional snapshots.

Verification:
- [x] Run task test:change-engine and task test:workspace-service for RebaseEngine and ReplayMaterializer.
- [x] Verify a multi-change chain with comments, Unicode, and inherited conflicts.

Files likely touched:
- `backend/change-engine/src/main/java/app/RebaseEngine.java`
- `backend/change-engine/src/test/java/app/RebaseEngineTest.java`
- `backend/workspace-service/src/main/java/app/ReplayMaterializer.java`
- `backend/workspace-service/src/test/java/app/ReplayMaterializerTest.java`

## T23: Deliver recorded work against the current accepted head

- [x] Complete T23

Module: workspace-service
Dependencies: T18, T21, T22
Estimated scope: Medium, 3 expected files.

Orchestrate revision capture, chain replay, official validation, conflict persistence, and safe accepted publication.

Acceptance criteria:
- [x] Alice's A1 advances accepted history; Bob's stale-base B1 produces needs-resolution with accepted history unchanged.
- [x] Errors, incomplete validation, unsupported edits, and unrecorded buffers prevent publication without losing work.
- [x] Successful delivery appends a child of the current head; racing head changes recompute or return stale-head rather than overwriting.

Verification:
- [x] Run task test:workspace-service with DeliveryControllerTest using the real parser and Git.
- [x] Assert actual accepted ref values for success, conflict, validation failure, and stale-head results.

Files likely touched:
- `backend/workspace-service/src/main/java/app/DeliveryController.java`
- `backend/workspace-service/src/test/java/app/DeliveryControllerTest.java`
- `backend/workspace-service/src/main/java/app/LocalServer.java`

## T24: Expose delivery outcomes and property conflict badges

- [x] Complete T24

Module: web-editor
Dependencies: T19, T23
Estimated scope: Medium, 5 expected files.

Connect Deliver and show backend-confirmed outcomes in text, diagram, and history.

Acceptance criteria:
- [x] Both views label provisional values and show base/incoming/local alternatives without implying resolution.
- [x] Deliver reports accepted, needs-resolution, stale, and validation failures distinctly; other properties stay usable.
- [x] Accepted-head notifications do not overwrite the user's workspace or claim another person's changes are already applied.

Verification:
- [x] Run task test:web-editor for delivery and conflict presentation.
- [x] Reproduce Alice 12 versus Bob 15 in two windows and inspect the unchanged accepted head.

Files likely touched:
- `web/src/delivery.ts`
- `web/src/delivery.test.ts`
- `web/src/conflicts.ts`
- `web/src/diagram.ts`
- `web/src/main.ts`

### Checkpoint 9: Conflicting delivery

Demonstrate Alice 12 versus Bob 15, visible alternatives, no stopped Git rebase, and unchanged accepted head.

- [x] Required task tests and the current slice build pass.
- [x] Evidence and limits are recorded.
- [x] Intermediate human review waived by the user's implement-all instruction; automated gate passed.

## T25: Add a property while another property is conflicted

- [x] Complete T25

Module: web-editor
Dependencies: T05, T14, T15, T24
Estimated scope: Medium, 4 expected files.

Expose supported attribute addition in the selected fixed block without moving it. Use the existing source-edit API.

Acceptance criteria:
- [x] Bob can add alertEnabled = true while flowRate remains unresolved.
- [x] The source insertion preserves flowRate's conflict metadata and the pump's identity and position.
- [x] Invalid names/types, collisions, and outdated analysis produce clear feedback without corrupting the draft.

Verification:
- [x] Run task test:web-editor for attribute addition during a conflict.
- [x] Verify the inserted declaration with the real parser and compare block coordinates before/after.

Files likely touched:
- `web/src/property-editor.ts`
- `web/src/property-editor.test.ts`
- `web/src/api.ts`
- `web/src/diagram.ts`

## T26: Record continued work with inherited conflict provenance

- [x] Complete T26

Module: change-engine + workspace-service
Dependencies: T18, T22, T25
Estimated scope: Medium, 4 expected files.

Verify and complete the B2 recording path. Existing provisional conflicts must not become new authored scalar assignments.

Acceptance criteria:
- [x] B2 records only the alert addition while retaining a reference to B1's conflict.
- [x] B2 remains recordable when candidate-source validation succeeds provisionally and its own edit is supported.
- [x] History, restart, and serialization preserve the distinction between inherited conflict and independent descendant edit.

Verification:
- [x] Run task test:change-engine and task test:workspace-service with inherited-conflict fixtures.
- [x] Inspect B2's stored delta and prove it does not contain a fabricated flowRate = 15 assignment.

Files likely touched:
- `backend/change-engine/src/main/java/app/ChangeRecorder.java`
- `backend/change-engine/src/test/java/app/ChangeRecorderTest.java`
- `backend/workspace-service/src/test/java/app/HistoryControllerTest.java`
- `contracts/golden/history-cases.json`

## T27: Share a conflicted draft without replacing another workspace

- [x] Complete T27

Module: workspace-service + web-editor
Dependencies: T19, T21, T24
Estimated scope: Medium, 5 expected files.

Publish an immutable draft snapshot under a separate Git ref and expose read-only inspection. Reuse the publication-intent protocol for durable shared refs.

Acceptance criteria:
- [x] Shared snapshots contain source, identities, conflict alternatives, and recorded history, even when unresolved.
- [x] Alice can inspect Bob's snapshot without changing her buffers, base, or accepted history.
- [x] Sharing again creates a new immutable snapshot; retries do not duplicate the same operation.

Verification:
- [x] Run task test:workspace-service with DraftControllerTest; run the existing UI suite to catch integration regressions.
- [x] Use the live UI to inspect the shared draft and its Git ref; compare Alice's workspace before/after. Full automated sharing coverage lands in the collaboration E2E task.

Files likely touched:
- `backend/workspace-service/src/main/java/app/DraftController.java`
- `backend/workspace-service/src/test/java/app/DraftControllerTest.java`
- `web/src/shared-drafts.ts`
- `backend/workspace-service/src/main/java/app/LocalServer.java`
- `web/src/main.ts`

### Checkpoint 10: Work and sharing during conflicts

Record B2's independent alert addition and inspect a shared conflicted draft without replacing Alice's workspace.

- [x] Required task tests and the current slice build pass.
- [x] Evidence and limits are recorded.
- [x] Intermediate human review waived by the user's implement-all instruction; automated gate passed.

## T28: Resolve an origin and replay its descendants

- [x] Complete T28

Module: change-engine
Dependencies: T20, T22, T26
Estimated scope: Medium, 4 expected files.

Add pure resolution propagation while preserving every descendant's authored delta and old revision provenance.

Acceptance criteria:
- [x] Resolving B1 to 14 yields new B1/B2 revisions with alertEnabled still true and stable logical change IDs.
- [x] Independent later same-property edits/resolutions are compared and can generate new conflicts; no unconditional overwrite occurs.
- [x] Resolving only a descendant leaves its ancestor unchanged, and further unaffected descendants still replay.

Verification:
- [x] Run task test:change-engine with ResolutionEngineTest.
- [x] Test several descendants, equal resolutions, incompatible later edits, and deterministic supplied revision IDs.

Files likely touched:
- `backend/change-engine/src/main/java/app/ResolutionEngine.java`
- `backend/change-engine/src/test/java/app/ResolutionEngineTest.java`
- `backend/change-engine/src/main/java/app/RebaseEngine.java`
- `contracts/golden/resolution-cases.json`

## T29: Apply resolution as one durable workspace revision

- [x] Complete T29

Module: workspace-service
Dependencies: T05, T18, T28
Estimated scope: Medium, 4 expected files.

Materialize and validate the complete proposed chain, then transactionally replace the workspace history. Keep accepted history untouched until a later delivery.

Acceptance criteria:
- [x] Expected conflict/origin/workspace revisions are checked. Unrelated unrecorded edits block resolution; a versioned conflict proposal is the explicit resolution input, not an unrecorded descendant change.
- [x] All rewritten descendants become visible together; old revisions remain available and fresh source ranges are recomputed.
- [x] Custom invalid values fail explicitly. New conflicts/validation problems remain visible and cannot authorize accepted delivery.

Verification:
- [x] Run task test:workspace-service with ResolutionControllerTest.
- [x] Inject a failure during candidate preparation and before/after snapshot commit; verify no half-rewritten visible history.

Files likely touched:
- `backend/workspace-service/src/main/java/app/ResolutionController.java`
- `backend/workspace-service/src/test/java/app/ResolutionControllerTest.java`
- `backend/workspace-service/src/main/java/app/ReplayMaterializer.java`
- `backend/workspace-service/src/main/java/app/LocalServer.java`

## T30: Resolve conflicts from text or fixed-block controls

- [x] Complete T30

Module: web-editor
Dependencies: T19, T24, T29
Estimated scope: Medium, 5 expected files.

Add incoming/local/custom choices, origin navigation, and visible descendant-replay results. Treat text typed into conflicted ranges as a proposal, not an implicit resolution.

Acceptance criteria:
- [x] An inherited badge navigates to B1; resolving to 14 updates both views and preserves B2's alert.
- [x] Text proposals are kept separate from unrelated dirty edits and require an explicit Resolve action.
- [x] Dirty navigation/resolution is blocked with clear guidance to record unrelated edits; controls are keyboard-accessible and expose new conflicts.

Verification:
- [x] Run task test:web-editor for text and graphical resolution paths.
- [x] Resolve the example from both interfaces and compare current history/revision IDs with backend results.

Files likely touched:
- `web/src/conflicts.ts`
- `web/src/conflicts.test.ts`
- `web/src/text-editor.ts`
- `web/src/history.ts`
- `web/src/main.ts`

### Checkpoint 11: Ancestor resolution

Resolve B1 to 14 and prove B2 survives; exercise an independent descendant edit that creates another conflict.

- [x] Required task tests and the current slice build pass.
- [x] Evidence and limits are recorded.
- [x] Intermediate human review waived by the user's implement-all instruction; automated gate passed.

## T31: Update another workspace without overwriting its work

- [x] Complete T31

Module: workspace-service + web-editor
Dependencies: T23, T30
Estimated scope: Medium, 5 expected files.

Expose explicit Update using the same rebase rules and connect accepted-head notifications to a safe user action.

Acceptance criteria:
- [x] After Bob delivers R2, Alice can explicitly update to flowRate = 14 and alertEnabled = true.
- [x] Alice's own recorded edits rebase rather than disappear; unrecorded buffers block the action.
- [x] Update never writes accepted history, and notifications alone never mutate source.

Verification:
- [x] Run task test:workspace-service with UpdateControllerTest and the existing UI suite.
- [x] Exercise clean, recorded-dirty, unrecorded, and conflicting Alice workspaces; full browser coverage lands in the collaboration E2E task.

Files likely touched:
- `backend/workspace-service/src/main/java/app/UpdateController.java`
- `backend/workspace-service/src/test/java/app/UpdateControllerTest.java`
- `web/src/update.ts`
- `backend/workspace-service/src/main/java/app/LocalServer.java`
- `web/src/main.ts`

## T32: Verify browser/backend contract conformance under races

- [x] Complete T32

Module: model-contracts + web-editor
Dependencies: T16, T27, T30
Estimated scope: Medium, 4 expected files.

Run all shared fixtures through the actual Java authoritative logic, TypeScript boundary, and Rust WASM browser worker. Add delayed-response cases.

Acceptance criteria:
- [x] Canonical values, identity reconciliation, inherited conflicts, and comparison outcomes agree across consumers.
- [x] Outdated analysis or identity-map proposals cannot replace newer workspace state or source mappings.
- [x] Pending text conflict proposals do not count as resolved or get lost when a stale analysis response arrives.

Verification:
- [x] Run task test:model-contracts, task test:web-editor, and focused task test:workspace-service.
- [x] Exercise browser-worker precision and Unicode fixtures using the built WASM artifact, not a JS substitute.

Files likely touched:
- `tests/e2e/contract-conformance.spec.ts`
- `web/src/api.test.ts`
- `backend/workspace-service/src/test/java/app/AnalysisQueueTest.java`
- `web/wasm/tests/contracts.rs`

## T33: Automate the complete two-person acceptance scenario

- [x] Complete T33

Module: integration
Dependencies: T26, T27, T30, T31, T32
Estimated scope: Medium, 3 expected files.

Run the full R0/A1/B1/B2/R2 story against real services in two browser contexts. Verify Git and stored deltas, not just success messages.

Acceptance criteria:
- [x] Alice text/Bob diagram and Alice diagram/Bob text variants both pass.
- [x] Bob continues and shares while conflicted, resolves B1, preserves B2, delivers R2, and Alice explicitly updates.
- [x] Refresh/backend restart retain source/history; fixed positions remain unchanged; accepted commits form an append-only chain.

Verification:
- [x] Run task test:e2e for collaboration.spec.ts with an isolated temp repository.
- [x] Inspect recorded test artifacts, Git ancestry, and B2's authored delta.

Files likely touched:
- `tests/e2e/collaboration.spec.ts`
- `tests/e2e/backend-fixture.ts`
- `tests/e2e/playwright.config.ts`

### Checkpoint 12: Complete collaboration slice

Both editing-direction variants pass through accepted R2 and Alice's explicit update.

- [x] Required task tests and the current slice build pass.
- [x] Evidence and limits are recorded.
- [x] Intermediate human review waived by the user's implement-all instruction; automated gate passed.

## T34: Fault-test publication and recovery at process boundaries

- [x] Complete T34

Module: workspace-service
Dependencies: T21, T23, T27, T29
Estimated scope: Medium, 4 expected files.

Add process-kill tests for the durable protocol, including initial R0, accepted delivery, and draft sharing.

Acceptance criteria:
- [x] Kill before/after intent, Git CAS, SQLite finalization, and acknowledgement; restart twice without duplicate publication.
- [x] Candidate commits remain recoverable; startup finalizes or safely blocks unresolved intents before new mutations.
- [x] Invalid autosaves survive acknowledged saves, and accepted history never rolls backward after partial publication.

Verification:
- [x] Run task test:workspace-service with PublicationCrashTest and StartupRecoveryTest.
- [x] Record expected durable state at each injected boundary and compare after restart.

Files likely touched:
- `backend/workspace-service/src/test/java/app/PublicationCrashTest.java`
- `backend/workspace-service/src/test/java/app/StartupRecoveryTest.java`
- `backend/workspace-service/src/main/java/app/PublicationRecovery.java`
- `backend/workspace-service/src/main/java/app/PublicationCoordinator.java`

## T35: Verify concurrent operations cannot lose work

- [x] Complete T35

Module: workspace-service
Dependencies: T23, T29, T31
Estimated scope: Medium, 4 expected files.

Stress the supported local concurrency model with controlled interleavings rather than timing-dependent sleeps.

Acceptance criteria:
- [x] Two deliveries based on one accepted head preserve both drafts and cannot overwrite the winner.
- [x] Save versus resolution/update and analysis versus identity updates obey expected revision checks.
- [x] Operation retries after lost responses return the same result without duplicate accepted commits or rewrites.

Verification:
- [x] Run task test:workspace-service with ConcurrentWorkspaceTest and OperationRetryTest.
- [x] Run the race suite repeatedly using explicit test barriers/latches.

Files likely touched:
- `backend/workspace-service/src/test/java/app/ConcurrentWorkspaceTest.java`
- `backend/workspace-service/src/test/java/app/OperationRetryTest.java`
- `backend/workspace-service/src/main/java/app/WorkspaceStore.java`
- `backend/workspace-service/src/main/java/app/PublicationCoordinator.java`

## T36: Verify the local service boundary is confined

- [x] Complete T36

Module: workspace-service
Dependencies: T06, T09, T08, T23
Estimated scope: Medium, 5 expected files.

Test and tighten local-only HTTP, filesystem, and resource-loading restrictions. This is boundary verification, not an authentication feature.

Acceptance criteria:
- [x] Foreign-origin mutations and unexpected Host headers are rejected while the intended same-origin UI works.
- [x] Traversal, symlink escapes where applicable, and shell-argument injection cannot access reference repos or arbitrary paths.
- [x] Parser input cannot trigger arbitrary remote resource loading, and client DTOs cannot bypass server-side comparison/validation.

Verification:
- [x] Run task test:workspace-service with LocalBoundaryTest and ResourceLoadingTest.
- [x] Verify Taskfile clear never removes .local data or either reference project.

Files likely touched:
- `backend/workspace-service/src/test/java/app/LocalBoundaryTest.java`
- `backend/sysml-adapter/src/test/java/app/ResourceLoadingTest.java`
- `backend/workspace-service/src/main/java/app/LocalServer.java`
- `backend/workspace-service/src/main/java/app/GitStore.java`
- `backend/sysml-adapter/src/main/java/app/ParserAdapter.java`

### Checkpoint 13: Recovery, concurrency, and local safety

Review process-kill and interleaving tests. SQLite transactions must not be presented as a cross-store atomic Git transaction.

- [x] Required task tests and the current slice build pass.
- [x] Evidence and limits are recorded.
- [x] Intermediate human review waived by the user's implement-all instruction; automated gate passed.

## T37: Verify keyboard access and fixed-layout behavior

- [x] Complete T37

Module: web-editor
Dependencies: T30, T31
Estimated scope: Medium, 4 expected files.

Finish interaction details for the reference-inspired toolbar, split editor, diagram, and history/conflict panel.

Acceptance criteria:
- [x] All record/share/deliver/update/resolve controls are reachable and labeled, with visible focus and status announcements.
- [x] Conflict states are understandable without color and no drag/position-edit action is exposed.
- [x] Attribute overflow, zoom/fit, resizing, and error states do not move model coordinates or hide essential resolution actions.

Verification:
- [x] Run task test:e2e for accessibility-layout.spec.ts.
- [x] Perform a keyboard-only pass through the full conflict scenario and record any limitations.

Files likely touched:
- `web/src/style.css`
- `web/src/diagram.ts`
- `web/src/conflicts.ts`
- `tests/e2e/accessibility-layout.spec.ts`

## T38: Package a repeatable local demo

- [x] Complete T38

Module: integration
Dependencies: T33, T34, T35, T36, T37
Estimated scope: Medium, 5 expected files.

Complete the root task interface, dependency/license record, and walkthrough. Production mode serves the built browser/WASM assets from the Java backend.

Acceptance criteria:
- [x] task setup/run/build/test/lint/clear work as documented with described tasks and default task --list.
- [x] README explains prerequisites, separate workspaces, dedicated repository location, and the exact demo sequence without Git/jj commands.
- [x] All direct/transitive versions are locked; licenses and library provenance are recorded; clear leaves saved work intact.

Verification:
- [x] Run task setup CHECK_ONLY=1, task build, task run, task test, and task lint.
- [x] Start from a fresh project-owned data directory and follow docs/demo.md; inspect existing saved data after clear.

Files likely touched:
- `Taskfile.yml`
- `scripts/toolchain.mjs`
- `README.md`
- `docs/demo.md`
- `docs/dependencies.md`

## T39: Run the release acceptance checkpoint

- [x] Complete T39 implementation and automated verification

Module: integration
Dependencies: T38
Estimated scope: Small, 1 expected files.

Record actual end-to-end verification and known limits. This task does not silently broaden scope or turn failed tests into waived requirements.

Acceptance criteria:
- [x] All specified suites and both editing-direction scenarios pass against the real parser, WASM, backend, SQLite, and Git.
- [x] Record dependency pins, parser timing, test commands/results, and unresolved limitations accurately.
- [x] No reference files changed, no placeholder parser/WASM/test path remains, and the demo matches approved scope.

Verification:
- [x] Run task build, task test, and task lint from the documented setup.
- [ ] User performs the final walkthrough and accepts the delivered version.

Files likely touched:
- `docs/verification.md`

### Checkpoint 14: Release acceptance

All required commands and suites pass; user can reproduce the demo. Any remaining failed requirement is an open task, not a completion claim.

- [x] Required task tests and the current slice build pass.
- [x] Evidence and limits are recorded.
- [ ] Final user review and acceptance.


## Implementation evidence and actual locations

Final aggregate: task test and task lint both passed. 89 tests pass, including 65 Java tests, 9 actual browser tests, 9 TypeScript/compiled-WASM tests, 2 Rust tests, and 4 tooling tests. Standalone official-parser and hash checks also pass. task setup and a real task run startup/health/assets smoke passed.

- Parser: backend/sysml-adapter/src/main/java/app/ParserAdapter.java and SourceEdits.java.
- Pure engine: backend/change-engine/src/main/java/app/ChangeEngine.java.
- Storage/publication: WorkspaceStore.java, GitStore.java, PublicationCoordinator.java in backend/workspace-service.
- Source/history orchestration: WorkspaceService.java, WorkspaceData.java, ReplayMaterializer.java, SourceScope.java.
- HTTP: LocalServer.java and contracts/http-api.md.
- Browser: web/src; real WASM: web/wasm; browser checks: web/e2e.
- Recovery tests: PublicationCrashTest.java runs real halt/restart cases, not only simulated exceptions.
- The initial SVG rendering proposal was refined to fixed HTML block cards with native controls, documented in docs/decisions/0004-fixed-html-blocks.md.
- Library numeric limits, read-only expressions, trusted-local deployment, and upstream redistribution review are documented in README.md and docs/verification.md.

The only open checkboxes are final user acceptance, as requested.
