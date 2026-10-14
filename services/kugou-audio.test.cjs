const test=require('node:test');
const assert=require('node:assert/strict');
const {audioHeader,inspectAudio}=require('./kugou-audio.cjs');
const {Adapter}=require('./bridge.cjs');
function flac(rate=96000,bits=24,seconds=180) {
  const bytes=Buffer.alloc(256);bytes.write('fLaC');bytes[4]=128;bytes.writeUIntBE(34,5,3);
  bytes.writeBigUInt64BE(BigInt(rate)<<44n | 1n<<41n | BigInt(bits-1)<<36n | BigInt(rate*seconds),18);
  return bytes;
}
function serve(bytes) {return async()=>({ok:true,body:(async function*(){yield bytes;})()});}
test('audio bytes identify FLAC resolution and reject misleading extensions, bad signatures and truncated headers',()=>{
  assert.deepEqual(audioHeader(flac()),{extension:'.flac',rate:96000,bits:24,duration:180000});
  assert.equal(audioHeader(Buffer.from('encrypted.flac')),null);
  assert.equal(audioHeader(flac().subarray(0,41)),null);
  const highBit=flac();highBit[0]|=128;assert.equal(audioHeader(highBit),null);
  assert.equal(audioHeader(flac(0)),null);
});
test('advanced audio uses the exact entitled variant and falls back to the current authorized API',async()=>{
  const original=global.fetch;global.fetch=serve(flac());const calls=[];
  const base='a'.repeat(32),advanced='b'.repeat(32);
  const variant={hash:advanced,quality:'high',album_audio_id:9,info:{tracker_type:'full'}};
  const adapter=new Adapter(async(name,p)=>{
    calls.push([name,p]);
    if(name==='privilege_lite')return {body:{status:1,data:[{hash:base,quality:'128',relate_goods:[variant]}]}};
    if(name==='song_url')return {body:{status:2}};
    if(name==='user_verify')return {body:{status:1,data:{valid:1,auth:'synthetic-user-auth'}}};
    if(name==='song_auth')return {body:{status:1,data:{auth:'synthetic-song-auth',open_time:123}}};
    if(name==='song_url_auth')return {body:{status:1,hash:advanced,url:['https://example.test/audio'],extName:'flac'}};
    throw Error('Unexpected request');
  });adapter.cookie={userid:1,token:'synthetic'};adapter.knownTracks.set('song',{id:'song',hash:base,duration:180000});
  try {
    const result=await adapter.run({op:'audio',id:'song',quality:'kugou:high'});
    assert.equal(result.quality,'kugou:high');
    assert.equal(calls.find(([name])=>name==='song_url')[1].hash,advanced);
    const authorized=calls.find(([name])=>name==='song_url_auth')[1];
    assert.equal(authorized.hash,advanced);assert.equal(authorized.auth,'synthetic-song-auth');assert.equal(authorized.open_time,123);
    const before=calls.length;variant.hash=base;
    await assert.rejects(adapter.run({op:'audio',id:'song',quality:'kugou:high'}),/没有可确认/);
    assert.equal(calls.length,before+1);
  } finally {global.fetch=original;}
});
test('advanced menu excludes fallback, trial, low resolution, unsupported codec and mismatched hashes',async()=>{
  const original=global.fetch,base='a'.repeat(32),advanced='b'.repeat(32);
  let bytes=flac(),data={status:1,hash:advanced,url:['https://example.test/audio']};
  const adapter=new Adapter(async(name)=>{
    if(name==='privilege_lite')return {body:{status:1,data:[{hash:base,quality:'128',relate_goods:[{hash:advanced,quality:'high'}]}]}};
    if(name==='song_url')return {body:data};
    throw Error('No authorization');
  });adapter.cookie={userid:1,token:'synthetic'};adapter.knownTracks.set('song',{id:'song',hash:base,duration:180000});
  global.fetch=async()=>serve(bytes)();
  try {
    assert.ok((await adapter.run({op:'qualities',id:'song'})).options.some(q=>q.id==='kugou:high'));
    for(const bad of [{...data,hash:base},{...data,is_free_part:1},{...data,hash_offset:{end_ms:30000}}]) {
      const good=data;data=bad;
      assert.ok(!(await adapter.run({op:'qualities',id:'song'})).options.some(q=>q.id==='kugou:high'));
      data=good;
    }
    for(const bad of [flac(44100,16),flac(96000,24,30),Buffer.from('MP4 unsupported'),Buffer.from('encrypted')]) {
      bytes=bad;await assert.rejects(adapter.run({op:'audio',id:'song',quality:'kugou:high'}),/保留当前播放/);
    }
  } finally {global.fetch=original;}
});
