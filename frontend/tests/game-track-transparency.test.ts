import { test } from 'node:test';
import assert from 'node:assert/strict';
import { gameTrackTransparencyKey, readGameTrackTransparency } from '../src/composables/gameTrackTransparency.ts';

function storage(entries: Record<string, string> = {}) {
  const values = new Map(Object.entries(entries));
  return { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => { values.set(key, value); } };
}

test('track transparency inherits effective appearance once and then stays independent', () => {
  for (const enabled of [false, true]) {
    const saved = storage({ 'musxi-transparent': String(enabled), 'musxi-transparency': '73' });
    const expected = enabled ? 73 : 0;
    assert.equal(readGameTrackTransparency(saved), expected);
    assert.equal(saved.getItem(gameTrackTransparencyKey), String(expected));
    saved.setItem('musxi-transparent', String(!enabled)); saved.setItem('musxi-transparency', '21');
    assert.equal(readGameTrackTransparency(saved), expected);
  }
  assert.equal(readGameTrackTransparency(storage({ 'musxi-theme': 'glass', 'musxi-transparency': '42' })), 42);
});

test('track transparency accepts endpoints and repairs malformed values without losing current appearance', () => {
  for (const value of ['0', '50', '100']) assert.equal(readGameTrackTransparency(storage({ [gameTrackTransparencyKey]: value })), Number(value));
  for (const value of ['', ' ', '-1', '101', 'NaN', 'Infinity', 'unknown']) {
    const saved = storage({ [gameTrackTransparencyKey]: value, 'musxi-transparent': 'true', 'musxi-transparency': '64' });
    assert.equal(readGameTrackTransparency(saved), 64);
    assert.equal(saved.getItem(gameTrackTransparencyKey), '64');
  }
  assert.equal(readGameTrackTransparency({ getItem: () => { throw Error('unavailable'); }, setItem: () => {} }), 0);
  const saved = storage({ 'musxi-transparent': 'true', 'musxi-transparency': '72' });
  assert.equal(readGameTrackTransparency({ ...saved, setItem: () => { throw Error('unavailable'); } }), 72);
});
