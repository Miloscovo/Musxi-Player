import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createNativeClient } from '../src/native/client.ts';
import { createDemoTransport } from '../src/demo/context.ts';
import { parsePlaybackSettings } from '../src/native/library.ts';
test('playback settings validate replies and demo changes survive page navigation',async()=>{
 const settings={defaultQuality:'128',outputDevice:'',devices:[{id:'device',name:'Output'}]};
 assert.deepEqual(parsePlaybackSettings(settings),settings);
 for(const bad of [{...settings,defaultQuality:'bad'},{...settings,outputDevice:4},{...settings,devices:[{id:'',name:'Bad'}]},{...settings,devices:[settings.devices[0],settings.devices[0]]}])assert.throws(()=>parsePlaybackSettings(bad));
 const client=createNativeClient(createDemoTransport());
 assert.equal((await client.library.getPlaybackSettings()).defaultQuality,'128');
 const saved=await client.library.setPlaybackSettings({defaultQuality:'flac',outputDevice:'demo-output'});
 assert.equal(saved.defaultQuality,'flac');assert.equal(saved.outputDevice,'demo-output');
 assert.deepEqual(await client.library.getPlaybackSettings(),saved);
 await assert.rejects(client.library.setPlaybackSettings({defaultQuality:'128',outputDevice:'bad'}));
 assert.deepEqual(await client.library.getPlaybackSettings(),saved);
});
