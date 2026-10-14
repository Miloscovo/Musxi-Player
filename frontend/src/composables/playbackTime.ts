export function formatPlaybackTime(ms: number): string {
  const seconds = Math.floor(Math.max(0, Number.isFinite(ms) ? ms : 0) / 1000);
  const minutes = Math.floor(seconds / 60), rest = String(seconds % 60).padStart(2, '0');
  return minutes >= 60 ? `${Math.floor(minutes / 60)}:${String(minutes % 60).padStart(2, '0')}:${rest}` : `${minutes}:${rest}`;
}
export function playbackTimes(opened: boolean, durationMs: number, positionMs: number) {
  if (!opened || !Number.isFinite(durationMs) || durationMs <= 0)
    return { elapsed: '--:--', total: '--:--', remaining: '--:--' };
  const duration = Math.floor(durationMs / 1000) * 1000;
  const elapsed = Math.min(duration, Math.floor(Math.max(0, Number.isFinite(positionMs) ? positionMs : 0) / 1000) * 1000);
  return { elapsed: formatPlaybackTime(elapsed), total: formatPlaybackTime(duration), remaining: formatPlaybackTime(duration - elapsed) };
}
