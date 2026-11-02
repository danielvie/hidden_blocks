# Seven-step header implementation

Approved design: docs/design/seven-step-header.excalidraw.
Isolated branch: poc/seven-step-header.
Base: 9812ca6, the last buildable commit before the parallel renderer/routing WIP checkpoint.

The main repository was checkpointed at 7c5014d at the user's explicit request. This worktree does not edit the other agent's renderer, routing, WASM, or model-view implementation.

## Scope

- [x] Implement real backend stages 2-7 using existing editing, recording, replay and publication paths.
- [x] Persist fixed scenario progress, reject out-of-order/stale requests, preserve manual work rather than silently overwriting it.
- [x] Implement numbered header shortcuts, current viewing context, and visible result feedback.
- [x] Keep optional free editing/history available through context menus rather than cluttering the header.
- [x] Test both Alice and Bob resolving the shared pending origin, later-alert preservation, refresh/reset and cross-tab progress.
- [x] Verify build/tests and screenshots in this worktree.
- [x] Coordinate integration with the parallel main renderer work; do not overwrite or auto-merge a busy checkout.

This is a fixed, repeatable experiment. It is not a general workflow engine and a guided run does not establish that free editing is intuitive.

## Verified integration

- Full main ancestry including1fef46a merged into this worktree, preserving the water fixture, compact block renderer and obstacle-aware routing.
- After integration:25 actual-browser scenarios passed,37 web unit/compiled-WASM tests passed,15 Rust tests passed,8 focused real-parser scenario/API tests passed, and lint passed.
- The full backend workspace suite also passed41 tests on the feature branch before renderer integration.
- The initial Viewing startup race was fixed; all existing manual-control browser cases were rerun and passed.
- Real integrated preview: docs/design/seven-step-header-implemented.png.
- The other agent approved integration after verifying main was clean. Main was fast-forwarded to tested a6ead41; both feature histories were preserved.

Implementation is committed in the isolated branch, integrated into main by coordinated fast-forward, and ready for the user's review.
