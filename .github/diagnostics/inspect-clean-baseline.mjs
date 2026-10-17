import fs from 'node:fs/promises';

async function inspectBaseline(apiKey, context) {
  const headers = {'Content-Type': 'application/json', 'x-buildbuddy-api-key': apiKey};
  const output = '.cat/work/temp/clean-baseline-observation';
  await fs.mkdir(output, {recursive: true});

  async function request(method, body) {
    const response = await fetch(`https://app.buildbuddy.io/api/v1/${method}`, {
      method: 'POST', headers, body: JSON.stringify(body),
    });
    if (!response.ok) throw new Error(`BuildBuddy ${method} returned HTTP ${response.status}.`);
    return response;
  }

  const invocations = [];
  let pageToken = '';
  const visited = new Set();
  do {
    if (visited.has(pageToken)) throw new Error('BuildBuddy repeated an invocation page token.');
    visited.add(pageToken);
    const response = await request('GetInvocation', {
      selector: {commit_sha: context.commit}, include_metadata: true, page_token: pageToken,
    });
    const page = await response.json();
    invocations.push(...(page.invocation ?? []));
    pageToken = page.nextPageToken ?? '';
  } while (pageToken);

  const sinceUsec = BigInt(Date.parse(context.startedAt)) * 1000n;
  const latest = new Map();
  for (const invocation of invocations) {
    const createdUsec = BigInt(invocation.createdAtUsec ?? '0');
    const metadata = invocation.buildMetadata ?? [];
    if (createdUsec < sinceUsec || !metadata.some(item => item.key === 'TAG_clean_upstream_native' && item.value === 'true')) continue;
    const complete = invocation.invocationStatus === 'COMPLETE_INVOCATION_STATUS' || invocation.invocationStatus === 1;
    const key = JSON.stringify([invocation.host, invocation.pattern, complete]);
    const previous = latest.get(key);
    if (!previous || BigInt(previous.createdAtUsec ?? '0') < createdUsec) latest.set(key, invocation);
  }

  const selected = [];
  for (const invocation of latest.values()) {
    const invocationId = invocation.id?.invocationId;
    if (!invocationId) throw new Error('Selected invocation has no invocation ID.');
    const record = {
      invocationId, host: invocation.host, pattern: invocation.pattern,
      status: invocation.invocationStatus, success: invocation.success,
      createdAtUsec: invocation.createdAtUsec, updatedAtUsec: invocation.updatedAtUsec,
      actionCount: invocation.actionCount, exitCode: invocation.bazelExitCode, testLogs: [],
    };
    const target = ['//codex-rs/core:core-all-test', '//codex-rs/app-server:app-server-all-test'].find(label => invocation.pattern?.includes(label));
    if (target) {
      const response = await request('GetAction', {selector: {invocation_id: invocationId, target_label: target}});
      const page = await response.json();
      for (const action of page.action ?? []) {
        for (const file of action.file ?? []) {
          if (file.name !== 'test.log') continue;
          const response = await request('GetFile', {uri: file.uri});
          const bytes = Buffer.from(await response.arrayBuffer());
          const name = `${invocationId}-test-${record.testLogs.length + 1}.log`;
          await fs.writeFile(`${output}/${name}`, bytes);
          const text = bytes.toString('utf8');
          record.testLogs.push({
            name, bytes: bytes.length,
            test: context.tests.find(test => text.includes(`test ${test} ...`)) ?? null,
            summary: text.split('\n').filter(line => line.startsWith('test result:')),
            oneTestExecuted: text.includes('running 1 test'),
          });
        }
      }
    }
    selected.push(record);
  }
  const result = {observedAt: new Date().toISOString(), context, matchingInvocations: latest.size, selected};
  await fs.writeFile(`${output}/observation.json`, JSON.stringify(result, null, 2) + '\n');
  console.log(JSON.stringify(result, null, 2));
}

const apiKey = process.env.BUILDBUDDY_API_KEY;
if (!apiKey) throw new Error('BUILDBUDDY_API_KEY is required for baseline observation.');
const context = JSON.parse(await fs.readFile('.github/diagnostics/clean-baseline-context.json', 'utf8'));
await inspectBaseline(apiKey, context);
