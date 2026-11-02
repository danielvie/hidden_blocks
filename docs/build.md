# Local build and runtime

## Tool selection

Use Node 24, a full JDK 21 selected by JAVA_HOME, Maven 3.9, Git, Rust with wasm32-unknown-unknown, wasm-pack, and Task.

On this machine JAVA_HOME selects JDK 21.0.6, although the shell javac previously selected JDK 17. The task runner prepends JAVA_HOME/bin only for child processes and directly launches Maven Classworlds on Windows. It never changes global PATH.

Nonempty MAVEN_OPTS, MAVEN_ARGS, MAVEN_DEBUG_OPTS, and .mvn/jvm.config are explicitly rejected on Windows rather than silently ignored. If those custom settings are needed, extend the project launcher deliberately or invoke standard Maven with the required project properties.

The runner invokes npm's JavaScript CLI through Node. This avoids the local shell wrapper that dropped --prefix arguments.

## Commands

```sh
task
task setup CHECK_ONLY=1
task setup
task build
task run
task test
task lint
```

- setup checks tools, downloads/verifies the official parser distribution, registers its unchanged JAR under application-local Maven coordinates, runs npm ci, and installs Chromium for tests.
- build compiles/packages Java modules and builds the TypeScript/WASM application. It does not claim that tests ran.
- run builds, then starts the one connected PoC under .local/poc-demo. Alice and Bob are ready-made environments. run:example is a compatibility alias, not another model choice. Default URL is http://127.0.0.1:4317; stop an older instance before binding that port.
- test runs Node safety checks, independent parser verification, Java suites, compiled-WASM and browser-state tests, native Rust tests, and isolated Chromium tests.
- lint checks scripts, Java compilation, TypeScript, and Rust formatting.

The official archive is about 126 MB. Download/extraction stays in .local/pilot. Maven and browser tooling also use their normal user-level dependency caches.

Generated WASM bindings and binaries live in web/src/wasm-pkg. They are built from web/wasm, not hand-written JavaScript substitutes. Build assets live in web/dist. The launcher stages content-addressed immutable copies of the application JAR, dependencies, fixture, and web assets under .local/runtime, and the Java process serves that staged version. Dependency build output is target/runtime-dependencies, not the target/lib directory used by older running versions. This avoids overwriting JARs locked by Windows. Running instances keep their version until restarted.

## Isolation and cleanup

task test:e2e creates a fresh PoC data directory under .local/e2e-* and starts an available loopback port. Browser scenarios may use Reset there; they never target the normal PoC. Its Java process is stopped afterward. Artifacts and server logs remain there for diagnosis. These tests do not write to .local/demo.

task clear removes a fixed allowlist of build outputs, including generated web/WASM files, but preserves .local, Git history, and user source. It refuses cleanup paths that traverse symlinks or junctions.

The backend takes an owner lock for its data directory. Use the Alice/Bob header buttons in multiple browser tabs for collaboration; do not start two backends on one data directory. Reset explicitly discards active demo state/history and restores the reference, while task clear remains non-destructive to saved demo data.

Optional environment variables:
- SYSML_PORT: defaults to 4317.
- SYSML_DATA_DIR: test/developer override; normal PoC defaults to .local/poc-demo.
- There is no SYSML_MODEL choice in the application launcher. The reference is fixtures/water-tank.sysml. Existing saved environments keep their model until an explicit Reset after restarting the server.
- SYSML_FAIL_AT: test-only fault injection. Leave unset for normal use.

## Development browser server

The production same-origin server is the default. For frontend-only iteration, start the backend and run `npm --prefix web run dev`. Vite uses 127.0.0.1:5173 and proxies only trusted loopback development requests to the backend.

## Reference documentation

- Maven release compilation: https://maven.apache.org/plugins/maven-compiler-plugin/examples/set-compiler-release.html
- JUnit: https://docs.junit.org/current/user-guide/
- Gson: https://google.github.io/gson/UserGuide.html
- Java HTTP server: https://docs.oracle.com/en/java/javase/21/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html
- Git expected-head ref update: https://git-scm.com/docs/git-update-ref
- SQLite transactions: https://www.sqlite.org/lang_transaction.html
