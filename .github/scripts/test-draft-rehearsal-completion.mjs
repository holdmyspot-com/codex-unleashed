import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { test } from 'node:test';
import { completeDraft, findCompletedDraft } from './draft-rehearsal-completion.mjs';

const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const targets = [
  'aarch64-apple-darwin', 'x86_64-apple-darwin', 'aarch64-pc-windows-msvc',
  'x86_64-pc-windows-msvc', 'aarch64-unknown-linux-musl', 'x86_64-unknown-linux-musl'
];

function fixture() {
  const request = { head: 'a'.repeat(40), upstreamTag: 'rust-v0.167.0', upstreamSha: 'b'.repeat(40), runId: '19' };
  const rerun = { ...request, runId: '20' };
  const tag = request.upstreamTag + '+4';
  const manifestSha = 'c'.repeat(64);
  const packageSha = 'd'.repeat(64);
  const sums = Buffer.from(`${manifestSha}  release-manifest.json\n${packageSha}  test-package.tar.gz\n`);
  const sumsSha = sha256(sums);
  const completion = { releaseId: '42', tag, manifestSha, sumsSha };
  const start = Buffer.from(JSON.stringify({ ...request, tag })).toString('base64url');
  const release = {
    id: 42, draft: true, target_commitish: request.head, tag_name: tag,
    body: `Original notes\n\n<!-- cp9-draft-started:${start} -->\n`,
    assets: [
      { id: 51, name: 'SHA256SUMS', digest: 'sha256:' + sumsSha },
      { id: 52, name: 'release-manifest.json', digest: 'sha256:' + manifestSha },
      { id: 53, name: 'test-package.tar.gz', digest: 'sha256:' + packageSha }
    ].map(asset => ({ ...asset, state: 'uploaded' }))
  };
  const jobs = ['Independently verify published assets and source',
    ...targets.map(target => `Installer and npm consumer - ${target}`),
    'Record draft rehearsal completion'].map(name => ({ name, conclusion: 'success' }));
  const workflow = {
    id: 19, head_sha: request.head, path: '.github/workflows/upstream-auto-release.yml',
    event: 'workflow_dispatch', status: 'completed', conclusion: 'success'
  };
  const writes = [];
  const api = {
    async release(id) {
      assert.equal(id, '42');
      return release;
    },
    async releases() { return [release]; },
    async workflow(id) { assert.equal(id, '19'); return workflow; },
    async jobs(id) { assert.equal(id, '19'); return jobs; },
    async asset(id) { assert.equal(id, 51); return sums; },
    async updateBody(id, body) {
      assert.equal(id, '42');
      assert.equal(release.draft, true);
      release.body = body;
      writes.push(body);
    }
  };
  return { request, rerun, tag, release, completion, workflow, jobs, api, writes };
}

test('records verified draft completion and identifies a fresh identical run as a no-op', async () => {
  const { request, rerun, tag, release, completion, api, writes } = fixture();
  await completeDraft(request, completion, targets, api);
  assert.equal(await findCompletedDraft(rerun, targets, api), tag);
  assert.equal(await findCompletedDraft({ ...rerun, upstreamSha: 'e'.repeat(40) }, targets, api), null);
  assert.equal(await findCompletedDraft({ ...rerun, head: 'e'.repeat(40) }, targets, api), null);
  await completeDraft(request, completion, targets, api);
  assert.equal(writes.length, 1);
  assert.equal(release.draft, true);
  assert.match(release.body, /^Original notes/);
});

test('an incomplete draft directs fresh dispatch to retry and lets original run resume', async () => {
  const { request, rerun, api, writes } = fixture();
  assert.equal(await findCompletedDraft(request, targets, api), null);
  await assert.rejects(findCompletedDraft(rerun, targets, api), /Retry failed jobs from rehearsal run 19/);
  assert.equal(writes.length, 0);
});

test('missing or unsuccessful job evidence prevents completion or a later no-op', async () => {
  const { request, rerun, completion, workflow, jobs, api, writes } = fixture();
  const job = jobs.splice(6, 1)[0];
  await assert.rejects(completeDraft(request, completion, targets, api), /lacks a successful job/);
  assert.equal(writes.length, 0);
  jobs.push(job);
  await completeDraft(request, completion, targets, api);
  workflow.conclusion = 'failure';
  await assert.rejects(findCompletedDraft(rerun, targets, api), /has not completed successfully/);
  workflow.conclusion = 'success';
  jobs.find(job => job.name === 'Record draft rehearsal completion').conclusion = 'skipped';
  await assert.rejects(findCompletedDraft(rerun, targets, api), /lacks a successful job/);
});

test('changed asset metadata or checksum bytes prevent a no-op', async () => {
  const { request, rerun, release, completion, api } = fixture();
  await completeDraft(request, completion, targets, api);
  release.assets[2].digest = 'sha256:' + 'f'.repeat(64);
  await assert.rejects(findCompletedDraft(rerun, targets, api), /differ from the verified checksum inventory/);
  release.assets[2].digest = 'sha256:' + 'd'.repeat(64);
  api.asset = async () => Buffer.from('unexpected');
  await assert.rejects(findCompletedDraft(rerun, targets, api), /checksum bytes have changed/);
});

test('a changed exact release ID invalidates the completion record', async () => {
  const { request, rerun, release, completion, api } = fixture();
  await completeDraft(request, completion, targets, api);
  release.id = 43;
  await assert.rejects(findCompletedDraft(rerun, targets, api), /differs from its original rehearsal/);
});
