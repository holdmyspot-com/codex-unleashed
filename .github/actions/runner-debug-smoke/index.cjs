/** Own smoke state until all later actions, including remote debugging, finish.
 * Main publishes RUNNER_DEBUG_SMOKE and records its cleanup ownership. Post
 * requires the remote client to append incremental-retry, then always removes
 * this action's directory. A missing or changed marker makes post fail.
 */
const fs = require('node:fs');
const path = require('node:path');

function createPartialBuild(environment) {
  const storage = fs.mkdtempSync(path.join(environment.RUNNER_TEMP, 'runner-debug-smoke-'));
  try {
    fs.appendFileSync(environment.GITHUB_STATE, `storage=${storage}\n`);
    fs.writeFileSync(path.join(storage, 'build-progress.txt'), 'completed-part\n');
    fs.appendFileSync(environment.GITHUB_ENV, `RUNNER_DEBUG_SMOKE=${storage}\n`);
  } catch (error) {
    fs.rmSync(storage, { recursive: true, force: true });
    throw error;
  }
}

function verifyIncrementalRetry(storage) {
  try {
    const actual = fs.readFileSync(path.join(storage, 'build-progress.txt'), 'utf8');
    if (actual !== 'completed-part\nincremental-retry\n')
      throw new Error(`Unexpected retained build state: ${JSON.stringify(actual)}`);
    console.log('Same-runner incremental retry preserved the completed part');
  } finally {
    fs.rmSync(storage, { recursive: true, force: true });
  }
}

if (process.env.STATE_storage)
  verifyIncrementalRetry(process.env.STATE_storage);
else
  createPartialBuild(process.env);
