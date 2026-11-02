#!/usr/bin/env node
// Official release and permanent source references: ../docs/parser-feasibility.md.
import { createHash } from 'node:crypto';
import { readFile, writeFile, mkdir, mkdtemp, lstat, readdir, rename } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import assert from 'node:assert/strict';

const root = fileURLToPath(new URL('../', import.meta.url));
const cache = path.join(root, '.local', 'pilot');
const lock = JSON.parse(await readFile(new URL('../tools/pilot-lock.json', import.meta.url), 'utf8'));
const args = process.argv.slice(2);
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

function localPath(base, relative) {
  if (!relative || relative.includes('\\') || relative.split('/').some(p => !p || p === '..' || p === '.') || path.isAbsolute(relative)) {
    throw new Error(`Unsafe locked path: ${relative}`);
  }
  const result = path.resolve(base, relative);
  if (!result.startsWith(path.resolve(base) + path.sep)) throw new Error(`Path escapes cache: ${relative}`);
  return result;
}

async function exists(file) {
  try { await lstat(file); return true; }
  catch (error) { if (error.code === 'ENOENT') return false; throw error; }
}

async function verify(file, expected) {
  if (!(await exists(file))) throw new Error(`Missing ${file}. Run node scripts/setup-pilot.mjs to prepare the pinned distribution.`);
  const stat = await lstat(file);
  if (!stat.isFile()) throw new Error(`Expected a regular file, not a directory or link: ${file}`);
  const actual = sha256(await readFile(file));
  if (actual !== expected) throw new Error(`SHA256 mismatch for ${file}\nExpected ${expected}\nActual   ${actual}\nNothing was overwritten. Preserve or move the damaged cache yourself, then rerun setup.`);
}

function run(executable, argv, cwd = root) {
  const result = spawnSync(executable, argv, { cwd, encoding: 'utf8', timeout: 180_000, maxBuffer: 8 * 1024 * 1024 });
  if (result.error || result.status !== 0) {
    throw new Error(`Command failed: ${executable} ${argv.join(' ')}\n${result.error?.message ?? ''}\n${result.stdout ?? ''}${result.stderr ?? ''}`);
  }
  return result.stdout + result.stderr;
}

async function verifyRuntime(directory) {
  for (const file of lock.files) await verify(localPath(directory, file.path), file.sha256);
  // loadLibrary recursively reads model files. Do not permit unpinned additions.
  const library = localPath(directory, lock.libraries);
  const expected = new Set(lock.files.filter(f => f.path.startsWith(lock.libraries + '/')).map(f => f.path.slice(lock.libraries.length + 1)));
  for (const entry of await readdir(library, { recursive: true, withFileTypes: true })) {
    const name = path.relative(library, path.join(entry.parentPath, entry.name)).split(path.sep).join('/');
    if (!entry.isDirectory() && (!entry.isFile() || !expected.has(name))) throw new Error(`Unpinned library entry: ${name}. Move it outside the pinned runtime and retry.`);
  }
}

const probeSource = `import java.nio.file.Path;
import org.omg.sysml.interactive.SysMLInteractive;
import org.omg.sysml.lang.sysml.*;
import org.eclipse.xtext.diagnostics.Severity;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;

public class PilotBootstrapProbe {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        long started = System.nanoTime();
        var pilot = SysMLInteractive.createInstance();
        pilot.setVerbose(false);
        pilot.loadLibrary(Path.of(args[0]).toAbsolutePath().toString());
        require(pilot.getLibraryResources().size() == 94, "Expected 94 matching library resources");
        System.out.println("LIBRARIES=" + pilot.getLibraryResources().size());
        pilot.setResource(Path.of(args[1], "pump.sysml").toAbsolutePath().toString());
        pilot.addInputResource(pilot.getResource());
        String valid = "package PumpModel { public import ScalarValues::*; part def Pump { attribute flowRate : Real = 10; } }";
        pilot.parse(valid);
        pilot.addResourceToIndex(pilot.getResource());
        pilot.resolveAllInputResources();
        var issues = pilot.validate();
        issues.forEach(i -> System.out.println("VALID " + i.getSeverity() + " " + i.getMessage()));
        require(issues.stream().noneMatch(i -> i.getSeverity() == Severity.ERROR), "Valid fixture rejected");
        boolean partFound = false, attributeFound = false, literalFound = false;
        var contents = pilot.getResource().getAllContents();
        while (contents.hasNext()) {
            var object = contents.next();
            if (object instanceof PartDefinition part && "Pump".equals(part.getName())) partFound = true;
            if (object instanceof AttributeUsage attribute && "flowRate".equals(attribute.getName())) {
                require(attribute.getType().stream().anyMatch(t -> !t.eIsProxy() && "ScalarValues::Real".equals(t.getQualifiedName())), "ScalarValues::Real did not link");
                System.out.println("TYPE=ScalarValues::Real proxy=false");
                attributeFound = true;
            }
            if (object instanceof LiteralInteger literal && literal.getValue() == 10) {
                var node = NodeModelUtils.getNode(literal);
                require(node != null && node.getText().strip().equals("10"), "Missing authored literal node");
                System.out.println("LITERAL=10 nodeRange=" + node.getOffset() + ":" + node.getEndOffset());
                literalFound = true;
            }
        }
        require(partFound && attributeFound && literalFound, "Missing genuine Pump/flowRate/literal model");
        System.out.println("COLD_MS=" + (System.nanoTime() - started) / 1_000_000);
        long warm = System.nanoTime();
        pilot.parse(valid.replace("= 10", "= 12"));
        pilot.addResourceToIndex(pilot.getResource());
        pilot.resolveAllInputResources();
        require(pilot.validate().stream().noneMatch(i -> i.getSeverity() == Severity.ERROR), "Warm reparse rejected");
        boolean newLiteralFound = false;
        var reparsed = pilot.getResource().getAllContents();
        while (reparsed.hasNext()) {
            var object = reparsed.next();
            if (object instanceof LiteralInteger literal) {
                require(literal.getValue() != 10, "Reparse retained the old literal");
                if (literal.getValue() == 12) newLiteralFound = true;
            }
        }
        require(newLiteralFound, "Reparse did not produce the new literal");
        System.out.println("WARM_MS=" + (System.nanoTime() - warm) / 1_000_000);
        pilot.setResource(Path.of(args[1], "unknown.sysml").toAbsolutePath().toString());
        pilot.parse("package Unknown { part def Pump { attribute flowRate : MissingType = 10; } }");
        require(pilot.validate().stream().anyMatch(i -> i.getSeverity() == Severity.ERROR && i.getMessage().contains("MissingType")), "Unknown type did not produce a linker diagnostic");
        pilot.setResource(Path.of(args[1], "malformed.sysml").toAbsolutePath().toString());
        pilot.parse("package Broken { public import ScalarValues::*; part def Pump { attribute flowRate : Real = ; } }");
        var malformed = pilot.validate();
        malformed.forEach(i -> System.out.println("MALFORMED " + i.getSeverity() + " line=" + i.getLineNumber() + " " + i.getMessage()));
        require(malformed.stream().anyMatch(i -> i.getSeverity() == Severity.ERROR && i.isSyntaxError()), "Malformed input did not produce a syntax error");
        System.out.println("PASS official parser, libraries, linking and validation");
    }
}
`;

async function selfTest() {
  await mkdir(cache, { recursive: true });
  const temporary = await mkdtemp(path.join(cache, 'bootstrap-test-'));
  const fixture = path.join(temporary, 'checksum-fixture.txt');
  await writeFile(fixture, 'unchanged', { flag: 'wx' });
  await verify(fixture, sha256('unchanged'));
  await assert.rejects(verify(fixture, sha256('different')), /SHA256 mismatch/);
  await assert.rejects(verify(path.join(temporary, 'missing'), sha256('')), /Missing/);
  assert.equal(await readFile(fixture, 'utf8'), 'unchanged');
  for (const unsafe of ['../escape', '/absolute', 'x/../../escape', 'x\\escape']) assert.throws(() => localPath(cache, unsafe));
  assert.equal(new Set(lock.files.map(f => f.path)).size, lock.files.length);
  assert.equal(lock.files.filter(f => /\.(sysml|kerml)$/.test(f.path)).length, lock.librarySourceCount);
  for (const file of lock.files) { localPath(cache, file.path); assert.match(file.sha256, /^[a-f0-9]{64}$/); }
  console.log('PASS bootstrap regression: checksum, missing file, no overwrite, path confinement, lock inventory');
}

async function main() {
  if (args.length > 1 || args.some(a => !['--check', '--self-test', '--help'].includes(a))) throw new Error('Usage: node scripts/setup-pilot.mjs [--check | --self-test | --help]');
  if (args.includes('--help')) {
    console.log('Default: download the pinned official ZIP if absent, verify/extract, then run the Java smoke probe.\n--check: offline verification and real Java probe; never downloads or repairs artifacts.\n--self-test: bootstrap safety regression; no network or JDK required.\nAll generated files remain under .local/pilot. JAVA_HOME must select a full JDK 21.');
    return;
  }
  if (args.includes('--self-test')) return selfTest();
  if (!process.env.JAVA_HOME) throw new Error('Set JAVA_HOME to a full JDK 21, then rerun. The shell javac may select JDK 17.');
  const executable = name => path.join(process.env.JAVA_HOME, 'bin', name + (process.platform === 'win32' ? '.exe' : ''));
  const java = executable('java'), javac = executable('javac'), jar = executable('jar');
  const version = run(java, ['-version']);
  if (!/version "21[.\"]/.test(version)) throw new Error(`JAVA_HOME must select JDK 21. Found:\n${version}`);
  if (!/^javac 21[.\s]/.test(run(javac, ['-version']))) throw new Error('JAVA_HOME must contain the matching JDK 21 compiler.');
  run(jar, ['--version']);
  const archive = localPath(cache, 'downloads/' + lock.archive.file);
  const runtime = localPath(cache, lock.runtimeDirectory);
  if (!(await exists(archive)) && !args.includes('--check')) {
    await mkdir(path.dirname(archive), { recursive: true });
    console.log(`Downloading ${lock.archive.url}`);
    const response = await fetch(lock.archive.url, { signal: AbortSignal.timeout(180_000) });
    if (!response.ok) throw new Error(`Download failed: HTTP ${response.status}. Retry the pinned URL when reachable; no alternate parser will be used.`);
    const bytes = Buffer.from(await response.arrayBuffer());
    if (bytes.length !== lock.archive.bytes || sha256(bytes) !== lock.archive.sha256) throw new Error('Downloaded archive does not match the pinned size/SHA256. Nothing installed; check the official release provenance.');
    await writeFile(archive, bytes, { flag: 'wx' });
  }
  await verify(archive, lock.archive.sha256);
  if (!(await exists(runtime))) {
    if (args.includes('--check')) throw new Error(`Missing runtime ${runtime}. Run node scripts/setup-pilot.mjs first.`);
    await mkdir(path.dirname(runtime), { recursive: true });
    const staging = await mkdtemp(path.join(path.dirname(runtime), 'install-'));
    run(jar, ['xf', archive, ...lock.files.map(f => f.path)], staging);
    await verifyRuntime(staging);
    await rename(staging, runtime);
  }
  await verifyRuntime(runtime);
  console.log(`Verified ${lock.files.length} pinned files for official Pilot ${lock.release}.`);
  const classpath = localPath(runtime, lock.classpath);
  const libraries = localPath(runtime, lock.libraries);
  await mkdir(path.join(cache, 'probe'), { recursive: true });
  const probe = await mkdtemp(path.join(cache, 'probe', 'run-'));
  const source = path.join(probe, 'PilotBootstrapProbe.java');
  await writeFile(source, probeSource, { flag: 'wx' });
  run(javac, ['-encoding', 'UTF-8', '-cp', classpath, '-d', probe, source]);
  const output = run(java, ['-Xmx2g', '-cp', [classpath, probe].join(path.delimiter), 'PilotBootstrapProbe', libraries, probe], probe);
  await writeFile(path.join(probe, 'result.txt'), output, { flag: 'wx' });
  console.log(output.trim());
  console.log(`CLASSPATH=${classpath}\nLIBRARIES=${libraries}\nPROBE_RESULT=${path.join(probe, 'result.txt')}`);
}

try { await main(); }
catch (error) { console.error(`Pilot bootstrap failed: ${error.message}`); process.exitCode = 1; }
