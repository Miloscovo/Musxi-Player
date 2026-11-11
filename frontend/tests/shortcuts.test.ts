import { test } from 'node:test';
import assert from 'node:assert/strict';
import { emptyShortcuts, parseShortcuts, shortcutActions } from '../src/native/shortcuts.ts';
import { bindingLabel, recordedKey, sameBinding } from '../src/composables/shortcutKeys.ts';

const event = (key: string, code: string, modifiers = {}) => ({ key, code, ctrlKey: false, altKey: false, shiftKey: false, metaKey: false, isComposing: false, ...modifiers });
test('all shortcuts start unbound and global shortcuts start disabled', () => {
  const defaults = emptyShortcuts();
  assert.equal(defaults.global, false);
  assert.equal(Object.keys(defaults.bindings).length, 12);
  assert.ok(shortcutActions.every(([id]) => defaults.bindings[id] === null));
  assert.deepEqual(parseShortcuts(defaults), defaults);
  assert.equal(bindingLabel(null), '未绑定');
});

test('retired panel and screenshot bindings are discarded without losing other shortcuts', () => {
  const state = emptyShortcuts();
  state.global = true;
  state.bindings.playPause = { key: 32, modifiers: 0 };
  state.bindings.overlayEditing = { key: 75, modifiers: 0 };
  const old = { ...state, bindings: { ...state.bindings, overlayPlayer: { key: 74, modifiers: 0 } } };
  assert.deepEqual(parseShortcuts(old), state);
  assert.deepEqual(parseShortcuts({ ...old, bindings: { ...old.bindings, overlayScreenshot: state.bindings.playPause } }), state);
  assert.equal(Object.keys(old.bindings).length, 13);
  const invalid: Record<string, unknown> = { ...old.bindings }; delete invalid.overlayEditing;
  assert.throws(() => parseShortcuts({ ...old, bindings: invalid }));
});

test('older ten-action settings retain every binding when game shortcuts are added', () => {
  const state = emptyShortcuts();
  state.bindings.playPause = { key: 32, modifiers: 0 };
  const old = { ...state, bindings: Object.fromEntries(shortcutActions.slice(0,10).map(([id]) => [id, state.bindings[id]])) };
  const migrated = parseShortcuts(old);
  assert.deepEqual(migrated.bindings.playPause, { key: 32, modifiers: 0 });
  assert.equal(migrated.bindings.overlayEditing, null);
  assert.equal(Object.keys(old.bindings).length, 10);
  assert.throws(() => parseShortcuts({ ...old, bindings: { ...old.bindings, unknown: null } }));
  const missing = { ...old.bindings };delete missing.playPause;
  assert.throws(() => parseShortcuts({ ...old, bindings: { ...missing, overlayPlayer: null } }));
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

test('CEF native keys remain recordable when code is empty, without accepting modifier-only or reserved keys', () => {
  const space = recordedKey({ ...event(' ', ''), keyCode: 32 });
  assert.deepEqual(space, { key: 32, modifiers: 0 });
  assert.equal(bindingLabel(space), 'Space');
  assert.deepEqual(recordedKey({ ...event(' ', '', { ctrlKey:true }), keyCode:32 }), { key:32, modifiers:2 });
  assert.deepEqual(recordedKey({ ...event('1', ''), keyCode:49 }), { key:49, modifiers:0 });
  assert.deepEqual(recordedKey({ ...event('Enter', ''), keyCode:13 }), { key:13, modifiers:0 });
  assert.equal(recordedKey({ ...event('Control', ''), keyCode:17 }), null);
  assert.equal(recordedKey({ ...event('Escape', ''), keyCode:27 }), null);
  assert.equal(recordedKey({ ...event(' ', '', { isComposing:true }), keyCode:32 }), null);
  assert.equal(recordedKey({ ...event('Unidentified', ''), keyCode:255 }), null);
});
