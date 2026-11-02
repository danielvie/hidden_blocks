# Render fixed blocks with native HTML controls

Status: Implemented refinement of the initial SVG proposal.
Date: 2026-11-02.

Render the Rust WASM projection as positioned HTML block cards inside the fixed diagram canvas. Native buttons, labels, inputs, and keyboard focus keep property editing and conflict resolution accessible without embedding HTML controls inside SVG.

The model/view boundary and fixed coordinates are unchanged. WASM still owns projection and advisory comparison, TypeScript owns rendering, and Java owns authoritative semantics/history. The seed model has no authored connections, so no edges are fabricated. General relationship visualization remains outside this demo's scalar-conflict scope.
