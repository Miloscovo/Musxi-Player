import type { Lyrics, LyricsLine } from '../native/library.ts';

export const lyricScrollTransition = { duration: 0.48, ease: [0.22, 1, 0.36, 1] as [number, number, number, number] };

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

// CEF does not clip no-drag rectangles to their scroll container.
export function observeLyricDragRegions(container: HTMLOListElement) {
  const observer = new IntersectionObserver(entries => {
    for (const entry of entries)
      entry.target.classList.toggle('lyric-interactive', entry.isIntersecting && entry.intersectionRatio === 1);
  }, { root: container, threshold: 1 });
  for (const row of Array.from(container.children)) observer.observe(row);
  return () => {
    observer.disconnect();
    for (const row of Array.from(container.children)) row.classList.remove('lyric-interactive');
  };
}

// Preserve row identities across polling without delaying matching or translation updates.
export function reuseLyrics(previous: Lyrics | null, next: Lyrics): Lyrics {
  if (!previous || previous.id !== next.id || previous.lines.length !== next.lines.length
    || next.lines.some((line, i) => line.timeMs !== previous.lines[i].timeMs || line.text !== previous.lines[i].text || line.translation !== previous.lines[i].translation)) return next;
  if (JSON.stringify({ ...previous, lines: undefined }) === JSON.stringify({ ...next, lines: undefined })) return previous;
  return { ...next, lines: previous.lines };
}
