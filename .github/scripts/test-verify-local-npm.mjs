/** Unit checks cover version/identity decisions; the launcher check uses a named Node fixture, not a release binary. */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { chmod, cp, lstat, mkdtemp, mkdir, writeFile, readFile, readdir, rm } from 'node:fs/promises';
import path from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
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

test('CLI version output requires the exact two-line banner and rejects vendor build prefix collisions', () => {
  const note = '(See --build-info for more information)';
  assert.throws(() => validateVersionOutput(`codex-cli 0.167.0+10\n${note}\n`, '0.167.0+1'), assert.AssertionError);
  assert.throws(() => validateVersionOutput(`unrelated 0.167.0+1\n${note}\n`, '0.167.0+1'), assert.AssertionError);
  assert.throws(() => validateVersionOutput('codex-cli 0.167.0+1\n', '0.167.0+1'), assert.AssertionError);
  assert.throws(() => validateVersionOutput(`codex-cli 0.167.0+1\n${note}\nextra output`, '0.167.0+1'), assert.AssertionError);
  assert.throws(() => validateVersionOutput('codex-cli 0.167.0+1\n(See --help for more information)\n', '0.167.0+1'),
    assert.AssertionError);
  assert.doesNotThrow(() => validateVersionOutput(`codex-cli 0.167.0+1\r\n${note}\r\n`, '0.167.0+1'));
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

/** Creates a local registry and npm fixture that installs a canonical package with the requested voice mutation. */
async function createConsumerFixture(root, mutation = 'preserve') {
  const packages = path.join(root, 'packages');
  const tools = path.join(root, 'tools');
  const npm = path.join(tools, 'npm-cli.mjs');
  const actionLog = path.join(root, 'npm-actions.log');
  const registryLog = path.join(root, 'registry-started.log');
  const version = '0.167.0+41';
  const selected = platforms.find(([, operatingSystem, cpu]) => operatingSystem === process.platform && cpu === process.arch);
  assert(selected, 'Fixture requires a supported native host');
  const [selectedTarget, selectedOperatingSystem, selectedCpu] = selected;

  await mkdir(packages);
  for (const [target, operatingSystem] of platforms) {
    const packageDirectory = path.join(packages, target);
    await mkdir(path.join(packageDirectory, 'bin'), { recursive: true });
    await mkdir(path.join(packageDirectory, 'codex-resources', 'voice'), { recursive: true });
    await mkdir(path.join(packageDirectory, 'codex-path'), { recursive: true });
    await writeFile(path.join(packageDirectory, 'codex-package.json'), JSON.stringify({
      layoutVersion: 1, version, target, variant: 'codex',
      entrypoint: operatingSystem === 'win32' ? 'bin/codex.exe' : 'bin/codex'
    }));
    await writeFile(path.join(packageDirectory, operatingSystem === 'win32' ? 'bin/codex.exe' : 'bin/codex'), 'fixture cli');
    await writeFile(path.join(packageDirectory, 'codex-resources', 'voice', 'library.bin'), `voice bytes for ${target}`);
    if (operatingSystem !== 'win32') {
      await chmod(path.join(packageDirectory, 'codex-resources', 'voice', 'library.bin'), 0o755);
    }
    await writeFile(path.join(packageDirectory, 'codex-path', 'path.txt'), target);
    if (operatingSystem !== 'win32') {
      const executable = path.join(packageDirectory, 'bin', 'codex');
      await chmod(executable, 0o755);
    }
  }

  await mkdir(path.join(tools, 'node_modules', 'verdaccio'), { recursive: true });
  await writeFile(path.join(tools, 'node_modules', 'verdaccio', 'package.json'), JSON.stringify({ name: 'verdaccio', version: '7.1.0' }));
  await writeFile(path.join(tools, 'node_modules', 'verdaccio', 'index.js'), `
const http = require('node:http');
exports.runServer = async () => {
  require('node:fs').appendFileSync(process.env.V5_REGISTRY_LOG, 'started\\n');
  return http.createServer((request, response) => {
    request.resume();
    request.on('end', () => {
      response.writeHead(201, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ token: 'fixture-token' }));
    });
  });
};
`);
  await writeFile(npm, `
import { cpSync, mkdirSync, appendFileSync, writeFileSync, rmSync, chmodSync, symlinkSync } from 'node:fs';
import path from 'node:path';
const args = process.argv.slice(2);
const action = args.find(value => ['publish', 'install', 'exec'].includes(value));
appendFileSync(process.env.V5_ACTION_LOG, action + '\\n');
if (action === 'install') {
  const nativeRoot = path.join(process.cwd(), 'node_modules', '@holdmyspot', ${JSON.stringify(`codex-unleashed-${selectedOperatingSystem}-${selectedCpu}`)}, 'vendor', ${JSON.stringify(selectedTarget)});
  mkdirSync(path.dirname(nativeRoot), { recursive: true });
  cpSync(path.join(process.env.V5_PACKAGE_ROOT, ${JSON.stringify(selectedTarget)}), nativeRoot, { recursive: true });
  const voiceLibrary = path.join(nativeRoot, 'codex-resources', 'voice', 'library.bin');
  if (process.env.V5_INSTALL_MUTATION === 'drop') rmSync(voiceLibrary);
  if (process.env.V5_INSTALL_MUTATION === 'modify') writeFileSync(voiceLibrary, 'modified voice bytes');
  if (process.env.V5_INSTALL_MUTATION === 'mode') chmodSync(voiceLibrary, 0o644);
  if (process.env.V5_INSTALL_MUTATION === 'symlink') {
    symlinkSync('library.bin', path.join(path.dirname(voiceLibrary), 'unexpected-link'));
  }
  const registryIndex = args.indexOf('--registry');
  const registry = args[registryIndex + 1];
  writeFileSync(path.join(process.cwd(), 'package-lock.json'), JSON.stringify({ packages: {
    ['node_modules/@holdmyspot/' + ${JSON.stringify(`codex-unleashed-${selectedOperatingSystem}-${selectedCpu}`)}]: {
      resolved: registry + '/' + ${JSON.stringify(`codex-unleashed-${selectedOperatingSystem}-${selectedCpu}`)} + '.tgz'
    }
  }}));
}
if (action === 'exec' && args.includes('--version')) process.stdout.write('codex-cli ' + process.env.V5_PACKAGE_VERSION + '\\n(See --build-info for more information)\\n');
`);

  return { packages, tools, npm, actionLog, registryLog, selectedTarget, version, mutation };
}

/** Runs the public npm verifier command against the fixture's local registry and npm process. */
function runConsumerVerifier(fixture, root, extraArgs = []) {
  const script = path.join(path.dirname(fileURLToPath(import.meta.url)), 'verify-local-npm.mjs');
  return spawnSync(process.execPath, [script,
    '--package-root', fixture.packages,
    '--tools', fixture.tools,
    '--npm-cli', fixture.npm,
    '--scratch-root', root,
    '--evidence-dir', path.join(root, 'evidence'),
    ...extraArgs
  ], {
    encoding: 'utf8',
    env: {
      ...process.env,
      V5_ACTION_LOG: fixture.actionLog,
      V5_REGISTRY_LOG: fixture.registryLog,
      V5_PACKAGE_ROOT: fixture.packages,
      V5_INSTALL_MUTATION: fixture.mutation,
      V5_PACKAGE_VERSION: fixture.version
    }
  });
}

test('npm install dropping a voice library is rejected before a passing result is written', async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'npm-voice-drop-fixture-'));
  try {
    const fixture = await createConsumerFixture(root, 'drop');
    const output = path.join(root, 'installed package');
    const result = runConsumerVerifier(fixture, root, ['--installed-package-output', output]);
    assert.notEqual(result.status, 0, 'the verifier accepted an installed package with a dropped voice library');
    assert.match(result.stderr, /codex-resources[\\/]voice[\\/]library\.bin/);
    assert.equal(await readFile(path.join(root, 'evidence', 'result.json')).then(() => true, () => false), false);
    await assert.rejects(lstat(output), { code: 'ENOENT' });
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('npm install modifying a voice library is rejected before a passing result is written', async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'npm-voice-modify-fixture-'));
  try {
    const fixture = await createConsumerFixture(root, 'modify');
    const output = path.join(root, 'installed package');
    const result = runConsumerVerifier(fixture, root, ['--installed-package-output', output]);
    assert.notEqual(result.status, 0, 'the verifier accepted an installed package with modified voice bytes');
    assert.match(result.stderr, /codex-resources[\\/]voice[\\/]library\.bin/);
    assert.equal(await readFile(path.join(root, 'evidence', 'result.json')).then(() => true, () => false), false);
    await assert.rejects(lstat(output), { code: 'ENOENT' });
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('npm install changing a Unix voice resource executable role is rejected', {
  skip: process.platform === 'win32' ? 'Unix executable roles are not used on Windows' : false
}, async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'npm-voice-mode-fixture-'));
  try {
    const fixture = await createConsumerFixture(root, 'mode');
    const output = path.join(root, 'installed package');
    const result = runConsumerVerifier(fixture, root, ['--installed-package-output', output]);
    assert.notEqual(result.status, 0, 'the verifier accepted a changed Unix voice resource executable role');
    assert.match(result.stderr, /executable role differs/);
    await assert.rejects(lstat(output), { code: 'ENOENT' });
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('npm install adding a voice resource symbolic link is rejected', {
  skip: process.platform === 'win32' ? 'Symbolic link creation is unavailable in this fixture on Windows' : false
}, async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'npm-voice-symlink-fixture-'));
  try {
    const fixture = await createConsumerFixture(root, 'symlink');
    const output = path.join(root, 'installed package');
    const result = runConsumerVerifier(fixture, root, ['--installed-package-output', output]);
    assert.notEqual(result.status, 0, 'the verifier accepted an installed voice resource symbolic link');
    assert.match(result.stderr, /symbolic link in voice resources/);
    await assert.rejects(lstat(output), { code: 'ENOENT' });
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('a preexisting installed package output is preserved and rejected before registry startup', async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'npm-voice-output-fixture-'));
  try {
    const fixture = await createConsumerFixture(root);
    const output = path.join(root, 'installed package');
    await mkdir(output);
    await writeFile(path.join(output, 'caller-owned.txt'), 'keep this file');
    const result = runConsumerVerifier(fixture, root, ['--installed-package-output', output]);
    assert.notEqual(result.status, 0, 'the verifier accepted a preexisting installed package output');
    assert.match(result.stderr, /already exists/);
    assert.equal(await readFile(path.join(output, 'caller-owned.txt'), 'utf8'), 'keep this file');
    assert.equal(await readFile(fixture.registryLog).then(() => true, () => false), false);
    assert.equal(await readFile(fixture.actionLog).then(() => true, () => false), false);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('verified voice bytes and the complete installed canonical package are handed off', async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'npm-voice-handoff-fixture-'));
  try {
    const fixture = await createConsumerFixture(root);
    const output = path.join(root, 'installed package');
    const result = runConsumerVerifier(fixture, root, ['--installed-package-output', output]);
    assert.equal(result.status, 0, result.stderr);
    const installedRoot = path.join(fixture.packages, fixture.selectedTarget);
    const handedOffFiles = (await readdir(output, { recursive: true })).sort();
    const installedFiles = (await readdir(installedRoot, { recursive: true })).sort();
    assert.deepEqual(handedOffFiles, installedFiles);
    assert.equal(await readFile(path.join(output, 'codex-resources', 'voice', 'library.bin'), 'utf8'),
      `voice bytes for ${fixture.selectedTarget}`);
    const sourceRole = (await lstat(path.join(installedRoot, 'codex-resources', 'voice', 'library.bin'))).mode & 0o111;
    const outputRole = (await lstat(path.join(output, 'codex-resources', 'voice', 'library.bin'))).mode & 0o111;
    assert.equal(outputRole, sourceRole);
    const resultJson = JSON.parse(await readFile(path.join(root, 'evidence', 'result.json'), 'utf8'));
    assert.equal(resultJson.voice_resources, 'verified');
    assert.equal(JSON.stringify(resultJson).includes(root), false, 'result.json persisted an absolute fixture path');
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
