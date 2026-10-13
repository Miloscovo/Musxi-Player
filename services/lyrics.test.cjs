'use strict';
const test=require('node:test');const assert=require('node:assert/strict');
const {Adapter}=require('./bridge.cjs');const {NeteaseAdapter}=require('./netease.cjs');
const {LyricsService}=require('./lyrics.cjs');const {foundLyrics}=require('./lyrics-result.cjs');
const {decodeKrc}=require('./krc.cjs');const {deflateSync}=require('node:zlib');
function encodedKrc(text) {
  const key=Buffer.from([64,71,97,119,94,50,116,71,81,54,49,45,206,210,110,105]);
  const compressed=deflateSync(text);for(let i=0;i<compressed.length;i++)compressed[i]^=key[i%16];
  return Buffer.concat([Buffer.from('krc1'),compressed]).toString('base64');
}
test('KRC decoding validates header, encoding, UTF-8 and inflation limit',()=>{
  const original='[1000,1000]<0,1000,0>原文';
  assert.equal(decodeKrc(encodedKrc(original)),original);
  for(const content of [null,'???','a'.repeat(1400000),Buffer.from('nope').toString('base64'),
    encodedKrc('x'.repeat(1024*1024+1)),encodedKrc('x\0y'),encodedKrc(Buffer.from([0xff,0xfe]))])
    assert.throws(()=>decodeKrc(content));
  const damaged=Buffer.from(encodedKrc(original),'base64');damaged[4]^=0xff;
  assert.throws(()=>decodeKrc(damaged.toString('base64')));
});
test('Kugou translation refresh downloads only KRC of the hash-scoped candidate',async()=>{
  const calls=[],raw='[1000,1000]<0,1000,0>Hello';
  const a=new Adapter(async(name,params)=>{calls.push([name,params]);return {body:name==='search_lyric'?
    {candidates:[{id:2,accesskey:'synthetic'}]}:{content:encodedKrc(raw)}};});
  const result=await a.run({op:'lyrics',track:{hash:'a'.repeat(32)},translationOnly:true});
  assert.deepEqual(result,{outcome:'found',format:'krc',text:raw});
  assert.deepEqual(calls.map(x=>x[0]),['search_lyric','lyric']);assert.equal(calls[1][1].fmt,'krc');
  assert.equal(calls[1][1].decode,false);
});
test('Kugou preview KRC uses the same candidate and damaged KRC keeps LRC',async()=>{
  const valid=encodedKrc('[1000,1000]Hello');
  for(const content of [valid,'bad',undefined]) {
    const calls=[];
    const a=new Adapter(async(name,params)=>{calls.push(params);return {body:name==='search_lyric'?
      {candidates:[{id:2,accesskey:'synthetic'}]}:params.fmt==='lrc'?{decodeContent:'[00:01]Hello'}:{content}};});
    const result=await a.run({op:'lyrics',track:{hash:'a'.repeat(32)},includeTranslation:true});
    assert.equal(result.text,'[00:01]Hello');assert.equal(result.translationState,'unknown');
    assert.equal(result.krc,content===valid?'[1000,1000]Hello':undefined);
    assert.deepEqual(calls.slice(1).map(x=>[x.id,x.accesskey]),[[2,'synthetic'],[2,'synthetic']]);
  }
});
test('Kugou stalled optional KRC does not lose a valid preview original',async()=>{
  const a=new Adapter(async(name,params)=>({body:name==='search_lyric'?{candidates:[{id:2,accesskey:'synthetic'}]}:
    params.fmt==='lrc'?{decodeContent:'[00:01]Hello'}:await new Promise(()=>{})}));
  const started=Date.now();
  const result=await a.run({op:'lyrics',track:{hash:'a'.repeat(32)},includeTranslation:true});
  assert.equal(result.text,'[00:01]Hello');assert.ok(Date.now()-started<5500);
});
test('lyrics service validates internal translation flags',async()=>{
  const service=new LyricsService(()=>({run:async()=>({outcome:'none'})}));
  for(const flags of [{translationOnly:'yes'},{includeTranslation:1},{translationOnly:true},{includeTranslation:true}])
    await assert.rejects(service.run({op:'lyrics',platform:'qq',id:'qq:123',...flags}));
  assert.deepEqual(await service.run({op:'lyrics',platform:'kugou',id:'fixture',translationOnly:true}),{outcome:'none'});
});
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
  assert.deepEqual(await a.run(request),{outcome:'found',format:'lrc',text:'[00:01]Original',translationState:'available',translation:'Translation'});
  // Real NetEase payloads send an empty tlyric for untranslated songs; a missing field is also absence.
  for(const tlyric of [{lyric:''},{},undefined]) {
    body={code:200,lrc:{lyric:'[00:01]Original'},tlyric};const result=await a.run(request);
    assert.equal(result.translationState,'none');assert.equal(result.translation,undefined);
  }
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
test('damaged translation keeps the original and stays retryable',()=>{
  for(const translation of ['x\0y','x'.repeat(1024*1024+1),'abc123ff',42]) {
    const result=foundLyrics('[00:01]Original',translation);
    assert.equal(result.text,'[00:01]Original');assert.equal(result.translationState,'unknown');assert.equal(result.translation,undefined);
  }
  assert.equal(foundLyrics('[00:01]Original').translationState,'unknown');
  assert.equal(foundLyrics('[00:01]Original',null).translationState,'none');
});
test('matching search uses only selected platform and retains recording candidates',async()=>{
  const calls=[];const tracks=[{id:'qq:studio',name:'Song',album:'Studio'},{id:'qq:live',name:'Song',album:'Live'}];
  const service=new LyricsService(platform=>({run:async request=>{calls.push([platform,request]);return {tracks,hasMore:true};}}));
  const response=await service.run({op:'lyrics_search',platform:'qq',keywords:'Song',page:2});
  assert.deepEqual(response,{tracks,hasMore:true});assert.equal(calls.length,1);assert.equal(calls[0][0],'qq');
  assert.equal(calls[0][1].op,'search');assert.equal(calls[0][1].page,2);
  for(const query of [{platform:'other'},{keywords:''},{keywords:'x'.repeat(121)},{page:0},{page:1.5}])
    await assert.rejects(service.run({op:'lyrics_search',platform:'qq',keywords:'Song',page:1,...query}));
});
