import type { LyricsLine } from '../native/library.ts';

// Index of the line being sung at positionMs, or -1 before the first line.
// Native replies are sorted by timeMs, so a binary search is enough.
export function activeLyricLine(lines: readonly LyricsLine[], positionMs: number) {
  let low = 0, high = lines.length - 1, found = -1;
  while (low <= high) {
    const middle = (low + high) >> 1;
    if (lines[middle].timeMs <= positionMs) { found = middle; low = middle + 1; } else high = middle - 1;
  }
  return found;
}

// The Core only echoes source; any listed value works for the song that is playing.
export function lyricsSource(id: string) { return id.startsWith('local:') ? 'local' as const : 'queue' as const; }
