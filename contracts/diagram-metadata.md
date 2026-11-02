# Diagram metadata extension

Approved by the connected-block design. These additive fields are emitted by the official parser projection; old stored analyses load with empty/null defaults.

- Attribute.declaredBy: qualified owning definition name for presentation.
- Attribute.definitionAttributeId: stable authored definition attribute ID for inspecting provenance and projecting conflicts.
- Attribute.inherited: true only for an effective, read-only row on a usage. Its ID and ownerId belong to the usage; its valueRange is null.
- ModelElement.typeName and definitionId: explicit user-defined type of a usage.
- ModelElement.superTypeIds: explicit direct user-defined bases of the definition or usage's type. Implicit library bases are not exposed.
- ModelElement.ports: owned/effective port rows with id, ownerId, name, typeName, conjugated, inherited, declaredBy.
- ModelElement.containerId: nearest authored containing part, enabling a default system view rather than showing every definition.
- Analysis.connections: actual linked port connections with stable IDs and source/target element/port IDs. Unnamed connections have a null name; the renderer must not invent names.

The normal connected-example diagram shows the child usages of system. Definitions remain available for on-demand inspection. Legacy definition-only models still display.

Inherited values are not standalone authored edits. A change to an inherited effective row must be explained by a supported edit to its owning definition. Ports, specialization, containment and connections are read-only topology for this iteration.

Workspace analysisVersion 2 triggers a one-time reanalysis of old stored workspace source without changing source, change history, accepted Git refs or the optimistic workspace revision. Existing data is never reset.
