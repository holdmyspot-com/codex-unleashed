import fs from 'node:fs/promises';

/**
 * Downloads retained logs from the Windows run whose retry output exceeded the console limit.
 * @param {string} apiKey BuildBuddy credential supplied at the process boundary.
 */
async function recoverAttemptLogs(apiKey) {
  const invocationId = 'da2ea27c-5368-4c67-b162-44b199b1a4a2';
  const output = '.cat/work/temp/native-log-recovery';
  await fs.mkdir(output, {recursive: true});
  const headers = {'Content-Type': 'application/json', 'x-buildbuddy-api-key': apiKey};
  const actions = [];
  let pageToken = '';
  do {
    const response = await fetch('https://app.buildbuddy.io/api/v1/GetAction', {
      method: 'POST', headers,
      body: JSON.stringify({selector: {invocation_id: invocationId, target_label: '//codex-rs/core:core-all-test'}, page_token: pageToken}),
    });
    if (!response.ok) throw new Error(`BuildBuddy GetAction returned HTTP ${response.status}: ${await response.text()}`);
    const page = await response.json();
    actions.push(...(page.action ?? []));
    pageToken = page.nextPageToken ?? '';
  } while (pageToken);
  await fs.writeFile(`${output}/actions.json`, JSON.stringify(actions, null, 2) + '\n');
  let count = 0;
  for (const action of actions) {
    for (const file of action.file ?? []) {
      if (file.name !== 'test.log') continue;
      const response = await fetch('https://app.buildbuddy.io/api/v1/GetFile', {
        method: 'POST', headers, body: JSON.stringify({uri: file.uri}),
      });
      if (!response.ok) throw new Error(`BuildBuddy GetFile returned HTTP ${response.status} for retained test log.`);
      const bytes = Buffer.from(await response.arrayBuffer());
      const name = `core-attempt-${++count}.log`;
      await fs.writeFile(`${output}/${name}`, bytes);
      console.log(JSON.stringify({name, shard: action.shard ?? 0, run: action.run ?? 0, attempt: action.attempt ?? 0, bytes: bytes.length}));
    }
  }
  if (count === 0) throw new Error('BuildBuddy returned no retained core test logs.');
  console.log(`Recovered ${count} core attempt logs; no tests were rerun.`);
}

const apiKey = process.env.BUILDBUDDY_API_KEY;
if (!apiKey) throw new Error('BUILDBUDDY_API_KEY is required for retained-log access.');
await recoverAttemptLogs(apiKey);
