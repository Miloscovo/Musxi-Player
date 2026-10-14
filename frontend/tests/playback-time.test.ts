import { test } from 'node:test';
import assert from 'node:assert/strict';
import { formatPlaybackTime, playbackTimes } from '../src/composables/playbackTime.ts';

test('playback times support hours, clamp seeking and keep rounded seconds consistent', () => {
  assert.equal(formatPlaybackTime(3661000), '1:01:01');
  assert.equal(formatPlaybackTime(-1), '0:00');
  assert.deepEqual(playbackTimes(true, 180999, 60999), { elapsed: '1:00', total: '3:00', remaining: '2:00' });
  assert.deepEqual(playbackTimes(true, 180000, 200000), { elapsed: '3:00', total: '3:00', remaining: '0:00' });
  assert.equal(playbackTimes(true, 60000, NaN).elapsed, '0:00');
  for (const duration of [0, -1, Infinity, NaN]) assert.equal(playbackTimes(true, duration, 1000).remaining, '--:--');
  assert.equal(playbackTimes(false, 60000, 20000).total, '--:--');
});
