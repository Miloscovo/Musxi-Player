import test from 'node:test';
import assert from 'node:assert/strict';
import { createDemoTransport } from '../src/demo/context.ts';
import { createLibraryClient } from '../src/native/library.ts';
import { createLyricsMatchClient, parseMatchState } from '../src/native/lyricsMatch.ts';
import type { CefTransport } from '../src/native/transport.ts';
test('matching preview is temporary; save, offset, reopen and restore use native contracts',async()=>{
  const host=createDemoTransport(),library=createLibraryClient(host),match=createLyricsMatchClient(host);
  await library.importLocal();const signal=new AbortController().signal;
  const run=(action:string,params:Record<string,unknown>={})=>match.run('local:0',action,params,signal);
  let state=await run('begin');const session=state.session;
  assert.ok(state.suggestion?.title);
  state=await run('search',{session,platform:'qq',keywords:'Song',page:1});assert.equal(state.candidates?.[0].platform,'qq');
  state=await run('preview',{session,candidate:'0'});assert.equal(state.preview?.origin,'matched');
  assert.notEqual((await library.lyrics('local','local:0')).origin,'matched');
  state=await run('adjust',{session,offsetMs:1500});assert.equal(state.preview?.lines[0].timeMs,1500);
  await run('save',{session,offsetMs:1500});assert.equal((await library.lyrics('local','local:0')).lines[0].timeMs,1500);
  state=await run('begin');assert.equal(state.preview?.offsetMs,1500);
  assert.equal(state.platform,'qq');
  await assert.rejects(run('save',{session,offsetMs:0}));
  await run('save',{session:state.session,offsetMs:-9000});
  state=await run('begin');
  state=await run('adjust',{session:state.session,offsetMs:0});
  assert.equal(state.preview?.lines[1].timeMs,8000,'reopening a clamped offset must preserve original times');
  await run('reset',{session:state.session});assert.notEqual((await library.lyrics('local','local:0')).origin,'matched');
});
test('matching parser rejects malformed candidates and cross-song previews',()=>{
  const value={status:'ready',id:'local:1',session:'1',job:'2',candidates:[],hasMore:false,selectedName:'',platform:'',preview:null};
  assert.equal(parseMatchState(value).id,'local:1');
  assert.throws(()=>parseMatchState({...value,candidates:[{key:'0',name:'x',artist:'y',album:'',duration:-1,platform:'qq'}]}));
  assert.throws(()=>parseMatchState({...value,preview:{status:'ready',source:'local',id:'local:other',platform:'local',origin:'matched',kind:'none',lines:[]}}));
});
test('matching client polls job tokens and rejects crossed sessions',async()=>{
  const calls:Record<string,unknown>[]=[];
  const host:CefTransport={cefQuery:query=>{
    const p=JSON.parse(query.request).params;calls.push(p);
    query.onSuccess(JSON.stringify({version:1,result:p.action==='search'?{status:'pending',id:'local:1',session:'session',job:'job'}:
      {status:'ready',id:'local:1',session:'wrong',job:'job',candidates:[],hasMore:false,preview:null,selectedName:'',platform:''}}));return calls.length;
  },cefQueryCancel:()=>{}};
  await assert.rejects(createLyricsMatchClient(host).run('local:1','search',{session:'session',platform:'qq',keywords:'song',page:1},new AbortController().signal));
  assert.deepEqual(calls[1],{action:'poll',id:'local:1',session:'session',job:'job'});
});
