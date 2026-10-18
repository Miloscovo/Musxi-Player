import { useEffect, useRef, useState } from 'react';
import { Button } from './ui/button';
import { Dialog, DialogContent, DialogTitle, DialogDescription } from './ui/dialog';
import { emptyShortcuts, shortcutActions, type KeyBinding } from '../native/shortcuts';
import { bindingLabel, recordedKey, sameBinding } from '../composables/shortcutKeys';
import type { ShortcutsController } from '../composables/useShortcuts';

export default function ShortcutSettings({ shortcuts: keys }: { shortcuts: ShortcutsController }) {
  const [confirm, setConfirm] = useState(false);
  const pending = useRef<{ code: string; binding: KeyBinding } | null>(null);
  const cancel = useRef(keys.cancel); cancel.current = keys.cancel;
  useEffect(() => () => { void cancel.current(); }, []);
  useEffect(() => {
    if (!keys.recording) return;
    pending.current = null;
    function down(event: KeyboardEvent) {
      event.preventDefault(); event.stopImmediatePropagation();
      if (event.key === 'Escape') { void keys.cancel(); return; }
      if (!keys.ready || event.repeat || pending.current) return;
      const binding = recordedKey(event);
      if (binding) pending.current = { code: event.code, binding };
    }
    async function up(event: KeyboardEvent) {
      event.preventDefault(); event.stopImmediatePropagation();
      const value = pending.current;
      if (!value || event.code !== value.code || !keys.recording) return;
      pending.current = null;
      const conflict = shortcutActions.find(([id]) => id !== keys.recording && sameBinding(keys.state.bindings[id], value.binding));
      if (conflict) { keys.setError(`此快捷键已用于“${conflict[1]}”，请重新设置。`); await keys.cancel(); return; }
      await keys.save({ ...keys.state, bindings: { ...keys.state.bindings, [keys.recording]: value.binding } });
      await keys.cancel();
    }
    const blur = () => { void keys.cancel(); };
    window.addEventListener('keydown', down, true); window.addEventListener('keyup', up, true); window.addEventListener('blur', blur);
    return () => { window.removeEventListener('keydown', down, true); window.removeEventListener('keyup', up, true); window.removeEventListener('blur', blur); };
  }, [keys.recording, keys.ready, keys.state]);
  return <section className="settings-content shortcut-settings" aria-label="快捷键设置">
    <div className="appearance-setting-row">
      <h3 id="global-shortcuts-label">启用全局快捷键</h3>
      <Button variant="unstyled" className="appearance-switch" role="switch" aria-checked={keys.state.global} aria-labelledby="global-shortcuts-label"
        disabled={!keys.loaded || keys.busy || !!keys.recording} onClick={() => void keys.save({ ...keys.state, global: !keys.state.global })}><span/></Button>
    </div>
    {keys.error && <p className="playback-settings-error" role="alert">{keys.error}</p>}
    {!!keys.state.failed.length && <p className="playback-settings-error" role="status">以下全局快捷键不可用，可能已被其他程序占用：{keys.state.failed.map(id => shortcutActions.find(([action]) => action === id)?.[1]).join('、')}。可重新绑定，窗口内仍可使用。</p>}
    {!keys.loaded && !keys.error && <p className="playback-settings-loading">正在读取快捷键…</p>}
    <div className="shortcut-groups">
      {[shortcutActions.slice(0,5), shortcutActions.slice(5,8), shortcutActions.slice(8)].map((group, index) => <div className="shortcut-group" key={index}>
        {group.map(([id, label]) => <div className="shortcut-row" key={id}>
          <span id={`shortcut-${id}`}>{label}</span>
          <div className="shortcut-binding-actions">
            <Button variant="secondary" className="shortcut-binding" aria-labelledby={`shortcut-${id} shortcut-value-${id}`} aria-pressed={keys.recording === id}
              disabled={!keys.loaded || keys.busy || (!!keys.recording && keys.recording !== id)} onClick={() => void keys.begin(id)}>
              <span id={`shortcut-value-${id}`}>{keys.recording === id ? keys.ready ? '请按下要绑定快捷键' : '正在准备录制…' : bindingLabel(keys.state.bindings[id])}</span>
            </Button>
            <Button variant="secondary" size="sm" disabled={keys.busy || !!keys.recording || !keys.state.bindings[id]} aria-label={`清除${label}快捷键`}
              onClick={() => void keys.save({ ...keys.state, bindings: { ...keys.state.bindings, [id]: null } })}>清除</Button>
          </div>
        </div>)}
      </div>)}
    </div>
    {keys.recording && <p className="shortcut-hint" role="status">按下组合键并松开完成录制，按 Escape 取消。<Button variant="secondary" size="sm" onClick={() => void keys.cancel()}>取消录制</Button></p>}
    <Button variant="secondary" disabled={!keys.loaded || keys.busy || !!keys.recording} onClick={() => setConfirm(true)}>恢复默认</Button>
    <Dialog open={confirm} onOpenChange={setConfirm}><DialogContent>
      <DialogTitle>恢复默认快捷键？</DialogTitle><DialogDescription>将清空所有绑定并关闭全局快捷键。</DialogDescription>
      {keys.error && <p className="playback-settings-error" role="alert">{keys.error}</p>}
      <div className="close-dialog-actions"><Button variant="secondary" onClick={() => setConfirm(false)}>取消</Button>
        <Button variant="secondary" disabled={keys.busy} onClick={async () => { if (await keys.save(emptyShortcuts())) setConfirm(false); }}>确认恢复</Button></div>
    </DialogContent></Dialog>
  </section>;
}
