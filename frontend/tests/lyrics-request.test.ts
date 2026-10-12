import test from 'node:test';
import assert from 'node:assert/strict';
import { loadLyricsRequest } from '../src/composables/lyricsRequest.ts';
import type { Lyrics } from '../src/native/library.ts';
const pending: Lyrics = { status: 'pending', source: 'queue', id: 'qq:1', platform: 'qq', origin: '', kind: 'none', lines: [] };
test('lyrics poll beyond old 10 s limit and accept explicit no lyrics', async () => {
  let time = 0;
  const result = await loadLyricsRequest(async () => ({ ...pending, status: time >= 14000 ? 'ready' : 'pending' }),
    pending.id, new AbortController().signal, async () => { time += 250; }, () => time);
  assert.equal(time, 14000); assert.equal(result.kind, 'none');
});
test('lyrics fail once on network error and can be requested again', async () => {
  let calls = 0;
  await assert.rejects(loadLyricsRequest(async () => { calls++; throw Error('network'); }, pending.id,
    new AbortController().signal, async () => {}));
  assert.equal(calls, 1);
  assert.equal((await loadLyricsRequest(async () => ({ ...pending, status: 'ready' }), pending.id,
    new AbortController().signal, async () => {})).status, 'ready');
});
test('cancelled and mismatched responses never deliver lyrics', async () => {
  const controller = new AbortController();
  await assert.rejects(loadLyricsRequest(async () => { controller.abort(); return { ...pending, status: 'ready' }; },
    pending.id, controller.signal, async () => {}));
  await assert.rejects(loadLyricsRequest(async () => ({ ...pending, id: 'qq:old', status: 'ready' }),
    pending.id, new AbortController().signal, async () => {}));
});
test('pending lyrics have a bounded total wait', async () => {
  let time = 0;
  await assert.rejects(loadLyricsRequest(async () => pending, pending.id, new AbortController().signal,
    async () => { time += 250; }, () => time));
  assert.equal(time, 20000);
});
