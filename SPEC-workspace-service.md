# Specification: workspace-service

Status: Approved in conversation on 2026-11-02.
Module ID: workspace-service.
Depends on: model-contracts, sysml-adapter, change-engine.
Parent: [SPEC.md](SPEC.md).

## Objective and ownership

Run one local Java application that serves the web UI, isolates workspaces, orchestrates analysis and replay, persists state, and publishes accepted models into Git.

Own backend/workspace-service, application configuration, HTTP boundary, and the Git adapter. Do not implement SysML parsing or merge rules here.

## Workspace lifecycle

Initialize the dedicated PoC from the fixed, parser-validated connected reference. The normal browser entry point creates Alice and Bob together from one accepted base and persists their stable pairing. Opening Bob after Alice delivers must not recreate Bob from Alice's newer delivery. See contracts/poc-demo.md for generation/reset behavior.

A workspace contains:
- Actor label, stable workspace ID, accepted base revision, and current workspace revision.
- Exact source buffers and document versions.
- Identity map, analysis status, and last-valid diagram input.
- Recorded change chain, selected revision, and inherited conflict records.

Autosave persists source but does not create a recorded change. Record change requires supported, analyzable edits; existing structured conflicts do not prevent unrelated valid edits from being recorded.

Before switching historical revisions or replaying, require pending edits to be recorded or explicitly set aside. Do not silently overwrite an unrecorded buffer. Shared drafts are immutable published snapshots; later local edits require sharing a new snapshot.

## Delivery and draft sharing

Share draft publishes a recorded snapshot for read-only inspection by another workspace. It may contain conflicts. It does not move the accepted head or replace another workspace's buffer.

Delivery:
1. Capture the expected workspace revision and accepted Git head.
2. Ensure there are no unrecorded edits to accidentally omit.
3. Rebase the recorded draft chain onto the current accepted state through change-engine.
4. Apply source edits and run official analysis on proposed snapshots.
5. If conflicts exist, persist the updated draft chain and return needs-resolution. The accepted head stays unchanged.
6. If there are validation errors or unsupported edits, return a distinct blocked result with preserved work.
7. If the result is conflict-free and valid, create a new accepted Git commit whose parent is the current accepted head.
8. Advance that head only with an expected-old-value check. A racing delivery triggers recomputation or a stale-head response, never an overwrite.

Warnings alone do not block acceptance. Parse errors, semantic errors, unknown/incomplete validation, conflicts, and unsupported edits do.

Alice sees that a newer accepted revision exists. Her workspace changes only through an explicit Update action, which preserves or rebases her own recorded work.

## Git and persistence contract

The demo repository is separate from the application source and both reference projects. It stores accepted source plus stable identity metadata. Shared draft snapshots include source, structured conflicts, and history metadata under separate draft references.

Accepted history is append-only during collaboration. The explicit user-confirmed Reset discards active demo state and starts a fresh reference history. Rewritten draft revisions remain recoverable until Reset. No operation runs an interactive Git rebase or relies on textual conflict markers as the collaboration model.

Autosaved invalid buffers may live in backend-managed workspace storage rather than accepted Git history. Persist them before acknowledging a successful save.

Use transactional workspace revision replacement and atomic Git ref updates. On restart, recover to a complete prior or new operation, never a partially rewritten change chain. Choose the minimal concrete storage and crash-recovery mechanism during planning; tests must demonstrate the behavior.

Do not add a destructive reset to task clear. Cleanup tests operate only on test-owned temporary directories.

## Provider API behavior

Define versioned JSON request/response schemas referencing model-contracts. Exact route spelling is an implementation detail; these operations are mandatory:

| Operation | Required behavior |
|---|---|
| Open/create workspace | Return independent identity, base, buffers, and current state |
| Save text | Check expected versions, persist exact text, schedule analysis |
| Get analysis/state | Return analyzed revisions and stale/pending indicators |
| Edit attribute/add attribute | Check versions, patch through adapter, reanalyze |
| Record change | Capture supported delta and description |
| Get history | Distinguish change IDs, revisions, conflicts, and accepted commits |
| Share/get shared draft | Publish or inspect an immutable snapshot |
| Deliver/update | Rebase against latest accepted state, never overwrite local work |
| Resolve conflict | Resolve origin and propagate descendants in one workspace transaction |

Responses distinguish success, needs-resolution, stale revision, validation failure, unsupported edit, and internal failure.

Discard stale asynchronous analysis results. A newer text buffer always wins over an old response.

## Local safety and concurrency

Bind to loopback only. No authentication is required for the trusted-local demo, but local HTTP endpoints must not accept arbitrary cross-origin mutations. Validate request origin/host and use same-origin browser requests.

Repository and document paths are backend-selected and confined to the configured project directory. Reject traversal, arbitrary shell commands, and client-selected filesystem destinations. Use argument-safe Git invocation.

Serialize mutations per workspace and accepted-ref updates. Serialize initial parser work separately. Slow analysis in one workspace must not contaminate another workspace's model.

No browser instance shares a mutable working directory merely because both publish to the same Git repository.

## Acceptance and tests

Run task test:workspace-service and task test:e2e.

Use real Git repositories in temporary directories:
- Two independent workspaces from R0, then conflicting delivery after A1.
- Failed delivery leaves the accepted head unchanged.
- Sharing a conflicted draft is inspectable and does not replace the viewer's workspace.
- Origin resolution atomically updates all local descendants and retains old revisions.
- Accepted R2 is a descendant of R1, not a rewrite.
- Two concurrent deliveries cannot lose either person's work.
- Stale edits and stale analysis cannot overwrite current state.
- Restart preserves saved invalid text, recorded history, shared drafts, and conflicts.
- Failures during propagation/publication do not expose partial history.
- Traversal and cross-origin mutation attempts are rejected.
