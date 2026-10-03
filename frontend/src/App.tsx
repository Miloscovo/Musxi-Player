import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import { useWindow } from './composables/useWindow';
import CoverImage from './components/CoverImage';
import { usePlayerState } from './composables/usePlayerState';
import LibraryView from './views/LibraryView';
import { useLibrary } from './composables/useLibrary';
import { Button } from './components/ui/button';
import { Dialog, DialogTrigger, DialogContent, DialogTitle, DialogDescription } from './components/ui/dialog';
import MessageAlerts from './components/MessageAlerts';
import QueuePanel from './components/QueuePanel';
import PlayerActions from './components/PlayerActions';

export default function App() {
  const shell = useWindow(); const library = useLibrary();
  const currentTrack = library.state?.now;
  const { state, error, loading, busy, connected, pause, resume, seek, setVolume } = usePlayerState();
  const [seekDraft, setSeekDraft] = useState<number | null>(null);
  const [volumeDraft, setVolumeDraft] = useState<number | null>(null);
  const [closeOpen, setCloseOpen] = useState(false);
  const [actionNotice, setActionNotice] = useState({ text: '', token: '0' });
  const showActionNotice = (text: string) => setActionNotice(previous => ({ text, token: String(Number(previous.token) + 1) }));
  const trayButton = useRef<HTMLButtonElement>(null);
  const seekInput = useRef<HTMLInputElement>(null); const volumeInput = useRef<HTMLInputElement>(null);
  const wantsPlay = state?.phase === 'failed' ? false : (state?.requestedPlaying ?? state?.playing ?? false);
  const status = state?.phase === 'failed' ?
    (/default output|output device/i.test(state.error || '') ? '输出设备已变化，重新播放将使用当前设备' : '播放失败，请重试') :
    state?.pending ? (state.phase === 'seeking' ? '正在跳转…' : '正在加载…') :
    !state ? '正在连接播放器' : !state.opened ? '等待一首好歌' : state.playing ? '正在播放' : '已暂停';
  const time = (ms: number) => `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`;
  const hasState = !!state;
  const operation = library.state?.operation;
  const notice = library.state?.notice || '';
  const playbackMessage = state?.phase === 'failed' ? status : '';
  const messages = useMemo(() => ({
    library: { text: library.error, error: true },
    operation: { text: operation?.status === 'failed' ? operation.error : '', error: true, token: operation?.id },
    notice: { text: notice },
    action: actionNotice,
    player: { text: error, error: true },
    playback: { text: playbackMessage, error: state?.phase === 'failed' },
    window: { text: shell.error, error: true },
    connection: { text: connected ? '' : '正在连接…' },
  }), [library.error, operation?.status, operation?.error, operation?.id, notice, error, playbackMessage, state?.phase, shell.error, connected, actionNotice]);
  useEffect(() => {
    const seekElement = seekInput.current; const volumeElement = volumeInput.current;
    async function seekChanged() {
      if (!seekElement) return;
      try { await seek(Number(seekElement.value)); } finally { setSeekDraft(null); }
    }
    async function volumeChanged() {
      if (!volumeElement) return;
      try { await setVolume(Number(volumeElement.value)); } finally { setVolumeDraft(null); }
    }
    // Native change fires on commit, unlike React onChange for range inputs.
    seekElement?.addEventListener('change', seekChanged);
    volumeElement?.addEventListener('change', volumeChanged);
    return () => {
      seekElement?.removeEventListener('change', seekChanged);
      volumeElement?.removeEventListener('change', volumeChanged);
    };
  }, [hasState, seek, setVolume]);
  useEffect(() => {
    function keyboard(event: KeyboardEvent) {
      if (event.ctrlKey || event.altKey || event.metaKey || event.repeat) return;
      const target = event.target as HTMLElement;
      if (target.closest('input, textarea, select, button, [contenteditable], [role=dialog]')) return;
      if (!state?.opened || !connected) return;
      if (event.code === 'Space') { event.preventDefault(); void (wantsPlay ? pause() : resume()); }
      if (event.code === 'ArrowLeft' || event.code === 'ArrowRight') {
        event.preventDefault(); void seek(Math.max(0, state.positionMs + (event.code === 'ArrowLeft' ? -5000 : 5000)));
      }
    }
    window.addEventListener('keydown', keyboard);
    return () => window.removeEventListener('keydown', keyboard);
  }, [state, connected, wantsPlay, pause, resume, seek]);

  return <main className="app-shell" onContextMenu={event => {
    if ((event.target as Element).closest('button')) event.preventDefault();
  }}>
    {shell.state.enabled && <div className="window-bar"><div className="window-buttons">
      <Button variant="unstyled" aria-label="最小化" onClick={shell.minimize}>─</Button>
      <Button variant="unstyled" className="window-maximize" aria-label={shell.state.maximized ? '还原' : '最大化'} onClick={shell.maximize}>
        <svg width="12" height="12" viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round">
          {shell.state.maximized ? <><path d="M5 5V2h9v9h-3"/><rect x="2" y="5" width="9" height="9"/></> : <rect x="2.5" y="2.5" width="11" height="11"/>}
        </svg>
      </Button>
      <Dialog open={closeOpen} onOpenChange={setCloseOpen}>
        <DialogTrigger asChild><Button variant="unstyled" className="window-close" aria-label="关闭">×</Button></DialogTrigger>
        <DialogContent onOpenAutoFocus={event => { event.preventDefault(); trayButton.current?.focus(); }}>
          <DialogTitle>是否把 Musxi Player 最小化到托盘？</DialogTitle>
          <DialogDescription>最小化后，歌曲会继续在后台播放。</DialogDescription>
          <div className="close-dialog-actions">
            <Button variant="secondary" onClick={shell.close}>退出</Button>
            <Button ref={trayButton} onClick={async () => { if (await shell.minimizeToTray()) setCloseOpen(false); }}>最小化</Button>
          </div>
        </DialogContent>
      </Dialog>
    </div></div>}
    <LibraryView library={library} theme={shell.theme} onTheme={shell.setTheme} transparency={shell.transparency} onTransparency={shell.setTransparency} />
    <section className="player" aria-label="播放器" aria-busy={loading}>
      {state && <input ref={seekInput} className="seek-bar" type="range" min="0" max={Math.max(0, state.durationMs - 1)}
        value={seekDraft ?? state.positionMs} title={time(seekDraft ?? state.positionMs)}
        style={{ '--progress': `${state.durationMs ? (seekDraft ?? state.positionMs) / state.durationMs * 100 : 0}%` } as CSSProperties}
        disabled={!connected || !state.opened || !state.durationMs}
        onInput={event => setSeekDraft(Number(event.currentTarget.value))} aria-label="歌曲进度" />}
      <div className="player-track"><CoverImage url={currentTrack?.cover || ''} />
        <div className="track"><h2 id="playback" title={currentTrack?.name}>{currentTrack?.name || '尚未选择歌曲'}</h2>
          <p>{currentTrack?.artist || status}</p></div>
      </div>
      {state && <PlayerActions library={library} currentId={state.opened ? state.trackId : ''} onNotice={showActionNotice}>
        <Button variant="unstyled" aria-label="上一首" onClick={() => library.skip(-1)}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6.5 5.5v13"/><path d="M18.5 5.5v13L8 12z"/></svg></Button>
        <Button variant="unstyled" className="play-button" aria-label={wantsPlay ? '暂停' : '播放'} disabled={!connected || !state.opened} onClick={() => wantsPlay ? pause() : resume()}>
          {wantsPlay ? <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="7" y="5" width="3.5" height="14" rx="1.2"/><rect x="13.5" y="5" width="3.5" height="14" rx="1.2"/></svg> :
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8.5 5.5v13L19 12z"/></svg>}
        </Button>
        <Button variant="unstyled" aria-label="下一首" onClick={() => library.skip(1)}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M17.5 5.5v13"/><path d="M5.5 5.5v13L16 12z"/></svg></Button>
      </PlayerActions>}
      <div className="player-side">{state && <label className="volume-control">
        <svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M3 9h4l5-4v14l-5-4H3z" fill="currentColor"/>
          {!!state.volumePercent && <path d="M16 8a6 6 0 0 1 0 8m3-11a10 10 0 0 1 0 14" fill="none" stroke="currentColor" strokeWidth="1.5"/>}</svg>
        <input ref={volumeInput} type="range" min="0" max="100" value={volumeDraft ?? state.volumePercent}
          style={{ '--volume': `${volumeDraft ?? state.volumePercent}%` } as CSSProperties}
          disabled={busy || !connected} onInput={event => setVolumeDraft(Number(event.currentTarget.value))} aria-label="音量" />
        <span id="volume" className="sr-only">{state.volumePercent}%</span>
      </label>}
        <QueuePanel library={library} />
      </div>
      <MessageAlerts messages={messages} />
    </section>
  </main>;
}
