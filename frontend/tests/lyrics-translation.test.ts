import { test } from 'node:test';
import assert from 'node:assert/strict';
import { refreshTranslation } from '../src/composables/lyricsRequest.ts';
import { readLyricsTranslation, writeLyricsTranslation } from '../src/composables/lyricsTranslation.ts';
import { parseLyrics, type Lyrics } from '../src/native/library.ts';

const base: Lyrics = { status: 'ready', source: 'queue', id: 'netease:1', platform: 'netease', origin: 'cache', kind: 'synced',
  lines: [{ timeMs: 1000, text: 'Hello' }], translation: 'unknown', translationPending: true };

test('translation refresh delivers the late translation for the same song, then stops', async () => {
  const replies: Lyrics[] = [base, { ...base, translation: 'available', translationPending: undefined, lines: [{ timeMs: 1000, text: 'Hello', translation: '你好' }] }];
  const delivered: Lyrics[] = [];
  await refreshTranslation(async () => replies.shift()!, 'netease:1', new AbortController().signal, async () => {}, value => delivered.push(value));
  assert.equal(delivered.length, 2);
  assert.equal(delivered.at(-1)?.lines[0].translation, '你好');
  assert.equal(replies.length, 0);
});

test('translation refresh never touches the page for another song, a pending reply, a timeout or an abort', async () => {
  for (const reply of [{ ...base, id: 'netease:2' }, { ...base, status: 'pending' as const }]) {
    const delivered: Lyrics[] = [];
    await refreshTranslation(async () => reply, 'netease:1', new AbortController().signal, async () => {}, value => delivered.push(value));
    assert.deepEqual(delivered, []);
  }
  let clock = 0, calls = 0;
  await refreshTranslation(async () => { calls++; return base; }, 'netease:1', new AbortController().signal,
    async () => { clock += 5000; }, () => {}, () => clock, 20000);
  assert.ok(calls <= 4, 'refresh ran past its deadline');
  const controller = new AbortController(); controller.abort(); calls = 0;
  await refreshTranslation(async () => { calls++; return base; }, 'netease:1', controller.signal, async () => {}, () => {});
  assert.equal(calls, 0);
});

test('translation display preference defaults on and persists', () => {
  const store = new Map<string, string>();
  const storage = { getItem: (key: string) => store.get(key) ?? null, setItem: (key: string, value: string) => { store.set(key, value); } };
  assert.equal(readLyricsTranslation(storage), true);
  writeLyricsTranslation(storage, false); assert.equal(readLyricsTranslation(storage), false);
  writeLyricsTranslation(storage, true); assert.equal(readLyricsTranslation(storage), true);
});

test('lyrics replies validate optional translation fields', () => {
  assert.deepEqual(parseLyrics(base), base);
  const translated = { ...base, translation: 'available', lines: [{ timeMs: 1000, text: 'Hello', translation: '你好' }] };
  assert.deepEqual(parseLyrics(translated), translated);
  // v0.4 Core replies have no translation fields at all.
  const { translation, translationPending, ...legacy } = base; void translation; void translationPending;
  assert.deepEqual(parseLyrics(legacy), legacy);
  for (const bad of [{ translation: 'maybe' }, { translationPending: 'yes' }, { translationRetryable: 'yes' }, { lines: [{ timeMs: 1, text: 'x', translation: 3 }] }])
    assert.throws(() => parseLyrics({ ...base, ...bad }), { code: 502 });
});
