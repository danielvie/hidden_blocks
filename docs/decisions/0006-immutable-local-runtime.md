# Stage immutable local runtime files

Status: Implemented.

A running Windows Java process held the old target/lib JARs open and prevented packaging the updated application. Stopping the user's instance would interrupt their work, so new builds use target/runtime-dependencies and the launcher stages immutable, content-addressed JAR and web-asset copies under .local/runtime.

Each launched process keeps its own built version, while later compilation can replace build outputs. Runtime artifacts are shared by content hash; the large unchanged official parser JAR is not duplicated for every app build. File staging verifies cached JAR integrity and publishes complete web directories through a staged rename.

These are generated caches, not workspace storage. task clear preserves them along with all .local data. Existing legacy processes may still hold their original build paths until the user stops them.
