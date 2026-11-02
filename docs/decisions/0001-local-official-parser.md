# Run the official SysML parser locally

Status: Accepted in conversation.
Date: 2026-11-02.

Use the official SysML v2 Pilot Implementation in a local backend rather than attempting to compile it to browser WebAssembly. We need its semantic linking, validation, standard-library support, and source mappings; a syntax-only WASM parser would not supply the same behavior.

The Pilot depends on JVM-oriented Xtext/EMF services and has no verified browser/WASM integration for this project. A port would distract from demonstrating conflict resolution. WASM remains in the browser for diagram projection and advisory model comparison.

One Java backend is the initial deployment. Exact dependency pins and standalone packaging must pass the feasibility gate. No successful build is implied by this decision.
