import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createNativeClient } from '../src/native/client.ts';
import { parseLibraryState, safeImage } from '../src/native/library.ts';
const snapshot = {
  connected: false, busy: false, user: '', status: '', notice: '', qr: '', avatar: '',
  playlists: [], tracks: [], trackCount: 0, playlistId: '', playlistName: '', search: [],
  keywords: '', searchPage: 1, searchMore: false, searchTotal: 0,
  now: { name: '', artist: '', cover: '' }, menu: { id: '', liked: false, canFavorite: false, playlists: [] },
  operation: { id: '0', status: 'idle', kind: '', error: '' }
};
test('library snapshot rejects invalid rows and operation status', () => {
  assert.deepEqual(parseLibraryState(snapshot), snapshot);
  assert.throws(() => parseLibraryState({ ...snapshot, tracks: [{ id: '1' }] }), { code: 502 });
  assert.throws(() => parseLibraryState({ ...snapshot, operation: { ...snapshot.operation, status: 'made-up' } }), { code: 502 });
  assert.throws(() => parseLibraryState({ ...snapshot, trackCount: -1 }), { code: 502 });
  assert.throws(() => parseLibraryState({ ...snapshot, queue: [{ id: 'invalid' }] }), { code: 502 });
  assert.throws(() => parseLibraryState({ ...snapshot, queueCurrentId: 5 }), { code: 502 });
  assert.throws(() => parseLibraryState({ ...snapshot, playbackOrder: 'invalid' }), { code: 502 });
  assert.throws(() => parseLibraryState({ ...snapshot, now: { ...snapshot.now, liked: 'yes' } }), { code: 502 });
  assert.throws(() => parseLibraryState({ ...snapshot, qualities: { id:'song',current:'',options:[{id:'invalid',name:'invalid'}] } }), { code: 502 });
});
test('images allow only raster data, never remote or executable URLs', () => {
  assert.equal(safeImage('https://example.com/a.jpg'), '');
  assert.equal(safeImage('data:image/svg+xml;base64,AAAA'), '');
  assert.equal(safeImage('data:image/png;base64,AAAA'), 'data:image/png;base64,AAAA');
});
test('library client sends typed business actions and preserves operation IDs', async () => {
  const seen: unknown[] = [];
  const native = createNativeClient({ cefQuery(q) {
    seen.push(JSON.parse(q.request)); q.onSuccess(JSON.stringify({ version: 1, result: { id: '14' } })); return 1;
  }, cefQueryCancel() {} });
  assert.deepEqual(await native.library.play('search', 'track-id'), { id: '14' });
  await native.library.favorite('track-id', false); await native.library.add('track-id', 'playlist-id');
  assert.deepEqual(seen, [
    { version: 1, command: 'library.play', params: { source: 'search', id: 'track-id' } },
    { version: 1, command: 'library.favorite', params: { id: 'track-id', enabled: false } },
    { version: 1, command: 'library.add', params: { id: 'track-id', playlistId: 'playlist-id' } }
  ]);
  await native.library.importLocal();
  assert.deepEqual(seen.at(-1), { version: 1, command: 'library.importLocal', params: {} });
  await native.library.play('local', 'local:1');
  assert.deepEqual(seen.at(-1), { version: 1, command: 'library.play', params: { source: 'local', id: 'local:1' } });
  await native.library.queueNext('local', 'local:1');
  assert.deepEqual(seen.at(-1), { version: 1, command: 'library.queueNext', params: { source: 'local', id: 'local:1' } });
  await native.library.queueRemove('local:1');
  assert.deepEqual(seen.at(-1), { version: 1, command: 'library.queueRemove', params: { id: 'local:1' } });
  await native.library.queueClear();
  assert.deepEqual(seen.at(-1), { version: 1, command: 'library.queueClear', params: {} });
  await native.library.playPlaylist('playlist-id');
  assert.deepEqual(seen.at(-1), { version: 1, command: 'library.playPlaylist', params: { id: 'playlist-id' } });
  await native.library.setPlaybackOrder('repeat-one');
  assert.deepEqual(seen.at(-1), { version: 1, command: 'library.setPlaybackOrder', params: { order: 'repeat-one' } });
  await native.library.qualities('song');
  assert.deepEqual(seen.at(-1), { version: 1, command: 'library.qualities', params: { id: 'song' } });
  await native.library.setQuality('song','flac');
  assert.deepEqual(seen.at(-1), { version: 1, command: 'library.setQuality', params: { id: 'song', quality: 'flac' } });
});
