import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { readLyricsBackground, lyricsBackgroundKey } from '../src/composables/lyricsBackground.ts';

test('lyrics background defaults to main and restores a valid cover setting', () => {
  for (const value of [null, '', 'invalid', 'main'])
    assert.equal(readLyricsBackground({ getItem: () => value }), 'main');
  assert.equal(readLyricsBackground({ getItem: key => key === lyricsBackgroundKey ? 'cover' : null }), 'cover');
});
