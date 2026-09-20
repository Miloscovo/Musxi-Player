import { native } from './client';
import type { CefTransport } from './transport';

// Used only by the real CEF --cef-smoke run; no playback mutation is exposed.
export async function verifyVuePage() {
  const state = await native.player.getState();
  if (!document.querySelector('#playback') || !document.querySelector('#volume')?.textContent?.includes(`${state.volumePercent}%`))
    throw new Error('Vue state was not rendered');
  const prefix = 'musxi-reload:';
  if (!window.name.startsWith(prefix)) {
    window.name = prefix + JSON.stringify({ trackId: state.trackId, volumePercent: state.volumePercent, opened: state.opened });
    location.reload(); return;
  }
  const previous = JSON.parse(window.name.slice(prefix.length));
  window.name = '';
  if (previous.trackId !== state.trackId || previous.volumePercent !== state.volumePercent || previous.opened !== state.opened)
    throw new Error('Native state changed across reload');
  const host = window as unknown as CefTransport;
  // test.complete intentionally has a test-only, non-v1 reply in the existing fixture.
  await new Promise<void>((resolve, reject) => host.cefQuery?.({
    request: JSON.stringify({ version: 1, command: 'test.complete', params: {} }), persistent: false,
    onSuccess: () => resolve(), onFailure: (_code, message) => reject(new Error(message))
  }));
}
