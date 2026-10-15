import { test } from 'node:test';
import assert from 'node:assert/strict';
import { bindWheelRange } from '../src/composables/wheelRange.ts';

let nextFrame = 0;
const frames = new Map<number, FrameRequestCallback>();
globalThis.requestAnimationFrame = callback => { frames.set(++nextFrame, callback); return nextFrame; };
globalThis.cancelAnimationFrame = id => { frames.delete(id); };
function advanceFrame(ms = 200) {
  const callbacks = [...frames.values()]; frames.clear();
  callbacks.forEach(callback => callback(performance.now() + ms));
}

test('volume wheel uses five-percent steps, clamps to mute/full volume and waits for exact native volume', async () => {
  for(const [start,deltaY,expected] of [[98,120,100],[2,-120,0],[0,120,5],[100,-120,95]]) {
    const input=Object.assign(new EventTarget(),{disabled:false,min:'0',max:'100',value:String(start)});
    let actual=start;let draft:number|null=null;const calls:number[]=[];
    const stop=bindWheelRange(input as HTMLInputElement,async value=>{calls.push(value);},value=>{draft=value;},()=>actual,5,1);
    try {
      const event=Object.assign(new Event('wheel',{cancelable:true}),{deltaY});input.dispatchEvent(event);
      assert.equal(event.defaultPrevented,true);
      advanceFrame(40);
      assert.ok(draft!>Math.min(start,expected) && draft!<Math.max(start,expected));
      await new Promise(resolve=>setTimeout(resolve,110));advanceFrame();
      assert.deepEqual(calls,[expected]);assert.equal(draft,expected);
      actual=expected;advanceFrame();assert.equal(draft,null);
    } finally {stop();}
  }
});

test('clicking mute cancels an outstanding wheel preview and scheduled volume command', async () => {
  const controls=new EventTarget();
  const input=Object.assign(new EventTarget(),{parentElement:controls,disabled:false,min:'0',max:'100',value:'50'});
  const calls:number[]=[];let draft:number|null=null;
  const stop=bindWheelRange(input as unknown as HTMLInputElement,async value=>{calls.push(value);},value=>{draft=value;},()=>50,5,1);
  try {
    input.dispatchEvent(Object.assign(new Event('wheel',{cancelable:true}),{deltaY:120}));advanceFrame();
    assert.equal(draft,55);
    controls.dispatchEvent(new Event('click'));advanceFrame();assert.equal(draft,null);
    await new Promise(resolve=>setTimeout(resolve,110));assert.deepEqual(calls,[]);
  } finally {stop();}
});

test('timeline wheel accumulates, clamps, cancels scrolling and cancels on direct interaction', async () => {
  const input = Object.assign(new EventTarget(), { disabled: false, min: '0', max: '29999', value: '10000' });
  const calls: number[] = []; let draft: number | null = null;
  const stop = bindWheelRange(input as HTMLInputElement, async position => { calls.push(position); }, value => { draft = value; }, () => calls.at(-1) ?? 10000, 5000, 1000);
  function wheel(deltaY: number) {
    const event = Object.assign(new Event('wheel', { cancelable: true }), { deltaY });
    input.dispatchEvent(event); return event.defaultPrevented;
  }
  try {
    assert.equal(wheel(120), true); wheel(120);
    advanceFrame(40);
    assert.ok(draft! > 10000 && draft! < 20000, 'wheel progress should interpolate instead of jumping');
    await new Promise(resolve => setTimeout(resolve, 110));
    advanceFrame();
    assert.deepEqual(calls, [20000]); assert.equal(draft, null);
    wheel(120); wheel(120); wheel(120); wheel(120); advanceFrame();
    assert.equal(draft, 29999);
    wheel(-120); advanceFrame(); assert.equal(draft, 24999);
    for (let n = 0; n < 6; n++) wheel(-120);
    advanceFrame();
    assert.equal(draft, 0);
    input.dispatchEvent(new Event('pointerdown'));
    await new Promise(resolve => setTimeout(resolve, 110));
    assert.deepEqual(calls, [20000]);
    input.disabled = true; assert.equal(wheel(120), false);
    input.disabled = false; assert.equal(wheel(0), false);
    wheel(120); stop(); advanceFrame();
    await new Promise(resolve => setTimeout(resolve, 110));
    assert.deepEqual(calls, [20000]);
  } finally { stop(); }
});

test('wheel bursts keep accumulating while an earlier seek is still pending', async () => {
  const input = Object.assign(new EventTarget(), { disabled: false, min: '0', max: '59999', value: '10000' });
  const calls: number[] = []; const replies: (() => void)[] = []; let draft: number | null = null;
  const stop = bindWheelRange(input as HTMLInputElement, position => {
    calls.push(position); return new Promise<void>(resolve => replies.push(resolve));
  }, value => { draft = value; }, () => calls.at(-1) ?? 10000, 5000, 1000);
  const wheel = () => input.dispatchEvent(Object.assign(new Event('wheel', { cancelable: true }), { deltaY: 120 }));
  try {
    wheel(); await new Promise(resolve => setTimeout(resolve, 110));
    wheel(); advanceFrame(); replies[0](); await Promise.resolve();
    assert.equal(draft, 20000);
    await new Promise(resolve => setTimeout(resolve, 110));
    assert.deepEqual(calls, [15000, 20000]);
    replies[1](); await Promise.resolve(); advanceFrame(); assert.equal(draft, null);
  } finally { stop(); replies.forEach(resolve => resolve()); }
});

test('smooth preview waits for the native position instead of snapping back after acknowledgement', async () => {
  const input = Object.assign(new EventTarget(), { disabled: false, min: '0', max: '59999', value: '10000' });
  let actual: number | null = 10000; let draft: number | null = null;
  const stop = bindWheelRange(input as HTMLInputElement, async () => {}, value => { draft = value; }, () => actual, 5000, 1000);
  try {
    input.dispatchEvent(Object.assign(new Event('wheel', { cancelable: true }), { deltaY: 120 }));
    await new Promise(resolve => setTimeout(resolve, 110));
    advanceFrame(); assert.equal(draft, 15000);
    actual = null; advanceFrame(); assert.equal(draft, 15000);
    actual = 15000; advanceFrame(); assert.equal(draft, null);
  } finally { stop(); }
});
