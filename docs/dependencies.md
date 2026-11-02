# Dependencies and provenance

The app source does not contain the official runtime JAR or generated WASM binaries. Setup obtains the official distribution; the project builds its own WASM module locally.

## Direct dependency pins

| Component | Version | Upstream license/reference |
|---|---|---|
| Official SysML Pilot distribution | 2026-09.1 / kernel 0.63.1 | EPL-2.0 top-level; see mixed-dependency warning below |
| Standard model libraries | Same distribution, 94 sources | EPL-2.0 per official release licensing statement |
| Gson | 2.15.0 | Apache-2.0, https://github.com/google/gson |
| JUnit Jupiter | 6.1.3 | EPL-2.0, https://github.com/junit-team/junit-framework |
| SQLite JDBC | 3.54.1.0 | Driver licensing: https://github.com/xerial/sqlite-jdbc; SQLite core is public domain |
| CodeMirror | codemirror 6.0.2, state 6.7.6, view 6.43.15, lint 6.9.7 | MIT, https://codemirror.net/ |
| CodeMirror language infrastructure | 6.13.1 | MIT, https://github.com/codemirror/language |
| Lezer highlighting | 1.2.5 | MIT, https://github.com/lezer-parser/highlight |
| TypeScript | 7.0.2 | Apache-2.0, https://github.com/microsoft/TypeScript |
| Vite | 8.3.5 | MIT, https://github.com/vitejs/vite |
| Vitest | 5.0.5 | MIT, https://github.com/vitest-dev/vitest |
| Playwright test | 1.64.1 | Apache-2.0, https://github.com/microsoft/playwright |
| axe-core Playwright integration | 4.14.0 | See package and axe-core notices, https://github.com/dequelabs/axe-core-npm |
| wasm-bindgen | 0.2.130 | MIT/Apache-2.0, https://github.com/wasm-bindgen/wasm-bindgen |
| serde | 1.0.229 | MIT/Apache-2.0, https://github.com/serde-rs/serde |
| serde_json | 1.0.154 | MIT/Apache-2.0, https://github.com/serde-rs/json |

Exact resolved frontend and Rust dependencies are recorded in web/package-lock.json and web/wasm/Cargo.lock. Java POMs pin direct dependencies and build plugins. tools/versions.json records the selected base toolchain; tools/pilot-lock.json owns official artifact and source-library checksums.

The CodeMirror state/view pins were aligned with the installed lint package after a real typecheck exposed incompatible duplicated editor types. No unverified type assertion was used to hide that mismatch.

## Official runtime

- Source commit: d81b2ad5016f2e2d3d4364b3d06d01d8bb12a896.
- Archive SHA256: 2ab938037b8d3a02a3518019b6e81b356a994f4a5214727bfedcfd64bad25415.
- JAR SHA256: 67c914092f4520ba98548b285c5a9a8bc08f92a0aeec7b763e12a574e91f9ff7.
- Official source: https://github.com/Systems-Modeling/SysML-v2-Pilot-Implementation/tree/d81b2ad5016f2e2d3d4364b3d06d01d8bb12a896
- Release: https://github.com/Systems-Modeling/SysML-v2-Pilot-Implementation/releases/tag/2026-09.1
- Model licensing: https://github.com/Systems-Modeling/SysML-v2-Release#licensing

All 97 installed distribution files are hashed in the lock. The matching standard-library sources were compared with the pinned upstream commit.

The official shaded JAR contains dependencies with their own licenses. Its top-level EPL license is not a statement that every bundled component is EPL. Runtime metadata includes an older Gson 2.8.6 copy; the app explicitly places Gson 2.15.0 first and tests the loaded class origin and real-parser compatibility.

Do not redistribute the shaded runtime as if its license review were complete. This local demo downloads it from the official project and preserves its notices. A full third-party redistribution/security review remains a separate requirement before shipping a hosted or packaged production product. No legal or full vulnerability-audit guarantee is claimed.
