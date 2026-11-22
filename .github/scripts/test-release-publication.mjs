import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

/**
 * Executes the workflow's publication shell with a recording gh function.
 * The test context owns cleanup; phase selects creation or draft resumption,
 * and failedUpload selects an upload whose shell status is 29 (zero means success).
 * Returns the shell result, recorded argv vectors and unchanged fixture assets.
 * This checks shell expansion and failure handling, not GitHub server behavior.
 */
function runPublication(context, phase, failedUpload, draft = false) {
  const root = mkdtempSync(join(tmpdir(), 'release publication '));
  context.after(() => rmSync(root, { recursive: true, force: true }));
  const assetsDirectory = join(root, 'assets');
  mkdirSync(assetsDirectory);
  const assetNames = ['a first.tar.gz', 'z-last.tar.zst'];
  for (const name of assetNames) {
    writeFileSync(join(assetsDirectory, name), name);
  }

  const workflow = readFileSync(new URL('../workflows/upstream-auto-release.yml', import.meta.url), 'utf8');
  const shell = ['Stage the release assets', 'Publish the staged release as a prerelease'].map(name => {
    const step = workflow.split('      - name: ' + name + '\n');
    assert.equal(step.length, 2, name + ' step must be unique');
    if (name === 'Publish the staged release as a prerelease') {
      assert.match(step[1], /^        if: inputs[.]draft_rehearsal != true && /);
      if (draft) return '';
    }
    return step[1].split('        run: |\n')[1].split('\n      - name:')[0].replace(/^          /gm, '');
  }).join('\n');
  const callLog = join(root, 'calls');
  writeFileSync(callLog, '');
  const version = '1.2.3';
  const tag = `rust-v${version}`;
  const repository = 'fixture/codex-unleashed';
  const commit = '0123456789012345678901234567890123456789';
  const script = `
RUNNER_TEMP=$1
DRAFT_REHEARSAL=${draft}
PHASE=$2
failed_upload=$3
call_log=$4
TAG=$5
NAME=$6
GITHUB_REPOSITORY=$7
GITHUB_SHA=$8
uploads=0
node() {
  [[ "$1" == .github/scripts/draft-rehearsal-completion.mjs && "$2" == start &&
     "$3" == "$RUNNER_TEMP/notes.md" ]]
}
gh() {
  printf '%s\\0' "$#" "$@" >> "$call_log"
  if [[ "$1" == release && "$2" == upload ]]; then
    uploads=$((uploads + 1))
    if [[ "$uploads" -eq "$failed_upload" ]]; then
      return 29
    fi
  fi
}
${shell}`;
  const result = spawnSync('bash', ['--noprofile', '--norc', '-c', script, 'publication', root, phase, String(failedUpload), callLog, tag, version, repository, commit], {
    encoding: 'utf8',
    timeout: 10000
  });
  assert.equal(result.error, undefined, 'publication shell must complete');

  const fields = readFileSync(callLog, 'utf8').split('\0');
  assert.equal(fields.pop(), '');
  const calls = [];
  while (fields.length > 0) {
    const argumentCount = Number(fields.shift());
    calls.push(fields.splice(0, argumentCount));
  }
  const assets = assetNames.map(name => join(assetsDirectory, name));
  for (const name of assetNames) {
    assert.equal(readFileSync(join(assetsDirectory, name), 'utf8'), name);
  }
  return { result, calls, assets, tag, repository, commit };
}

for (const phase of ['create', 'upload']) {
  test(`${phase}: uploads one asset per call in order and publishes afterward`, context => {
    const { result, calls, assets, tag, repository, commit } = runPublication(context, phase, 0);
    assert.equal(result.status, 0, result.stderr);
    const uploads = calls.filter(call => call[1] === 'upload');
    assert.equal(uploads.length, assets.length);
    for (const [index, asset] of assets.entries()) {
      assert.deepEqual(uploads[index], ['release', 'upload', tag, '-R', repository, asset, '--clobber']);
    }
    const expectedOperations = ['upload', 'upload', 'edit'];
    if (phase === 'create') {
      expectedOperations.unshift('create');
    }
    assert.deepEqual(calls.map(call => call[1]), expectedOperations);
    const edit = calls.at(-1);
    assert.deepEqual(edit.slice(0, 7), ['release', 'edit', tag, '-R', repository, '--draft=false', '--prerelease']);
    if (phase === 'create') {
      assert.deepEqual(calls[0].slice(0, 9), ['release', 'create', tag, '-R', repository, '--draft', '--prerelease', '--target', commit]);
    }
  });

  for (const failedUpload of [1, 2]) {
    test(`${phase}: upload ${failedUpload} failure preserves status and prevents publication`, context => {
      const { result, calls } = runPublication(context, phase, failedUpload);
      assert.equal(result.status, 29, result.stderr);
      assert.equal(calls.filter(call => call[1] === 'upload').length, failedUpload);
      assert.equal(calls.filter(call => call[1] === 'edit').length, 0);
    });
  }
}

test('draft resumption uploads assets without making the draft public', context => {
  const { result, calls, assets } = runPublication(context, 'upload', 0, true);
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(calls.map(call => call[1]), assets.map(() => 'upload'));
});

test('draft creation stages assets and leaves the release unpublished', context => {
  const { result, calls, assets, tag, repository, commit } = runPublication(context, 'create', 0, true);
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(calls.map(call => call[1]), ['create', ...assets.map(() => 'upload')]);
  assert.deepEqual(calls[0].slice(0, 9), ['release', 'create', tag, '-R', repository, '--draft', '--prerelease', '--target', commit]);
});

test('draft rehearsal requires the strict gates and selects all six package targets', context => {
  const root = mkdtempSync(join(tmpdir(), 'release mode '));
  context.after(() => rmSync(root, { recursive: true, force: true }));
  const output = join(root, 'output');
  const workflow = readFileSync(new URL('../workflows/upstream-auto-release.yml', import.meta.url), 'utf8');
  const mode = workflow.split('      - id: mode\n');
  assert.equal(mode.length, 2);
  const shell = mode[1].split('        run: |\n')[1].split('\n      - name:')[0].replace(/^          /gm, '');
  const env = {
    ...process.env,
    EVENT: 'workflow_dispatch',
    DRY_RUN: 'true',
    DRAFT_REHEARSAL: 'true',
    SKIP_GATES: 'false',
    PACKAGE_TARGETS: 'x86_64-unknown-linux-musl',
    PACKAGE_DRY_RUN: 'true',
    PACKAGE_FINAL_OUTPUT_CACHE: 'false',
    GITHUB_OUTPUT: output
  };
  const run = overrides => spawnSync('bash', ['--noprofile', '--norc', '-c', shell], {
    encoding: 'utf8', env: { ...env, ...overrides }, timeout: 10000
  });

  const valid = run({});
  assert.equal(valid.status, 0, valid.stderr);
  const [live, matrix] = readFileSync(output, 'utf8').trim().split('\n');
  assert.equal(live, 'live=true');
  assert.equal(JSON.parse(matrix.slice('package_matrix='.length)).length, 6);
  for (const overrides of [{ DRY_RUN: 'false' }, { SKIP_GATES: 'true' }, { PACKAGE_FINAL_OUTPUT_CACHE: 'true' }]) {
    assert.equal(run(overrides).status, 1, JSON.stringify(overrides));
  }
});
