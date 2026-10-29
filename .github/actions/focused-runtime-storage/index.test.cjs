const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

/** Create an isolated action environment in a directory removed by t.after. */
function fixture(t) {
  const root = fs.mkdtempSync(path.join(process.env.TMPDIR, 'focused-storage-test-'));
  t.after(() => fs.rmSync(root, {recursive: true, force: true}));
  const source = fs.readFileSync(path.join(__dirname, 'index.cjs'), 'utf8');
  const environment = {
    RUNNER_TEMP: root,
    GITHUB_WORKSPACE: path.join(root, 'checkout'),
    GITHUB_STATE: path.join(root, 'state'),
    GITHUB_ENV: path.join(root, 'environment')
  };

  return {
    root,
    environment,
    /** Execute the action with environment overrides; propagate its failures. */
    run(overrides = {}) {
      vm.runInNewContext(source, {require, process: {env: {...environment, ...overrides}}});
    },
    /** Read the published FOCUSED_RUNTIME path; fail if the file or entry is absent. */
    storage() {
      return fs.readFileSync(environment.GITHUB_ENV, 'utf8')
        .split('\n').find(line => line.startsWith('FOCUSED_RUNTIME=')).split('=')[1];
    }
  };
}

test('successive jobs recreate storage at the same path after cleanup', t => {
  const owner = fixture(t);
  owner.run();
  const first = owner.storage();
  owner.run({STATE_storage: first});
  assert.equal(fs.existsSync(first), false);
  fs.writeFileSync(owner.environment.GITHUB_ENV, '');

  owner.run();
  const second = owner.storage();
  assert.equal(second, first, 'random directories change V8 environment inputs between workers');
  for (const child of ['evidence', 'target', 'cargo-home', 'tmp'])
    assert.equal(fs.statSync(path.join(second, child)).isDirectory(), true);
  owner.run({STATE_storage: second});
  assert.equal(fs.existsSync(second), false);
});

test('an existing storage directory is refused without changing or deleting its files', t => {
  const owner = fixture(t);
  owner.run();
  const storage = owner.storage();
  fs.writeFileSync(path.join(storage, 'owned'), 'retained build');

  assert.throws(() => owner.run(), error => error.code === 'EEXIST');
  assert.equal(fs.readFileSync(path.join(storage, 'owned'), 'utf8'), 'retained build');
  owner.run({STATE_storage: storage});
});

test('failed environment publication removes only newly owned storage', t => {
  const owner = fixture(t);
  const unrelated = path.join(owner.root, 'unrelated');
  fs.writeFileSync(unrelated, 'keep');

  assert.throws(() => owner.run({GITHUB_ENV: path.join(owner.root, 'missing', 'environment')}),
    error => error.code === 'ENOENT');
  const storage = fs.readFileSync(owner.environment.GITHUB_STATE, 'utf8').trim().slice('storage='.length);
  assert.equal(fs.existsSync(storage), false);
  assert.equal(fs.readFileSync(unrelated, 'utf8'), 'keep');
});
