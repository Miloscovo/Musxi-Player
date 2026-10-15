import type { LibraryState, Track } from '../native/library.ts';

// Keep loaded pages together so a confirmed edit can replace, rather than append to, the list.
export class PlaylistPages {
  private pages = new Map<number, Track[]>();
  private previous: LibraryState | null = null;
  private dirty = false;

  async update(value: LibraryState, page: number, read: (page: number) => Promise<LibraryState>) {
    const same = this.previous?.playlistId === value.playlistId;
    const edited = value.operation.status === 'completed' && ['song_update', 'tracks'].includes(value.operation.kind)
      && (this.previous?.operation.id !== value.operation.id || this.previous?.operation.status !== 'completed');
    this.dirty = same && (this.dirty || edited || this.previous!.trackCount !== value.trackCount);
    const last = Math.max(1, Math.min(page, Math.ceil(value.trackCount / 50)));
    const pages = this.dirty ? new Map<number, Track[]>() : new Map(same ? this.pages : []);
    if (this.dirty) {
      for (let index = 1; index <= last; index++) {
        const snapshot = index === page ? value : await read(index);
        if (snapshot.playlistId !== value.playlistId || snapshot.trackCount !== value.trackCount
          || snapshot.operation.id !== value.operation.id || snapshot.operation.status !== value.operation.status) return null;
        pages.set(index, snapshot.tracks);
      }
    } else pages.set(page, value.tracks);
    const state = { ...value, tracks: [...pages].sort(([a], [b]) => a - b).flatMap(([, tracks]) => tracks) };
    this.pages = pages; this.previous = state; this.dirty = false;
    return { state, page: last };
  }
}
