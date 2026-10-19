import { validShortcutKey, type KeyBinding } from '../native/shortcuts.ts';

const keyCodes: Record<string, number> = { Backspace:8, Tab:9, Enter:13, Space:32, PageUp:33, PageDown:34, End:35, Home:36,
  ArrowLeft:37, ArrowUp:38, ArrowRight:39, ArrowDown:40, Insert:45, Delete:46,
  NumpadMultiply:106, NumpadAdd:107, NumpadSubtract:109, NumpadDecimal:110, NumpadDivide:111,
  Semicolon:186, Equal:187, Comma:188, Minus:189, Period:190, Slash:191, Backquote:192, BracketLeft:219, Backslash:220, BracketRight:221, Quote:222 };
const names: Record<number,string> = { 8:'Backspace',9:'Tab',13:'Enter',32:'Space',33:'PageUp',34:'PageDown',35:'End',36:'Home',37:'←',38:'↑',39:'→',40:'↓',45:'Insert',46:'Delete',106:'Num *',107:'Num +',108:'Num Separator',109:'Num −',110:'Num .',111:'Num /',186:';',187:'=',188:',',189:'−',190:'.',191:'/',192:'`',219:'[',220:'\\',221:']',222:"'" };
export function recordedKey(event: Pick<KeyboardEvent,'code'|'key'|'ctrlKey'|'altKey'|'shiftKey'|'metaKey'|'isComposing'> & { keyCode?: number }): KeyBinding | null {
  if (event.isComposing) return null;
  // CEF's windowless native input can omit code while retaining the Windows virtual key.
  const nativeKey = event.keyCode ?? 0;
  let key = (event.code.startsWith('Numpad') && keyCodes[event.key]) || keyCodes[event.code] || (validShortcutKey(nativeKey) ? nativeKey : undefined);
  if (/^[a-z]$/i.test(event.key)) key = event.key.toUpperCase().charCodeAt(0);
  else if (/^Digit[0-9]$/.test(event.code)) key = event.code.charCodeAt(5);
  else if (/^Numpad[0-9]$/.test(event.code) && /^[0-9]$/.test(event.key)) key = 96 + Number(event.code.slice(6));
  else if (/^F([1-9]|1[0-9]|2[0-4])$/.test(event.key)) key = 111 + Number(event.key.slice(1));
  if (!key) return null;
  return { key, modifiers: (event.altKey ? 1 : 0) | (event.ctrlKey ? 2 : 0) | (event.shiftKey ? 4 : 0) | (event.metaKey ? 8 : 0) };
}
export function bindingLabel(binding: KeyBinding | null) {
  if (!binding) return '未绑定';
  const { key, modifiers } = binding;
  return [...(modifiers & 2 ? ['Ctrl'] : []), ...(modifiers & 1 ? ['Alt'] : []), ...(modifiers & 4 ? ['Shift'] : []), ...(modifiers & 8 ? ['Win'] : []),
    names[key] ?? (key >= 112 && key <= 135 ? `F${key - 111}` : key >= 96 && key <= 105 ? `Num ${key - 96}` : String.fromCharCode(key))].join(' + ');
}
export function sameBinding(a: KeyBinding | null, b: KeyBinding | null) { return !!a && !!b && a.key === b.key && a.modifiers === b.modifiers; }
