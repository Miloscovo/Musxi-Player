import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createGameOverlayClient, initialGameOverlayState, parseGameOverlay } from '../src/native/gameOverlay.ts';
import type { CefTransport } from '../src/native/transport.ts';

const state = { ...initialGameOverlayState, available: true, enabled: true };

test('track height reporting validates bounds, payload, and acknowledgement', async () => {
  const calls: unknown[] = [];
  const client = createGameOverlayClient({ cefQueryCancel() {}, cefQuery(query) {
    calls.push(JSON.parse(query.request));query.onSuccess(JSON.stringify({ version: 1, result: true }));return 1;
  } });
  for (const height of [80, 120, 194, 1000]) await client.reportTrackHeight(height);
  assert.deepEqual(calls, [80, 120, 194, 1000].map(height => ({ version: 1, command: 'gameOverlay.reportTrackHeight', params: { height } })));
  for (const height of [0, 79, 1001, 120.5, NaN, Infinity]) await assert.rejects(client.reportTrackHeight(height), /Invalid track widget height/);
  assert.equal(calls.length, 4);
  const malformed = createGameOverlayClient({ cefQueryCancel() {}, cefQuery(query) { query.onSuccess(JSON.stringify({ version: 1, result: false }));return 1; } });
  await assert.rejects(malformed.reportTrackHeight(120), /Invalid track widget height reply/);
});

test('game overlay state rejects missing fields and nonboolean flags', () => {
  assert.deepEqual(parseGameOverlay(state), state);
  for (const bad of [null, [], {}, { ...state, editing: 1 }, { ...state, available: undefined }, { ...state, panelVisible: 'false' }])
    assert.throws(() => parseGameOverlay(bad), /Invalid game overlay state/);
});

test('game overlay setters preserve independent widget selection and command fields', async () => {
  const calls: { command: string; params: object }[] = [];
  const client = createGameOverlayClient({ cefQueryCancel() {}, cefQuery(query) {
    calls.push(JSON.parse(query.request)); query.onSuccess(JSON.stringify({ version: 1, result: state })); return 1;
  } });
  assert.deepEqual(await client.getState(), state);
  await client.setEnabled(true); await client.setVisible(false); await client.setLyricsVisible(false);
  await client.setTrackVisible(true); await client.setEditing(true); await client.setPanelVisible(false);
  await client.setHideOnHover(true);
  await client.setMinecraftCompatibility(true);
  await client.setWindowCompatibility(true); await client.applyWindowCompatibility('123-4'); await client.restoreWindowCompatibility();
  assert.deepEqual(calls, [
    { version: 1, command: 'gameOverlay.getState', params: {} },
    { version: 1, command: 'gameOverlay.setEnabled', params: { enabled: true } },
    { version: 1, command: 'gameOverlay.setVisible', params: { visible: false } },
    { version: 1, command: 'gameOverlay.setLyricsVisible', params: { lyricsVisible: false } },
    { version: 1, command: 'gameOverlay.setTrackVisible', params: { trackVisible: true } },
    { version: 1, command: 'gameOverlay.setEditing', params: { editing: true } },
    { version: 1, command: 'gameOverlay.setPanelVisible', params: { panelVisible: false } },
    { version: 1, command: 'gameOverlay.setHideOnHover', params: { hideOnHover: true } },
    { version: 1, command: 'gameOverlay.setMinecraftCompatibility', params: { minecraftCompatibility: true } },
    { version: 1, command: 'gameOverlay.setWindowCompatibility', params: { windowCompatibility: true } },
    { version: 1, command: 'gameOverlay.applyWindowCompatibility', params: { id: '123-4' } },
    { version: 1, command: 'gameOverlay.restoreWindowCompatibility', params: {} },
  ]);
});

test('hover hiding defaults off for older hosts and rejects malformed flags', () => {
  const { hideOnHover, ...legacy } = state;
  assert.equal(hideOnHover, false);
  assert.deepEqual(parseGameOverlay(legacy), state);
  for (const value of [null, 1, 'true']) assert.throws(() => parseGameOverlay({ ...state, hideOnHover: value }), /Invalid game overlay state/);
});

test('manual compatibility validates choices, opaque IDs and state, and defaults older hosts off', async () => {
  const { windowCompatibility, windowCompatibilityStatus, windowCompatibilityTarget, ...legacy } = state;
  assert.equal(windowCompatibility, false); assert.equal(windowCompatibilityStatus, 'off'); assert.equal(windowCompatibilityTarget, '');
  assert.deepEqual(parseGameOverlay(legacy), state);
  for (const bad of [{ ...state, windowCompatibility: 1 }, { ...state, windowCompatibilityStatus: 'waiting' }, { ...state, windowCompatibilityTarget: null }])
    assert.throws(() => parseGameOverlay(bad), /Invalid window compatibility state/);
  const choice = { id: '123-4', title: 'Minecraft', program: 'javaw.exe' };
  let result: unknown = [choice];
  const calls: unknown[] = [];
  const client = createGameOverlayClient({ cefQueryCancel() {}, cefQuery(query) { calls.push(JSON.parse(query.request)); query.onSuccess(JSON.stringify({ version: 1, result })); return 1; } });
  assert.deepEqual(await client.listCompatibilityWindows(), result);
  assert.deepEqual(calls, [{ version: 1, command: 'gameOverlay.listCompatibilityWindows', params: {} }]);
  for (const bad of [null, {}, [{ id: 'raw-hwnd', title: 'Game', program: 'game.exe' }], [{ id: '1-2', title: '', program: 'game.exe' }], [{ id: '1-2', title: 'Game', program: 1 }], [choice, choice]]) {
    result = bad; await assert.rejects(client.listCompatibilityWindows(), /Invalid compatibility window list/);
  }
  const count = calls.length;
  for (const id of ['', '123', '0x123', '../game', '1-2-3', '1'.repeat(49) + '-1']) await assert.rejects(client.applyWindowCompatibility(id), /Invalid compatibility window/);
  assert.equal(calls.length, count);
});

test('Minecraft compatibility defaults for older hosts and validates new state fields', () => {
  const { minecraftCompatibility, minecraftStatus, ...legacy } = state;
  assert.equal(minecraftCompatibility, false); assert.equal(minecraftStatus, 'off');
  assert.deepEqual(parseGameOverlay(legacy), state);
  for (const status of ['off', 'waiting', 'windowedRequired', 'applied', 'userChanged', 'error'] as const)
    assert.equal(parseGameOverlay({ ...state, minecraftCompatibility: true, minecraftStatus: status }).minecraftStatus, status);
  for (const bad of [{ ...state, minecraftCompatibility: 1 }, { ...state, minecraftStatus: 'unknown' }, { ...state, minecraftStatus: null }])
    assert.throws(() => parseGameOverlay(bad), /Invalid Minecraft compatibility state/);
});

test('game overlay subscription validates its event envelope and stops malformed streams', () => {
  let success: (value: string) => void = () => {};
  const cancelled: number[] = []; const seen: unknown[] = []; const errors: unknown[] = [];
  const host: CefTransport = { cefQueryCancel: id => cancelled.push(id), cefQuery(query) {
    assert.equal(query.persistent, true); assert.equal(JSON.parse(query.request).command, 'gameOverlay.subscribe');
    success = query.onSuccess; return 9;
  } };
  const stop = createGameOverlayClient(host).subscribe(value => seen.push(value), e => errors.push(e));
  success(JSON.stringify({ version: 1, event: 'gameOverlay.changed', state }));
  success(JSON.stringify({ version: 1, event: 'desktopLyrics.changed', state }));
  success(JSON.stringify({ version: 1, event: 'gameOverlay.changed', state: { ...state, editing: true } }));
  stop();
  assert.deepEqual(seen, [state]); assert.equal(errors.length, 1); assert.deepEqual(cancelled, [9]);
});

test('game overlay synchronously rejected subscriptions cancel once and missing hosts report unavailable', () => {
  const cancelled: number[] = []; const codes: number[] = [];
  const host: CefTransport = { cefQueryCancel: id => cancelled.push(id), cefQuery(query) {
    query.onFailure(404, 'Unsupported command'); return 4;
  } };
  createGameOverlayClient(host).subscribe(() => assert.fail('Rejected subscription delivered state'), e => codes.push(e.code))();
  createGameOverlayClient({}).subscribe(() => assert.fail('Missing host delivered state'), e => codes.push(e.code))();
  assert.deepEqual(codes, [404, 503]); assert.deepEqual(cancelled, [4]);
});
