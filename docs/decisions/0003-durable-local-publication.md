# Keep workspace transactions separate from Git publication

Status: Implemented.
Date: 2026-11-02.

Use embedded SQLite for complete workspace snapshots and immutable draft revisions, and Git for accepted/source history and shared drafts. Both remain inside one local Java deployment.

A database commit and a Git ref update are not one transaction. Before publishing, retain the candidate Git object and write a durable intent with the expected head, operation ID, and replacement workspace. Advance the ref using compare-and-swap, then finalize the workspace and operation result transactionally. Startup reconciles incomplete intents before accepting mutations.

This avoids a custom crash-safe file journal on Windows while preserving real Git history. Process-kill tests cover candidate, intent, ref-update, and finalization boundaries for both delivery and sharing. Accepted history is never rolled backward to hide a partially completed publication.
