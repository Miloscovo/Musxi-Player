import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readAppearance, resolveTheme } from '../src/composables/appearance.ts';

test('appearance migrates legacy transparent themes and retains explicit preferences', () => {
  const read = (values: Record<string, string>) => readAppearance({ getItem: key => values[key] ?? null });
  assert.deepEqual(read({}), { mode: 'light', transparent: false, tone: 'green' });
  assert.deepEqual(read({ 'musxi-theme': 'glass' }), { mode: 'dark', transparent: true, tone: 'green' });
  assert.deepEqual(read({ 'musxi-theme': 'glass-light' }), { mode: 'light', transparent: true, tone: 'green' });
  assert.deepEqual(read({ 'musxi-theme': 'dark' }), { mode: 'dark', transparent: false, tone: 'green' });
  assert.deepEqual(read({ 'musxi-theme': 'glass', 'musxi-appearance-mode': 'system', 'musxi-transparent': 'false' }), { mode: 'system', transparent: false, tone: 'green' });
  assert.deepEqual(read({ 'musxi-theme': 'glass-light', 'musxi-appearance-mode': 'invalid' }), { mode: 'light', transparent: true, tone: 'green' });
});

test('system appearance responds to color changes while manual modes stay fixed, with independent transparency', () => {
  for (const transparent of [false, true]) {
    const light = transparent ? 'glass-light' : 'light';
    const dark = transparent ? 'glass' : 'dark';
    assert.equal(resolveTheme({ mode: 'system', transparent }, false), light);
    assert.equal(resolveTheme({ mode: 'system', transparent }, true), dark);
    assert.equal(resolveTheme({ mode: 'light', transparent }, true), light);
    assert.equal(resolveTheme({ mode: 'dark', transparent }, false), dark);
  }
});

test('appearance restores supported tones and falls back to green for invalid preferences', () => {
  for (const tone of ['green', 'blue', 'pink', 'red', 'invalid']) {
    const appearance = readAppearance({ getItem: key => key === 'musxi-tone' ? tone : null });
    assert.equal(appearance.tone, tone === 'invalid' ? 'green' : tone);
    assert.equal(resolveTheme(appearance, true), 'light');
  }
});
