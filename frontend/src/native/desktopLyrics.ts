import { request, type CefTransport } from './transport.ts';
import { NativeError } from './types.ts';

export const desktopColors = ['theme', 'light', 'dark'] as const;
export type DesktopColor = typeof desktopColors[number];
// panel/hover are transient native UI state; the rest is saved in the profile.
export interface DesktopLyricsState {
  enabled: boolean; visible: boolean; locked: boolean; dual: boolean;
  panel: boolean; hover: boolean; fontSize: number; color: DesktopColor;
}
export const desktopFontRange = { min: 18, max: 48, step: 2 } as const;

export function parseDesktopLyrics(value: unknown): DesktopLyricsState {
  const s = value as Partial<DesktopLyricsState> | null;
  if (!s || (['enabled', 'visible', 'locked', 'dual', 'panel', 'hover'] as const).some(key => typeof s[key] !== 'boolean')
      || !Number.isInteger(s.fontSize) || s.fontSize! < desktopFontRange.min || s.fontSize! > desktopFontRange.max
      || !desktopColors.includes(s.color as DesktopColor))
    throw new NativeError(502, 'Invalid desktop lyrics state');
  return s as DesktopLyricsState;
}

export function createDesktopLyricsClient(host: CefTransport) {
  const call = async (command: string, params = {}) => parseDesktopLyrics(await request(host, command, params));
  return Object.freeze({
    getState: () => call('desktopLyrics.getState'),
    setVisible: (visible: boolean) => call('desktopLyrics.setVisible', { visible }),
    setLocked: (locked: boolean) => call('desktopLyrics.setLocked', { locked }),
    setDual: (dual: boolean) => call('desktopLyrics.setDual', { dual }),
    setPanel: (panel: boolean) => call('desktopLyrics.setPanel', { panel }),
    setFontSize: (fontSize: number) => call('desktopLyrics.setFontSize', { fontSize }),
    setColor: (color: DesktopColor) => call('desktopLyrics.setColor', { color }),
    // Native pushes the current state first, then every change from any page or the tray.
    subscribe(listener: (state: DesktopLyricsState) => void, onError: (error: NativeError) => void) {
      let closed = false; let id: number | undefined;
      const stop = () => { if (closed) return; closed = true; if (id !== undefined) try { host.cefQueryCancel?.(id); } catch { /* Teardown. */ } };
      if (!host.cefQuery) { onError(new NativeError(503, 'Native bridge unavailable')); return stop; }
      id = host.cefQuery({ request: JSON.stringify({ version: 1, command: 'desktopLyrics.subscribe', params: {} }), persistent: true,
        onSuccess(text) {
          if (closed) return;
          try { listener(parseDesktopLyrics(JSON.parse(text).state)); }
          catch { stop(); onError(new NativeError(502, 'Invalid desktop lyrics event')); }
        },
        onFailure(code, message) { if (!closed) { stop(); onError(new NativeError(code, message)); } } });
      return stop;
    },
  });
}
