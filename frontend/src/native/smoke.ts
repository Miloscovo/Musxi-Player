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
export async function verifyVuePage() {
  const library = await native.library.getState();
  if (!Array.isArray(library.playlists) || library.connected) throw new Error('Unexpected offline library state');
  await waitFor(() => !!document.querySelector('.library-shell'));
  const state = await native.player.getState();
  if (!document.querySelector('#playback') || !document.querySelector('#volume')?.textContent?.includes(`${state.volumePercent}%`))
    throw new Error('Vue state was not rendered');
  const prefix = 'musxi-reload:';
  if (!window.name.startsWith(prefix)) {
    const host = window as unknown as CefTransport;
    for (const [command, params, expected] of [
      ['player.pause', {}, 409], ['player.resume', {}, 409], ['player.seek', { positionMs: 10 }, 409],
      ['player.setVolume', { volumePercent: 101 }, 400], ['player.seek', { positionMs: -1 }, 400],
      ['player.seek', { positionMs: 1.5 }, 400], ['player.pause', { bad: true }, 400]
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
      await native.player.setVolume(target);
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
