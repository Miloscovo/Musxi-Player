'use strict';
const test=require('node:test');const assert=require('node:assert/strict');const {Adapter}=require('./bridge.cjs');
function fixture() {
  const state={vip:false,claimed:false,granted:true,failed:false,writes:0,statusUnknown:false,recordUnknown:false};
  const a=new Adapter(async(name,p)=>{
    const ok=data=>({body:{status:1,error_code:0,data}});
    if(name==='user_vip_detail') return ok(state.statusUnknown?{is_vip:null,busi_vip:[]}:{is_vip:0,busi_vip:[{busi_type:'concept',product_type:'svip',is_vip:state.vip?1:0}]});
    if(name==='youth_month_vip_record') return ok(state.recordUnknown?{list:null}:{server_time:Date.parse('2026-09-14T16:01:00Z')/1000,list:state.claimed?[{day:'2026-09-15',receive_vip:1}]:[]});
    assert.equal(name,'youth_day_vip');assert.equal(p.receive_day,'2026-09-15');++state.writes;
    if(state.failed) throw Error('not eligible');
    if(state.granted) {state.vip=true;state.claimed=true;}return ok({});
  });
  a.cookie.token='fixture';a.cookie.userid='123';return {a,state};
}
test('active concept VIP is recognized even when general VIP flag is zero',async()=>{
  const {a,state}=fixture();state.vip=true;
  assert.deepEqual(await a.run({op:'vip_auto'}),{outcome:'already_vip',notify:false});assert.equal(state.writes,0);
});
test('already received daily benefit stays silent without a second claim',async()=>{
  const {a,state}=fixture();state.claimed=true;
  assert.deepEqual(await a.run({op:'vip_auto'}),{outcome:'already_claimed',notify:false});assert.equal(state.writes,0);
});
test('claims only server-local today and notifies only after both receipt and VIP verification',async()=>{
  const {a,state}=fixture();const r=await a.run({op:'vip_auto'});
  assert.equal(r.outcome,'claimed');assert.equal(r.notify,true);assert.equal(r.day,'2026-09-15');
  assert.equal((await a.run({op:'vip_auto'})).notify,false);assert.equal(state.writes,1);
});
test('successful HTTP/API response without entitlement does not trigger success toast or repeated claims',async()=>{
  const {a,state}=fixture();state.granted=false;
  assert.deepEqual(await a.run({op:'vip_auto'}),{outcome:'unverified',notify:false});
  assert.deepEqual(await a.run({op:'vip_auto'}),{outcome:'already_attempted',notify:false});assert.equal(state.writes,1);
});
test('ineligible or failed claims remain silent and are not retried automatically',async()=>{
  const {a,state}=fixture();state.failed=true;
  assert.equal((await a.run({op:'vip_auto'})).outcome,'unavailable');
  assert.equal((await a.run({op:'vip_auto'})).notify,false);assert.equal(state.writes,1);
});
test('unknown membership or receipt schema never initiates a claim',async()=>{
  const {a,state}=fixture();state.statusUnknown=true;
  assert.equal((await a.run({op:'vip_auto'})).outcome,'unknown_status');state.statusUnknown=false;state.recordUnknown=true;
  assert.equal((await a.run({op:'vip_auto'})).outcome,'unknown_record');assert.equal(state.writes,0);
});
test('logged-out user cannot claim',async()=>{
  const {a,state}=fixture();delete a.cookie.token;await assert.rejects(a.run({op:'vip_auto'}),/先扫码/);assert.equal(state.writes,0);
});
