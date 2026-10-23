import assert from 'node:assert/strict';
import test from 'node:test';
import { isCurrentSong } from '../src/composables/songPlayback.ts';

test('song playback indicators use IDs and only fall back for a matching aggregated source', () => {
  const song = { id: 'kugou:one', name: 'Song', artist: 'Artist' };
  assert.equal(isCurrentSong(song, { opened: true, trackId: song.id }), true);
  assert.equal(isCurrentSong(song, { opened: false, trackId: song.id }), false);
  assert.equal(isCurrentSong(song, undefined), false);
  const playback = { opened: true, trackId: 'qq:two' };
  const now = { name: song.name, artist: song.artist, cover: '', platform: 'qq' as const };
  assert.equal(isCurrentSong(song, playback, now), false);
  assert.equal(isCurrentSong({ ...song, platforms: ['kugou', 'qq'] }, playback, now), true);
  assert.equal(isCurrentSong({ ...song, platforms: ['kugou', 'netease'] }, playback, now), false);
  assert.equal(isCurrentSong({ ...song, name: 'Other', platforms: ['kugou', 'qq'] }, playback, now), false);
});
