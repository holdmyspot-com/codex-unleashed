#!/usr/bin/env node
import { execFile, spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { once } from 'node:events';
import { createReadStream } from 'node:fs';
import { lstat, mkdtemp, open, readdir, rename, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

const run = promisify(execFile);
const OPTIONS = ['repo', 'release-id', 'tag', 'expected-assets', 'output'];

function parseArguments(args) {
  const values = Object.create(null);
  for (let index = 0; index < args.length; index += 2) {
    const name = args[index].slice(2);
    const value = args[index + 1];
    if (!args[index].startsWith('--') || !OPTIONS.includes(name) || Object.hasOwn(values, name) ||
        !value || value.startsWith('--')) {
      throw new Error(`Invalid option: ${args[index]}`);
    }
    values[name] = value;
  }
  for (const name of OPTIONS) {
    if (!Object.hasOwn(values, name)) throw new Error(`Missing --${name}`);
  }
  return {
    repo: values.repo, releaseId: values['release-id'], tag: values.tag,
    expectedAssets: values['expected-assets'], output: values.output
  };
}

async function sha256(filename) {
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(filename)) hash.update(chunk);
  return `sha256:${hash.digest('hex')}`;
}

async function expectAbsent(filename) {
  try {
    await lstat(filename);
  } catch (error) {
    if (error.code === 'ENOENT') return;
    throw error;
  }
  throw new Error(`Output already exists: ${filename}`);
}

const github = {
  async metadata(endpoint) {
    const { stdout } = await run('gh', ['api', endpoint], { maxBuffer: 4 * 1024 * 1024 });
    return JSON.parse(stdout);
  },

  async download(endpoint, filename) {
    const file = await open(filename, 'wx');
    try {
      const child = spawn('gh', ['api', '-H', 'Accept: application/octet-stream', endpoint], {
        stdio: ['ignore', file.fd, 'inherit']
      });
      const [status, signal] = await once(child, 'close');
      if (status !== 0) throw new Error(`Asset download failed (${signal ?? status}): ${endpoint}`);
    } finally {
      await file.close();
    }
  }
};

/** Reads back the exact draft inventory and exposes the output directory after verification. */
export async function readbackDraftAssets({ repo, releaseId, tag, expectedAssets, output }, api = github) {
  if (!/^[A-Za-z0-9-]+\/(?!\.{1,2}$)[A-Za-z0-9_.-]+$/.test(repo) || !/^[1-9][0-9]*$/.test(releaseId) ||
      !/^rust-v[0-9]+\.[0-9]+\.[0-9]+\+[1-9][0-9]*$/.test(tag)) {
    throw new Error('Invalid repository, release ID or release tag');
  }
  const destination = path.resolve(output);
  await expectAbsent(destination);
  const endpoint = `repos/${repo}/releases`;
  const release = await api.metadata(`${endpoint}/${releaseId}`);
  if (!Number.isSafeInteger(release?.id) || String(release.id) !== releaseId ||
      release.draft !== true || release.tag_name !== tag) {
    throw new Error('The supplied ID does not identify the expected draft release');
  }

  const expected = new Map();
  for (const entry of await readdir(expectedAssets, { withFileTypes: true })) {
    if (!entry.isFile() || !/^[A-Za-z0-9][A-Za-z0-9._+-]*$/.test(entry.name)) {
      throw new Error(`Invalid expected release asset: ${entry.name}`);
    }
    expected.set(entry.name, await sha256(path.join(expectedAssets, entry.name)));
  }
  if (expected.size === 0 || !Array.isArray(release.assets) || release.assets.length !== expected.size) {
    throw new Error('Draft asset inventory differs from the expected release');
  }

  const assets = new Map();
  const assetIds = new Set();
  for (const asset of release.assets) {
    if (asset?.state !== 'uploaded' || !expected.has(asset.name) || assets.has(asset.name) ||
        !Number.isSafeInteger(asset.id) || asset.id < 1 || assetIds.has(asset.id)) {
      throw new Error('Draft contains an unexpected or duplicate asset');
    }
    if (expected.get(asset.name) !== asset.digest) throw new Error(`Draft digest differs: ${asset.name}`);
    assets.set(asset.name, asset.id);
    assetIds.add(asset.id);
  }

  const staging = await mkdtemp(path.join(path.dirname(destination), '.draft-assets-'));
  try {
    for (const [name, id] of assets) {
      const filename = path.join(staging, name);
      await api.download(`${endpoint}/assets/${id}`, filename);
      if (await sha256(filename) !== expected.get(name)) throw new Error(`Downloaded digest differs: ${name}`);
    }
    await expectAbsent(destination);
    await rename(staging, destination);
  } finally {
    await rm(staging, { recursive: true, force: true });
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    await readbackDraftAssets(parseArguments(process.argv.slice(2)));
  } catch (error) {
    console.error(`readback-draft-assets: ${error.message}`);
    process.exitCode = 1;
  }
}
