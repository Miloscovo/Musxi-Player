import { test } from 'node:test';
import assert from 'node:assert/strict';
import { emptyShortcuts, parseShortcuts, shortcutActions } from '../src/native/shortcuts.ts';
import { bindingLabel, recordedKey, sameBinding } from '../src/composables/shortcutKeys.ts';

const event = (key: string, code: string, modifiers = {}) => ({ key, code, ctrlKey: false, altKey: false, shiftKey: false, metaKey: false, isComposing: false, ...modifiers });
test('all shortcuts start unbound and global shortcuts start disabled', () => {
  const defaults = emptyShortcuts();
  assert.equal(defaults.global, false);
  assert.equal(Object.keys(defaults.bindings).length, 10);
  assert.ok(shortcutActions.every(([id]) => defaults.bindings[id] === null));
  assert.deepEqual(parseShortcuts(defaults), defaults);
  assert.equal(bindingLabel(null), '未绑定');
});
test('record and display canonical key combinations without accepting modifiers or composition', () => {
  const combination = recordedKey(event('K', 'KeyK', { ctrlKey: true, altKey: true, shiftKey: true }));
  assert.deepEqual(combination, { key: 75, modifiers: 7 });
  assert.equal(bindingLabel(combination), 'Ctrl + Alt + Shift + K');
  assert.deepEqual(recordedKey(event('!', 'Digit1', { shiftKey: true })), { key: 49, modifiers: 4 });
  assert.equal(bindingLabel(recordedKey(event('F24', 'F24'))), 'F24');
  assert.equal(bindingLabel(recordedKey(event('ArrowLeft', 'ArrowLeft'))), '←');
  assert.deepEqual(recordedKey(event('1', 'Numpad1')), { key: 97, modifiers: 0 });
  assert.deepEqual(recordedKey(event('End', 'Numpad1')), { key: 35, modifiers: 0 });
  assert.deepEqual(recordedKey(event('Enter', 'NumpadEnter')), { key: 13, modifiers: 0 });
  assert.equal(recordedKey(event('Control', 'ControlLeft', { ctrlKey: true })), null);
  assert.equal(recordedKey(event('Escape', 'Escape')), null);
  assert.equal(recordedKey(event('k', 'KeyK', { isComposing: true })), null);
  assert.ok(sameBinding(combination, recordedKey(event('k', 'KeyK', { ctrlKey: true, altKey: true, shiftKey: true }))));
});
test('invalid native bindings and duplicates are rejected before use', () => {
  const state = emptyShortcuts();state.bindings.mute = { key: 65, modifiers: 2 };
  assert.doesNotThrow(() => parseShortcuts(state));
  state.bindings.next = { key: 65, modifiers: 2 };
  assert.throws(() => parseShortcuts(state), /重复/);
  state.bindings.next = null;state.bindings.mute = { key: 16, modifiers: 0 };
  assert.throws(() => parseShortcuts(state));
  state.bindings.mute = { key: 65, modifiers: 16 };assert.throws(() => parseShortcuts(state));
});
