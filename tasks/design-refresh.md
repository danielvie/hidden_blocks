# Approved diagram-first design implementation

The user approved docs/design/connected-block-design.excalidraw and requested implementation. No intermediate design approval is required.

## Scope
- Default to one minimal header and the diagram. Text is a toggleable, resizable source pane.
- Show names/types, useful attribute rows and values, inherited origins, ports, and real connections.
- Single-click selects; double-click opens a local editor; right-click exposes secondary actions.
- Highlight only conflicted rows. Alternatives and type definitions open on demand.
- Changes/history/delivery controls stay behind Changes, never a permanent third column.
- Introduce the validated connected Pump/VariableSpeedPump/Tank example without resetting existing data.
- Structural connection/type editing remains unsupported; preserve source and reject unsupported records.

## Contract-first work
- [x] Extend Java/JSON contracts with inherited provenance, type metadata, ports and connections, retaining legacy-load compatibility.
- [x] Project these records from the official linked semantic model, not text heuristics.
- [x] Update semantic comparison to avoid treating inherited effective values as new authored edits.
- [x] Rebuild the diagram-first browser interactions and real WASM projection.
- [x] Add task run:example using a separate persisted data directory.
- [x] Verify the connected two-person conflict flow, source synchronization, source/connection preservation, legacy models, crash recovery, and accessibility.
- [x] Document commands and limitations; inspect screenshots; leave existing demo data untouched.

Parser and web implementation can proceed in parallel after shared contract fields are frozen. Parent owns contracts, backend integration, root tooling/docs, and acceptance tests.

## Verification

- task test: 117 tests passed, including 12 real-browser checks on a fresh connected project.
- task lint: passed after Rust formatting.
- task run:example command smoke: passed with test-owned data/port; real connection, inheritance and new UI verified.
- Existing original demo data and the user's running port4317 instance were not stopped or reset.
- Runtime copies avoid Windows JAR-lock collisions with running older versions.
- Desktop, Text-mode, conflict, and mobile screenshots inspected.

Final code and documentation are ready for the user's review.
