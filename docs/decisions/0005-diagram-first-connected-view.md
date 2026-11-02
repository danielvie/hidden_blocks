# Use a diagram-first view with on-demand editing

Status: Accepted and implemented.

The user rejected the persistent source/history/property layout as too crowded and approved the connected-block design. The default is now one minimal header and a fixed diagram. Text is toggleable; editing, definitions, and Changes open only when requested.

Names, types, useful values, inherited origins, and ports remain on blocks because these are needed to understand the system and locate conflicts. A conflict highlights its feature row rather than the whole block. Ports and specialization come from the official linked model, not inferred source patterns or invented edges.

The connected example starts in a separate persisted data directory. Existing projects and URLs are not reset. Old stored analyses gain metadata through a one-time reanalysis without source or Git-history changes.

Inherited effective rows are read-only projections. Their source definition remains separately inspectable/editable; the change engine records the authored definition edit once instead of generating assignments to every inheriting usage.
