'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { Adapter, track, playlist } = require('./bridge.cjs');
test('cover metadata normalizes image sizes and does not confuse author avatars with playlist covers', () => {
  assert.equal(playlist({listid:1,pic:'http://imge.kugou.com/{size}/cover.jpg'}).cover,'https://imge.kugou.com/200/cover.jpg');
  assert.equal(playlist({listid:1,create_user_pic:'https://imge.kugou.com/avatar.jpg'}).cover,'');
  assert.equal(track({trans_param:{union_cover:'https://imge.kugou.com/{size}/album.jpg'}}).cover,'https://imge.kugou.com/200/album.jpg');
  assert.equal(track({cover:'file:///private.png'}).cover,'');
});
test('cover batches deduplicate URLs and isolate unavailable images', async () => {
  const original=global.fetch;let calls=0;
  global.fetch=async url=>{++calls;if(url.includes('missing')) throw Error('offline');return {ok:true,body:(async function*(){yield Buffer.from('fixture');})()};};
  try {
    const a=new Adapter(async()=>{throw Error('cover downloads must not call account API');});
    const r=await a.run({op:'covers',urls:['https://imge.kugou.com/a.jpg','https://imge.kugou.com/a.jpg','http://localhost/private','https://imge.kugou.com/missing.jpg']});
    assert.equal(calls,2);assert.equal(r.images.length,2);assert.ok(r.images[0].image.startsWith('data:image/jpeg;base64,'));assert.equal(r.images[1].image,'');
  } finally {global.fetch=original;}
});
const response = data => ({ body: { status: 1, data }, cookie: [] });
test('song labels separate title and artists while preserving source filename for cloud writes',()=>{
  const a=track({name:'MIKS - Left Alone.mp3'});assert.equal(a.name,'Left Alone');assert.equal(a.artist,'MIKS');assert.equal(a.fileName,'MIKS - Left Alone.mp3');
  const b=track({name:'Vicetone、Meron Ryan - Walk Thru Fire.mp3',singerinfo:[{name:'Vicetone'},{name:'Meron Ryan'}]});
  assert.equal(b.name,'Walk Thru Fire');assert.equal(b.artist,'Vicetone、Meron Ryan');
  assert.equal(track({name:'Love - Live',singername:'Someone'}).name,'Love - Live');
  assert.equal(track({songname:'Song - Live',singername:'AC-DC'}).name,'Song - Live');
  assert.equal(track({name:'Dance Fruits Music, Steve Void - Toosie Slide (Explicit).mp3'}).name,'Toosie Slide (Explicit)');
});
test('favorites sort globally by newest collection time, keeping ties stable and other playlists unchanged',async()=>{
  const a=new Adapter(async(name,p)=>name==='user_playlist'?response({total:2,info:[{listid:1,is_def:2},{listid:2,is_def:1}]}):
    response({total:5,info:p.page===1?[{fileid:1,collecttime:1700000000},{fileid:2,collecttime:'1700000030'}]:[{fileid:3,collecttime:1700000020000},{fileid:4,collecttime:1700000030},{fileid:5}]}));
  a.cookie.token='fixture';a.cookie.userid='123';await a.run({op:'sync'});
  assert.deepEqual((await a.run({op:'tracks',id:'1'})).tracks.map(t=>t.fileId),['2','4','3','1','5']);
  assert.deepEqual((await a.run({op:'tracks',id:'2'})).tracks.map(t=>t.fileId),['1','2','3','4','5']);
  assert.equal(track({collecttime:'2026-09-14 17:00:00'}).collectedAt,Date.parse('2026-09-14T17:00:00+08:00'));
});
test('guest search maps results, pagination and playable IDs without altering library', async () => {
  const a = new Adapter(async (name, params) => {
    assert.equal(name, 'search');assert.equal(params.keywords, '中文歌名');assert.equal(params.pagesize, 30);
    return response({ total: 31, lists: [{ FileHash: 'A'.repeat(32), MixSongID: 42, AlbumID: 3, SongName: '<em>中文</em>歌名', SingerName: '甲 &amp; 乙', Duration: 180 }] });
  });
  a.knownPlaylists.set('saved', { id: 'saved' });
  const first=await a.run({op:'search',keywords:' 中文歌名 ',page:1});
  assert.equal(first.hasMore,true);assert.equal(first.tracks[0].name,'中文歌名');assert.equal(first.tracks[0].artist,'甲 & 乙');
  assert.equal(first.tracks[0].duration,180000);assert.equal(first.tracks[0].hash,'a'.repeat(32));
  assert.equal(a.knownTracks.has(first.tracks[0].id),true);assert.equal(a.knownPlaylists.has('saved'),true);
  assert.equal((await a.run({op:'search',keywords:'中文歌名',page:2})).hasMore,false);
  await assert.rejects(a.run({op:'audio',id:first.tracks[0].id}),/先扫码/);
  await assert.rejects(a.run({op:'search',keywords:' '}),/请输入/);
  a.call=async()=>response({total:0,lists:[]});assert.deepEqual((await a.run({op:'search',keywords:'不存在'})).tracks,[]);
});
function authenticated(call) { const a = new Adapter(call); a.cookie.token = 'fixture-token'; a.cookie.userid = '123'; return a; }
test('QR login carries the same device through polling and captures account', async () => {
  let device;
  const a = new Adapter(async (name, params) => {
    device ??= params.cookie.KUGOU_API_DEV;
    assert.equal(params.cookie.KUGOU_API_DEV, device);
    if (name === 'login_qr_key') return response({ qrcode: 'fixture-key' });
    if (name === 'login_qr_create') { assert.equal(params.key, 'fixture-key'); return response({ base64: 'data:image/png;base64,fixture' }); }
    if (name === 'login_qr_check') return response({ status: 4, token: 'fixture-token', userid: 123, nickname: '测试用户' });
    throw Error('unexpected');
  });
  assert.equal((await a.run({ op: 'qr' })).status, 'waiting');
  const result = await a.run({ op: 'poll' });
  assert.equal(result.status, 'connected');assert.equal(result.session.platform, 'lite');assert.equal(result.session.cookie.userid, '123');
  await a.run({ op: 'logout' });assert.equal(a.cookie.token, undefined);assert.equal(a.knownTracks.size, 0);
});
test('pagination handles server page caps, deduplication and preserved Unicode titles', async () => {
  const a = authenticated(async (name, p) => {
    if (name === 'user_playlist') return response({ total: 3, info: p.page === 1 ? [{ listid: 1, name: '我喜欢' }, { listid: 2, name: '夜晚' }] : [{ listid: 2, name: '夜晚' }, { listid: 3, name: '收藏' }] });
    assert.equal(name, 'playlist_track_all_new');assert.equal(p.listid, '1');
    return response({ total: 1, info: [{ fileid: 9, filename: '歌手 - 中文歌名', hash: 'A'.repeat(32), duration: 180 }] });
  });
  const list = await a.run({ op: 'sync' });assert.equal(list.playlists.length, 3);
  const tracks = await a.run({ op: 'tracks', id: '1' });assert.equal(tracks.tracks[0].id, '1:9');assert.equal(tracks.tracks[0].duration, 180000);
});
test('failed or repeated pagination never replaces a complete previous snapshot', async () => {
  const a = authenticated(async () => response({ total: 2, info: [{ listid: 1, name: '我喜欢' }] }));
  a.knownPlaylists.set('old', { id: 'old' });
  await assert.rejects(a.run({ op: 'sync' }), /分页重复/);assert.equal(a.knownPlaylists.has('old'), true);
});
test('real cloud-list end marker info:null completes sync instead of reporting schema drift', async () => {
  const a = authenticated(async (_name, p) => response({
    list_count: 3, collect_count: 0, album_count: 0,
    info: p.page === 1 ? [{ listid: 1, name: '我喜欢' }, { listid: 2, name: '歌单二' }, { listid: 3, name: '歌单三' }] : null,
  }));
  const result = await a.run({ op: 'sync' });
  assert.equal(result.playlists.length, 3);assert.equal(a.knownPlaylists.size, 3);
});
test('null first page is empty only without a conflicting declared total', async () => {
  const a = authenticated(async () => response({ info: null }));
  assert.deepEqual((await a.run({ op: 'sync' })).playlists, []);
  a.call = async () => response({ total: 2, info: null });
  await assert.rejects(a.run({ op: 'sync' }), /未完整/);
});
test('partial page, changed response schema, and logged-out operations fail explicitly', async () => {
  const a = authenticated(async () => response({ total: 1, info: [] }));
  await assert.rejects(a.run({ op: 'sync' }), /未完整/);
  a.call = async () => response({ unknown: [] });await assert.rejects(a.run({ op: 'sync' }), /结构/);
  await a.run({ op: 'logout' });await assert.rejects(a.run({ op: 'sync' }), /先扫码/);
});
test('expired QR and stale stored credentials do not become logged-in states', async () => {
  const a = new Adapter(async () => response({ status: 0 }));a.key = 'fixture-key';
  assert.equal((await a.run({ op: 'poll' })).status, 'expired');
  a.call = async () => { throw { body: { error_code: 20010 } }; };
  const r = await a.run({ op: 'init', session: { platform: 'lite', cookie: { token: 'bad', userid: '123' } } });
  assert.equal(r.verificationFailed, true);assert.equal(r.connected, false);
  // A network failure must not erase the user's persisted credentials.
  assert.equal(a.cookie.token, 'bad');
});
test('playback refuses missing account entitlements and unrecognized track IDs', async () => {
  const a = authenticated(async () => response({ url: [] }));a.knownTracks.set('1:1', { id: '1:1', hash: 'a'.repeat(32) });
  await assert.rejects(a.run({ op: 'audio', id: '1:1' }), /无法播放/);
  await assert.rejects(a.run({ op: 'audio', id: 'unknown' }), /标识/);
});
test('upstream failures are sanitized and never expose credentials in messages', async () => {
  const a = authenticated(async () => { throw Error('fixture-token secret request'); });
  await assert.rejects(a.run({ op: 'sync' }), error => !error.message.includes('fixture-token'));
  assert.equal(track({ hash: 'A'.repeat(32), name: '歌曲', timelen: 1234 }).duration, 1234);
});
