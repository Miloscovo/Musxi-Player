import { native } from './client';
import { request, type CefTransport } from './transport';

// Runs in the desktop lyrics page of a --cef-desktop-smoke host and reports
// its DOM to native; the main page then reads the report plus window styles.
export function reportDesktopPage() {
  const host = window as unknown as CefTransport;
  const send = () => void request(host, 'test.desktopReport', {
    text: document.querySelector('.dl-lyrics')?.textContent ?? '',
    panel: !!document.querySelector('.dl-capsule'), unlock: !!document.querySelector('.dl-unlock'),
    color: document.querySelector<HTMLElement>('.desktop-lyrics')?.dataset.color ?? '',
    size: document.querySelector<HTMLElement>('.desktop-lyrics')?.style.getPropertyValue('--dl-size') ?? '',
    tone: document.documentElement.dataset.tone ?? '',
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
  const pushed: boolean[] = [];
  const stop = native.desktopLyrics.subscribe(state => pushed.push(state.panel), () => {});
  await native.desktopLyrics.setVisible(true);
  const view = await wait(value => value.painted && value.page?.text === 'Musxi Player', 'idle text');
  if (!view.noActivate || !view.toolWindow || !view.topmost || !view.transparent) throw new Error('Desktop window styles/alpha incorrect');
  if (view.page.panel) throw new Error('Desktop panel visible by default');
  await native.desktopLyrics.setPanel(true);
  await wait(value => value.page?.panel, 'panel');
  if (!pushed.includes(true)) throw new Error('Desktop state was not pushed to the main page');
  await native.desktopLyrics.setFontSize(40); await native.desktopLyrics.setColor('dark');
  await wait(value => value.page?.size === '40px' && value.page?.color === 'dark', 'font/color');
  await native.desktopLyrics.setLocked(true);
  await wait(value => value.clickThrough && !value.page?.panel, 'lock');
  if ((await native.desktopLyrics.getState()).panel) throw new Error('Locking kept the panel open');
  await native.desktopLyrics.setLocked(false);
  await wait(value => !value.clickThrough, 'unlock');
  localStorage.setItem('musxi-tone', 'blue');
  await wait(value => value.page?.tone === 'blue', 'tone sync');
  localStorage.removeItem('musxi-tone');
  await native.desktopLyrics.setVisible(false);
  await wait(value => !value.exists, 'close');
  await native.desktopLyrics.setVisible(true);
  await wait(value => value.painted && !value.page?.panel, 'reopen');
  stop();
  // Exit with the desktop still open also exercises shutdown ordering.
  await request(host, 'test.complete', {}).catch(() => {}); // Replies with a bare {} and then closes.
}
