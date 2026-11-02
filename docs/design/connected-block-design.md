# Connected blocks and feature-level conflicts

Status: Approved and implemented. The connected fixture is opt-in through task run:example and never resets the existing demo.

This refines the block contents shared by the interaction proposals. The implementation uses contextual popovers, a Text toggle, and temporary Changes/definition dialogs, with a diagram-first default.

## Proposed block contents

- Instance name and type in the header.
- A compact specialization cue, such as :> Pump.
- Attribute names, types, and values.
- Ports at the connection endpoints.
- Inherited features labeled with the definition they come from.
- A small conflict count and highlight on the affected feature row only. The provisional value is explicitly marked Conflict.

Single-click selects. Double-click edits. Clicking a conflict opens its alternatives. The Text toggle opens the full SysML source beside the diagram. Detailed definitions, revision history, and editing controls stay on demand.

For longer feature lists, collapse nonessential rows rather than growing an endless block. Never hide a conflicted feature.

## Illustrative model

The companion [SysML file](connected-pump-example.sysml) defines:
- Pump, with a default flowRate and an outlet.
- VariableSpeedPump, specializing Pump and adding speed.
- Tank, with capacity and a conjugated inlet.
- A system containing pump and tank, connected at their ports.

The pump instance explicitly redefines flowRate. Alice and Bob can therefore disagree on this pump's value without silently changing every instance of its type. speed and outlet remain inherited. Inspect their owning definitions before editing inherited values; do not silently modify a base type.

The hollow-triangle relationship is between type definitions. It is separate from the solid port connection between instances. The connection is structural; the design does not imply a simulation engine.

## Parser check

The exact companion source was parsed, linked, and validated using the pinned official Pilot 2026-09.1 and its matching libraries. Result: zero errors and zero diagnostics.

The base flowRate uses a default value so that the instance can legally redefine it. A fixed binding value on the base would reject that override.

The official adapter now projects the connected example's real endpoints, specialization, and inherited features. The change engine distinguishes inherited effective values from owning-definition edits. Structural connection/type editing remains out of scope.

## Artifacts

- [Editable Excalidraw](connected-block-design.excalidraw)
- [Preview](connected-block-design.png)
- [Earlier interaction options](diagram-first-proposals.excalidraw)
