import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { readbackDraftAssets } from './readback-draft-assets.mjs';

async function fixture(t) {
  const root = await mkdtemp(path.join(os.tmpdir(), 'draft-readback-test-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const expectedAssets = path.join(root, 'expected');
  const output = path.join(root, 'readback');
  await mkdir(expectedAssets);
  const originals = [
    ['release-manifest.json', Buffer.from('{"fixture": true}')],
    ['codex-package-x86_64-unknown-linux-musl.tar.gz', Buffer.from([0, 0x1f, 0x8b, 0xff])]
  ];
  for (const [name, bytes] of originals) await writeFile(path.join(expectedAssets, name), bytes);
  const options = { repo: 'owner/repo', releaseId: '42', tag: 'rust-v0.167.0+4', expectedAssets, output };
  const release = {
    id: 42,
    draft: true,
    tag_name: options.tag,
    assets: originals.map(([name, bytes], index) => ({
      id: index + 101,
      state: 'uploaded',
      name,
      digest: `sha256:${createHash('sha256').update(bytes).digest('hex')}`
    }))
  };
  const requests = [];
  const api = {
    async metadata(endpoint) {
      requests.push(endpoint);
      return release;
    },
    async download(endpoint, filename) {
      requests.push(endpoint);
      const index = Number(endpoint.split('/').at(-1)) - 101;
      await writeFile(filename, originals[index][1]);
    }
  };
  return { root, originals, options, release, requests, api };
}

test('reads back the exact draft and streams only its expected asset IDs', async t => {
  const { originals, options, requests, api } = await fixture(t);
  await readbackDraftAssets(options, api);
  assert.deepEqual(requests, [
    'repos/owner/repo/releases/42',
    'repos/owner/repo/releases/assets/101',
    'repos/owner/repo/releases/assets/102'
  ]);
  assert.deepEqual((await readdir(options.output)).sort(), originals.map(([name]) => name).sort());
  for (const [name, bytes] of originals) assert.deepEqual(await readFile(path.join(options.output, name)), bytes);
  await assert.rejects(readbackDraftAssets(options, api), /Output already exists/);
});

test('rejects incorrect draft state, identity, asset inventory and digest before downloading', async t => {
  const cases = [
    ['wrong release', release => { release.id = 43; }, /expected draft release/],
    ['published release', release => { release.draft = false; }, /expected draft release/],
    ['wrong tag', release => { release.tag_name = 'rust-v0.167.0+5'; }, /expected draft release/],
    ['missing asset', release => { release.assets.pop(); }, /inventory differs/],
    ['unexpected name', release => { release.assets[0].name = '../escape'; }, /unexpected or duplicate asset/],
    ['duplicate name', release => { release.assets[1] = { ...release.assets[0], id: 102 }; }, /unexpected or duplicate asset/],
    ['duplicate ID', release => { release.assets[0].id = release.assets[1].id; }, /unexpected or duplicate asset/],
    ['wrong digest', release => { release.assets[0].digest = 'sha256:' + '0'.repeat(64); }, /Draft digest differs/]
  ];
  for (const [name, change, diagnostic] of cases) {
    await t.test(name, async t => {
      const { root, options, release, requests, api } = await fixture(t);
      change(release);
      await assert.rejects(readbackDraftAssets(options, api), diagnostic);
      assert.deepEqual(requests, ['repos/owner/repo/releases/42']);
      assert.deepEqual(await readdir(root), ['expected']);
    });
  }
});

test('a truncated download is rejected and staging is removed', async t => {
  const { root, options, api } = await fixture(t);
  const { download } = api;
  api.download = async (endpoint, filename) => {
    if (endpoint.endsWith('/102')) await writeFile(filename, Buffer.from([0]));
    else await download(endpoint, filename);
  };
  await assert.rejects(readbackDraftAssets(options, api), /Downloaded digest differs/);
  assert.deepEqual(await readdir(root), ['expected']);
});
