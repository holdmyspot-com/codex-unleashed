const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {execFileSync, spawnSync} = require('node:child_process');
const test = require('node:test');
const vm = require('node:vm');

/** Create an SSH key and an isolated action environment in a directory removed by t.after. */
function fixture(t) {
  const root = fs.mkdtempSync(path.join(process.env.TMPDIR, 'debug-storage-test-'));
  t.after(() => fs.rmSync(root, {recursive: true, force: true}));
  const key = path.join(root, 'client');
  execFileSync('ssh-keygen', ['-q', '-t', 'ed25519', '-N', '', '-f', key]);
  const environment = {
    DEBUG_PUBLIC_KEY: fs.readFileSync(`${key}.pub`, 'utf8'),
    DEBUG_IDLE_MINUTES: '30',
    RUNNER_TEMP: root,
    GITHUB_STATE: path.join(root, 'state'),
    GITHUB_OUTPUT: path.join(root, 'output')
  };
  const source = fs.readFileSync(path.join(__dirname, 'session.cjs'), 'utf8');
  return {
    root,
    environment,
    /** Execute the action with environment overrides; propagate its failures. */
    run(overrides = {}) {
      vm.runInNewContext(source, {require, process: {env: {...environment, ...overrides}}});
    },
    /** Read published step-output name/value pairs; fail if the file is absent. */
    output() {
      return Object.fromEntries(fs.readFileSync(environment.GITHUB_OUTPUT, 'utf8').trim()
        .split('\n').map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
    }
  };
}

test('prepared startup gives an interactive Bash a readonly idle limit without exporting it', t => {
  const owner = fixture(t);
  owner.run();
  const output = owner.output();
  const shell = spawnSync('bash', ['--noprofile', '--norc', '-i'], {
    env: {...process.env, PROMPT_COMMAND: output['shell-startup'] || '', HISTFILE: path.join(owner.root, 'history')},
    input: 'declare -p TMOUT\nbash -c \'printf "child-TMOUT=%s\\n" "${TMOUT-unset}"\'\nexit\n',
    encoding: 'utf8',
    timeout: 5000
  });
  assert.equal(shell.status, 0, shell.stderr);
  assert.match(shell.stdout, /declare -r TMOUT="1800"/);
  assert.match(shell.stdout, /child-TMOUT=unset/);
  owner.run({STATE_storage: output.storage});
  assert.equal(fs.existsSync(output.storage), false);
});

test('a one-minute idle limit is published for the hosted lifecycle diagnostic', t => {
  const owner = fixture(t);
  owner.run({DEBUG_IDLE_MINUTES: '1'});
  const output = owner.output();
  const shell = spawnSync('bash', ['--noprofile', '--norc', '-i'], {
    env: {...process.env, PROMPT_COMMAND: output['shell-startup'] || '', HISTFILE: path.join(owner.root, 'history')},
    input: 'declare -p TMOUT\nexit\n',
    encoding: 'utf8',
    timeout: 5000
  });
  assert.equal(shell.status, 0, shell.stderr);
  assert.match(shell.stdout, /declare -r TMOUT="60"/);
});

test('invalid idle limits are refused before publishing access or leaving storage', t => {
  const owner = fixture(t);
  for (const value of ['', '0', '-1', '1.5', 'Infinity', '9007199254740991', '1; exit']) {
    assert.throws(() => owner.run({DEBUG_IDLE_MINUTES: value}), /positive safe integer/);
    assert.equal(fs.existsSync(owner.environment.GITHUB_OUTPUT), false);
    assert.equal(fs.readdirSync(owner.root).some(name => name.startsWith('runner-debug-')), false);
  }
});
