import type { Lyrics } from '../native/library.ts';

// The native worker owns the 15 s network budget; allow time for IPC and cancellation.
export async function loadLyricsRequest(request: () => Promise<Lyrics>, id: string, signal: AbortSignal,
  wait: () => Promise<void>, now: () => number = Date.now, timeoutMs = 20000): Promise<Lyrics> {
  const deadline = now() + timeoutMs;
  while (!signal.aborted) {
    const value = await request();
    signal.throwIfAborted();
    if (value.id !== id) throw new Error('歌词响应不匹配');
    if (value.status === 'ready') return value;
    if (now() >= deadline) throw new Error('歌词请求超时');
    await wait();
  }
  signal.throwIfAborted();
  throw new Error('歌词请求已取消');
}
