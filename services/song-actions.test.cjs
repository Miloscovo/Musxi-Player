'use strict';
const test=require('node:test');const assert=require('node:assert/strict');
const {Adapter}=require('./bridge.cjs');
const hash='a'.repeat(32);
function fixture() {
  const state={rows:{'10':[],'20':[],'30':[]},writes:[],failWrite:false,failRead:false};
  const a=new Adapter(async(name,p)=>{
    const result=data=>({body:{status:1,error_code:0,data}});
    if(name==='user_playlist') return result({total:3,info:[
      {listid:10,name:'默认收藏',is_def:1,is_edit:1},{listid:20,name:'我喜欢',is_def:2,is_edit:1},
      {listid:30,name:'我喜欢',is_edit:0,type:1}]});
    if(name==='playlist_track_all_new') {
      if(state.failRead && state.writes.length) throw Error('offline');
      return result({total:state.rows[p.listid].length,info:state.rows[p.listid]});
    }
    if(state.failWrite) return {body:{status:0,error_code:5}};
    state.writes.push({name,p});
    if(name==='playlist_tracks_add') {
      assert.equal(p.resource.name,'歌手, A | 歌曲');
      state.rows[p.listid].push({fileid:99,hash:p.resource.hash,mixsongid:p.resource.mixsongid,name:p.resource.name});
    } else if(name==='playlist_tracks_del') {
      state.rows[p.listid]=state.rows[p.listid].filter(t=>!p.fileids.split(',').includes(String(t.fileid)));
    } else throw Error('unexpected');
    return result({});
  });
  a.cookie.token='fixture';a.cookie.userid='123';
  a.knownTracks.set('source',{id:'source',hash,name:'歌手, A | 歌曲',albumId:'7',audioId:'42',duration:180000});
  return {a,state};
}
test('menu uses system favorite marker and only offers editable playlists',async()=>{
  const {a,state}=fixture();state.rows['20']=[{fileid:5,hash,mixsongid:42}];
  const menu=await a.run({op:'song_menu',id:'source'});
  assert.equal(menu.liked,true);assert.equal(menu.canFavorite,true);assert.deepEqual(menu.playlists.map(p=>p.id),['10','20']);
  assert.equal(state.writes.length,0);
});
test('add passes structured titles, verifies result and prevents duplicate additions',async()=>{
  const {a,state}=fixture();
  const request={op:'song_update',kind:'add',id:'source',playlistId:'10'};
  const r=await a.run(request);assert.equal(r.tracks.length,1);assert.equal(r.playlist.count,1);
  await a.run(request);assert.equal(state.writes.length,1);assert.equal(state.writes[0].p.resource.album_id,7);
  await assert.rejects(a.run({...request,playlistId:'30'}),/不可编辑/);assert.equal(state.writes.length,1);
});
test('favorite and unfavorite affect only the system favorites and use returned file IDs',async()=>{
  const {a,state}=fixture();state.rows['10']=[{fileid:8,hash,mixsongid:42}];
  await a.run({op:'song_update',id:'source',kind:'favorite',enabled:true});
  assert.equal(state.rows['20'].length,1);
  await a.run({op:'song_update',id:'source',kind:'favorite',enabled:false,playlistId:'10'});
  assert.equal(state.rows['20'].length,0);assert.equal(state.rows['10'].length,1);
  assert.equal(state.writes[1].p.fileids,'99');assert.equal(state.writes[1].p.listid,'20');
});
test('rejected writes and unverifiable outcomes never report success or auto-retry',async()=>{
  const {a,state}=fixture();const request={op:'song_update',kind:'favorite',id:'source',enabled:true};
  state.failWrite=true;await assert.rejects(a.run(request),/接口返回错误/);assert.equal(state.rows['20'].length,0);
  state.failWrite=false;state.failRead=true;await assert.rejects(a.run(request),/已提交.*无法核实/);assert.equal(state.writes.length,1);
});
test('unknown tracks, missing login and missing delete identifiers fail before mutation',async()=>{
  const {a,state}=fixture();const request={op:'song_update',kind:'favorite',id:'source',enabled:false};
  state.rows['20']=[{hash,mixsongid:42}];await assert.rejects(a.run(request),/缺少删除标识/);
  await assert.rejects(a.run({...request,id:'missing'}),/有效标识/);
  delete a.cookie.token;await assert.rejects(a.run(request),/先扫码/);assert.equal(state.writes.length,0);
});
