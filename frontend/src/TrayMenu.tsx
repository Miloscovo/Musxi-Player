import { useEffect, useRef, useState } from 'react';
import { native } from './native/client';
import { ContextMenu, ContextMenuTrigger, ContextMenuContent, ContextMenuItem } from './components/ui/context-menu';
import { useDesktopLyrics } from './composables/useDesktopLyrics';

export default function TrayMenu() {
  const trigger = useRef<HTMLDivElement>(null);
  const [error, setError] = useState('');
  const desktop = useDesktopLyrics();
  useEffect(() => {
    const saved = localStorage.getItem('musxi-theme');
    document.documentElement.dataset.theme = saved === 'dark' || saved === 'glass' ? 'dark' : 'light';
    document.documentElement.style.setProperty('--background-opacity', '1');
    document.body.classList.add('tray-menu-page');
    trigger.current?.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, button: 2, clientX: 0, clientY: 0 }));
    return () => document.body.classList.remove('tray-menu-page');
  }, []);
  async function act(action: () => Promise<unknown>) {
    try { await action(); await native.window.dismissTrayMenu(); }
    catch (e) { setError(e instanceof Error ? e.message : '托盘操作失败'); }
  }
  return <ContextMenu onOpenChange={open => { if (!open) void native.window.dismissTrayMenu().catch(() => {}); }}>
    <ContextMenuTrigger asChild><div ref={trigger} className="tray-menu-trigger" /></ContextMenuTrigger>
    <ContextMenuContent className="tray-context-menu" collisionPadding={0} onCloseAutoFocus={event => event.preventDefault()}>
      <ContextMenuItem onSelect={() => void act(native.window.toggleVisibility)}>显示/隐藏</ContextMenuItem>
      {desktop.state.enabled && <><ContextMenuItem onSelect={() => void act(() => native.desktopLyrics.setVisible(!desktop.state.visible))}>
        {desktop.state.visible ? '关闭桌面歌词' : '打开桌面歌词'}</ContextMenuItem>
        <ContextMenuItem disabled={!desktop.state.visible} onSelect={() => void act(() => native.desktopLyrics.setLocked(!desktop.state.locked))}>
          {desktop.state.locked ? '解锁桌面歌词' : '锁定桌面歌词'}</ContextMenuItem></>}
      <ContextMenuItem onSelect={() => void act(native.window.close)}>退出</ContextMenuItem>
      {error && <div role="alert">{error}</div>}
    </ContextMenuContent>
  </ContextMenu>;
}
