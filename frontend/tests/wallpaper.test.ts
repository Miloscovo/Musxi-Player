import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readVisualSettings,reduceVisual,writeVisualSettings,type VisualState } from '../src/composables/appearancePreview.ts';
import { parseWallpaper,createWallpaperClient } from '../src/native/wallpaper.ts';
import { createDemoTransport } from '../src/demo/context.ts';

const defaults = () => readVisualSettings({ getItem:()=>null });
test('wallpaper and dock are disabled by default while existing appearance settings are retained',()=>{
  assert.deepEqual(defaults(),{ appearance:{mode:'light',tone:'green',transparent:false},transparency:50,wallpaperEnabled:false,playerTransparent:false });
  const values:Record<string,string>={ 'musxi-theme':'glass','musxi-transparency':'65','musxi-tone':'blue','musxi-wallpaper-enabled':'true','musxi-player-transparent':'true' };
  assert.deepEqual(readVisualSettings({getItem:key=>values[key]??null}),{appearance:{mode:'dark',tone:'blue',transparent:true},transparency:65,wallpaperEnabled:true,playerTransparent:true});
});
test('preview isolates all visual edits; cancel restores them and apply commits them together',()=>{
  const original=defaults();
  let state:VisualState={saved:original,preview:null};state=reduceVisual(state,{type:'begin'});
  assert.equal(state.preview?.appearance.transparent,true);assert.equal(state.saved.appearance.transparent,false);
  state=reduceVisual(state,{type:'transparency',value:80});state=reduceVisual(state,{type:'player',value:true});
  state=reduceVisual(state,{type:'appearance',value:{mode:'dark',tone:'pink',transparent:true}});
  assert.strictEqual(state.saved,original);assert.equal(state.preview?.wallpaperEnabled,true);
  assert.deepEqual(reduceVisual(state,{type:'cancel'}),{saved:original,preview:null});
  const applied=reduceVisual(state,{type:'apply'});assert.equal(applied.preview,null);assert.deepEqual(applied.saved,state.preview);
});
test('disabling main transparency cancels preview but saves only that operation; wallpaper reset leaves other preferences alone',()=>{
  const original={ ...defaults(),appearance:{...defaults().appearance,transparent:true},wallpaperEnabled:true,playerTransparent:true };
  let state:VisualState=reduceVisual({saved:original,preview:null},{type:'begin'});
  state=reduceVisual(state,{type:'appearance',value:{mode:'dark',tone:'red',transparent:true}});
  assert.deepEqual(reduceVisual(state,{type:'appearance',value:{mode:'dark',tone:'red',transparent:false}}),{
    saved:{...original,appearance:{...original.appearance,transparent:false}},preview:null });
  assert.deepEqual(reduceVisual(state,{type:'resetWallpaper'}),{saved:{...original,wallpaperEnabled:false},preview:null});
  const suspended=reduceVisual({saved:original,preview:null},{type:'appearance',value:{...original.appearance,transparent:false}});
  assert.equal(suspended.saved.wallpaperEnabled,true);
});
test('visual persistence contains preferences only, never candidate resources or preview state',()=>{
  const values:Record<string,string>={};const storage={getItem:(key:string)=>values[key]??null,setItem:(key:string,value:string)=>{values[key]=value;}};
  const settings=defaults();writeVisualSettings(storage,settings,false);
  assert.deepEqual(readVisualSettings(storage),settings);
  assert.ok(Object.values(values).every(value=>!value.includes('data:') && !value.includes('candidate')));
});
test('wallpaper replies reject arbitrary paths, remote URLs, oversized images and malformed types',()=>{
  const image={id:'saved',url:'data:image/png;base64,YQ==',width:320,height:180};assert.deepEqual(parseWallpaper(image),image);
  for(const bad of [{...image,id:'../../secret'},{...image,url:'file:///C:/secret.png'},{...image,url:'https://example.com/a.png'},
    {...image,width:20000},{...image,width:10000,height:10000},{...image,url:'data:image/svg+xml;base64,YQ=='}])assert.throws(()=>parseWallpaper(bad));
});
test('demo wallpaper choices remain candidates until explicitly applied; reset clears the saved resource',async()=>{
  const client=createWallpaperClient(createDemoTransport());assert.equal(await client.getState(),null);
  const candidate=(await client.choose())!;assert.equal(await client.getState(),null);await client.accept(candidate.id);
  const applied=(await client.apply(candidate.id))!;assert.equal(applied.id,'saved');assert.deepEqual(await client.getState(),applied);
  await client.choose();assert.deepEqual(await client.getState(),applied);await client.reset();assert.equal(await client.getState(),null);
});
test('wallpaper transport accepts a 32 MiB image payload and rejects one above the limit',()=>{
  const url='data:image/jpeg;base64,'+'A'.repeat(Math.ceil(32*1024*1024/3)*4);
  const image={id:'saved',url,width:320,height:180};
  assert.strictEqual(parseWallpaper(image),image);
  assert.throws(()=>parseWallpaper({...image,url:url+'AAAA'}));
});
