import { useEffect, useState } from 'react';
import { native } from '../native/client';

interface CoverEntry {
  image: string; retryAt: number; users: number;
  decoded?: HTMLImageElement;
  promise?: Promise<string>; controller?: AbortController;
}
const covers = new Map<string, CoverEntry>();

function trimCovers() {
  let images = [...covers.values()].filter(entry => entry.image).length;
  for (const [url, entry] of covers) {
    if (entry.users || entry.promise) continue;
    if (covers.size > 256 || (entry.image && images > 32)) {
      covers.delete(url); if (entry.image) images--;
    }
  }
}

function loadCover(url: string, entry: CoverEntry): Promise<string> {
  if (entry.image || entry.retryAt > Date.now()) return Promise.resolve(entry.image);
  if (entry.promise) return entry.promise;
  const controller = new AbortController(); entry.controller = controller;
  entry.promise = (async () => {
    for (let attempt = 0; attempt < 8 && !controller.signal.aborted; attempt++) {
      try {
        const image = await native.library.image(url, controller.signal);
        if (image && !controller.signal.aborted) {
          const decoded = new Image(); decoded.src = image; await decoded.decode();
          if (controller.signal.aborted) return '';
          entry.image = image; entry.decoded = decoded; entry.retryAt = 0; return image;
        }
      } catch { /* Keep the placeholder and retry. */ }
      if (controller.signal.aborted) return '';
      if (attempt < 7) await new Promise<void>(resolve => {
        const done = () => { clearTimeout(timer); controller.signal.removeEventListener('abort', done); resolve(); };
        const timer = setTimeout(done, 1500);
        controller.signal.addEventListener('abort', done, { once: true });
        if (controller.signal.aborted) done();
      });
    }
    if (!controller.signal.aborted) entry.retryAt = Date.now() + 60000;
    return '';
  })().finally(() => {
    if (entry.controller === controller) { entry.promise = undefined; entry.controller = undefined; }
    trimCovers();
  });
  return entry.promise;
}

export function useCoverImage(url: string) {
  const [result, setResult] = useState({ url: '', image: '', loading: false });
  useEffect(() => {
    if (!url) return;
    const cached = covers.get(url);
    const loading = !cached || (!!cached.promise && !cached.retryAt);
    const entry = covers.get(url) || { image: '', retryAt: 0, users: 0 };
    covers.delete(url); covers.set(url, entry); entry.users++;
    let active = true;
    setResult({ url, image: entry.image, loading });
    void loadCover(url, entry).then(image => { if (active) setResult({ url, image, loading: false }); });
    trimCovers();
    return () => {
      active = false; entry.users--;
      if (!entry.users && entry.controller) {
        entry.controller.abort(); entry.controller = undefined; entry.promise = undefined;
      }
      trimCovers();
    };
  }, [url]);
  return {
    image: result.url === url ? result.image : covers.get(url)?.image || '',
    loading: !!url && (result.url === url ? result.loading : !covers.has(url) || (!!covers.get(url)?.promise && !covers.get(url)?.retryAt)),
  };
}
