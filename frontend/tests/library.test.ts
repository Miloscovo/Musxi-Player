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
});
