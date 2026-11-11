import { memo, useEffect, useLayoutEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { animate, AnimatePresence, motion } from 'motion/react';
import { reportDesktopPage } from './native/desktopSmoke';
import FloatingLyricsControls from './DesktopLyrics';
import CoverImage from './components/CoverImage';
import LiquidGlassSurface from './components/LiquidGlassSurface';
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
  const [liquidGlass, setLiquidGlass] = useState(() => readVisualSettings(localStorage).liquidGlass);
  const [trackTransparency, setTrackTransparency] = useState(() => kind === 'track' ? readGameTrackTransparency(localStorage) : 0);
  useEffect(() => {
    const system = matchMedia('(prefers-color-scheme: dark)');
    const sync = () => {
      const { appearance, transparency, liquidGlass } = readVisualSettings(localStorage);
      setLiquidGlass(liquidGlass);
      document.documentElement.dataset.theme = resolveTheme(appearance, system.matches);
      document.documentElement.dataset.tone = appearance.tone;
      document.documentElement.style.setProperty('--background-opacity', String(appearance.transparent ? 1 - transparency / 100 : 1));
      setDisplay(readLyricsDisplay(localStorage)); setTranslation(readLyricsTranslation(localStorage));
      setStyle(readGameLyricsStyle(localStorage));
      if (kind === 'track') setTrackTransparency(readGameTrackTransparency(localStorage));
    };
    sync(); window.addEventListener('storage', sync); window.addEventListener(lyricsDisplayKey, sync);
    window.addEventListener(gameLyricsStyleKey, sync); window.addEventListener('musxi-lyrics-translation', sync);
    if (kind === 'track') window.addEventListener(gameTrackTransparencyKey, sync);
    system.addEventListener('change', sync);
    return () => {
      window.removeEventListener('storage', sync); window.removeEventListener(lyricsDisplayKey, sync);
      window.removeEventListener(gameLyricsStyleKey, sync); window.removeEventListener('musxi-lyrics-translation', sync);
      window.removeEventListener(gameTrackTransparencyKey, sync);
      system.removeEventListener('change', sync);
    };
  }, [kind]);
  return { display, translation, style, trackTransparency, liquidGlass };
}

function OverlayLyrics({ trackId, positionMs, connected, editing, display, translation, following, onManualScroll, style, mode, active: visible }: {
  trackId: string; positionMs: number; connected: boolean; editing: boolean;
  display: ReturnType<typeof readLyricsDisplay>; translation: boolean; following: boolean; onManualScroll: () => void;
  style: GameLyricsStyle; mode: 'single' | 'multi'; active: boolean;
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
  const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;
  useLayoutEffect(() => { if (list.current) list.current.scrollTop = 0; }, [trackId]);
  useLayoutEffect(() => {
    const container = list.current; if (!container || !synced || !following || !visible || mode === 'single') return;
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
  }, [active, lines, synced, following, display.fontSize, display.alignment, translation, visible, mode]);
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
    <div className="overlay-lyrics-viewport" data-style={style} data-mode={mode} style={{
      ...(color ? { '--vinyl-color': color, '--lyrics-color': 'var(--lyric-vinyl)' } : {}),
    } as CSSProperties}>
      {display.colorMode === 'cover' && cover && <div hidden aria-hidden="true" onLoadCapture={event => {
        if (event.target instanceof HTMLImageElement) setSample({ url: cover, color: sampleCoverColor(event.target) });
      }}><CoverImage url={cover}/></div>}
      <ol ref={list} className="overlay-lyrics-list" aria-label={mode === 'single' ? '当前句歌词' : synced ? '同步滚动歌词' : '歌曲歌词'}
      data-synced={synced} data-dual={dual} data-alignment={display.alignment}
      style={{ '--overlay-lyric-size': `${display.fontSize}px` } as CSSProperties}
      onWheel={manualScroll} onPointerDown={manualScroll}>
      {mode === 'single' ? <li aria-current="true" className="overlay-single-line">
        <AnimatePresence key={`${trackId}:${reduceMotion}`} initial={false}>
          <motion.div key={active} className="overlay-single-content"
            initial={reduceMotion ? false : { opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={reduceMotion ? undefined : { opacity: 0, y: -8 }}
            transition={{ duration: reduceMotion ? 0 : .3, ease: 'easeOut' }}>
            <span>{lines?.[Math.max(0, active)]?.text || '♪'}</span>
            {translation && lines?.[Math.max(0, active)]?.translation && <small>{lines[Math.max(0, active)].translation}</small>}
          </motion.div>
        </AnimatePresence>
      </li> : rows}
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

function OverlayTrack({ player, connected, active }: {
  player: PlayerState | null; connected: boolean; active: boolean;
}) {
  const id = player?.opened ? player.trackId : '';
  const [track, setTrack] = useState<{ id: string; now: LibraryState['now'] } | null>(null);
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
  const now = track?.id === id ? track.now : null;
  const times = playbackTimes(!!player?.opened, player?.durationMs ?? 0, player?.positionMs ?? 0);
  return <div className="overlay-track-content">
    <OverlayTrackDetails name={now?.name || ''} artist={now?.artist || ''} cover={now?.cover || ''}
      playing={!!player?.playing} connected={connected} hasTrack={!!id} />
    <progress aria-label="歌曲播放进度" max={Math.max(1, player?.durationMs ?? 0)} value={player?.positionMs ?? 0} />
    <div className="overlay-track-times"><span>{times.elapsed}</span><span>{times.total}</span></div>
  </div>;
}

export default function GameOverlay({ kind }: { kind: 'lyrics' | 'track' }) {
  const card = useRef<HTMLElement>(null);
  useEffect(() => new URLSearchParams(location.search).has('desktopSmoke') ? reportDesktopPage() : undefined, []);
  useLayoutEffect(() => {
    if (kind !== 'track' || !card.current) return;
    const element = card.current; let lastHeight = 0, lastWidth = 0;
    const measure = () => {
      const width = element.getBoundingClientRect().width;
      if (width > 0 && width !== lastWidth) {
        lastWidth = width;
        element.style.setProperty('--game-track-scale', String(width / 360));
      }
      const height = Math.ceil(element.getBoundingClientRect().height);
      if (height === lastHeight) return;
      lastHeight = height;
      void native.gameOverlay.reportTrackHeight(height).catch(() => { /* Older hosts keep their saved window size until restart. */ });
    };
    measure(); const resize = new ResizeObserver(measure); resize.observe(element);
    return () => resize.disconnect();
  }, [kind]);
  const overlay = useGameOverlay();
  const editing = kind === 'lyrics' ? overlay.state.lyricsEditing : overlay.state.trackEditing;
  const active = overlay.state.enabled && overlay.state.visible && (kind === 'lyrics' ? overlay.state.lyricsVisible : overlay.state.trackVisible);
  const player = usePlayerState(active);
  const { display: pageDisplay, translation, style, trackTransparency, liquidGlass } = useOverlayAppearance(kind);
  const display = { ...pageDisplay, fontSize: overlay.state.lyricsFontSize || pageDisplay.fontSize };
  const [following, setFollowing] = useState(true);
  const trackId = player.state?.opened ? player.state.trackId : '';
  useEffect(() => setFollowing(true), [trackId, editing]);
  const control = (label: string, action: () => Promise<void>, children: ReactNode = label) =>
    <Button variant="unstyled" title={label} aria-label={label} disabled={overlay.busy} onClick={() => void action()}>{children}</Button>;
  return <main ref={card} className="game-overlay" data-widget={kind} data-editing={editing} aria-label={kind === 'lyrics' ? '歌词悬浮组件' : '歌曲信息组件'}
    style={kind === 'track' ? { '--game-track-opacity': 1 - trackTransparency / 100 } as CSSProperties : undefined}>
    {kind === 'track' && liquidGlass && <LiquidGlassSurface refract={false}/>}
    {editing && <header className="overlay-widget-header">
      <span aria-hidden="true"/>
      <div>
        {control('完成编辑', () => overlay.setEditing(false), '完成')}
        {control(kind === 'lyrics' ? '关闭歌词组件' : '关闭歌曲信息组件', () => kind === 'lyrics' ? overlay.setLyricsVisible(false) : overlay.setTrackVisible(false), '关闭')}
      </div>
    </header>}
    {kind === 'lyrics' ? <OverlayLyrics trackId={trackId} positionMs={player.state?.positionMs ?? 0} connected={player.connected}
      mode={overlay.state.lyricsMode} editing={editing} display={display} translation={translation} style={style} active={active} following={following} onManualScroll={() => setFollowing(false)} />
      : <OverlayTrack active={active} player={player.state} connected={player.connected} />}
    {kind === 'lyrics' && <FloatingLyricsControls playing={player.state?.requestedPlaying ?? player.state?.playing ?? false} pause={player.pause} resume={player.resume} />}
    {(overlay.error || player.error) && <p className="overlay-error" role="alert">{overlay.error || player.error}</p>}
  </main>;
}
