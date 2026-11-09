import { native } from './client';
import { request, type CefTransport } from './transport';

// Runs in the desktop lyrics page of a --cef-desktop-smoke host and reports
// its DOM to native; the main page then reads the report plus window styles.
export function reportDesktopPage() {
  const host = window as unknown as CefTransport;
  // Exercise the native minimum width even when the smoke host starts wider.
  const root = document.querySelector<HTMLElement>('.game-overlay');
  const controlsFit = () => {
    const width = root?.style.width ?? '';
    if (root) root.style.width = '480px';
    const bounds = root?.getBoundingClientRect();
    const fits = Array.from(document.querySelectorAll<HTMLElement>('.dl-capsule button')).every(button => {
      const box = button.getBoundingClientRect();
      return !!bounds && box.left >= bounds.left && box.right <= bounds.right;
    });
    if (root) root.style.width = width;
    return fits;
  };
  const send = () => void request(host, 'test.desktopReport', {
    text: document.querySelector('.overlay-message,.overlay-lyrics-list')?.textContent ?? '',
    panel: !!document.querySelector('.dl-capsule'), unlock: !!document.querySelector('.dl-unlock'),
    color: document.querySelector<HTMLElement>('.overlay-lyrics-viewport')?.dataset.style ?? '',
    size: document.querySelector<HTMLElement>('.overlay-lyrics-list')?.style.getPropertyValue('--overlay-lyric-size') ?? '',
    tone: document.documentElement.dataset.tone ?? '',
    controlsFit: controlsFit(),
  }).catch(() => {});
  send(); const timer = setInterval(send, 100);
  return () => clearInterval(timer);
}

export async function verifyDesktopWindow() {
  const host = window as unknown as CefTransport;
  const probe = () => request(host, 'test.desktop', {}) as Promise<Record<string, any>>;
  const wait = async (check: (value: Record<string, any>) => boolean, what: string) => {
    const end = Date.now() + 5000; let last: Record<string, any> = {};
    while (Date.now() < end) {
      last = await probe(); if (check(last)) return last;
      await new Promise(resolve => setTimeout(resolve, 40));
    }
    throw new Error(`Desktop check timed out (${what}): ${JSON.stringify(last)}`);
  };
  const stop = native.desktopLyrics.subscribe(() => {}, () => {});
  await native.desktopLyrics.setVisible(true);
  const view = await wait(value => value.painted && value.page?.text === '尚未播放歌曲', 'idle text');
  if (!view.noActivate || !view.toolWindow || !view.topmost || !view.transparent) throw new Error('Desktop window styles/alpha incorrect');
  if ((await native.gameOverlay.getState()).editing) throw new Error('Lyrics unexpectedly entered manual editing');
  await native.desktopLyrics.setLocked(false);
  try {
    await native.desktopLyrics.setPanel(true);
    throw new Error('Obsolete toolbar toggle unexpectedly succeeded');
  } catch (error) { if (!(error && typeof error === 'object' && 'code' in error && error.code === 409)) throw error; }
  await native.desktopLyrics.setFontSize(40);
  if ((await native.gameOverlay.getState()).lyricsFontSize !== 40) throw new Error('Legacy font setter did not update unified state');
  await native.desktopLyrics.setLocked(true);
  await wait(value => value.clickThrough && !value.page?.panel, 'lock');
  if ((await native.desktopLyrics.getState()).panel) throw new Error('Locking kept the panel open');
  await native.desktopLyrics.setLocked(false);
  await wait(value => !value.clickThrough, 'unlock');
  localStorage.setItem('musxi-tone', 'blue');
  await wait(value => value.page?.tone === 'blue', 'tone sync');
  localStorage.removeItem('musxi-tone');
  await native.desktopLyrics.setVisible(false);
  await wait(value => !value.visible, 'close');
  await native.desktopLyrics.setVisible(true);
  await wait(value => value.painted && !value.page?.panel, 'reopen');
  stop();
  // Exit with the desktop still open also exercises shutdown ordering.
  await request(host, 'test.complete', {}).catch(() => {}); // Replies with a bare {} and then closes.
}
