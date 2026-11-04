import { test } from 'node:test';
import assert from 'node:assert/strict';
import { gameLyricsStyleKey, readGameLyricsStyle } from '../src/composables/gameLyricsStyle.ts';

test('game lyrics keep the flat default and restore only supported styles', () => {
  for (const value of [null, '', 'flat', 'unknown']) {
    assert.equal(readGameLyricsStyle({ getItem: key => { assert.equal(key, gameLyricsStyleKey); return value; } }), 'flat');
  }
  assert.equal(readGameLyricsStyle({ getItem: () => 'perspective' }), 'perspective');
  assert.equal(readGameLyricsStyle({ getItem: () => { throw Error('unavailable'); } }), 'flat');
});
