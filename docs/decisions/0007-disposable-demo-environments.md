# Make Alice, Bob and Reset the PoC entry point

Status: Approved and implemented.

The user clarified that this is a disposable demonstration, not a workspace-management product. There is one connected reference model and one shared Git repository. Alice and Bob are created together from the same base before either can deliver. Reopening an actor preserves its environment; it does not clone the latest accepted model each time.

The minimal header exposes Reset, Bob, Alice. Switching saves pending text but does not require a recorded change. Text and Changes remain on demand. The browser no longer asks users to create actors, choose a fixture, or distinguish run versus run:example.

Reset explicitly discards both actors' work, changes, conflicts, drafts and active accepted history, then restores the fixed reference and creates new workspace IDs. This is the deliberate exception to otherwise append-only accepted history. It affects only the selected demo repository/database, not source repositories, parser caches or other projects.

A generation token fences stale resets and browser responses. A durable SQLite reset intent lets the service finish a reset interrupted between Git ref replacement and database finalization. Old requests target retired IDs and cannot revive the previous demo. Unreachable Git objects may remain in the object database until Git garbage collection; they are no longer active demo history.

One command, task run, launches the connected PoC under .local/poc-demo. run:example is only a compatibility alias. Lower-level legacy fixtures/APIs remain for regression tests, not as additional demo choices.
