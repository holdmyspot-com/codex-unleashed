/** Checks the one rule that decides whether a `codex --version` output is the patched CLI's two-line banner. */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { SEE_BUILD_INFO_LINE, assertVersionBanner, isVersionBanner } from './codex-version-banner.mjs';

const MODULE_PATH = fileURLToPath(new URL('./codex-version-banner.mjs', import.meta.url));
const VERSION = '0.167.0+1';
const BANNER = `codex-cli ${VERSION}\n${SEE_BUILD_INFO_LINE}\n`;

test('the patched CLI banner is accepted with its final newline, without it, or with CRLF line endings', () => {
  assert.equal(SEE_BUILD_INFO_LINE, '(See --build-info for more information)');
  for (const output of [BANNER, BANNER.trimEnd(), BANNER.replaceAll('\n', '\r\n')]) {
    assert.equal(isVersionBanner(output, VERSION), true, JSON.stringify(output));
    assert.doesNotThrow(() => assertVersionBanner(output, VERSION));
  }
});

test('anything other than the exact two lines for the exact version is rejected', () => {
  const rejected = [
    `codex-cli ${VERSION}\n`,
    '',
    `codex-cli 0.167.0+10\n${SEE_BUILD_INFO_LINE}\n`,
    `codex-cli 0.167.0\n${SEE_BUILD_INFO_LINE}\n`,
    `codex-cli ${VERSION}\n(See --help for more information)\n`,
    `codex-cli ${VERSION}\n${SEE_BUILD_INFO_LINE} \n`,
    `codex-cli ${VERSION}\n${SEE_BUILD_INFO_LINE}\nextra output\n`,
    `codex-cli ${VERSION}\n\n${SEE_BUILD_INFO_LINE}\n`,
    `${SEE_BUILD_INFO_LINE}\ncodex-cli ${VERSION}\n`,
    `unrelated ${VERSION}\n${SEE_BUILD_INFO_LINE}\n`,
    `codex-cli ${VERSION} \n${SEE_BUILD_INFO_LINE}\n`,
    ` ${BANNER}`,
    `${BANNER}\n`
  ];
  for (const output of rejected) {
    assert.equal(isVersionBanner(output, VERSION), false, JSON.stringify(output));
    assert.throws(() => assertVersionBanner(output, VERSION), assert.AssertionError);
  }
});

test('the command line form reads standard input and reports the verdict through its exit status', () => {
  const accepted = spawnSync(process.execPath, [MODULE_PATH, VERSION], { input: BANNER, encoding: 'utf8' });
  assert.equal(accepted.status, 0, accepted.stderr);
  const wrong = spawnSync(process.execPath, [MODULE_PATH, VERSION], { input: `codex-cli ${VERSION}\n`, encoding: 'utf8' });
  assert.equal(wrong.status, 1);
  assert.match(wrong.stderr, /codex-cli 0\.167\.0\+1/);
  const usage = spawnSync(process.execPath, [MODULE_PATH], { input: BANNER, encoding: 'utf8' });
  assert.equal(usage.status, 2);
});
