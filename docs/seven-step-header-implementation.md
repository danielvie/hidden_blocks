# Seven-step header implementation

Implemented in the user-requested isolated worktree on branch poc/seven-step-header. Main was checkpointed at 7c5014d before work began. The branch started from last buildable 9812ca6, then merged full main ancestry including 1fef46a so the other agent's water model, compact blocks and measured obstacle-aware routing are preserved.

## Run the isolated implementation

```powershell
cd C:\SANDBOX\REPOS\blocks_gpui\.worktrees\sysml-seven-step-header
$env:SYSML_PORT = '4318'
task run
```

Open http://127.0.0.1:4318. This worktree has its own demo data. It does not change the normal main checkout's running server or workspace database.

## Behavior

The seven buttons run the existing parser-backed edit, record, rebase, resolution, delivery and update operations. Stages2/3 are independent local edits, stage5 ends with the actual10/12/15 conflict, stage6 records a separate alert addition, and stage7 rewrites the origin, replays the child, publishes the result and refreshes both actors. The current viewer may be Alice or Bob; the resolution author is recorded accordingly.

Progress is stored in the existing demo metadata, shared across tabs and restored after refresh. Only the next step runs. Completed retries are harmless. Manual divergence and partial failures preserve work and require Reset rather than silently rewriting edits or claiming completion.

The next action is highlighted. A contextual result remains visible after each shortcut; final feedback explicitly says the later alert was preserved. Optional manual actor selection is under Viewing, and manual history/record/delivery controls are under the overflow menu.

The work is still a disposable fixed experiment, not a general scenario framework or proof that unguided editing is intuitive.

## Verification

- Backend workspace-service suite:41 tests passed before renderer integration, including8 new scenario/API tests.
- After merging main: focused real-parser scenario/API suite8 passed, including both resolvers, persistence, stale/manual/out-of-order handling, failure preservation and HTTP shared state.
- After merging main:37 web unit/compiled-WASM tests and15 Rust tests passed.
- Integrated actual-browser suite:25 passed against the real water-model backend and real WASM, including21 existing renderer/workflow scenarios and4 new shortcut scenarios.
- task lint passed.
- Desktop, step5 conflict, step7 preservation and320-pixel screenshots inspected.

A startup race in the optional Viewing menu was found and fixed. It now stays disabled until the current projection is mounted, so opening it cannot be canceled by the initial render. Initial failures were not waived; the whole25-test browser suite was rerun successfully.

Logs are local/ignored under .local/header-*. The application server used for review is test-owned and isolated. No user server/data were reset.

## Integration surface

The renderer, routing worker/WASM, types and model-view files are unchanged by the header feature. Main.ts preserves the parallel geometry => projector.route(geometry) callback and route-generation behavior. The new appearance lives in scenario-header.css rather than rewriting the renderer's global stylesheet.

After the other agent confirmed main was clean and approved integration, main was fast-forwarded to the tested feature branch at a6ead41. Both feature histories are preserved. The worktree remains available for independent review; the normal main checkout now includes the seven shortcuts.
