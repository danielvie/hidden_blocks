# Simple blocks and obstacle-aware routing

The visual reference is [rust_gpui_blocks_simple](../../../gpui-blocks/rust_gpui_blocks_simple/README.md), specifically its `block_element` renderer, browser `draw` function, and `diagram_router.rs`. This replaces the web editor's white feature cards and midpoint connection paths. The purpose is to keep attention on model values and their conflicts while making the three-part feedback loop readable.

[Browser preview](simple-blocks.png)

## Presentation

- Pale-yellow rectangular blocks, thin muted borders, centered name/type headers, and square terminals.
- Inputs on the left and outputs on the right, paired in compact rows. Long port labels retain their full name and type in the tooltip and definition inspector.
- Source-block colors match the prototype. Connected inputs retain their source's color, while unconnected inputs stay hollow. Selection does not erase the connection color.
- Blocks are 240 pixels wide rather than the prototype's 192, to retain the SysML attribute/value rows. Empty attribute compartments are omitted. Conflicted features remain visible.
- Default fixed slots place three blocks across. Property additions, conflict resolution, and feature expansion do not move those slots.

## Routing

`web/wasm/src/router.rs` adapts the reference's grid A* search, including bend and congestion costs. It is self-contained rather than importing files from the sibling prototype at build time.

The DOM renderer measures actual border rectangles and terminal centers in unscaled diagram coordinates. The worker routes on a 12-pixel grid with block clearance. Horizontal escape stubs and off-grid bridges preserve exact endpoints. Both stubs and complete path segments are checked against obstacles. Reverse connections can travel above, below, or around the outer blocks. White underlays distinguish crossings without inventing connections.

Routes are recalculated after block content changes. Zoom and scrolling change only the viewport. Fit includes the route bounds. Superseded worker replies cannot replace a newer diagram's wires.

Every requested connection has a route result. Failure is explicit, with endpoint crosses and a count; the renderer never falls back to drawing through a block. This remains a small-diagram prototype. A narrow passage can be unroutable on the grid even if a continuous geometric path exists.

## Preserved scope

The water-tank model, official parser, scalar editing, attribute additions, inherited-feature inspection, and Alice/Bob Record/Deliver/Update/Resolve workflow are unchanged. Block dragging, deletion, manual wiring, and port creation are not added.

## Checks

- Rust and compiled-WASM tests cover obstacles, reverse links, fractional coordinates, varying block heights, trapped ports, and route identity.
- Browser tests check real terminal alignment, source colors, whole-segment obstacle avoidance, expansion without slot movement, failure/recovery, keyboard editing, responsive layout, accessibility, and the existing collaboration scenario.

Geometry measurement follows [MDN's DOMRect API](https://developer.mozilla.org/en-US/docs/Web/API/Element/getBoundingClientRect). SVG path coordinates are diagram-local and transformed together with the blocks.
