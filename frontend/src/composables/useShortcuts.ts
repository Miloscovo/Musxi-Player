import { useCallback, useEffect, useRef, useState } from 'react';
import { native } from '../native/client';
import { emptyShortcuts, shortcutActions, type ShortcutAction, type ShortcutSettings } from '../native/shortcuts';
import { recordedKey, sameBinding } from './shortcutKeys';

function editingControl() { return document.hasFocus() && !!document.activeElement?.closest('input, textarea, select, button, [contenteditable], [role=dialog], [role=menuitem]'); }
export function useShortcuts() {
  const [state, setState] = useState(emptyShortcuts);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [recording, setRecording] = useState<ShortcutAction | null>(null);
  const [ready, setReady] = useState(false);
  const locked = useRef(false);
  const recordRef = useRef(recording); recordRef.current = recording;
  const fail = (e: unknown) => setError(e instanceof Error ? e.message : '快捷键操作失败');
  const execute = useCallback(async (action: ShortcutAction) => {
    try { await native.shortcuts.execute(action); } catch (e) { fail(e); }
  }, []);
  useEffect(() => {
    let active = true;
    native.shortcuts.getState().then(value => { if (active) { setState(value); setLoaded(true); } }).catch(e => { if (active) fail(e); });
    return () => { active = false; };
  }, []);
  useEffect(() => {
    function sync() {
      void native.shortcuts.suspend(!!recordRef.current, editingControl()).then(setState).catch(fail);
    }
    document.addEventListener('focusin', sync); document.addEventListener('focusout', sync);
    window.addEventListener('focus', sync); window.addEventListener('blur', sync);
    return () => {
      document.removeEventListener('focusin', sync); document.removeEventListener('focusout', sync);
      window.removeEventListener('focus', sync); window.removeEventListener('blur', sync);
      void native.shortcuts.suspend(false, false).catch(() => {});
    };
  }, []);
  useEffect(() => {
    function keyboard(event: KeyboardEvent) {
      if (!loaded || recording || busy || event.defaultPrevented || event.repeat || event.isComposing) return;
      const target = event.target as Element;
      if (target.closest('input, textarea, select, [contenteditable], [role=dialog]')) return;
      const binding = recordedKey(event);
      if (!binding || (target.closest('button, [role=menuitem]') && [13,32].includes(binding.key))) return;
      const match = shortcutActions.find(([id]) => sameBinding(binding, state.bindings[id]));
      if (!match) return;
      // Registered global combinations are consumed by Windows; never execute a second time in CEF.
      if (state.global && !state.suspended && !state.failed.includes(match[0])) return;
      event.preventDefault(); void execute(match[0]);
    }
    window.addEventListener('keydown', keyboard);
    return () => window.removeEventListener('keydown', keyboard);
  }, [state, loaded, recording, busy, execute]);
  async function save(settings: ShortcutSettings) {
    if (locked.current) return false;
    locked.current = true; setBusy(true); setError('');
    try { setState(await native.shortcuts.setSettings(settings)); return true; }
    catch (e) { fail(e); try { setState(await native.shortcuts.getState()); } catch { /* Keep last confirmed settings. */ } return false; }
    finally { locked.current = false; setBusy(false); }
  }
  async function begin(action: ShortcutAction) {
    if (locked.current) return;
    setReady(false); setError(''); recordRef.current = action; setRecording(action);
    try { setState(await native.shortcuts.suspend(true, false)); setReady(true); }
    catch (e) { fail(e); recordRef.current = null; setRecording(null); }
  }
  async function cancel() {
    recordRef.current = null; setRecording(null); setReady(false);
    try { setState(await native.shortcuts.suspend(false, editingControl())); } catch (e) { fail(e); }
  }
  return { state, loaded, error, setError, busy, recording, ready, begin, cancel, save, execute };
}
export type ShortcutsController = ReturnType<typeof useShortcuts>;
