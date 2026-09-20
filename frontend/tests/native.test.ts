import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createNativeClient } from '../src/native/client.ts';
import { request, type CefTransport } from '../src/native/transport.ts';
const state = { opened: true, playing: false, positionMs: 1200, durationMs: 3000, volumePercent: 72, trackId: 'track-1' };
test('getState validates protocol and returns immutable native snapshot', async () => {
  const host: CefTransport = { cefQuery(q) {
    assert.deepEqual(JSON.parse(q.request), { version: 1, command: 'player.getState', params: {} });
    q.onSuccess(JSON.stringify({ version: 1, result: state })); return 1;
  }, cefQueryCancel() {} };
  const result = await createNativeClient(host).player.getState();
  assert.deepEqual(result, state); assert.ok(Object.isFrozen(result));
});
test('malformed envelopes and state fields are rejected', async () => {
  for (const reply of ['broken', { version: 2, result: state }, { version: 1, result: { ...state, playing: 1 } },
    { version: 1, result: { ...state, volumePercent: 101 } }, { version: 1, result: { ...state, positionMs: -1 } }]) {
    await assert.rejects(createNativeClient({ cefQuery(q) { q.onSuccess(typeof reply === 'string' ? reply : JSON.stringify(reply)); return 1; }, cefQueryCancel() {} }).player.getState(), { code: 502 });
  }
});
test('missing native host is explicit, never mock playback', async () => {
  await assert.rejects(createNativeClient({}).player.getState(), { code: 503 });
});
test('native errors retain their code', async () => {
  await assert.rejects(request({ cefQuery(q) { q.onFailure(403, 'Untrusted frame'); return 1; }, cefQueryCancel() {} }, 'player.getState', {}), { code: 403 });
});
test('abort cancels CEF query and late responses cannot resolve it', async () => {
  let callback: ((text: string) => void) | undefined;
  const cancelled: number[] = [];
  const controller = new AbortController();
  const pending = request({ cefQuery(q) { callback = q.onSuccess; return 42; }, cefQueryCancel(id) { cancelled.push(id); } }, 'player.getState', {}, { signal: controller.signal });
  controller.abort(); callback?.(JSON.stringify({ version: 1, result: state }));
  await assert.rejects(pending, { code: 499 }); assert.deepEqual(cancelled, [42]);
});
test('timeout cancels native work', async () => {
  const cancelled: number[] = [];
  await assert.rejects(request({ cefQuery() { return 7; }, cefQueryCancel(id) { cancelled.push(id); } }, 'player.getState', {}, {}, 10), { code: 408 });
  assert.deepEqual(cancelled, [7]);
});
test('pre-aborted requests never dispatch', async () => {
  const controller = new AbortController(); controller.abort();
  await assert.rejects(request({ cefQuery() { assert.fail('dispatched'); }, cefQueryCancel() {} }, 'player.getState', {}, { signal: controller.signal }), { code: 499 });
});
