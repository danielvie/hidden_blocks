# Browser editor

Vanilla TypeScript, CodeMirror 6 and a Rust WASM module in a module worker. The Java service remains authoritative for parsing, validation and collaboration.

## Run

For the complete PoC, run `task run` from the repository root and open the reported URL. It starts the one connected reference with Alice and Bob ready to use. `task run:example` is the same demo, not another fixture. Restarting keeps work until you explicitly confirm Reset.

For frontend development, install with `npm --prefix web install`, then run:

```sh
npm --prefix web run build
npm --prefix web run dev
```

The build runs `wasm-pack build wasm --target web` before TypeScript and Vite. It requires Rust, the `wasm32-unknown-unknown` target and wasm-pack. Generated bindings and binaries are ignored, not hand-written replacements.

Start the real backend on port 4317. Vite proxies `/api` there and translates only its own exact loopback Origin to the backend origin. Foreign Origins are not rewritten. Port 5173 is strict to keep this mapping explicit. The backend can also serve `web/dist` on the same origin. In this Windows bash harness use `npm.cmd` if the shell's `npm` wrapper drops arguments.

## Checks

```sh
npm --prefix web run typecheck
npm --prefix web test
E2E_BASE_URL=http://127.0.0.1:<isolated-port> npm --prefix web run test:e2e
```

Browser tests build the frontend and require explicit `E2E_BASE_URL` pointing to a separate real service with disposable persisted data. Every test resets the entire demo, then edits and publishes real changes. Never use a personal workspace server. Port 4317 is refused. The supplied service must serve the rebuilt `web/dist` and use the connected reference model. The root `task test:e2e` runner starts a disposable service on a random port. Install a browser locally with `node web/node_modules/@playwright/test/cli.js install chromium` when needed.

## Collaboration walkthrough

The default view is diagram-only. Text opens a resizable full-source pane; closing it restores the canvas. Changes opens the temporary recording and history dialog. Diagnostics, recovery and keyboard help are in More options.

Click selects a block or feature. Double-click, Enter or F2 opens a local editor. Shift+F10 or right-click opens actions, including Add attribute. Escape or an outside click closes the local editor. Inherited features are read-only and offer Inspect definition instead of editing their base silently.

1. Open the app. Alice and Bob already have independent environments from the same accepted head. Alice opens by default. Use the header Bob and Alice buttons to switch this tab, or open `?actor=Bob` in another tab. New tabs default to Alice, not another tab's last selection. Each tab's `?workspace=` URL identifies its current environment.
2. Alice changes flowRate to 12, Records, then Delivers.
3. Bob changes flowRate to 15, Records, then Delivers. Bob sees explicit base, incoming and local alternatives.
4. Bob adds Boolean alertEnabled = true and Records the descendant change.
5. Select the earlier flow change to inspect its read-only historical source. Close the snapshot, then use Resolve flowRate in text or the diagram's Resolve action.
6. Enter custom expression 14 and explicitly Resolve. The status identifies rewritten and replayed logical changes. alertEnabled remains present.
7. Bob Delivers. Alice explicitly Updates to receive both values.

Switching actors saves pending text, even invalid source, without Recording. Saved drafts and recorded history remain in that actor's environment. Failed or stale saves block switching and retain the buffer for recovery. Two tabs using the same actor use the same environment; concurrent stale writes require explicit recovery.

Reset is the only destructive demo control. Its confirmation discards both actors' work, history, shared drafts and conflicts, restores the connected reference, and creates fresh environment IDs and one accepted root commit. Cancelling does nothing. Every tab keeps its selected actor when it detects the new generation, but retires old buffers and URLs. Opening a retired workspace URL defaults to the fresh Alice environment. Ordinary polling never replaces unsaved text in the current generation.

Share draft publishes an immutable read-only snapshot. Viewing it never replaces the workspace's source. Record unrelated dirty edits before inspecting history or resolving.

## Safety and component boundaries

- `demo.ts` owns the actor pair and checks the server generation before writes and switches, on focus/visibility, and while polling. Reset notifications use BroadcastChannel only to request a server check. Generation and session identity checks reject late responses; retired sessions cancel pending autosave timers and cannot repopulate recovery storage.
- `session.ts` serializes saves against the freshest confirmed versions. A save response never replaces newer typing. Mutations lock source editing and flush pending saves first.
- Failed saves stop autosave. Retry checks the server revision before resubmission. If another writer advanced the same workspace, copy the buffer and explicitly reload to reconcile.
- Unsaved recovery is stored in tab-scoped sessionStorage under `sysml-buffer:<workspaceId>`, never as a global actor selection. Recovery requires review and explicit Retry save. A new server generation clears only the `sysml-buffer:` prefix, including its generation marker, before opening the fresh pair.
- Conflicted source ranges are protected. Typing there stages a custom proposal in a local resolution editor. Choosing an alternative also stages it; only Resolve submits it. It does not silently resolve or change the live source. Other fields remain editable.
- `diagram.worker.ts` initializes the real wasm-bindgen module. `wasm/src/lib.rs` projects fixed cards and compares supported canonical scalars without floating-point conversion. It also exports the obstacle-aware router in `wasm/src/router.rs`, adapted from `rust_gpui_blocks_simple`. Routing runs in the same worker using measured block rectangles and exact port-terminal centers. Comparison is advisory and matches the backend's type-name and scalar-kind checks.
- The renderer uses DOM textContent, not HTML interpolation of authored model text. Coordinates never change for property edits, additions or resolutions. Scrolling and zoom affect only the viewport.

The canvas prefers immediate child usages of a root system. All definitions remain available for inspection. Older analyses without usage, inheritance or port metadata fall back to the definition view. Blocks follow the [simple GPUI block/routing contract](../docs/design/simple-blocks.md), with compact yellow bodies, paired input/output ports, and source colors. Structural wires connect only normalized connection endpoints and use the rendered port terminals; no simulation or inferred connections are shown. Wires avoid block rectangles, including when attributes expand. Trapped ports show endpoint crosses and a routing-failure count instead of lines through blocks. Fit includes the routed return paths. The diagram keeps scalar editing and definition inspection, not the reference prototype's topology-editing tools. WASM layout keys include the model scope, so inspecting another scope cannot consume this scope's fixed slots.

## Implementation references

- [Vite module workers](https://vite.dev/guide/features.html#web-workers)
- [CodeMirror layout measurement on source reveal](https://codemirror.net/docs/ref/#view.EditorView.requestMeasure)
- [Accessible menu keyboard pattern](https://www.w3.org/WAI/ARIA/apg/patterns/menu/)
- [Native modal dialogs](https://developer.mozilla.org/en-US/docs/Web/HTML/Element/dialog)
- [CodeMirror transaction filters](https://codemirror.net/docs/ref/#state.EditorState%5EtransactionFilter) and [decorations](https://codemirror.net/examples/decoration/)
- [wasm-bindgen web-target initialization](https://rustwasm.github.io/docs/wasm-bindgen/examples/without-a-bundler.html)
- [Playwright automatic test fixtures](https://playwright.dev/docs/test-fixtures#automatic-fixtures)
- [Page visibility events](https://developer.mozilla.org/en-US/docs/Web/API/Page_Visibility_API)
- [BroadcastChannel notifications](https://developer.mozilla.org/en-US/docs/Web/API/Broadcast_Channel_API)
