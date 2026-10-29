/** Own public-key and connection storage until remote debugging finishes.
 * Main validates DEBUG_PUBLIC_KEY with OpenSSH and publishes storage and
 * key-file and shell-startup outputs. GitHub invokes post after Upterm finishes;
 * post removes only the directory recorded in this action's state.
 */
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

/**
 * Create SSH-key storage and publish storage, key-file, and Bash shell-startup outputs.
 * environment supplies DEBUG_PUBLIC_KEY, DEBUG_IDLE_MINUTES, RUNNER_TEMP,
 * GITHUB_STATE, and GITHUB_OUTPUT. The idle limit must convert to positive safe
 * integer minutes and seconds. Reject a missing or blank key; ssh-keygen must
 * accept the written key file. Record the directory for post cleanup.
 * On a validation or publication failure after creation, remove the directory
 * and rethrow. State or output publication may already have been appended.
 */
function prepare(environment) {
  const idleMinutes = Number(environment.DEBUG_IDLE_MINUTES);
  const idleSeconds = idleMinutes * 60;
  if (!Number.isSafeInteger(idleMinutes) || idleMinutes <= 0 || !Number.isSafeInteger(idleSeconds))
    throw new RangeError('The idle limit in minutes and its seconds value must be a positive safe integer');

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
      `storage=${storage}\nkey-file=${authorizedKeys}\n` +
      `shell-startup=readonly TMOUT=${idleSeconds}; export -n TMOUT; unset PROMPT_COMMAND\n`);
  } catch (error) {
    fs.rmSync(storage, { recursive: true, force: true });
    throw error;
  }
}

if (process.env.STATE_storage)
  fs.rmSync(process.env.STATE_storage, { recursive: true, force: true });
else
  prepare(process.env);
