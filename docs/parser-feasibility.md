# Official parser bootstrap evidence

## Result and scope

The official Pilot runs headlessly on the installed full JDK 21.0.6. The bootstrap loads matching libraries, parses a minimal Pump model, links `ScalarValues::Real`, reads genuine EMF part/attribute/literal objects, and runs Xtext validation. Malformed text produces a syntax error. No Eclipse, Jupyter server, Python, replacement parser, or Maven build is needed for this distribution.

Bootstrap, the T03 Maven integration, and T04-T05 now pass. The adapter projects actual semantic elements, preserves stable IDs and precise source mappings, applies source-preserving edits, resolves cross-file references, isolates workspaces, and denies source-controlled external resource loading. Production redistribution/security review remains separate.

## Pins and provenance

`tools/pilot-lock.json` pins the official [2026-09.1 release](https://github.com/Systems-Modeling/SysML-v2-Pilot-Implementation/releases/tag/2026-09.1) and its source commit `d81b2ad5016f2e2d3d4364b3d06d01d8bb12a896`.

- Archive: `jupyter-sysml-kernel-0.63.1.zip`, 126,141,158 bytes.
- Archive SHA256: `2ab938037b8d3a02a3518019b6e81b356a994f4a5214727bfedcfd64bad25415`.
- Shaded JAR SHA256: `67c914092f4520ba98548b285c5a9a8bc08f92a0aeec7b763e12a574e91f9ff7`.
- The archive digest matches the [official release API](https://api.github.com/repos/Systems-Modeling/SysML-v2-Pilot-Implementation/releases/tags/2026-09.1).
- The same archive contains all 94 `.kerml`/`.sysml` standard-library sources and `.index.json`. Each source was compared with `sysml.library` at the pinned implementation commit; all matched byte-for-byte. The lock hashes all 97 installed files, including the JAR, index and license.

The initial candidate `a9676a3c4825201b270b59289bf284154842bde1` is four commits newer than that release. It includes library changes and version `0.64.0-SNAPSHOT`, so the release binary must not be labeled as that candidate. The published self-contained runtime avoids an unnecessary unverified source build. No bit-for-bit rebuild or signed build attestation was established.

Permanent implementation evidence:

- [SysMLInteractive](https://github.com/Systems-Modeling/SysML-v2-Pilot-Implementation/blob/d81b2ad5016f2e2d3d4364b3d06d01d8bb12a896/org.omg.sysml.interactive/src/org/omg/sysml/interactive/SysMLInteractive.java), especially `createInstance`, `loadLibrary`, `parse` and `validate`.
- [SysMLUtil](https://github.com/Systems-Modeling/SysML-v2-Pilot-Implementation/blob/d81b2ad5016f2e2d3d4364b3d06d01d8bb12a896/org.omg.sysml/src/org/omg/sysml/io/SysMLUtil.java), resource sets and indexing.
- [Kernel POM](https://github.com/Systems-Modeling/SysML-v2-Pilot-Implementation/blob/d81b2ad5016f2e2d3d4364b3d06d01d8bb12a896/org.omg.sysml.jupyter.kernel/pom.xml) and [assembly](https://github.com/Systems-Modeling/SysML-v2-Pilot-Implementation/blob/d81b2ad5016f2e2d3d4364b3d06d01d8bb12a896/org.omg.sysml.jupyter.kernel/assembly.xml), official shaded runtime packaging.

## Commands and local paths

From the project root, with Node 24 and `JAVA_HOME` selecting the full JDK 21:

```text
node scripts/setup-pilot.mjs
node scripts/setup-pilot.mjs --check
node scripts/setup-pilot.mjs --self-test
```

Setup downloads only the pinned URL when absent, checks SHA256 before extraction, stages an absent runtime, verifies every file, and compiles/runs the embedded Java regression. `--check` performs offline archive/runtime verification and the same real-parser regression. It creates a fresh probe directory but never downloads or repairs artifacts. `--self-test` checks bootstrap safety without Java or network access.

All output stays under `.local/pilot`; no global installs, POM changes or cache deletion occur. A checksum mismatch or unpinned library addition stops setup without overwriting it. Preserve or move damaged files explicitly before retrying. `--help` lists modes.

Installed relative paths:

```text
.local/pilot/downloads/jupyter-sysml-kernel-0.63.1.zip
.local/pilot/runtime/2026-09.1/sysml/jupyter-sysml-kernel-0.63.1-all.jar
.local/pilot/runtime/2026-09.1/sysml/sysml.library
.local/pilot/probe/run-*/PilotBootstrapProbe.java
.local/pilot/probe/run-*/result.txt
```

The only external compile/runtime classpath entry is that JAR. Compilation uses `JAVA_HOME/bin/javac -encoding UTF-8 -cp <jar> -d <probe> <source>`. Execution uses `JAVA_HOME/bin/java -Xmx2g -cp <jar>;<probe> PilotBootstrapProbe <libraries> <probe>` on Windows. The script uses the platform classpath separator and never launches the JAR's Jupyter main class.

## Verified APIs and fixture

```sysml
package PumpModel {
    public import ScalarValues::*;
    part def Pump {
        attribute flowRate : Real = 10;
    }
}
```

The probe calls `SysMLInteractive.createInstance()`, disables verbose reading, and calls `loadLibrary` with the absolute library directory. For the document it calls `setResource` with an absolute `.sysml` path, `addInputResource`, `parse`, `addResourceToIndex`, `resolveAllInputResources`, then `validate`. `validate` uses `CheckMode.ALL`. Traversal finds `PartDefinition`, `AttributeUsage`, and `LiteralInteger`. `AttributeUsage.getType()` contains `ScalarValues::Real` with `eIsProxy() == false`.

Do not use `process` unchanged for production invalid-buffer handling: upstream removes resources when validation fails. The probe uses `parse`/`validate` directly. `NodeModelUtils.getNode(literal)` exposes an authored node range. The adapter now tests exact expression slicing and preservation of surrounding whitespace/comments, including Unicode and CRLF. Implicit or derived elements are not assumed to have editable source mappings. `LiteralInteger.getValue()` returns a Java `int`; arbitrary numeric precision requires authored-text handling in later adapter work.

## Measured checks

On this Windows machine, in separate Java processes:

| Check | Result |
| --- | --- |
| `node --check scripts/setup-pilot.mjs` | Passed |
| `node scripts/setup-pilot.mjs --self-test` | Passed checksum, missing-file, preservation, path and inventory assertions |
| `node scripts/setup-pilot.mjs --check` | Passed 97 file hashes, 94 loaded libraries, real model/link/validation assertions |
| Cold initialization, library load and first analysis | 5,853 ms; isolated fresh extraction 5,825 ms; repeat process 5,705 ms |
| Warm reparse from 10 to 12 and validation | 60 ms; isolated repeats 61 and 62 ms |
| Malformed initializer `Real = ;` | `ERROR`, line 1, `no viable alternative at input '}'` |
| Unknown `MissingType` | Real linker error assertion passed |

`node .local/pilot/research/bootstrap-integration.mjs` additionally passed fresh extraction and offline repeat tests in an isolated test-owned project under `.local/pilot`. Missing runtime/JDK, an extra library source, and a corrupted test-owned JAR each failed explicitly without overwriting the test data. A second empty test project, `.local/pilot/fresh-bootstrap-YxMrHY`, ran the script with no preseeded archive or runtime and passed the complete download, verification, extraction and probe sequence; cold analysis was 5,673 ms and warm analysis 60 ms. Its output is preserved in `fresh-setup-result.txt`. That experiment is local evidence, not a tracked test dependency; the tracked script's regression remains runnable through `--self-test` and `--check`.

Times exclude Node hashing, download and Java compilation. They are samples, not performance guarantees. Upstream emits log4j no-appender warnings and a bound-feature warning for malformed input; neither was suppressed or counted as a valid-fixture error.

## Maven integration checkpoint

The parent integration independently ran `task setup` and `task test:sysml-adapter`.
Setup preserves the official JAR bytes and installs them under local Maven coordinates
`app.sysmlcollab.vendor:official-sysml-pilot:2026.09.1`. These are application-local coordinates, not an official Maven Central publication.

At the initial checkpoint, the aggregate task test run passed 4 Node tooling tests, 6 Java contract tests, 5 real-parser Maven tests, and both bootstrap regressions. The final full-application test counts are in docs/verification.md. A read-only reviewer found no T01-T03 blocker. Follow-up fixes preserved read-only expression metadata, specified semantic scalar-kind meaning, rejected unsupported Maven launcher settings explicitly, and made the standalone warm-reparse probe assert the changed model literal.

Five `ParserSmokeTest` tests passed:
- The shared Pump fixture produces genuine part/attribute objects and resolves ScalarValues::Real.
- Malformed text produces an actual syntax diagnostic.
- MissingType produces an actual linker diagnostic.
- Reparsing changes the actual literal from 10 to 12.
- The application uses Gson 2.15.0 rather than the older shaded copy and round-trips application records.

The integration run measured 5,446 ms for library startup and 69 ms for warm reparse. The independently executed bootstrap probe measured 5,802 ms cold and 57 ms warm.

The adapter module now contains production ParserAdapter and SourceEdits implementations, 23 real-parser adapter tests, and the 5 smoke tests. It is integrated with the browser, authoritative change engine, SQLite workspaces, and Git publication.

## Licensing and remaining risks

The preserved distribution `LICENSE` is EPL-2.0. The [official release licensing statement](https://github.com/Systems-Modeling/SysML-v2-Release/blob/9546486f49f704d1df092e3f252f4fcfbce534dd/README.md#licensing) also assigns EPL-2.0 to included KerML/SysML models. Third-party components retain their own licenses; this shaded JAR includes Xtext/EMF, Guice, Gson, API-client components, PlantUML and Jupyter support. A complete dependency/license inventory and redistribution review remain open. Do not infer that every bundled component has the top-level license.

Actual embedded Maven metadata reports Gson `2.8.6`, despite the kernel POM's provided Gson `2.12.1`. The application adapter POM now places Gson `2.15.0` before the shaded runtime. Its integration test verifies the actual loaded JAR location and a Java-record JSON round trip, and the official parsing/linking/validation tests pass in that same classpath. This proves the tested integration, not compatibility with every API or embedded component. The JAR manifest reports `Build-Jdk-Spec: 21`; embedded kernel metadata reports `0.63.1`.

Backend ParserSmokeTest and the expanded Pump/Controller/Tank fixture pass. Adapter tests now verify isolation, blocked external URI reads, explicit scalar-type diagnostics, and source-preserving edits. Parser execution remains serialized because no general thread-safety guarantee is assumed. The tests do not establish full SysML conformance.

The connected-model extension follows Type.getFeature() for effective features, explicit typing/specialization metadata for user-defined types, and ConnectionUsage.getRelatedFeature() feature chains for real port endpoints. Conjugation is read through ConjugatedPortDefinition.getOriginalPortDefinition(); PortUsage.isConjugated() alone was not sufficient for this fixture. Per-usage inherited rows retain the actual authored definition attribute ID and have no editable local range. Tests cover source order, aliases, stable endpoints, local overrides and refreshed inherited values.

The pinned official validator did not reject a Boolean literal assigned to Real in a focused test. The adapter therefore adds a scalar-type diagnostic based on the real resolved standard-library type and real literal class. Integer tokens outside the official converter's 32-bit range are rejected, never silently rounded. Supported decimal/exponent values retain authored precision. Derived/computed values remain read-only.

Measured adapter samples: 6,219 ms cold first analysis, 60 ms warm changed-document analysis, 23 ms for another analysis in an existing workspace. These are samples, not a latency guarantee. The complete application checks are recorded in docs/verification.md.
