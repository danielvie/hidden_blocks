# Disposable PoC demo controls

Approved behavior: one connected reference model and two persistent independent environments. No actor creation or fixture selection is required by the browser.

## HTTP additions

- GET /api/demo -> {generation:string, alice:WorkspaceState, bob:WorkspaceState}. First use creates BOTH from the same initial accepted head. Repeated calls keep work; they do not recreate actors after someone delivers.
- POST /api/demo/reset {expectedGeneration:string,operationId:string} -> same DemoState shape. Explicit destructive operation, scoped to the running demo data directory. Clears all workspace/history/shared-draft/publication state, restores the backend's fixed reference fixture and fresh accepted Git history, and creates fresh Alice/Bob IDs from that one base.
- GET /api/project gains demo:null|{generation,aliceWorkspaceId,bobWorkspaceId} for tab invalidation.

Reset retries using the same operationId return the same generation. A stale generation is rejected with HTTP409, not used to destroy newer work. Reset has a small durable marker so an interrupted reset finishes before serving the demo again. A reset retires the old workspace IDs; late old saves/operations cannot overwrite the fresh generation.

## Browser

Header buttons Reset, Bob, Alice. Text/Changes remain for actual editing and delivery. Alice opens by default. Bob/Alice switch the current tab without losing saved drafts or recorded changes; pending valid or invalid text must save before switching. Failed/stale saves block switching and retain the buffer. Same actor in another tab is the same environment; Alice and Bob in separate tabs work independently against one shared repo.

Reset confirmation explains that BOTH people's work and history will be discarded. Capture expectedGeneration at confirmation. Old in-flight browser responses and sessionStorage buffers must not revive reset data. Project generation changes are detected when the tab regains focus, before actor switching/mutation, and by normal polling; reopen that tab's actor from the fresh pair without changing another tab's actor. A reset-specific BroadcastChannel may notify same-origin tabs promptly, but the server generation remains authoritative.

Remove the workspace chooser and manual create form from the normal UI. Generic workspace APIs may remain internal for lower-level tests. Application launch always selects the one connected reference; task run:example becomes an alias, not a second model choice.
