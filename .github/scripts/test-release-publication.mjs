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
function runPublication(context, phase, failedUpload) {
  const root = mkdtempSync(join(tmpdir(), 'release publication '));
  context.after(() => rmSync(root, { recursive: true, force: true }));
  const assetsDirectory = join(root, 'assets');
  mkdirSync(assetsDirectory);
  const assetNames = ['a first.tar.gz', 'z-last.tar.zst'];
  for (const name of assetNames) {
    writeFileSync(join(assetsDirectory, name), name);
  }

  const workflow = readFileSync(new URL('../workflows/upstream-auto-release.yml', import.meta.url), 'utf8');
  const publicationStep = workflow.split('      - name: Publish the release as a prerelease\n');
  assert.equal(publicationStep.length, 2, 'publication step must be unique');
  const runBody = publicationStep[1].split('        run: |\n')[1].split('\n  verify-install:')[0];
  const shell = runBody.replace(/^          /gm, '');
  const callLog = join(root, 'calls');
  writeFileSync(callLog, '');
  const version = '1.2.3';
  const tag = `rust-v${version}`;
  const repository = 'fixture/codex-unleashed';
  const commit = '0123456789012345678901234567890123456789';
  const script = `
RUNNER_TEMP=$1
PHASE=$2
failed_upload=$3
call_log=$4
TAG=$5
NAME=$6
GITHUB_REPOSITORY=$7
GITHUB_SHA=$8
uploads=0
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
