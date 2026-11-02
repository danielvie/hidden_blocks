# SysML collaboration

This glossary defines the collaboration terms used in the demo.

## Language

**Accepted system**:
The shared system version that has passed delivery checks. It contains no unresolved conflicts.
_Avoid_: Latest draft, everybody's workspace.

**Workspace**:
One person's independent working state and recorded changes, based on an accepted system version.
_Avoid_: Shared editor buffer.

**Draft**:
Work that has not been accepted into the shared system. It may contain unresolved conflicts or incomplete text.

**Recorded change**:
A named unit of supported authored edits relative to a parent change or accepted system version.
_Avoid_: Every keystroke.

**Change revision**:
One version of a recorded change. Resolving an earlier change can create new revisions of that change and its descendants without changing their logical identities.

**Share draft**:
Make a recorded draft visible for inspection without accepting it into the shared system.
_Avoid_: Deliver.

**Delivery**:
An attempt to integrate recorded work into the accepted system. An attempt can complete with conflicts that require resolution.
_Avoid_: Blind overwrite.

**Conflict**:
An unresolved disagreement about an authored property between changes that share a base. A conflict retains the alternatives rather than selecting an authoritative value.

**Resolution**:
An explicit choice of an alternative or a custom value that replaces a conflict.

**Propagation**:
Reapplying descendant changes after an ancestor changes, preserving independent edits and exposing new disagreements.

**Authored value**:
The expression written in the system text. It is distinct from a value inferred, inherited, or calculated from that text.

**Fixed diagram**:
A graphical view whose block positions are not editable. Its properties and conflict controls can still be edited.
