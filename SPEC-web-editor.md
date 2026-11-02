# Specification: web-editor

Status: Approved in conversation on 2026-11-02.
Module ID: web-editor.
Depends on: model-contracts and workspace-service API.
Parent: [SPEC.md](SPEC.md).

## Objective and ownership

Provide a web UI for editing SysML text and fixed graphical properties, inspecting history, sharing drafts, and resolving conflicts.

Own web/, including its Rust WASM crate. Do not embed the official Java parser, own repository writes, or treat browser comparison as authoritative.

## Layout

Use `gpui-blocks/rust_gpui_blocks_simple` for block appearance and obstacle-aware routing, without embedding GPUI. Blocks are compact pale-yellow rectangles with centered headers, paired left/right square ports, and source-colored wires. Retain editable SysML property rows and explicit feature conflicts. See [the block/routing contract](docs/design/simple-blocks.md).

- One slim header with a human-readable model name, Text toggle, Reset, Bob, Alice, Changes, and overflow actions. No workspace creation dialog or manual fixture selection. Alice is ready by default; the active actor uses aria-pressed.
- Diagram only by default. Text reveals a resizable source pane; closing it restores the canvas.
- Contextual editors open on double-click. Single-click selects; right-click or Shift+F10 opens secondary actions.
- Record, Share, Deliver, Update, and history live inside a temporary Changes dialog.
- No permanent inspector, collaboration sidebar, technical footer, revision IDs, or healthy-status banner.
- Blocks retain useful attributes/values, port terminals, inheritance labels, and a compact specialization cue. Conflicted rows always remain visible when longer feature lists collapse.

Route orthogonal wires around the measured block rectangles with the prototype's 12-pixel A* grid and clearance. Reroute after property content changes, without moving block coordinates. Include return paths in Fit. Unroutable connections show endpoint markers and a failure count, never a fallback line through a block.

Keep block coordinates fixed across edits, resolution, refresh, and actor windows. Fit-to-view and viewport zoom/pan may change the view, not the model positions. Do not expose drag handles or persist position edits.

Adding an attribute must not move blocks. Use the fixed block's property area or selected-block details when content exceeds its available space.

## Editing

Text editor:
- Editable source with diagnostics and source-range highlighting.
- Autosave status distinguishes unsaved, saving, saved, and failed.
- Invalid text remains visible and survives refresh.
- Conflicted ranges have labeled decorations with access to base and alternatives.
- Editing a conflicted range stages a resolution proposal; it does not resolve silently.

Diagram:
- Show names, supported authored values, and fixed relationships.
- Edit a supported literal inline or in selected-block properties.
- Add a supported scalar attribute to an existing block.
- Show read-only reasons for unsupported or derived values.
- Send expected workspace/document versions with every edit.
- Disable graphical mutations while text is invalid or analysis is outdated.

Selecting a property links its text range and diagram representation when mapping is current.

## Conflict interaction

Display explicit labels, not color alone:
- Base value.
- Incoming accepted value and author/revision.
- Local value and author/revision.
- Unresolved status.
- Use incoming, Use local, and Custom value resolution actions.

Provisional source values are visibly provisional. A conflict badge appears on the property and its containing block; the entire block is not locked.

An inherited conflict links to its origin change. Resolving it there shows which descendants were replayed and any remaining/new conflicts.

Keep unrelated fields editable. Do not claim that candidate validation proves the unresolved model valid.

The history view distinguishes stable logical changes from their rewritten revisions without requiring users to type Git or jj commands.

## WASM responsibility

Implement real Rust-to-WASM functions for:
- Projecting normalized models and fixed coordinates into diagram view records.
- Comparing supported model literals for advisory change highlighting.

WASM runs in a browser worker so computation does not block editor interaction. It receives application contracts, not Java objects or a second SysML syntax parser.

The positioned-HTML renderer remains in TypeScript. Native HTML controls provide keyboard/property editing within the fixed canvas; this refines the initial SVG implementation proposal without changing the WASM or model boundary. The backend repeats authoritative comparison independently. Shared fixtures must catch Java/Rust differences in numeric precision and equality.

A missing or failed WASM module produces a visible initialization error. Do not quietly replace all required WASM behavior with a stub and claim the requirement is met.

## Instance behavior and accessibility

Each tab selects the fixed Alice or Bob environment. They have distinct IDs even in the same browser profile; switching saves pending text without requiring a recorded change. Reset requires confirmation, replaces both environments, clears only application recovery buffers, and fences old in-flight sessions. Tabs detect the server generation before mutations, on focus, and through polling/reset notifications.

A notification that accepted history advanced does not overwrite text. Updating is explicit.

All editing and resolution actions must be keyboard-accessible with labeled controls, visible focus, and readable status announcements. Conflicts and validation states must not rely only on color.

A provisional comparison, pending server operation, or failure must be visible. Do not show a delivered state until the backend confirms accepted publication.

## Acceptance and tests

Run task test:web-editor and task test:e2e.

Verify:
- Real WASM loads in a browser worker and projects the pump fixture.
- WASM advisory comparison matches shared backend fixtures.
- Text edits update the diagram and diagram edits update the source after analysis.
- Out-of-order responses do not restore older values.
- Invalid text marks the last valid diagram outdated and disables graphical mutations.
- Conflict alternatives and custom resolution are accessible by keyboard.
- Adding alertEnabled remains possible while flowRate is conflicted.
- Selecting and resolving B1 visibly preserves B2.
- Fixed positions are unchanged by values, additions, or conflicts.
- Independent browser contexts complete the acceptance scenario.
- Both text-first and diagram-first variants work.
- Refresh restores the correct workspace, not another tab's actor.
