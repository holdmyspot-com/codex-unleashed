/**
 * Keep a retry marker until later actions, including remote debugging, finish.
 * Main writes completed-part to build-progress.txt, publishes RUNNER_DEBUG_SMOKE,
 * and records its directory for post cleanup. The remote client must append
 * incremental-retry followed by a newline. Post requires exactly those two lines
 * and attempts to remove the directory even when reading or checking fails.
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
 * Require storage/build-progress.txt to contain exactly
 * "completed-part\nincremental-retry\n" and report success.
 * A missing/unreadable file or different contents fails the action. Attempt to
 * remove storage recursively in all cases; a removal failure also propagates.
 */
function verifyRetryMarkerAndRemoveStorage(storage) {
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
  verifyRetryMarkerAndRemoveStorage(process.env.STATE_storage);
else
  createRetryMarkerStorage(process.env);
