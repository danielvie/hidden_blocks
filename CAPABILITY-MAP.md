# Capability map

The user approved these five module boundaries and then approved the written specifications on 2026-11-02. The implementation plan is a separate review gate.

| Module ID | Responsibility | Depends on | Specification |
|---|---|---|---|
| model-contracts | Model identities, revisions, edits, conflicts, diagnostics, source ranges | None | [Contract spec](SPEC-model-contracts.md) |
| sysml-adapter | Official analysis and precise source edits | model-contracts | [Parser spec](SPEC-sysml-adapter.md) |
| change-engine | Authoritative comparison, structured conflicts, resolution, descendant replay | model-contracts | [Change spec](SPEC-change-engine.md) |
| workspace-service | Workspace lifecycle, parser/engine orchestration, persistence, Git, HTTP | model-contracts, sysml-adapter, change-engine | [Workspace spec](SPEC-workspace-service.md) |
| web-editor | Text UI, fixed diagram, WASM projection/comparison, conflict controls | model-contracts, workspace-service API | [Editor spec](SPEC-web-editor.md) |

Dependencies are one-way. The parser does not invoke the change engine. The change engine consumes normalized models and produces edit/replay proposals without calling the parser or Git. The workspace service validates and persists those proposals. The browser does not depend on parser internals.

Build order:
1. Establish model-contracts and representative fixtures.
2. Prove the official parser round trip. Develop the change engine independently against those contracts.
3. Integrate both through workspace-service.
4. Connect web-editor and verify the complete two-person scenario.

Safe overlap:
- sysml-adapter and change-engine after shared contracts are stable.
- UI shell and WASM view logic against approved fixtures while backend work proceeds.
- Module-specific tests alongside their implementations.

Shared files, schemas, and fixture meaning have one coordinating owner. Contract changes require updating consumers and compatibility tests, not independent reinterpretation. This is a capability map, not a task plan.
