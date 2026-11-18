#!/usr/bin/env node
/**
 * Decides whether `codex --version` output is the patched CLI's two-line banner: `codex-cli VERSION` followed by
 * `(See --build-info for more information)`. The rule mirrors codex-rs/cli/tests/build_info.rs in the patch queue and
 * PackageIdentity in the Java tooling; change all three together.
 *
 * Command line: `node codex-version-banner.mjs VERSION` reads the CLI output from standard input and exits 0 when it
 * is the banner for VERSION, 1 with a diagnostic when it is not, and 2 when VERSION is missing.
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/** The second banner line, which follows the version line. */
export const SEE_BUILD_INFO_LINE = '(See --build-info for more information)';

/** Splits the output into lines, accepting LF or CRLF separators and at most one final line terminator. */
function bannerLines(output) {
  return output.replace(/\r?\n$/, '').split(/\r?\n/);
}

/** True only when the output is exactly the two-line banner for the given package version, such as `0.167.0+1`. */
export function isVersionBanner(output, version) {
  const lines = bannerLines(output);
  return lines.length === 2 && lines[0] === `codex-cli ${version}` && lines[1] === SEE_BUILD_INFO_LINE;
}

/** Throws an AssertionError naming the expected banner when the output is not the banner for the given version. */
export function assertVersionBanner(output, version) {
  assert.ok(isVersionBanner(output, version),
    `CLI did not report the exact banner "codex-cli ${version}" then "${SEE_BUILD_INFO_LINE}"; got ${JSON.stringify(output)}`);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const version = process.argv[2];
  if (process.argv.length !== 3 || !version) {
    process.stderr.write('usage: codex-version-banner.mjs VERSION < codex-version-output\n');
    process.exit(2);
  }
  try {
    assertVersionBanner(readFileSync(0, 'utf8'), version);
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exit(1);
  }
}
