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

// Polls a shown result until its background translation lookup settles. Every
// ready reply is delivered (lines may gain translations); other songs' replies
// and a reply that reverts to pending end the refresh without touching the page.
// Resolves 'timeout' when this page gave up waiting, so it can say so without
// inventing a reason the Core never sent; request errors propagate.
export async function refreshTranslation(request: () => Promise<Lyrics>, id: string, signal: AbortSignal,
  wait: () => Promise<void>, deliver: (value: Lyrics) => void, now: () => number = Date.now, timeoutMs = 20000):
  Promise<'settled' | 'timeout' | 'stopped'> {
  const deadline = now() + timeoutMs;
  while (!signal.aborted) {
    if (now() >= deadline) return 'timeout';
    await wait();
    if (signal.aborted) break;
    if (now() >= deadline) return 'timeout';
    const value = await request();
    if (signal.aborted || value.id !== id || value.status !== 'ready') break;
    deliver(value);
    if (!value.translationPending) return 'settled';
  }
  return 'stopped';
}
