import { NativeError, type RequestOptions } from './types.ts';
export interface CefTransport {
  cefQuery?: (query: { request: string; persistent: boolean;
    onSuccess: (reply: string) => void; onFailure: (code: number, message: string) => void }) => number;
  cefQueryCancel?: (id: number) => void;
}
// Internal transport; components and composables import only client.ts.
export function request(host: CefTransport, command: string, params: object,
                        options: RequestOptions = {}, timeoutMs = 5000): Promise<unknown> {
  return new Promise((resolve, reject) => {
    if (options.signal?.aborted) { reject(new NativeError(499, 'Request cancelled')); return; }
    if (!host.cefQuery || !host.cefQueryCancel) { reject(new NativeError(503, '请在播放器预览窗口中打开此页面')); return; }
    let id: number | undefined;
    let settled = false;
    const finish = (error?: Error, value?: unknown) => {
      if (settled) return;
      settled = true; clearTimeout(timer);
      options.signal?.removeEventListener('abort', abort);
      if (error) reject(error); else resolve(value);
    };
    const cancel = (code: number, message: string) => {
      finish(new NativeError(code, message));
      if (id !== undefined) { try { host.cefQueryCancel?.(id); } catch { /* Context already gone. */ } }
    };
    const abort = () => cancel(499, 'Request cancelled');
    const timer = setTimeout(() => cancel(408, 'Native request timed out'), timeoutMs);
    options.signal?.addEventListener('abort', abort, { once: true });
    try {
      id = host.cefQuery({ request: JSON.stringify({ version: 1, command, params }), persistent: false,
        onSuccess(text) {
          try {
            const reply: unknown = JSON.parse(text);
            if (!reply || typeof reply !== 'object' || (reply as { version?: unknown }).version !== 1 || !('result' in reply))
              throw new NativeError(502, 'Invalid native reply');
            finish(undefined, reply.result);
          } catch { finish(new NativeError(502, 'Invalid native reply')); }
        },
        onFailure: (code, message) => finish(new NativeError(code, message))
      });
    } catch { finish(new NativeError(503, 'Native bridge unavailable')); }
  });
}
