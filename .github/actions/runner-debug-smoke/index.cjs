/**
 * Keep a retry marker until later actions, including remote debugging, finish.
 * Main writes completed-part to build-progress.txt, publishes RUNNER_DEBUG_SMOKE,
 * and records its directory for post cleanup. The remote client must append
 * incremental-retry followed by a newline. Post reports an incomplete retry if
 * only the initial marker remains, and removes storage after either valid state.
 * The marker checks retained state; no compiler build runs in this script.
 */
const fs = require('node:fs');
const path = require('node:path');

/**
 * Create retry-marker storage under environment.RUNNER_TEMP, record its path in
 * environment.GITHUB_STATE, and publish RUNNER_DEBUG_SMOKE through
 * environment.GITHUB_ENV. Remove the new directory and rethrow on setup failure;
 * publication may already be partial.
 */
function createRetryMarkerStorage(environment) {
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

/**
 * Report whether storage/build-progress.txt records an incomplete or completed
 * retry. Missing/unreadable or unexpected marker contents fail the action.
 * Attempt to remove storage recursively in all cases; removal failures propagate.
 */
function verifyRetryMarkerAndRemoveStorage(storage, output = console) {
  try {
    const actual = fs.readFileSync(path.join(storage, 'build-progress.txt'), 'utf8');
    if (actual === 'completed-part\n') {
      output.log('Same-runner retry incomplete; the existing workflow failure or cancellation remains in effect');
      return;
    }
    if (actual !== 'completed-part\nincremental-retry\n')
      throw new Error(`Unexpected retained build state: ${JSON.stringify(actual)}`);
    output.log('Same-runner incremental retry preserved the completed part');
  } finally {
    fs.rmSync(storage, { recursive: true, force: true });
  }
}

function runAction(environment, output = console) {
  if (environment.STATE_storage)
    verifyRetryMarkerAndRemoveStorage(environment.STATE_storage, output);
  else
    createRetryMarkerStorage(environment);
}

if (require.main === module)
  runAction(process.env, console);

module.exports = { runAction };
