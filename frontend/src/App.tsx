import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
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
import PlaybackPlatform from './components/PlaybackPlatform';
import LyricsView from './views/LyricsView';
import { playbackTimes, formatPlaybackTime } from './composables/playbackTime';
import { useDesktopLyrics } from './composables/useDesktopLyrics';
import { bindWheelRange } from './composables/wheelRange';
import { useAppUpdates } from './composables/useAppUpdates';
import UpdateNotice from './components/UpdateNotice';
import { useShortcuts } from './composables/useShortcuts';

export default function App() {
  const shell = useWindow(); const library = useLibrary();
  const desktop = useDesktopLyrics(); const updates = useAppUpdates();
  const shortcuts = useShortcuts();
  const [showUpdates, setShowUpdates] = useState(0);
  const currentTrack = library.state?.now;
  const { state, error, loading, busy, connected, pause, resume, seek, setVolume } = usePlayerState();
  const [seekDraft, setSeekDraft] = useState<number | null>(null);
  const [volumeDraft, setVolumeDraft] = useState<number | null>(null);
  const [closeOpen, setCloseOpen] = useState(false);
  const [lyricsOpen, setLyricsOpen] = useState(false);
  const compact = !!shell.state.compact;
  useEffect(() => { if ((lyricsOpen || compact) && shortcuts.recording) void shortcuts.cancel(); }, [lyricsOpen, compact, shortcuts.recording]);
  const playerElement = useRef<HTMLElement>(null);
  const [compactBusy, setCompactBusy] = useState(false);
  async function setCompact(value: boolean) {
    if(compactBusy)return;
    setCompactBusy(true);
    try { await shell.setCompact(value, Math.ceil(playerElement.current?.getBoundingClientRect().height ?? 110) + 16); }
    finally { setCompactBusy(false); }
  }
  const closeLyrics = useCallback(() => setLyricsOpen(false), []);
  const [actionNotice, setActionNotice] = useState({ text: '', token: '0' });
  const showActionNotice = (text: string) => setActionNotice(previous => ({ text, token: String(Number(previous.token) + 1) }));
  const trayButton = useRef<HTMLButtonElement>(null);
  const seekInput = useRef<HTMLInputElement>(null); const volumeInput = useRef<HTMLInputElement>(null);
  const wantsPlay = state?.phase === 'failed' ? false : (state?.requestedPlaying ?? state?.playing ?? false);
  const status = state?.phase === 'failed' ?
    (/default output|output device/i.test(state.error || '') ? '输出设备已变化，重新播放将使用当前设备' : '播放失败，请重试') :
    state?.pending ? (state.phase === 'seeking' ? '正在跳转…' : '正在加载…') :
    !state ? '正在连接播放器' : !state.opened ? '等待一首好歌' : state.playing ? '正在播放' : '已暂停';
  const times = playbackTimes(!!state?.opened, state?.durationMs ?? 0, seekDraft ?? state?.positionMs ?? 0);
  const [showRemaining, setShowRemaining] = useState(false);
  const [hoverSeek, setHoverSeek] = useState<{ x: number; ms: number } | null>(null);
  const hasState = !!state;
  const wheelPlayback = useRef(state); wheelPlayback.current = state;
  useEffect(() => {
    const input = seekInput.current;
    if (input && connected) return bindWheelRange(input, seek, setSeekDraft,
      () => wheelPlayback.current?.pending ? null : wheelPlayback.current?.positionMs ?? null, 5000, 1000);
  }, [hasState, connected, state?.trackId, seek]);
  useEffect(() => {
    const input = volumeInput.current;
    if (input && connected) return bindWheelRange(input, setVolume, setVolumeDraft,
      () => wheelPlayback.current?.volumePercent ?? null, 5, 1);
  }, [hasState, connected, setVolume]);
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
    window: { text: shell.error || desktop.error || shortcuts.error, error: true },
    connection: { text: connected ? '' : '正在连接…' },
    updates: updates.message,
  }), [updates.message, library.error, operation?.status, operation?.error, operation?.id, notice, error, playbackMessage, state?.phase, shell.error, desktop.error, shortcuts.error, connected, actionNotice]);
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
  return <main className={`app-shell${compact ? ' compact-player' : ''}`} onContextMenu={event => {
    if ((event.target as Element).closest('button')) event.preventDefault();
  }}>
    {shell.state.enabled && !compact && <div className="window-bar"><div className="window-buttons">
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
            <Button variant="ghost" className="close-minimize" ref={trayButton} onClick={async () => { if (await shell.minimizeToTray()) setCloseOpen(false); }}>最小化</Button>
          </div>
        </DialogContent>
      </Dialog>
    </div></div>}
    <div className="app-body" hidden={compact}>
      <div className="library-layer" inert={lyricsOpen}><LibraryView library={library} theme={shell.theme} appearance={shell.appearance} onAppearance={shell.setAppearance} transparency={shell.transparency} onTransparency={shell.setTransparency} updates={updates} showUpdates={showUpdates} shortcuts={shortcuts} /></div>
      {lyricsOpen && <LyricsView trackId={state?.opened ? state.trackId : ''} now={currentTrack} positionMs={state?.positionMs ?? 0}
        canSeek={connected && !!state?.opened && !!state.durationMs} onSeek={positionMs => void seek(positionMs)} onClose={closeLyrics} />}
    </div>
    <section ref={playerElement} className="player" aria-label="播放器" aria-busy={loading} onClickCapture={event => {
      // Menus and lyrics need the full viewport rather than a clipped player strip.
      if(compact && (event.target as Element).closest('[aria-haspopup],.lyrics-toggle'))void setCompact(false);
    }}>
      {shell.state.enabled && <Button variant="unstyled" className="player-rail" data-state={compact ? 'collapsed' : 'expanded'} disabled={compactBusy}
        aria-label={compact ? '展开主界面' : '收起主界面'} aria-expanded={!compact}
        title={compact ? '展开主界面' : '收起主界面'} onClick={() => void setCompact(!compact)} />}
      <div className="player-progress">
        <span className="player-time elapsed" aria-label="已播放">{times.elapsed}</span>
        <div className="seek-track">
          {state && <input ref={seekInput} className="seek-bar" type="range" min="0" max={Math.max(0, state.durationMs - 1)}
            value={seekDraft ?? state.positionMs} aria-valuetext={formatPlaybackTime(seekDraft ?? state.positionMs)}
            style={{ '--progress': `${state.durationMs ? (seekDraft ?? state.positionMs) / state.durationMs * 100 : 0}%` } as CSSProperties}
            disabled={!connected || !state.opened || !state.durationMs}
            onPointerMove={event => {
              // Hover bubble: time under the pointer (or the dragged value while seeking).
              const box = event.currentTarget.getBoundingClientRect(); if (!state.durationMs || !box.width) return;
              const ratio = Math.min(1, Math.max(0, (event.clientX - box.left) / box.width));
              setHoverSeek({ x: ratio * 100, ms: seekDraft ?? ratio * state.durationMs });
            }}
            onPointerLeave={() => setHoverSeek(null)}
            onInput={event => setSeekDraft(Number(event.currentTarget.value))} aria-label="歌曲进度" />}
          {hoverSeek && state?.durationMs ? <span className="seek-bubble" aria-hidden="true"
            style={{ left: `${seekDraft !== null ? seekDraft / state.durationMs * 100 : hoverSeek.x}%` }}>
            {formatPlaybackTime(seekDraft ?? hoverSeek.ms)}</span> : null}
        </div>
        <Button variant="unstyled" className="player-time total" onClick={() => setShowRemaining(value => !value)}
          title={showRemaining ? '点击显示总时长' : '点击显示剩余时长'} aria-label={showRemaining ? `剩余时长 ${times.remaining}` : `总时长 ${times.total}`}>
          {showRemaining ? `-${times.remaining}` : times.total}</Button>
      </div>
      <div className="player-track" inert={lyricsOpen && !compact}><Button variant="unstyled" className="lyrics-toggle" aria-expanded={lyricsOpen}
          aria-label={lyricsOpen ? '收起歌词页' : '展开歌词页'} title={lyricsOpen ? '收起歌词' : '查看歌词'} onClick={() => setLyricsOpen(open => !open)}>
          <CoverImage url={currentTrack?.cover || ''} />
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d={lyricsOpen ? 'm6 9 6 6 6-6' : 'm6 15 6-6 6 6'}/></svg>
        </Button>
        <div className="track"><div className="playback-title"><h2 id="playback" title={currentTrack?.name}>{currentTrack?.name || '尚未选择歌曲'}</h2>
          <PlaybackPlatform library={library} currentId={state?.opened ? state.trackId : ''}/></div>
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
      <div className="player-side">{desktop.state.enabled && <Button variant="unstyled" className="desktop-lyrics-toggle" aria-label="桌面歌词"
        aria-pressed={desktop.state.visible} title={desktop.state.visible ? '关闭桌面歌词' : '打开桌面歌词'} onClick={() => void desktop.setVisible(!desktop.state.visible)}>
        <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="2.8" y="4.5" width="18.4" height="15" rx="4"/><text x="12" y="16.1" textAnchor="middle">词</text></svg>
      </Button>}{state && <div className="volume-control">
        <Button variant="unstyled" className="icon volume-button" aria-label={state.volumePercent===0 ? '取消静音' : '静音'} aria-pressed={state.volumePercent===0} disabled={busy || !connected}
          onClick={()=>void shortcuts.execute('mute')}>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 9h4l5-4v14l-5-4H3z" fill="currentColor"/>
          <path d={state.volumePercent===0 ? 'm16 9 6 6m0-6-6 6' : 'M16 8a6 6 0 0 1 0 8m3-11a10 10 0 0 1 0 14'} fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/></svg>
        </Button>
        <input ref={volumeInput} type="range" min="0" max="100" value={volumeDraft ?? state.volumePercent}
          style={{ '--volume': `${volumeDraft ?? state.volumePercent}%` } as CSSProperties}
          disabled={!connected} onInput={event => setVolumeDraft(Number(event.currentTarget.value))} aria-label="音量" />
        <span id="volume" className="sr-only">{state.volumePercent}%</span>
      </div>}
        <QueuePanel library={library} />
      </div>
      {!compact && !lyricsOpen && <UpdateNotice updates={updates} onDetails={() => setShowUpdates(n => n + 1)} />}
      <MessageAlerts messages={messages} />
    </section>
  </main>;
}
