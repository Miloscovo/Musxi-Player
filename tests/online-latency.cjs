'use strict';
// Controlled latency comparison; no accounts, upstream traffic or audio output device.
const assert=require('node:assert/strict');
const fs=require('node:fs/promises'),os=require('node:os'),path=require('node:path');
const {setTimeout:delay}=require('node:timers/promises');
const {MultiPlatform}=require('../services/multi-platform.cjs');
const {NeteaseAdapter,cacheAudio,closeAudioDownloads}=require('../services/netease.cjs');
const {Adapter}=require('../services/bridge.cjs');
const {QQAdapter}=require('../services/qq.cjs');
const rounded=value=>Math.round(value*10)/10;
const summarize=values=>({medianMs:rounded([...values].sort((a,b)=>a-b)[Math.floor(values.length/2)]),maxMs:rounded(Math.max(...values))});
function fixture(platform) {
  const id=platform==='kugou'?'list':platform+':list';let adapter;
  if(platform==='netease') {
    adapter=new NeteaseAdapter(async(name,r)=>{
      await delay(25);
      return {body:name==='playlist_detail'?{playlist:{trackIds:Array.from({length:900},(_,id)=>({id}))}}:
        {songs:r.ids.split(',').map(id=>({id,name:'Song '+id}))}};
    });
    adapter.profile={id:'fixture'};adapter.lists.set(id,{id,serviceId:'fixture'});
  } else if(platform==='kugou') {
    adapter=new Adapter(async(name,r)=>{
      await delay(25);return {body:{status:1,data:{count:900,info:Array.from({length:100},(_,i)=>({
        hash:((r.page-1)*100+i+1).toString(16).padStart(32,'0'),filename:'Song '+i}))}}};
    });
    adapter.auth=()=>{};adapter.knownPlaylists.set(id,{id,editable:true});
  } else {
    adapter=new QQAdapter();
    adapter.send=async r=>{
      await delay(r.op==='tracks'?225:25);
      return {playlist:{id},tracks:Array.from({length:r.op==='tracks'?900:100},(_,i)=>({
        id:'qq:'+((r.page-1 || 0)*100+i),name:'Song '+i})),hasMore:r.page<9};
    };
  }
  const manager=new MultiPlatform({[platform]:adapter});manager.accounts[platform]={connected:true};
  return {id,manager};
}
async function playlists(platform) {
  const full=[],first=[],warm=[];
  for(let trial=0;trial<5;trial++) {
    const {id,manager}=fixture(platform);let start=performance.now();
    assert.equal((await manager.run({op:'tracks',id})).tracks.length,900);full.push(performance.now()-start);
    manager.readCache.clear();start=performance.now();
    let snapshot=await manager.run({op:'tracks_start',id,token:'cold'});
    while(!snapshot.tracks?.length){await delay(2);snapshot=await manager.run({op:'tracks_progress',token:'cold'});}
    first.push(performance.now()-start);assert.equal(snapshot.status,'pending');
    await manager.loading.promise;assert.equal(manager.loading.data.tracks.length,900);
    start=performance.now();snapshot=await manager.run({op:'tracks_start',id,token:'warm'});
    warm.push(performance.now()-start);assert.equal(snapshot.tracks.length,900);
    await manager.loading.promise;await manager.close();
  }
  return {completeList:summarize(full),firstUsableBatch:summarize(first),cachedFirstSnapshot:summarize(warm)};
}
async function audio() {
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'musxi-latency-'));const bytes=Buffer.alloc(1024*1024);bytes.write('ID3');
  const server=require('node:http').createServer((req,res)=>{
    res.writeHead(200,{'Content-Length':bytes.length});let offset=0;
    const timer=setInterval(()=>{res.write(bytes.subarray(offset,offset+=65536));if(offset>=bytes.length){clearInterval(timer);res.end();}},25);
    res.on('close',()=>clearInterval(timer));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const full=[],first=[];
  try {
    for(let trial=0;trial<5;trial++)for(const stream of [false,true]) {
      const start=performance.now();const result=await cacheAudio(`http://127.0.0.1:${server.address().port}`,{id:'fixture'},'128',dir,undefined,stream);
      (stream?first:full).push(performance.now()-start);
      while(await fs.access(result.path+'.download').then(()=>true,()=>false))await delay(5);
      assert.deepEqual(await fs.readFile(result.path),bytes);await fs.unlink(result.path);
    }
  }finally{await closeAudioDownloads();server.closeAllConnections();server.close();await fs.rm(dir,{recursive:true,force:true});}
  return {completeDownload:summarize(full),streamPathReady:summarize(first)};
}
async function startupComparison() {
  const previous=[],cached=[],first=[],all=[],background=[];
  for(let trial=0;trial<5;trial++) {
    const create=()=>{
      const adapters=Object.fromEntries(['kugou','netease','qq'].map(platform=>[platform,{async run(request){
        const id=platform==='kugou'?'1':platform+':1';
        const playlists=[{id,name:platform,editable:true,favorite:true,count:100}];
        if(request.op==='init'){await delay(platform==='qq'?250:platform==='kugou'?40:30);return {connected:true,profile:{id:platform,name:platform},session:{profile:{id:platform}}};}
        if(request.op==='sync'){await delay(25+(platform==='kugou' && !request.light?120:0));return {playlists};}
        if(request.op==='vip_auto'){await delay(80);return {outcome:'skipped'};}
        if(request.op==='tracks'){await delay(100);return {playlist:{id},tracks:[{id:platform+':song',name:'Song',artist:'Artist'}]};}
        if(request.op==='playlist_covers'){await delay(120);return {playlists};}
        if(['avatar','membership'].includes(request.op)){await delay(40);return {};}
        throw Error('Unexpected startup request');
      }}]));return new MultiPlatform(adapters);
    };
    const session={platform:'multi',accounts:Object.fromEntries(['kugou','netease','qq'].map(p=>[p,{profile:{id:p}}]))};
    let manager=create(),start=performance.now();
    await manager.run({op:'init',session});await manager.run({op:'vip_auto'});await manager.run({op:'sync'});await manager.run({op:'avatar'});
    previous.push(performance.now()-start);await manager.close();
    manager=create();start=performance.now();
    const initial=await manager.run({op:'startup',session,cachedPlaylists:[{id:'netease:cached',name:'Cached',platform:'netease'}]});
    cached.push(performance.now()-start);assert.equal(initial.playlists[0].id,'netease:cached');assert.equal(initial.accounts.every(a=>!a.playlistsReady),true);
    let firstAt=0;
    while(true) {
      const state=await manager.run({op:'startup_state'});
      if(!firstAt && state.accounts.some(a=>a.playlistsReady))firstAt=performance.now()-start;
      if(state.accounts.every(a=>a.playlistsReady)){all.push(performance.now()-start);break;}
      await delay(2);
    }
    first.push(firstAt);await manager.startupJob.promise;background.push(performance.now()-start);await manager.close();
  }
  assert.ok(Math.max(...first)<Math.min(...previous));
  return {conditions:'Synthetic 3-platform startup, 5 trials. Init 30/40/250ms, lists 25ms, KuGou covers 120ms, VIP 80ms, favorites 100ms, avatar/membership 40ms. Service timings; no accounts or public network.',
    previousServiceChain:summarize(previous),previousKnownUiDelayMs:800,cachedFirstSnapshot:summarize(cached),firstPlatformInteractive:summarize(first),allPlatformsInteractive:summarize(all),backgroundComplete:summarize(background)};
}
(async()=>{
  if(process.argv[2]==='--startup') {
    const results=await startupComparison();await fs.writeFile(path.join(__dirname,'../build/startup-playlists-results.json'),JSON.stringify(results,null,2));
    console.log(JSON.stringify(results,null,2));return;
  }
  const results={conditions:'Synthetic 900-song playlists, 25ms per upstream request; audio 1MiB loopback, 64KiB/25ms; 5 trials. Service readiness only, not public-network latency or physical sound.',playlists:{}};
  for(const platform of ['netease','kugou','qq'])results.playlists[platform]=await playlists(platform);
  results.audio=await audio();
  const output=path.resolve(process.argv[2] || path.join(__dirname,'../build/online-latency-results.json'));
  await fs.writeFile(output,JSON.stringify(results,null,2));console.log(JSON.stringify(results,null,2));
})().catch(error=>{console.error(error);process.exitCode=1;});
