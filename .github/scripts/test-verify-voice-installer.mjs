/** Exercises the public installer command with a local installer fixture and real curl/tar boundaries. */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { access, mkdir, mkdtemp, readFile, readdir, realpath, rm, stat, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

/** The package identity used to exercise vendor-build encoding in pinned release URIs. */
const PACKAGE_VERSION = '0.167.0+41';

/** The application target selected by the current native test runner. */
const HOST_TARGETS = Object.freeze({
  'linux/x64': 'x86_64-unknown-linux-musl',
  'linux/arm64': 'aarch64-unknown-linux-musl',
  'darwin/x64': 'x86_64-apple-darwin',
  'darwin/arm64': 'aarch64-apple-darwin',
  'win32/x64': 'x86_64-pc-windows-msvc',
  'win32/arm64': 'aarch64-pc-windows-msvc'
});

/** The target this machine can install using its unchanged platform installer. */
const HOST_TARGET = HOST_TARGETS[`${process.platform}/${process.arch}`];

/** The production command exercised by each fixture. */
const DRIVER_PATH = fileURLToPath(new URL('./verify-voice-installer.mjs', import.meta.url));

/** Supplies only stable system executable locations to child commands. */
function testEnvironment(root, pathPrefix = []) {
  return {
    PATH: [...pathPrefix, path.dirname(process.execPath), '/usr/bin', '/bin'].join(path.delimiter),
    HOME: path.join(root, 'fixture home'),
    TMPDIR: root,
    TEMP: root,
    TMP: root
  };
}

/** Quotes one controlled fixture value for the Bash installer. */
function shellQuote(value) {
  return `'${value.replaceAll("'", "'\\''")}'`;
}

/** Creates an unchanged-shaped shell installer fixture that reads metadata, downloads assets, and extracts the package. */
function installerFixture(target, mode) {
  const assetName = `codex-package-${target}.tar.gz`;
  const modeAction = mode === 'reject-uri'
    ? `curl -fsSL "https://api.github.com/repos/holdmyspot-com/codex-unleashed/releases/tags/rust-v0.167.0+42" -o "$tmp/unlisted.json"`
    : mode === 'fail'
      ? `printf '%s\\n' 'fixture installer failed' >&2\nexit 23`
      : '';
  const metadataUri = mode === 'encoded-percent'
    ? '"https://api.github.com/repos/holdmyspot-com/codex-unleashed/releases/tags/rust-v${CODEX_RELEASE/+/%2B}"'
    : '"https://api.github.com/repos/holdmyspot-com/codex-unleashed/releases/tags/rust-v${CODEX_RELEASE}"';
  const tamperVoice = mode === 'tamper-voice'
    ? `printf '%s\\n' 'changed by fixture installer' > "$release_dir/codex-resources/voice/runtime/runtime.json"`
    : '';
  return `#!/usr/bin/env bash
set -euo pipefail
target=${shellQuote(target)}
asset=${shellQuote(assetName)}
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
${modeAction}
metadata_uri=${metadataUri}
curl -fsSL --connect-timeout 10 --max-time 30 "$metadata_uri" -o "$tmp/release.json"
checksum_url="$(node -e 'const fs=require("node:fs"); const release=JSON.parse(fs.readFileSync(process.argv[1],"utf8")); const asset=release.assets.find(candidate => candidate.name === process.argv[3]); if (release.tag_name !== process.argv[2] || release.assets.length !== 2 || !asset || !asset.digest.startsWith("sha256:")) process.exit(1); process.stdout.write(asset.browser_download_url);' "$tmp/release.json" "rust-v$CODEX_RELEASE" 'SHA256SUMS')"
archive_url="$(node -e 'const fs=require("node:fs"); const release=JSON.parse(fs.readFileSync(process.argv[1],"utf8")); const asset=release.assets.find(candidate => candidate.name === process.argv[3]); if (release.tag_name !== process.argv[2] || release.assets.length !== 2 || !asset || !asset.digest.startsWith("sha256:")) process.exit(1); process.stdout.write(asset.browser_download_url);' "$tmp/release.json" "rust-v$CODEX_RELEASE" "$asset")"
curl -fsSL --connect-timeout 10 --max-time 30 "$checksum_url" -o "$tmp/SHA256SUMS"
curl -fsSL --connect-timeout 10 --max-time 300 "$archive_url" -o "$tmp/$asset"
node -e 'const fs=require("node:fs"); const crypto=require("node:crypto"); const release=JSON.parse(fs.readFileSync(process.argv[1],"utf8")); const sums=fs.readFileSync(process.argv[2],"utf8"); const archive=fs.readFileSync(process.argv[3]); const asset=process.argv[4]; const metadata=release.assets.find(candidate => candidate.name === asset); const digest=crypto.createHash("sha256").update(archive).digest("hex"); const line=sums.split(/\\r?\\n/).find(value => value.trim().split(/[\\t ]+/)[1] === asset); if (!metadata || metadata.digest !== "sha256:" + digest || !line || line.trim().split(/[\\t ]+/)[0].toLowerCase() !== digest) process.exit(1);' "$tmp/release.json" "$tmp/SHA256SUMS" "$tmp/$asset" "$asset"
release_dir="$CODEX_HOME/packages/standalone/releases/$CODEX_RELEASE-$target"
mkdir -p "$release_dir"
tar -xzf "$tmp/$asset" -C "$release_dir"
${tamperVoice}
ln -s "$release_dir" "$CODEX_HOME/packages/standalone/current"
mkdir -p "$CODEX_INSTALL_DIR"
ln -s "$CODEX_HOME/packages/standalone/current/bin/codex" "$CODEX_INSTALL_DIR/codex"
`;
}

/** Creates a package archive, read-only release assets, and the selected installer fixture in a fresh test directory. */
async function createFixture(options = {}) {
  const root = await mkdtemp(path.join(tmpdir(), 'voice installer v6-'));
  const sourceRoot = path.join(root, 'upstream source');
  const assetsRoot = path.join(root, 'release assets');
  const scratchRoot = path.join(root, 'runner scratch');
  const packageRoot = path.join(root, 'package source');
  const evidencePath = path.join(root, 'installer evidence');
  const outputPath = path.join(root, 'installed voice package');
  const target = options.target ?? HOST_TARGET;
  const packageVersion = options.packageVersion ?? PACKAGE_VERSION;
  const metadataVersion = options.metadataVersion ?? packageVersion;
  const metadataTarget = options.metadataTarget ?? target;
  const archiveName = `codex-package-${target}.tar.gz`;
  const archivePath = path.join(assetsRoot, archiveName);
  const checksumPath = path.join(assetsRoot, 'SHA256SUMS');
  const installPath = path.join(sourceRoot, 'scripts', 'install', 'install.sh');

  await mkdir(path.dirname(installPath), { recursive: true });
  await mkdir(assetsRoot);
  await mkdir(scratchRoot);
  await mkdir(path.join(packageRoot, 'bin'), { recursive: true });
  await mkdir(path.join(packageRoot, 'codex-path'), { recursive: true });
  await mkdir(path.join(packageRoot, 'codex-resources', 'voice', 'bin'), { recursive: true });
  await mkdir(path.join(packageRoot, 'codex-resources', 'voice', 'runtime'), { recursive: true });
  await mkdir(path.join(packageRoot, 'codex-resources', 'voice', 'lib'), { recursive: true });
  await writeFile(path.join(packageRoot, 'codex-package.json'), JSON.stringify({
    layoutVersion: 1, version: metadataVersion, target: metadataTarget, variant: 'codex',
    entrypoint: 'bin/codex', resourcesDir: 'codex-resources', pathDir: 'codex-path'
  }));
  await writeFile(path.join(packageRoot, 'bin', 'codex'), `#!/bin/sh\nprintf '%s\\n' ${shellQuote(`codex-cli ${metadataVersion}`)} ${shellQuote(options.versionSecondLine ?? '(See --build-info for more information)')}\n`, { mode: 0o755 });
  await writeFile(path.join(packageRoot, 'bin', 'codex-code-mode-host'), '#!/bin/sh\nexit 0\n', { mode: 0o755 });
  await writeFile(path.join(packageRoot, 'codex-path', 'rg'), '#!/bin/sh\nexit 0\n', { mode: 0o755 });
  await writeFile(path.join(packageRoot, 'codex-resources', 'bwrap'), '#!/bin/sh\nexit 0\n', { mode: 0o755 });
  await writeFile(path.join(packageRoot, 'codex-resources', 'voice', 'bin', 'codex-voice-host'), '#!/bin/sh\nexit 0\n', { mode: 0o755 });
  await writeFile(path.join(packageRoot, 'codex-resources', 'voice', 'runtime', 'runtime.json'), '{"schemaVersion":1,"buildCommit":"fixture"}\n');
  await writeFile(path.join(packageRoot, 'codex-resources', 'voice', 'lib', 'libvoice.so'), 'fixture voice bytes\n');
  await writeFile(path.join(packageRoot, 'codex-resources', 'voice', 'manifest.json'), '{"schemaVersion":1}\n');
  await writeFile(installPath, installerFixture(target, options.installerMode ?? 'normal'), { mode: 0o755 });

  const archiveResult = spawnSync('tar', ['-czf', archivePath, '-C', packageRoot, '.'], {
    cwd: root, env: testEnvironment(root), encoding: 'utf8'
  });
  assert.equal(archiveResult.status, 0, archiveResult.stderr);
  const archiveDigest = createHash('sha256').update(await readFile(archivePath)).digest('hex');
  const checksumDigest = options.badChecksum ? '0'.repeat(64) : archiveDigest;
  await writeFile(checksumPath, `${checksumDigest}  ${archiveName}\n`);
  return { root, sourceRoot, assetsRoot, scratchRoot, packageRoot, evidencePath, outputPath, target, packageVersion, archivePath, archiveName, archiveDigest };
}

/** Invokes the public operation with explicit paths, identity, and a controlled child environment. */
function invoke(fixture, values = {}) {
  const target = values.target ?? fixture.target;
  const packageVersion = values.packageVersion ?? fixture.packageVersion;
  const argumentsList = [
    DRIVER_PATH,
    '--upstream-root', fixture.sourceRoot,
    '--assets', fixture.assetsRoot,
    '--target', target,
    '--package-version', packageVersion,
    '--scratch-root', fixture.scratchRoot,
    '--installed-package-output', fixture.outputPath,
    '--evidence-dir', fixture.evidencePath
  ];
  const result = spawnSync(process.execPath, argumentsList, {
    cwd: fixture.root, env: testEnvironment(fixture.root, values.pathPrefix), encoding: 'utf8'
  });
  return result;
}

/** Removes one test fixture root after its assertions finish. */
async function removeFixture(fixture) {
  await rm(fixture.root, { recursive: true, force: true });
}

test('the public installer operation maps pinned URLs and hands off the complete extracted package', {
  skip: process.platform === 'win32' ? 'This fixture exercises the Unix installer and curl facade' : false
}, async () => {
  const fixture = await createFixture();
  try {
    const result = invoke(fixture);
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /voice_resources verified/);
    const receipt = JSON.parse(await readFile(path.join(fixture.evidencePath, 'result.json'), 'utf8'));
    assert.deepEqual(receipt, {
      format_version: 1,
      result: 'passed',
      target: fixture.target,
      package_version: fixture.packageVersion,
      archive_sha256: fixture.archiveDigest,
      voice_resources: 'verified'
    });
    assert.equal(await readFile(path.join(fixture.outputPath, 'codex-resources', 'voice', 'runtime', 'runtime.json'), 'utf8'),
      '{"schemaVersion":1,"buildCommit":"fixture"}\n');
    assert.equal(await readFile(path.join(fixture.outputPath, 'codex-path', 'rg'), 'utf8'), '#!/bin/sh\nexit 0\n');
    const transports = (await readFile(path.join(fixture.evidencePath, 'transports.jsonl'), 'utf8'))
      .trim().split('\n').map(line => JSON.parse(line));
    assert.equal(transports.length, 3);
    assert.equal(transports.every(entry => entry.result === 'mapped' && entry.mapped_uri.startsWith('http://127.0.0.1:')), true);
    assert.deepEqual(transports.map(entry => new URL(entry.original_uri).pathname), [
      `/repos/holdmyspot-com/codex-unleashed/releases/tags/rust-v${fixture.packageVersion}`,
      `/holdmyspot-com/codex-unleashed/releases/download/rust-v${fixture.packageVersion}/SHA256SUMS`,
      `/holdmyspot-com/codex-unleashed/releases/download/rust-v${fixture.packageVersion}/codex-package-${fixture.target}.tar.gz`
    ]);
    const installerStatus = JSON.parse(await readFile(path.join(fixture.evidencePath, 'installer.status.json'), 'utf8'));
    assert.equal(installerStatus.exit_code, 0);
    assert.equal((await readFile(path.join(fixture.evidencePath, 'installer.stderr.log'), 'utf8')).length, 0);
    assert.deepEqual(await readdir(fixture.scratchRoot), []);
  } finally {
    await removeFixture(fixture);
  }
});

test('the public installer operation normalizes percent encoding but preserves the pinned version', {
  skip: process.platform === 'win32' ? 'This fixture exercises the Unix installer and curl facade' : false
}, async () => {
  const fixture = await createFixture({ installerMode: 'encoded-percent' });
  try {
    const result = invoke(fixture);
    assert.equal(result.status, 0, result.stderr);
    const transports = (await readFile(path.join(fixture.evidencePath, 'transports.jsonl'), 'utf8'))
      .trim().split('\n').map(line => JSON.parse(line));
    assert.equal(transports[0].original_uri.endsWith('rust-v0.167.0%2B41'), true);
    assert.equal(transports[0].mapped_uri.includes('rust-v0.167.0+41'), true);
  } finally {
    await removeFixture(fixture);
  }
});

test('an unlisted release URI fails the installer and leaves no passing result or package output', {
  skip: process.platform === 'win32' ? 'This fixture exercises the Unix installer and curl facade' : false
}, async () => {
  const fixture = await createFixture({ installerMode: 'reject-uri' });
  try {
    const result = invoke(fixture);
    assert.equal(result.status, 1, result.stdout);
    assert.match(await readFile(path.join(fixture.evidencePath, 'installer.stderr.log'), 'utf8'), /unlisted installer URI/);
    await assert.rejects(readFile(path.join(fixture.evidencePath, 'result.json')));
    await assert.rejects(stat(fixture.outputPath));
    const transports = (await readFile(path.join(fixture.evidencePath, 'transports.jsonl'), 'utf8'))
      .trim().split('\n').map(line => JSON.parse(line));
    assert.equal(transports.length, 1);
    assert.equal(transports[0].result, 'rejected');
    assert.equal(transports[0].mapped_uri, null);
    assert.match(transports[0].original_uri, /rust-v0\.167\.0\+42/);
    const installerStatus = JSON.parse(await readFile(path.join(fixture.evidencePath, 'installer.status.json'), 'utf8'));
    assert.equal(installerStatus.exit_code, 22);
    assert.deepEqual(await readdir(fixture.scratchRoot), []);
  } finally {
    await removeFixture(fixture);
  }
});

test('a nonzero installer exit is retained with its real logs and status', {
  skip: process.platform === 'win32' ? 'This fixture exercises the Unix installer and curl facade' : false
}, async () => {
  const fixture = await createFixture({ installerMode: 'fail' });
  try {
    const result = invoke(fixture);
    assert.equal(result.status, 1, result.stdout);
    assert.match(result.stderr, /exit status 23/);
    assert.match(await readFile(path.join(fixture.evidencePath, 'installer.stderr.log'), 'utf8'), /fixture installer failed/);
    assert.equal(JSON.parse(await readFile(path.join(fixture.evidencePath, 'installer.status.json'), 'utf8')).exit_code, 23);
    await assert.rejects(readFile(path.join(fixture.evidencePath, 'result.json')));
    await assert.rejects(stat(fixture.outputPath));
    assert.deepEqual(await readdir(fixture.scratchRoot), []);
  } finally {
    await removeFixture(fixture);
  }
});

test('altered installed voice bytes fail before the canonical package is handed to the caller', {
  skip: process.platform === 'win32' ? 'This fixture exercises the Unix installer and curl facade' : false
}, async () => {
  const fixture = await createFixture({ installerMode: 'tamper-voice' });
  try {
    const result = invoke(fixture);
    assert.equal(result.status, 1, result.stdout);
    assert.match(result.stderr, /Installed voice resource differs/);
    assert.equal(JSON.parse(await readFile(path.join(fixture.evidencePath, 'installer.status.json'), 'utf8')).exit_code, 0);
    await assert.rejects(readFile(path.join(fixture.evidencePath, 'result.json')));
    await assert.rejects(stat(fixture.outputPath));
    assert.deepEqual(await readdir(fixture.scratchRoot), []);
  } finally {
    await removeFixture(fixture);
  }
});

test('archive, checksum, and embedded package identity mismatches do not produce a passing result', {
  skip: process.platform === 'win32' ? 'This fixture exercises the Unix installer and curl facade' : false
}, async t => {
  for (const options of [
    { badChecksum: true },
    { metadataVersion: '0.167.1+41' },
    { metadataTarget: 'aarch64-unknown-linux-musl' },
    { versionSecondLine: '(See --help for more information)' },
    { versionSecondLine: '' }
  ]) {
    await t.test(JSON.stringify(options), async () => {
      const fixture = await createFixture(options);
      try {
        const result = invoke(fixture);
        assert.equal(result.status, 1, result.stdout);
        await assert.rejects(readFile(path.join(fixture.evidencePath, 'result.json')));
        await assert.rejects(stat(fixture.outputPath));
        assert.deepEqual(await readdir(fixture.scratchRoot), []);
      } finally {
        await removeFixture(fixture);
      }
    });
  }
});

test('preexisting output and evidence paths are preserved and rejected before installer execution', {
  skip: process.platform === 'win32' ? 'This fixture exercises the Unix installer and curl facade' : false
}, async t => {
  for (const existingPath of ['output', 'evidence']) {
    await t.test(existingPath, async () => {
      const fixture = await createFixture();
      try {
        const preservedPath = existingPath === 'output' ? fixture.outputPath : fixture.evidencePath;
        await mkdir(preservedPath);
        const sentinel = path.join(preservedPath, 'sentinel.txt');
        await writeFile(sentinel, 'caller-owned\n');
        const result = invoke(fixture);
        assert.equal(result.status, 1, result.stdout);
        assert.match(result.stderr, /must name a new path/);
        assert.equal(await readFile(sentinel, 'utf8'), 'caller-owned\n');
        assert.deepEqual(await readdir(fixture.scratchRoot), []);
        if (existingPath === 'output') await assert.rejects(stat(fixture.evidencePath));
      } finally {
        await removeFixture(fixture);
      }
    });
  }
});

test('the reference extraction names its archive by bare filename so GNU tar cannot read a drive path as host:path', {
  skip: process.platform === 'win32' ? 'This fixture exercises the Unix installer and curl facade' : false
}, async () => {
  const fixture = await createFixture();
  try {
    const shimRoot = path.join(fixture.root, 'tar shim');
    const callLog = path.join(fixture.root, 'tar calls.log');
    await mkdir(shimRoot);
    await writeFile(path.join(shimRoot, 'tar'),
      `#!/bin/sh\nprintf '%s\\n' \"$PWD\" >> ${shellQuote(callLog)}\nprintf '%s\\n' \"$*\" >> ${shellQuote(callLog)}\nexec /usr/bin/tar \"$@\"\n`,
      { mode: 0o755 });
    const result = invoke(fixture, { pathPrefix: [shimRoot] });
    assert.equal(result.status, 0, result.stderr);
    const [workingDirectory, argumentsLine] = (await readFile(callLog, 'utf8')).split('\n');
    assert.equal(workingDirectory, await realpath(fixture.assetsRoot));
    assert.match(argumentsLine, new RegExp(`^-xzf ${fixture.archiveName.replaceAll('.', '\\.')} -C `));
  } finally {
    await removeFixture(fixture);
  }
});
