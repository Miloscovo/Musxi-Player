import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readPlaylistLoadingCount, rememberPlaylistLoadingCounts, readTrackLoadingCount, rememberTrackLoadingCount } from '../src/composables/playlistLoadingCounts.ts';
import type { LibraryState } from '../src/native/library.ts';

test('playlist skeleton counts remember successful categories and include the cloud favorites entry', () => {
  let value: string | null = null;
  const storage = { getItem: () => value, setItem: (_key: string, next: string) => { value = next; } };
  const state = { connected: true, busy: false, user: 'fixture', operation: { kind: 'sync', status: 'completed' },
    playlists: [{ editable: true }, { editable: true }, { editable: false }] } as LibraryState;
  assert.equal(readPlaylistLoadingCount(storage, null, 'created'), 6);
  rememberPlaylistLoadingCounts(storage, state, false, '');
  assert.equal(readPlaylistLoadingCount(storage, null, 'created'), 3);
  assert.equal(readPlaylistLoadingCount(storage, state, 'saved'), 1);
  assert.equal(readPlaylistLoadingCount(storage, { ...state, user: 'other' }, 'created'), 6);
  for (const next of [{ ...state, busy: true }, { ...state, operation: { ...state.operation, status: 'failed' as const } },
    { ...state, playlists: [], operation: { ...state.operation, kind: 'init', status: 'idle' as const } }]) {
    rememberPlaylistLoadingCounts(storage, next, false, '');
    assert.equal(readPlaylistLoadingCount(storage, null, 'created'), 3);
  }
  rememberPlaylistLoadingCounts(storage, { ...state, playlists: [] }, false, '');
  assert.equal(readPlaylistLoadingCount(storage, null, 'created'), 1);
  assert.equal(readPlaylistLoadingCount(storage, null, 'saved'), 0);
  value = '{broken';
  assert.equal(readPlaylistLoadingCount(storage, null, 'created'), 6);
  value = JSON.stringify({ account: 'fixture', created: 10002, saved: -1 });
  assert.equal(readPlaylistLoadingCount(storage, state, 'created'), 6);
  assert.equal(readPlaylistLoadingCount(storage, state, 'saved'), 6);
  const unavailable = { getItem: () => { throw new Error('unavailable'); }, setItem: () => { throw new Error('unavailable'); } };
  assert.equal(readPlaylistLoadingCount(unavailable, state, 'created'), 6);
  assert.doesNotThrow(() => rememberPlaylistLoadingCounts(unavailable, state, false, ''));
});

test('song skeleton counts use successful totals per account and playlist, without counting only loaded pages', () => {
  const values=new Map<string,string>();
  const storage={getItem:(key:string)=>values.get(key) ?? null,setItem:(key:string,value:string)=>{values.set(key,value);}};
  const state={connected:true,busy:false,user:'fixture',playlistId:'a',trackCount:123,tracks:[{id:'first-page'}],
    operation:{kind:'tracks',status:'completed'}} as LibraryState;
  assert.equal(readTrackLoadingCount(storage,state,'a'),8);
  rememberTrackLoadingCount(storage,state,false,'');
  assert.equal(readTrackLoadingCount(storage,state,'a'),123);
  assert.equal(readTrackLoadingCount(storage,state,'b'),8);
  assert.equal(readTrackLoadingCount(storage,{...state,user:'other'},'a'),8);
  for(const next of [{...state,busy:true},{...state,operation:{...state.operation,status:'failed' as const}}])
    rememberTrackLoadingCount(storage,{...next,trackCount:99},false,'');
  rememberTrackLoadingCount(storage,{...state,trackCount:99},true,'');
  rememberTrackLoadingCount(storage,{...state,trackCount:99},false,'failed');
  assert.equal(readTrackLoadingCount(storage,state,'a'),123);
  rememberTrackLoadingCount(storage,{...state,trackCount:0,tracks:[]},false,'');
  assert.equal(readTrackLoadingCount(storage,state,'a'),0);
  rememberTrackLoadingCount(storage,{...state,user:'',playlistId:'local-folder:one'},false,'');
  assert.equal(readTrackLoadingCount(storage,{...state,user:'other'},'local-folder:one'),123);
});
