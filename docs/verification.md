# Current PoC verification

Date: 2026-11-02. Scope: one connected reference, fixed Alice/Bob environments and explicit disposable Reset, with the diagram-first conflict workflow retained.

## Verified behavior

- First use creates Alice and Bob together from one accepted reference. Opening Bob after Alice delivers keeps Bob's original base instead of cloning Alice's newer result.
- Header Reset/Bob/Alice replaces manual workspace creation. Switching flushes pending valid or invalid text without recording it. Failed/stale saves block switching and retain the buffer.
- Two tabs can use different actors in parallel against the same Git repo.
- Reset clears both actors' work, recorded revisions, conflicts, shared drafts, publication intents and operation results. Active Git history returns to one parentless reference commit; both actors get new IDs from that base.
- Reset is generation-checked and idempotent. Repeating the same operation does not erase edits made afterward. A stale different reset is rejected.
- Retired workspace IDs cannot save, deliver or reopen discarded work. Old browser save/mutation/project/actor-open responses are fenced by generation/session identity.
- Three tests actually halt a Java process during reset-intent, reset-git and reset-finalized stages. Recovery, two restarts, old-generation same-operation retries, exact database/history cleanup, and sibling-file preservation pass.
- Destructive reset requests still require the same-origin/Host/JSON boundary and current generation.
- The source/reference repositories and dependencies are outside the reset scope. Unreachable Git objects may remain until garbage collection; active demo history is fresh.

## Final commands

| Command | Result |
|---|---|
| task test | Passed, exit 0, including all 15 browser scenarios on a fresh isolated backend |
| task lint | Passed, exit 0 |
| task run smoke | Passed: actual command built/served the connected Alice/Bob pair, the three header controls and a successful Reset using test-owned data/port |
| git diff --check | Passed |

## Test counts

| Check | Observed result |
|---|---|
| Backend Maven reactor verify | 81 backend tests passed: parser 28, engine 20, workspace/recovery 33 |
| Model contracts | 7 passed |
| Browser unit tests | 26 TypeScript/compiled-WASM tests passed |
| Native Rust | 3 passed |
| Isolated Chromium suite | 15 passed |
| Node tool/runtime safety | 7 passed |
| task lint | Passed |

The final root test run passed all 139 tests with no failures or skips. The standalone official-runtime checksum and real-parser/library/linking probes also passed independently.

The production/browser build and typecheck pass. Desktop/mobile screenshots were inspected at 320, 768, 1024 and 1440 pixels. Active actor buttons are visibly highlighted and accessible through aria-pressed. Responsive and Axe checks pass for the tested views; this is not formal accessibility certification.

## Integration logs

- .local/poc-backend-all.log: real parser, merge, collaboration, persistence, local HTTP and actual process-crash tests.
- .local/poc-demo-test.log and .local/poc-boundary-test.log: pairing/reset/idempotency/boundary checks.
- .local/poc-all-tests.log: successful final root-command verification run, including a test-owned backend at port 47048.
- .local/poc-lint.log: successful lint run.
- .local/poc-run-smoke-*/server.log: successful actual task run startup/reset command smoke with isolated data.

The actual browser suite also ran on a parent-created isolated server at an available port. It was allowed to reset only test-owned data. The user's running port 4317 and normal demo data were not touched.

## Syntax-coloring increment

The text editor now uses a small display-only CodeMirror stream tokenizer for SysML keywords, names/types, numbers/Booleans, strings and comments. It does not replace official parsing or decide semantic validity. Conflict backgrounds/underlines and parser diagnostics remain independent decorations.

Focused verification after this change: 31 TypeScript/compiled-WASM tests, 3 Rust tests, and all 16 real-browser tests passed. The added browser check verifies actual rendered colors and unchanged saved source. Existing conflict, autosave, actor-switch, reset and recovery browser cases still pass. Build/typecheck passed as part of the isolated browser command. Lint also passed. Logs: .local/syntax-web-tests.log, .local/syntax-browser-tests.log, and .local/syntax-lint.log.

## Existing guarantees retained

Both text-first and diagram-first conflict scenarios still preserve the independent alert after resolving the earlier flowRate conflict. Ports, specialization and inherited values remain visible and intact. Derived/inherited rows stay read-only projections with explicit owning-definition inspection.

Existing tests cover eight actual publication crashes, concurrent delivery, stale writes, source mapping/Unicode/CRLF, precision, immutable sharing, source preservation, browser recovery and real WASM load/failure.

## Limits

- This is a trusted-local disposable PoC, not a public authenticated service.
- Scalar edits/additions are supported. Free topology editing and structural merges remain unsupported.
- The official integer token converter has a 32-bit limit; supported real values retain authored precision.
- Reset discards active application history/state, not arbitrary filesystem directories or Git object storage through destructive recursive deletion.
- Full third-party redistribution/security review of the official shaded runtime remains separate.

Run task run and use the header. The walkthrough is in docs/demo.md.
