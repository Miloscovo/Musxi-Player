'use strict';
const test=require('node:test');const assert=require('node:assert/strict');
const {Adapter}=require('./bridge.cjs');const {NeteaseAdapter}=require('./netease.cjs');
const {LyricsService}=require('./lyrics.cjs');const {foundLyrics}=require('./lyrics-result.cjs');
test('Kugou lyrics use recording metadata without search/login initialization',async()=>{
  const calls=[];const hash='a'.repeat(32);
  const a=new Adapter(async(name,params)=>{calls.push([name,params]);return {body:name==='search_lyric'?
    {status:200,candidates:[{id:1,hash:'b'.repeat(32),accesskey:'wrong'},{id:2,hash,accesskey:'key'}]}:
    {status:200,decodeContent:'[00:01.00]Hello'}};});
  const result=await a.run({op:'lyrics',track:{hash,audioId:'42',duration:180000},session:{cookie:{token:'synthetic'}}});
  assert.equal(result.text,'[00:01.00]Hello');assert.deepEqual(calls.map(c=>c[0]),['search_lyric','lyric']);
  assert.equal(calls[0][1].hash,hash);assert.equal(calls[0][1].keywords,undefined);
  assert.equal(calls[1][1].id,2);assert.equal(calls[1][1].fmt,'lrc');
});
test('Kugou explicit empty candidates differ from failure or malformed download',async()=>{
  const request={op:'lyrics',track:{hash:'a'.repeat(32)}};
  assert.deepEqual(await new Adapter(async()=>({body:{status:200,candidates:[]}})).run(request),{outcome:'none'});
  for(const body of [{status:500},{status:200},{status:200,candidates:[{id:1,accesskey:'key'}]}])
    await assert.rejects(new Adapter(async()=>({body})).run(request));
});
test('Netease preserves original lyrics and distinguishes absence from network failure',async()=>{
  let body={code:200,lrc:{lyric:'[00:01]Original'},tlyric:{lyric:'Translation'}};
  const a=new NeteaseAdapter(async(name,p)=>{assert.equal(name,'lyric');assert.equal(p.id,'123');return {body};});
  const request={op:'lyrics',id:'netease:123'};
  assert.equal((await a.run(request)).text,'[00:01]Original');
  for(const field of ['nolyric','uncollected']){body={code:200,[field]:true};assert.deepEqual(await a.run(request),{outcome:'none'});}
  for(const b of [{code:500},{code:200},{code:200,lrc:{lyric:''}}]){body=b;await assert.rejects(a.run(request));}
  await assert.rejects(a.run({...request,id:'qq:123'}));
});
test('independent lyrics service creates only requested adapter and never initializes accounts',async()=>{
  const calls=[];const service=new LyricsService(platform=>({run:async request=>{calls.push([platform,request]);return {outcome:'none'};}}));
  const request={op:'lyrics',platform:'netease',id:'netease:123',session:{cookie:'synthetic'}};
  await service.run(request);assert.equal(service.adapters.size,1);assert.deepEqual(calls,[['netease',request]]);
  await assert.rejects(service.run({...request,platform:'qq'}));
  await assert.rejects(service.run({...request,op:'audio'}));
});
test('raw lyric transport rejects empty, oversized and NUL content',()=>{
  for(const text of [null,'','  ','x\0y','x'.repeat(1024*1024+1)])assert.throws(()=>foundLyrics(text));
});
