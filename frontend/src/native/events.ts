import { NativeError, parsePlayerState, type PlayerState } from './types.ts';
import type { CefTransport } from './transport.ts';
export type PlayerEventName = 'player.stateChanged' | 'player.trackChanged' | 'player.positionChanged' | 'player.volumeChanged';
export interface PlayerEvent { version: 1; event: PlayerEventName; state: PlayerState }
const names = new Set(['player.stateChanged', 'player.trackChanged', 'player.positionChanged', 'player.volumeChanged']);
export function subscribe(host: CefTransport, listener: (event: PlayerEvent) => void,
                          onError: (error: NativeError) => void): () => void {
  let closed = false;
  let id: number | undefined;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const stop = () => {
    if (closed) return;
    closed = true; clearTimeout(timer);
    if (id !== undefined) { try { host.cefQueryCancel?.(id); } catch { /* Renderer teardown. */ } }
  };
  const fail = (code: number, message: string) => {
    if (closed) return; stop(); onError(new NativeError(code, message));
  };
  if (!host.cefQuery || !host.cefQueryCancel) { fail(503, '请在播放器预览窗口中打开此页面'); return stop; }
  timer = setTimeout(() => fail(408, 'Native subscription timed out'), 5000);
  try {
    id = host.cefQuery({ request: JSON.stringify({ version: 1, command: 'player.subscribe', params: {} }), persistent: true,
      onSuccess(text) {
        if (closed) return;
        let event: PlayerEvent;
        try {
          const value = JSON.parse(text);
          if (value.version !== 1 || !names.has(value.event)) throw new Error('Invalid event');
          event = { version: 1, event: value.event, state: parsePlayerState(value.state) };
        } catch { fail(502, 'Invalid native event'); return; }
        clearTimeout(timer); listener(event);
      }, onFailure: fail
    });
    // Also handle a synchronously rejected test transport.
    if (closed && id !== undefined) host.cefQueryCancel(id);
  } catch { fail(503, 'Native subscription unavailable'); }
  return stop;
}
