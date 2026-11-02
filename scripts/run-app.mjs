import { existsSync, readdirSync, readFileSync, mkdirSync, copyFileSync, renameSync, rmSync, mkdtempSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, delimiter, resolve, basename, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { spawn } from 'node:child_process';
const root = fileURLToPath(new URL('../', import.meta.url));

export function immutableFile(file, category, cache = join(root, '.local/runtime')) {
  const digest = createHash('sha256').update(readFileSync(file)).digest('hex');
  const destination = join(cache, category, digest, basename(file));
  const verify = () => {
    if (createHash('sha256').update(readFileSync(destination)).digest('hex') !== digest)
      throw new Error('Corrupt runtime cache: ' + destination + '. Remove that generated cache after stopping its runtime.');
  };
  if (existsSync(destination)) { verify(); return destination; }
  mkdirSync(dirname(destination), { recursive: true });
  const temporary = destination + '.part-' + process.pid;
  copyFileSync(file, temporary);
  try { renameSync(temporary, destination); }
  catch (error) { if (!existsSync(destination)) throw error; verify(); }
  finally { rmSync(temporary, { force: true }); }
  return destination;
}
export function immutableWeb(directory, cache = join(root, '.local/runtime')) {
  const files = readdirSync(directory, { recursive: true, withFileTypes: true })
    .filter(entry => entry.isFile()).map(entry => join(entry.parentPath, entry.name));
  const digest = createHash('sha256');
  for (const file of files.sort()) { digest.update(file.slice(directory.length)); digest.update(readFileSync(file)); }
  const destination = join(cache, 'web', digest.digest('hex'));
  if (existsSync(destination)) return destination;
  mkdirSync(dirname(destination), { recursive: true });
  const staging = mkdtempSync(join(dirname(destination), '.stage-'));
  try {
    for (const file of files) { const target = join(staging, file.slice(directory.length + 1)); mkdirSync(dirname(target), { recursive: true }); copyFileSync(file, target); }
    try { renameSync(staging, destination); }
    catch (error) { if (!existsSync(destination)) throw error; }
  } finally { rmSync(staging, { recursive: true, force: true }); }
  return destination;
}

export function serverCommand({ port = process.env.SYSML_PORT ?? '4317', data = process.env.SYSML_DATA_DIR ?? join(root, '.local/poc-demo') } = {}) {
  const module = join(root, 'backend/workspace-service/target');
  const application = join(module, 'workspace-service-0.1.0-SNAPSHOT.jar');
  if (!existsSync(application) || !existsSync(join(root, 'web/dist/index.html'))) {
    throw new Error('Run task build before starting the application.');
  }
  const lock = JSON.parse(readFileSync(join(root, 'tools/pilot-lock.json'), 'utf8'));
  const lib = join(module, 'runtime-dependencies');
  const jars = readdirSync(lib).filter(name => name.endsWith('.jar')).sort((a, b) => {
    const rank = name => name.startsWith('gson-') ? 0 : name.startsWith('official-sysml-pilot-') ? 2 : 1;
    return rank(a) - rank(b) || a.localeCompare(b);
  });
  const env = { ...process.env };
  if (!env.JAVA_HOME) throw new Error('Set JAVA_HOME to a full JDK 21 before running.');
  return { command: join(env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java'), env,
    args: ['-Xmx2g', '-Dsysml.root=' + root, ...(process.env.SYSML_FAIL_AT ? ['-Dsysml.failAt=' + process.env.SYSML_FAIL_AT] : []),
      '-cp', [immutableFile(application, 'jars'), ...jars.map(name => immutableFile(join(lib, name), 'jars'))].join(delimiter),
      'app.LocalServer', '--port', String(port), '--data', resolve(data), '--web', immutableWeb(join(root, 'web/dist')),
      '--fixture', immutableFile(join(root, 'fixtures/water-tank.sysml'), 'fixtures'),
      '--libraries', join(root, '.local/pilot', lock.runtimeDirectory, lock.libraries)] };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const invocation = serverCommand();
    const child = spawn(invocation.command, invocation.args, { cwd: root, env: invocation.env, stdio: 'inherit' });
    child.on('error', error => { console.error(error.message); process.exitCode = 1; });
    child.on('exit', code => { process.exitCode = code ?? 1; });
    process.on('SIGINT', () => child.kill('SIGINT'));
    process.on('SIGTERM', () => child.kill('SIGTERM'));
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
