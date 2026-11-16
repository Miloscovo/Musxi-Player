import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createFullscreenAppOverlayClient, initialFullscreenAppOverlayState, parseFullscreenAppOverlay } from '../src/native/fullscreenAppOverlay.ts';
import type { CefTransport } from '../src/native/transport.ts';

const state = { ...initialFullscreenAppOverlayState, available: true, enabled: true };

test('track height reporting validates bounds, payload, and acknowledgement', async () => {
  const calls: unknown[] = [];
  const client = createFullscreenAppOverlayClient({ cefQueryCancel() {}, cefQuery(query) {
    calls.push(JSON.parse(query.request));query.onSuccess(JSON.stringify({ version: 1, result: true }));return 1;
  } });
  for (const height of [80, 120, 194, 1000]) await client.reportTrackHeight(height);
  assert.deepEqual(calls, [80, 120, 194, 1000].map(height => ({ version: 1, command: 'fullscreenAppOverlay.reportTrackHeight', params: { height } })));
  for (const height of [0, 79, 1001, 120.5, NaN, Infinity]) await assert.rejects(client.reportTrackHeight(height), /Invalid track widget height/);
  assert.equal(calls.length, 4);
  const malformed = createFullscreenAppOverlayClient({ cefQueryCancel() {}, cefQuery(query) { query.onSuccess(JSON.stringify({ version: 1, result: false }));return 1; } });
  await assert.rejects(malformed.reportTrackHeight(120), /Invalid track widget height reply/);
});

test('fullscreen-app overlay state rejects missing fields and nonboolean flags', () => {
  assert.deepEqual(parseFullscreenAppOverlay(state), state);
  for (const bad of [null, [], {}, { ...state, editing: 1 }, { ...state, available: undefined }])
    assert.throws(() => parseFullscreenAppOverlay(bad), /Invalid fullscreen-app overlay state/);
});

test('fullscreen-app overlay setters preserve independent widget selection and command fields', async () => {
  const calls: { command: string; params: object }[] = [];
  const client = createFullscreenAppOverlayClient({ cefQueryCancel() {}, cefQuery(query) {
    calls.push(JSON.parse(query.request)); query.onSuccess(JSON.stringify({ version: 1, result: state })); return 1;
  } });
  assert.deepEqual(await client.getState(), state);
  await client.setEnabled(true); await client.setVisible(false); await client.setLyricsVisible(false);
  await client.setTrackVisible(true); await client.setEditing(true);
  await client.setHideOnHover(true);
  await client.setWindowCompatibility(true); await client.applyWindowCompatibility('123-4'); await client.restoreWindowCompatibility();
  assert.deepEqual(calls, [
    { version: 1, command: 'fullscreenAppOverlay.getState', params: {} },
    { version: 1, command: 'fullscreenAppOverlay.setEnabled', params: { enabled: true } },
    { version: 1, command: 'fullscreenAppOverlay.setVisible', params: { visible: false } },
    { version: 1, command: 'fullscreenAppOverlay.setLyricsVisible', params: { lyricsVisible: false } },
    { version: 1, command: 'fullscreenAppOverlay.setTrackVisible', params: { trackVisible: true } },
    { version: 1, command: 'fullscreenAppOverlay.setEditing', params: { editing: true } },
    { version: 1, command: 'fullscreenAppOverlay.setHideOnHover', params: { hideOnHover: true } },
    { version: 1, command: 'fullscreenAppOverlay.setWindowCompatibility', params: { windowCompatibility: true } },
    { version: 1, command: 'fullscreenAppOverlay.applyWindowCompatibility', params: { id: '123-4' } },
    { version: 1, command: 'fullscreenAppOverlay.restoreWindowCompatibility', params: {} },
  ]);
});

test('hover hiding defaults off for older hosts and rejects malformed flags', () => {
  const { hideOnHover, ...legacy } = state;
  assert.equal(hideOnHover, false);
  assert.deepEqual(parseFullscreenAppOverlay(legacy), state);
  for (const value of [null, 1, 'true']) assert.throws(() => parseFullscreenAppOverlay({ ...state, hideOnHover: value }), /Invalid fullscreen-app overlay state/);
});

test('manual compatibility validates choices, opaque IDs and state, and defaults older hosts off', async () => {
  const { windowCompatibility, windowCompatibilityStatus, windowCompatibilityTarget, ...legacy } = state;
  assert.equal(windowCompatibility, false); assert.equal(windowCompatibilityStatus, 'off'); assert.equal(windowCompatibilityTarget, '');
  assert.deepEqual(parseFullscreenAppOverlay(legacy), state);
  for (const bad of [{ ...state, windowCompatibility: 1 }, { ...state, windowCompatibilityStatus: 'waiting' }, { ...state, windowCompatibilityTarget: null }])
    assert.throws(() => parseFullscreenAppOverlay(bad), /Invalid window compatibility state/);
  const choice = { id: '123-4', title: 'Fullscreen application', program: 'application.exe' };
  let result: unknown = [choice];
  const calls: unknown[] = [];
  const client = createFullscreenAppOverlayClient({ cefQueryCancel() {}, cefQuery(query) { calls.push(JSON.parse(query.request)); query.onSuccess(JSON.stringify({ version: 1, result })); return 1; } });
  assert.deepEqual(await client.listCompatibilityWindows(), result);
  assert.deepEqual(calls, [{ version: 1, command: 'fullscreenAppOverlay.listCompatibilityWindows', params: {} }]);
  for (const bad of [null, {}, [{ id: 'raw-hwnd', title: 'FullscreenApp', program: 'fullscreen-app.exe' }], [{ id: '1-2', title: '', program: 'fullscreen-app.exe' }], [{ id: '1-2', title: 'FullscreenApp', program: 1 }], [choice, choice]]) {
    result = bad; await assert.rejects(client.listCompatibilityWindows(), /Invalid compatibility window list/);
  }
  const count = calls.length;
  for (const id of ['', '123', '0x123', '../fullscreen-app', '1-2-3', '1'.repeat(49) + '-1']) await assert.rejects(client.applyWindowCompatibility(id), /Invalid compatibility window/);
  assert.equal(calls.length, count);
});

test('fullscreen-app overlay subscription validates its event envelope and stops malformed streams', () => {
  let success: (value: string) => void = () => {};
  const cancelled: number[] = []; const seen: unknown[] = []; const errors: unknown[] = [];
  const host: CefTransport = { cefQueryCancel: id => cancelled.push(id), cefQuery(query) {
    assert.equal(query.persistent, true); assert.equal(JSON.parse(query.request).command, 'fullscreenAppOverlay.subscribe');
    success = query.onSuccess; return 9;
  } };
  const stop = createFullscreenAppOverlayClient(host).subscribe(value => seen.push(value), e => errors.push(e));
  success(JSON.stringify({ version: 1, event: 'fullscreenAppOverlay.changed', state }));
  success(JSON.stringify({ version: 1, event: 'desktopLyrics.changed', state }));
  success(JSON.stringify({ version: 1, event: 'fullscreenAppOverlay.changed', state: { ...state, editing: true } }));
  stop();
  assert.deepEqual(seen, [state]); assert.equal(errors.length, 1); assert.deepEqual(cancelled, [9]);
});

test('fullscreen-app overlay synchronously rejected subscriptions cancel once and missing hosts report unavailable', () => {
  const cancelled: number[] = []; const codes: number[] = [];
  const host: CefTransport = { cefQueryCancel: id => cancelled.push(id), cefQuery(query) {
    query.onFailure(404, 'Unsupported command'); return 4;
  } };
  createFullscreenAppOverlayClient(host).subscribe(() => assert.fail('Rejected subscription delivered state'), e => codes.push(e.code))();
  createFullscreenAppOverlayClient({}).subscribe(() => assert.fail('Missing host delivered state'), e => codes.push(e.code))();
  assert.deepEqual(codes, [404, 503]); assert.deepEqual(cancelled, [4]);
});

test('unified floating lyrics defaults and new state are validated', async () => {
  assert.equal(initialFullscreenAppOverlayState.enabled, true);
  assert.equal(initialFullscreenAppOverlayState.visible, false);
  assert.equal(initialFullscreenAppOverlayState.topmost, true);
  assert.equal(initialFullscreenAppOverlayState.lyricsMode, 'multi');
  for (const bad of [{ topmost: 1 }, { lyricsMode: 'dual' }, { lyricsFontSize: 15 }, { lyricsFontSize: 49 }, { lyricsFontSize: 22.5 }])
    assert.throws(() => parseFullscreenAppOverlay({ ...state, ...bad }), /Invalid floating lyrics state/);
  const calls: unknown[] = [];
  const client = createFullscreenAppOverlayClient({ cefQueryCancel() {}, cefQuery(query) {
    calls.push(JSON.parse(query.request)); query.onSuccess(JSON.stringify({ version: 1, result: state })); return 1;
  } });
  await client.setTopmost(false); await client.setLyricsMode('single'); await client.setLyricsFontSize(16);
  assert.deepEqual(calls, [
    { version: 1, command: 'fullscreenAppOverlay.setTopmost', params: { topmost: false } },
    { version: 1, command: 'fullscreenAppOverlay.setLyricsMode', params: { lyricsMode: 'single' } },
    { version: 1, command: 'fullscreenAppOverlay.setLyricsFontSize', params: { lyricsFontSize: 16 } },
  ]);
});

test('lyrics-only editing is independent and older hosts inherit manual editing', () => {
  assert.equal(parseFullscreenAppOverlay({ ...state, editing: false, lyricsEditing: true }).lyricsEditing, true);
  assert.equal(parseFullscreenAppOverlay({ ...state, editing: false, lyricsEditing: true }).editing, false);
  const { lyricsEditing, ...legacy } = state;
  assert.equal(parseFullscreenAppOverlay({ ...legacy, editing: true }).lyricsEditing, true);
  for (const value of [null, 1, 'true'])
    assert.throws(() => parseFullscreenAppOverlay({ ...state, lyricsEditing: value }), /Invalid lyrics editing state/);
});

test('track hover editing is independent and validates legacy fallback', () => {
  const parsed = parseFullscreenAppOverlay({ ...state, trackEditing: true });
  assert.equal(parsed.trackEditing, true);
  assert.equal(parsed.lyricsEditing, false);
  assert.equal(parsed.editing, false);
  const { trackEditing, ...legacy } = state;
  assert.equal(parseFullscreenAppOverlay({ ...legacy, editing: true }).trackEditing, true);
  for (const value of [null, 1, 'true'])
    assert.throws(() => parseFullscreenAppOverlay({ ...state, trackEditing: value }), /Invalid track editing state/);
});
