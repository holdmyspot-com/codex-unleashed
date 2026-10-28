const fs = require('node:fs');
const path = require('node:path');

if (process.env.STATE_storage) {
  fs.rmSync(process.env.STATE_storage, {recursive: true, force: true});
} else {
  const storage = fs.mkdtempSync(path.join(process.env.RUNNER_TEMP, 'codex-runtime-'));
  try {
    fs.appendFileSync(process.env.GITHUB_STATE, `storage=${storage}\n`);
    for (const child of ['evidence', 'target', 'cargo-home', 'tmp'])
      fs.mkdirSync(path.join(storage, child));
    fs.appendFileSync(process.env.GITHUB_ENV,
      `FOCUSED_RUNTIME=${storage}\nCARGO_TARGET_DIR=${path.join(storage, 'target')}\n` +
      `CARGO_HOME=${path.join(storage, 'cargo-home')}\nTMPDIR=${path.join(storage, 'tmp')}\n` +
      `CODEX_REPO_ROOT=${path.join(process.env.GITHUB_WORKSPACE, 'upstream')}\n`);
  } catch (error) {
    fs.rmSync(storage, {recursive: true, force: true});
    throw error;
  }
}
