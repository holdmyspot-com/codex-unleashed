/**
 * Exercises verify-app-server-package.mjs with a small Node program in place of codex-app-server and real tar and
 * checksum boundaries. The fixture executable needs a POSIX shebang, so the tests are registered on POSIX hosts only;
 * the Windows executable path is exercised by the hosted dry-run verification workflow.
 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { chmod, mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

/** The production command under test. */
const DRIVER_PATH = fileURLToPath(new URL('./verify-app-server-package.mjs', import.meta.url));

/** The package version used by every fixture. */
const VERSION = '0.167.0+41';

/** The target selected by this runner. */
const HOST_TARGETS = Object.freeze({
  'linux/x64': 'x86_64-unknown-linux-musl',
  'linux/arm64': 'aarch64-unknown-linux-musl',
  'darwin/x64': 'x86_64-apple-darwin',
  'darwin/arm64': 'aarch64-apple-darwin'
});
const TARGET = HOST_TARGETS[`${process.platform}/${process.arch}`];

/** Legal documents with distinct bytes. */
const LEGAL = Object.freeze({
  'LICENSE.md': 'license\n', 'docs/LICENSE.html': '<p>license</p>\n',
  'docs/terms.html': '<p>terms</p>\n', 'docs/privacy.html': '<p>privacy</p>\n'
});

/** A fixture server: `--help` exits 0, and each JSON line on stdin gets a result unless the mode withholds it. */
const SERVER = mode => `#!/usr/bin/env node
if (process.argv.includes('--help')) { console.log('usage: codex-app-server'); process.exit(${mode === 'bad-help' ? 3 : 0}); }
if (${JSON.stringify(mode)} === 'silent') { setInterval(() => {}, 1000); }
else {
  let buffered = '';
  process.stdin.on('data', chunk => {
    buffered += chunk;
    for (const line of buffered.split('\\n')) {
      try {
        const message = JSON.parse(line);
        const reply = ${JSON.stringify(mode)} === 'no-result'
          ? { id: message.id, error: { code: -1, message: 'no' } }
          : { id: message.id, result: { userAgent: 'fixture/${VERSION}', codexHome: process.env.CODEX_HOME } };
        process.stdout.write(JSON.stringify(reply) + '\\n');
      } catch {}
    }
    buffered = '';
  });
  process.stdin.on('end', () => process.exit(0));
}
`;

/** Creates the archive, SHA256SUMS and legal source for one scenario and returns the command arguments. */
async function fixture(overrides = {}) {
  const root = await mkdtemp(path.join(tmpdir(), 'app-server-fixture-'));
  const packageRoot = path.join(root, 'package');
  await mkdir(path.join(packageRoot, 'bin'), { recursive: true });
  const metadata = {
    layoutVersion: 1, variant: 'codex-app-server', target: TARGET, version: VERSION,
    entrypoint: 'bin/codex-app-server', ...overrides.metadata
  };
  await writeFile(path.join(packageRoot, 'codex-package.json'), JSON.stringify(metadata));
  await writeFile(path.join(packageRoot, 'bin', 'codex-app-server'), SERVER(overrides.mode ?? 'ok'));
  await chmod(path.join(packageRoot, 'bin', 'codex-app-server'), overrides.executable === false ? 0o644 : 0o755);
  const legalSource = path.join(root, 'legal source');
  for (const [relative, contents] of Object.entries(LEGAL)) {
    const packaged = path.join(packageRoot, 'codex-resources', 'legal', relative);
    const source = path.join(legalSource, relative);
    await mkdir(path.dirname(packaged), { recursive: true });
    await mkdir(path.dirname(source), { recursive: true });
    await writeFile(packaged, overrides.legalDiffers === relative ? 'changed\n' : contents);
    await writeFile(source, contents);
  }
  const assets = path.join(root, 'assets');
  await mkdir(assets);
  const archive = path.join(assets, `codex-app-server-package-${TARGET}.tar.gz`);
  const tar = spawnSync('tar', ['-czf', archive, '-C', packageRoot, '.']);
  assert.equal(tar.status, 0);
  const digest = overrides.digest ?? createHash('sha256').update(await readFile(archive)).digest('hex');
  await writeFile(path.join(assets, 'SHA256SUMS'), `${digest}  codex-app-server-package-${TARGET}.tar.gz\n`);
  const argumentsList = [
    DRIVER_PATH, '--archive', archive, '--sums', path.join(assets, 'SHA256SUMS'), '--target', TARGET,
    '--package-version', VERSION, '--legal-source', legalSource, '--install-root', path.join(root, 'install root'),
    '--evidence-dir', path.join(root, 'evidence'), '--handshake-timeout-seconds', '2'
  ];
  return { root, argumentsList, evidence: path.join(root, 'evidence'), install: path.join(root, 'install root') };
}

/** Runs the command and returns its process result. */
function run(argumentsList) {
  return spawnSync(process.execPath, argumentsList, { encoding: 'utf8' });
}

const hostTest = TARGET === undefined ? test.skip : test;

if (process.platform !== 'win32') {
  hostTest('a valid package is installed, verified, cleaned up and recorded', async () => {
    const scenario = await fixture();
    try {
      const result = run(scenario.argumentsList);
      assert.equal(result.status, 0, result.stderr);
      const record = JSON.parse(await readFile(path.join(scenario.evidence, 'result.json'), 'utf8'));
      assert.equal(record.result, 'passed');
      assert.equal(record.legal_documents, 'verified');
      assert.deepEqual(record.initialize_result_keys, ['codexHome', 'userAgent']);
      assert.deepEqual(await readdir(path.dirname(scenario.evidence)).then(names => names.filter(name => name.startsWith('app-server-scratch-'))), []);
      await assert.rejects(readdir(scenario.install), { code: 'ENOENT' });
    } finally {
      await rm(scenario.root, { recursive: true, force: true });
    }
  });

  for (const [name, overrides, message] of [
    ['a checksum mismatch', { digest: '0'.repeat(64) }, /does not match its SHA256SUMS digest/],
    ['the wrong package variant', { metadata: { variant: 'codex' } }, /not the codex-app-server package/],
    ['the wrong version', { metadata: { version: '0.167.0+42' } }, /not the codex-app-server package/],
    ['a non-executable entrypoint', { executable: false }, /not executable/],
    ['a legal document that differs from the repository', { legalDiffers: 'docs/terms.html' }, /docs\/terms\.html/],
    ['a failing --help', { mode: 'bad-help' }, /--help failed/],
    ['an initialize error', { mode: 'no-result' }, /without a result/],
    ['no initialize answer', { mode: 'silent' }, /no initialize response/]
  ]) {
    hostTest(`${name} fails verification and leaves failure evidence`, async () => {
      const scenario = await fixture(overrides);
      try {
        const result = run(scenario.argumentsList);
        assert.equal(result.status, 1, result.stderr);
        assert.match(result.stderr, message);
        assert.match(await readFile(path.join(scenario.evidence, 'failure.txt'), 'utf8'), message);
        await assert.rejects(readFile(path.join(scenario.evidence, 'result.json')), { code: 'ENOENT' });
        await assert.rejects(readdir(scenario.install), { code: 'ENOENT' });
      } finally {
        await rm(scenario.root, { recursive: true, force: true });
      }
    });
  }

  hostTest('an install root without a space is a usage error', async () => {
    const scenario = await fixture();
    try {
      const index = scenario.argumentsList.indexOf('--install-root') + 1;
      scenario.argumentsList[index] = path.join(scenario.root, 'install');
      const result = run(scenario.argumentsList);
      assert.equal(result.status, 2, result.stderr);
    } finally {
      await rm(scenario.root, { recursive: true, force: true });
    }
  });
}
