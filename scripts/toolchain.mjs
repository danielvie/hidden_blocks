import { existsSync, lstatSync, readFileSync, readdirSync, realpathSync, rmSync } from 'node:fs';
import { delimiter, dirname, join, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';

export const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const outputs = [
  'contracts/java/target', 'backend/sysml-adapter/target',
  'backend/change-engine/target', 'backend/workspace-service/target',
  'web/dist', 'web/wasm/target', 'web/wasm/pkg', 'web/src/wasm-pkg', 'web/test-results', 'web/playwright-report', 'test-results', 'playwright-report'
];

export function javaEnvironment(env = process.env) {
  const home = env.JAVA_HOME;
  const suffix = process.platform === 'win32' ? '.exe' : '';
  if (!home || !existsSync(join(home, 'bin', 'javac' + suffix))) {
    throw new Error('JAVA_HOME must point to a full JDK 21, not a JRE.');
  }
  const result = { ...env };
  const pathKey = Object.keys(result).find(key => key.toLowerCase() === 'path') ?? 'PATH';
  result[pathKey] = join(home, 'bin') + delimiter + (result[pathKey] ?? '');
  return result;
}

export function clearBuildOutputs(projectRoot = root) {
  const base = realpathSync(projectRoot);
  // Inspect every ancestor before deleting anything. An output behind a junction
  // could otherwise point into another project or the saved workspace directory.
  for (const relative of outputs) {
    let current = base;
    for (const part of relative.split('/')) {
      current = join(current, part);
      if (existsSync(current) && lstatSync(current).isSymbolicLink()) {
        throw new Error('Refusing cleanup through symlink/junction: ' + current);
      }
      if (existsSync(current) && !realpathSync(current).startsWith(base + sep)) {
        throw new Error('Build output escapes project: ' + current);
      }
    }
  }
  for (const relative of outputs) rmSync(join(base, relative), { recursive: true, force: true });
}

export function checkMavenConfiguration(env = process.env, projectRoot = root) {
  for (const key of ['MAVEN_OPTS', 'MAVEN_ARGS', 'MAVEN_DEBUG_OPTS']) {
    if (env[key]?.trim()) throw new Error('The Windows task launcher does not support ' + key +
      '. Use the standard Maven launcher or add explicit project support; the value was not printed.');
  }
  const config = join(projectRoot, '.mvn', 'jvm.config');
  if (existsSync(config) && readFileSync(config, 'utf8').trim()) {
    throw new Error('The Windows task launcher does not support .mvn/jvm.config. Use standard Maven or add explicit project support.');
  }
}

function run(command, args, env = javaEnvironment()) {
  if (command === 'mvn' && process.platform === 'win32') {
    checkMavenConfiguration(env);
    const pathKey = Object.keys(env).find(key => key.toLowerCase() === 'path');
    const script = env[pathKey].split(delimiter).map(dir => join(dir, 'mvn.cmd')).find(existsSync);
    if (!script) throw new Error('Maven bin directory must be on PATH.');
    const home = dirname(dirname(script));
    const boot = readdirSync(join(home, 'boot')).find(name => name.startsWith('plexus-classworlds-') && name.endsWith('.jar'));
    if (!boot) throw new Error('Maven classworlds launcher was not found.');
    // Same launcher as Maven bin/mvn, without cmd.exe string interpolation.
    args = ['-classpath', join(home, 'boot', boot),
      '-Dclassworlds.conf=' + join(home, 'bin', 'm2.conf'), '-Dmaven.home=' + home,
      '-Dmaven.multiModuleProjectDirectory=' + root,
      'org.codehaus.plexus.classworlds.launcher.Launcher', ...args];
    command = join(env.JAVA_HOME, 'bin', 'java.exe');
  }
  if (command === 'npm') {
    const pathKey = Object.keys(env).find(key => key.toLowerCase() === 'path');
    const candidates = [dirname(process.execPath), ...env[pathKey].split(delimiter)]
      .map(dir => join(dir, 'node_modules/npm/bin/npm-cli.js'));
    const cli = candidates.find(existsSync);
    if (!cli) throw new Error('Cannot find npm-cli.js alongside the installed Node/npm. Check the toolchain.');
    args = [cli, ...args]; command = process.execPath;
  }
  const result = spawnSync(command, args, { cwd: root, env, stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(command + ' exited with status ' + result.status);
}

function requireFile(path, feature) {
  if (!existsSync(join(root, path))) throw new Error(feature + ' is not implemented yet. See tasks/todo.md.');
}

function mavenArgs() {
  const args = ['-B', '-ntp', '-Dsysml.root=' + root];
  const lockFile = join(root, 'tools/pilot-lock.json');
  if (existsSync(lockFile)) {
    const lock = JSON.parse(readFileSync(lockFile, 'utf8'));
    args.push('-Dsysml.pilot.library=' + join(root, '.local/pilot', lock.runtimeDirectory, lock.libraries));
  }
  return args;
}

function maven(pom, goals) {
  requireFile(pom, pom);
  run('mvn', [...mavenArgs(), '-f', pom, ...goals]);
}

function installPilot() {
  const lock = JSON.parse(readFileSync(join(root, 'tools/pilot-lock.json'), 'utf8'));
  run('mvn', [...mavenArgs(), 'org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file',
    '-Dfile=' + join(root, '.local/pilot', lock.runtimeDirectory, lock.classpath),
    '-DgroupId=app.sysmlcollab.vendor', '-DartifactId=official-sysml-pilot',
    '-Dversion=2026.09.1', '-Dpackaging=jar', '-DgeneratePom=true']);
}

function check() {
  const env = javaEnvironment();
  const suffix = process.platform === 'win32' ? '.exe' : '';
  for (const tool of ['java', 'javac']) {
    const executable = join(env.JAVA_HOME, 'bin', tool + suffix);
    const version = spawnSync(executable, ['-version'], { env, encoding: 'utf8' });
    if (version.error || version.status !== 0 || !/\b21\./.test(version.stdout + version.stderr)) {
      throw new Error('Expected JDK 21: ' + executable);
    }
    console.log(executable + '\n' + (version.stdout + version.stderr).trim());
  }
  run('mvn', ['-version'], env);
  run('git', ['--version'], env);
  run('rustc', ['--version'], env);
  run('wasm-pack', ['--version'], env);
  run('npm', ['--version'], env);
  console.log('Node ' + process.version + '. Global PATH was not changed.');
}

export async function main(action, checkOnly = '0') {
  if (action === 'clear') return clearBuildOutputs();
  if (action === 'setup') {
    check();
    if (checkOnly === '1') return;
    requireFile('scripts/setup-pilot.mjs', 'Official parser bootstrap');
    run(process.execPath, ['scripts/setup-pilot.mjs']);
    installPilot();
    run('npm', ['--prefix', 'web', 'ci']);
    run(process.execPath, ['web/node_modules/@playwright/test/cli.js', 'install', 'chromium']);
    return;
  }
  if (action === 'contracts') return maven('contracts/java/pom.xml', ['verify']);
  const module = { parser: 'sysml-adapter', changes: 'change-engine', workspaces: 'workspace-service' }[action];
  if (module) {
    requireFile('backend/' + module + '/pom.xml', module);
    maven('contracts/java/pom.xml', ['install']);
    return maven('backend/pom.xml', ['-pl', module, '-am', 'verify']);
  }
  if (action === 'run') {
    await main('build');
    return run(process.execPath, ['scripts/run-app.mjs']);
  }
  if (action === 'serve') return run(process.execPath, ['scripts/run-app.mjs']);
  if (action === 'web') return run('npm', ['--prefix', 'web', 'test']);
  if (action === 'e2e') {
    await main('build');
    const { runBrowserTests } = await import('./e2e.mjs');
    return runBrowserTests();
  }
  if (action === 'build' || action === 'lint' || action === 'test') {
    if (action !== 'build') run(process.execPath, ['--test', 'scripts/toolchain.test.mjs', 'scripts/runtime.test.mjs']);
    if (action === 'lint') {
      for (const script of ['toolchain.mjs', 'toolchain.test.mjs', 'setup-pilot.mjs', 'run-app.mjs', 'runtime.test.mjs', 'e2e.mjs']) {
        if (existsSync(join(root, 'scripts', script))) run(process.execPath, ['--check', 'scripts/' + script]);
      }
    }
    if (action === 'test' && existsSync(join(root, 'scripts/setup-pilot.mjs'))) {
      run(process.execPath, ['scripts/setup-pilot.mjs', '--self-test']);
      run(process.execPath, ['scripts/setup-pilot.mjs', '--check']);
    }
    const skip = action === 'test' ? [] : ['-DskipTests'];
    maven('contracts/java/pom.xml', ['install', ...skip]);
    maven('backend/pom.xml', ['verify', ...skip]);
    if (action === 'lint') {
      run('npm', ['--prefix', 'web', 'run', 'typecheck']);
      run('cargo', ['fmt', '--manifest-path', 'web/wasm/Cargo.toml', '--', '--check']);
    } else {
      if (action === 'test') run('npm', ['--prefix', 'web', 'test']);
      run('npm', ['--prefix', 'web', 'run', 'build']);
      if (action === 'test') {
        const { runBrowserTests } = await import('./e2e.mjs');
        await runBrowserTests();
      }
    }
    return;
  }
  throw new Error('Task "' + action + '" is not implemented yet. No placeholder success. See tasks/todo.md.');
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { await main(process.argv[2], process.argv[3]); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
