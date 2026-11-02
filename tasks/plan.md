# Implementation plan: SysML collaboration demo

Status: Approved plan implemented and automatically verified on 2026-11-02. The user requested final review only. See todo.md and ../docs/verification.md.
Date: 2026-11-02

## Scope and task authority

Implement [SPEC.md](../SPEC.md) through thin, demonstrable slices. The first useful slice is real SysML text editing connected to a fixed WASM-generated diagram. The collaboration slices then add recorded history, conflicting delivery, continued work, sharing, and ancestor resolution.

The complete task checklist is [todo.md](todo.md). It contains 39 tasks, dependencies, acceptance criteria, verification commands, and expected files. This plan supplies the architecture choices, sequencing, and operational rules behind that checklist.

The user approved this plan and requested implementation. The user subsequently requested implementation of all remaining work with final review only. Automated checkpoint tests still apply; intermediate human approvals are waived. SQLite and later browser/runtime dependencies are approved choices, not claims that their implementation is already present.

## Evidence gathered during planning

- The project contains specifications only. No existing tasks/plan.md or tasks/todo.md was overwritten from another initiative.
- The reference repositories were inspected read-only.
- Windows has Git 2.45.1, Maven 3.9.11, Node 24.15.0, npm 11.0.0, Rust/Cargo 1.95.0, wasm-pack 0.13.1, and Task 3.50.0.
- Both wasm32-unknown-unknown and the native Windows Rust target are installed.
- Shell java resolves to a Java 21 runtime, while shell javac resolves to JDK 17.
- JAVA_HOME points to an existing complete JDK 21.0.6. Calling its compiler explicitly succeeds; Maven already runs on that JDK. No JDK installation is currently required. T01 makes project commands use it consistently without changing global PATH.
- Package registry metadata was queried to select explicit candidates. Metadata confirms published versions, not that these dependencies build together.
- No parser build, package installation, application test, database creation, or demo Git initialization was performed during planning.

## Technology choices for approval

| Concern | Initial choice | Reason |
|---|---|---|
| Backend | Java 21.0.6, Maven 3.9.11 | Matches the locally available full JDK and researched official parser requirements |
| HTTP | JDK jdk.httpserver, explicit route handlers | Avoid another web framework for a small trusted-local service |
| JSON | Gson 2.15.0 candidate | Small application DTO boundary; explicit string representation for canonical numbers |
| Tests | JUnit Jupiter 6.1.3 candidate | Current published Java test dependency; validate alongside Pilot packaging |
| Workspace persistence | SQLite through sqlite-jdbc 3.54.1.0 candidate | Transactional snapshots without a second process or a custom Windows file journal |
| Accepted/shared history | Git CLI, argument-safe process invocation | Real Git objects and expected-head ref updates without a shared mutable checkout |
| Frontend | Vanilla TypeScript 7.0.2, Vite 8.3.5 | Small modular screen without an additional UI framework |
| Text editor | CodeMirror 6, codemirror 6.0.2 | Production editor component with document transactions and decorations |
| Browser testing | Vitest 5.0.5, Playwright test 1.64.1 | Unit/state tests plus real two-context browser tests |
| Schema checks | Ajv 8.20.0 | TypeScript fixture validation against application schemas |
| WASM | Rust 1.95.0, wasm-pack 0.13.1, wasm-bindgen 0.2.130 candidate | Actual browser-worker projection and advisory comparison |
| Rust data encoding | serde 1.0.229, serde_json 1.0.154 candidates | Shared serialized DTOs; no parsing SysML in Rust |

These exact dependency candidates came from npm, Maven Central metadata, and crates.io during this planning turn. They must pass the bootstrap/compatibility checks and be recorded in manifests and lockfiles. Do not interpret latest published as proven compatible. If a conflict requires a different version, record why and update the plan/dependency record before using it.

The official Pilot source revision inspected during research was a9676a3c4825201b270b59289bf284154842bde1. Use it as the first build candidate, not an unverified promise of a stable embedded SDK. T03 must pin a working official revision and its matching library distribution, including checksums/provenance. Do not mix libraries from a moving default branch with another parser revision.

No second parser, React-style framework, diagram-layout engine, jj executable, remote API service, or external database server is needed.

## Module ownership and dependencies

Follow the approved [capability map](../CAPABILITY-MAP.md):

- model-contracts owns schemas, shared meanings, Java boundary records, and golden fixtures.
- sysml-adapter owns official analysis, source ranges, and precise edits.
- change-engine owns pure authored-delta comparison and replay.
- workspace-service owns runtime, SQLite, Git, HTTP, and the orchestration between parser and engine.
- web-editor owns TypeScript controls, CodeMirror, fixed-position HTML block rendering, and Rust/WASM view computations.

The parser never calls the change engine. The change engine never calls Git or the parser. The service applies proposals, validates their materialized source, and commits only complete workspace transitions.

WASM computes browser view data and advisory differences. The Java engine independently determines authoritative differences. Shared fixtures enforce agreement; the browser cannot authorize its own delivery.

## Contract and source rules

### Identifiers and scalar values

Persist an application identity sidecar. Existing fixture members get IDs that survive reparsing. New attributes get IDs allocated by the service. A name or qualified authored path can locate a reconciliation candidate within the restricted subset, but is not the identity itself.

Reject ambiguity rather than attaching an old ID to a different member. Equal concurrent additions with different tentative IDs need a canonical mapping that is applied to descendant deltas too.

Canonical numbers travel as strings with a scalar-kind tag. Neither JavaScript Number nor implicit JSON floating-point conversion may decide equality for large integers or precise decimals.

### Edits and source preservation

Store authored semantic operations and source provenance, not only materialized model snapshots. Each source edit is tied to the exact old document, expected expression, and UTF-16 range.

After every replay step, reanalyze/recompute mappings before patching the next version. Never replay old byte offsets onto a new parent.

Preserve residual source edits such as comments/formatting independently from semantic value replacements. Start with disjoint, verifiable patches. If residual edits overlap or cannot be placed safely, retain all inputs and return a blocked source conflict. Do not silently regenerate a whole document or invent a formatting merge.

### Conflicted source

Store alternatives and provenance separately. Materialize the local alternative as parseable provisional text, visibly marked in the UI. This is an editing representation, not a chosen semantic winner.

B2 inheriting a provisional value from B1 does not introduce an assignment to that value. Its own authored delta remains the alert addition.

A text edit inside a conflicted range becomes a versioned resolution proposal. The UI/service distinguish that proposal from unrelated unrecorded work. Resolve consumes the explicit proposal; unrelated dirty edits still block historical operations. This prevents the impossible workflow where making a custom resolution always causes the dirty-buffer guard to reject that same resolution.

For the first version, offer Record or Cancel when unrelated edits block history navigation. Do not add a general shelving feature. Preserve the saved text until the user records or deliberately edits it.

## Persistence and publication design

### Storage boundary

Use one embedded SQLite database under .local for workspace snapshots, exact buffers, identity maps, last-valid projections, immutable change revisions, and operation results. Store versioned JSON payloads rather than introducing a large relational model for every SysML element.

Use the dedicated local Git repository for accepted source/identity snapshots and immutable shared drafts. Only this backend process manages those refs. Keep workspace analysis in memory and out of Git working-copy state.

Database transactions atomically replace complete workspace snapshots. This covers an entire origin-resolution/replay result, not one descendant at a time.

Retain old recorded revisions and references needed to recover unpublished candidates. Do not require garbage collection or history compaction in this version.

### Publication protocol

A SQLite transaction and a Git ref update are not one atomic transaction. Use a small durable publication-intent record:

1. Capture operation ID, workspace revision, source versions, and expected accepted head.
2. Compute/replay an immutable candidate, apply precise source edits, and validate using the official parser.
3. Recheck the captured revisions before publication.
4. Create the exact Git candidate commit. Retain it under a pending operation ref so it remains recoverable.
5. Persist the intent with expected old head, candidate OID, expected workspace revision, and complete replacement workspace snapshot.
6. Advance the accepted ref with compare-and-swap against the captured old head.
7. In a SQLite transaction, install the replacement snapshot and final operation result.
8. Acknowledge success and notify other workspaces only after finalization.

Hold the workspace mutation lock across finalization and serialize accepted publication. If finalization fails after Git advances, stop further publication and recover. Do not move Git backward to pretend it never happened.

Preparation may run against immutable snapshots outside the publication lock. Recheck revisions after acquiring the commit locks. A race either triggers recomputation or returns stale state with work preserved.

### Recovery rules

Recover pending operations before accepting mutations on startup:

| Observed target ref | Recovery |
|---|---|
| Still the expected old head | Retry the recorded CAS or abort explicitly while preserving the workspace |
| Equal to the candidate | Finalize the recorded workspace transition idempotently |
| Candidate is an ancestor of current head | Finalize the operation without rewinding Git |
| Unrelated head | Preserve work and block with stale/recovery status; never overwrite |

An operation ID makes lost-response retries return the recorded result. Apply the same principle to shared-draft publication. Initialize R0 idempotently, with startup tests covering failure between creating its Git snapshot and opening workspaces.

Use a single application owner lock for each data directory. Multiple browser windows are supported; multiple backend processes racing on the same database/repository are not a required deployment mode.

### Concurrency

- One serialized parser worker initially. Measure before attempting parallel parser access.
- Workspace mutations are version checked and serialized per workspace.
- Accepted-head updates and recovery are serialized.
- Browser state accepts only results matching its current versions.
- Install identity-map updates together with the matching analysis result, never from a stale analysis.
- The Git adapter uses isolated index/object operations or test-owned temporary trees, not one live checkout shared by Alice and Bob.

## Vertical slices and checkpoints

| Slice | Tasks | Demonstrable outcome |
|---|---|---|
| Contract and official-parser proof | T01-T05 | Exact source can become a semantic model and round-trip a value/addition |
| Local durable runtime | T06-T09 | Alice and Bob have separate persistent buffers based on real Git R0 |
| Live text and fixed diagram | T10-T16 | Real parser and real browser WASM support both editing directions |
| Recorded history | T17-T19 | Autosave and recorded changes are visibly distinct |
| Conflicting delivery | T20-T24 | Alice delivers 12; Bob's 15 becomes an explicit conflict |
| Continued work and sharing | T25-T27 | Bob records an alert and shares the unresolved draft |
| Resolution propagation | T28-T31 | Resolve B1 to 14, preserve B2, deliver, then update Alice |
| Integrated correctness | T32-T37 | Two-person flow, recovery, races, local safety, and accessibility verified |
| Repeatable demo | T38-T39 | Documented commands and release acceptance evidence |

todo.md contains checkpoint checklists after each short group. The official parser round-trip gate is mandatory before collaboration integration. If packaging or source mapping fails, stop and revise the approved approach instead of accumulating UI code around a fake parser.

## Safe parallel work

Parallelize only after the relevant contracts and prerequisites pass:

1. After T02, frontend bootstrap T10 and WASM fixture work T12 can proceed while T03 proves parser startup.
2. After T03, parser mapping/edit tasks T04-T05 and pure change tasks T17/T20 can proceed independently.
3. After T05 and the runtime contract are stable, source-backed API work and fixed-diagram UI work can run in separate directories.
4. After T20, replay logic and publication/recovery work can progress independently until T23 integrates them.
5. After the complete flow works, crash tests, concurrency tests, and UI accessibility verification can run independently.

Use at most three implementation lanes initially:
- Parser/runtime lane.
- Pure change-engine lane.
- Browser/WASM lane.

The integration owner handles shared schema changes, backend/pom.xml, Taskfile.yml, package lockfiles, shared golden fixtures, and API wiring. A worker must request a contract change instead of quietly diverging. If a task touches an integration-owned file, serialize that edit even when the rest of the task is parallel.

Do not parallelize two edits to the same browser root component or backend router. Independent test processes use separate data directories, ports, and temporary repositories.

Planning initially used one read-only advisor. Implementation later used separate parser, pure-engine, browser/WASM, reliability-test, and verification workers with explicit file ownership. The parent reviewed the changes and integrated the complete application.

## Task index

The checklist and full task details live only in todo.md.

- [T01: Make the selected toolchain reproducible](todo.md#t01-make-the-selected-toolchain-reproducible)
- [T02: Freeze the shared model and revision contracts](todo.md#t02-freeze-the-shared-model-and-revision-contracts)
- [T03: Prove standalone official-parser startup](todo.md#t03-prove-standalone-official-parser-startup)
- [T04: Extract versioned models and precise source mappings](todo.md#t04-extract-versioned-models-and-precise-source-mappings)
- [T05: Round-trip graphical edits without losing source](todo.md#t05-round-trip-graphical-edits-without-losing-source)
- [T06: Start the local application HTTP boundary](todo.md#t06-start-the-local-application-http-boundary)
- [T07: Persist complete workspace snapshots transactionally](todo.md#t07-persist-complete-workspace-snapshots-transactionally)
- [T08: Store accepted snapshots in a dedicated Git repository](todo.md#t08-store-accepted-snapshots-in-a-dedicated-git-repository)
- [T09: Open and autosave independent workspaces](todo.md#t09-open-and-autosave-independent-workspaces)
- [T10: Bootstrap the browser build and test toolchain](todo.md#t10-bootstrap-the-browser-build-and-test-toolchain)
- [T11: Edit and save real SysML text in the browser](todo.md#t11-edit-and-save-real-sysml-text-in-the-browser)
- [T12: Build real WASM projection and comparison](todo.md#t12-build-real-wasm-projection-and-comparison)
- [T13: Render the live fixed diagram through a WASM worker](todo.md#t13-render-the-live-fixed-diagram-through-a-wasm-worker)
- [T14: Expose validated property-edit operations](todo.md#t14-expose-validated-property-edit-operations)
- [T15: Edit scalar values from fixed blocks](todo.md#t15-edit-scalar-values-from-fixed-blocks)
- [T16: Verify stale and invalid single-user round trips](todo.md#t16-verify-stale-and-invalid-single-user-round-trips)
- [T17: Record authored deltas without inheriting provisional edits](todo.md#t17-record-authored-deltas-without-inheriting-provisional-edits)
- [T18: Persist recorded changes and expose history](todo.md#t18-persist-recorded-changes-and-expose-history)
- [T19: Inspect and record workspace history in the UI](todo.md#t19-inspect-and-record-workspace-history-in-the-ui)
- [T20: Compare three-way scalar edits into structured conflicts](todo.md#t20-compare-three-way-scalar-edits-into-structured-conflicts)
- [T21: Make Git publication recoverable and idempotent](todo.md#t21-make-git-publication-recoverable-and-idempotent)
- [T22: Replay a draft chain onto a new accepted model](todo.md#t22-replay-a-draft-chain-onto-a-new-accepted-model)
- [T23: Deliver recorded work against the current accepted head](todo.md#t23-deliver-recorded-work-against-the-current-accepted-head)
- [T24: Expose delivery outcomes and property conflict badges](todo.md#t24-expose-delivery-outcomes-and-property-conflict-badges)
- [T25: Add a property while another property is conflicted](todo.md#t25-add-a-property-while-another-property-is-conflicted)
- [T26: Record continued work with inherited conflict provenance](todo.md#t26-record-continued-work-with-inherited-conflict-provenance)
- [T27: Share a conflicted draft without replacing another workspace](todo.md#t27-share-a-conflicted-draft-without-replacing-another-workspace)
- [T28: Resolve an origin and replay its descendants](todo.md#t28-resolve-an-origin-and-replay-its-descendants)
- [T29: Apply resolution as one durable workspace revision](todo.md#t29-apply-resolution-as-one-durable-workspace-revision)
- [T30: Resolve conflicts from text or fixed-block controls](todo.md#t30-resolve-conflicts-from-text-or-fixed-block-controls)
- [T31: Update another workspace without overwriting its work](todo.md#t31-update-another-workspace-without-overwriting-its-work)
- [T32: Verify browser/backend contract conformance under races](todo.md#t32-verify-browserbackend-contract-conformance-under-races)
- [T33: Automate the complete two-person acceptance scenario](todo.md#t33-automate-the-complete-two-person-acceptance-scenario)
- [T34: Fault-test publication and recovery at process boundaries](todo.md#t34-fault-test-publication-and-recovery-at-process-boundaries)
- [T35: Verify concurrent operations cannot lose work](todo.md#t35-verify-concurrent-operations-cannot-lose-work)
- [T36: Verify the local service boundary is confined](todo.md#t36-verify-the-local-service-boundary-is-confined)
- [T37: Verify keyboard access and fixed-layout behavior](todo.md#t37-verify-keyboard-access-and-fixed-layout-behavior)
- [T38: Package a repeatable local demo](todo.md#t38-package-a-repeatable-local-demo)
- [T39: Run the release acceptance checkpoint](todo.md#t39-run-the-release-acceptance-checkpoint)

## Verification approach

- Pure engine tests use manually specified expected outputs and injected IDs.
- Official-parser tests load matching libraries and use real analysis. Mocks cannot establish adapter correctness.
- Backend integration tests exercise real SQLite files and real Git refs.
- Browser tests load the compiled WASM worker and the actual backend.
- The complete story runs in both text-first and diagram-first variants.
- Process-kill tests verify every boundary of the SQLite/Git publication protocol and restart twice.
- Race tests use explicit barriers, not sleep-based timing.
- Source tests include comments, CRLF, non-BMP characters, repeated names in different owners, large numbers, and ambiguous identity.
- Security tests cover local host/origin checks, path confinement, resource loading, and client attempts to bypass validation.
- Verify the generated code and actual diffs from workers before marking tasks complete.

Required final commands are those in SPEC.md. Early tasks run only their introduced targets, with unsupported targets failing explicitly. Taskfile.yml must provide descriptions, a default task --list action, task run, and non-destructive task clear.

## Risks and stop conditions

| Risk | Mitigation or stop condition |
|---|---|
| Pilot cannot embed cleanly | T03 fails early; record the exact blocker and ask before changing parser/deployment |
| Missing source mappings | T04-T05 must prove accurate literal and insertion ranges before graph editing |
| Dependency version conflicts | Test candidates against the pinned Pilot; document replacement pins rather than forcing all transitives upward |
| Parser global state leaks | Serialize initial analysis and test workspace isolation |
| Inherited conflict becomes a fake edit | Persist original authored deltas and assert B2 has no flowRate assignment |
| Resolution destroys later choices | Compare each descendant against its original parent context; retain new conflicts |
| Dirty text conflicts with resolution input | Separate explicit resolution proposals from unrelated dirty work |
| Source patching damages comments or ranges | Expected-expression/version checks, Unicode/CRLF tests, explicit source-conflict blocking |
| SQLite/Git split publication | Durable intent, Git CAS, idempotent recovery, and process-kill tests |
| Java/Rust numeric mismatch | Canonical string scalars and shared fixtures run through actual WASM |
| Fixed blocks overflow with added attributes | Fixed positions, scroll/detail property area, keyboard-accessible selection |
| Scope expands into a full SysML editor | Reject unsupported edits while preserving text; ask before adding structural merge |

## Open implementation gates

The product behavior is settled. Implementation still must establish:
- Successful pinned official parser/library packaging and license record.
- Compatible dependency resolution for the proposed candidates.
- Identity mapping and source preservation proven by the fixture tests.
- Real analysis duration and whether full reanalysis is adequate.

No fixed performance number is claimed before measurement. No general SysML semantic conformance, arbitrary history merging, browser-only parser support, or network collaboration is promised.

## Primary references

- Official Pilot candidate: https://github.com/Systems-Modeling/SysML-v2-Pilot-Implementation/tree/a9676a3c4825201b270b59289bf284154842bde1
- JDK HTTP server: https://docs.oracle.com/en/java/javase/21/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html
- SQLite transactions: https://www.sqlite.org/lang_transaction.html
- SQLite JDBC: https://github.com/xerial/sqlite-jdbc
- Git expected-old-value ref updates: https://git-scm.com/docs/git-update-ref
- CodeMirror reference: https://codemirror.net/docs/ref/
- Vite worker support: https://vite.dev/guide/features.html#web-workers
- wasm-bindgen guide: https://wasm-bindgen.github.io/wasm-bindgen/
- Playwright browser contexts: https://playwright.dev/docs/browser-contexts

These are implementation reference targets, not claims that every API has already been tested. Before writing framework-specific code, read the version-appropriate official documentation and follow the pinned version.
