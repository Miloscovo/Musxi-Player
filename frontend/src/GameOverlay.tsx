import { memo, useEffect, useLayoutEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { animate } from 'motion/react';
import CoverImage from './components/CoverImage';
import VinylCover from './components/VinylCover';
import { Button } from './components/ui/button';
import { resolveTheme } from './composables/appearance';
import { readVisualSettings } from './composables/appearancePreview';
import { gameLyricsStyleKey, readGameLyricsStyle, type GameLyricsStyle } from './composables/gameLyricsStyle';
import { gameTrackTransparencyKey, readGameTrackTransparency } from './composables/gameTrackTransparency';
import { activeLyricLine, lyricScrollTransition } from './composables/lyrics';
import { playbackTimes } from './composables/playbackTime';
import { useDesktopTrack } from './composables/useDesktopTrack';
import { useGameOverlay } from './composables/useGameOverlay';
import { usePlayerState } from './composables/usePlayerState';
import { usePlayerStyle } from './composables/usePlayerStyle';
import { sampleCoverColor } from './composables/vinylColor';
import { readLyricsDisplay, lyricsDisplayKey } from './composables/lyricsDisplay';
import { readLyricsTranslation } from './composables/lyricsTranslation';
import { native } from './native/client';
import type { LibraryState } from './native/library';
import type { PlayerState } from './native/types';
import './game-overlay.css';

function useOverlayAppearance(kind: 'lyrics' | 'track') {
  const [display, setDisplay] = useState(() => readLyricsDisplay(localStorage));
  const [translation, setTranslation] = useState(() => readLyricsTranslation(localStorage));
  const [style, setStyle] = useState(() => readGameLyricsStyle(localStorage));
  const [trackTransparency, setTrackTransparency] = useState(() => kind === 'track' ? readGameTrackTransparency(localStorage) : 0);
  useEffect(() => {
    const system = matchMedia('(prefers-color-scheme: dark)');
    const sync = () => {
      const { appearance, transparency } = readVisualSettings(localStorage);
      document.documentElement.dataset.theme = resolveTheme(appearance, system.matches);
      document.documentElement.dataset.tone = appearance.tone;
      document.documentElement.style.setProperty('--background-opacity', String(appearance.transparent ? 1 - transparency / 100 : 1));
      setDisplay(readLyricsDisplay(localStorage)); setTranslation(readLyricsTranslation(localStorage));
      setStyle(readGameLyricsStyle(localStorage));
      if (kind === 'track') setTrackTransparency(readGameTrackTransparency(localStorage));
    };
    sync(); window.addEventListener('storage', sync); window.addEventListener(lyricsDisplayKey, sync);
    window.addEventListener(gameLyricsStyleKey, sync);
    if (kind === 'track') window.addEventListener(gameTrackTransparencyKey, sync);
    system.addEventListener('change', sync);
    return () => {
      window.removeEventListener('storage', sync); window.removeEventListener(lyricsDisplayKey, sync);
      window.removeEventListener(gameLyricsStyleKey, sync);
      window.removeEventListener(gameTrackTransparencyKey, sync);
      system.removeEventListener('change', sync);
    };
  }, [kind]);
  return { display, translation, style, trackTransparency };
}

function OverlayLyrics({ trackId, positionMs, connected, editing, display, translation, following, onManualScroll, style, active: visible }: {
  trackId: string; positionMs: number; connected: boolean; editing: boolean;
  display: ReturnType<typeof readLyricsDisplay>; translation: boolean; following: boolean; onManualScroll: () => void;
  style: GameLyricsStyle; active: boolean;
}) {
  const { lyrics, failed, cover } = useDesktopTrack(trackId, visible);
  const [sample, setSample] = useState({ url: '', color: '' });
  const color = display.colorMode === 'cover' && cover && sample.url === cover ? sample.color : '';
  const list = useRef<HTMLOListElement>(null);
  const scrollAnimation = useRef<ReturnType<typeof animate> | null>(null);
  const synced = lyrics?.kind === 'synced';
  const lines = lyrics?.lines;
  const dual = synced && translation && !!lines?.some(line => line.translation);
  const active = synced && lines ? activeLyricLine(lines, positionMs) : -1;
  useLayoutEffect(() => { if (list.current) list.current.scrollTop = 0; }, [trackId]);
  useLayoutEffect(() => {
    const container = list.current; if (!container || !synced || !following || !visible) return;
    const row = container.children[Math.max(0, active)] as HTMLElement | undefined; if (!row) return;
    let destination = -1;
    const center = () => {
      container.style.setProperty('--overlay-center-padding', `${Math.max(22, container.clientHeight / 2)}px`);
      const top = Math.max(0, row.offsetTop - (container.clientHeight - row.clientHeight) / 2);
      if (top === destination) return;
      destination = top;
      scrollAnimation.current?.stop(); scrollAnimation.current = null;
      if (Math.abs(container.scrollTop - top) < 1 || matchMedia('(prefers-reduced-motion: reduce)').matches) container.scrollTop = top;
      else scrollAnimation.current = animate(container.scrollTop, top, {
        ...lyricScrollTransition, onUpdate: value => { container.scrollTop = value; },
      });
    };
    center();
    const resize = new ResizeObserver(center); resize.observe(container, { box: 'border-box' });
    return () => { resize.disconnect(); scrollAnimation.current?.stop(); scrollAnimation.current = null; };
  }, [active, lines, synced, following, display.fontSize, display.alignment, translation, visible]);
  const status = !connected ? '正在连接播放器…' : !trackId ? '尚未播放歌曲' : failed ? '歌词暂不可用，正在重试…'
    : !lyrics ? '正在获取歌词…' : !lines?.length ? '暂无歌词' : '';
  const manualScroll = () => {
    if (!editing) return;
    scrollAnimation.current?.stop(); scrollAnimation.current = null; onManualScroll();
  };
  const rows = useMemo(() => lines?.map((line, index) => <li key={index} aria-current={synced && index === active ? 'true' : undefined}>
    <span>{line.text || '♪'}</span>{dual && line.translation && <small>{line.translation}</small>}
  </li>), [lines, synced, active, dual]);
  return status ? <div className="overlay-message" role="status">{status}</div> :
    <div className="overlay-lyrics-viewport" data-style={style} style={{
      ...(color ? { '--vinyl-color': color, '--lyrics-color': 'var(--lyric-vinyl)' } : {}),
    } as CSSProperties}>
      {display.colorMode === 'cover' && cover && <div hidden aria-hidden="true" onLoadCapture={event => {
        if (event.target instanceof HTMLImageElement) setSample({ url: cover, color: sampleCoverColor(event.target) });
      }}><CoverImage url={cover}/></div>}
      <ol ref={list} className="overlay-lyrics-list" aria-label={synced ? '同步滚动歌词' : '歌曲歌词'}
      data-synced={synced} data-dual={dual} data-alignment={display.alignment}
      style={{ '--overlay-lyric-size': `${display.fontSize}px` } as CSSProperties}
      onWheel={manualScroll} onPointerDown={manualScroll}>
      {rows}
    </ol></div>;
}

const OverlayTrackDetails = memo(function OverlayTrackDetails({ name, artist, cover, playing, connected, hasTrack }: {
  name: string; artist: string; cover: string; playing: boolean; connected: boolean; hasTrack: boolean;
}) {
  const [playerStyle] = usePlayerStyle();
  return <div className="overlay-track-details">
    {playerStyle === 'vinyl' ? <VinylCover url={cover} playing={playing} /> :
      playerStyle === 'square' ? <CoverImage url={cover} animateLoading /> : null}
    <div className="overlay-track-title"><h1 title={name}>{!connected ? '正在连接播放器…' : name || (hasTrack ? '正在读取歌曲…' : '尚未选择歌曲')}</h1>
      <p title={artist}>{artist || 'Musxi Player'}</p></div>
  </div>;
});

function OverlayTrack({ player, connected, editing, busy, pause, resume, active }: {
  player: PlayerState | null; connected: boolean; editing: boolean; busy: boolean; active: boolean;
  pause: () => Promise<void>; resume: () => Promise<void>;
}) {
  const id = player?.opened ? player.trackId : '';
  const [track, setTrack] = useState<{ id: string; now: LibraryState['now'] } | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    if (!id || !active) return;
    const controller = new AbortController(); let timer: ReturnType<typeof setTimeout> | undefined;
    const load = async () => {
      try {
        const library = await native.library.getState(1, controller.signal);
        if (!controller.signal.aborted) setTrack(previous => previous?.id === id && previous.now.name === library.now.name && previous.now.artist === library.now.artist && previous.now.cover === library.now.cover
          ? previous : { id, now: library.now });
      } catch { /* Keep metadata while a request is retried. */ }
      if (!controller.signal.aborted) timer = setTimeout(() => void load(), 4000);
    };
    void load();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [id, active]);
  useEffect(() => setError(''), [id]);
  const now = track?.id === id ? track.now : null;
  const times = playbackTimes(!!player?.opened, player?.durationMs ?? 0, player?.positionMs ?? 0);
  const playing = player?.requestedPlaying ?? player?.playing ?? false;
  const skip = async (delta: -1 | 1) => {
    try { await native.library.skip(delta); setError(''); }
    catch (e) { setError(e instanceof Error ? e.message : '切歌失败'); }
  };
  return <div className="overlay-track-content">
    <OverlayTrackDetails name={now?.name || ''} artist={now?.artist || ''} cover={now?.cover || ''}
      playing={!!player?.playing} connected={connected} hasTrack={!!id} />
    <progress aria-label="歌曲播放进度" max={Math.max(1, player?.durationMs ?? 0)} value={player?.positionMs ?? 0} />
    <div className="overlay-track-times"><span>{times.elapsed}</span><span>{times.total}</span></div>
    {editing && <div className="overlay-track-controls">
      <Button variant="unstyled" aria-label="上一首" disabled={!connected || busy || !id} onClick={() => void skip(-1)}>上一首</Button>
      <Button variant="unstyled" aria-label={playing ? '暂停' : '播放'} disabled={!connected || busy || !id} onClick={() => void (playing ? pause() : resume())}>{playing ? '暂停' : '播放'}</Button>
      <Button variant="unstyled" aria-label="下一首" disabled={!connected || busy || !id} onClick={() => void skip(1)}>下一首</Button>
    </div>}
    {error && <p className="overlay-error" role="alert">{error}</p>}
  </div>;
}

export default function GameOverlay({ kind }: { kind: 'lyrics' | 'track' }) {
  const card = useRef<HTMLElement>(null);
  useLayoutEffect(() => {
    if (kind !== 'track' || !card.current) return;
    const element = card.current; let lastHeight = 0;
    const measure = () => {
      const height = Math.ceil(element.getBoundingClientRect().height);
      if (height === lastHeight) return;
      lastHeight = height;
      void native.gameOverlay.reportTrackHeight(height).catch(() => { /* Older hosts keep their saved window size until restart. */ });
    };
    measure(); const resize = new ResizeObserver(measure); resize.observe(element);
    return () => resize.disconnect();
  }, [kind]);
  const overlay = useGameOverlay();
  const active = overlay.state.enabled && overlay.state.visible && (kind === 'lyrics' ? overlay.state.lyricsVisible : overlay.state.trackVisible);
  const player = usePlayerState(active);
  const { display, translation, style, trackTransparency } = useOverlayAppearance(kind);
  const [following, setFollowing] = useState(true);
  const trackId = player.state?.opened ? player.state.trackId : '';
  useEffect(() => setFollowing(true), [trackId, overlay.state.editing]);
  const control = (label: string, action: () => Promise<void>, children: ReactNode = label) =>
    <Button variant="unstyled" title={label} aria-label={label} disabled={overlay.busy} onClick={() => void action()}>{children}</Button>;
  return <main ref={card} className="game-overlay" data-widget={kind} data-editing={overlay.state.editing} aria-label={kind === 'lyrics' ? '游戏滚动歌词' : '歌曲信息组件'}
    style={kind === 'track' ? { '--game-track-opacity': 1 - trackTransparency / 100 } as CSSProperties : undefined}>
    {overlay.state.editing && <header className="overlay-widget-header">
      <span>{kind === 'lyrics' ? '滚动歌词' : '歌曲信息'}</span>
      <div>{kind === 'lyrics' && !following && <Button variant="unstyled" onClick={() => setFollowing(true)}>跟随当前句</Button>}
        {control('打开选歌面板', () => overlay.setPanelVisible(true), '选歌')}
        {control('完成编辑', () => overlay.setEditing(false), '完成')}
        {control(kind === 'lyrics' ? '关闭歌词组件' : '关闭歌曲信息组件', () => kind === 'lyrics' ? overlay.setLyricsVisible(false) : overlay.setTrackVisible(false), '×')}
      </div>
    </header>}
    {kind === 'lyrics' ? <OverlayLyrics trackId={trackId} positionMs={player.state?.positionMs ?? 0} connected={player.connected}
      editing={overlay.state.editing} display={display} translation={translation} style={style} active={active} following={following} onManualScroll={() => setFollowing(false)} />
      : <OverlayTrack active={active} player={player.state} connected={player.connected} editing={overlay.state.editing} busy={player.busy} pause={player.pause} resume={player.resume} />}
    {(overlay.error || player.error) && <p className="overlay-error" role="alert">{overlay.error || player.error}</p>}
  </main>;
}
