import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { readPlayerStyle, playerStyleKey } from '../src/composables/playerStyle.ts';

test('player style defaults to vinyl and restores valid selections', () => {
  for (const value of [null, '', 'invalid', 'vinyl']) assert.equal(readPlayerStyle({ getItem: () => value }), 'vinyl');
  for (const value of ['square', 'none']) assert.equal(readPlayerStyle({ getItem: key => key === playerStyleKey ? value : null }), value);
});
