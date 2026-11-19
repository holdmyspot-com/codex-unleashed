#!/usr/bin/env node
/**
 * Runs the patched platform installer against verified release bytes served by a loopback fixture, checks the physical
 * installation, and copies its canonical package to the requested new directory. The caller owns evidence and output.
 * Exits 2 for invalid arguments and 1 when verification or cleanup fails.
 */
import { createHash } from 'node:crypto';
import { constants as fsConstants, createReadStream, createWriteStream, appendFileSync } from 'node:fs';
import {
  access, cp, lstat, mkdir, mkdtemp, readFile, realpath, readdir, rename, rm, stat, writeFile
} from 'node:fs/promises';
import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import { pipeline } from 'node:stream/promises';
import { createServer } from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { isVersionBanner } from './codex-version-banner.mjs';

/** The only CLI options accepted by the unpublished installer verification operation. */
const REQUIRED_ARGUMENTS = Object.freeze([
  'upstream-root', 'assets', 'target', 'package-version', 'scratch-root', 'installed-package-output', 'evidence-dir'
]);

/** Application package targets supported by the maintained release matrix. */
const APP_TARGETS = new Set([
  'x86_64-unknown-linux-musl', 'aarch64-unknown-linux-musl',
  'x86_64-apple-darwin', 'aarch64-apple-darwin',
  'x86_64-pc-windows-msvc', 'aarch64-pc-windows-msvc'
]);

/** Maps each native runner identity to the only application package it can install. */
const HOST_TARGETS = Object.freeze({
  'linux/x64': 'x86_64-unknown-linux-musl',
  'linux/arm64': 'aarch64-unknown-linux-musl',
  'darwin/x64': 'x86_64-apple-darwin',
  'darwin/arm64': 'aarch64-apple-darwin',
  'win32/x64': 'x86_64-pc-windows-msvc',
  'win32/arm64': 'aarch64-pc-windows-msvc'
});

/** Exact public repository origins whose pinned release paths the unchanged installers request. */
const RELEASE_ORIGINS = Object.freeze({
  metadata: 'https://api.github.com',
  assets: 'https://github.com'
});

/** The one voice tree whose installed bytes must match the verified package archive. */
const VOICE_TREE = 'codex-resources/voice';

/** Raises an argument error that the CLI maps to status 2. */
class UsageError extends Error {}

/** Raises an installation or verification error that the CLI maps to status 1. */
class VerificationError extends Error {}

/** Parses the exact named-option contract and rejects duplicate, missing, empty, and unknown values. */
function parseArguments(argumentsList) {
  const values = Object.create(null);
  for (let index = 0; index < argumentsList.length; index += 1) {
    const token = argumentsList[index];
    const equals = token.indexOf('=');
    const option = equals < 0 ? token.slice(2) : token.slice(2, equals);
    const isLongOption = token.startsWith('--') && token.length > 2;
    if (!isLongOption || !REQUIRED_ARGUMENTS.includes(option)) {
      throw new UsageError(`Unknown or positional argument: ${token}`);
    }
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
  return values;
}

/** Returns true when one canonical path is the same as or below another. */
function isWithin(parent, candidate) {
  const relative = path.relative(parent, candidate);
  const isSame = relative === '';
  const staysBelow = relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative);
  return isSame || staysBelow;
}

/** Throws when a caller path is not absolute, including the option name needed to correct it. */
function requireAbsolute(option, value) {
  if (!path.isAbsolute(value)) throw new UsageError(`--${option} must be an absolute path: ${value}`);
}

/** Checks that a caller-owned destination does not exist, including as a dangling symbolic link. */
async function requireAbsent(filename, option) {
  try {
    await lstat(filename);
  } catch (error) {
    if (error.code === 'ENOENT') return;
    throw error;
  }
  throw new VerificationError(`--${option} must name a new path; it already exists: ${filename}`);
}

/** Resolves an existing physical directory and reports the option whose input is invalid. */
async function physicalDirectory(filename, option) {
  const resolved = await realpath(filename);
  const details = await stat(resolved);
  if (!details.isDirectory()) throw new VerificationError(`--${option} must name a directory: ${filename}`);
  return resolved;
}

/** Requires an input to be a nonsymlink regular file and returns its physical path. */
async function physicalFile(filename, option) {
  const details = await lstat(filename);
  if (details.isSymbolicLink() || !details.isFile()) {
    throw new VerificationError(`--${option} must name a nonsymlink regular file: ${filename}`);
  }
  return realpath(filename);
}

/** Streams a file into SHA-256 without loading archive bytes into memory. */
async function sha256File(filename) {
  const digest = createHash('sha256');
  for await (const chunk of createReadStream(filename)) digest.update(chunk);
  return digest.digest('hex');
}

/** Reads the SHA256SUMS entry accepted by the upstream installer for one exact asset name. */
function checksumForAsset(contents, assetName) {
  const matchingDigests = [];
  for (const line of contents.split(/\r?\n/)) {
    const firstWhitespace = line.search(/[\t ]/);
    if (firstWhitespace < 0) continue;
    const digest = line.slice(0, firstWhitespace);
    const filename = line.slice(firstWhitespace).trim();
    const isDigest = /^[a-fA-F0-9]{64}$/.test(digest);
    if (filename === assetName && isDigest) matchingDigests.push(digest.toLowerCase());
  }
  if (matchingDigests.length !== 1) {
    throw new VerificationError(`SHA256SUMS must contain exactly one valid digest for ${assetName}`);
  }
  return matchingDigests[0];
}

/** Finds one executable by searching the supplied child PATH before any transport facade is added. */
async function findExecutable(name, environment, platform) {
  const searchPath = environment.PATH ?? '';
  const suffixes = platform === 'win32' ? (environment.PATHEXT ?? '.COM;.EXE;.BAT;.CMD').split(';') : [''];
  for (const directory of searchPath.split(path.delimiter)) {
    if (directory.length === 0) continue;
    const searchDirectory = directory;
    for (const suffix of suffixes) {
      const candidate = path.resolve(searchDirectory, `${name}${suffix}`);
      try {
        await access(candidate, platform === 'win32' ? undefined : fsConstants.X_OK);
        const physical = await realpath(candidate);
        const details = await stat(physical);
        if (details.isFile()) return physical;
      } catch (error) {
        if (error.code !== 'ENOENT' && error.code !== 'EACCES' && error.code !== 'ENOTDIR') throw error;
      }
    }
  }
  throw new VerificationError(`Required executable is absent from the supplied PATH: ${name}`);
}

/** Creates a native asset selection from the validated package identity. */
function createAssetSelection(target, packageVersion) {
  const archiveName = `codex-package-${target}.tar.gz`;
  const tag = `rust-v${packageVersion}`;
  const metadataUri = `${RELEASE_ORIGINS.metadata}/repos/holdmyspot-com/codex-unleashed/releases/tags/${tag}`;
  const downloadBase = `${RELEASE_ORIGINS.assets}/holdmyspot-com/codex-unleashed/releases/download/${tag}`;
  return {
    archiveName,
    checksumName: 'SHA256SUMS',
    tag,
    metadataUri,
    archiveUri: `${downloadBase}/${archiveName}`,
    checksumUri: `${downloadBase}/SHA256SUMS`
  };
}

/** Normalizes percent-encoded URI bytes while preserving path separators and identity-bearing text. */
/** Appends one scoped transport decision to the caller-owned JSONL evidence file. */
function recordTransport(logPath, kind, originalUri, mappedUri, result) {
  appendFileSync(logPath, `${JSON.stringify({
    transport: kind, original_uri: originalUri, mapped_uri: mappedUri, result
  })}\n`);
}

/** Writes an executable Node facade that maps the installer's original curl request to loopback. */
async function writeCurlFacade(directory, realCurl, port, routes, logPath, nodeExecutable) {
  const facade = path.join(directory, 'curl');
  const routeData = JSON.stringify(routes.map(route => ({
    originalUri: route.originalUri, localPath: route.localPath
  })));
  const source = `#!/usr/bin/env node
const { appendFileSync } = require('node:fs');
const { spawnSync } = require('node:child_process');
const routes = ${JSON.stringify(routeData)};
const port = ${JSON.stringify(String(port))};
const logPath = ${JSON.stringify(logPath)};
const realCurl = ${JSON.stringify(realCurl)};
const routeList = JSON.parse(routes);
function normalizePercentEncoding(uri) {
  return uri.replace(/%([a-fA-F0-9]{2})/g, (encoded, hex) => {
    const byte = Number.parseInt(hex, 16);
    const character = String.fromCharCode(byte);
    const delimiter = '/?#%'.includes(character);
    if (byte < 0x80 && !delimiter) return character;
    return '%' + hex.toUpperCase();
  });
}
const args = process.argv.slice(2);
const urlPositions = [];
for (let index = 0; index < args.length; index += 1) {
  if (args[index] === '--url' && args[index + 1] !== undefined) {
    urlPositions.push(index + 1);
    index += 1;
  } else if (args[index].startsWith('--url=')) {
    urlPositions.push(index);
  } else if (args[index].startsWith('https://')) {
    urlPositions.push(index);
  }
}
if (urlPositions.length !== 1) {
  process.stderr.write('Expected exactly one HTTPS release URI in curl arguments\\n');
  process.exit(22);
}
const position = urlPositions[0];
const originalUri = args[position].startsWith('--url=') ? args[position].slice('--url='.length) : args[position];
let parsed;
try { parsed = new URL(originalUri); } catch { parsed = undefined; }
const hasExpectedScheme = parsed && parsed.protocol === 'https:';
const hasCredentials = parsed && (parsed.username.length > 0 || parsed.password.length > 0);
const hasPort = parsed && parsed.port.length > 0;
const route = hasExpectedScheme && !hasCredentials && !hasPort
  ? routeList.find(candidate => normalizePercentEncoding(candidate.originalUri) === normalizePercentEncoding(originalUri))
  : undefined;
if (route === undefined) {
  appendFileSync(logPath, JSON.stringify({ transport: 'curl', original_uri: originalUri, mapped_uri: null, result: 'rejected' }) + '\\n');
  process.stderr.write('Rejected unlisted installer URI: ' + originalUri + '\\n');
  process.exit(22);
}
const mappedUri = 'http://127.0.0.1:' + port + route.localPath;
appendFileSync(logPath, JSON.stringify({ transport: 'curl', original_uri: originalUri, mapped_uri: mappedUri, result: 'mapped' }) + '\\n');
const replacement = args[position].startsWith('--url=') ? '--url=' + mappedUri : mappedUri;
args[position] = replacement;
const result = spawnSync(realCurl, args, { stdio: 'inherit', windowsHide: true });
if (result.error) {
  process.stderr.write(String(result.error.message) + '\\n');
  process.exit(127);
}
process.exit(result.status === null ? 128 : result.status);
`;
  await writeFile(facade, source, { mode: 0o755, flag: 'wx' });
  await access(nodeExecutable, fsConstants.X_OK);
  return facade;
}

/** Writes the Windows wrapper that maps only the admitted cmdlet URIs and restores PATH in finally. */
async function writePowerShellWrapper(filename, installer, routes) {
  const routeData = JSON.stringify(routes.map(route => ({ original_uri: route.originalUri, local_path: route.localPath })));
  const script = String.raw`
$ErrorActionPreference = 'Stop'
$routes = @(${psQuote(routeData)} | ConvertFrom-Json)
$pathWasSet = Test-Path Env:PATH
$originalPath = $env:PATH

function Normalize-UriEncoding([string]$Value) {
    return [regex]::Replace($Value, '%([a-fA-F0-9]{2})', {
        param($Match)
        $byte = [Convert]::ToInt32($Match.Groups[1].Value, 16)
        $character = [char]$byte
        $delimiter = '/?#%'.IndexOf($character) -ge 0
        if (($byte -lt 128) -and (-not $delimiter)) { return [string]$character }
        return '%' + $Match.Groups[1].Value.ToUpperInvariant()
    })
}

function Map-InstallerUri([string]$Uri, [string]$Transport) {
    $parsed = $null
    $isAbsoluteHttps = [System.Uri]::TryCreate($Uri, [System.UriKind]::Absolute, [ref]$parsed) -and
        ($parsed.Scheme -ceq 'https') -and ($parsed.UserInfo.Length -eq 0) -and ($parsed.Port -eq 443)
    $route = $null
    if ($isAbsoluteHttps) {
        $normalized = Normalize-UriEncoding $Uri
        foreach ($candidate in $routes) {
            if ([string]::Equals((Normalize-UriEncoding $candidate.original_uri), $normalized, [System.StringComparison]::Ordinal)) {
                $route = $candidate
                break
            }
        }
    }
    if ($null -eq $route) {
        $record = @{ transport = $Transport; original_uri = $Uri; mapped_uri = $null; result = 'rejected' } |
            ConvertTo-Json -Compress
        Add-Content -LiteralPath $env:VOICE_TRANSPORT_LOG -Value $record -Encoding UTF8
        throw "Rejected unlisted installer URI: $Uri"
    }
    $mappedUri = "http://127.0.0.1:$env:VOICE_TRANSPORT_PORT$($route.local_path)"
    $record = @{ transport = $Transport; original_uri = $Uri; mapped_uri = $mappedUri; result = 'mapped' } |
        ConvertTo-Json -Compress
    Add-Content -LiteralPath $env:VOICE_TRANSPORT_LOG -Value $record -Encoding UTF8
    return $mappedUri
}

function Invoke-RestMethod {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Uri)
    $forwarded = @{}
    foreach ($entry in $PSBoundParameters.GetEnumerator()) { $forwarded[$entry.Key] = $entry.Value }
    $forwarded.Uri = Map-InstallerUri $Uri 'invoke_rest_method'
    Microsoft.PowerShell.Utility\Invoke-RestMethod @forwarded
}

function Invoke-WebRequest {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [string]$OutFile,
        [int]$TimeoutSec,
        [switch]$UseBasicParsing
    )
    $forwarded = @{}
    foreach ($entry in $PSBoundParameters.GetEnumerator()) { $forwarded[$entry.Key] = $entry.Value }
    $forwarded.Uri = Map-InstallerUri $Uri 'invoke_web_request'
    Microsoft.PowerShell.Utility\Invoke-WebRequest @forwarded
}

try {
    & ${psQuote(installer)}
} finally {
    if ($pathWasSet) { $env:PATH = $originalPath } else { Remove-Item Env:PATH -ErrorAction SilentlyContinue }
}
`;
  await writeFile(filename, script, { flag: 'wx' });
}

/** Quotes one string as a PowerShell single-quoted literal. */
function psQuote(value) {
  return `'${value.replaceAll("'", "''")}'`;
}

/** Streams a command's stdout and stderr to separate evidence files and waits for its exit status. */
async function runCommand(command, argumentsList, options) {
  if (options.abortSignal?.aborted) throw new VerificationError(`${options.commandName} was interrupted before it started`);
  const child = spawn(command, argumentsList, {
    cwd: options.cwd,
    env: options.environment,
    stdio: ['ignore', 'pipe', 'pipe'],
    detached: options.platform !== 'win32',
    windowsHide: true
  });
  options.onChild(child);
  let spawnError;
  child.on('error', error => { spawnError = error; });
  const close = new Promise(resolve => child.once('close', (code, signal) => resolve({ code, signal })));
  const stdout = pipeline(child.stdout, createWriteStream(options.stdoutPath, { flags: 'wx' }));
  const stderr = pipeline(child.stderr, createWriteStream(options.stderrPath, { flags: 'wx' }));
  const abort = () => terminateChild(child, options.platform);
  options.abortSignal?.addEventListener('abort', abort, { once: true });

  let status;
  try {
    [status] = await Promise.all([close, stdout, stderr]);
  } catch (error) {
    await terminateChild(child, options.platform);
    await close;
    await Promise.allSettled([stdout, stderr]);
    throw error;
  } finally {
    options.abortSignal?.removeEventListener('abort', abort);
    options.onChild(null);
  }

  const statusFile = `${options.statusPath}`;
  await writeFile(statusFile, `${JSON.stringify({
    command: options.commandName, exit_code: status.code, signal: status.signal,
    spawn_error: spawnError?.code ?? null
  }, null, 2)}\n`, { flag: 'wx' });
  if (spawnError) throw new VerificationError(`Could not start ${options.commandName} (${spawnError.code}); see ${options.stderrPath}`);
  if (status.code !== 0) {
    const outcome = status.signal === null ? `exit status ${status.code}` : `signal ${status.signal}`;
    throw new VerificationError(`${options.commandName} failed with ${outcome}; stdout: ${options.stdoutPath}; stderr: ${options.stderrPath}`);
  }
  if (options.abortSignal?.aborted) throw new VerificationError(`${options.commandName} was interrupted`);
  return status;
}

/** Stops the owned command process, including its Unix process group or Windows child tree. */
async function terminateChild(child, platform) {
  if (child === null || child.pid === undefined || child.exitCode !== null || child.signalCode !== null) return;
  if (platform === 'win32') {
    spawnSync('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore', windowsHide: true });
    child.kill();
    return;
  }
  try {
    process.kill(-child.pid, 'SIGTERM');
  } catch (error) {
    if (error.code !== 'ESRCH') child.kill('SIGTERM');
  }
}

/**
 * Runs the real archive extractor to create the independent package copy used for byte comparison.
 *
 * GNU tar reads an archive operand of the form {@code name:path} as {@code host:path} and tries to open a remote
 * archive, which is what a Windows drive path such as {@code C:\work\package.tar.gz} looks like. The archive is
 * therefore named by its bare filename from the directory that holds it, a form that GNU tar, bsdtar and Windows tar
 * all read as a local file. The destination follows {@code -C}, which tar never reads as a remote name.
 */
async function extractReferencePackage(archivePath, destination, evidence, environment, runtime, abortSignal) {
  const tar = await findExecutable('tar', environment, runtime.platform);
  await mkdir(destination);
  return runCommand(tar, ['-xzf', path.basename(archivePath), '-C', destination], {
    cwd: path.dirname(archivePath),
    environment,
    platform: runtime.platform,
    abortSignal,
    commandName: 'reference package extraction',
    stdoutPath: path.join(evidence, 'reference-extract.stdout.log'),
    stderrPath: path.join(evidence, 'reference-extract.stderr.log'),
    statusPath: path.join(evidence, 'reference-extract.status.json'),
    onChild: runtime.onChild
  });
}

/** Parses the canonical package metadata and checks the selected release identity. */
async function validatePackageMetadata(packageRoot, target, packageVersion) {
  const metadataPath = await physicalFile(path.join(packageRoot, 'codex-package.json'), 'package metadata');
  let metadata;
  try {
    metadata = JSON.parse(await readFile(metadataPath, 'utf8'));
  } catch (error) {
    throw new VerificationError(`Package metadata is not valid JSON: ${error.message}`);
  }
  const isObject = metadata !== null && typeof metadata === 'object' && !Array.isArray(metadata);
  if (!isObject) throw new VerificationError('Package metadata must be a JSON object');
  const expectedEntrypoint = target.endsWith('-msvc') ? 'bin/codex.exe' : 'bin/codex';
  if (metadata.layoutVersion !== 1 || metadata.variant !== 'codex' || metadata.target !== target ||
      metadata.version !== packageVersion || metadata.entrypoint !== expectedEntrypoint) {
    throw new VerificationError(`Package metadata does not match target ${target} and version ${packageVersion}`);
  }
  const entrypoint = await physicalFile(path.join(packageRoot, expectedEntrypoint), 'package entrypoint');
  const binaryMode = await stat(entrypoint);
  if (!target.endsWith('-msvc') && (binaryMode.mode & 0o111) === 0) {
    throw new VerificationError(`Package entrypoint is not executable: ${expectedEntrypoint}`);
  }
  return { metadata, entrypoint };
}

/** Adds the target's three exact upstream request identities and their local-only routes. */
function createRoutes(selection, port) {
  return [
    { originalUri: selection.metadataUri, localPath: `/api/repos/holdmyspot-com/codex-unleashed/releases/tags/${selection.tag}` },
    { originalUri: selection.checksumUri, localPath: `/releases/download/${selection.tag}/${selection.checksumName}` },
    { originalUri: selection.archiveUri, localPath: `/releases/download/${selection.tag}/${selection.archiveName}` }
  ].map(route => ({ ...route, mappedUri: `http://127.0.0.1:${port}${route.localPath}` }));
}

/** Streams the selected read-only asset to the loopback response. */
function sendAsset(response, filename) {
  response.writeHead(200, { 'content-type': 'application/octet-stream' });
  createReadStream(filename).on('error', () => response.destroy()).pipe(response);
}

/** Starts the only HTTP server the installer child may contact. */
async function startAssetServer(selection, routes, assets, evidence, checksums, archiveDigest) {
  const server = createServer((request, response) => {
    const requestUri = new URL(request.url, 'http://127.0.0.1');
    let status = 200;
    const isGet = request.method === 'GET';
    const route = routes.find(candidate => candidate.localPath === requestUri.pathname);
    if (!isGet || route === undefined || requestUri.search.length > 0 || requestUri.hash.length > 0) {
      status = 404;
      response.writeHead(status, { 'content-type': 'text/plain; charset=utf-8' });
      response.end('Not found\n');
    } else if (route.originalUri === selection.metadataUri) {
      const metadata = {
        tag_name: selection.tag,
        assets: [
          {
            name: selection.checksumName, browser_download_url: selection.checksumUri,
            digest: `sha256:${checksums.digest}`
          },
          {
            name: selection.archiveName, browser_download_url: selection.archiveUri,
            digest: `sha256:${archiveDigest}`
          }
        ]
      };
      response.writeHead(status, { 'content-type': 'application/json; charset=utf-8' });
      response.end(`${JSON.stringify(metadata)}\n`);
    } else {
      const filename = route.originalUri === selection.checksumUri ? checksums.path : assets.archivePath;
      sendAsset(response, filename);
    }
    appendFileSync(path.join(evidence, 'http-requests.jsonl'), `${JSON.stringify({
      method: request.method, path: requestUri.pathname, status
    })}\n`);
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  return { server, port: server.address().port };
}

/** Closes the local server and its remaining connections. */
async function closeAssetServer(server) {
  if (server === null) return;
  const closed = new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  server.closeAllConnections?.();
  await closed;
}

/** Hashes every physical regular file under a voice tree and records Unix executable roles. */
async function inventoryVoiceTree(packageRoot, platform) {
  const voiceRoot = path.join(packageRoot, VOICE_TREE);
  const rootInfo = await lstat(voiceRoot);
  if (!rootInfo.isDirectory() || rootInfo.isSymbolicLink()) {
    throw new VerificationError(`Voice resources must be a physical directory: ${VOICE_TREE}`);
  }
  const inventory = new Map();

  async function visit(directory, relativeDirectory) {
    const entries = await readdir(directory, { withFileTypes: true });
    entries.sort((left, right) => left.name.localeCompare(right.name, 'en'));
    for (const entry of entries) {
      const filename = path.join(directory, entry.name);
      const relativePath = relativeDirectory ? `${relativeDirectory}/${entry.name}` : entry.name;
      const details = await lstat(filename);
      if (details.isDirectory() && !details.isSymbolicLink()) {
        await visit(filename, relativePath);
      } else if (details.isFile() && !details.isSymbolicLink()) {
        inventory.set(relativePath, {
          size: details.size,
          sha256: await sha256File(filename),
          executable: platform === 'win32' ? null : (details.mode & 0o111) !== 0
        });
      } else {
        throw new VerificationError(`Voice resources contain a symlink or special file: ${relativePath}`);
      }
    }
  }
  await visit(voiceRoot, '');
  if (inventory.size === 0) throw new VerificationError('The package contains no voice resource files');
  return inventory;
}

/** Requires identical voice paths, bytes, and Unix executable roles in the two physical packages. */
async function verifyVoiceResources(referenceRoot, installedRoot, platform) {
  const reference = await inventoryVoiceTree(referenceRoot, platform);
  const installed = await inventoryVoiceTree(installedRoot, platform);
  const samePaths = reference.size === installed.size && [...reference.keys()].every(filename => installed.has(filename));
  if (!samePaths) throw new VerificationError('Installed voice resource paths differ from the verified package');
  for (const [filename, expected] of reference) {
    const actual = installed.get(filename);
    if (actual.size !== expected.size || actual.sha256 !== expected.sha256 || actual.executable !== expected.executable) {
      throw new VerificationError(`Installed voice resource differs from the verified package: ${filename}`);
    }
  }
}

/** Resolves the installed current pointer and proves its physical release remains inside this invocation's home. */
async function resolveInstalledPackage(codexHome, version, target) {
  const releasesDirectory = await realpath(path.join(codexHome, 'packages', 'standalone', 'releases'));
  const current = await realpath(path.join(codexHome, 'packages', 'standalone', 'current'));
  const isOwnedRelease = isWithin(releasesDirectory, current) && current !== releasesDirectory;
  const expectedReleaseName = `${version}-${target}`;
  if (!isOwnedRelease || path.basename(current) !== expectedReleaseName) {
    throw new VerificationError(`Installed current package is not ${expectedReleaseName} below the invocation-owned releases directory`);
  }
  return current;
}

/** Checks canonical CLI output and retains the actual executable's streams and status. */
async function verifyInstalledCli(entrypoint, packageRoot, version, environment, runtime, evidence, abortSignal) {
  const status = await runCommand(entrypoint, ['--version'], {
    cwd: packageRoot,
    environment,
    platform: runtime.platform,
    abortSignal,
    commandName: 'installed Codex CLI version check',
    stdoutPath: path.join(evidence, 'codex-version.stdout.log'),
    stderrPath: path.join(evidence, 'codex-version.stderr.log'),
    statusPath: path.join(evidence, 'codex-version.status.json'),
    onChild: runtime.onChild
  });
  const output = await readFile(path.join(evidence, 'codex-version.stdout.log'), 'utf8');
  if (!isVersionBanner(output, version)) {
    throw new VerificationError(`Installed Codex CLI did not report the exact version banner for ${version}; see ${path.join(evidence, 'codex-version.stdout.log')}`);
  }
  return status;
}

/** Copies a complete package into an exclusively created destination without replacing existing entries. */
async function copyCanonicalPackage(source, destination) {
  const entries = await readdir(source);
  for (const entry of entries) {
    await cp(path.join(source, entry), path.join(destination, entry), {
      recursive: true, force: false, errorOnExist: true, dereference: false
    });
  }
}

/** Atomically writes the passing JSON result after every child and cleanup step has succeeded. */
async function writePassingResult(evidence, result) {
  const temporary = path.join(evidence, '.result.json.tmp');
  const destination = path.join(evidence, 'result.json');
  await writeFile(temporary, `${JSON.stringify(result, null, 2)}\n`, { flag: 'wx' });
  await rename(temporary, destination);
}

/** Runs the unpublished installer and validates the installed package before handing it to the caller. */
async function verify(options, inheritedEnvironment, runtime) {
  const requestedTarget = options.target;
  const packageVersion = options['package-version'];
  if (!APP_TARGETS.has(requestedTarget)) throw new UsageError(`Unsupported target: ${requestedTarget}`);
  const versionIsCanonical = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(\+(0|[1-9]\d*))?$/.test(packageVersion);
  if (!versionIsCanonical) throw new UsageError(`Invalid canonical package version: ${packageVersion}`);
  const hostTarget = HOST_TARGETS[`${runtime.platform}/${runtime.arch}`];
  if (hostTarget === undefined) throw new UsageError(`Unsupported installer host: ${runtime.platform}/${runtime.arch}`);
  if (requestedTarget !== hostTarget) {
    throw new UsageError(`Target ${requestedTarget} does not match installer host target ${hostTarget}`);
  }

  for (const option of ['upstream-root', 'assets', 'scratch-root', 'installed-package-output', 'evidence-dir']) {
    requireAbsolute(option, options[option]);
  }
  const outputPath = path.resolve(options['installed-package-output']);
  const evidencePath = path.resolve(options['evidence-dir']);
  if (!outputPath.includes(' ')) {
    throw new UsageError('--installed-package-output must contain a space for the installed consumer check');
  }
  if (isWithin(outputPath, evidencePath) || isWithin(evidencePath, outputPath)) {
    throw new UsageError('--installed-package-output and --evidence-dir must be separate paths');
  }
  await requireAbsent(outputPath, 'installed-package-output');
  await requireAbsent(evidencePath, 'evidence-dir');

  const sourceRoot = await physicalDirectory(options['upstream-root'], 'upstream-root');
  const assetRoot = await physicalDirectory(options.assets, 'assets');
  const scratchRoot = await physicalDirectory(options['scratch-root'], 'scratch-root');
  const outputParent = await physicalDirectory(path.dirname(outputPath), 'installed-package-output parent');
  const evidenceParent = await physicalDirectory(path.dirname(evidencePath), 'evidence-dir parent');
  const canonicalOutput = path.join(outputParent, path.basename(outputPath));
  const canonicalEvidence = path.join(evidenceParent, path.basename(evidencePath));
  const outputOverwritesInput = isWithin(sourceRoot, canonicalOutput) || isWithin(assetRoot, canonicalOutput);
  const evidenceOverwritesInput = isWithin(sourceRoot, canonicalEvidence) || isWithin(assetRoot, canonicalEvidence);
  const scratchOverwritesInput = isWithin(sourceRoot, scratchRoot) || isWithin(assetRoot, scratchRoot);
  if (outputOverwritesInput || evidenceOverwritesInput || scratchOverwritesInput) {
    throw new UsageError('Scratch, output, and evidence paths must remain outside upstream-root and assets');
  }

  const selection = createAssetSelection(requestedTarget, packageVersion);
  await mkdir(canonicalEvidence, { recursive: false });
  const transportLog = path.join(canonicalEvidence, 'transports.jsonl');
  const controller = runtime.abortSignal;
  let scratch;
  let server;
  let activeChild;
  let outputWasCreated = false;
  let passingResult;
  let failure;
  let archivePath;
  let checksumsPath;
  let archiveDigest;
  let checksumAssetDigest;

  try {
    await writeFile(transportLog, '', { flag: 'wx' });
    archivePath = await physicalFile(path.join(assetRoot, selection.archiveName), 'assets archive');
    checksumsPath = await physicalFile(path.join(assetRoot, selection.checksumName), 'assets SHA256SUMS');
    archiveDigest = await sha256File(archivePath);
    const checksumsContents = await readFile(checksumsPath, 'utf8');
    const checksumDigest = checksumForAsset(checksumsContents, selection.archiveName);
    if (checksumDigest !== archiveDigest) {
      throw new VerificationError(`SHA256SUMS digest for ${selection.archiveName} does not match the archive`);
    }
    checksumAssetDigest = await sha256File(checksumsPath);

    scratch = await mkdtemp(path.join(scratchRoot, 'voice-installer-'));
    const referenceRoot = path.join(scratch, 'verified-primary-package');
    await extractReferencePackage(archivePath, referenceRoot, canonicalEvidence, inheritedEnvironment, {
      platform: runtime.platform,
      onChild: child => { activeChild = child; }
    }, controller);
    await validatePackageMetadata(referenceRoot, requestedTarget, packageVersion);
    await inventoryVoiceTree(referenceRoot, runtime.platform);

    const installerName = runtime.platform === 'win32' ? 'install.ps1' : 'install.sh';
    const installerPath = await physicalFile(path.join(sourceRoot, 'scripts', 'install', installerName), 'upstream installer');
    const routes = createRoutes(selection, 0);
    const { server: localServer, port } = await startAssetServer(
      selection, routes, { archivePath }, canonicalEvidence,
      { path: checksumsPath, digest: checksumAssetDigest }, archiveDigest
    );
    server = localServer;
    const activeRoutes = createRoutes(selection, port);
    const childEnvironment = { ...inheritedEnvironment };
    const codexHome = path.join(scratch, 'codex_home');
    const visibleBin = path.join(scratch, 'visible_bin');
    Object.assign(childEnvironment, {
      CODEX_RELEASE: packageVersion,
      CODEX_NON_INTERACTIVE: '1',
      CODEX_HOME: codexHome,
      CODEX_INSTALL_DIR: visibleBin,
      VOICE_TRANSPORT_PORT: String(port),
      VOICE_TRANSPORT_LOG: transportLog,
      VOICE_TRANSPORT_ROUTES: JSON.stringify(activeRoutes)
    });

    let command;
    let commandArguments;
    if (runtime.platform === 'win32') {
      const wrapperPath = path.join(scratch, 'invoke-installer.ps1');
      await writePowerShellWrapper(wrapperPath, installerPath, activeRoutes);
      command = await findExecutable('powershell.exe', childEnvironment, runtime.platform);
      commandArguments = ['-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', wrapperPath];
    } else {
      const realCurl = await findExecutable('curl', inheritedEnvironment, runtime.platform);
      const facadeDirectory = path.join(scratch, 'transport');
      await mkdir(facadeDirectory);
      const nodeExecutable = runtime.execPath;
      await writeCurlFacade(facadeDirectory, realCurl, port, activeRoutes, transportLog, nodeExecutable);
      childEnvironment.PATH = `${facadeDirectory}${path.delimiter}${path.dirname(nodeExecutable)}${path.delimiter}${inheritedEnvironment.PATH ?? ''}`;
      childEnvironment.TMPDIR = scratch;
      command = await findExecutable('bash', childEnvironment, runtime.platform);
      commandArguments = [installerPath];
    }

    const installerEnvironment = { ...childEnvironment };
    if (runtime.platform === 'win32') {
      installerEnvironment.TEMP = scratch;
      installerEnvironment.TMP = scratch;
    }
    await runCommand(command, commandArguments, {
      cwd: sourceRoot,
      environment: installerEnvironment,
      platform: runtime.platform,
      abortSignal: controller,
      commandName: 'patched Codex installer',
      stdoutPath: path.join(canonicalEvidence, 'installer.stdout.log'),
      stderrPath: path.join(canonicalEvidence, 'installer.stderr.log'),
      statusPath: path.join(canonicalEvidence, 'installer.status.json'),
      onChild: child => { activeChild = child; }
    });
    await closeAssetServer(server);
    server = null;

    const installedRoot = await resolveInstalledPackage(codexHome, packageVersion, requestedTarget);
    const installedPackage = await validatePackageMetadata(installedRoot, requestedTarget, packageVersion);
    await verifyInstalledCli(installedPackage.entrypoint, installedRoot, packageVersion, installerEnvironment, {
      platform: runtime.platform,
      onChild: child => { activeChild = child; }
    }, canonicalEvidence, controller);
    await verifyVoiceResources(referenceRoot, installedRoot, runtime.platform);

    if (controller?.aborted) throw new VerificationError('Installer verification was interrupted before package handoff');
    await mkdir(canonicalOutput, { recursive: false });
    outputWasCreated = true;
    await copyCanonicalPackage(installedRoot, canonicalOutput);
    await validatePackageMetadata(canonicalOutput, requestedTarget, packageVersion);
    await verifyVoiceResources(referenceRoot, canonicalOutput, runtime.platform);
    passingResult = {
      format_version: 1,
      result: 'passed',
      target: requestedTarget,
      package_version: packageVersion,
      archive_sha256: archiveDigest,
      voice_resources: 'verified'
    };
  } catch (error) {
    failure = error;
  }

  const cleanupFailures = [];
  if (activeChild !== undefined && activeChild !== null) {
    try {
      await terminateChild(activeChild, runtime.platform);
      await once(activeChild, 'close');
    } catch (error) {
      cleanupFailures.push(`installer child cleanup failed: ${error.message}`);
    }
  }
  if (server !== undefined && server !== null) {
    try {
      await closeAssetServer(server);
    } catch (error) {
      cleanupFailures.push(`loopback server cleanup failed: ${error.message}`);
    }
  }
  if (scratch !== undefined) {
    try {
      await rm(scratch, { recursive: true, force: true });
    } catch (error) {
      cleanupFailures.push(`driver scratch cleanup failed: ${error.message}`);
    }
  }

  if (failure !== undefined || cleanupFailures.length > 0) {
    if (outputWasCreated) await rm(canonicalOutput, { recursive: true, force: true });
    const detail = [failure?.message, ...cleanupFailures].filter(Boolean).join('; ');
    await writeFile(path.join(canonicalEvidence, 'failure.txt'), `${detail}\n`, { flag: 'wx' });
    throw new VerificationError(detail);
  }

  if (controller?.aborted) {
    if (outputWasCreated) await rm(canonicalOutput, { recursive: true, force: true });
    await writeFile(path.join(canonicalEvidence, 'failure.txt'), 'Installer verification was interrupted before result emission\n', { flag: 'wx' });
    throw new VerificationError('Installer verification was interrupted before result emission');
  }

  try {
    await writePassingResult(canonicalEvidence, passingResult);
  } catch (error) {
    if (outputWasCreated) await rm(canonicalOutput, { recursive: true, force: true });
    await writeFile(path.join(canonicalEvidence, 'failure.txt'), `Could not write passing result: ${error.message}\n`, { flag: 'wx' });
    throw new VerificationError(`Could not write passing result: ${error.message}`);
  }
  return passingResult;
}

/** Runs the CLI operation with process state collected once at this boundary. */
async function main(argumentsList, environment, runtime) {
  const options = parseArguments(argumentsList);
  const result = await verify(options, environment, runtime);
  process.stdout.write(`voice_resources verified for ${result.target} package ${result.package_version}\n`);
}

const isCommand = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isCommand) {
  const abortController = new AbortController();
  const handleInterrupt = signal => abortController.abort(signal);
  const onInterrupt = () => handleInterrupt('SIGINT');
  const onTerminate = () => handleInterrupt('SIGTERM');
  process.once('SIGINT', onInterrupt);
  process.once('SIGTERM', onTerminate);
  try {
    await main(process.argv.slice(2), process.env, {
      platform: process.platform,
      arch: process.arch,
      execPath: process.execPath,
      abortSignal: abortController.signal,
      onChild: () => {}
    });
  } catch (error) {
    const exitCode = error instanceof UsageError ? 2 : 1;
    process.stderr.write(`verify-voice-installer: ${error.message}\n`);
    process.exitCode = exitCode;
  } finally {
    process.removeListener('SIGINT', onInterrupt);
    process.removeListener('SIGTERM', onTerminate);
  }
}
