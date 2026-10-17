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

test('audio rights refusals survive authorization fallback without mislabeling network failures',async()=>{
 const hash='a'.repeat(32);
 for(const status of [2,3,500]) {
  const adapter=new Adapter(async name=>{
   if(name==='song_url' || name==='song_url_auth')return {body:{status,url:[]}};
   if(name==='privilege_lite')return response([{hash}]);
   if(name==='user_verify')return response({valid:1,auth:'fixture'});
   if(name==='song_auth')return response({auth:'fixture'});
   throw Error('network');
  });
  adapter.cookie={userid:'1',token:'fixture'};
  adapter.knownTracks.set('track',{id:'track',hash});
  await assert.rejects(adapter.run({op:'audio',id:'track',quality:'128'}),error=>
   status===500?!error.rights:error.rights===true && /购买或会员权限/.test(error.message));
 }
});
test('NetEase and QQ HTTP covers download over HTTPS but retain the requested cache key',async()=>{
  const original=global.fetch;const downloaded=[];
  global.fetch=async url=>{downloaded.push(url);return {ok:true,body:(async function*(){yield Buffer.from('fixture');})()};};
  try {
    const urls=['http://p1.music.126.net/cover.jpg','http://y.gtimg.cn/cover.jpg','http://qpic.y.qq.com/cover.jpg'];
    const a=new Adapter(async()=>{throw Error('No account request expected');});
    const result=await a.run({op:'covers',urls:[...urls,urls[0],'http://localhost/private']});
    assert.deepEqual(result.images.map(image=>image.url),urls);
    assert.deepEqual(downloaded,urls.map(url=>url.replace('http:','https:')));
    assert.ok(result.images.every(image=>image.image.startsWith('data:image/jpeg;base64,')));
  } finally {global.fetch=original;}
});
test('restart metadata restores song identities without bypassing authentication or accepting URLs',async()=>{
  const a=new Adapter(async()=>response({qrcode:'test-key',base64:'data:image/png;base64,fixture'}));
  const hash='a'.repeat(32);
  await a.run({op:'init',tracks:[{id:'saved',hash,name:'Saved',albumId:'123',audioId:'456',url:'http://localhost/private',token:'private'},
    {id:'local:1',hash},{id:'bad',hash:'invalid'}]});
  assert.equal(a.knownTracks.size,1);assert.equal(a.knownTracks.get('saved').albumId,'123');
  assert.equal(a.knownTracks.get('saved').url,undefined);assert.equal(a.knownTracks.get('saved').token,undefined);
  await assert.rejects(a.run({op:'qualities',id:'saved'}));
  await a.run({op:'qr'});assert.equal(a.knownTracks.get('saved').audioId,'456');
  a.call=async(name,p)=>{
    if(name==='user_detail')return response({nickname:'Fixture'});
    assert.equal(name,'song_url');assert.equal(p.hash,hash);assert.equal(p.album_id,'123');assert.equal(p.album_audio_id,'456');
    return response({url:[]});
  };
  await a.run({op:'init',session:{platform:'lite',cookie:{token:'fixture',userid:'123'}}});
  await a.run({op:'qualities',id:'saved'});
});
test('song labels separate title and artists while preserving source filename for cloud writes',()=>{
  const a=track({name:'MIKS - Left Alone.mp3'});assert.equal(a.name,'Left Alone');assert.equal(a.artist,'MIKS');assert.equal(a.fileName,'MIKS - Left Alone.mp3');
  const b=track({name:'Vicetone、Meron Ryan - Walk Thru Fire.mp3',singerinfo:[{name:'Vicetone'},{name:'Meron Ryan'}]});
  assert.equal(b.name,'Walk Thru Fire');assert.equal(b.artist,'Vicetone、Meron Ryan');
  assert.equal(track({name:'Love - Live',singername:'Someone'}).name,'Love - Live');
  assert.equal(track({songname:'Song - Live',singername:'AC-DC'}).name,'Song - Live');
  assert.equal(track({name:'Dance Fruits Music, Steve Void - Toosie Slide (Explicit).mp3'}).name,'Toosie Slide (Explicit)');
});
test('playlist and search rows retain available album names',async()=>{
  assert.equal(track({albuminfo:{album_name:'Album &amp; More'},name:'Song'}).album,'Album & More');
  assert.equal(track({name:'Song'}).album,'');
  const a=new Adapter(async()=>response({total:1,lists:[{FileHash:'A'.repeat(32),SongName:'Song',AlbumName:'Search Album'}]}));
  assert.equal((await a.run({op:'search',keywords:'Song',page:1})).tracks[0].album,'Search Album');
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
test('quality menu only lists entitled and confirmed audio; fallback is not advertised', async () => {
  const a=authenticated(async (_name,p)=>response(p.quality===128?
    {url:['https://example.com/standard.mp3'],bitrate:128000,extName:'mp3'}:
    p.quality===320?{url:['https://example.com/standard.mp3'],bitrate:128000,extName:'mp3'}:{url:[]}));
  a.knownTracks.set('song',{id:'song',hash:'a'.repeat(32)});
  const result=await a.run({op:'qualities',id:'song'});
  assert.deepEqual(result.options.map(option=>option.id),['128']);
  assert.equal(JSON.stringify(result).includes('https://'),false);
  await assert.rejects(a.run({op:'audio',id:'song',quality:'320'}),/该音质暂时不可用/);
  await assert.rejects(a.run({op:'audio',id:'song',quality:'unknown'}),/无效音质/);
  for(const quality of ['kugou:viper_clear','kugou:viper_tape'])await assert.rejects(a.run({op:'audio',id:'song',quality}),/无效音质/);
  a.call=async()=>response({url:['https://example.com/lossless.flac'],extName:'flac',bitrate:900000});
  assert.deepEqual((await a.run({op:'qualities',id:'song'})).options.map(option=>option.id),['flac']);
  assert.equal((await a.run({op:'audio',id:'song',quality:'flac'})).quality,'flac');
});
test('real song_url bitRate field enables standard and high-quality choices', async () => {
  const a=authenticated(async (_name,p)=>response({url:['https://example.com/audio'],
    bitRate:p.quality==='flac'?1013000:Number(p.quality)*1000,extName:p.quality==='flac'?'flac':'mp3'}));
  a.knownTracks.set('song',{id:'song',hash:'a'.repeat(32)});
  assert.deepEqual((await a.run({op:'qualities',id:'song'})).options.map(option=>option.id),['128','320','flac']);
  assert.equal((await a.run({op:'audio',id:'song',quality:'320'})).quality,'320');
});

test('basic quality confirmation accepts small measured bitrate offsets without accepting another tier',async()=>{
  const hash='a'.repeat(32);let bitRate=128029;
  const a=authenticated(async()=>response({hash,url:['https://example.test/audio'],bitRate,extName:'mp3'}));
  a.knownTracks.set('song',{id:'song',hash});
  assert.deepEqual((await a.run({op:'qualities',id:'song'})).options.map(q=>q.id),['128']);
  assert.equal((await a.run({op:'audio',id:'song',quality:'128'})).quality,'128');
  await assert.rejects(a.run({op:'audio',id:'song',quality:'320'}),/暂时不可用/);
  bitRate=320042;
  assert.deepEqual((await a.run({op:'qualities',id:'song'})).options.map(q=>q.id),['320']);
  assert.equal((await a.run({op:'audio',id:'song',quality:'320'})).quality,'320');
  bitRate=192000;
  assert.deepEqual((await a.run({op:'qualities',id:'song'})).options,[]);
});

test('VIP basic qualities use authorized variant URLs after old failures or silent downgrades',async()=>{
  const base='a'.repeat(32),hashes={'320':'b'.repeat(32),flac:'c'.repeat(32)};let oldFails=true;
  const calls=[];const a=authenticated(async(name,p)=>{
    calls.push([name,p]);
    if(name==='privilege_lite')return response([{hash:base,quality:'128',relate_goods:Object.entries(hashes).map(([quality,hash])=>({quality,hash,album_audio_id:9,info:{tracker_type:'full'}}))}]);
    if(name==='song_url'){
      if(oldFails && p.quality!==128)return {body:{status:2}};
      return response({hash:base,url:['https://example.test/standard.mp3'],bitRate:128000,extName:'mp3'});
    }
    if(name==='user_verify')return response({valid:1,auth:'fixture-user-auth'});
    if(name==='song_auth'){assert.equal(p.cookie.auth,'fixture-user-auth');return response({auth:'fixture-song-auth',open_time:123});}
    if(name==='song_url_auth'){
      assert.equal(p.hash,hashes[String(p.quality)]);assert.equal(p.album_audio_id,9);
      assert.equal(p.auth,'fixture-song-auth');assert.equal(p.open_time,123);
      return response({hash:p.hash,url:['https://example.test/audio'],bitRate:p.quality===320?320000:900000,extName:p.quality===320?'mp3':'flac'});
    }
    throw Error('Unexpected request');
  });a.knownTracks.set('song',{id:'song',hash:base});
  assert.deepEqual((await a.run({op:'qualities',id:'song'})).options.map(q=>q.id),['128','320','flac']);
  for(const quality of ['320','flac'])assert.equal((await a.run({op:'audio',id:'song',quality})).quality,quality);
  oldFails=false;
  assert.equal((await a.run({op:'audio',id:'song',quality:'flac'})).quality,'flac');
  calls.length=0;await a.run({op:'audio',id:'song',quality:'128'});
  assert.deepEqual(calls.map(([name])=>name),['song_url']);
});

test('basic playback still authorizes the original song when variant metadata is unavailable',async()=>{
  const hash='a'.repeat(32),calls=[];
  const a=authenticated(async(name,p)=>{
    calls.push(name);
    if(name==='song_url')return response({url:[]});
    if(name==='privilege_lite')throw Error('metadata unavailable');
    if(name==='user_verify')return response({valid:1,auth:'fixture-user-auth'});
    if(name==='song_auth')return response({auth:'fixture-song-auth',open_time:123});
    if(name==='song_url_auth'){
      assert.equal(p.hash,hash);assert.equal(p.album_id,'7');assert.equal(p.album_audio_id,'9');
      return response({hash,url:['https://example.test/audio'],bitRate:128000,extName:'mp3'});
    }
    throw Error('Unexpected request');
  });
  a.knownTracks.set('song',{id:'song',hash,albumId:'7',audioId:'9'});
  assert.ok((await a.run({op:'qualities',id:'song'})).options.some(q=>q.id==='128'));
  calls.length=0;
  assert.equal((await a.run({op:'audio',id:'song',quality:'128'})).quality,'128');
  assert.deepEqual(calls,['song_url','privilege_lite','user_verify','song_auth','song_url_auth']);
});

test('authorized basic audio rejects invalid login, downgrades, trial and mismatched variants',async()=>{
  const base='a'.repeat(32),variant='b'.repeat(32);let valid=1;
  let data={hash:variant,url:['https://example.test/audio'],bitRate:320000,extName:'mp3'};
  const a=authenticated(async(name)=>{
    if(name==='privilege_lite')return response([{hash:base,quality:'128',relate_goods:[{hash:variant,quality:'320'}]}]);
    if(name==='song_url')return response({url:[]});
    if(name==='user_verify')return response({valid,auth:'fixture-user-auth'});
    if(name==='song_auth'){assert.equal(valid,1);return response({auth:'fixture-song-auth',open_time:123});}
    if(name==='song_url_auth')return response(data);
    throw Error('Unexpected request');
  });a.knownTracks.set('song',{id:'song',hash:base});
  const good=data;
  for(const bad of [{...good,bitRate:128000},{...good,is_free_part:1},{...good,freeTrialInfo:{}},{...good,hash_offset:{end_ms:30000}},{...good,hash:base}]){
    data=bad;await assert.rejects(a.run({op:'audio',id:'song',quality:'320'}),/暂时不可用/);
    assert.deepEqual((await a.run({op:'qualities',id:'song'})).options,[]);
  }
  data=good;valid=0;await assert.rejects(a.run({op:'audio',id:'song',quality:'320'}),/暂时不可用/);
});

test('basic menu accepts the returned quality variant instead of comparing it with the input song hash',async()=>{
  const base='a'.repeat(32),standard='b'.repeat(32),high='c'.repeat(32);let originalFails=false;
  const calls=[];const a=authenticated(async(name,p)=>{
    calls.push([name,p.hash]);
    if(name==='privilege_lite')return response([{hash:base,quality:'128',relate_goods:[{hash:standard,quality:'128'},{hash:high,quality:'320'}]}]);
    if(name==='song_url') {
      if(originalFails && p.hash===base)return response({hash:base,extName:'flac',url:['https://example.test/wrong-tier']});
      if(p.quality===128)return response({hash:standard,extName:'mp3',bitRate:128000,url:['https://example.test/standard']});
      if(p.quality===320)return response({hash:high,extName:'mp3',bitRate:320000,url:['https://example.test/high']});
      return response({url:[]});
    }
    throw Error('Authorization not needed for available variants');
  });a.knownTracks.set('song',{id:'song',hash:base});
  assert.deepEqual((await a.run({op:'qualities',id:'song'})).options.map(q=>q.id),['128','320']);
  for(const quality of ['128','320'])assert.equal((await a.run({op:'audio',id:'song',quality})).quality,quality);
  originalFails=true;
  for(const quality of ['128','320']){
    calls.length=0;assert.equal((await a.run({op:'audio',id:'song',quality})).quality,quality);
    assert.ok(calls.some(([name,hash])=>name==='song_url' && hash===(quality==='128'?standard:high)));
    assert.ok(!calls.some(([name])=>name==='user_verify'));
  }
});

test('own playlists without artwork use their first song cover without replacing custom covers or failing sync',async()=>{
  const requests=[];const a=new Adapter(async(name,params)=>{
    requests.push([name,params]);
    if(name==='user_playlist')return response({total:5,info:[
      {listid:1,is_edit:1,count:201},
      {listid:2,is_edit:1,count:2,pic:'https://imge.kugou.com/custom.jpg'},
      {listid:3,is_edit:1,count:0},
      {listid:4,is_edit:1,count:2},
      {listid:5,is_edit:0,count:2},
    ]});
    assert.equal(name,'playlist_track_all_new');assert.equal(params.page,1);assert.equal(params.pagesize,1);
    if(params.listid==='4')throw Error('private response');
    assert.equal(params.listid,'1');return response({info:[{trans_param:{union_cover:'http://imge.kugou.com/{size}/album.jpg'}}]});
  });
  a.cookie={userid:'fixture',token:'fixture'};
  const result=await a.run({op:'sync'});
  assert.deepEqual(result.playlists.map(p=>p.cover),['https://imge.kugou.com/200/album.jpg','https://imge.kugou.com/custom.jpg','','','']);
  assert.deepEqual(requests.filter(([name])=>name==='playlist_track_all_new').map(([,params])=>params.listid),['1','4']);
  assert.equal(a.knownPlaylists.get('1').cover,result.playlists[0].cover);
});

test('automatic covers follow first visible song while manual covers and failed reads are preserved',async()=>{
  const a=new Adapter(async(name,p)=>{
    if(name==='user_playlist')return response({total:4,info:[
      {listid:1,is_edit:1,count:3,is_custom_pic:0,pic:'https://imge.kugou.com/automatic-first.jpg'},
      {listid:2,is_edit:1,count:3,is_custom_pic:1,pic:'https://imge.kugou.com/manual.jpg'},
      {listid:3,is_edit:1,count:3,is_def:2,is_custom_pic:'0',pic:'https://imge.kugou.com/automatic-first.jpg'},
      {listid:4,is_edit:1,count:3,is_custom_pic:0,pic:'https://imge.kugou.com/fallback.jpg'},
    ]});
    if(p.listid==='4')throw Error('unavailable');
    if(p.listid==='1'){assert.equal(p.page,1);assert.equal(p.pagesize,1);return response({info:[{cover:'https://imge.kugou.com/first.jpg'}]});}
    assert.equal(p.listid,'3');assert.equal(p.pagesize,100);
    return response({count:3,info:[
      {fileid:1,collecttime:10,cover:'https://imge.kugou.com/oldest.jpg'},
      {fileid:2,collecttime:30,cover:'https://imge.kugou.com/newest.jpg'},
      {fileid:3,collecttime:20,cover:'https://imge.kugou.com/middle.jpg'},
    ]});
  });a.cookie={userid:'fixture',token:'fixture'};
  const result=await a.run({op:'sync'});
  assert.deepEqual(result.playlists.map(p=>p.cover),['https://imge.kugou.com/first.jpg','https://imge.kugou.com/manual.jpg','https://imge.kugou.com/newest.jpg','https://imge.kugou.com/fallback.jpg']);
});
