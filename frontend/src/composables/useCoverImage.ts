import { useEffect, useState } from 'react';
import { native } from '../native/client';
import type { CoverSize } from '../native/library';

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

function loadCover(url: string, entry: CoverEntry, size: CoverSize): Promise<string> {
  if (entry.image || entry.retryAt > Date.now()) return Promise.resolve(entry.image);
  if (entry.promise) return entry.promise;
  const controller = new AbortController(); entry.controller = controller;
  entry.promise = (async () => {
    for (let attempt = 0; attempt < 8 && !controller.signal.aborted; attempt++) {
      try {
        const image = await native.library.image(url, controller.signal, size);
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

export function useCoverImage(url: string, size: CoverSize = 1200) {
  const key = JSON.stringify([url, size]);
  const [result, setResult] = useState({ key: '', image: '', loading: false });
  useEffect(() => {
    if (!url) return;
    const cached = covers.get(key);
    const loading = !cached || (!!cached.promise && !cached.retryAt);
    const entry = covers.get(key) || { image: '', retryAt: 0, users: 0 };
    covers.delete(key); covers.set(key, entry); entry.users++;
    let active = true;
    setResult({ key, image: entry.image, loading });
    void loadCover(url, entry, size).then(image => { if (active) setResult({ key, image, loading: false }); });
    trimCovers();
    return () => {
      active = false; entry.users--;
      if (!entry.users && entry.controller) {
        entry.controller.abort(); entry.controller = undefined; entry.promise = undefined;
      }
      trimCovers();
    };
  }, [url, key, size]);
  return {
    image: result.key === key ? result.image : covers.get(key)?.image || '',
    loading: !!url && (result.key === key ? result.loading : !covers.has(key) || (!!covers.get(key)?.promise && !covers.get(key)?.retryAt)),
  };
}
