import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createWindowClient } from '../src/native/window.ts';
test('window API validates replies and uses the shared protocol', async () => {
  const seen: unknown[] = [];
  let result: unknown = { enabled: true, maximized: false };
  const client = createWindowClient({ cefQueryCancel() {}, cefQuery(q) {
    seen.push(JSON.parse(q.request)); q.onSuccess(JSON.stringify({ version: 1, result })); return 1;
  } });
  assert.deepEqual(await client.setTheme('glass'), result);
  assert.deepEqual(seen[0], { version: 1, command: 'window.setTheme', params: { theme: 'glass' } });
  assert.deepEqual(await client.setTheme('glass-light'), result);
  assert.deepEqual(seen[1], { version: 1, command: 'window.setTheme', params: { theme: 'glass-light' } });
  await client.maximize();
  assert.deepEqual(seen[2], { version: 1, command: 'window.maximize', params: {} });
  for (const command of ['minimizeToTray', 'toggleVisibility', 'dismissTrayMenu'] as const) {
    await client[command]();
    assert.deepEqual(seen.at(-1), { version: 1, command: `window.${command}`, params: {} });
  }
  result = { enabled: 'yes', maximized: false };
  await assert.rejects(client.getState(), /Invalid window state/);
});
