#!/usr/bin/env node
import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { appendFile, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

const run = promisify(execFile);
const WORKFLOW = '.github/workflows/upstream-auto-release.yml';
const SHA = /^[0-9a-f]{40}$/;
const DIGEST = /^[0-9a-f]{64}$/;
const ID = /^[1-9][0-9]*$/;
const VERSION = /^rust-v\d+\.\d+\.\d+$/;
const ASSET = /^[A-Za-z0-9][A-Za-z0-9._+-]*$/;
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

function marker(name, record) {
  const payload = Buffer.from(JSON.stringify(record)).toString('base64url');
  return `<!-- cp9-draft-${name}:${payload} -->`;
}

function readMarker(body, name) {
  const matches = [...(body ?? '').matchAll(new RegExp(`<!-- cp9-draft-${name}:([A-Za-z0-9_-]+) -->`, 'g'))];
  if (matches.length > 1) throw new Error(`Multiple draft ${name} records`);
  if (matches.length === 0) return null;
  return JSON.parse(Buffer.from(matches[0][1], 'base64url').toString('utf8'));
}

function requestMatches(record, request) {
  return record?.head === request.head && record.upstreamTag === request.upstreamTag &&
    record.upstreamSha === request.upstreamSha;
}

function assertDraftIdentity(release, start) {
  if (release.draft !== true || release.tag_name !== start.tag || release.target_commitish !== start.head ||
      !start.tag.startsWith(`${start.upstreamTag}+`) ||
      !ID.test(start.tag.slice(start.upstreamTag.length + 1)) || !ID.test(start.runId)) {
    throw new Error('Retained draft does not match its original rehearsal identity');
  }
}

function verifyJobs(jobs, targets, finished = false) {
  if (targets.length !== 6 || new Set(targets).size !== 6) throw new Error('Expected six native targets');
  const required = [
    'Independently verify published assets and source',
    ...targets.map(target => `Installer and npm consumer - ${target}`)
  ];
  if (finished) required.push('Record draft rehearsal completion');
  for (const name of required) {
    const matches = jobs.filter(job => job.name === name);
    if (matches.length !== 1 || matches[0].conclusion !== 'success') {
      throw new Error(`Original draft rehearsal lacks a successful job: ${name}`);
    }
  }
}

async function verifyAssets(release, completion, api) {
  const assets = new Map();
  const ids = new Set();
  for (const asset of release.assets ?? []) {
    if (typeof asset.name !== 'string' || !ASSET.test(asset.name) || assets.has(asset.name) || ids.has(asset.id) ||
        !Number.isSafeInteger(asset.id) || asset.id < 1 || asset.state !== 'uploaded' ||
        !/^sha256:[0-9a-f]{64}$/.test(asset.digest)) {
      throw new Error('Invalid or duplicate draft asset in completion check');
    }
    assets.set(asset.name, asset);
    ids.add(asset.id);
  }
  const sums = assets.get('SHA256SUMS');
  if (!DIGEST.test(completion.sumsSha) || !DIGEST.test(completion.manifestSha) ||
      sums?.digest !== `sha256:${completion.sumsSha}` ||
      assets.get('release-manifest.json')?.digest !== `sha256:${completion.manifestSha}`) {
    throw new Error('Retained draft checksum or manifest digest has changed');
  }
  const bytes = await api.asset(sums.id);
  if (sha256(bytes) !== completion.sumsSha) throw new Error('Retained draft checksum bytes have changed');
  const required = new Map();
  for (const line of bytes.toString('utf8').trimEnd().split('\n')) {
    const match = /^([0-9a-f]{64})  ([A-Za-z0-9][A-Za-z0-9._+-]*)$/.exec(line);
    if (!match || match[2] === 'SHA256SUMS' || required.has(match[2])) {
      throw new Error('Invalid or duplicate draft checksum entry');
    }
    required.set(match[2], match[1]);
  }
  if (assets.size !== required.size + 1 ||
      [...required].some(([name, digest]) => assets.get(name)?.digest !== `sha256:${digest}`)) {
    throw new Error('Retained draft assets differ from the verified checksum inventory');
  }
}

/** Checks the prior draft and its original hosted evidence before returning a no-op tag. */
export async function findCompletedDraft(request, targets, api) {
  const drafts = (await api.releases()).filter(release => release.draft === true &&
    release.target_commitish === request.head && release.tag_name.startsWith(`${request.upstreamTag}+`));
  const matching = drafts.map(release => ({ release, start: readMarker(release.body, 'started') }))
    .filter(({ start }) => requestMatches(start, request));
  if (matching.length > 1) throw new Error('Multiple draft rehearsals match this release identity');
  if (matching.length === 0) return null;
  const { release, start } = matching[0];
  assertDraftIdentity(release, start);
  if (start.runId === request.runId) return null;

  const completion = readMarker(release.body, 'completed');
  const retry = `Retry failed jobs from rehearsal run ${start.runId}`;
  if (!completion) throw new Error(`A matching draft is incomplete. ${retry}`);
  if (completion.releaseId !== String(release.id) || !requestMatches(completion, request) ||
      completion.tag !== start.tag || completion.runId !== start.runId) {
    throw new Error('Retained draft completion record differs from its original rehearsal');
  }
  const previous = await api.workflow(start.runId);
  if (String(previous.id) !== start.runId || previous.head_sha !== request.head || previous.path !== WORKFLOW ||
      previous.event !== 'workflow_dispatch' || previous.status !== 'completed' || previous.conclusion !== 'success') {
    throw new Error(`The recorded draft rehearsal has not completed successfully. ${retry}`);
  }
  verifyJobs(await api.jobs(start.runId), targets, true);
  await verifyAssets(release, completion, api);
  return start.tag;
}

/** Seals the draft only after its independently verified native jobs have succeeded. */
export async function completeDraft(request, completion, targets, api) {
  const release = await api.release(completion.releaseId);
  const start = readMarker(release.body, 'started');
  if (!requestMatches(start, request) || start.runId !== request.runId || start.tag !== completion.tag ||
      String(release.id) !== completion.releaseId) {
    throw new Error('Draft completion refers to a different release or run');
  }
  assertDraftIdentity(release, start);
  verifyJobs(await api.jobs(request.runId), targets);
  await verifyAssets(release, completion, api);
  const record = { ...start, ...completion };
  const existing = readMarker(release.body, 'completed');
  if (existing && marker('completed', existing) !== marker('completed', record)) {
    throw new Error('Draft already has a different completion record');
  }
  if (!existing) await api.updateBody(completion.releaseId, `${release.body.trimEnd()}\n\n${marker('completed', record)}\n`);
}

function github(repo) {
  async function bytes(endpoint, ...args) {
    const { stdout } = await run('gh', ['api', endpoint, ...args], {
      encoding: 'buffer', maxBuffer: 16 * 1024 * 1024
    });
    return stdout;
  }
  async function json(endpoint, ...args) {
    return JSON.parse(await bytes(endpoint, ...args));
  }

  const releases = `repos/${repo}/releases`;
  const runs = `repos/${repo}/actions/runs`;
  return {
    release: id => json(`${releases}/${id}`),
    releases: async () => (await json(`${releases}?per_page=100`, '--paginate', '--slurp')).flat(),
    workflow: id => json(`${runs}/${id}`),
    jobs: async id => (await json(`${runs}/${id}/jobs?filter=latest&per_page=100`, '--paginate', '--slurp'))
      .flatMap(page => page.jobs),
    asset: id => bytes(`${releases}/assets/${id}`, '-H', 'Accept: application/octet-stream'),
    async updateBody(id, body) {
      const updated = await json(`${releases}/${id}`, '--method', 'PATCH', '--raw-field', `body=${body}`);
      if (String(updated.id) !== id || updated.draft !== true || updated.body !== body) {
        throw new Error('GitHub did not retain the draft completion record');
      }
    }
  };
}

function readRequest(environment) {
  const { GITHUB_REPOSITORY: repo, GITHUB_SHA: head, GITHUB_RUN_ID: runId,
    UPSTREAM_TAG: upstreamTag, UPSTREAM_COMMIT: upstreamSha } = environment;
  if (!/^[A-Za-z0-9-]+\/[A-Za-z0-9_.-]+$/.test(repo) || !SHA.test(head) ||
      !ID.test(runId) || !VERSION.test(upstreamTag) || !SHA.test(upstreamSha)) {
    throw new Error('Invalid draft rehearsal identity');
  }
  return { repo, head, runId, upstreamTag, upstreamSha };
}

async function main([operation, arg], environment) {
  const request = readRequest(environment);
  const { repo, ...identity } = request;
  if (operation === 'start') {
    const tag = environment.TAG;
    if (!tag?.startsWith(`${identity.upstreamTag}+`) || !ID.test(tag.slice(identity.upstreamTag.length + 1))) {
      throw new Error('Invalid draft release tag');
    }
    await appendFile(arg, `\n${marker('started', { ...identity, tag })}\n`);
    return;
  }
  const matrix = JSON.parse(await readFile('.github/release-matrix.json', 'utf8'));
  const targets = matrix.map(entry => entry.target);
  const api = github(repo);
  if (operation === 'lookup') {
    const tag = await findCompletedDraft(identity, targets, api);
    if (tag) process.stdout.write(tag + '\n');
  } else if (operation === 'complete') {
    const { DRAFT_RELEASE_ID: releaseId, TAG: tag, DRAFT_MANIFEST_SHA: manifestSha,
      DRAFT_SUMS_SHA: sumsSha } = environment;
    if (!ID.test(releaseId) || !DIGEST.test(manifestSha) || !DIGEST.test(sumsSha)) {
      throw new Error('Invalid draft readback identity');
    }
    await completeDraft(identity, { releaseId, tag, manifestSha, sumsSha }, targets, api);
  } else {
    throw new Error('Expected start, lookup or complete');
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    await main(process.argv.slice(2), process.env);
  } catch (error) {
    console.error(`draft-rehearsal-completion: ${error.message}`);
    process.exitCode = 1;
  }
}
