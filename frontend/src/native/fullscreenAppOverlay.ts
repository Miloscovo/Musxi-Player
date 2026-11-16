import { request, type CefTransport } from './transport.ts';
import { NativeError } from './types.ts';

export interface FullscreenAppOverlayState {
  available: boolean; enabled: boolean; visible: boolean; lyricsVisible: boolean;
  trackVisible: boolean; editing: boolean; lyricsEditing: boolean; trackEditing: boolean;
  hideOnHover: boolean; topmost: boolean; lyricsMode: 'single' | 'multi'; lyricsFontSize: number;
  windowCompatibility: boolean;
  windowCompatibilityStatus: 'off' | 'ready' | 'applied' | 'userChanged' | 'error';
  windowCompatibilityTarget: string;
}
export interface CompatibilityWindow { id: string; title: string; program: string }
const windowId = /^\d+-\d+$/;

export const initialFullscreenAppOverlayState: FullscreenAppOverlayState = {
  available: false, enabled: true, visible: false, lyricsVisible: true,
  trackVisible: true, editing: false, lyricsEditing: false, trackEditing: false,
  hideOnHover: false, topmost: true, lyricsMode: 'multi', lyricsFontSize: 0,
  windowCompatibility: false, windowCompatibilityStatus: 'off', windowCompatibilityTarget: '',
};

export function parseFullscreenAppOverlay(value: unknown): FullscreenAppOverlayState {
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || (['available', 'enabled', 'visible', 'lyricsVisible', 'trackVisible', 'editing'] as const).some(key => typeof (value as FullscreenAppOverlayState)[key] !== 'boolean'))
    throw new NativeError(502, 'Invalid fullscreen-app overlay state');
  const state = value as FullscreenAppOverlayState;
  if (state.lyricsEditing !== undefined && typeof state.lyricsEditing !== 'boolean') throw new NativeError(502, 'Invalid lyrics editing state');
  if (state.trackEditing !== undefined && typeof state.trackEditing !== 'boolean') throw new NativeError(502, 'Invalid track editing state');
  if (state.hideOnHover !== undefined && typeof state.hideOnHover !== 'boolean') throw new NativeError(502, 'Invalid fullscreen-app overlay state');
  if ((state.windowCompatibility !== undefined && typeof state.windowCompatibility !== 'boolean')
      || (state.windowCompatibilityStatus !== undefined && !['off', 'ready', 'applied', 'userChanged', 'error'].includes(state.windowCompatibilityStatus))
      || (state.windowCompatibilityTarget !== undefined && (typeof state.windowCompatibilityTarget !== 'string' || state.windowCompatibilityTarget.length > 512)))
    throw new NativeError(502, 'Invalid window compatibility state');
  if ((state.topmost !== undefined && typeof state.topmost !== 'boolean')
      || (state.lyricsMode !== undefined && state.lyricsMode !== 'single' && state.lyricsMode !== 'multi')
      || (state.lyricsFontSize !== undefined && (!Number.isInteger(state.lyricsFontSize) || (state.lyricsFontSize !== 0 && (state.lyricsFontSize < 16 || state.lyricsFontSize > 48)))))
    throw new NativeError(502, 'Invalid floating lyrics state');
  return { ...initialFullscreenAppOverlayState, ...state, lyricsEditing: state.lyricsEditing ?? state.editing, trackEditing: state.trackEditing ?? state.editing };
}

export function createFullscreenAppOverlayClient(host: CefTransport) {
  const call = async (command: string, params = {}) => parseFullscreenAppOverlay(await request(host, command, params));
  return Object.freeze({
    getState: () => call('fullscreenAppOverlay.getState'),
    setTopmost: (topmost: boolean) => call('fullscreenAppOverlay.setTopmost', { topmost }),
    setLyricsMode: (lyricsMode: 'single' | 'multi') => call('fullscreenAppOverlay.setLyricsMode', { lyricsMode }),
    setLyricsFontSize: (lyricsFontSize: number) => call('fullscreenAppOverlay.setLyricsFontSize', { lyricsFontSize }),
    setEnabled: (enabled: boolean) => call('fullscreenAppOverlay.setEnabled', { enabled }),
    setVisible: (visible: boolean) => call('fullscreenAppOverlay.setVisible', { visible }),
    setLyricsVisible: (lyricsVisible: boolean) => call('fullscreenAppOverlay.setLyricsVisible', { lyricsVisible }),
    setTrackVisible: (trackVisible: boolean) => call('fullscreenAppOverlay.setTrackVisible', { trackVisible }),
    setEditing: (editing: boolean) => call('fullscreenAppOverlay.setEditing', { editing }),
    setHideOnHover: (hideOnHover: boolean) => call('fullscreenAppOverlay.setHideOnHover', { hideOnHover }),
    setWindowCompatibility: (windowCompatibility: boolean) => call('fullscreenAppOverlay.setWindowCompatibility', { windowCompatibility }),
    restoreWindowCompatibility: () => call('fullscreenAppOverlay.restoreWindowCompatibility'),
    async applyWindowCompatibility(id: string) {
      if (typeof id !== 'string' || id.length > 48 || !windowId.test(id)) throw new NativeError(400, 'Invalid compatibility window');
      return call('fullscreenAppOverlay.applyWindowCompatibility', { id });
    },
    async listCompatibilityWindows(): Promise<CompatibilityWindow[]> {
      const windows = await request(host, 'fullscreenAppOverlay.listCompatibilityWindows', {});
      if (!Array.isArray(windows) || windows.length > 256 || windows.some(value => !value || typeof value !== 'object'
          || typeof value.id !== 'string' || value.id.length > 48 || !windowId.test(value.id)
          || typeof value.title !== 'string' || !value.title || value.title.length > 512
          || typeof value.program !== 'string' || !value.program || value.program.length > 260)
          || new Set(windows.map(value => value.id)).size !== windows.length)
        throw new NativeError(502, 'Invalid compatibility window list');
      return windows;
    },
    async reportTrackHeight(height: number) {
      if (!Number.isInteger(height) || height < 80 || height > 1000) throw new NativeError(400, 'Invalid track widget height');
      if (await request(host, 'fullscreenAppOverlay.reportTrackHeight', { height }) !== true) throw new NativeError(502, 'Invalid track widget height reply');
    },
    subscribe(listener: (state: FullscreenAppOverlayState) => void, onError: (error: NativeError) => void) {
      let closed = false; let id: number | undefined;
      const stop = () => {
        if (closed) return;
        closed = true;
        if (id !== undefined) try { host.cefQueryCancel?.(id); } catch { /* Context already gone. */ }
      };
      if (!host.cefQuery) { onError(new NativeError(503, 'Native bridge unavailable')); return stop; }
      try {
        id = host.cefQuery({ request: JSON.stringify({ version: 1, command: 'fullscreenAppOverlay.subscribe', params: {} }), persistent: true,
          onSuccess(text) {
            if (closed) return;
            try {
              const event: unknown = JSON.parse(text);
              if (!event || typeof event !== 'object' || !('version' in event) || event.version !== 1
                  || !('event' in event) || event.event !== 'fullscreenAppOverlay.changed' || !('state' in event)) throw new Error();
              listener(parseFullscreenAppOverlay(event.state));
            } catch { stop(); onError(new NativeError(502, 'Invalid fullscreen-app overlay event')); }
          },
          onFailure(code, message) { if (!closed) { stop(); onError(new NativeError(code, message)); } },
        });
        if (closed) try { host.cefQueryCancel?.(id); } catch { /* Synchronous failure. */ }
      } catch { stop(); onError(new NativeError(503, 'Native bridge unavailable')); }
      return stop;
    },
  });
}
