import type { LibraryState } from '../native/library.ts';

const storageKey = 'musxi-playlist-loading-counts';
function accountKey(state: LibraryState) {
  const accounts = state.accounts?.filter(account => account.connected);
  if (accounts?.some(account => !account.user)) return '';
  // shortcut: the bridge exposes display names only; use stable IDs if it gains them.
  return accounts?.length ? JSON.stringify(accounts.map(account => [account.platform, account.user]).sort()) : state.user;
}

export function readPlaylistLoadingCount(storage: Pick<Storage, 'getItem'>, state: LibraryState | null, section: 'created' | 'saved' | 'local') {
  try {
    const cached = JSON.parse(storage.getItem(storageKey) || 'null');
    if (!cached || (state && (!state.connected || accountKey(state) !== cached.account))) return 6;
    const count = cached[section];
    return Number.isInteger(count) && count >= 0 && count <= 10001 ? count : 6;
  } catch { return 6; }
}

export function rememberPlaylistLoadingCounts(storage: Pick<Storage, 'setItem'>, state: LibraryState, pending: boolean, error: string) {
  if (pending || error || state.busy || !state.connected || !accountKey(state)
    || state.operation.status === 'failed' || state.operation.status === 'cancelled'
    || (!state.playlists.length && !(state.operation.kind === 'sync' && state.operation.status === 'completed'))) return;
  const created = state.playlists.filter(list => list.editable).length;
  try {
    storage.setItem(storageKey, JSON.stringify({ account: accountKey(state), created: created + 1, saved: state.playlists.length - created }));
  } catch { /* Loading remains usable when storage is unavailable. */ }
}

function trackCountKey(state: LibraryState, id: string) {
  const account = id.startsWith('local-folder:') || id === 'musxi:local' ? 'local' : accountKey(state);
  return account ? `musxi-track-loading-count:${JSON.stringify([account, id])}` : null;
}

export function readTrackLoadingCount(storage: Pick<Storage, 'getItem'>, state: LibraryState | null, id: string) {
  if (!state || !id) return 8;
  try {
    const key = trackCountKey(state, id);
    const count = key ? JSON.parse(storage.getItem(key) || 'null') : null;
    return Number.isSafeInteger(count) && count >= 0 ? count : 8;
  } catch { return 8; }
}

export function rememberTrackLoadingCount(storage: Pick<Storage, 'setItem'>, state: LibraryState, pending: boolean, error: string) {
  if (pending || error || state.busy || !state.playlistId || state.operation.status === 'failed' || state.operation.status === 'cancelled'
    || !Number.isSafeInteger(state.trackCount) || state.trackCount < 0
    || (!state.tracks.length && !(state.trackCount === 0 && state.operation.kind === 'tracks' && state.operation.status === 'completed'))) return;
  const key = trackCountKey(state, state.playlistId);
  try { if (key) storage.setItem(key, String(state.trackCount)); } catch { /* Storage is optional. */ }
}
