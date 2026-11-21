#!/usr/bin/env node
/**
 * Installs the app-server package archive of a release the way a consumer receives it (extract the archive into a new
 * directory), then checks that the installation is the package its checksum and package metadata claim and that its
 * executable runs natively on this host. No installer installs the app-server archive; extraction is the installation.
 *
 * The checks are: the archive matches its SHA256SUMS entry; `codex-package.json` names the `codex-app-server` variant,
 * this host's target and the release version; the entrypoint is a regular executable file; the legal documents under
 * `codex-resources/legal` equal the repository's bytes; the executable's `--help` exits 0; and, started with standard
 * input and output as its transport, the executable answers a JSON-RPC `initialize` request with a result.
 *
 * Exits 2 for invalid arguments and 1 when verification or cleanup fails. The caller owns the evidence directory.
 */
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { lstat, mkdir, mkdtemp, open, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { spawn, spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/** Options that every invocation must supply. */
const REQUIRED_ARGUMENTS = Object.freeze([
  'archive', 'sums', 'target', 'package-version', 'legal-source', 'install-root', 'evidence-dir'
]);

/** Options with a default. */
const OPTIONAL_ARGUMENTS = Object.freeze({ 'handshake-timeout-seconds': '60' });

/** Maps each native runner identity to the only target whose executable it can run. */
const HOST_TARGETS = Object.freeze({
  'linux/x64': 'x86_64-unknown-linux-musl',
  'linux/arm64': 'aarch64-unknown-linux-musl',
  'darwin/x64': 'x86_64-apple-darwin',
  'darwin/arm64': 'aarch64-apple-darwin',
  'win32/x64': 'x86_64-pc-windows-msvc',
  'win32/arm64': 'aarch64-pc-windows-msvc'
});

/** Legal documents that package finalization stages, relative to the repository and to `codex-resources/legal`. */
const LEGAL_FILES = Object.freeze(['LICENSE.md', 'docs/LICENSE.html', 'docs/terms.html', 'docs/privacy.html']);

/** The longest output retained from one child stream. */
const MAXIMUM_CAPTURE_BYTES = 1024 * 1024;

/** Raises an argument error that the CLI maps to status 2. */
class UsageError extends Error {}

/** Raises a verification or cleanup error that the CLI maps to status 1. */
class VerificationError extends Error {}

/** Parses the named options and rejects duplicate, missing, empty and unknown values. */
function parseArguments(argumentsList) {
  const values = Object.create(null);
  for (let index = 0; index < argumentsList.length; index += 1) {
    const token = argumentsList[index];
    const equals = token.indexOf('=');
    const option = equals < 0 ? token.slice(2) : token.slice(2, equals);
    const known = REQUIRED_ARGUMENTS.includes(option) || Object.hasOwn(OPTIONAL_ARGUMENTS, option);
    if (!token.startsWith('--') || !known) throw new UsageError(`Unknown or positional argument: ${token}`);
    if (Object.hasOwn(values, option)) throw new UsageError(`Duplicate option: --${option}`);
    let value;
    if (equals >= 0) {
      value = token.slice(equals + 1);
    } else {
      const next = argumentsList[index + 1];
      if (next === undefined || next.startsWith('--')) throw new UsageError(`Missing value for --${option}`);
      value = next;
      index += 1;
    }
    if (value.length === 0) throw new UsageError(`Empty value for --${option}`);
    values[option] = value;
  }
  for (const option of REQUIRED_ARGUMENTS) {
    if (!Object.hasOwn(values, option)) throw new UsageError(`Missing required option --${option}`);
  }
  for (const [option, fallback] of Object.entries(OPTIONAL_ARGUMENTS)) {
    if (!Object.hasOwn(values, option)) values[option] = fallback;
  }
  return values;
}

/** Streams a file into SHA-256. */
async function sha256File(filename) {
  const digest = createHash('sha256');
  for await (const chunk of createReadStream(filename)) digest.update(chunk);
  return digest.digest('hex');
}

/** Returns the one SHA256SUMS digest for an asset name, as the release installers read it. */
function checksumForAsset(contents, assetName) {
  const digests = [];
  for (const line of contents.split(/\r?\n/)) {
    const firstWhitespace = line.search(/[\t ]/);
    if (firstWhitespace < 0) continue;
    const digest = line.slice(0, firstWhitespace);
    const filename = line.slice(firstWhitespace).trim().replace(/^\*/, '');
    if (filename === assetName && /^[a-fA-F0-9]{64}$/.test(digest)) digests.push(digest.toLowerCase());
  }
  if (digests.length !== 1) throw new VerificationError(`SHA256SUMS must contain exactly one valid digest for ${assetName}`);
  return digests[0];
}

/** Requires a nonsymlink regular file and returns its path. */
async function regularFile(filename, description) {
  let details;
  try {
    details = await lstat(filename);
  } catch (error) {
    if (error.code === 'ENOENT') throw new VerificationError(`${description} is missing: ${filename}`);
    throw error;
  }
  if (details.isSymbolicLink() || !details.isFile()) {
    throw new VerificationError(`${description} must be a nonsymlink regular file: ${filename}`);
  }
  return details;
}

/** Checks that a caller-supplied destination does not exist, including as a dangling symbolic link. */
async function requireAbsent(filename, option) {
  try {
    await lstat(filename);
  } catch (error) {
    if (error.code === 'ENOENT') return;
    throw error;
  }
  throw new VerificationError(`--${option} must name a new path; it already exists: ${filename}`);
}

/** Runs a command to completion with bounded captured output. */
function runToCompletion(command, argumentsList, options) {
  const result = spawnSync(command, argumentsList, {
    cwd: options.cwd, env: options.environment, encoding: 'utf8', windowsHide: true,
    maxBuffer: MAXIMUM_CAPTURE_BYTES, timeout: options.timeoutMilliseconds, stdio: [options.stdinFd ?? 'ignore', 'pipe', 'pipe']
  });
  return result;
}

/** Stops a child process tree. */
function terminate(child, platform) {
  if (child.pid === undefined || child.exitCode !== null || child.signalCode !== null) return;
  if (platform === 'win32') {
    spawnSync('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore', windowsHide: true });
    return;
  }
  try {
    process.kill(-child.pid, 'SIGKILL');
  } catch (error) {
    if (error.code !== 'ESRCH') child.kill('SIGKILL');
  }
}

/** Starts the executable on standard input and output and waits for the JSON-RPC result to `initialize`. */
async function initializeOverStdio(entrypoint, cwd, environment, platform, timeoutMilliseconds, evidence) {
  const child = spawn(entrypoint, [], {
    cwd, env: environment, stdio: ['pipe', 'pipe', 'pipe'], detached: platform !== 'win32', windowsHide: true
  });
  let stdout = '';
  let stderr = '';
  let spawnError;
  child.on('error', error => { spawnError = error; });
  child.stdin.on('error', () => {});
  child.stderr.on('data', chunk => { if (stderr.length < MAXIMUM_CAPTURE_BYTES) stderr += chunk; });
  const closed = new Promise(resolve => child.once('close', resolve));
  const request = {
    method: 'initialize', id: 1,
    params: { clientInfo: { name: 'codex-unleashed-install-check', title: 'Install check', version: '1' } }
  };
  let timer;
  const answer = new Promise((resolve, reject) => {
    timer = setTimeout(() => reject(new VerificationError(
      `The app-server gave no initialize response within ${timeoutMilliseconds / 1000} seconds`)), timeoutMilliseconds);
    child.once('close', () => reject(new VerificationError('The app-server exited before answering initialize')));
    child.once('error', error => reject(new VerificationError(`Could not start the app-server: ${error.code}`)));
    child.stdout.on('data', chunk => {
      if (stdout.length < MAXIMUM_CAPTURE_BYTES) stdout += chunk;
      for (const line of stdout.split('\n')) {
        let message;
        try { message = JSON.parse(line); } catch { continue; }
        if (message !== null && typeof message === 'object' && message.id === request.id) {
          if (message.result === null || typeof message.result !== 'object' || Array.isArray(message.result)) {
            reject(new VerificationError(`The app-server answered initialize without a result: ${line.slice(0, 500)}`));
          } else {
            resolve(message.result);
          }
          return;
        }
      }
    });
  });
  child.stdin.write(`${JSON.stringify(request)}\n`);
  let failure;
  let result;
  try {
    result = await answer;
  } catch (error) {
    failure = error;
  } finally {
    clearTimeout(timer);
    child.stdin.end();
    terminate(child, platform);
    await closed;
  }
  await writeFile(path.join(evidence, 'initialize.stdout.log'), stdout, { flag: 'wx' });
  await writeFile(path.join(evidence, 'initialize.stderr.log'), stderr, { flag: 'wx' });
  if (spawnError) throw new VerificationError(`Could not start the app-server: ${spawnError.code}`);
  if (failure) throw failure;
  return result;
}

/** Installs and verifies the archive, then removes the installation. Returns the passing result record. */
async function verify(options, runtime) {
  const target = options.target;
  const version = options['package-version'];
  if (!/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(\+(0|[1-9]\d*))?$/.test(version)) {
    throw new UsageError(`Invalid canonical package version: ${version}`);
  }
  const hostTarget = HOST_TARGETS[`${runtime.platform}/${runtime.arch}`];
  if (hostTarget === undefined) throw new UsageError(`Unsupported host: ${runtime.platform}/${runtime.arch}`);
  if (target !== hostTarget) throw new UsageError(`Target ${target} does not match host target ${hostTarget}`);
  const timeoutSeconds = Number(options['handshake-timeout-seconds']);
  if (!Number.isInteger(timeoutSeconds) || timeoutSeconds < 1) {
    throw new UsageError('--handshake-timeout-seconds must be a positive integer');
  }
  for (const option of ['archive', 'sums', 'legal-source', 'install-root', 'evidence-dir']) {
    if (!path.isAbsolute(options[option])) throw new UsageError(`--${option} must be an absolute path: ${options[option]}`);
  }
  const installRoot = path.resolve(options['install-root']);
  const evidenceRoot = path.resolve(options['evidence-dir']);
  if (!installRoot.includes(' ')) throw new UsageError('--install-root must contain a space');
  await requireAbsent(installRoot, 'install-root');
  await requireAbsent(evidenceRoot, 'evidence-dir');

  const archiveName = `codex-app-server-package-${target}.tar.gz`;
  if (path.basename(options.archive) !== archiveName) {
    throw new UsageError(`--archive must be named ${archiveName}`);
  }
  await mkdir(evidenceRoot, { recursive: false });
  let scratch;
  let installed = false;
  let result;
  let failure;
  try {
    await regularFile(options.archive, 'archive');
    const archiveDigest = await sha256File(options.archive);
    const expectedDigest = checksumForAsset(await readFile(options.sums, 'utf8'), archiveName);
    if (archiveDigest !== expectedDigest) throw new VerificationError(`${archiveName} does not match its SHA256SUMS digest`);

    scratch = await mkdtemp(path.join(path.dirname(evidenceRoot), 'app-server-scratch-'));
    await mkdir(installRoot, { recursive: false });
    installed = true;
    const archive = await open(options.archive, 'r');
    let extraction;
    try {
      // Stream the archive and set cwd so GNU tar cannot interpret a Windows drive letter as a remote host.
      extraction = runToCompletion('tar', ['-xzf', '-'], {
        cwd: installRoot, stdinFd: archive.fd, environment: runtime.environment, timeoutMilliseconds: 600000
      });
    } finally {
      await archive.close();
    }
    await writeFile(path.join(evidenceRoot, 'extract.log'), `${extraction.stdout ?? ''}${extraction.stderr ?? ''}`, { flag: 'wx' });
    if (extraction.error || extraction.status !== 0) {
      throw new VerificationError(`Extracting ${archiveName} failed: ${extraction.error?.message ?? `exit status ${extraction.status}`}`);
    }

    const metadataPath = path.join(installRoot, 'codex-package.json');
    await regularFile(metadataPath, 'package metadata');
    let metadata;
    try {
      metadata = JSON.parse(await readFile(metadataPath, 'utf8'));
    } catch (error) {
      throw new VerificationError(`Package metadata is not valid JSON: ${error.message}`);
    }
    const entrypointName = target.endsWith('-msvc') ? 'bin/codex-app-server.exe' : 'bin/codex-app-server';
    const isObject = metadata !== null && typeof metadata === 'object' && !Array.isArray(metadata);
    if (!isObject || metadata.layoutVersion !== 1 || metadata.variant !== 'codex-app-server' ||
        metadata.target !== target || metadata.version !== version || metadata.entrypoint !== entrypointName) {
      throw new VerificationError(
        `Package metadata is not the codex-app-server package for ${target} ${version}: ${JSON.stringify(metadata)}`);
    }
    const entrypoint = path.join(installRoot, ...entrypointName.split('/'));
    const entrypointDetails = await regularFile(entrypoint, 'app-server entrypoint');
    if (runtime.platform !== 'win32' && (entrypointDetails.mode & 0o111) === 0) {
      throw new VerificationError(`The app-server entrypoint is not executable: ${entrypointName}`);
    }

    for (const relative of LEGAL_FILES) {
      const packaged = await readFile(path.join(installRoot, 'codex-resources', 'legal', ...relative.split('/'))).catch(error => {
        if (error.code === 'ENOENT') throw new VerificationError(`Packaged legal document is missing: ${relative}`);
        throw error;
      });
      const source = await readFile(path.join(options['legal-source'], ...relative.split('/')));
      if (!packaged.equals(source)) throw new VerificationError(`Packaged legal document differs from the repository: ${relative}`);
    }

    const codexHome = path.join(scratch, 'codex home');
    await mkdir(codexHome);
    const environment = { ...runtime.environment, CODEX_HOME: codexHome };
    const help = runToCompletion(entrypoint, ['--help'], {
      cwd: installRoot, environment, timeoutMilliseconds: 60000
    });
    await writeFile(path.join(evidenceRoot, 'help.stdout.log'), help.stdout ?? '', { flag: 'wx' });
    await writeFile(path.join(evidenceRoot, 'help.stderr.log'), help.stderr ?? '', { flag: 'wx' });
    if (help.error || help.status !== 0) {
      throw new VerificationError(`codex-app-server --help failed: ${help.error?.message ?? `exit status ${help.status}`}`);
    }

    const initialized = await initializeOverStdio(entrypoint, installRoot, environment, runtime.platform,
      timeoutSeconds * 1000, evidenceRoot);
    result = {
      format_version: 1, result: 'passed', target, package_version: version, archive_sha256: archiveDigest,
      legal_documents: 'verified', initialize_result_keys: Object.keys(initialized).sort()
    };
  } catch (error) {
    failure = error;
  }

  const cleanupFailures = [];
  for (const owned of [scratch, installed ? installRoot : undefined]) {
    if (owned === undefined) continue;
    try {
      // Windows can keep the just-exited executable locked briefly after the child closes.
      await rm(owned, { recursive: true, force: true, maxRetries: 8, retryDelay: 250 });
    } catch (error) {
      cleanupFailures.push(`cleanup of ${owned} failed: ${error.message}`);
    }
  }
  if (failure !== undefined || cleanupFailures.length > 0) {
    const detail = [failure?.message, ...cleanupFailures].filter(Boolean).join('; ');
    await writeFile(path.join(evidenceRoot, 'failure.txt'), `${detail}\n`, { flag: 'wx' });
    throw new VerificationError(detail);
  }
  await writeFile(path.join(evidenceRoot, 'result.json'), `${JSON.stringify(result, null, 2)}\n`, { flag: 'wx' });
  return result;
}

/** Runs the command-line operation. */
async function main(argumentsList, runtime) {
  const options = parseArguments(argumentsList);
  const legalSource = await stat(options['legal-source']).catch(() => null);
  if (legalSource === null || !legalSource.isDirectory()) {
    throw new UsageError(`--legal-source must name a directory: ${options['legal-source']}`);
  }
  const result = await verify(options, runtime);
  process.stdout.write(`app-server package verified for ${result.target} ${result.package_version}\n`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    await main(process.argv.slice(2), { platform: process.platform, arch: process.arch, environment: process.env });
  } catch (error) {
    process.stderr.write(`verify-app-server-package: ${error.message}\n`);
    process.exitCode = error instanceof UsageError ? 2 : 1;
  }
}
