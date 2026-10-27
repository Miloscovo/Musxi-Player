import { test } from 'node:test';
import assert from 'node:assert/strict';
import { vinylColor } from '../src/composables/vinylColor.ts';

test('vinyl color samples visible cover colors and ignores transparent or extreme pixels', () => {
  assert.equal(vinylColor(new Uint8ClampedArray([180, 40, 80, 255, 180, 40, 80, 255])), 'rgb(180, 40, 80)');
  assert.equal(vinylColor(new Uint8ClampedArray([180, 40, 80, 255, 0, 0, 0, 255, 255, 255, 255, 255, 0, 0, 255, 0])), 'rgb(180, 40, 80)');
  assert.equal(vinylColor(new Uint8ClampedArray([0, 0, 0, 0, 255, 255, 255, 255])), '');
  assert.equal(vinylColor(new Uint8ClampedArray([128, 128, 128, 255])), 'rgb(128, 128, 128)');
});
