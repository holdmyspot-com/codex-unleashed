/** Keep the compiler diagnostic directory until later action posts finish.
 * Main publishes SCHEDULER_COMPILER and records cleanup ownership. Post removes
 * only that directory, including on a failed probe or debug session.
 */
const fs = require('node:fs');
const path = require('node:path');

function createDiagnosticStorage(environment) {
  const storage = fs.mkdtempSync(path.join(environment.RUNNER_TEMP, 'scheduler-compiler-'));
  try {
    fs.appendFileSync(environment.GITHUB_STATE, `storage=${storage}\n`);
    fs.appendFileSync(environment.GITHUB_ENV, `SCHEDULER_COMPILER=${storage}\n`);
  } catch (error) {
    fs.rmSync(storage, { recursive: true, force: true });
    throw error;
  }
}

if (process.env.STATE_storage)
  fs.rmSync(process.env.STATE_storage, { recursive: true, force: true });
else
  createDiagnosticStorage(process.env);
