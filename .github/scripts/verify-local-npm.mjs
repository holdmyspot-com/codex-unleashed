#!/usr/bin/env node
/**
 * Assembles the retained npm family from six extracted release packages, publishes to an owned loopback Verdaccio,
 * installs in a fresh consumer and runs its CLI. Inputs are named options documented in docs/local-npm-verification.md.
 * Exits nonzero on invalid input or any failed publication, installation, identity or executable check. Removes owned
 * staging and registry storage even on failure; the caller owns the evidence directory and supplied package trees.
 */
import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { cp, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

/** The owner-selected release platforms and npm's corresponding OS/CPU selectors. */
export const platforms = [
  ['x86_64-unknown-linux-musl', 'linux', 'x64'],
  ['aarch64-unknown-linux-musl', 'linux', 'arm64'],
  ['x86_64-apple-darwin', 'darwin', 'x64'],
  ['aarch64-apple-darwin', 'darwin', 'arm64'],
  ['x86_64-pc-windows-msvc', 'win32', 'x64'],
  ['aarch64-pc-windows-msvc', 'win32', 'arm64']
];
/** The retained selector, without the excluded early-access family. */
export const packageName = '@holdmyspot/codex-unleashed';
/** Upstream's canonical native package metadata filename. */
const nativeMetadataName = 'codex-package.json';
/** npm's manifest filename. */
const npmMetadataName = 'package.json';
/** Maintained legal documents accompanying the product license in every npm package. */
const legalFiles = ['LICENSE.md', 'docs/LICENSE.html', 'docs/terms.html', 'docs/privacy.html', 'licenses'];
/** Verdaccio's supported and pinned Node API version for this consumer check. */
const verdaccioVersion = '7.1.0';

/** Converts an exact stable release plus optional numeric vendor build to npm's ordered prerelease version. */
export function npmVersion(version) {
  if (typeof version !== 'string' || !/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(\+(0|[1-9]\d*))?$/.test(version)) {
    throw new TypeError(`Expected a stable package version with an optional numeric vendor build, got ${version}`);
  }
  return version.replace('+', '-');
}

/** Validates all six canonical Codex package identities and returns their one common release version. */
export function validatePackages(metadata) {
  assert.equal(metadata.length, platforms.length, 'Exactly six package metadata records are required');
  const version = metadata[0].version;
  npmVersion(version);
  for (const [index, [target, operatingSystem]] of platforms.entries()) {
    const entrypoint = operatingSystem === 'win32' ? 'bin/codex.exe' : 'bin/codex';
    assert.equal(metadata[index].layoutVersion, 1, `${target}: unsupported layoutVersion`);
    assert.equal(metadata[index].target, target, `${target}: wrong package target`);
    assert.equal(metadata[index].variant, 'codex', `${target}: expected the CLI package variant`);
    assert.equal(metadata[index].version, version, `${target}: package versions differ`);
    assert.equal(metadata[index].entrypoint, entrypoint, `${target}: unexpected CLI entrypoint`);
  }
  return version;
}

/** Requires the exact trimmed `codex-cli VERSION` line for the expected package version; throws on a mismatch. */
export function validateVersionOutput(output, version) {
  assert.equal(output.trim(), `codex-cli ${version}`, `Installed CLI did not report the exact package version ${version}`);
}

/** Hashes a file without loading the native binary into memory. */
async function sha256(filename) {
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(filename)) hash.update(chunk);
  return hash.digest('hex');
}

/** Runs one command without a shell and retains at most 1 MiB of combined output in its evidence log. */
async function run(command, args, cwd, env, evidenceFile) {
  const child = spawn(command, args, { cwd, env, stdio: ['ignore', 'pipe', 'pipe'] });
  const chunks = [];
  let size = 0;
  const collect = chunk => {
    size += chunk.length;
    if (size > 1024 * 1024) {
      child.kill();
      return;
    }
    chunks.push(chunk);
  };
  child.stdout.on('data', collect);
  child.stderr.on('data', collect);
  const [code, signal] = await once(child, 'close');
  const output = Buffer.concat(chunks).toString('utf8');
  await writeFile(evidenceFile, output);
  assert(size <= 1024 * 1024, `Command output exceeded 1 MiB: ${command}`);
  assert.equal(code, 0, `Command failed (${signal ?? code}): ${command} ${args.join(' ')}; see ${evidenceFile}`);
  return output;
}

/** Writes a package manifest with npm-owned field spelling. */
async function writeManifest(directory, metadata) {
  await writeFile(path.join(directory, npmMetadataName), `${JSON.stringify(metadata, null, 2)}\n`);
}

/** Creates six native packages and one selector in the supplied fresh staging directory. */
export async function assemble(packageRoot, staging, version, registry, legalRoot) {
  const dependencies = {};
  const directories = [];
  for (const [target, operatingSystem, cpu] of platforms) {
    const suffix = `${operatingSystem}-${cpu}`;
    const name = `${packageName}-${suffix}`;
    const directory = path.join(staging, suffix);
    await mkdir(directory);
    await cp(path.join(packageRoot, target), path.join(directory, 'vendor', target), { recursive: true });
    await writeManifest(directory, {
      name, version: npmVersion(version), os: [operatingSystem], cpu: [cpu], files: ['vendor', ...legalFiles],
      license: 'SEE LICENSE IN LICENSE.md', publishConfig: { registry }
    });
    for (const filename of legalFiles) {
      await cp(path.join(legalRoot, filename), path.join(directory, filename), { recursive: true });
    }
    directories.push(directory);
    dependencies[name] = npmVersion(version);
  }

  const selector = path.join(staging, 'selector');
  await mkdir(path.join(selector, 'bin'), { recursive: true });
  await cp(new URL('./local-npm-launcher.mjs', import.meta.url), path.join(selector, 'bin', 'codex.mjs'));
  await writeManifest(selector, {
    name: packageName, version: npmVersion(version), type: 'module', bin: { codex: 'bin/codex.mjs' },
    files: ['bin', ...legalFiles], optionalDependencies: dependencies, publishConfig: { registry },
    license: 'SEE LICENSE IN LICENSE.md'
  });
  for (const filename of legalFiles) {
    await cp(path.join(legalRoot, filename), path.join(selector, filename), { recursive: true });
  }
  directories.push(selector);
  return directories;
}

/** Owns one local registry's lifecycle, package publication, fresh install and actual installed executable smoke. */
async function verify(options, env, host) {
  const packageRoot = path.resolve(options['package-root']);
  const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
  const releaseMatrix = JSON.parse(await readFile(path.join(repository, '.github', 'release-matrix.json'), 'utf8'));
  assert.deepEqual(new Set(platforms.map(([target]) => target)), new Set(releaseMatrix.map(entry => entry.target)),
    'npm platforms differ from the maintained release matrix');
  const toolMetadata = JSON.parse(await readFile(path.join(path.resolve(options.tools), 'node_modules', 'verdaccio', npmMetadataName), 'utf8'));
  assert.equal(toolMetadata.version, verdaccioVersion, `Install Verdaccio ${verdaccioVersion} for this check`);
  const evidence = path.resolve(options['evidence-dir']);
  const metadata = await Promise.all(platforms.map(async ([target]) =>
    JSON.parse(await readFile(path.join(packageRoot, target, nativeMetadataName), 'utf8'))));
  const version = validatePackages(metadata);
  const selected = platforms.find(([, operatingSystem, cpu]) => operatingSystem === host.platform && cpu === host.arch);
  assert(selected, `Unsupported native consumer: ${host.platform}/${host.arch}`);
  await mkdir(evidence);
  const scratch = await mkdtemp(path.join(path.resolve(options['scratch-root']), 'local-npm-'));
  let server;
  try {
    const storage = path.join(scratch, 'storage');
    const require = createRequire(path.join(path.resolve(options.tools), npmMetadataName));
    const { runServer } = require('verdaccio');
    server = await runServer({
      storage, self_path: path.join(scratch, 'config.yaml'),
      auth: { htpasswd: { file: path.join(scratch, 'htpasswd') } },
      uplinks: {}, packages: { '@holdmyspot/*': { access: '$all', publish: '$authenticated' } },
      log: { type: 'stdout', format: 'pretty', level: 'error' },
      max_body_size: '2gb'
    });
    server.listen(0, '127.0.0.1');
    await once(server, 'listening');
    const registry = `http://127.0.0.1:${server.address().port}`;
    const response = await fetch(`${registry}/-/user/org.couchdb.user:local_verification`, {
      method: 'PUT', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ name: 'local_verification', password: randomBytes(24).toString('hex') })
    });
    assert.equal(response.status, 201, 'Local Verdaccio user creation failed');
    const { token } = await response.json();
    assert.equal(typeof token, 'string', 'Local Verdaccio did not return a token');
    const npmrc = path.join(scratch, '.npmrc');
    await writeFile(npmrc, `registry=${registry}\n@holdmyspot:registry=${registry}\n//127.0.0.1:${server.address().port}/:_authToken=${token}\n`, { mode: 0o600 });
    const commandEnv = Object.fromEntries(Object.entries(env).filter(([key]) => !/^npm_config_/i.test(key)));
    Object.assign(commandEnv, { NPM_CONFIG_USERCONFIG: npmrc, NPM_CONFIG_CACHE: path.join(scratch, 'npm-cache') });
    const npm = path.resolve(options['npm-cli']);
    const npmArgs = [npm, '--registry', registry, '--userconfig', npmrc, '--ignore-scripts', '--no-audit', '--no-fund', '--loglevel=error'];
    const staging = path.join(scratch, 'packages');
    await mkdir(staging);
    const directories = await assemble(packageRoot, staging, version, registry, repository);
    for (const [index, directory] of directories.entries()) {
      await run(host.node, [...npmArgs, 'publish', directory, '--tag', 'local-verification'], scratch, commandEnv,
        path.join(evidence, `publish-${index}.log`));
    }

    const consumer = path.join(scratch, 'consumer');
    await mkdir(consumer);
    await writeManifest(consumer, { private: true });
    await run(host.node, [...npmArgs, 'install', '--include=optional', `${packageName}@${npmVersion(version)}`],
      consumer, commandEnv, path.join(evidence, 'install.log'));
    const lock = JSON.parse(await readFile(path.join(consumer, 'package-lock.json'), 'utf8'));
    for (const installed of Object.values(lock.packages)) {
      if (installed.resolved) assert.equal(new URL(installed.resolved).origin, registry, 'Install resolved outside local Verdaccio');
    }
    const [target, operatingSystem, cpu] = selected;
    const nativeRoot = path.join(consumer, 'node_modules', `${packageName}-${operatingSystem}-${cpu}`, 'vendor', target);
    const installedBinary = path.join(nativeRoot, metadata[platforms.indexOf(selected)].entrypoint);
    const sourceBinary = path.join(packageRoot, target, metadata[platforms.indexOf(selected)].entrypoint);
    const sourceHash = await sha256(sourceBinary);
    assert.equal(await sha256(installedBinary), sourceHash, 'Installed executable differs from the selected release package');
    const consumerCommand = [...npmArgs, 'exec', '--offline', '--no', '--', 'codex'];
    const output = await run(host.node, [...consumerCommand, '--version'], consumer, commandEnv, path.join(evidence, 'version.log'));
    validateVersionOutput(output, version);
    await run(host.node, [...consumerCommand, '--help'], consumer, commandEnv, path.join(evidence, 'help.log'));
    await writeFile(path.join(evidence, 'result.json'), `${JSON.stringify({
      format_version: 1, result: 'passed', target, package_version: version, npm_version: npmVersion(version),
      native_sha256: sourceHash, packages_published: directories.length, registry: 'loopback_verdaccio'
    }, null, 2)}\n`);
  } finally {
    if (server) {
      server.closeAllConnections();
      await new Promise(resolve => server.close(resolve));
    }
    await rm(scratch, { recursive: true, force: true });
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { values } = parseArgs({ options: {
    'package-root': { type: 'string' }, tools: { type: 'string' }, 'npm-cli': { type: 'string' },
    'scratch-root': { type: 'string' }, 'evidence-dir': { type: 'string' }
  } });
  for (const name of ['package-root', 'tools', 'npm-cli', 'scratch-root', 'evidence-dir']) {
    assert(values[name], `Missing required --${name}; see docs/local-npm-verification.md`);
  }
  await verify(values, process.env, { platform: process.platform, arch: process.arch, node: process.execPath });
}
