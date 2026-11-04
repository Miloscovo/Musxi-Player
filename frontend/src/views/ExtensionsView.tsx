import { Button } from '../components/ui/button';
import type { GameOverlayController } from '../composables/useGameOverlay';

export default function ExtensionsView({ overlay }: { overlay: GameOverlayController }) {
  const { state, busy, error } = overlay;
  return <section className="extensions-content" aria-label="扩展" aria-busy={busy}>
    <article className="extension-card" data-extension="game-player" aria-labelledby="game-player-extension-title">
      <div className="extension-card-heading">
        <span className="extension-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M7 5.5h10a4 4 0 0 1 3.9 3.2l1.2 7.6c.4 2.6-2.7 4.2-4.5 2.3L15 16H9l-2.6 2.6c-1.8 1.9-4.9.3-4.5-2.3l1.2-7.6A4 4 0 0 1 7 5.5ZM5.5 10.5h5m-2.5-2.5v5m7-3h.01m2.5 2.5h.01"/></svg></span>
        <div><h2 id="game-player-extension-title">游戏内悬浮组件</h2><span className="extension-origin">内置</span></div>
        <Button id="game-overlay-enabled" variant="unstyled" className="appearance-switch" role="switch" aria-label="启用游戏内悬浮组件"
          aria-describedby="game-player-extension-description" aria-checked={state.enabled} disabled={!state.available || busy}
          onClick={() => void overlay.setEnabled(!state.enabled)}><span/></Button>
      </div>
      <p id="game-player-extension-description">在游戏画面上显示滚动歌词和歌曲信息。</p>
      <p className="extension-status">{state.enabled ? '已启用，可在“设置 → 游戏内悬浮组件”中配置。' : '启用后可在设置中配置，悬浮组件由你手动显示。'}</p>
      {!state.available && <p role="status">当前窗口暂不支持游戏内悬浮组件。</p>}
    </article>
    {error && <p className="playback-settings-error" role="alert">{error}</p>}
  </section>;
}
