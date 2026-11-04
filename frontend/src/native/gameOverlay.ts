import { request, type CefTransport } from './transport.ts';
import { NativeError } from './types.ts';

export interface GameOverlayState {
  available: boolean; enabled: boolean; visible: boolean; lyricsVisible: boolean;
  trackVisible: boolean; editing: boolean; panelVisible: boolean;
  hideOnHover: boolean;
  minecraftCompatibility: boolean;
  minecraftStatus: 'off' | 'waiting' | 'windowedRequired' | 'applied' | 'userChanged' | 'error';
  windowCompatibility: boolean;
  windowCompatibilityStatus: 'off' | 'ready' | 'applied' | 'userChanged' | 'error';
  windowCompatibilityTarget: string;
}
export interface CompatibilityWindow { id: string; title: string; program: string }
const windowId = /^\d+-\d+$/;

export const initialGameOverlayState: GameOverlayState = {
  available: false, enabled: false, visible: true, lyricsVisible: true,
  trackVisible: true, editing: false, panelVisible: false,
  hideOnHover: false,
  minecraftCompatibility: false, minecraftStatus: 'off',
  windowCompatibility: false, windowCompatibilityStatus: 'off', windowCompatibilityTarget: '',
};

export function parseGameOverlay(value: unknown): GameOverlayState {
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || (['available', 'enabled', 'visible', 'lyricsVisible', 'trackVisible', 'editing', 'panelVisible'] as const).some(key => typeof (value as GameOverlayState)[key] !== 'boolean'))
    throw new NativeError(502, 'Invalid game overlay state');
  const state = value as GameOverlayState;
  if (state.hideOnHover !== undefined && typeof state.hideOnHover !== 'boolean') throw new NativeError(502, 'Invalid game overlay state');
  if ((state.minecraftCompatibility !== undefined && typeof state.minecraftCompatibility !== 'boolean')
      || (state.minecraftStatus !== undefined && !['off', 'waiting', 'windowedRequired', 'applied', 'userChanged', 'error'].includes(state.minecraftStatus)))
    throw new NativeError(502, 'Invalid Minecraft compatibility state');
  if ((state.windowCompatibility !== undefined && typeof state.windowCompatibility !== 'boolean')
      || (state.windowCompatibilityStatus !== undefined && !['off', 'ready', 'applied', 'userChanged', 'error'].includes(state.windowCompatibilityStatus))
      || (state.windowCompatibilityTarget !== undefined && (typeof state.windowCompatibilityTarget !== 'string' || state.windowCompatibilityTarget.length > 512)))
    throw new NativeError(502, 'Invalid window compatibility state');
  return { ...initialGameOverlayState, ...state };
}

export function createGameOverlayClient(host: CefTransport) {
  const call = async (command: string, params = {}) => parseGameOverlay(await request(host, command, params));
  return Object.freeze({
    getState: () => call('gameOverlay.getState'),
    setEnabled: (enabled: boolean) => call('gameOverlay.setEnabled', { enabled }),
    setVisible: (visible: boolean) => call('gameOverlay.setVisible', { visible }),
    setLyricsVisible: (lyricsVisible: boolean) => call('gameOverlay.setLyricsVisible', { lyricsVisible }),
    setTrackVisible: (trackVisible: boolean) => call('gameOverlay.setTrackVisible', { trackVisible }),
    setEditing: (editing: boolean) => call('gameOverlay.setEditing', { editing }),
    setPanelVisible: (panelVisible: boolean) => call('gameOverlay.setPanelVisible', { panelVisible }),
    setHideOnHover: (hideOnHover: boolean) => call('gameOverlay.setHideOnHover', { hideOnHover }),
    setMinecraftCompatibility: (minecraftCompatibility: boolean) => call('gameOverlay.setMinecraftCompatibility', { minecraftCompatibility }),
    setWindowCompatibility: (windowCompatibility: boolean) => call('gameOverlay.setWindowCompatibility', { windowCompatibility }),
    restoreWindowCompatibility: () => call('gameOverlay.restoreWindowCompatibility'),
    async applyWindowCompatibility(id: string) {
      if (typeof id !== 'string' || id.length > 48 || !windowId.test(id)) throw new NativeError(400, 'Invalid compatibility window');
      return call('gameOverlay.applyWindowCompatibility', { id });
    },
    async listCompatibilityWindows(): Promise<CompatibilityWindow[]> {
      const windows = await request(host, 'gameOverlay.listCompatibilityWindows', {});
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
      if (await request(host, 'gameOverlay.reportTrackHeight', { height }) !== true) throw new NativeError(502, 'Invalid track widget height reply');
    },
    subscribe(listener: (state: GameOverlayState) => void, onError: (error: NativeError) => void) {
      let closed = false; let id: number | undefined;
      const stop = () => {
        if (closed) return;
        closed = true;
        if (id !== undefined) try { host.cefQueryCancel?.(id); } catch { /* Context already gone. */ }
      };
      if (!host.cefQuery) { onError(new NativeError(503, 'Native bridge unavailable')); return stop; }
      try {
        id = host.cefQuery({ request: JSON.stringify({ version: 1, command: 'gameOverlay.subscribe', params: {} }), persistent: true,
          onSuccess(text) {
            if (closed) return;
            try {
              const event: unknown = JSON.parse(text);
              if (!event || typeof event !== 'object' || !('version' in event) || event.version !== 1
                  || !('event' in event) || event.event !== 'gameOverlay.changed' || !('state' in event)) throw new Error();
              listener(parseGameOverlay(event.state));
            } catch { stop(); onError(new NativeError(502, 'Invalid game overlay event')); }
          },
          onFailure(code, message) { if (!closed) { stop(); onError(new NativeError(code, message)); } },
        });
        if (closed) try { host.cefQueryCancel?.(id); } catch { /* Synchronous failure. */ }
      } catch { stop(); onError(new NativeError(503, 'Native bridge unavailable')); }
      return stop;
    },
  });
}
