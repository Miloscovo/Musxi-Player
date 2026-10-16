import { test } from 'node:test';
import assert from 'node:assert/strict';
import { translationHint } from '../src/composables/translationStatus.ts';
import { refreshTranslation } from '../src/composables/lyricsRequest.ts';
import { parseLyrics, type Lyrics, type TranslationLookup } from '../src/native/library.ts';
import { createDemoTransport } from '../src/demo/context.ts';
import { createNativeClient } from '../src/native/client.ts';

const lines = [{ timeMs: 1000, text: 'Hello' }, { timeMs: 2000, text: 'World' }];
const dual = [{ timeMs: 1000, text: 'Hello', translation: '你好' }, { timeMs: 2000, text: 'World', translation: '世界' }];
const song = (translation: Lyrics['translation'], lookup?: TranslationLookup, extra: Partial<Lyrics> = {}): Lyrics => ({
  status: 'ready', source: 'queue', id: 'kugou:1', platform: 'kugou', origin: 'cache', kind: 'synced',
  lines: translation === 'available' ? dual : lines, translation, translationRetryable: lookup?.canRefresh,
  ...(lookup ? { translationLookup: lookup, ...(lookup.status === 'pending' ? { translationPending: true } : {}) } : {}), ...extra });

test('saved translations stay quiet, update in place and report a failed update', () => {
  assert.deepEqual(translationHint(song('available', { status: 'idle', canRefresh: true })),
    { text: '', action: 'recheck', pending: false, transient: false });
  const updating = translationHint(song('available', { status: 'pending', canRefresh: true }));
  assert.equal(updating.text, '正在更新翻译…'); assert.equal(updating.pending, true); assert.equal(updating.action, 'recheck');
  const failed = translationHint(song('available', { status: 'failed', reason: 'timeout', canRefresh: true }));
  assert.equal(failed.text, '翻译更新失败，继续显示已保存翻译'); assert.equal(failed.action, 'retry');
  assert.doesNotMatch(failed.text, /暂无/);
});

test('a manual check answers briefly, including when the platform had nothing new', () => {
  const none = translationHint(song('available', { status: 'succeeded', reason: 'not_provided', canRefresh: true }), { manual: true });
  assert.equal(none.text, '平台本次未提供新翻译'); assert.equal(none.transient, true);
  // The same reply without a user check says nothing.
  assert.equal(translationHint(song('available', { status: 'succeeded', reason: 'not_provided', canRefresh: true })).text, '');
});

test('missing translations distinguish the platform answer from a failed lookup', () => {
  const none = translationHint(song('none', { status: 'succeeded', reason: 'not_provided', canRefresh: true }));
  assert.equal(none.text, '该版本暂无翻译'); assert.equal(none.action, 'recheck');
  for (const [reason, text] of [['network', '网络连接失败，未取得翻译'], ['timeout', '翻译查询超时'], ['invalid_data', '翻译数据无法使用'],
    ['version_mismatch', '翻译与当前歌词版本不一致'], ['internal_error', '获取翻译失败']] as const) {
    const hint = translationHint(song('unknown', { status: 'failed', reason, canRefresh: true }));
    assert.equal(hint.text, text); assert.notEqual(hint.action, '');
  }
  const fetching = translationHint(song('unknown', { status: 'pending', canRefresh: true }));
  assert.equal(fetching.text, '正在获取翻译…'); assert.equal(fetching.action, ''); assert.equal(fetching.pending, true);
});

test('unsupported lyrics never claim the platform has no translation', () => {
  const local = { platform: 'local' as const, origin: 'lrc-file' as const };
  assert.deepEqual(translationHint(song('none', { status: 'unsupported', reason: 'not_bound', canRefresh: false }, local)),
    { text: '', action: '', pending: false, transient: false });
  assert.equal(translationHint(song('none', { status: 'unsupported', reason: 'not_synced', canRefresh: false }, { kind: 'plain' })).text,
    '当前歌词暂不支持逐行翻译');
  assert.equal(translationHint(song('none', { status: 'unsupported', reason: 'no_lyrics', canRefresh: false }, { kind: 'none', lines: [] })).text, '');
});

test('this page giving up on a lookup ends the loading hint and allows another try', () => {
  const hint = translationHint(song('unknown', { status: 'pending', canRefresh: true }), { wait: 'timeout' });
  assert.equal(hint.pending, false); assert.equal(hint.text, '翻译查询较慢，可稍后重试'); assert.equal(hint.action, 'retry');
  const kept = translationHint(song('available', { status: 'pending', canRefresh: true }), { wait: 'error' });
  assert.equal(kept.pending, false); assert.equal(kept.action, 'retry');
});

test('older Cores without translationLookup keep the previous rules', () => {
  assert.equal(translationHint(song('none')).text, '该版本暂无翻译');
  assert.equal(translationHint(song('unknown', undefined, { platform: 'netease' })).action, 'retry');
  // v0.4 KuGou had no retry path unless the Core said so.
  assert.equal(translationHint(song('unknown')).action, '');
  assert.equal(translationHint(song('unknown', undefined, { translationPending: true })).text, '正在获取翻译…');
  assert.equal(translationHint(song('available')).action, '');
});

test('translation lookups are validated; unknown future reasons read as internal errors', () => {
  const ok = song('available', { status: 'failed', reason: 'timeout', canRefresh: true });
  assert.deepEqual(parseLyrics(ok), ok);
  const future = parseLyrics(song('unknown', { status: 'failed', reason: 'quota' as never, canRefresh: true }));
  assert.equal(future.translationLookup?.reason, 'internal_error');
  for (const lookup of [null, [], { status: 'maybe', canRefresh: true }, { status: 'idle' }, { status: 'idle', canRefresh: 1 },
    { status: 'failed', reason: 3, canRefresh: true }])
    assert.throws(() => parseLyrics({ ...ok, translationLookup: lookup }), { code: 502 });
});

test('translation refresh reports a timeout separately from a settled lookup', async () => {
  const pending = song('unknown', { status: 'pending', canRefresh: true });
  let clock = 0;
  assert.equal(await refreshTranslation(async () => pending, pending.id, new AbortController().signal,
    async () => { clock += 5000; }, () => {}, () => clock, 20000), 'timeout');
  const done = song('available', { status: 'succeeded', canRefresh: true });
  assert.equal(await refreshTranslation(async () => done, done.id, new AbortController().signal, async () => {}, () => {}), 'settled');
  assert.equal(await refreshTranslation(async () => ({ ...done, id: 'kugou:2' }), done.id, new AbortController().signal,
    async () => {}, () => {}), 'stopped');
});

test('demo translation check sends retryTranslation once and settles to a new translation', async () => {
  const c = createNativeClient(createDemoTransport());
  const first = await c.library.lyrics('library', 'demo-1');
  assert.equal(first.translationLookup?.status, 'failed'); assert.equal(first.translationLookup?.reason, 'network');
  assert.ok(first.lines.every(line => !line.translation));
  const checking = await c.library.lyrics('library', 'demo-1', undefined, true);
  assert.equal(checking.translationLookup?.status, 'pending'); assert.equal(checking.translationPending, true);
  // A poll without the flag neither restarts nor ends the running check.
  assert.equal((await c.library.lyrics('library', 'demo-1')).translationLookup?.status, 'pending');
  const local = await c.library.lyrics('local', 'demo-3');
  assert.equal(local.translation, 'none'); assert.equal(local.translationLookup?.reason, 'not_provided');
});

test('demo repeated checks retain acquired translations and join the running check', async t => {
  let now=10000;t.mock.method(Date,'now',()=>now);
  const c=createNativeClient(createDemoTransport());
  await c.library.lyrics('library','demo-1',undefined,true);
  now+=1500;
  const acquired=await c.library.lyrics('library','demo-1');
  assert.equal(acquired.translation,'available');
  const checking=await c.library.lyrics('library','demo-1',undefined,true);
  assert.deepEqual(checking.lines,acquired.lines);
  assert.equal(checking.translation,'available');assert.equal(checking.translationPending,true);
  now+=1000;
  assert.deepEqual((await c.library.lyrics('library','demo-1',undefined,true)).lines,acquired.lines);
  now+=500;
  const done=await c.library.lyrics('library','demo-1');
  assert.equal(done.translationLookup?.status,'succeeded');assert.equal(done.translationPending,undefined);
  // Completion also counts when the user left before a poll observed it.
  await c.library.lyrics('library','demo-6');now+=3000;
  const lateCheck=await c.library.lyrics('library','demo-6',undefined,true);
  assert.equal(lateCheck.translation,'available');assert.equal(lateCheck.translationPending,true);
});

test('demo bound lyrics check adds translation while preserving original, offset and later previews', async t => {
  let now=10000;t.mock.method(Date,'now',()=>now);
  const c=createNativeClient(createDemoTransport()),signal=new AbortController().signal;
  await c.library.importLocal();
  const begin=await c.lyricsMatch.run('local:0','begin',{},signal),session=begin.session;
  const run=(action:string,params:Record<string,unknown>={})=>c.lyricsMatch.run('local:0',action,{session,...params},signal);
  await run('search',{platform:'qq',keywords:'Song',page:1});
  await run('preview',{candidate:'0'});
  await run('save',{offsetMs:1500});
  const before=await c.library.lyrics('local','local:0');
  assert.equal(before.translation,'unknown');
  const checking=await c.library.lyrics('local','local:0',undefined,true);
  assert.equal(checking.translationLookup?.status,'pending');assert.equal(checking.translationPending,true);
  assert.deepEqual(checking.lines,before.lines);
  now+=1000;
  assert.equal((await c.library.lyrics('local','local:0',undefined,true)).translationPending,true);
  now+=500;
  const done=await c.library.lyrics('local','local:0');
  assert.equal(done.translationLookup?.status,'succeeded');assert.equal(done.translationPending,undefined);
  assert.equal(done.translation,'available');assert.equal(done.offsetMs,1500);
  assert.deepEqual(done.lines.map(({text,timeMs})=>({text,timeMs})),before.lines.map(({text,timeMs})=>({text,timeMs})));
  assert.deepEqual((await c.library.lyrics('local','local:0',undefined,true)).lines,done.lines);
  now+=1500;await c.library.lyrics('local','local:0');
  const reopened=await c.lyricsMatch.run('local:0','begin',{},signal);
  assert.equal(reopened.preview?.translation,'available');
  const adjusted=await c.lyricsMatch.run('local:0','adjust',{session:reopened.session,offsetMs:0},signal);
  assert.equal(adjusted.preview?.lines[0].timeMs,0);assert.ok(adjusted.preview?.lines[0].translation);
});

test('demo replacing or removing a binding discards its outstanding translation check', async t => {
  let now=10000;t.mock.method(Date,'now',()=>now);
  const c=createNativeClient(createDemoTransport()),signal=new AbortController().signal;
  await c.library.importLocal();
  const begin=await c.lyricsMatch.run('local:0','begin',{},signal),session=begin.session;
  const run=(action:string,params:Record<string,unknown>={})=>c.lyricsMatch.run('local:0',action,{session,...params},signal);
  await run('search',{platform:'netease',keywords:'Song',page:1});
  await run('preview',{candidate:'0'});await run('save',{offsetMs:500});
  await c.library.lyrics('local','local:0',undefined,true);
  await run('save',{offsetMs:2500});now+=1500;
  const replaced=await c.library.lyrics('local','local:0');
  assert.equal(replaced.offsetMs,2500);assert.equal(replaced.translation,'unknown');
  assert.equal(replaced.translationLookup?.status,'idle');
  await c.library.lyrics('local','local:0',undefined,true);await run('reset');now+=1500;
  const reset=await c.library.lyrics('local','local:0');
  assert.notEqual(reset.origin,'matched');assert.equal(reset.translationLookup?.reason,'not_bound');
});
