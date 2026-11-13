import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseDynamicState, parseWallpaperListing, parseWallpaperSession, createDynamicWallpaperClient, wallpaperOrigin } from '../src/native/dynamicWallpaper.ts';
import { createDemoTransport } from '../src/demo/context.ts';
test('dynamic wallpaper is opt-in and enabling does not scan or open a renderer', async () => {
  const client = createDynamicWallpaperClient(createDemoTransport());
  assert.deepEqual(await client.getState(), { enabled: false, selected: '', directories: [], fps:60, quality:'standard' });
  assert.equal((await client.setEnabled(true)).enabled, true);
  assert.equal((await client.setPlayback({fps:30,quality:'high'})).quality,'high');
  assert.equal((await client.getState()).fps,30);
  assert.deepEqual(await client.scan(), { items: [], directories: [] });
  assert.equal((await client.setEnabled(false)).enabled, false);
});

test('wallpaper settings migrate defaults and reject invalid settings and scan diagnostics', () => {
  const legacy={enabled:true,selected:'',directories:[]};
  assert.deepEqual(parseDynamicState(legacy),{...legacy,fps:60,quality:'standard'});
  for(const fps of [0,59,120,'60',null])assert.throws(()=>parseDynamicState({...legacy,fps}));
  for(const quality of ['ultra',0,null])assert.throws(()=>parseDynamicState({...legacy,quality}));
  const item={id:'1',title:'Scene',type:'scene',selected:false,source:'D:/Steam',preview:`${wallpaperOrigin}/thumbnail/1?v=2`};
  const report={directory:'D:/Steam',count:1,invalid:0,unsupported:2,missing:0,error:''};
  assert.equal(parseWallpaperListing({items:[item],directories:[],reports:[report]}).reports?.[0],report);
  for(const preview of [`${item.preview}&file=secret`,`${wallpaperOrigin}/thumbnail/1?v=../a`])
    assert.throws(()=>parseWallpaperListing({items:[{...item,preview}],directories:[]}));
  for(const count of [-1,1.2,'1',Infinity])assert.throws(()=>parseWallpaperListing({items:[],directories:[],reports:[{...report,count}]}));
});
test('wallpaper replies admit only native-owned sessions and thumbnail URLs', () => {
  const item = { id:'12', title:'Scene', type:'scene', selected:false, preview:`${wallpaperOrigin}/thumbnail/12` };
  assert.equal(parseWallpaperListing({items:[item],directories:[]}).items[0], item);
  for(const preview of ['file:///secret','https://example.com/a.png',`${wallpaperOrigin}/thumbnail/13`])
    assert.throws(() => parseWallpaperListing({items:[{...item,preview}],directories:[]}));
  for(const type of ['web','application',null])assert.throws(() => parseWallpaperListing({items:[{...item,type}],directories:[]}));
  const session = {url:`${wallpaperOrigin}/session-3/player.html`,type:'video',file:'movie.mp4'};
  assert.deepEqual(parseWallpaperSession(session),session);
  for(const url of ['file:///secret',`${session.url}?x=1`,`${wallpaperOrigin}/session-../player.html`])assert.throws(() => parseWallpaperSession({...session,url}));
  assert.throws(() => parseDynamicState({enabled:'true',selected:'',directories:[]}));
  assert.throws(() => parseDynamicState({enabled:true,selected:'',directories:[{}]}));
});
