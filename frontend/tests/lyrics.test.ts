import { test } from 'node:test';
import assert from 'node:assert/strict';
import { activeLyricLine, lyricsSource } from '../src/composables/lyrics.ts';

test('active lyric line follows playback position', () => {
  const lines = [{ timeMs: 1000, text: 'a' }, { timeMs: 3000, text: 'b' }, { timeMs: 3000, text: 'c' }, { timeMs: 8000, text: 'd' }];
  assert.equal(activeLyricLine(lines, 0), -1);
  assert.equal(activeLyricLine(lines, 999), -1);
  assert.equal(activeLyricLine(lines, 1000), 0);
  assert.equal(activeLyricLine(lines, 2999), 0);
  // Duplicate timestamps highlight the last line that has started.
  assert.equal(activeLyricLine(lines, 3000), 2);
  assert.equal(activeLyricLine(lines, 999999), 3);
  assert.equal(activeLyricLine([], 5000), -1);
});

test('lyrics requests use a source the Core accepts for the playing song', () => {
  assert.equal(lyricsSource('local:3'), 'local');
  assert.equal(lyricsSource('netease:1'), 'queue');
  assert.equal(lyricsSource('demo-1'), 'queue');
});
