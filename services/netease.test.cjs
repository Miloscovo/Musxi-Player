const test=require('node:test');
const assert=require('node:assert/strict');
const {NeteaseAdapter}=require('./netease.cjs');
const {cacheAudio,closeAudioDownloads}=require('./netease.cjs');

test('streaming cache is ready before download finishes, preserves bytes, and falls back for other containers',async t=>{
  const fs=require('node:fs/promises'),path=require('node:path'),os=require('node:os');
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'musxi-stream-'));
  const bytes=Buffer.alloc(1024*1024,7);bytes.write('ID3');
  let release,ended=false;
  const gate=new Promise(resolve=>{release=resolve});
  const server=require('node:http').createServer(async(req,res)=>{
    res.writeHead(200,{'Content-Length':bytes.length});res.write(bytes.subarray(0,65536));
    await gate;ended=true;res.end(bytes.subarray(65536));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  t.after(async()=>{release();await closeAudioDownloads();server.closeAllConnections();server.close();await fs.rm(dir,{recursive:true,force:true});});
  const url=`http://127.0.0.1:${server.address().port}/audio`;
  const start=performance.now();
  const timer=setTimeout(release,2000);
  const audio=await cacheAudio(url,{id:'fixture'},'128',dir,undefined,true);clearTimeout(timer);
  assert.equal(ended,false,'stream waited for the entire audio file');
  assert.equal(Number(await fs.readFile(audio.path+'.download','utf8')),bytes.length);
  console.log(`stream path ready: ${Math.round(performance.now()-start)}ms, before remaining bytes were sent`);
  release();
  for(let i=0;i<200;i++){try{await fs.access(audio.path+'.download');}catch{break;}await new Promise(r=>setTimeout(r,5));}
  assert.deepEqual(await fs.readFile(audio.path),bytes);
  await assert.rejects(fs.access(audio.path+'.download'));
  bytes.write('ftyp');ended=false;
  const fallback=await cacheAudio(url,{id:'fixture'},'128',dir,undefined,true);
  assert.equal(ended,true);await assert.rejects(fs.access(fallback.path+'.download'));
  assert.deepEqual(await fs.readFile(fallback.path),bytes);
});

test('an interrupted streaming download exposes failure instead of a successful truncated cache',async t=>{
  const fs=require('node:fs/promises'),path=require('node:path'),os=require('node:os');
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'musxi-stream-fail-'));
  const bytes=Buffer.alloc(4096);bytes.write('fLaC');let response;
  const server=require('node:http').createServer((req,res)=>{response=res;res.writeHead(200,{'Content-Length':8192});res.write(bytes);});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  t.after(async()=>{await closeAudioDownloads();server.closeAllConnections();server.close();await fs.rm(dir,{recursive:true,force:true});});
  const audio=await cacheAudio(`http://127.0.0.1:${server.address().port}`,{id:'fixture'},'flac',dir,undefined,true);
  response.destroy();
  for(let i=0;i<200;i++){try{await fs.access(audio.path+'.failed');break;}catch{}await new Promise(r=>setTimeout(r,5));}
  assert.equal(await fs.readFile(audio.path+'.failed','utf8'),'download failed');
  assert.equal((await fs.stat(audio.path)).size,4096);
  await assert.rejects(cacheAudio('file:///private',{id:'fixture'},'flac',dir),/地址无效/);
});
test('avatar uses the bundled login status endpoint and preserves authentication',async()=>{
  const calls=[];
  const adapter=new NeteaseAdapter(async(name,params)=>{
    assert.equal(name,'login_status');calls.push(params);
    return {body:{data:{profile:{userId:7,nickname:'Account',avatarUrl:'https://p1.music.126.net/avatar.jpg'}}}};
  });
  await assert.rejects(adapter.run({op:'avatar'}),/请先扫码登录/);
  assert.equal(calls.length,0);
  await adapter.run({op:'init',session:{cookie:'test-session'}});
  assert.deepEqual(await adapter.run({op:'avatar'}),{url:'https://p1.music.126.net/avatar.jpg'});
  assert.equal(calls[1].cookie,'test-session');
});
test('QR confirmation protects session, and unliking uses the literal false string',async()=>{let cookie='',like;const adapter=new NeteaseAdapter(async(name,p)=>{cookie=p.cookie;const body={login_qr_key:{code:200,data:{unikey:'key'}},login_qr_create:{code:200,data:{qrimg:'data:image/png;base64,test'}},login_qr_check:{code:803,cookie:'private-cookie'},login_status:{data:{profile:{userId:7,nickname:'Account'}}},user_playlist:{code:200,playlist:[{id:9,name:'Liked',creator:{userId:7},specialType:5}],more:false},likelist:{code:200,ids:[]},playlist_detail:{code:200,playlist:{trackIds:[]}}}[name];if(name==='like'){like=p.like;return {body:{body:{code:200}}};}return {body};});await adapter.run({op:'qr'});const login=await adapter.run({op:'poll'});assert.equal(login.status,'connected');assert.equal(login.session.cookie,'private-cookie');adapter.song({id:1,name:'Song'});await adapter.run({op:'song_update',id:'netease:1',kind:'favorite',enabled:false});assert.equal(like,'false');assert.equal(cookie,'private-cookie');});
test('quality list excludes trial and fallback qualities; errors hide service responses',async()=>{const adapter=new NeteaseAdapter(async(name,p)=>{if(name==='song_url_v1')return {body:{code:200,data:[p.level==='standard'?{url:'https://example.com/a',br:128000}:p.level==='lossless'?{url:'https://example.com/b',type:'flac',freeTrialInfo:{}}:{url:'https://example.com/a',br:128000}]}};throw Error('token=secret');});adapter.profile={id:'7'};adapter.song({id:1,name:'Song'});assert.deepEqual((await adapter.run({op:'qualities',id:'netease:1'})).options.map(q=>q.id),['128']);await assert.rejects(adapter.api('login_status'),error=>!error.message.includes('secret'));await assert.rejects(adapter.run({op:'audio',id:'netease:1',quality:'320'}),/暂无权限/);});

test('membership distinguishes active packages, expired memberships and missing data',async()=>{
  let data={};const adapter=new NeteaseAdapter(async name=>{assert.equal(name,'vip_info');return {body:{code:200,data}};});adapter.profile={id:'7'};
  assert.deepEqual(await adapter.run({op:'membership'}),{name:'会员状态暂不可用'});
  data={associator:{expireTime:0},musicPackage:{expireTime:0},redplus:{expireTime:0}};
  assert.deepEqual(await adapter.run({op:'membership'}),{name:'非会员'});
  const expiresAt=Date.now()+86400000;data.associator.expireTime=expiresAt;
  assert.deepEqual(await adapter.run({op:'membership'}),{name:'黑胶 VIP',expiresAt});
  data.redplus.expireTime=expiresAt;
  assert.deepEqual(await adapter.run({op:'membership'}),{name:'超级黑胶 VIP',expiresAt});
  data.associator.expireTime=1;data.redplus.expireTime=1;data.musicPackage.expireTime=expiresAt;
  assert.deepEqual(await adapter.run({op:'membership'}),{name:'音乐包',expiresAt});
});

test('advanced tiers require exact returned level, plain FLAC and full playback',async()=>{
 let data;const adapter=new NeteaseAdapter(async()=>({body:{code:200,data:[data]}}));
 adapter.profile={id:'synthetic'};const track=adapter.song({id:1,name:'Fixture'});
 await assert.rejects(adapter.audioInfo(track,'netease:jymaster'),/无效音质/);
 for(const level of ['hires','jyeffect']){
  data={url:'https://example.test/song.flac',type:'flac',level};
  assert.equal((await adapter.audioInfo(track,`netease:${level}`)).quality,`netease:${level}`);
  for(const bad of [{...data,level:'lossless'},{...data,level:undefined},{...data,type:'mp3',br:320000},{...data,freeTrialInfo:{}}]){
   data=bad;await assert.rejects(adapter.run({op:'audio',id:track.id,quality:`netease:${level}`}),/权限/);
   data={url:'https://example.test/song.flac',type:'flac',level};
  }
 }
 data={url:'https://example.test/song',type:'nac',level:'hires'};
 await assert.rejects(adapter.audioInfo(track,'netease:hires'),/格式/);
});

test('HD pristine quality is offered only for an exact full FLAC response',async()=>{
 let data={url:'https://example.test/song.flac',type:'flac',level:'jyeffect'};
 const adapter=new NeteaseAdapter(async(name,p)=>({body:{code:200,data:[p.level==='jyeffect'?data:{url:null}]}}));
 adapter.profile={id:'synthetic'};adapter.song({id:1,name:'Fixture'});
 assert.deepEqual((await adapter.run({op:'qualities',id:'netease:1'})).options,[{id:'netease:jyeffect',name:'高清臻音'}]);
 assert.equal((await adapter.run({op:'audio',id:'netease:1',quality:'netease:jyeffect'})).quality,'netease:jyeffect');
 for(const bad of [{...data,level:'hires'},{...data,level:undefined},{...data,freeTrialInfo:{}},{...data,type:'nac'},{...data,url:null}]){
  const original=data;data=bad;
  assert.deepEqual((await adapter.run({op:'qualities',id:'netease:1'})).options,[]);
  await assert.rejects(adapter.run({op:'audio',id:'netease:1',quality:'netease:jyeffect'}));
  data=original;
 }
});

test('large playlists read at most three detail batches concurrently and keep playlist order',async()=>{
  let running=0,peak=0;const ids=Array.from({length:450},(_,id)=>({id:id+1}));
  const adapter=new NeteaseAdapter(async(name,params)=>{
    if(name==='playlist_detail')return {body:{code:200,playlist:{trackIds:ids}}};
    assert.equal(name,'song_detail');const batch=params.ids.split(',').map(Number);
    running++;peak=Math.max(peak,running);
    await new Promise(resolve=>setTimeout(resolve,batch[0]===1?15:2));running--;
    return {body:{code:200,songs:batch.map(id=>({id,name:String(id)})).reverse()}};
  });
  adapter.lists.set('netease:list',{id:'netease:list',serviceId:'list'});
  const counts=[];const result=await adapter.playlist('netease:list',snapshot=>counts.push(snapshot.tracks.length));
  assert.equal(peak,3);assert.equal(running,0);
  assert.deepEqual(result.tracks.map(track=>track.id),ids.map(row=>'netease:'+row.id));
  assert.deepEqual(counts,[300,450]);
});

test('a failed detail batch does not return a partial playlist',async()=>{
  const adapter=new NeteaseAdapter(async(name,params)=>{
    if(name==='playlist_detail')return {body:{playlist:{trackIds:Array.from({length:250},(_,id)=>({id}))}}};
    if(params.ids.startsWith('100,'))throw Error('offline');
    return {body:{songs:params.ids.split(',').map(id=>({id}))}};
  });
  adapter.lists.set('netease:list',{id:'netease:list',serviceId:'list'});
  await assert.rejects(adapter.playlist('netease:list'),/无法连接/);
});
