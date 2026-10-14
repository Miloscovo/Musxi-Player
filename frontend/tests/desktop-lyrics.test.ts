import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createDesktopLyricsClient, parseDesktopLyrics } from '../src/native/desktopLyrics.ts';

const state = { enabled: true, visible: true, locked: false, dual: true, panel: false, hover: false, fontSize: 30, color: 'theme' };

test('desktop client validates state and sends one field per setter', async () => {
  const calls: unknown[] = [];
  const client = createDesktopLyricsClient({ cefQueryCancel() {}, cefQuery(q) {
    calls.push(JSON.parse(q.request)); q.onSuccess(JSON.stringify({ version: 1, result: state })); return 1;
  } });
  assert.deepEqual(await client.getState(), state);
  await client.setVisible(false); await client.setLocked(true); await client.setDual(false);
  await client.setPanel(true); await client.setFontSize(36); await client.setColor('dark');
  assert.deepEqual(calls.map(call => (call as { command: string; params: object }).params), [
    {}, { visible: false }, { locked: true }, { dual: false }, { panel: true }, { fontSize: 36 }, { color: 'dark' }]);
  for (const value of [null, {}, { ...state, dual: 'true' }, { ...state, panel: undefined }, { ...state, fontSize: 60 },
    { ...state, fontSize: 30.5 }, { ...state, color: 'red' }])
    assert.throws(() => parseDesktopLyrics(value), /Invalid desktop lyrics state/);
});

test('desktop subscription delivers pushed states and stops on cancel or bad events', () => {
  let success: (text: string) => void = () => {}; const cancelled: number[] = [];
  const host = { cefQueryCancel: (id: number) => cancelled.push(id), cefQuery(q: { request: string; persistent: boolean; onSuccess: (text: string) => void }) {
    assert.equal(JSON.parse(q.request).command, 'desktopLyrics.subscribe'); assert.equal(q.persistent, true);
    success = q.onSuccess; return 7;
  } };
  const seen: unknown[] = []; const errors: unknown[] = [];
  const stop = createDesktopLyricsClient(host).subscribe(value => seen.push(value), error => errors.push(error));
  success(JSON.stringify({ version: 1, event: 'desktopLyrics.changed', state }));
  success(JSON.stringify({ version: 1, event: 'desktopLyrics.changed', state: { ...state, panel: true } }));
  assert.deepEqual(seen, [state, { ...state, panel: true }]);
  success(JSON.stringify({ version: 1, state: { ...state, color: 'x' } }));
  assert.equal(errors.length, 1); assert.deepEqual(cancelled, [7]);
  success(JSON.stringify({ version: 1, state })); stop();
  assert.equal(seen.length, 2); assert.deepEqual(cancelled, [7]);
});
