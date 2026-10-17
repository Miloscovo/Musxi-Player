'use strict';
const test=require('node:test');const assert=require('node:assert/strict');const {Adapter}=require('./bridge.cjs');
function fixture() {
  const state={vip:false,listenVip:false,claimed:false,granted:true,failed:false,writes:0,statusUnknown:false,recordUnknown:false};
  const a=new Adapter(async(name,p)=>{
    const ok=data=>({body:{status:1,error_code:0,data}});
    if(name==='user_vip_detail') return ok(state.statusUnknown?{is_vip:null,busi_vip:[]}:{is_vip:state.listenVip?1:0,busi_vip:[{busi_type:'concept',product_type:'svip',is_vip:state.vip?1:0},{busi_type:'concept',product_type:'tvip',is_vip:state.listenVip?1:0}]});
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

test('listening VIP does not skip concept VIP eligibility or falsely confirm a concept grant',async()=>{
  const {a,state}=fixture();state.listenVip=true;
  assert.equal((await a.run({op:'vip_auto'})).outcome,'claimed');
  assert.equal(state.writes,1);assert.equal(state.vip,true);
  const other=fixture();other.state.listenVip=true;other.state.granted=false;
  assert.deepEqual(await other.a.run({op:'vip_auto'}),{outcome:'unverified',notify:false});
  assert.equal(other.state.writes,1);
});

test('a missing concept entry with only listening VIP still checks concept eligibility',async()=>{
  const {a,state}=fixture();state.listenVip=true;
  const call=a.call;a.call=async(name,p)=>{
    const result=await call(name,p);
    if(name==='user_vip_detail' && !state.vip)result.body.data.busi_vip=result.body.data.busi_vip.filter(v=>v.product_type==='tvip');
    return result;
  };
  assert.equal((await a.run({op:'vip_auto'})).outcome,'claimed');assert.equal(state.writes,1);
});

test('unknown product codes never authorize an automatic concept claim',async()=>{
  let calls=0;
  const a=new Adapter(async name=>{
    ++calls;assert.equal(name,'user_vip_detail');
    return {body:{status:1,data:{is_vip:0,busi_vip:[{busi_type:'concept',product_type:'future',is_vip:1}]}}};
  });a.cookie={userid:'123',token:'fixture'};
  assert.deepEqual(await a.run({op:'vip_auto'}),{outcome:'unknown_status',notify:false});assert.equal(calls,1);
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

test('membership reads entitlement without claiming and retains unknown states',async()=>{
  const {a,state}=fixture();
  assert.deepEqual(await a.run({op:'membership'}),{name:'非会员'});
  state.vip=true;assert.deepEqual(await a.run({op:'membership'}),{name:'概念版 VIP'});
  state.statusUnknown=true;assert.deepEqual(await a.run({op:'membership'}),{name:'会员状态暂不可用'});
  assert.equal(state.writes,0);
});

test('membership expiry belongs to active concept entitlement and uses China time',async()=>{
  const a=new Adapter(async()=>({body:{status:1,error_code:0,data:{is_vip:0,vip_end_time:'2099-01-01 00:00:00',busi_vip:[{busi_type:'music',is_vip:1,vip_end_time:'2099-01-01 00:00:00'},{busi_type:'concept',product_type:'svip',is_vip:1,vip_end_time:'2028-01-01 00:00:00'}]}}}));
  a.cookie.token='fixture';a.cookie.userid='123';
  assert.deepEqual(await a.run({op:'membership'}),{name:'概念版 VIP',expiresAt:1830268800000});
});

test('membership prefers concept VIP regardless of order and keeps each product expiry separate',async()=>{
  const concept={busi_type:'concept',product_type:'svip',is_vip:1,vip_end_time:'2028-01-01 00:00:00'};
  const listening={busi_type:'concept',product_type:'tvip',is_vip:1,vip_end_time:'2028-02-01 00:00:00'};
  let products=[listening,concept];
  const a=new Adapter(async()=>({body:{status:1,data:{is_vip:0,busi_vip:products}}}));a.cookie={userid:'123',token:'fixture'};
  for(const order of [[listening,concept],[concept,listening]]){
    products=order;assert.deepEqual(await a.run({op:'membership'}),{name:'概念版 VIP',expiresAt:1830268800000});
  }
  products=[{...concept,is_vip:0},listening];
  assert.deepEqual(await a.run({op:'membership'}),{name:'畅听 VIP',expiresAt:1832947200000});
});
