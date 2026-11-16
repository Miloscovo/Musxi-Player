import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fullscreenAppTrackTransparencyKey, readFullscreenAppTrackTransparency } from '../src/composables/fullscreenAppTrackTransparency.ts';

function storage(entries: Record<string, string> = {}) {
  const values = new Map(Object.entries(entries));
  return { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => { values.set(key, value); } };
}

test('fullscreen application track transparency migrates old values without overriding newer preferences', () => {
  const saved = storage({ 'musxi-game-track-transparency': '73' });
  assert.equal(readFullscreenAppTrackTransparency(saved), 73);
  assert.equal(saved.getItem(fullscreenAppTrackTransparencyKey), '73');
  assert.equal(saved.getItem('musxi-game-track-transparency'), '73');
  saved.setItem(fullscreenAppTrackTransparencyKey, '0');
  assert.equal(readFullscreenAppTrackTransparency(saved), 0);
  const readOnly = storage({ 'musxi-game-track-transparency': '42' });
  assert.equal(readFullscreenAppTrackTransparency({ ...readOnly, setItem: () => { throw Error('read only'); } }), 42);
  const invalid = storage({ 'musxi-game-track-transparency': '101', 'musxi-transparent': 'true', 'musxi-transparency': '64' });
  assert.equal(readFullscreenAppTrackTransparency(invalid), 64);
});

test('track transparency inherits effective appearance once and then stays independent', () => {
  for (const enabled of [false, true]) {
    const saved = storage({ 'musxi-transparent': String(enabled), 'musxi-transparency': '73' });
    const expected = enabled ? 73 : 0;
    assert.equal(readFullscreenAppTrackTransparency(saved), expected);
    assert.equal(saved.getItem(fullscreenAppTrackTransparencyKey), String(expected));
    saved.setItem('musxi-transparent', String(!enabled)); saved.setItem('musxi-transparency', '21');
    assert.equal(readFullscreenAppTrackTransparency(saved), expected);
  }
  assert.equal(readFullscreenAppTrackTransparency(storage({ 'musxi-theme': 'glass', 'musxi-transparency': '42' })), 42);
});

test('track transparency accepts endpoints and repairs malformed values without losing current appearance', () => {
  for (const value of ['0', '50', '100']) assert.equal(readFullscreenAppTrackTransparency(storage({ [fullscreenAppTrackTransparencyKey]: value })), Number(value));
  for (const value of ['', ' ', '-1', '101', 'NaN', 'Infinity', 'unknown']) {
    const saved = storage({ [fullscreenAppTrackTransparencyKey]: value, 'musxi-transparent': 'true', 'musxi-transparency': '64' });
    assert.equal(readFullscreenAppTrackTransparency(saved), 64);
    assert.equal(saved.getItem(fullscreenAppTrackTransparencyKey), '64');
  }
  assert.equal(readFullscreenAppTrackTransparency({ getItem: () => { throw Error('unavailable'); }, setItem: () => {} }), 0);
  const saved = storage({ 'musxi-transparent': 'true', 'musxi-transparency': '72' });
  assert.equal(readFullscreenAppTrackTransparency({ ...saved, setItem: () => { throw Error('unavailable'); } }), 72);
});
