import { request, type CefTransport } from './transport.ts';
import { NativeError } from './types.ts';

export const shortcutActions = [
  ['playPause', '播放 / 暂停'], ['previous', '上一首'], ['next', '下一首'],
  ['forward', '歌曲快进'], ['backward', '歌曲快退'], ['volumeUp', '音量增加'],
  ['volumeDown', '音量降低'], ['mute', '静音 / 取消静音'],
  ['playbackOrder', '切换播放模式'], ['visibility', '显示 / 隐藏播放器窗口'],
] as const;
export type ShortcutAction = typeof shortcutActions[number][0];
export interface KeyBinding { key: number; modifiers: number }
export interface ShortcutSettings { global: boolean; bindings: Record<ShortcutAction, KeyBinding | null> }
export interface ShortcutState extends ShortcutSettings { failed: ShortcutAction[]; suspended: boolean }
export const emptyShortcuts = (): ShortcutState => ({ global: false, bindings: Object.fromEntries(shortcutActions.map(([id]) => [id, null])) as ShortcutSettings['bindings'], failed: [], suspended: false });
export function validShortcutKey(key: number) {
  return (key >= 0x30 && key <= 0x39) || (key >= 0x41 && key <= 0x5a) || (key >= 0x60 && key <= 0x87)
    || [8,9,13,32,33,34,35,36,37,38,39,40,45,46].includes(key) || (key >= 0xba && key <= 0xc0) || (key >= 0xdb && key <= 0xde);
}
export function parseShortcuts(value: unknown): ShortcutState {
  const state = value as ShortcutState | null;
  if (!state || typeof state.global !== 'boolean' || typeof state.suspended !== 'boolean' || !state.bindings || Object.keys(state.bindings).length !== shortcutActions.length
    || !Array.isArray(state.failed) || state.failed.some(id => !shortcutActions.some(([action]) => action === id))) throw new NativeError(502, '无效快捷键配置');
  const used = new Set<string>();
  for (const [id] of shortcutActions) {
    const binding = state.bindings[id];
    if (binding === null) continue;
    if (!binding || !Number.isInteger(binding.key) || !validShortcutKey(binding.key) || !Number.isInteger(binding.modifiers) || binding.modifiers < 0 || binding.modifiers > 15) throw new NativeError(502, '无效快捷键');
    const key = `${binding.modifiers}:${binding.key}`;
    if (used.has(key)) throw new NativeError(502, '重复快捷键');
    used.add(key);
  }
  return state;
}
export function createShortcutsClient(host: CefTransport) {
  return Object.freeze({
    getState: async () => parseShortcuts(await request(host, 'shortcuts.getState', {})),
    setSettings: async (settings: ShortcutSettings) => parseShortcuts(await request(host, 'shortcuts.setSettings', { global: settings.global, bindings: settings.bindings })),
    suspend: async (recording: boolean, editing: boolean) => parseShortcuts(await request(host, 'shortcuts.suspend', { recording, editing })),
    execute: async (action: ShortcutAction) => { await request(host, 'shortcuts.execute', { action }); },
  });
}
