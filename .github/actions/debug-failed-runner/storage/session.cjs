/** Own public-key and connection storage until remote debugging finishes.
 * Main validates DEBUG_PUBLIC_KEY with OpenSSH and publishes storage and
 * key-file outputs. GitHub invokes post after the later Upterm action finishes;
 * post removes only the directory recorded in this action's state.
 */
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

/** Validate one authorized-key file and publish owned paths as step outputs. */
function prepare(environment) {
  const publicKey = environment.DEBUG_PUBLIC_KEY;
  if (!publicKey || publicKey.trim() === '')
    throw new Error('A public SSH key is required to start runner debugging');
  const storage = fs.mkdtempSync(path.join(environment.RUNNER_TEMP, 'runner-debug-'));
  try {
    const authorizedKeys = path.join(storage, 'authorized_keys');
    fs.writeFileSync(authorizedKeys, `${publicKey.trim()}\n`, { mode: 0o600 });
    execFileSync('ssh-keygen', ['-lf', authorizedKeys], { stdio: 'pipe' });
    fs.appendFileSync(environment.GITHUB_STATE, `storage=${storage}\n`);
    fs.appendFileSync(environment.GITHUB_OUTPUT,
      `storage=${storage}\nkey-file=${authorizedKeys}\n`);
  } catch (error) {
    fs.rmSync(storage, { recursive: true, force: true });
    throw error;
  }
}

if (process.env.STATE_storage)
  fs.rmSync(process.env.STATE_storage, { recursive: true, force: true });
else
  prepare(process.env);
