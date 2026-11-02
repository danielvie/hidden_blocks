# Disposable PoC controls

User-approved change. Replace the manual workspace/model-selection flow with one connected reference and ready-made Alice/Bob environments.

- [x] Persist a single demo generation and create Alice/Bob from one base on first use.
- [x] Implement an explicit, generation-checked reset that clears demo state/history and restores the reference, safely completing interrupted resets.
- [x] Put Reset, Bob, Alice in the minimal header. Switching saves pending text without requiring a recorded change.
- [x] Prevent late requests/responses and other tabs from reviving pre-reset work.
- [x] Make task run use the one connected PoC by default; keep run:example only as a compatibility alias.
- [x] Verify switching, two-tab collaboration, conflicts, reset/cancel/retry/restart, and retained data isolation.
- [x] Run all tests/lint, inspect the UI, update docs and commit.

Contracts: ../contracts/poc-demo.md. Reset is intentionally destructive ONLY within the application's selected demo data, never the source/reference repos or tool caches. No live user demo is reset during implementation tests.

## Evidence

- task test: 139 tests passed, including 15 actual browser scenarios.
- task lint: passed.
- Actual task run smoke: connected reference, automatic Alice/Bob pair, header controls and Reset all passed on an isolated data directory/port.
- Three actual reset JVM-halt/restart tests pass, along with the eight existing publication crash tests.
- Desktop and 320/768/1024/1440 screenshots inspected; active actor highlighted and controls unclipped.
- Test-owned review server was stopped. The user's running 4317 server and normal data were untouched.

Implementation is ready for the user's final check. Restart the app with task run, then use Reset, Bob and Alice.
