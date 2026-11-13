import { useEffect, useState, type CSSProperties } from 'react';
import { gameLyricsStyleKey, readGameLyricsStyle, type GameLyricsStyle } from '../composables/gameLyricsStyle';
import { gameTrackTransparencyKey, readGameTrackTransparency } from '../composables/gameTrackTransparency';
import type { useGameOverlay } from '../composables/useGameOverlay';
import { Button } from './ui/button';

export default function GameOverlaySettings({ overlay, onShortcutSettings }: { overlay: ReturnType<typeof useGameOverlay>; onShortcutSettings: () => void }) {
  const { state, busy, error, windows = [] } = overlay;
  const [selectedWindow, setSelectedWindow] = useState('');
  useEffect(() => { if (!windows.some(window => window.id === selectedWindow)) setSelectedWindow(''); }, [windows, selectedWindow]);
  const [style, setStyle] = useState(() => readGameLyricsStyle(localStorage));
  const [transparency, setTransparency] = useState(() => readGameTrackTransparency(localStorage));
  const [transparencyError, setTransparencyError] = useState('');
  const [styleError, setStyleError] = useState('');
  const updateStyle = (next: GameLyricsStyle) => {
    try {
      localStorage.setItem(gameLyricsStyleKey, next); setStyle(next); setStyleError('');
      window.dispatchEvent(new Event(gameLyricsStyleKey));
    } catch { setStyleError('歌词样式保存失败，请重试'); }
  };
  const updateTransparency = (next: number) => {
    try {
      localStorage.setItem(gameTrackTransparencyKey, String(next)); setTransparency(next); setTransparencyError('');
      window.dispatchEvent(new Event(gameTrackTransparencyKey));
    } catch { setTransparencyError('歌曲信息组件透明度保存失败，请重试'); }
  };
  return <section className="settings-content game-overlay-settings" aria-label="悬浮组件设置" aria-busy={busy}>
    <h2>悬浮组件</h2>
    {!state.available && <p role="status">当前窗口暂不支持悬浮组件。</p>}
    {error && <p className="playback-settings-error" role="alert">{error}</p>}
    {([
      ['floating-widgets-topmost', '组件置顶', state.topmost, overlay.setTopmost],
      ['game-overlay-track', '歌曲信息组件', state.visible && state.trackVisible, overlay.setTrackVisible],
      ['game-overlay-lyrics', '歌词悬浮组件', state.visible && state.lyricsVisible, overlay.setLyricsVisible],
    ] as const).map(([id, label, checked, update]) => <div className="appearance-setting-row" key={id}>
      <h3 id={id}>{label}</h3>
      <Button variant="unstyled" className="appearance-switch" role="switch" aria-checked={checked} aria-labelledby={id}
        disabled={!state.available || busy} onClick={() => void update(!checked)}><span/></Button>
    </div>)}
    <div className="game-overlay-hints">
      <div className="appearance-setting-row">
        <h3 id="game-overlay-hide-on-hover">鼠标出现时隐藏组件</h3>
        <Button variant="unstyled" className="appearance-switch" role="switch" aria-checked={state.hideOnHover}
          aria-labelledby="game-overlay-hide-on-hover" disabled={!state.available || busy}
          onClick={() => void overlay.setHideOnHover(!state.hideOnHover)}><span/></Button>
      </div>
      <p>其他程序在前台、组件已锁定时，系统鼠标出现即隐藏歌词和歌曲信息，鼠标隐藏后恢复原先开启的组件。编辑和选歌期间保持显示。</p>
    </div>
    <div className="game-overlay-hints">
      <div className="appearance-setting-row">
        <h3 id="window-compatibility-label">通用窗口兼容</h3>
        <Button variant="unstyled" className="appearance-switch" role="switch" aria-checked={state.windowCompatibility}
          aria-labelledby="window-compatibility-label" disabled={!state.available || busy}
          onClick={() => void overlay.setWindowCompatibility(!state.windowCompatibility)}><span/></Button>
      </div>
      <p>全屏时看不到组件，可先切换为带标题栏的普通窗口模式，再选择窗口并点击“应用”，使画面完整铺满屏幕。关闭兼容模式可恢复原布局；重启播放器后需重新开启并应用。</p>
        <div className="playback-setting">
          <select id="compatibility-window" aria-label="窗口" value={selectedWindow} disabled={!state.windowCompatibility || busy || !!state.windowCompatibilityTarget}
            onChange={event => setSelectedWindow(event.currentTarget.value)}>
            <option value="">请选择窗口</option>
            {windows.map(window => <option key={window.id} value={window.id}>{window.title} — {window.program}</option>)}
          </select>
        </div>
        <div className="close-dialog-actions">
          <Button variant="secondary" disabled={!state.windowCompatibility || busy || !!state.windowCompatibilityTarget} onClick={() => void overlay.refreshWindows()}>刷新列表</Button>
          <Button variant="secondary" disabled={!state.windowCompatibility || busy || !selectedWindow || !!state.windowCompatibilityTarget}
            onClick={() => void overlay.applyWindowCompatibility(selectedWindow)}>应用</Button>
          <Button variant="secondary" disabled={!state.windowCompatibility || busy || !state.windowCompatibilityTarget}
            onClick={() => void overlay.restoreWindowCompatibility()}>{state.windowCompatibilityStatus === 'userChanged' ? '结束管理' : '恢复原布局'}</Button>
        </div>
        {state.windowCompatibilityTarget && <p>当前窗口：{state.windowCompatibilityTarget}</p>}
        {!busy && !windows.length && !state.windowCompatibilityTarget && <p>未列出窗口？请确认目标程序为普通窗口模式，且播放器与目标程序权限一致，再刷新列表。</p>}
        <p role={state.windowCompatibilityStatus === 'error' ? 'alert' : 'status'}>
          {{ off: '工具已关闭。', ready: '选择窗口后点击“应用”；切回目标窗口不会自动应用。',
            applied: '兼容布局已应用，画面保持完整铺满。', userChanged: '已保留你调整后的布局并停止接管，请结束管理后重新选择。',
            error: '窗口调整或恢复未完成，请检查窗口模式和权限；恢复失败时可先关闭所选窗口。' }[state.windowCompatibilityStatus]}
        </p>
    </div>
    <div className="appearance-level">
      <div className="appearance-setting-row"><label htmlFor="game-track-transparency">歌曲信息组件透明度</label><output htmlFor="game-track-transparency">{transparency}%</output></div>
      <input id="game-track-transparency" className="appearance-slider" type="range" min="0" max="100" step="1" value={transparency}
        style={{ '--level': `${transparency}%` } as CSSProperties} aria-valuetext={`${transparency}%`}
        onChange={event => updateTransparency(Number(event.currentTarget.value))}/>
      {transparencyError && <p className="playback-settings-error" role="alert">{transparencyError}</p>}
    </div>
    <div className="appearance-mode"><h3 id="floating-lyrics-mode-label">悬浮歌词行数</h3>
      <div className="appearance-segments" role="group" aria-labelledby="floating-lyrics-mode-label">
        {([['single', '单行'], ['multi', '多行']] as const).map(([value, label]) =>
          <Button key={value} variant="unstyled" className="appearance-segment" aria-pressed={state.lyricsMode === value}
            disabled={!state.available || busy} onClick={() => void overlay.setLyricsMode(value)}>{label}</Button>)}
      </div>
    </div>
    <div className="appearance-level">
      <div className="appearance-setting-row"><label htmlFor="floating-lyrics-font">悬浮歌词字号</label><output htmlFor="floating-lyrics-font">{state.lyricsFontSize || 20}px</output></div>
      <input id="floating-lyrics-font" className="appearance-slider" type="range" min="16" max="48" step="1" value={state.lyricsFontSize || 20}
        disabled={!state.available || busy} style={{ '--level': `${((state.lyricsFontSize || 20) - 16) / 32 * 100}%` } as CSSProperties}
        onChange={event => void overlay.setLyricsFontSize(Number(event.currentTarget.value))}/>
    </div>
    <div className="appearance-mode"><h3 id="game-lyrics-style-label">歌词样式</h3>
      <div className="appearance-segments" role="group" aria-labelledby="game-lyrics-style-label">
        {([['flat', '平面'], ['perspective', '斜向立体']] as const).map(([value, label]) =>
          <Button key={value} variant="unstyled" className="appearance-segment" aria-pressed={style === value}
            onClick={() => updateStyle(value)}>{label}</Button>)}
      </div>
      {styleError && <p className="playback-settings-error" role="alert">{styleError}</p>}
    </div>
    <div className="close-dialog-actions">
      <Button variant="secondary" disabled={!state.available || busy || (!state.lyricsVisible && !state.trackVisible)} aria-pressed={state.editing}
        onClick={() => void overlay.setEditing(!state.editing)}>{state.editing ? '完成编辑并锁定' : '编辑组件'}</Button>
      <Button variant="secondary" onClick={onShortcutSettings}>组件快捷键设置</Button>
    </div>
    <div className="game-overlay-hints"><p>关闭置顶后，组件可能被游戏或其他窗口遮挡。</p><p>在“快捷键”中设置组件显隐、编辑切换快捷键，并启用全局快捷键，即可在游戏中操作。</p>
    <p>启用全局快捷键后，按键会同时传给前台程序，按键可能同时执行播放器和游戏操作。游戏以管理员权限运行时，请右键播放器并选择“以管理员身份运行”，以便接收游戏前台的快捷键。</p></div>
  </section>;
}
