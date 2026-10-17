import test from 'node:test';
import assert from 'node:assert/strict';
import { parseUpdateState, createUpdatesClient } from '../src/native/updates.ts';
import { createDemoUpdates, demoUpdateScenarios } from '../src/demo/updates.ts';
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

test('download state parser accepts idle with empty IDs and validates progress fields', () => {
  const withDownload = (download: any) => ({ ...sample(), capabilities: { installerDownload: true },
    latest: { ...sample().latest, inAppDownloadAvailable: true }, download });
  // idle state allows empty IDs, version and reason
  const idle = parseUpdateState(withDownload({ status: 'idle', reason: '', releaseId: '', assetId: '', version: '',
    receivedBytes: 0, totalBytes: 0, canCancel: false, canInstall: false, superseded: false }));
  assert.equal(idle.download?.status, 'idle');
  assert.equal(idle.download?.releaseId, '');
  assert.equal(idle.download?.assetId, '');
  assert.equal(idle.download?.version, '');
  assert.equal(parseUpdateState({ ...sample(), download: null }).download, null);
  assert.equal(parseUpdateState(sample()).download, undefined);
  // cancelled state allows empty IDs and version
  const cancelled = parseUpdateState(withDownload({ status: 'cancelled', reason: '', releaseId: '', assetId: '', version: '',
    receivedBytes: 0, totalBytes: 0, canCancel: false, canInstall: false, superseded: false }));
  assert.equal(cancelled.download?.status, 'cancelled');
  // ready state requires non-empty IDs and version
  const ready = parseUpdateState(withDownload({ status: 'ready', reason: '', releaseId: '20', assetId: '120', version: '0.5.0',
    receivedBytes: 224013707, totalBytes: 224013707, canCancel: false, canInstall: true, superseded: false }));
  assert.equal(ready.download?.status, 'ready');
  assert.equal(ready.download?.canInstall, true);
  assert.equal(ready.download?.totalBytes, 224013707);
  // downloading state requires progress fields
  const downloading = parseUpdateState(withDownload({ status: 'downloading', reason: '', releaseId: '20', assetId: '120', version: '0.5.0',
    receivedBytes: 50000000, totalBytes: 224013707, canCancel: true, canInstall: false, superseded: false }));
  assert.equal(downloading.download?.status, 'downloading');
  assert.equal(downloading.download?.receivedBytes, 50000000);
  // failed state with reason
  const failed = parseUpdateState(withDownload({ status: 'failed', reason: 'network_error', releaseId: '20', assetId: '120', version: '0.5.0',
    receivedBytes: 0, totalBytes: 224013707, canCancel: false, canInstall: false, superseded: false }));
  assert.equal(failed.download?.status, 'failed');
  assert.equal(failed.download?.reason, 'network_error');
  // superseded ready state cannot install
  const superseded = parseUpdateState(withDownload({ status: 'ready', reason: '', releaseId: '20', assetId: '120', version: '0.5.0',
    receivedBytes: 224013707, totalBytes: 224013707, canCancel: false, canInstall: false, superseded: true }));
  assert.equal(superseded.download?.canInstall, false);
  assert.equal(superseded.download?.superseded, true);
  // reject invalid status
  assert.throws(() => parseUpdateState(withDownload({ status: 'done', reason: '', releaseId: '20', assetId: '120', version: '0.5.0', receivedBytes: 0, totalBytes: 0, canCancel: false, canInstall: false, superseded: false })), NativeError);
  // reject ready with empty IDs
  assert.throws(() => parseUpdateState(withDownload({ status: 'ready', reason: '', releaseId: '', assetId: '120', version: '0.5.0',
    receivedBytes: 0, totalBytes: 0, canCancel: false, canInstall: true, superseded: false })), NativeError);
  // reject negative progress
  assert.throws(() => parseUpdateState(withDownload({ status: 'downloading', reason: '', releaseId: '20', assetId: '120', version: '0.5.0',
    receivedBytes: -1, totalBytes: 100, canCancel: true, canInstall: false, superseded: false })), NativeError);
  for (const invalid of ['1e3', '0', '-2', 'anything', '2'.repeat(33)]) {
    assert.throws(() => parseUpdateState(withDownload({ ...ready.download, releaseId: invalid })), NativeError);
    assert.throws(() => parseUpdateState(withDownload({ ...idle.download, assetId: invalid })), NativeError);
  }
  for (const patch of [{ releaseId: '20' }, { totalBytes: 1 }, { canInstall: true }, { canCancel: true }]) {
    assert.throws(() => parseUpdateState(withDownload({ ...idle.download, ...patch })), NativeError);
  }
  assert.throws(() => parseUpdateState(withDownload({ ...ready.download, superseded: true })), NativeError);
  assert.throws(() => parseUpdateState(withDownload({ ...ready.download, status: 'verifying' })), NativeError);
});

test('download client preserves full state replies and sends only release/asset IDs', async () => {
  const { transport, sent } = host((command) => {
    if (command === 'updates.startDownload' || command === 'updates.cancelDownload' || command === 'updates.install') {
      return { ...sample(), capabilities: { installerDownload: true },
        latest: { ...sample().latest, inAppDownloadAvailable: true },
        download: { status: command === 'updates.startDownload' ? 'downloading' : command === 'updates.cancelDownload' ? 'cancelled' : 'installing',
          reason: command === 'updates.cancelDownload' ? 'cancelled' : '', releaseId: '20', assetId: '120', version: '0.5.0',
          receivedBytes: command === 'updates.install' ? 224013707 : 50000000, totalBytes: 224013707,
          canCancel: command === 'updates.startDownload', canInstall: false, superseded: false } };
    }
    return sample();
  });
  const client = createUpdatesClient(transport);
  const result1 = await client.startDownload('20', '120');
  const result2 = await client.cancelDownload();
  const result3 = await client.install('20', '120');
  assert.equal(result1.download?.status, 'downloading');
  assert.equal(result1.download?.canCancel, true);
  assert.equal(result1.download?.receivedBytes, 50000000);
  assert.equal(result2.download?.status, 'cancelled');
  assert.equal(result2.download?.reason, 'cancelled');
  assert.equal(result3.download?.status, 'installing');
  assert.equal(result3.download?.canInstall, false);
  for (const result of [result1, result2, result3]) {
    assert.equal(result.capabilities?.installerDownload, true);
    assert.equal(result.latest?.inAppDownloadAvailable, true);
    assert.equal(result.download?.releaseId, '20');
    assert.deepEqual(result.current, sample().current);
  }
  assert.deepEqual(sent.map(s => [s.command, s.params]), [
    ['updates.startDownload', { releaseId: '20', assetId: '120' }],
    ['updates.cancelDownload', {}],
    ['updates.install', { releaseId: '20', assetId: '120' }]
  ]);
});

test('download commands reject malformed replies and preserve backend error codes', async () => {
  for (const action of [
    (client: ReturnType<typeof createUpdatesClient>) => client.startDownload('20', '120'),
    (client: ReturnType<typeof createUpdatesClient>) => client.cancelDownload(),
    (client: ReturnType<typeof createUpdatesClient>) => client.install('20', '120')
  ]) {
    const malformed = createUpdatesClient(host(() => ({ ...sample(), download: { status: 'ready' } })).transport);
    await assert.rejects(action(malformed), (e: NativeError) => e.code === 502);
    for (const [code, message] of [[409, 'release_changed'], [500, 'storage_error']] as const) {
      const failed = createUpdatesClient(host(() => { throw new NativeError(code, message); }).transport);
      await assert.rejects(action(failed), (e: NativeError) => e.code === code && e.message === message);
    }
  }
});

test('polling accelerates during download and returns to normal after idle', () => {
  const s = parseUpdateState(sample());
  const withStatus = (status: string) => {
    if (status === 'idle' || status === 'cancelled') {
      return { ...s, download: { status: status as any, reason: '', releaseId: '', assetId: '', version: '',
        receivedBytes: 0, totalBytes: 0, canCancel: false, canInstall: false, superseded: false } };
    }
    return { ...s, download: { status: status as any, reason: '', releaseId: '20', assetId: '120', version: '0.5.0',
      receivedBytes: status === 'downloading' ? 50000000 : 0, totalBytes: 224013707, canCancel: status !== 'ready', canInstall: status === 'ready', superseded: false } };
  };
  assert.equal(pollDelay(withStatus('preparing'), 60000), 450);
  assert.equal(pollDelay(withStatus('downloading'), 60000), 450);
  assert.equal(pollDelay(withStatus('verifying'), 60000), 450);
  assert.equal(pollDelay(withStatus('ready'), 60000), 30000);
  assert.equal(pollDelay(withStatus('idle'), 60000), 30000);
  assert.equal(pollDelay({ ...s, checkStatus: 'checking' }, 60000), 400);
});

test('every demo scenario returns a valid state or the explicit legacy 404', () => {
  const demo = createDemoUpdates(() => 1800000000);
  for (const scenario of Object.keys(demoUpdateScenarios) as (keyof typeof demoUpdateScenarios)[]) {
    demo.setScenario(scenario);
    if (scenario === 'legacy') assert.throws(() => demo.run('updates.getState', {}), (e: NativeError) => e.code === 404);
    else parseUpdateState(demo.run('updates.getState', {}));
  }
});

test('demo downloads progress, cancel, retry, reuse ready and install only current verified packages', () => {
  let t = 1800000000;
  const demo = createDemoUpdates(() => t);
  const state = () => parseUpdateState(demo.run('updates.getState', {}));
  const ids = { releaseId: '20', assetId: '120' };
  const error = (command: string, params: object, code: number, message: string) =>
    assert.throws(() => demo.run(command, params as Record<string, unknown>), (e: NativeError) => e.code === code && e.message === message);
  error('updates.startDownload', ids, 409, 'download_unavailable');
  demo.setScenario('download-idle');
  assert.equal(state().download?.releaseId, '');
  error('updates.startDownload', { ...ids, url: 'https://invalid' }, 400, 'invalid_params');
  demo.run('updates.startDownload', ids);
  assert.equal(state().download?.status, 'preparing');
  error('updates.startDownload', ids, 409, 'download_busy');
  error('updates.install', ids, 409, 'download_not_ready');
  t += 2;
  assert.equal(state().download?.status, 'downloading');
  assert.ok(state().download!.receivedBytes > 0);
  demo.run('updates.cancelDownload', {});
  assert.equal(state().download?.status, 'cancelled');
  t += 10;
  assert.equal(state().download?.status, 'cancelled');
  demo.run('updates.startDownload', ids);
  t += 3;
  assert.equal(state().download?.status, 'verifying');
  assert.equal(state().download?.canInstall, false);
  t += 1;
  assert.equal(state().download?.canInstall, true);
  assert.equal(parseUpdateState(demo.run('updates.startDownload', ids)).download?.status, 'ready');
  error('updates.install', { releaseId: '19', assetId: '120' }, 409, 'release_changed');
  demo.run('updates.install', ids);
  assert.equal(state().download?.status, 'installing');
  error('updates.cancelDownload', {}, 409, 'shutting_down');

  demo.setScenario('download-install-failed');
  error('updates.install', ids, 500, 'installer_start_failed');
  assert.equal(state().download?.canInstall, true);
  demo.setScenario('download-checksum');
  error('updates.install', ids, 409, 'download_not_ready');
  demo.run('updates.startDownload', ids); t += 4;
  assert.equal(state().download?.status, 'ready');
  demo.setScenario('download-superseded');
  assert.equal(state().download?.superseded, true);
  assert.equal(state().download?.canInstall, false);
  error('updates.install', ids, 409, 'release_changed');
  demo.run('updates.cancelDownload', {});
  demo.run('updates.startDownload', { releaseId: '30', assetId: '130' }); t += 4;
  assert.equal(state().download?.superseded, false);
  assert.equal(state().download?.version, '0.6.0');
  assert.equal(state().download?.canInstall, true);
});
