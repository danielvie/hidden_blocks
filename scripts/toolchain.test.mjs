import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, existsSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { clearBuildOutputs, javaEnvironment, checkMavenConfiguration } from './toolchain.mjs';

test('clear deletes build outputs but preserves saved work and Git history', () => {
  const root = mkdtempSync(join(tmpdir(), 'sysml-clear-'));
  try {
    for (const dir of ['backend/sysml-adapter/target', '.local/data', '.git', 'fixtures']) {
      mkdirSync(join(root, dir), { recursive: true });
      writeFileSync(join(root, dir, 'sentinel'), 'keep');
    }
    clearBuildOutputs(root);
    assert.equal(existsSync(join(root, 'backend/sysml-adapter/target')), false);
    for (const dir of ['.local/data', '.git', 'fixtures']) assert.ok(existsSync(join(root, dir, 'sentinel')));
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('clear rejects an output under a junction into another directory', () => {
  const root = mkdtempSync(join(tmpdir(), 'sysml-clear-'));
  const other = mkdtempSync(join(tmpdir(), 'sysml-protected-'));
  try {
    mkdirSync(join(other, 'dist'));
    writeFileSync(join(other, 'dist/sentinel'), 'keep');
    symlinkSync(other, join(root, 'web'), process.platform === 'win32' ? 'junction' : 'dir');
    assert.throws(() => clearBuildOutputs(root), /symlink\/junction/);
    assert.ok(existsSync(join(other, 'dist/sentinel')));
  } finally {
    rmSync(root, { recursive: true, force: true });
    rmSync(other, { recursive: true, force: true });
  }
});

test('unsupported Maven configuration is rejected without revealing values', () => {
  const root = mkdtempSync(join(tmpdir(), 'sysml-maven-'));
  try {
    checkMavenConfiguration({}, root);
    assert.throws(() => checkMavenConfiguration({ MAVEN_OPTS: 'private-option-value' }, root),
      error => error.message.includes('MAVEN_OPTS') && !error.message.includes('private-option-value'));
    mkdirSync(join(root, '.mvn'));
    writeFileSync(join(root, '.mvn/jvm.config'), '-Xmx2g');
    assert.throws(() => checkMavenConfiguration({}, root), /jvm.config/);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('a JRE is rejected and environment selection never mutates global PATH', () => {
  assert.throws(() => javaEnvironment({ JAVA_HOME: '/does-not-exist' }), /full JDK/);
  const before = { ...process.env };
  const selected = javaEnvironment();
  assert.ok(Object.keys(before).length === Object.keys(process.env).length &&
    Object.keys(before).every(key => before[key] === process.env[key]),
    'Tool selection must not change the process environment');
  const pathKey = Object.keys(selected).find(key => key.toLowerCase() === 'path');
  assert.ok(selected[pathKey].startsWith(join(process.env.JAVA_HOME, 'bin')));
});
