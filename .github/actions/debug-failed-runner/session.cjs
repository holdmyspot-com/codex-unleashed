/** Prepare key-restricted runner debugging, then hand off a live session.
 * `prepare` validates the supplied public key with OpenSSH and creates owned
 * storage. `handoff` selects the session by the action's exact SSH command,
 * opens a ten-minute join window, and records its connection details.
 * Environment values are supplied by the composite action at this boundary.
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
    fs.appendFileSync(environment.GITHUB_OUTPUT,
      `storage=${storage}\nkey-file=${authorizedKeys}\n`);
  } catch (error) {
    fs.rmSync(storage, { recursive: true, force: true });
    throw error;
  }
}

/** Record the uniquely selected live session and its exact native wait name. */
function handoff(environment) {
  const sessions = JSON.parse(execFileSync('upterm', ['session', 'list', '-o', 'json'], { encoding: 'utf8' }));
  const matches = sessions.filter(session => session.sshCommand === environment.DEBUG_SSH_COMMAND);
  if (matches.length !== 1)
    throw new Error(`Expected one session matching the SSH command; found ${matches.length}`);
  const session = matches[0];
  if (session.access !== 'allowlist')
    throw new Error(`Expected key-restricted debugging; session access is ${session.access}`);
  execFileSync('upterm', ['session', 'set', session.name, '--join-timeout', '10m'], { stdio: 'inherit' });
  fs.writeFileSync(path.join(environment.DEBUG_STORAGE, 'connection.json'),
    `${JSON.stringify({ name: session.name, sshCommand: session.sshCommand,
      joinCommand: session.joinCommand, workspace: environment.GITHUB_WORKSPACE,
      resume: 'Exit the remote shell to continue cleanup; the original failed step remains failed.' }, null, 2)}\n`);
  fs.appendFileSync(environment.GITHUB_OUTPUT, `session-name=${session.name}\n`);
}

const operation = process.argv[2];
if (operation === 'prepare')
  prepare(process.env);
else if (operation === 'handoff')
  handoff(process.env);
else
  throw new Error(`Unknown debug session operation: ${operation}`);
