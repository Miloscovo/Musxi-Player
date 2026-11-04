import { test } from 'node:test';
import assert from 'node:assert/strict';
import { activeLyricLine, lyricsSource, reuseLyrics } from '../src/composables/lyrics.ts';
import type { Lyrics } from '../src/native/library.ts';

test('repeated lyrics keep rows stable while translation, matching and timing changes arrive', () => {
  const previous: Lyrics = { status: 'ready', source: 'local', id: 'local:1', platform: 'local', origin: 'embedded',
    kind: 'synced', lines: [{ timeMs: 1000, text: 'First' }, { timeMs: 2000, text: 'Second' }], translationPending: true };
  const copy = structuredClone(previous);
  assert.equal(reuseLyrics(null, copy), copy);
  assert.equal(reuseLyrics(previous, copy), previous);
  const complete = reuseLyrics(previous, { ...copy, translationPending: false });
  assert.notEqual(complete, previous);
  assert.equal(complete.lines, previous.lines);
  assert.equal(complete.translationPending, false);
  const lookup = reuseLyrics(previous, { ...copy, translationLookup: { status: 'failed', reason: 'timeout', canRefresh: true } });
  assert.equal(lookup.lines, previous.lines);
  assert.equal(lookup.translationLookup?.reason, 'timeout');
  for (const next of [
    { ...copy, id: 'local:2' },
    { ...copy, lines: [...copy.lines, { timeMs: 3000, text: 'Third' }] },
    { ...copy, lines: [{ ...copy.lines[0], text: 'Matched' }, copy.lines[1]] },
    { ...copy, lines: [{ ...copy.lines[0], timeMs: 500 }, copy.lines[1]] },
    { ...copy, lines: [{ ...copy.lines[0], translation: '译文' }, copy.lines[1]] },
  ]) assert.equal(reuseLyrics(previous, next), next);
});

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
