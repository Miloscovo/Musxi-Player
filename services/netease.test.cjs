const test=require('node:test');
const assert=require('node:assert/strict');
const {NeteaseAdapter}=require('./netease.cjs');
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
