/** Unit checks cover version/identity decisions; the launcher check uses a named Node fixture, not a release binary. */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { cp, mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import { assemble, platforms, npmVersion, validatePackages, validateVersionOutput, packageName } from './verify-local-npm.mjs';

/** Supplies canonical metadata identities from upstream scripts/codex_package/layout.py's layout version 1. */
function metadata() {
  return platforms.map(([target, operatingSystem]) => ({
    layoutVersion: 1, version: '0.167.0+41', target, variant: 'codex',
    entrypoint: operatingSystem === 'win32' ? 'bin/codex.exe' : 'bin/codex'
  }));
}

test('numeric vendor builds become ordered npm prerelease versions', () => {
  assert.equal(npmVersion('0.167.0+41'), '0.167.0-41');
  assert.equal(npmVersion('0.167.0'), '0.167.0');
});

test('versions outside the stable numbered contract are rejected', () => {
  for (const version of ['00.167.0', '0.167.0+01', '0.167.0-beta', 'rust-v0.167.0', '0.167.0+41+2', null]) {
    assert.throws(() => npmVersion(version), TypeError);
  }
});

test('CLI version output requires the exact trimmed version line and rejects vendor build prefix collisions', () => {
  assert.throws(() => validateVersionOutput('codex-cli 0.167.0+10\n', '0.167.0+1'), assert.AssertionError);
  assert.throws(() => validateVersionOutput('unrelated 0.167.0+1\n', '0.167.0+1'), assert.AssertionError);
  assert.throws(() => validateVersionOutput('codex-cli 0.167.0+1\nextra output', '0.167.0+1'), assert.AssertionError);
  assert.doesNotThrow(() => validateVersionOutput('  codex-cli 0.167.0+1\r\n', '0.167.0+1'));
});

test('all native package identities must belong to the same CLI release', () => {
  assert.equal(validatePackages(metadata()), '0.167.0+41');
  for (const [key, value] of [['version', '0.167.1+41'], ['target', 'unknown'], ['variant', 'codex-app-server'],
    ['entrypoint', '../codex'], ['layoutVersion', 2]]) {
    const inputs = metadata();
    inputs[2][key] = value;
    assert.throws(() => validatePackages(inputs), assert.AssertionError);
  }
  assert.throws(() => validatePackages(metadata().slice(1)), assert.AssertionError);
});

test('assembled launcher forwards arguments and exit status through the selected Node CLI fixture',
  { skip: process.platform === 'win32' ? 'Node script fixture requires a Unix executable' : false }, async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'npm-launcher-fixture-'));
  try {
    const sources = path.join(root, 'sources');
    const staging = path.join(root, 'staging');
    await mkdir(staging);
    for (const [index, [target]] of platforms.entries()) {
      const source = path.join(sources, target);
      await mkdir(path.join(source, 'bin'), { recursive: true });
      await writeFile(path.join(source, 'codex-package.json'), JSON.stringify(metadata()[index]));
    }
    const legal = path.join(root, 'legal');
    await mkdir(legal);
    await writeFile(path.join(legal, 'LICENSE.md'), 'Fixture license');
    await mkdir(path.join(legal, 'docs'));
    await mkdir(path.join(legal, 'licenses'));
    for (const filename of ['LICENSE.html', 'terms.html', 'privacy.html']) {
      await writeFile(path.join(legal, 'docs', filename), 'Fixture legal document');
    }
    const directories = await assemble(sources, staging, '0.167.0+41', 'http://127.0.0.1:4873', legal);
    const selected = platforms.find(([, operatingSystem, cpu]) => operatingSystem === process.platform && cpu === process.arch);
    assert(selected, 'Fixture requires a supported native host');
    const [target, operatingSystem, cpu] = selected;
    const selector = directories.at(-1);
    const moduleRoot = path.join(selector, 'node_modules', `${packageName}-${operatingSystem}-${cpu}`);
    await mkdir(path.dirname(moduleRoot), { recursive: true });
    const nativePackage = directories[platforms.indexOf(selected)];
    await cp(nativePackage, moduleRoot, { recursive: true });
    const executable = path.join(moduleRoot, 'vendor', target, 'bin', operatingSystem === 'win32' ? 'codex.exe' : 'codex');
    await writeFile(executable, '#!/usr/bin/env node\nconsole.log(JSON.stringify(process.argv.slice(2))); process.exitCode = 23;\n', { mode: 0o755 });
    const result = spawnSync(process.execPath, [path.join(selector, 'bin', 'codex.mjs'), 'one argument', '$literal'], { encoding: 'utf8' });
    assert.equal(result.status, 23, result.stderr);
    assert.deepEqual(JSON.parse(result.stdout), ['one argument', '$literal']);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
