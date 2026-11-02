# SysML collaboration PoC

A disposable experiment, not a production application. It exists only to answer:

> Can a jj-like rebase flow make collaboration in a SysML v2 editor easy to understand and intuitive?

Alice and Bob work independently on one connected reference model. The demo must make conflicts, continued work, and preservation of later changes understandable, not merely produce the correct final values. Automated tests verify behavior; user observation is still needed to judge the UX.

## Run

```powershell
cd C:\SANDBOX\REPOS\blocks_gpui\sysml-collab
task run
```

Open **http://127.0.0.1:4317**. Stop an older running server first so the new version can use that port.

Dependencies are already prepared on this machine. On a new checkout, run `task setup` first. Prerequisites are Node 24, a full JDK 21 selected by JAVA_HOME, Maven 3.9, Git, Rust with wasm32-unknown-unknown, wasm-pack, and Task.

## Seven header shortcuts

Click the numbered buttons in order:

1. **Reset** restores the reference and fresh Alice/Bob environments after confirmation.
2. **Alice edits** sets Alice's local flowRate to 12.
3. **Bob edits** independently sets Bob's local flowRate to 15.
4. **Alice publishes** records and integrates Alice's change automatically.
5. **Bob publishes** rebases Bob's change and exposes the base 10 / Alice 12 / Bob 15 conflict.
6. **Bob adds alert** records alertEnabled = true as later work while flowRate remains conflicted.
7. **Resolve** applies 14, preserves the alert, and completes integration for both people.

The buttons perform real model/change operations. There is no separate Record, Deliver or Update click in this scripted sequence. Actors switch automatically, completed steps are subdued, and the next step is highlighted. A visible result explains that the alert survived.

**Viewing** switches the optional actor perspective. Either Alice or Bob may run Resolve. Manual edits are preserved, but diverging from the fixed sequence requires Reset before replaying the shortcuts.

Both environments are created together from the same base. Switching saves pending text, including an invalid draft, without requiring a recorded change. A failed/stale save blocks the switch and keeps your buffer.

For parallel manual work, open two tabs and use Viewing to select Alice in one and Bob in the other. Reset affects both; other tabs move to the fresh generation without reviving old work.

**Text** toggles the syntax-colored editor. Optional manual **Changes and history** controls are in the overflow menu. Other details stay on demand.

## Optional manual conflict workflow

Use Viewing to choose the actor and the overflow menu to open Changes. This is optional; the seven shortcuts handle these operations automatically.

1. Alice double-clicks the pump's flowRate row and changes `10` to `12`. In Changes, Record change and Deliver.
2. Switch to Bob. Change the same value to `15`, Record change and Deliver. Bob sees a conflict because his environment still started from `10`.
3. While flowRate is conflicted, Bob right-clicks the pump, adds Boolean `alertEnabled = true`, and records that later change.
4. Click the conflicted flowRate row, enter `14`, and explicitly Resolve. The alert remains.
5. Bob Delivers. Switch to Alice and Update to receive the result.
6. Press Reset whenever you want to repeat from the original model.

The reference is [fixtures/water-tank.sysml](fixtures/water-tank.sysml). It contains three parts, `controller : Controller`, `pump : Pump`, and `tank : Tank`, with six ports and three connections forming a loop. The controller commands the pump, the pump supplies water to the tank, and the tank reports its level to the controller. `pump.flowRate = 10` is a local editable attribute for the conflict scenario. There is no specialization or simulation behavior.

After restarting with this version, existing saved environments keep their previous model. Use Reset to load the new reference. Reset discards both actors' saved work and history.

See [the walkthrough](docs/demo.md).

## Commands

| Command | Purpose |
|---|---|
| `task run` | Build and run the one PoC |
| `task setup` | Prepare pinned dependencies and browser tests |
| `task build` | Build Java, TypeScript and real Rust WASM |
| `task test` | All automated checks with isolated test data |
| `task test:e2e` | Build and run the real browser scenarios against a fresh isolated backend |
| `task lint` | Script, Java, TypeScript and Rust formatting checks |
| `task clear` | Remove build outputs only, not demo data |

`task run:example` remains an alias for `task run`; there is no second reference-model choice.

## Local data

The default PoC data is `.local/poc-demo`. Reset clears the application's database state and active Git history there. It never touches this source repository, the reference repositories, parser dependencies, or other data directories. Unreachable Git objects may remain until Git garbage collection, but are not active demo history.

`.local/pilot` contains the pinned official Java parser and matching libraries. `.local/runtime` contains immutable runtime artifacts so a running Windows process does not lock new build outputs.

The service binds to loopback and rejects foreign-origin mutations. One backend owns a data directory; multiple browser environments share that backend. Tests start their own data directories and available ports rather than touching your running demo.

## Scope

The official parser supplies semantic values, ports, specialization and connections. Rust WASM projects the fixed diagram. Java owns changes, structured conflicts, replay, validation and publication. Git stores accepted/shared history and SQLite stores workspaces and recovery records.

Scalar edits/additions are supported. Topology editing, general structural merges, simulation and public hosting are not. Inherited rows are read-only projections; inspect the owning definition before deliberately changing it. The official integer converter has a 32-bit token limit; supported real literals keep authored precision.

## Documents

- [Demo walkthrough](docs/demo.md)
- [Verification](docs/verification.md)
- [PoC reset/actor contract](contracts/poc-demo.md)
- [Build details](docs/build.md)
- [Dependencies and licensing](docs/dependencies.md)
- [Architecture specification](SPEC.md)
