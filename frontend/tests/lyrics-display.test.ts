import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readLyricsDisplay } from '../src/composables/lyricsDisplay.ts';

test('main lyrics default to centered 20px and restore supported display preferences', () => {
  const read = (value: string | null) => readLyricsDisplay({ getItem: () => value });
  assert.deepEqual(read(null), { alignment: 'center', fontSize: 20 });
  assert.deepEqual(read('{"alignment":"left","fontSize":32}'), { alignment: 'left', fontSize: 32 });
  assert.deepEqual(read('{"alignment":"right","fontSize":15}'), { alignment: 'center', fontSize: 20 });
  assert.deepEqual(read('{"fontSize":33}'), { alignment: 'center', fontSize: 20 });
  assert.deepEqual(read('{"fontSize":20.5}'), { alignment: 'center', fontSize: 20 });
  assert.deepEqual(read('null'), { alignment: 'center', fontSize: 20 });
  assert.deepEqual(read('broken'), { alignment: 'center', fontSize: 20 });
  assert.deepEqual(readLyricsDisplay({ getItem: () => { throw Error('unavailable'); } }), { alignment: 'center', fontSize: 20 });
});
