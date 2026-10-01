import { native } from './client';
import type { CefTransport } from './transport';
import { request } from './transport';
import type { PlayerEvent } from './events';

async function waitFor(check: () => boolean) {
  const end = Date.now() + 3000;
  while (!check()) {
    if (Date.now() > end) throw new Error('Event/UI update timed out');
    await new Promise(resolve => setTimeout(resolve, 20));
  }
}

// Invoked only by the real CEF --cef-smoke run (cloud startup is disabled).
export async function verifyWebPage() {
  const library = await native.library.getState();
  if (!Array.isArray(library.playlists) || library.connected) throw new Error('Unexpected offline library state');
  await waitFor(() => !!document.querySelector('.library-shell'));
  document.querySelector<HTMLButtonElement>('.settings-nav')?.click();
  await waitFor(() => document.querySelectorAll('.theme-option').length === 3);
  const cards = document.querySelectorAll<HTMLButtonElement>('.theme-option');
  // Color changes preserve transparency; normalize a persisted glass theme first.
  if (cards[2].getAttribute('aria-pressed') === 'true') {
    cards[2].click();
    await waitFor(() => cards[2].getAttribute('aria-pressed') === 'false');
  }
  cards[0].click();
  await waitFor(() => document.documentElement.dataset.theme === 'light');
  cards[2].click();
  await waitFor(() => document.documentElement.dataset.theme === 'glass-light' && cards[0].getAttribute('aria-pressed') === 'true' && cards[2].getAttribute('aria-pressed') === 'true');
  cards[1].click();
  await waitFor(() => document.documentElement.dataset.theme === 'glass' && cards[1].getAttribute('aria-pressed') === 'true');
  cards[2].click();
  await waitFor(() => document.documentElement.dataset.theme === 'dark');
  cards[0].click();
  await waitFor(() => document.documentElement.dataset.theme === 'light');
  const state = await native.player.getState();
  const windowState = await native.window.getState();
  if (windowState.enabled) {
    const slider = document.querySelector<HTMLInputElement>('.transparency-slider');
    if (!slider || slider.min !== '0' || slider.max !== '100') throw new Error('Missing transparency range');
    const saved = slider.value;
    cards[2].click();
    await waitFor(() => document.documentElement.dataset.theme === 'glass-light');
    const setRange = (value: number) => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(slider, String(value));
      slider.dispatchEvent(new Event('input', { bubbles: true }));
    };
    const pixel = (x: number, y: number) => request(window as unknown as CefTransport, 'test.pixelAlpha', { x, y });
    for (const themeCard of [cards[0], cards[1]]) {
      themeCard.click();
      await waitFor(() => document.documentElement.dataset.theme === (themeCard === cards[0] ? 'glass-light' : 'glass'));
      for (const value of [0, 50, 100]) {
        setRange(value);
        await waitFor(() => document.documentElement.style.getPropertyValue('--background-opacity') === String(1 - value / 100));
        const end = Date.now() + 3000; const expected = Math.round(255 * (1 - value / 100));
        while (Math.abs(Number(await pixel(window.innerWidth - 24, Math.round(window.innerHeight * .65))) - expected) > 1) {
          if (Date.now() > end) throw new Error(`Native background alpha did not reach ${expected}`);
          await new Promise(resolve => setTimeout(resolve, 30));
        }
        // Header and title bar must not compound the page's background alpha.
        for (const [x, y] of [[window.innerWidth - 60, 70], [window.innerWidth - 160, 16], [10, 16]]) {
          if (Math.abs(Number(await pixel(x, y)) - expected) > 1)
            throw new Error('Header transparency differs from page background');
        }
        // Preview swatches are opaque controls even when the page background is not.
        const preview = document.querySelector('.theme-swatch')!.getBoundingClientRect();
        if (await pixel(Math.round(preview.left + 10), Math.round(preview.top + 40)) !== 255)
          throw new Error('Transparency faded controls');
      }
    }
    setRange(Number(saved));
    cards[0].click();
    await waitFor(() => document.documentElement.dataset.theme === 'glass-light');
    cards[2].click();
    await waitFor(() => document.documentElement.dataset.theme === 'light');
    const maximized = await native.window.maximize();
    if (!maximized.maximized) throw new Error('Test window did not maximize');
    const restored = await native.window.maximize();
    if (restored.maximized) throw new Error('Test window did not restore');
    await native.window.setTheme('glass-light');
    await native.window.setTheme('glass');
    await native.window.setTheme('light');
  }
  if (!document.querySelector('#playback') || !document.querySelector('#volume')?.textContent?.includes(`${state.volumePercent}%`))
    throw new Error('React state was not rendered');
  const prefix = 'musxi-reload:';
  if (!window.name.startsWith(prefix)) {
    const host = window as unknown as CefTransport;
    for (const [command, params, expected] of [
      ['player.pause', {}, 409], ['player.resume', {}, 409], ['player.seek', { positionMs: 10 }, 409],
      ['player.setVolume', { volumePercent: 101 }, 400], ['player.seek', { positionMs: -1 }, 400],
      ['player.seek', { positionMs: 1.5 }, 400], ['player.pause', { bad: true }, 400]
      ,['window.setTheme', { theme: 'invalid' }, 400], ['window.close', { bad: true }, 400], ['window.unknown', {}, 404]
      ,['library.play', { source: 'library', id: 'missing' }, 409],
      ['library.image', { url: 'https://example.com/private' }, 403],
      ['library.getState', { page: 0 }, 400], ['library.unknown', {}, 404]
    ] as const) {
      let rejected = false;
      try { await request(host, command, params); }
      catch (e) { if ((e as { code: number }).code !== expected) throw e; rejected = true; }
      if (!rejected) throw new Error('Invalid command accepted');
    }
    const events: PlayerEvent[] = [];
    let subscriptionError: Error | undefined;
    const stop = native.player.subscribe(event => events.push(event), e => { subscriptionError = e; });
    try {
      await waitFor(() => events.length > 0 || !!subscriptionError);
      if (subscriptionError) throw subscriptionError;
      const target = state.volumePercent === 37 ? 38 : 37;
      const slider = document.querySelector<HTMLInputElement>('input[aria-label="音量"]');
      if (!slider) throw new Error('Volume control is missing');
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(slider, String(target));
      slider.dispatchEvent(new Event('input', { bubbles: true }));
      if ((await native.player.getState()).volumePercent !== state.volumePercent)
        throw new Error('Range input submitted before commit');
      slider.dispatchEvent(new Event('change', { bubbles: true }));
      await waitFor(() => events.some(e => e.event === 'player.volumeChanged' && e.state.volumePercent === target));
      await waitFor(() => document.querySelector('#volume')?.textContent === `${target}%`);
      stop();
      // A following query is processed after the cancellation IPC.
      await native.player.getState();
      const count = events.length;
      await native.player.setVolume(state.volumePercent);
      await waitFor(() => document.querySelector('#volume')?.textContent === `${state.volumePercent}%`);
      if (events.length !== count) throw new Error('Cancelled subscription received an event');
      await native.player.setVolume(target);
      await waitFor(() => document.querySelector('#volume')?.textContent === `${target}%`);
      window.name = prefix + JSON.stringify({ trackId: state.trackId, volumePercent: target,
        originalVolume: state.volumePercent, opened: state.opened });
    } catch (e) { await native.player.setVolume(state.volumePercent); throw e; }
    finally { stop(); }
    location.reload(); return;
  }
  const previous = JSON.parse(window.name.slice(prefix.length));
  window.name = '';
  if (previous.trackId !== state.trackId || previous.volumePercent !== state.volumePercent || previous.opened !== state.opened)
    throw new Error('Native state changed across reload');
  await native.player.setVolume(previous.originalVolume);
  await waitFor(() => document.querySelector('#volume')?.textContent === `${previous.originalVolume}%`);
  const host = window as unknown as CefTransport;
  // test.complete intentionally has a test-only, non-v1 reply in the existing fixture.
  await new Promise<void>((resolve, reject) => host.cefQuery?.({
    request: JSON.stringify({ version: 1, command: 'test.complete', params: {} }), persistent: false,
    onSuccess: () => resolve(), onFailure: (_code, message) => reject(new Error(message))
  }));
}
