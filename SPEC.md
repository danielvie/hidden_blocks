# SysML collaboration demo specification

Status: Approved specification implemented and automatically verified on 2026-11-02. Final user acceptance is pending. See docs/verification.md and tasks/todo.md.
Date: 2026-11-02

## Objective

This is a disposable proof of concept, not a production application or production foundation. Its only question is:

> Can a SysML v2 editing application use a jj-like flow to rebase different changes in a way that is easy to understand and intuitive to use?

The local app is an experiment for answering that question. Two people edit a textual SysML v2 system, inspect fixed graphical views, deliver conflicting work, continue making independent changes, and resolve an earlier conflict with automatic propagation through later changes.

Evaluate technical feasibility and human comprehension separately. Automated checks can establish preservation, conflicts and replay, but they cannot establish intuitive UX. The user-facing success condition is that a person can complete the short Alice/Bob scenario and explain what changed, what conflicted, what was rebased, and why later work survived, without understanding Git commands or internal revision IDs.

Do not prioritize production readiness, broad SysML coverage or infrastructure work that does not answer this question. [AGENTS.md](AGENTS.md) keeps this governing intent for future sessions.

The app uses the official SysML v2 Pilot Implementation locally. Jujutsu is a behavioral reference, not a runtime dependency. Git stores shared project history; the application owns semantic conflicts and draft change history.

Start here, then read [the capability map](CAPABILITY-MAP.md) and the module specifications it indexes. Shared terms are defined in [CONTEXT.md](CONTEXT.md).

## Agreed scope and assumptions

- One trusted local user emulates Alice and Bob through ready-made environments and direct header buttons. They may switch in one tab or work in separate tabs.
- Each workspace has independent buffers, recorded changes, and a base accepted revision.
- One local backend serves the application and owns a dedicated demo Git repository.
- The browser uses real WebAssembly for fixed diagram projection and advisory model comparison.
- The official parser runs in the backend, not in WebAssembly.
- Text and graphical edits address the same authored SysML source.
- Graphical editing supports existing scalar attribute values and adding scalar attributes to existing blocks.
- First-version scalar values are explicit Boolean, string, integer, and real literals. Parser validation determines valid SysML spelling and types.
- The reference system has a fixed set of blocks and relationships. Their positions are predetermined and do not change with edits or conflicts.
- The approved diagram-first design shows linked port connections, specialization cues, and inherited user-defined features. Definitions are inspected on demand rather than all appearing on the normal canvas.
- The connected example starts in separate data; original projects remain available without reset.
- Arbitrary text can be preserved as a draft. Only the supported edit subset can become recorded, replayable changes.
- Draft sharing may include structured conflicts. Accepted delivery requires no conflicts and successful validation.
- Browser refresh and backend restart preserve saved workspace state and history, unless the user explicitly resets the disposable PoC.
- One connected reference is the normal entry point. Header Reset clears both environments, conflicts, histories and shared drafts, restores the reference, and recreates Alice/Bob from one base. Bob/Alice switch without manual workspace creation.
- Reset is generation-checked and recoverable. Retired environment IDs and late browser responses cannot revive the discarded generation.
- No existing reference or application-source repository is modified or reset by the demo Reset. Only the running application's dedicated PoC data is affected.

## Reference material

- Block appearance and routing: [simple GPUI prototype](../gpui-blocks/rust_gpui_blocks_simple/README.md) and its [browser view](../gpui-blocks/rust_gpui_blocks_simple/web/index.html).
- Actual jj sequence: [lab steps](../lab_jujutsu/STEPS.md).
- Intended two-person SysML sequence: [collaboration diagram](../lab_jujutsu/docs/doc-02-sysml-collaborative-conflict.excalidraw).

Preserve the reference's toolbar, central block diagram, property-level interaction, and status feedback. Do not copy its block dragging, deletion, port creation, or wiring tools into this version.

## Acceptance scenario

These values describe the scenario, not a claim that an illustrative snippet is parser-validated SysML. The parser feasibility check must establish the exact fixture.

1. Initialize accepted revision R0 with Pump.flowRate = 10 and a fixed diagram.
2. Open Alice and Bob from R0.
3. Alice changes flowRate to 12 through the text editor, records A1, and delivers it. Accepted history advances to R1.
4. Bob independently changes flowRate to 15 through the diagram and records B1.
5. Bob attempts delivery. The backend rebases his draft chain onto R1, records a conflict containing base 10, accepted 12, and local 15, and leaves accepted history unchanged.
6. The delivery attempt completes with a needs-resolution result. It does not leave a stopped Git rebase or a global editing lock.
7. Bob adds alertEnabled = true to the pump and records B2 while B1 remains conflicted. He can share that draft. Alice can inspect it without replacing her own workspace.
8. Bob selects B1 and explicitly resolves flowRate to 14 in the conflict controls.
9. The backend creates new revisions of B1 and its local descendants. B2 retains alertEnabled = true and inherits flowRate = 14.
10. Text and diagram agree on the new revision and report validation results.
11. Bob delivers the resolved tip. Accepted history advances to R2 without rewriting R1.
12. Alice explicitly updates her workspace to R2 and sees flowRate = 14 and alertEnabled = true. Notification of R2 must not overwrite her local edits.
13. Reopening the app restores the accepted system and the recorded draft history.

Automated verification must also run the editing directions in reverse: Alice edits graphically and Bob edits textually.

## Scope limits

Out of scope:
- Moving blocks, manual layout, layout collaboration, or user-edited diagram coordinates.
- Creating, deleting, or renaming blocks; removing attributes; modifying types, imports, relationships, ports, or connections. Existing connection/specialization visualization is supported, but topology editing and structural conflict merging are not.
- General SysML semantic merge, expression rewriting, unit conversion, or evaluation-based equality.
- Resolving delete-versus-edit conflicts for arbitrary models.
- Real-time co-editing of one text buffer, authentication, remote hosting, and external Git hosting.
- Reimplementing jj commands, arbitrary revision graphs or merges between draft branches. Accepted history is not rewritten during collaboration; the explicit disposable-demo Reset is the deliberate exception that starts fresh history.
- A complete SysML IDE, simulator, debugger, or full language-conformance claim.

The official parser may understand constructs outside this scope. That does not make those constructs editable or mergeable by this app.

## Runtime and proposed technology

| Concern | Specification |
|---|---|
| Semantic analysis | Official SysML-v2-Pilot-Implementation, exact revision and matching libraries pinned after the feasibility check |
| Backend | One Java 21 application embedding the parser; no parser sidecar initially |
| Browser | TypeScript, CodeMirror, fixed-position HTML block diagram, accessible HTML controls |
| Browser computations | Rust compiled to wasm32-unknown-unknown using wasm-bindgen, hosted under web-editor |
| Collaboration authority | Backend change-engine and workspace-service |
| Repository | Local Git with append-only accepted history and separate draft records |
| Build interface | Taskfile.yml at the project root |

Java 21 reflects the inspected Pilot build, not a successful local build. The Java HTTP framework, editor component, frontend bundler, and exact Rust/TypeScript tool versions are implementation-plan selections. Pin their versions before implementation. Do not silently replace the official parser.

The WASM comparison is advisory. The backend independently computes the authoritative result from stored source and model snapshots. A forged or stale browser comparison cannot authorize delivery.

## Data flow

1. Save versioned source in a workspace.
2. Analyze a consistent workspace snapshot with the official parser and matching standard libraries.
3. Project authored elements, relevant semantic information, source ranges, and diagnostics into application-owned contracts.
4. Render the diagram and advisory comparisons in the browser.
5. Apply source-preserving graphical edits using current mappings, then reanalyze.
6. Record supported authored edits as a change relative to a parent.
7. On delivery, compare against the latest accepted head and replay the workspace's changes.
8. Store structured conflicts or publish a validated conflict-free result.
9. On ancestor resolution, replay local descendants before exposing the new history revision.

## Invalid and conflicted state

Parse failure, semantic validation failure, unsupported editing, and merge conflict are separate states.

Invalid source is saved but is not recordable or deliverable. The diagram may show the last valid analysis, clearly marked outdated, with graphical mutations disabled.

A structured conflict is not a syntax error. A workspace may retain a parseable provisional source document while conflict records preserve all alternatives. Every provisional conflicted value is labeled in both views and cannot be treated as an accepted value. Conflict markers are not inserted into parser input.

Typing in a conflicted property's range does not silently resolve the conflict. The UI stages the proposed value and requires an explicit Resolve action. Changes outside those ranges remain recordable when otherwise supported and valid.

## Project structure

Implemented layout. Parser and change-engine details remain separate from workspace/Git orchestration. Browser tests are colocated under web/e2e.

```text
sysml-collab/
  SPEC.md
  CAPABILITY-MAP.md
  CONTEXT.md
  SPEC-model-contracts.md
  SPEC-sysml-adapter.md
  SPEC-change-engine.md
  SPEC-workspace-service.md
  SPEC-web-editor.md
  docs/decisions/
  contracts/                      # schemas and shared golden fixtures
  backend/
    sysml-adapter/
    change-engine/
    workspace-service/            # application entry point and Git adapter
  web/                            # TypeScript UI
    wasm/                         # Rust projection/comparison crate
  web/e2e/                        # real-browser integration tests
  fixtures/
  Taskfile.yml
  .local/                         # generated runtime data, excluded from source control
```

The application source project and the runtime demo Git repository are distinct. Runtime data defaults beneath .local/, never inside either reference project.

## Command contract

The following commands are implemented. Detailed setup and isolation behavior is documented in docs/build.md.

Run from sysml-collab:

| Command | Required result |
|---|---|
| task | List available tasks using task --list |
| task setup | Verify prerequisites and install/build the pinned parser and dependencies |
| task run | Start the local backend and browser application |
| task build | Build the backend, frontend, and browser WASM artifact |
| task test | Run all automated checks |
| task test:model-contracts | Verify schemas, encoding, and golden fixtures |
| task test:sysml-adapter | Run real-parser integration and source-edit tests |
| task test:change-engine | Run merge and replay tests |
| task test:workspace-service | Run persistence, concurrency, and Git tests in temporary repositories |
| task test:web-editor | Run UI and real-WASM tests |
| task test:e2e | Run the two-browser collaboration scenario |
| task lint | Run the selected formatters and static checks |
| task clear | Remove generated build outputs only, never workspace data or Git history |

Taskfile.yml must contain descriptions for the main tasks. Do not add placeholder commands that claim tests passed without running them.

## Code style

Use explicit domain names and small functions with typed inputs and outputs. Separate pure comparison rules from parsing, storage, HTTP, and rendering. Do not expose EMF or Xtext objects through application APIs.

Illustrative TypeScript boundary, not an implemented API:

```typescript
type SetAttributeValue = {
  workspaceId: string;
  expectedWorkspaceRevision: string;
  expectedDocumentVersion: number;
  elementId: string;
  attributeId: string;
  expectedExpression: string;
  replacementExpression: string;
};
```

Reject stale versions before using stored ranges. Use discriminated result states rather than an ambiguous success flag. Comments explain constraints, not obvious statements. Keep contract field names consistent across Java, TypeScript, and Rust.

## Testing strategy

Each module specification lists its required cases. Shared golden fixtures provide expected normalized models and comparison results. Unit tests must not substitute for integration tests against the actual official parser.

End-to-end tests run two independent browser contexts against a real local backend and a temporary Git repository. Assert repository history as well as UI messages. Tests must prove that independent work survives ancestor resolution and failed delivery never moves accepted history.

Use real browser WASM loading in at least one automated test; a JavaScript stub is insufficient. Include Unicode and CRLF source mapping cases, restarts, stale edits, and concurrent delivery.

## Boundaries

Always:
- Preserve source text, comments, and unrelated edits.
- Validate using the pinned official parser and matching libraries.
- Treat browser output as untrusted input to authoritative operations.
- Keep conflicts explicit and retain revision provenance.
- Test each change and document actual verification limits.

Ask first:
- Broaden supported SysML edits or require external services.
- Replace the parser, change accepted-history policy, or add another backend process.
- Add remote access or authentication.
- Modify either reference project or delete demo history outside the explicitly authorized Reset operation.

Never:
- Silently choose a conflict winner, discard unsupported text, or overwrite stale edits.
- Claim a conflicted or incomplete model is accepted or fully valid.
- Force-push or rewrite accepted history during delivery/replay. The user-authorized PoC Reset starts a new reference history, scoped to the demo repository.
- Use a regex parser as a substitute for official semantic analysis.
- Require jj to run the application.

## Open technical questions and gates

No unresolved product choice blocks this specification. These engineering questions must be settled during the approved planning and feasibility work, before dependent implementation:

1. Which exact Pilot revision and standard libraries build and embed successfully on this machine?
2. Can that build expose precise source ranges and required semantics for the fixture while preserving comments and line endings?
3. What editor/build/HTTP dependencies and exact versions will be pinned?
4. What analysis cost does the real fixture have, and does full workspace reanalysis suffice? Do not assume parser concurrency is safe.
5. What minimal persistent identity mapping survives reparsing and attribute insertion without ambiguity?

Do not promise support for a construct until its parser and round-trip fixtures pass. A failed feasibility check requires revising this specification, not silently substituting another parser.
