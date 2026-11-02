import { spawn } from 'node:child_process';
import { mkdirSync, mkdtempSync, createWriteStream } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { serverCommand } from './run-app.mjs';

export async function runBrowserTests() {
  const root = fileURLToPath(new URL('../', import.meta.url));
  mkdirSync(join(root, '.local'), { recursive: true });
  const data = mkdtempSync(join(root, '.local/e2e-'));
  const log = createWriteStream(join(data, 'server.log'));
  const invocation = serverCommand({ port: 0, data });
  const server = spawn(invocation.command, invocation.args, { cwd: root, env: invocation.env, stdio: ['ignore', 'pipe', 'pipe'] });
  try {
    const url = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Test backend did not start in 90 seconds. See ' + data)), 90000);
      let output = '';
      server.stdout.on('data', chunk => {
        log.write(chunk); output += chunk.toString();
        const match = output.match(/SysML collaboration: (http:\/\/127\.0\.0\.1:\d+)/);
        if (match) { clearTimeout(timer); resolve(match[1]); }
      });
      server.stderr.on('data', chunk => log.write(chunk));
      server.once('error', error => { clearTimeout(timer); reject(error); });
      server.once('exit', code => { clearTimeout(timer); reject(new Error('Test backend exited: ' + code)); });
    });
    console.log('Testing isolated backend ' + url + '. Artifacts: ' + data);
    await new Promise((resolve, reject) => {
      const test = spawn(process.execPath, [join(root, 'web/node_modules/@playwright/test/cli.js'), 'test'], {
        cwd: join(root, 'web'), env: { ...process.env, E2E_BASE_URL: url, E2E_MODEL: 'connected' }, stdio: 'inherit'
      });
      test.once('error', reject);
      test.once('exit', code => code === 0 ? resolve() : reject(new Error('Browser tests failed with exit ' + code)));
    });
  } finally {
    if (server.exitCode === null) {
      const exited = new Promise(resolve => server.once('exit', resolve));
      server.kill('SIGTERM');
      await exited;
    }
    log.end();
  }
}
