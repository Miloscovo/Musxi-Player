import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fullscreenAppLyricsStyleKey, readFullscreenAppLyricsStyle } from '../src/composables/fullscreenAppLyricsStyle.ts';

test('fullscreen-app lyrics keep the flat default and restore only supported styles', () => {
  for (const value of [null, '', 'flat', 'unknown']) {
    assert.equal(readFullscreenAppLyricsStyle({ getItem: key => { assert.equal(key, fullscreenAppLyricsStyleKey); return value; } }), 'flat');
  }
  assert.equal(readFullscreenAppLyricsStyle({ getItem: () => 'perspective' }), 'perspective');
  assert.equal(readFullscreenAppLyricsStyle({ getItem: () => { throw Error('unavailable'); } }), 'flat');
});

test('fullscreen application lyrics migrate old preferences once and preserve them if saving fails', () => {
  const values = new Map([['musxi-game-lyrics-style', 'perspective']]);
  const storage = { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => { values.set(key, value); } };
  assert.equal(readFullscreenAppLyricsStyle(storage), 'perspective');
  assert.equal(values.get(fullscreenAppLyricsStyleKey), 'perspective');
  assert.equal(values.get('musxi-game-lyrics-style'), 'perspective');
  storage.setItem(fullscreenAppLyricsStyleKey, 'flat');
  assert.equal(readFullscreenAppLyricsStyle(storage), 'flat');
  values.delete(fullscreenAppLyricsStyleKey);
  assert.equal(readFullscreenAppLyricsStyle({ ...storage, setItem: () => { throw Error('read only'); } }), 'perspective');
});
