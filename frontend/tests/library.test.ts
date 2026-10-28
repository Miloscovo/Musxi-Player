import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createNativeClient } from '../src/native/client.ts';
import { parseLibraryState, parseLyrics, safeImage } from '../src/native/library.ts';
import { PlaylistPages } from '../src/composables/playlistPages.ts';
import type { Track } from '../src/native/library.ts';
const snapshot = {
  connected: false, busy: false, user: '', status: '', notice: '', qr: '', avatar: '',
  playlists: [], tracks: [], trackCount: 0, playlistId: '', playlistName: '', search: [],
  keywords: '', searchPage: 1, searchMore: false, searchTotal: 0,
  now: { name: '', artist: '', cover: '' }, menu: { id: '', liked: false, canFavorite: false, playlists: [] },
  operation: { id: '0', status: 'idle', kind: '', error: '' }
};

test('confirmed playlist edits replace all loaded pages, including shifted and emptied pages', async () => {
  for (const removed of [0, 60, 100]) {
    const cache = new PlaylistPages();
    let tracks: Track[] = Array.from({ length: 101 }, (_, index) => ({ id: String(index), name: String(index), artist: '', cover: '', duration: 0, count: 0, editable: false }));
    let edit = '0';
    const read = async (page: number) => parseLibraryState({ ...snapshot, playlistId: 'liked', trackCount: tracks.length,
      tracks: tracks.slice((page - 1) * 50, page * 50), operation: { id: edit, status: 'completed', kind: 'song_update', error: '' } });
    for (let page = 1; page <= 3; page++) await cache.update(await read(page), page, read);
    tracks = tracks.filter(track => track.id !== String(removed)); edit = '1';
    const result = await cache.update(await read(3), 3, read);
    assert.equal(result?.page, 2);
    assert.deepEqual(result?.state.tracks, tracks);
    assert.equal(new Set(result?.state.tracks.map(track => track.id)).size, 100);
    assert.deepEqual((await cache.update(await read(2), 2, read))?.state.tracks, tracks);
  }
});

test('merged favorites replace platform labels and representatives even when the count is unchanged', async () => {
  const cache = new PlaylistPages();
  const row: Track = { id: 'netease:one', name: 'Song', artist: '', cover: '', duration: 0, count: 0, editable: false, platforms: ['netease', 'qq'] };
  const state = parseLibraryState({ ...snapshot, playlistId: 'musxi:cloud-favorites', trackCount: 1, tracks: [row] });
  await cache.update(state, 1, async () => state);
  const changed = { ...state, tracks: [{ ...row, id: 'qq:one', platforms: ['qq' as const] }], operation: { id: '1', status: 'completed' as const, kind: 'song_update', error: '' } };
  assert.deepEqual((await cache.update(changed, 1, async () => changed))?.state.tracks, changed.tracks);
  const empty = { ...changed, trackCount: 0, tracks: [], operation: { ...changed.operation, id: '2' } };
  assert.deepEqual((await cache.update(empty, 1, async () => empty))?.state.tracks, []);
});

test('page refresh refuses mixed playlist snapshots and retries after a racing operation', async () => {
  const cache = new PlaylistPages();
  const before = parseLibraryState({ ...snapshot, playlistId: 'liked', trackCount: 51 });
  await cache.update(before, 1, async () => before);
  const changed = { ...before, operation: { id: '1', status: 'completed' as const, kind: 'song_update', error: '' } };
  assert.equal(await cache.update(changed, 2, async () => ({ ...changed, playlistId: 'other' })), null);
  const later = { ...changed, operation: { ...changed.operation, id: '2', kind: 'song_menu' } };
  const pages: number[] = [];
  assert.ok(await cache.update(later, 2, async page => { pages.push(page); return later; }));
  assert.deepEqual(pages, [1]);
});
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
test('platform metadata is optional for legacy hosts but rejects unknown providers',()=>{
  const favorite={id:'liked',name:'我喜欢',artist:'',cover:'',duration:0,count:1,editable:true,favorite:true};
  assert.equal(parseLibraryState({...snapshot,playlists:[favorite]}).playlists[0].favorite,true);
  assert.throws(()=>parseLibraryState({...snapshot,playlists:[{...favorite,favorite:'yes'}]}),{code:502});
  assert.equal(parseLibraryState({...snapshot,recent:[favorite]}).recent?.length,1);
  assert.throws(()=>parseLibraryState({...snapshot,recent:Array(501).fill(favorite)}),{code:502});
  assert.equal(parseLibraryState({...snapshot,now:{...snapshot.now,platform:'qq'}}).now.platform,'qq');
  assert.deepEqual(parseLibraryState({...snapshot,now:{...snapshot.now,platforms:['qq','netease']}}).now.platforms,['qq','netease']);
  assert.throws(()=>parseLibraryState({...snapshot,now:{...snapshot.now,platforms:['unknown']}}),{code:502});
  const provider={platform:'qq',liked:true,canFavorite:true,playlists:[]};
  assert.equal(parseLibraryState({...snapshot,menu:{...snapshot.menu,providers:[provider]}}).menu.providers?.[0].liked,true);
  assert.throws(()=>parseLibraryState({...snapshot,menu:{...snapshot.menu,providers:[provider,provider]}}),{code:502});
  assert.throws(()=>parseLibraryState({...snapshot,now:{...snapshot.now,platform:'invalid'}}),{code:502});
  const accounts=[{platform:'qq',name:'QQ 音乐',connected:true,user:'Account',error:''}];
  assert.equal(parseLibraryState({...snapshot,accounts,loginPlatform:'qq'}).accounts?.[0].platform,'qq');
  assert.throws(()=>parseLibraryState({...snapshot,accounts:[{...accounts[0],platform:'unknown'}]}),{code:502});
  assert.throws(()=>parseLibraryState({...snapshot,loginPlatform:'unknown'}),{code:502});
  assert.equal(parseLibraryState({...snapshot,accounts:[{...accounts[0],avatar:'data:image/png;base64,AAAA'}]}).accounts?.[0].avatar,'data:image/png;base64,AAAA');
  assert.throws(()=>parseLibraryState({...snapshot,accounts:[{...accounts[0],avatar:123}]}),{code:502});
  const track={id:'song',name:'Song',artist:'Artist',cover:'',duration:0,count:0,editable:false,platforms:['netease','qq']};
  assert.deepEqual(parseLibraryState({...snapshot,search:[track]}).search[0].platforms,['netease','qq']);
  assert.throws(()=>parseLibraryState({...snapshot,search:[{...track,platforms:['unknown']}]}),{code:502});
  assert.throws(()=>parseLibraryState({...snapshot,search:[{...track,platforms:['qq','qq']}]}),{code:502});
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
  await native.library.rescanLocal('local-folder:1');
  assert.deepEqual(seen.at(-1), { version: 1, command: 'library.rescanLocal', params: { id: 'local-folder:1' } });
  await native.library.removeLocalFolder('local-folder:1');
  assert.deepEqual(seen.at(-1), { version: 1, command: 'library.removeLocalFolder', params: { id: 'local-folder:1' } });
  await native.library.removeLocalTrack('local:1','musxi:local');
  assert.deepEqual(seen.at(-1), { version: 1, command: 'library.removeLocalTrack', params: { id: 'local:1', playlistId: 'musxi:local' } });
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
  await native.library.setPlatform('song','qq');
  assert.deepEqual(seen.at(-1), { version: 1, command: 'library.setPlatform', params: { id: 'song', platform: 'qq' } });
  await native.library.favorite('song',false,undefined,'qq');
  assert.deepEqual(seen.at(-1), { version: 1, command: 'library.favorite', params: { id: 'song', enabled:false, platform:'qq' } });
  await native.library.add('song','qq:playlist',undefined,'qq');
  assert.deepEqual(seen.at(-1), { version: 1, command: 'library.add', params: { id: 'song', playlistId:'qq:playlist', platform:'qq' } });
  await native.library.login(undefined,'netease');
  assert.deepEqual(seen.at(-1),{version:1,command:'library.login',params:{platform:'netease'}});
  await native.library.logout(undefined,'qq');
  assert.deepEqual(seen.at(-1),{version:1,command:'library.logout',params:{platform:'qq'}});
});
test('lyrics replies are validated before reaching the UI', () => {
  const reply = { status: 'ready', source: 'local', id: 'local:1', platform: 'local', origin: 'lrc-file', kind: 'synced', lines: [{ timeMs: 500, text: 'line' }] };
  assert.deepEqual(parseLyrics(reply), reply);
  assert.deepEqual(parseLyrics({ ...reply, status: 'pending', platform: '', origin: '', kind: 'none', lines: [] }).status, 'pending');
  for (const bad of [{ status: 'done' }, { source: 'elsewhere' }, { platform: 'spotify' }, { origin: 'web' }, { kind: 'karaoke' },
    { lines: [{ timeMs: -1, text: 'x' }] }, { lines: [{ timeMs: 1 }] }, { lines: 'x' }, { id: 7 }])
    assert.throws(() => parseLyrics({ ...reply, ...bad }), { code: 502 });
});

test('account membership accepts legacy snapshots and validates expiry before rendering',()=>{
  const account={platform:'netease',name:'网易云音乐',connected:true,user:'Fixture',error:''};
  assert.equal(parseLibraryState({...snapshot,accounts:[account]}).accounts?.[0].membership,undefined);
  assert.deepEqual(parseLibraryState({...snapshot,accounts:[{...account,membership:{name:'黑胶 VIP',expiresAt:1800000000000}}]}).accounts?.[0].membership,{name:'黑胶 VIP',expiresAt:1800000000000});
  for(const expiresAt of [NaN,-1,'tomorrow',253402300800000])assert.throws(()=>parseLibraryState({...snapshot,accounts:[{...account,membership:{name:'VIP',expiresAt}}]}),{code:502});
});

test('advanced quality IDs survive native snapshots without accepting unknown tiers',()=>{
 const options=['netease:hires','kugou:high'].map(id=>({id,name:id}));
 const state={...snapshot,qualities:{id:'song',current:'netease:hires',options}};
 assert.deepEqual(parseLibraryState(state).qualities,state.qualities);
 assert.throws(()=>parseLibraryState({...state,qualities:{...state.qualities,options:[{id:'qq:nac',name:'Unsupported'}]}}));
 for(const id of ['netease:jymaster','netease:jyeffect','qq:master','qq:atmos2','kugou:viper_clear','kugou:viper_tape'])
  assert.throws(()=>parseLibraryState({...state,qualities:{...state.qualities,options:[{id,name:'Removed'}]}}));
});
