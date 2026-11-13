const test=require('node:test');
const assert=require('node:assert/strict');
const {MultiPlatform}=require('./multi-platform.cjs');
function fixture(){const calls=[];const adapters=Object.fromEntries(['kugou','netease','qq'].map(platform=>[platform,{async run(r){calls.push([platform,r]);if(r.op==='init')return {connected:!!r.session,profile:{name:platform},session:r.session};if(r.op==='sync')return {playlists:[{id:platform==='kugou'?'1':platform+':1'}]};if(r.op==='search')return {tracks:[{id:platform+':song',name:platform,artist:'Artist',album:'Album'}],total:1,hasMore:false};if(r.op==='vip_auto')return {session:{cookie:{token:'private'}},outcome:'skipped'};return {status:'connected',profile:{name:platform},session:{platform}};}}]));return {calls,adapters,manager:new MultiPlatform(adapters)};}

test('startup exposes cached cards read-only and a ready platform before slow login, VIP and favorite reads finish',async()=>{
  let releaseLogin,releaseExtras;
  const login=new Promise(resolve=>{releaseLogin=resolve}),extras=new Promise(resolve=>{releaseExtras=resolve});
  const calls=[];
  const adapters=Object.fromEntries(['kugou','netease','qq'].map(platform=>[platform,{async run(request){
    calls.push([platform,request.op]);
    if(request.op==='init') {if(platform==='qq')await login;return {connected:true,profile:{id:platform,name:platform},session:{profile:{id:platform}}};}
    if(request.op==='sync'){assert.equal(request.light,true);return {playlists:[{id:platform==='kugou'?'1':platform+':1',name:platform,editable:true,favorite:true}]};}
    if(request.op==='tracks'){if(!request.refresh)await extras;return {playlist:{id:request.id},tracks:[{id:platform+':song',name:'Song',artist:'Artist'}]};}
    if(['vip_auto','playlist_covers','avatar','membership'].includes(request.op)){await extras;return {};}
    if(request.op==='logout')return {connected:false};
    if(request.op==='qr')return {status:'waiting'};
    throw Error('Unexpected operation');
  }}]));
  const manager=new MultiPlatform(adapters);
  try {
    const cached=[{id:'qq:cached',platform:'qq',name:'Cached',editable:true}];
    const session={platform:'multi',accounts:Object.fromEntries(['kugou','netease','qq'].map(p=>[p,{profile:{id:p}}]))};
    const first=await manager.run({op:'startup',session,cachedPlaylists:cached});
    assert.equal(first.playlists[0].id,'qq:cached');assert.equal(first.accounts.every(account=>!account.playlistsReady),true);
    for(let i=0;i<50 && !manager.accounts.netease.playlistsReady;i++)await new Promise(resolve=>setTimeout(resolve,2));
    const ready=await manager.run({op:'startup_state'});
    assert.equal(ready.accounts.find(a=>a.platform==='netease').playlistsReady,true);
    assert.equal(ready.accounts.find(a=>a.platform==='qq').verifying,true);
    assert.equal(ready.startupPending,true);assert.equal(ready.playlistsRefreshing,true);assert.equal(ready.favoritesCountKnown,false);
    assert.ok(ready.playlists.some(row=>row.id==='netease:1'));assert.ok(!calls.some(([p,op])=>op==='avatar'));
    assert.equal(ready.playlistSnapshots.netease.accountId,'netease');
    const opened=await manager.run({op:'tracks_start',id:'netease:1',token:'interactive'});
    assert.equal(opened.tracks[0].id,'netease:song');
    releaseLogin();releaseExtras();await manager.startupJob.promise;
    const completed=await manager.run({op:'startup_state'});
    assert.equal(completed.startupPending,false);assert.equal(completed.playlistsRefreshing,false);assert.equal(completed.favoritesCountKnown,true);
    assert.equal(completed.accounts.every(a=>a.playlistsReady),true);
    const verifying=await manager.run({op:'qr',platform:'qq'});
    assert.equal(verifying.accounts.find(a=>a.platform==='qq').playlistsReady,false);
    assert.equal(verifying.accounts.find(a=>a.platform==='netease').playlistsReady,true);
    await manager.run({op:'logout',platform:'qq'});
    const loggedOut=await manager.run({op:'startup_state'});
    assert.ok(!loggedOut.playlistSnapshots.qq);assert.equal(loggedOut.verifiedAccounts.qq,'');assert.ok(!loggedOut.playlists.some(row=>row.platform==='qq'));
  }finally{releaseLogin();releaseExtras();await manager.close();}
});

test('startup preserves failed-account cache, clears changed-account cache and records successful empty lists',async()=>{
  const {manager,adapters}=fixture();
  for(const platform of ['kugou','netease','qq'])adapters[platform].run=async request=>{
    if(request.op==='init')return platform==='netease'?{connected:false,session:{profile:null}}:{connected:platform==='qq',profile:{id:'new',name:'New'}};
    if(request.op==='sync')return {playlists:[]};return {};
  };
  const old={profile:{id:'old'}};
  try {
    await manager.run({op:'startup',session:{platform:'multi',accounts:{netease:old,qq:old}},cachedPlaylists:[{id:'netease:old',platform:'netease'},{id:'qq:old',platform:'qq'}]});
    await manager.startupJob.promise;const result=await manager.run({op:'startup_state'});
    assert.deepEqual(result.playlists.map(row=>row.id),['netease:old']);
    assert.equal(result.accounts.find(a=>a.platform==='netease').playlistsReady,false);
    assert.equal(result.session.accounts.netease.profile.id,'old');
    assert.equal(result.verifiedAccounts.qq,'new');assert.deepEqual(result.playlistSnapshots.qq.playlists,[]);
  }finally{await manager.close();}
});

test('restarting verification waits for old authentication on that platform before installing the new account',async()=>{
  let release;const gate=new Promise(resolve=>{release=resolve});let owner='';const authenticated=[];
  const adapters=Object.fromEntries(['kugou','netease','qq'].map(platform=>[platform,{async run(request){
    if(request.op==='init') {
      if(platform!=='netease')return {connected:false};
      const id=request.session.profile.id;authenticated.push(id);if(id==='old')await gate;owner=id;
      return {connected:true,profile:{id,name:id},session:{profile:{id}}};
    }
    if(request.op==='sync')return {playlists:[{id:'netease:'+owner,name:owner}]};return {};
  }}]));
  const manager=new MultiPlatform(adapters),request=id=>({op:'startup',session:{platform:'multi',accounts:{netease:{profile:{id}}}}});
  try {
    await manager.run(request('old'));await manager.run(request('new'));
    assert.deepEqual(authenticated,['old']);
    release();await manager.startupJob.promise;
    const state=await manager.run({op:'startup_state'});
    assert.deepEqual(authenticated,['old','new']);assert.equal(owner,'new');
    assert.equal(state.playlistSnapshots.netease.accountId,'new');assert.equal(state.playlists[0].id,'netease:new');
  }finally{release();await manager.close();}
});

async function audioFixture(t,handler) {
  const fs=require('node:fs/promises'),os=require('node:os'),path=require('node:path');
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'musxi-prefetch-'));
  const server=require('node:http').createServer(handler || ((request,response)=>response.end(Buffer.alloc(256,1))));
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  t.after(async()=>{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));await fs.rm(dir,{recursive:true,force:true});});
  return {dir,fs,url:`http://127.0.0.1:${server.address().port}/audio`};
}
async function prepared(manager,token) {
  for(let count=0;count<200;count++) {
    const reply=await manager.run({op:'audio_prepared',token});
    if(reply.status!=='pending')return reply;
    await new Promise(resolve=>setTimeout(resolve,5));
  }
  throw Error('Audio preparation did not complete');
}

test('playlist jobs show progress, serve stale cache while refreshing, and keep failures incomplete',async()=>{
  const {manager,adapters}=fixture();let release,fail=false;
  const id='netease:list',playlist={id,name:'Fixture'};
  let gate=new Promise(resolve=>{release=resolve});
  adapters.netease.run=async request=>{
    const tracks=[{id:'netease:one',name:'First',artist:'Artist'}];
    request.onProgress?.({playlist,tracks});await gate;
    if(fail)throw Error('private service response');
    return {playlist,tracks:[...tracks,{id:'netease:two',name:'Second',artist:'Artist'}]};
  };
  await manager.run({op:'tracks_start',id,token:'first'});
  const partial=await manager.run({op:'tracks_progress',token:'first'});
  assert.equal(partial.status,'pending');assert.equal(partial.tracks.length,1);
  assert.equal((await manager.run({op:'tracks_progress',token:'first',revision:partial.revision})).tracks,undefined);
  assert.equal(manager.readCache.size,0);
  release();await manager.loading.promise;
  assert.equal((await manager.run({op:'tracks_progress',token:'first'})).tracks.length,2);
  assert.equal(manager.loading.status,'completed');
  for(const value of manager.readCache.values())value.expires=0;
  gate=new Promise(resolve=>{release=resolve});fail=true;
  const cached=await manager.run({op:'tracks_start',id,token:'second'});
  assert.equal(cached.status,'pending');assert.equal(cached.tracks.length,2);
  cached.tracks[0].name='caller mutation';
  release();await manager.loading.promise;
  const failed=await manager.run({op:'tracks_progress',token:'second'});
  assert.equal(failed.status,'failed');assert.equal(failed.tracks.length,2);assert.equal(failed.tracks[0].name,'First');
  assert.ok(!failed.error.includes('private'));
  await assert.rejects(manager.run({op:'tracks_progress',token:'first'}),/失效/);
});

test('a replaced or signed-out playlist job cannot publish old account data',async()=>{
  const {manager,adapters}=fixture();let release;
  const gate=new Promise(resolve=>{release=resolve});
  adapters.netease.run=async request=>{
    if(request.op==='tracks'){await gate;request.onProgress?.({playlist:{id:request.id},tracks:[{id:'netease:old'}]});return {playlist:{id:request.id},tracks:[{id:'netease:old'}]};}
    return {connected:false};
  };
  await manager.run({op:'tracks_start',id:'netease:old',token:'old'});const old=manager.loading;
  await manager.run({op:'tracks_start',id:'netease:new',token:'new'});
  await manager.run({op:'logout',platform:'netease'});release();
  await Promise.all([old.promise,manager.loading.promise]);
  assert.equal(old.status,'failed');assert.equal(manager.loading.status,'failed');assert.equal(manager.loading.data,null);
  assert.equal(manager.readCache.size,0);
});

test('QQ playlist pages yield the single response slot to foreground playback',async()=>{
  const {QQAdapter}=require('./qq.cjs');const adapter=new QQAdapter();
  const calls=[],counts=[];let foreground,running=0;
  adapter.send=async request=>{
    assert.equal(running++,0,'overlapping QQ commands');calls.push(request.op+(request.page || ''));
    await new Promise(resolve=>setTimeout(resolve,2));running--;
    return request.op==='audio'?{url:'https://example.test/authorized',track:{id:'qq:song'},quality:'128'}:
      {playlist:{id:'qq:list'},tracks:[{id:'qq:'+request.page}],hasMore:request.page<3};
  };
  const list=await adapter.run({op:'tracks',id:'qq:list',onProgress:snapshot=>{
    counts.push(snapshot.tracks.length);
    if(snapshot.tracks.length===1)foreground=adapter.run({op:'audio',id:'qq:song'});
  }});
  assert.equal((await foreground).quality,'128');
  assert.deepEqual(calls,['tracks_page1','audio','tracks_page2','tracks_page3']);
  assert.deepEqual(counts,[1,2,3]);assert.deepEqual(list.tracks.map(row=>row.id),['qq:1','qq:2','qq:3']);
});
test('audio preparation uses authorized sources on every platform and transfers cache ownership once',async t=>{
  const {dir,fs,url}=await audioFixture(t);
  for(const platform of ['kugou','netease','qq']) {
    const {manager,adapters}=fixture(),id=platform==='kugou'?'song':platform+':song';
    const requests=[];
    adapters[platform].run=async request=>{requests.push(request);return {url,track:{id,name:'Song',artist:'Artist'},quality:'flac'};};
    const token='prepare-'+platform;
    assert.deepEqual(await manager.run({op:'audio_prepare',token,id,cacheDir:dir}),{token,status:'pending'});
    const result=await prepared(manager,token);
    assert.equal(result.status,'ready');assert.equal(result.data.track.id,id);
    assert.equal((await fs.readFile(result.data.path)).length,256);
    assert.equal(requests.length,1);assert.equal(requests[0].op,'audio');assert.equal(requests[0].cacheDir,undefined);
    assert.equal(manager.active,'kugou');assert.equal(result.data.session,undefined);
    assert.equal((await manager.run({op:'audio_prepared',token})).status,'failed');
    await manager.run({op:'audio_cancel',token});await manager.close();
    assert.equal((await fs.stat(result.data.path)).size,256,'Transferred file belongs to native playback');
    await fs.unlink(result.data.path);
  }
});
test('audio cancellation aborts an active download, clears partial files and does not block foreground work',async t=>{
  let started,closed;const downloadStarted=new Promise(resolve=>started=resolve),downloadClosed=new Promise(resolve=>closed=resolve);
  const {dir,fs,url}=await audioFixture(t,(request,response)=>{response.write(Buffer.alloc(256,1));started();response.on('close',closed);});
  const {manager,adapters}=fixture();
  adapters.kugou.run=async request=>request.op==='audio'?{url,track:{id:'song'},quality:'128'}:{message:'foreground'};
  await manager.run({op:'audio_prepare',token:'slow',id:'song',cacheDir:dir});
  const job=manager.preparing;await downloadStarted;
  assert.equal((await manager.run({op:'tracks',id:'playlist'})).message,'foreground');
  await manager.run({op:'audio_cancel',token:'slow'});await job.promise;await downloadClosed;
  assert.equal(job.controller.signal.aborted,true);assert.deepEqual(await fs.readdir(dir),[]);
  assert.equal((await manager.run({op:'audio_prepared',token:'slow'})).status,'failed');
});
test('new preparation and logout remove only unclaimed cache files; invalid tokens are rejected',async t=>{
  const {dir,fs,url}=await audioFixture(t),{manager,adapters}=fixture();
  adapters.kugou.run=async request=>request.op==='audio'?{url,track:{id:request.id},quality:'128'}:{};
  for(const token of [undefined,1,'','a'.repeat(65),'a b','中文'])await assert.rejects(manager.run({op:'audio_prepare',token,id:'one',cacheDir:dir}),/无效预加载标识/);
  await assert.rejects(manager.run({op:'audio_prepare',token:'valid',id:'one'}),/无效预加载请求/);
  await manager.run({op:'audio_prepare',token:'one',id:'one',cacheDir:dir});
  while(manager.preparing.status==='pending')await new Promise(resolve=>setTimeout(resolve,5));
  const old=manager.preparing,oldPath=old.data.path;
  await manager.run({op:'audio_prepare',token:'two',id:'two',cacheDir:dir});await old.promise;
  await assert.rejects(fs.stat(oldPath),{code:'ENOENT'});
  const second=manager.preparing;
  await manager.run({op:'logout',platform:'kugou'});await second.promise;
  assert.deepEqual(await fs.readdir(dir),[]);await manager.close();
});
test('cancelled QQ authorization is allowed to settle before a foreground request reuses its response slot',async t=>{
  const {dir,url}=await audioFixture(t),{manager,adapters}=fixture();
  let release,entered;const gate=new Promise(resolve=>release=resolve),started=new Promise(resolve=>entered=resolve);let calls=0;
  adapters.qq.run=async request=>{
    assert.equal(!!adapters.qq.pending,false,'No concurrent QQ pipe request');
    adapters.qq.pending=true;calls++;entered();
    if(calls===1)await gate;
    adapters.qq.pending=false;return {url,track:{id:request.id},quality:'128'};
  };
  await manager.run({op:'audio_prepare',token:'qq',id:'qq:one',cacheDir:dir});await started;
  const job=manager.preparing;await manager.run({op:'audio_cancel',token:'qq'});
  const foreground=manager.run({op:'audio',id:'qq:two'});
  assert.equal(job.controller.signal.aborted,true);assert.equal(calls,1);
  release();const result=await foreground;await job.promise;
  assert.equal(result.track.id,'qq:two');assert.equal(calls,2);await manager.close();
});
test('failed audio preparation reports a public failure and leaves normal audio requests usable',async()=>{
  const {manager,adapters}=fixture();let failed=true;
  adapters.kugou.run=async()=>{if(failed)throw Error('private upstream detail');return {url:'https://example.com/song.mp3',track:{id:'song'},quality:'128'};};
  await manager.run({op:'audio_prepare',token:'failure',id:'song',cacheDir:'unused'});
  const result=await prepared(manager,'failure');assert.equal(result.status,'failed');assert.doesNotMatch(result.error,/private/);
  failed=false;assert.equal((await manager.run({op:'audio',id:'song'})).track.id,'song');await manager.close();
});

test('account initialization preserves verification failures instead of reporting confirmed logout',async()=>{
  const {manager,adapters}=fixture();
  adapters.kugou.run=async()=>({connected:false,verificationFailed:true});
  const result=await manager.run({op:'init'});
  assert.equal(result.connected,false);
  assert.match(result.accounts.find(account=>account.platform==='kugou').error,/验证失败/);
  assert.equal(result.accounts.find(account=>account.platform==='qq').error,'');
});

test('confirmed favorite edits refresh counts and automatic covers on every platform',async()=>{
  for(const platform of ['kugou','netease','qq']){
    const {manager,adapters}=fixture();
    await manager.run({op:'init',session:{platform:'multi',accounts:{[platform]:{}}}});
    const list={id:platform+':liked',favorite:true,editable:true,count:99,cover:'old',automaticCover:true};
    let rows=[{id:platform+':one',name:'One',artist:'Artist',cover:'first'},{id:platform+':two',name:'Two',artist:'Artist',cover:'second'}];
    adapters[platform].run=async r=>{
      if(r.op==='sync')return {playlists:[list]};
      if(r.op==='song_update')rows=rows.slice(1);
      return {playlist:list,playlists:[list],tracks:rows};
    };
    await manager.run({op:'sync'});
    for(const expected of [1,0]){
      const result=await manager.run({op:'song_update',id:rows[0].id,kind:'favorite',enabled:false});
      assert.equal(result.playlist.count,expected);
      assert.equal(result.playlists[0].count,expected);
      assert.equal(result.playlists[0].cover,expected?'second':'');
      assert.equal(result.favoritesCount,expected);
    }
  }
});

test('cloud favorites refresh signed-in own favorites, merge exact metadata and retain playable sources',async()=>{
  const {manager,adapters}=fixture();
  await manager.run({op:'init',session:{platform:'multi',accounts:{kugou:{},netease:{}}}});
  const calls=[];let removed=false;
  for(const platform of ['kugou','netease'])adapters[platform].run=async r=>{
    calls.push([platform,r.op,r.id]);
    if(r.op==='sync')return {playlists:[{id:platform+':liked',favorite:true,editable:true},{id:platform+':saved',favorite:true,editable:false},{id:platform+':ordinary',editable:true}]};
    if(r.op==='song_update'){removed=true;return {message:'已取消收藏'};}
    return {tracks:removed && platform==='netease'?[]:[{id:platform==='kugou'?'one':'netease:one',name:platform==='kugou'?' Song ':'song',artist:'Artist',album:'Album'},...(platform==='netease'?[{id:'netease:two',name:'Song',artist:'Artist',album:'Different'}]:[])]};
  };
  assert.equal((await manager.run({op:'sync'})).favoritesCount,2);
  calls.length=0;
  let result=await manager.run({op:'tracks',id:'musxi:cloud-favorites'});
  assert.equal(result.playlist.id,'musxi:cloud-favorites');assert.equal(result.tracks.length,2);
  assert.equal(result.favoritesCount,2);
  assert.deepEqual(result.tracks[0].platforms,['netease','kugou']);assert.equal(result.tracks[0].sources.length,2);
  assert.deepEqual(result.tracks[1].platforms,['netease']);
  assert.equal(calls.length,0,'Opening freshly synchronized favorites must reuse the full result');
  const readCalls=calls.length;
  assert.equal((await manager.run({op:'tracks',id:'musxi:cloud-favorites'})).tracks.length,2);
  assert.equal(calls.length,readCalls);
  assert.ok(!result.playlists.some(p=>p.id==='musxi:cloud-favorites'));
  result=await manager.run({op:'song_update',id:'netease:one',action:'unlike',aggregate:true});
  assert.equal(result.message,'已取消收藏');assert.equal(result.playlist.id,'musxi:cloud-favorites');
  assert.deepEqual(result.tracks[0].platforms,['kugou']);assert.equal(result.tracks.length,1);
  assert.equal(result.favoritesCount,1);
  assert.equal((await manager.run({op:'sync'})).favoritesCount,1);
  assert.equal((await manager.run({op:'logout',platform:'kugou'})).favoritesCount,0);
});

test('cloud favorites handle empty results and partial failures without exposing private responses',async()=>{
  const {manager,adapters}=fixture();
  await assert.rejects(manager.run({op:'tracks',id:'musxi:cloud-favorites'}),/先登录/);
  await manager.run({op:'init',session:{platform:'multi',accounts:{kugou:{},qq:{}}}});
  adapters.kugou.run=async()=>({playlists:[]});adapters.qq.run=async()=>{throw Error('private token');};
  const result=await manager.run({op:'tracks',id:'musxi:cloud-favorites'});
  assert.equal(result.tracks.length,0);assert.equal(result.warnings.length,1);assert.ok(!JSON.stringify(result).includes('private token'));
  adapters.kugou.run=adapters.qq.run;
  await assert.rejects(manager.run({op:'tracks',id:'musxi:cloud-favorites'}),/收藏读取失败/);
});
test('legacy session restores only KuGou; QR and IDs select independent providers',async()=>{const {manager,calls}=fixture();await manager.run({op:'init',session:{cookie:{token:'saved'}},tracks:[{id:'qq:1'},{id:'netease:1'},{id:'1'}]});assert.deepEqual(manager.accountState().map(a=>a.connected),[true,false,false]);assert.equal(calls.find(c=>c[0]==='qq')[1].tracks[0].id,'qq:1');await manager.run({op:'qr',platform:'qq'});await manager.run({op:'poll'});await manager.run({op:'tracks',id:'netease:1'});assert.equal(calls.at(-1)[0],'netease');assert.deepEqual(manager.accountState().map(a=>a.connected),[true,false,true]);});
test('merged sessions survive VIP and scoped logout; partial sync keeps cached playlists',async()=>{const {manager,adapters}=fixture();await manager.run({op:'init',session:{platform:'multi',accounts:{kugou:{},netease:{},qq:{}}}});const first=await manager.run({op:'sync'});assert.equal(first.playlists.length,3);const vip=await manager.run({op:'vip_auto'});assert.equal(vip.session.platform,'multi');assert.ok(vip.session.accounts.qq);adapters.qq.run=async r=>{if(r.op==='sync')throw Error('private response');return {};};const partial=await manager.run({op:'sync'});assert.equal(partial.playlists.length,3);assert.equal(partial.warnings.length,1);assert.ok(!JSON.stringify(partial).includes('private response'));const logout=await manager.run({op:'logout',platform:'qq'});assert.equal(logout.playlists.length,2);assert.equal(logout.accounts.find(a=>a.platform==='netease').connected,true);await assert.rejects(manager.run({op:'song_update',id:'netease:1',playlistId:'qq:1'}),/同一平台/);});
test('search combines each provider without losing IDs',async()=>{const {manager}=fixture();const r=await manager.run({op:'search',keywords:'music',page:1});assert.equal(r.total,3);assert.deepEqual(r.tracks.map(t=>t.id),['kugou:song','netease:song','qq:song']);});
test('avatars stay platform scoped; failed pictures preserve login; logout clears its picture',async()=>{
  const {manager,adapters}=fixture();
  await manager.run({op:'init',session:{platform:'multi',accounts:{kugou:{},netease:{},qq:{}}}});
  const image='data:image/png;base64,AAAA';
  adapters.kugou.run=async r=>r.op==='covers'?{images:[{image}]}:{image};
  adapters.netease.run=async()=>({url:'https://music.126.net/avatar.jpg'});
  adapters.qq.run=async()=>{throw Error('picture unavailable');};
  const result=await manager.run({op:'avatar'});
  assert.equal(result.image,image);
  assert.deepEqual(result.accounts.map(a=>a.avatar),[image,image,'']);
  assert.ok(result.accounts.every(a=>a.connected));
  adapters.netease.run=async()=>({});
  const logout=await manager.run({op:'logout',platform:'netease'});
  assert.equal(logout.accounts.find(a=>a.platform==='netease').avatar,'');
  assert.equal(logout.accounts.find(a=>a.platform==='kugou').avatar,image);
});
test('search merges metadata across pages, updates platform labels, and keeps different albums separate',async()=>{
  const {manager,adapters}=fixture();
  for(const platform of ['kugou','netease','qq'])adapters[platform].run=async r=>({tracks:r.page===1?[{id:platform+':one',name:platform==='qq'?' song ':'Song',artist:'Artist',album:'Album'}]:[{id:platform+':two',name:'Song',artist:'Artist',album:platform==='qq'?'Other album':'Album'}],total:2,hasMore:r.page===1});
  const first=await manager.run({op:'search',keywords:'song',page:1});
  assert.equal(first.tracks.length,1);assert.deepEqual(first.tracks[0].platforms,['netease','kugou','qq']);
  const second=await manager.run({op:'search',keywords:'song',page:2});
  assert.equal(second.tracks.length,2);assert.equal(second.tracks[0].id,first.tracks[0].id);assert.equal(second.tracks[0].sources.length,3);
  assert.deepEqual(second.tracks[1].platforms,['qq']);
  await manager.run({op:'search',keywords:'new',page:1});assert.equal(manager.searchGroups.size,1);
});
test('merged audio tries signed-in platforms in priority order, reports actual source, and preserves explicit quality routing',async()=>{
  const {manager,adapters}=fixture();const tried=[];
  await manager.run({op:'init',session:{platform:'multi',accounts:{kugou:{},netease:{},qq:{}}}});
  const sources=['kugou','netease','qq'].map(platform=>({id:platform==='kugou'?'original':platform+':song',name:'Song',artist:'Artist',album:'Album'}));
  for(const platform of ['kugou','netease','qq'])adapters[platform].run=async r=>{tried.push([platform,r.id,r.quality]);if(platform!=='qq')throw Error('private account failure');return {path:'cached.mp3',track:{...sources[2]},quality:'128'};};
  const result=await manager.run({op:'audio',id:'original',sources});
  assert.deepEqual(tried.map(r=>r[0]),['netease','kugou','qq']);assert.equal(result.requestedId,'original');assert.equal(result.track.id,'qq:song');assert.deepEqual(result.track.sources,sources);
  tried.length=0;await manager.run({op:'audio',id:'qq:song',sources,quality:'320'});assert.deepEqual(tried,[['qq','qq:song','320']]);
  adapters.qq.run=async()=>{throw Error('private detail');};await assert.rejects(manager.run({op:'audio',id:'original',sources}),error=>/均无法播放/.test(error.message) && !error.message.includes('private'));
  manager.accounts.netease.connected=false;tried.length=0;await assert.rejects(manager.run({op:'audio',id:'original',sources}));assert.ok(!tried.some(r=>r[0]==='netease'));
});
test('restart restores each merged candidate to its own adapter',async()=>{
  const {manager,calls}=fixture();await manager.run({op:'init',tracks:[{id:'original',sources:[{id:'netease:song'},{id:'qq:song'}]}]});
  assert.equal(calls.find(c=>c[0]==='netease')[1].tracks[0].id,'netease:song');assert.equal(calls.find(c=>c[0]==='qq')[1].tracks[0].id,'qq:song');
});
test('explicit source switching uses only the selected provider, searches exact metadata and keeps source candidates',async()=>{
  const {manager,adapters}=fixture();
  await manager.run({op:'init',session:{platform:'multi',accounts:{kugou:{},netease:{},qq:{}}}});
  const original={id:'original',name:'Song',artist:'Artist',album:'Album',sources:[{id:'netease:one',name:'Song',artist:'Artist',album:'Album'}]};
  const calls=[];
  for(const platform of ['netease','qq'])adapters[platform].run=async r=>{
    calls.push([platform,r.op]);
    if(r.op==='search')return {tracks:[{id:'qq:wrong',name:'Song',artist:'Artist',album:'Other'}, {id:'qq:match',name:' song ',artist:'artist',album:'ALBUM'}]};
    return {path:'cached.mp3',track:{id:r.id,name:'Song',artist:'Artist',album:'Album'},quality:'128'};
  };
  const direct=await manager.run({op:'audio',id:'original',platform:'netease',track:original});
  assert.deepEqual(calls,[['netease','audio']]);assert.equal(direct.track.id,'netease:one');
  calls.length=0;
  const searched=await manager.run({op:'audio',id:'original',platform:'qq',track:original});
  assert.deepEqual(calls,[['qq','search'],['qq','audio']]);assert.equal(searched.track.id,'qq:match');assert.equal(searched.requestedId,'original');assert.equal(searched.track.sources.length,3);
  adapters.qq.run=async()=>({tracks:[]});await assert.rejects(manager.run({op:'audio',id:'original',platform:'qq',track:original}),/未找到/);
  adapters.netease.run=async()=>{throw Error('private data');};await assert.rejects(manager.run({op:'audio',id:'original',platform:'netease',track:original}),/已保留原播放源/);
  manager.accounts.qq.connected=false;await assert.rejects(manager.run({op:'audio',id:'original',platform:'qq',track:original}),/请先登录/);
});
test('song menus query signed-in matching providers and preserve the actual-source legacy fields',async()=>{
  const {manager,adapters}=fixture();
  await manager.run({op:'init',session:{platform:'multi',accounts:{kugou:{},netease:{},qq:{}}}});
  const track={id:'original',name:'Song',artist:'Artist',album:'Album',sources:[{id:'netease:song',name:'Song',artist:'Artist',album:'Album'}]};
  const calls=[];
  for(const platform of ['kugou','netease','qq'])adapters[platform].run=async r=>{
    calls.push([platform,r.op,r.id]);
    if(r.op==='search')return {tracks:[{id:'qq:song',name:'Song',artist:'Artist',album:'Album'}]};
    return {id:r.id,liked:platform==='netease',canFavorite:true,playlists:[{id:platform==='kugou'?'playlist':platform+':playlist',editable:true}]};
  };
  const result=await manager.run({op:'song_menu',id:'netease:song',menuId:'original',track});
  assert.equal(result.id,'original');assert.equal(result.sourceId,'netease:song');assert.equal(result.liked,true);
  assert.deepEqual(result.providers.map(p=>[p.platform,p.liked]),[['netease',true],['kugou',false],['qq',false]]);
  assert.equal(calls.filter(c=>c[1]==='search').length,1);
  manager.accounts.qq.connected=false;calls.length=0;
  const partial=await manager.run({op:'song_menu',id:'original',track});assert.equal(partial.providers.length,2);assert.ok(!calls.some(c=>c[0]==='qq'));
  manager.accounts.qq.connected=true;adapters.qq.run=async()=>({tracks:[{id:'qq:wrong',name:'Song',artist:'Artist',album:'Other'}]});
  assert.equal((await manager.run({op:'song_menu',id:'original',track})).providers.length,2);
  adapters.netease.run=async()=>{throw Error('private credential data');};
  const failed=await manager.run({op:'song_menu',id:'original',track});assert.equal(failed.providers.length,1);assert.equal(failed.warnings.length,1);assert.ok(!JSON.stringify(failed).includes('private'));
});

test('default quality falls down without changing explicit per-song selection',async()=>{
 const seen=[];
 const manager=new MultiPlatform({kugou:{async run(r){seen.push(r.quality);if(r.quality==='flac')throw Error('Unavailable');return {track:{id:'one'},path:'fixture',quality:r.quality};}}});
 const result=await manager.run({op:'audio',id:'one',defaultQuality:'flac'});
 assert.equal(result.quality,'320');assert.deepEqual(seen,['flac','320']);
 seen.length=0;
 await assert.rejects(manager.run({op:'audio',id:'one',quality:'flac',defaultQuality:'128'}),/Unavailable/);
 assert.deepEqual(seen,['flac']);
});
test('a song refused at every quality reports missing rights instead of missing qualities',async()=>{
 const refuse=async()=>{throw Object.assign(Error('rights'),{rights:true});};
 const manager=new MultiPlatform({kugou:{run:refuse}});
 await assert.rejects(manager.run({op:'audio',id:'one',defaultQuality:'flac'}),/酷狗概念版需要购买或会员权限/);
 const mixed=new MultiPlatform({kugou:{async run(r){if(r.quality==='flac')throw Object.assign(Error('rights'),{rights:true});throw Error('network');}}});
 await assert.rejects(mixed.run({op:'audio',id:'one',defaultQuality:'flac'}),/该平台没有可播放的音质/);
 const plain=new MultiPlatform({kugou:{async run(){throw Error('network');}}});
 await assert.rejects(plain.run({op:'audio',id:'one',defaultQuality:'flac'}),/该平台没有可播放的音质/);
});
test('default quality falls down on each source and preserves platform fallback',async()=>{
 const seen=[];
 const manager=new MultiPlatform(Object.fromEntries(['netease','kugou'].map(p=>[p,{async run(r){seen.push([p,r.quality]);if(p==='netease' || r.quality!=='128')throw Error('Unavailable');return {track:{id:'one'},path:'fixture',quality:r.quality};}}])));
 manager.accounts={netease:{connected:true},kugou:{connected:true}};
 const result=await manager.run({op:'audio',id:'one',sources:[{id:'netease:one'},{id:'one'}],defaultQuality:'flac'});
 assert.equal(result.platform,'kugou');assert.equal(result.quality,'128');
 assert.deepEqual(seen,[['netease','flac'],['netease','320'],['netease','128'],['kugou','flac'],['kugou','320'],['kugou','128']]);
 await assert.rejects(manager.run({op:'audio',id:'one',defaultQuality:'bad'}),/无效默认音质/);
});

test('membership is platform scoped and image failures never erase it or sign out',async()=>{
  const {manager,adapters}=fixture();await manager.run({op:'init',session:{platform:'multi',accounts:{kugou:{},netease:{},qq:{}}}});
  for(const platform of ['kugou','netease','qq'])adapters[platform].run=async r=>{
    if(r.op==='membership'){if(platform==='qq')throw Error('private response');return {name:platform==='kugou'?'非会员':'黑胶 VIP',...(platform==='netease'?{expiresAt:1800000000000}:{})};}
    throw Error('picture unavailable');
  };
  let result=await manager.run({op:'avatar'});
  assert.deepEqual(result.accounts.map(a=>a.membership),[{name:'非会员'},{name:'黑胶 VIP',expiresAt:1800000000000},{name:'会员状态暂不可用'}]);
  assert.ok(result.accounts.every(a=>a.connected));assert.ok(!JSON.stringify(result).includes('private response'));
  adapters.netease.run=async()=>({});result=await manager.run({op:'logout',platform:'netease'});
  assert.equal(result.accounts.find(a=>a.platform==='netease').membership,undefined);
});

test('highest available prioritizes advanced tiers and falls back',async()=>{
 for(const [platform,expected] of [['netease',['netease:jyeffect','netease:hires','flac','320']],['qq',['flac','320']],['kugou',['kugou:high','flac','320']]]){
  const seen=[];const manager=new MultiPlatform({[platform]:{async run(r){seen.push(r.quality);if(r.quality!=='320')throw Error('Unavailable');return {quality:r.quality};}}});
  assert.equal((await manager.audioWithDefault(platform,{op:'audio',defaultQuality:'best'})).quality,'320');
  assert.deepEqual(seen,expected);
 }
});

test('Netease highest uses HD pristine first without changing explicit quality preferences',async()=>{
 const seen=[];const manager=new MultiPlatform({netease:{async run(r){seen.push(r.quality);return {quality:r.quality};}}});
 assert.equal((await manager.audioWithDefault('netease',{op:'audio',defaultQuality:'best'})).quality,'netease:jyeffect');
 assert.equal((await manager.audioWithDefault('netease',{op:'audio',defaultQuality:'flac'})).quality,'flac');
 assert.equal((await manager.audioWithDefault('netease',{op:'audio',defaultQuality:'best',quality:'netease:hires'})).quality,'netease:hires');
 assert.deepEqual(seen,['netease:jyeffect','flac','netease:hires']);
});

test('online reads reuse successful results for 30 seconds and isolate cached snapshots',async()=>{
  const {manager,adapters}=fixture();let reads=0;
  adapters.netease.run=async r=>{
    if(r.op==='tracks'){reads++;return {playlist:{id:r.id},tracks:[{id:'netease:one',name:'Original',artist:'Artist'}]};}
    return {playlists:[]};
  };
  const originalNow=Date.now;let now=originalNow();Date.now=()=>now;
  try {
    const request={op:'tracks',id:'netease:one'};
    const first=await manager.run(request);first.tracks[0].name='Changed by caller';
    assert.equal((await manager.run(request)).tracks[0].name,'Original');assert.equal(reads,1);
    now+=30001;await manager.run(request);assert.equal(reads,2);
    for(const op of ['sync','song_update','logout','init','poll']){
      await manager.call('netease',{op});await manager.run(request);assert.equal(reads,3+['sync','song_update','logout','init','poll'].indexOf(op));
    }
  } finally {Date.now=originalNow;}
});

test('search cache separates keywords and pages, retries failures, and keeps playback source checks fresh',async()=>{
  const {manager,adapters,calls}=fixture();
  await manager.run({op:'search',keywords:'one',page:1});const initial=calls.length;
  await manager.run({op:'search',keywords:'one',page:1});assert.equal(calls.length,initial);
  await manager.run({op:'search',keywords:'one',page:2});assert.equal(calls.length,initial+3);
  await manager.run({op:'search',keywords:'two',page:1});assert.equal(calls.length,initial+6);
  let attempts=0;
  adapters.netease.run=async()=>{if(++attempts===1)throw Error('offline');return {tracks:[],total:0,hasMore:false};};
  await assert.rejects(manager.call('netease',{op:'search',keywords:'failed',page:1}));
  await manager.call('netease',{op:'search',keywords:'failed',page:1});assert.equal(attempts,2);
  await manager.sourceFor({id:'qq:one',name:'Song',artist:'Artist'},'netease');
  await manager.sourceFor({id:'qq:one',name:'Song',artist:'Artist'},'netease');assert.equal(attempts,4);
  for(let page=1;page<30;page++)await manager.call('netease',{op:'search',keywords:'bounded',page});
  assert.ok(manager.readCache.size<=24);
});

test('a ready platform starts its favorite reads before another platform finishes synchronizing',async()=>{
  const {manager,adapters}=fixture();
  await manager.run({op:'init',session:{platform:'multi',accounts:{kugou:{},netease:{}}}});
  let releaseSlow,signalFast;
  const slow=new Promise(resolve=>{releaseSlow=resolve});
  const fastStarted=new Promise(resolve=>{signalFast=resolve});
  for(const platform of ['kugou','netease'])adapters[platform].run=async request=>{
    if(request.op==='sync'){
      if(platform==='netease')await slow;
      return {playlists:[{id:platform+':liked',editable:true,favorite:true}]};
    }
    assert.equal(request.op,'tracks');
    if(platform==='kugou')signalFast();
    return {tracks:[{id:platform+':song',name:platform,artist:'Artist'}]};
  };
  const synchronization=manager.run({op:'sync'});let timeout;
  try {
    await Promise.race([fastStarted,new Promise((_,reject)=>{timeout=setTimeout(()=>reject(Error('Ready platform waited for the slow sync')),1000)})]);
  } finally {clearTimeout(timeout);releaseSlow();}
  const result=await synchronization;
  assert.equal(result.favoritesCount,2);assert.equal(result.playlists.length,2);assert.deepEqual(result.warnings,[]);
});

test('completed sync warms merged favorites without repeated requests and honors expiry',async()=>{
  const {manager,adapters,calls}=fixture();await manager.run({op:'init',session:{platform:'multi',accounts:{netease:{}}}});
  let reads=0;
  adapters.netease.run=async r=>{
    calls.push(['netease',r]);
    if(r.op==='sync')return {playlists:[{id:'netease:liked',favorite:true,editable:true}]};
    if(r.op==='tracks'){reads++;return {tracks:[{id:'netease:song',name:'Song',artist:'Artist'}]};}
    return {};
  };
  await manager.run({op:'sync'});const after=calls.length;
  const first=await manager.run({op:'tracks',id:'musxi:cloud-favorites'});
  assert.equal(reads,1);assert.equal(calls.length,after);assert.equal(first.tracks.length,1);
  first.tracks[0].name='changed';assert.equal((await manager.run({op:'tracks',id:'musxi:cloud-favorites'})).tracks[0].name,'Song');
  manager.readCache.get('musxi:cloud-favorites').expires=Date.now()-1;
  await manager.run({op:'tracks',id:'musxi:cloud-favorites'});assert.equal(reads,2);
});

test('partially failed synchronization does not warm complete merged favorites',async()=>{
  const {manager,adapters}=fixture();await manager.run({op:'init',session:{platform:'multi',accounts:{kugou:{},netease:{}}}});
  adapters.kugou.run=async()=>{throw Error('unavailable')};adapters.netease.run=async r=>r.op==='sync'?{playlists:[]}:{tracks:[]};
  const result=await manager.run({op:'sync'});assert.ok(result.warnings.length);assert.equal(manager.readCache.has('musxi:cloud-favorites'),false);
});

test('avatar downloads start independently of slow memberships while QQ commands remain serialized',async()=>{
  const {manager,adapters}=fixture();await manager.run({op:'init',session:{platform:'multi',accounts:{netease:{},qq:{}}}});
  let releaseMembership,signalPictures;const gate=new Promise(resolve=>{releaseMembership=resolve});
  const started=new Promise(resolve=>{signalPictures=resolve});const pictures=[];let qqBusy=false;
  for(const platform of ['netease','qq'])adapters[platform].run=async request=>{
    if(platform==='qq'){assert.equal(qqBusy,false,'QQ child received concurrent commands');qqBusy=true;}
    try {
      if(request.op==='membership'){await gate;return {name:'VIP'};}
      assert.equal(request.op,'avatar');return {url:`https://music.126.net/${platform}.jpg`};
    } finally {if(platform==='qq')qqBusy=false;}
  };
  adapters.kugou.run=async request=>{
    assert.equal(request.op,'covers');pictures.push(request.urls[0]);if(pictures.length===2)signalPictures();
    return {images:[{image:'data:image/png;base64,AAAA'}]};
  };
  const task=manager.run({op:'avatar'});let timer;
  try {
    await Promise.race([started,new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('Avatar waited for membership')),1000)})]);
  } finally {clearTimeout(timer);releaseMembership();}
  const result=await task;assert.equal(pictures.length,2);assert.ok(result.accounts.filter(a=>a.connected).every(a=>a.avatar&&a.membership.name==='VIP'));
});
