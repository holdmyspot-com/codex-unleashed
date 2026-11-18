const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const { runAction } = require('./index.cjs');

test('main setup publishes retry marker storage', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'runner-debug-smoke-setup-test-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const environment = {
    RUNNER_TEMP: root,
    GITHUB_STATE: path.join(root, 'state'),
    GITHUB_ENV: path.join(root, 'environment')
  };

  runAction(environment);

  const state = fs.readFileSync(environment.GITHUB_STATE, 'utf8');
  const storage = state.slice('storage='.length).trim();
  assert.equal(fs.readFileSync(path.join(storage, 'build-progress.txt'), 'utf8'), 'completed-part\n');
  assert.equal(fs.readFileSync(environment.GITHUB_ENV, 'utf8'), `RUNNER_DEBUG_SMOKE=${storage}\n`);
});

function makePostFixture(t, marker) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'runner-debug-smoke-test-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const storage = path.join(root, 'owned-storage');
  fs.mkdirSync(storage);
  if (marker !== undefined)
    fs.writeFileSync(path.join(storage, 'build-progress.txt'), marker);
  const messages = [];
  const output = { log: (message) => messages.push(message) };
  return { storage, messages, output };
}

test('post cleanup reports a completed retry', (t) => {
  const fixture = makePostFixture(t, 'completed-part\nincremental-retry\n');
  runAction({ STATE_storage: fixture.storage }, fixture.output);
  assert.deepEqual(fixture.messages, ['Same-runner incremental retry preserved the completed part']);
  assert.equal(fs.existsSync(fixture.storage), false);
});

test('post cleanup accepts an incomplete retry without reporting success', (t) => {
  const fixture = makePostFixture(t, 'completed-part\n');
  runAction({ STATE_storage: fixture.storage }, fixture.output);
  assert.deepEqual(fixture.messages, ['Same-runner retry incomplete; the existing workflow failure or cancellation remains in effect']);
  assert.equal(fs.existsSync(fixture.storage), false);
});

test('post cleanup rejects unexpected marker bytes and removes its storage', (t) => {
  const fixture = makePostFixture(t, 'unexpected\n');
  assert.throws(() => runAction({ STATE_storage: fixture.storage }, fixture.output), /Unexpected retained build state/);
  assert.equal(fs.existsSync(fixture.storage), false);
});

test('post cleanup rejects a missing marker and removes its storage', (t) => {
  const fixture = makePostFixture(t);
  assert.throws(() => runAction({ STATE_storage: fixture.storage }, fixture.output), { code: 'ENOENT' });
  assert.equal(fs.existsSync(fixture.storage), false);
});
