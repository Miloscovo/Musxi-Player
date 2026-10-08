const test=require('node:test');
const assert=require('node:assert/strict');
const {MultiPlatform}=require('./multi-platform.cjs');
function fixture(){const calls=[];const adapters=Object.fromEntries(['kugou','netease','qq'].map(platform=>[platform,{async run(r){calls.push([platform,r]);if(r.op==='init')return {connected:!!r.session,profile:{name:platform},session:r.session};if(r.op==='sync')return {playlists:[{id:platform==='kugou'?'1':platform+':1'}]};if(r.op==='search')return {tracks:[{id:platform+':song',name:platform,artist:'Artist',album:'Album'}],total:1,hasMore:false};if(r.op==='vip_auto')return {session:{cookie:{token:'private'}},outcome:'skipped'};return {status:'connected',profile:{name:platform},session:{platform}};}}]));return {calls,adapters,manager:new MultiPlatform(adapters)};}

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
  assert.deepEqual(calls.filter(c=>c[1]==='tracks').map(c=>c[2]),['kugou:liked','netease:liked']);
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
