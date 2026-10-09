import test from 'node:test';
import assert from 'node:assert/strict';
import { createDemoTransport, isDemoMode } from '../src/demo/context.ts';
import { createNativeClient } from '../src/native/client.ts';
const client = () => createNativeClient(createDemoTransport());

test('recent history moves replayed tracks first and removal leaves playback and queue intact',async()=>{
  const c=client();await c.library.open('demo-focus');
  await c.library.play('library','demo-2');await c.library.play('library','demo-4');await c.library.play('recent','demo-2');
  assert.deepEqual((await c.library.getState()).recent?.map(t=>t.id),['demo-2','demo-4']);
  await c.library.queueNext('recent','demo-4');
  await c.library.recentRemove('demo-2');
  assert.equal((await c.player.getState()).trackId,'demo-2');assert.equal((await c.library.getState()).queue?.length,2);
  await c.library.recentClear();assert.equal((await c.library.getState()).recent?.length,0);assert.equal((await c.player.getState()).playing,true);
  await assert.rejects(c.library.play('recent','demo-2'),/不在当前列表/);
  await c.library.importLocal();await c.library.open('musxi:local');await c.library.play('local','local:0');
  await c.library.logout();await c.library.play('recent','local:0');assert.equal((await c.library.getState()).recent?.[0].id,'local:0');
});

test('demo cloud favorites merge signed-in favorites, refresh after edits, and support playlist playback',async()=>{
  const c=client();await c.library.login(undefined,'netease');
  await c.library.menu('demo-1');await c.library.favorite('demo-1',true,undefined,'netease');
  await c.library.menu('demo-2');await c.library.favorite('demo-2',true,undefined,'netease');
  await c.library.open('musxi:cloud-favorites');
  let state=await c.library.getState();assert.equal(state.playlistName,'云端收藏整合');assert.equal(state.trackCount,5);
  assert.equal(state.favoritesCount,5);
  assert.deepEqual(state.tracks.find(t=>t.id==='demo-1')?.platforms,['netease','kugou']);
  assert.deepEqual(state.tracks.find(t=>t.id==='demo-2')?.platforms,['netease']);
  await c.library.playPlaylist('musxi:cloud-favorites');assert.equal((await c.library.getState()).queue?.length,5);
  assert.deepEqual((await c.library.getState()).recent?.[0].platforms,['netease','kugou']);
  await c.library.menu('demo-2');await c.library.favorite('demo-2',false,undefined,'netease');
  assert.equal((await c.library.getState()).trackCount,4);
  await c.library.open('demo-focus');assert.equal((await c.library.getState()).favoritesCount,4);
  await c.library.open('musxi:cloud-favorites');
  await c.library.logout(undefined,'kugou');state=await c.library.getState();assert.equal(state.trackCount,1);
  assert.deepEqual(state.tracks[0].platforms,['netease']);
  await c.library.logout(undefined,'netease');
  await assert.rejects(c.library.open('musxi:cloud-favorites'),/先登录/);
});

test('demo song menu keeps favorite state isolated by platform',async()=>{
  const c=client();await c.library.login(undefined,'netease');await c.library.menu('demo-2');
  await c.library.favorite('demo-2',true,undefined,'netease');await c.library.menu('demo-2');
  const providers=(await c.library.getState()).menu.providers!;
  assert.equal(providers.find(p=>p.platform==='netease')?.liked,true);
  assert.equal(providers.find(p=>p.platform==='kugou')?.liked,false);
  await c.library.favorite('demo-2',false,undefined,'netease');await c.library.menu('demo-2');
  assert.equal((await c.library.getState()).menu.providers?.find(p=>p.platform==='netease')?.liked,false);
});
test('demo is opt-in and cannot override CEF, smoke or tray', () => {
  assert.equal(isDemoMode({}, '?demo'), true);
  assert.equal(isDemoMode({}, ''), false);
  assert.equal(isDemoMode({cefQuery: () => 1}, '?demo'), false);
  assert.equal(isDemoMode({}, '?demo&smoke'), false);
  assert.equal(isDemoMode({}, '?demo&tray'), false);
});
test('demo playback delivers events and advances only while playing', async () => {
  const c = client(); const events: string[] = [];
  const unsubscribe = c.player.subscribe(e => events.push(e.state.trackId), error => { throw error; });
  try {
    assert.equal((await c.player.getState()).opened, false);
    await c.library.open('demo-focus'); await c.library.play('library', 'demo-2');
    assert.ok(events.includes('demo-2'));
    await new Promise(resolve => setTimeout(resolve, 300));
    assert.ok((await c.player.getState()).positionMs > 0);
    await c.player.pause(); const position = (await c.player.getState()).positionMs;
    await new Promise(resolve => setTimeout(resolve, 300));
    assert.equal((await c.player.getState()).positionMs, position);
    await c.player.seek(1000); await c.library.qualities('demo-2'); await c.library.setQuality('demo-2', 'flac');
    assert.equal((await c.player.getState()).positionMs, 1000);
    assert.equal((await c.player.getState()).playing, false);
    await c.library.login(undefined,'qq');
    await c.library.setPlatform('demo-2','qq');
    assert.equal((await c.library.getState()).now.platform,'qq');
    assert.equal((await c.player.getState()).positionMs,1000);
    assert.equal((await c.player.getState()).playing,false);
  } finally { unsubscribe(); }
});
test('demo volume and seek use native event names', async () => {
  const c = client(); const events: string[] = [];
  const unsubscribe = c.player.subscribe(e => events.push(e.event), error => { throw error; });
  try {
    await c.library.open('demo-focus'); await c.library.play('library', 'demo-2'); await c.player.pause();
    events.length = 0; await c.player.setVolume(30);
    assert.deepEqual(events, ['player.volumeChanged']);
    events.length = 0; await c.player.seek(2000);
    assert.deepEqual(events, ['player.positionChanged']);
  } finally { unsubscribe(); }
});
test('demo lyrics match the native reply contract', async () => {
  const c = client();
  const lyrics = await c.library.lyrics('library', 'demo-2');
  assert.equal(lyrics.status, 'ready'); assert.equal(lyrics.id, 'demo-2'); assert.equal(lyrics.kind, 'synced');
  assert.ok(lyrics.lines.length > 1 && lyrics.lines.every((line, i) => i === 0 || line.timeMs > lyrics.lines[i - 1].timeMs));
  await assert.rejects(c.library.lyrics('library', 'missing'), { code: 404 });
});
test('single play, playlist append and clear preserve current playback', async () => {
  const c = client(); await c.library.open('demo-focus'); await c.library.play('library', 'demo-2');
  assert.equal((await c.library.getState()).queue?.length, 1);
  await c.library.playPlaylist('demo-focus'); await c.library.playPlaylist('demo-focus');
  assert.equal((await c.library.getState()).queue?.length, 3);
  await c.library.queueNext('library', 'demo-6');
  assert.deepEqual((await c.library.getState()).queue?.map(row => row.id), ['demo-2', 'demo-6', 'demo-4']);
  await c.library.queueRemove('demo-2'); assert.equal((await c.player.getState()).trackId, 'demo-2');
  await c.library.queueClear(); assert.equal((await c.player.getState()).playing, true);
  assert.equal((await c.library.getState()).queue?.length, 0);
});
test('demo mutations stay isolated and local import is virtual and deduplicated', async () => {
  const c = client(); const other = client();
  await c.library.favorite('demo-2', true); await c.library.open('demo-favorites');
  assert.equal((await c.library.getState()).trackCount, 5);
  await other.library.open('demo-favorites'); assert.equal((await other.library.getState()).trackCount, 4);
  await c.library.add('demo-2', 'demo-focus'); await c.library.open('demo-focus'); assert.equal((await c.library.getState()).trackCount, 3);
  await c.library.importLocal(); await c.library.importLocal(); await c.library.open('musxi:local');
  const state = await c.library.getState(); assert.equal(state.localPlaylists?.length, 1); assert.equal(state.trackCount, 3);
  await c.library.play('local', state.tracks[0].id);
  await assert.rejects(c.library.favorite(state.tracks[0].id, true), /本地歌曲/);
  await c.library.setPlaybackOrder('random'); await c.library.playPlaylist('musxi:local'); await c.library.skip(1);
  assert.notEqual((await c.player.getState()).trackId, state.tracks[0].id);
  await c.library.setPlaybackOrder('repeat-one'); assert.equal((await c.library.getState()).playbackOrder, 'repeat-one');
  assert.deepEqual(await c.window.getState(), {enabled:false, maximized:false});
});
