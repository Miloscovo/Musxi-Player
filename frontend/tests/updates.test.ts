import test from 'node:test';
import assert from 'node:assert/strict';
import { parseUpdateState, createUpdatesClient } from '../src/native/updates.ts';
import { createDemoUpdates } from '../src/demo/updates.ts';
import { noteLines, pollDelay, reasonText, shouldNotify, updateSummary } from '../src/composables/appUpdates.ts';
import type { CefTransport } from '../src/native/transport.ts';
import { NativeError } from '../src/native/types.ts';

const sample = () => ({
  current: { version: '0.4.0', commit: 'e7ccf87f2d5587c1fb650e5f21fc186a3500994f', releaseBuild: true, architecture: 'x64' },
  source: 'Miloscovo/Musxi-Player', includesPrereleases: true, preferences: { autoCheck: true }, automaticCheckEnabled: true,
  checkStatus: 'succeeded', result: 'update_available', reason: '',
  latest: { releaseId: '20', assetId: '120', version: '0.5.0', tag: 'v0.5.0', prerelease: true, publishedAt: '2026-10-16T00:00:00Z',
    notes: '更新说明', assetName: 'MusxiPlayer-Setup-0.5.0.exe', bytes: 224013707, downloadAvailable: true },
  lastAttemptAt: 1800000000, lastSuccessAt: 1800000001, stale: false, retryAt: 1800000030, canCheck: false
});
// Transport backed by one in-memory handler, recording requests.
function host(handler: (command: string, params: any) => unknown) {
  const sent: { command: string; params: any }[] = [];
  const transport: CefTransport = {
    cefQuery(q) {
      const { command, params } = JSON.parse(q.request); sent.push({ command, params });
      try { q.onSuccess(JSON.stringify({ version: 1, result: handler(command, params) })); }
      catch (e) { const n = e as NativeError; q.onFailure(n.code, n.message); }
      return sent.length;
    }, cefQueryCancel() {}
  };
  return { transport, sent };
}

test('update state parser accepts the documented shape and rejects unsafe or unknown values', () => {
  const state = parseUpdateState(sample());
  assert.equal(state.latest?.releaseId, '20'); assert.equal(state.preferences.ignoredReleaseId, undefined);
  assert.equal(parseUpdateState({ ...sample(), latest: null }).latest, null);
  const noAsset = { ...sample(), result: 'no_compatible_asset', latest: { ...sample().latest, assetId: '', assetName: '', bytes: 0, downloadAvailable: false } };
  assert.equal(parseUpdateState(noAsset).latest?.assetId, '');
  const bad: unknown[] = [null, [], { ...sample(), checkStatus: 'done' }, { ...sample(), result: 'up_to_date' },
    { ...sample(), latest: { ...sample().latest, releaseId: 20 } }, { ...sample(), latest: { ...sample().latest, releaseId: '1e3' } },
    { ...sample(), retryAt: 1.5 }, { ...sample(), lastSuccessAt: -1 }, { ...sample(), preferences: { autoCheck: 'yes' } },
    { ...sample(), latest: { ...sample().latest, assetId: '', downloadAvailable: true } },
    { ...sample(), latest: { ...sample().latest, assetName: '' } }, { ...sample(), current: undefined }];
  for (const value of bad) assert.throws(() => parseUpdateState(value), NativeError);
});

test('client sends opaque string IDs only and validates open replies', async () => {
  const { transport, sent } = host((command) => command.startsWith('updates.open') ? { opened: true } : sample());
  const client = createUpdatesClient(transport);
  assert.equal(await client.openDownload('20', '120'), true);
  assert.equal(await client.openReleaseNotes('20'), true);
  await client.setPreferences(false);
  assert.deepEqual(sent.map(s => [s.command, s.params]), [
    ['updates.openDownload', { releaseId: '20', assetId: '120' }], ['updates.openReleaseNotes', { releaseId: '20' }],
    ['updates.setPreferences', { autoCheck: false }]]);
  const broken = createUpdatesClient(host(() => ({ opened: 'yes' })).transport);
  await assert.rejects(broken.openReleaseNotes('20'), NativeError);
});

test('older backends surface 404 instead of a fake empty state', async () => {
  const client = createUpdatesClient(host(() => { throw new NativeError(404, 'Unknown command'); }).transport);
  await assert.rejects(client.getState(), (e: NativeError) => e.code === 404);
});

test('summaries keep failure, stale results and "no newer release" distinct', () => {
  const s = parseUpdateState(sample());
  assert.equal(updateSummary(s).tone, 'available');
  assert.match(updateSummary({ ...s, result: 'no_newer_release' }).title, /未发现更高版本/);
  const failed = updateSummary({ ...s, checkStatus: 'failed', reason: 'timeout', stale: true });
  assert.equal(failed.tone, 'error'); assert.match(failed.title, /连接超时/); assert.match(failed.detail, /上次成功检查/);
  assert.doesNotMatch(failed.title + failed.detail, /最新/);
  assert.match(updateSummary({ ...s, result: 'no_compatible_asset' }).title, /Windows x64 安装包/);
  assert.match(updateSummary({ ...s, result: 'development_build', current: { ...s.current, releaseBuild: false } }).title, /开发版本/);
  assert.match(updateSummary({ ...s, checkStatus: 'checking' }).title, /正在检查/);
  assert.equal(reasonText('something_new'), '未知原因');
});

test('dock notice shows once per release, never for dev builds or ignored versions', () => {
  const s = parseUpdateState(sample());
  assert.equal(shouldNotify(s, new Set()), true);
  assert.equal(shouldNotify(s, new Set(['20'])), false);
  assert.equal(shouldNotify({ ...s, preferences: { autoCheck: true, ignoredReleaseId: '20' } }, new Set()), false);
  assert.equal(shouldNotify({ ...s, preferences: { autoCheck: true, ignoredReleaseId: '19' } }, new Set()), true);
  assert.equal(shouldNotify({ ...s, current: { ...s.current, releaseBuild: false } }, new Set()), false);
  assert.equal(shouldNotify({ ...s, result: 'no_compatible_asset' }, new Set()), false);
  assert.equal(shouldNotify(null, new Set()), false);
});

test('polling is fast while checking, brief after start, then slow', () => {
  const s = parseUpdateState(sample());
  assert.equal(pollDelay({ ...s, checkStatus: 'checking' }, 60000), 400);
  assert.equal(pollDelay(s, 3000), 1000);
  assert.equal(pollDelay(s, 25000), 30000);
});

test('release notes become plain text without links, HTML or markup', () => {
  const lines = noteLines('## 新功能\r\n- **加粗** [链接](https://x.invalid)\n- [x] 完成\n\n\n<b>html</b>\n<!-- hidden -->\n---\n1. 第一\n');
  assert.deepEqual(lines, [{ kind: 'heading', text: '新功能' }, { kind: 'item', text: '加粗 链接' }, { kind: 'item', text: '完成' },
    { kind: 'gap', text: '' }, { kind: 'text', text: 'html' }, { kind: 'gap', text: '' }, { kind: 'item', text: '第一' }]);
  assert.deepEqual(noteLines('  \n'), []);
});

test('demo backend follows the contract: throttle, stale IDs, dev builds and legacy 404', () => {
  let t = 1800000000; const demo = createDemoUpdates(() => t);
  const state = () => parseUpdateState(demo.run('updates.getState', {}));
  assert.equal(state().result, 'update_available');
  assert.equal(parseUpdateState(demo.run('updates.check', {})).checkStatus, 'checking');
  assert.equal(state().canCheck, false); t += 3; assert.equal(state().checkStatus, 'succeeded');
  assert.throws(() => demo.run('updates.check', {}), (e: NativeError) => e.code === 429);
  assert.throws(() => demo.run('updates.openDownload', { releaseId: '19', assetId: '120' }), (e: NativeError) => e.code === 409);
  assert.deepEqual(demo.run('updates.openDownload', { releaseId: '20', assetId: '120' }), { opened: true });
  assert.equal(parseUpdateState(demo.run('updates.ignoreRelease', { releaseId: '20' })).preferences.ignoredReleaseId, '20');
  demo.setScenario('development');
  assert.equal(state().latest?.downloadAvailable, false);
  assert.throws(() => demo.run('updates.openDownload', { releaseId: '20', assetId: '120' }), (e: NativeError) => e.code === 409);
  demo.setScenario('failed-stale'); assert.equal(state().stale, true); assert.ok(state().latest);
  demo.setScenario('failed'); assert.equal(state().latest, null); assert.equal(state().checkStatus, 'failed');
  demo.setScenario('legacy'); assert.throws(() => demo.run('updates.getState', {}), (e: NativeError) => e.code === 404);
});
